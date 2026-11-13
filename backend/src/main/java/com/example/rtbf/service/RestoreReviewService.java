package com.example.rtbf.service;

import com.example.rtbf.util.HashUtil;
import com.example.rtbf.web.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.Principal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 备份恢复后的删除复查。
 * 关键约束：
 *  - 恢复内容先落 quarantine 隔离区，所有位置通过检查并登记资产血缘后才能提交开放申请；
 *    不做“直接恢复线上再补删”（恢复与补删之间会暴露旧简历）
 *  - 复查基准是当前仍有效的删除凭证：每次扫描实时提取主体指纹与处置结果，
 *    检查期间新办结的注销申请会追加进本次扫描；开放前再次核对，不只查启动名单
 *  - 备份缺主体关联字段 → 位置停 PENDING_VERIFY；文件目录与清单不一致 → 列缺件/多件，补齐后重检
 *  - 独立批次号；重试只接着处理未完成位置，已核验数据不重复释放
 *  - 残留、处理动作、放行依据作为新记录附在原删除工单后，不更换当年证据说明
 */
@Service
public class RestoreReviewService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate txTemplate;

    public RestoreReviewService(NamedParameterJdbcTemplate jdbc, AuditService audit,
                                ObjectMapper objectMapper,
                                org.springframework.transaction.PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.objectMapper = objectMapper;
        // 每个隔离位置独立事务：单位置失败只回滚自身，其余位置照常提交，重试接着处理未完成位置
        this.txTemplate = new TransactionTemplate(txManager);
        this.txTemplate.setPropagationBehavior(
            org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public record RegisterBatchRequest(LocalDate backupDate, String sourceRef,
                                       List<String> involvedTables, List<String> fileDirectories,
                                       List<String> manifestFiles) {
    }

    public record ManualDecisionRequest(String decision, String note) {
    }

    public record LinkageRequest(Map<String, String> linkage, String assetCode) {
    }

    public record DiffResolveRequest(String note) {
    }

    public record OpenRequest(String basis) {
    }

    public record OpenDecisionRequest(String note) {
    }

    /** 隔离区表 → 复查取数 SQL 与条目类型的映射；item_ref 唯一标识恢复条目 */
    private record ScanSpec(String quarantineTable, String itemKind, boolean accountFingerprint) {
    }

    private static final Map<String, ScanSpec> SCAN_SPECS = Map.of(
        "quarantine.q_account", new ScanSpec("quarantine.q_account", "ACCOUNT", true),
        "quarantine.q_resume", new ScanSpec("quarantine.q_resume", "DERIVED", false),
        "quarantine.q_search_index_doc", new ScanSpec("quarantine.q_search_index_doc", "DERIVED", false),
        "quarantine.q_backup_file", new ScanSpec("quarantine.q_backup_file", "ATTACHMENT", false),
        "quarantine.q_legacy_dump", new ScanSpec("quarantine.q_legacy_dump", "DERIVED", false)
    );

    /** 复查通过后按列复制上线的目标表、列清单与自然键存在性判断 */
    private record ReleaseTarget(String onlineTable, List<String> columns,
                                 String sourceWhere, String existsWhere) {
    }

    private static final Map<String, ReleaseTarget> RELEASE_TARGETS = Map.of(
        "quarantine.q_account", new ReleaseTarget("biz.account",
            List.of("account_id", "subject_hash", "phone_hash", "email_hash", "idcard_hash", "name"),
            "q.account_id = :ref", "o.account_id = q.account_id"),
        "quarantine.q_resume", new ReleaseTarget("biz.resume",
            List.of("account_id", "subject_hash", "version_no", "content_pii"),
            "('resume#' || q.resume_id) = :ref", "o.resume_id = q.resume_id"),
        "quarantine.q_search_index_doc", new ReleaseTarget("biz.search_index_doc",
            List.of("subject_hash", "index_name", "doc_ref"),
            "('doc#' || q.doc_id) = :ref", "o.doc_id = q.doc_id"),
        "quarantine.q_backup_file", new ReleaseTarget("biz.resume_pdf",
            List.of("subject_hash", "object_key", "bucket"),
            "q.object_key = :ref", "o.object_key = q.object_key")
    );

    private record Credential(long id, long ticketId, String subjectHash, String assetCode,
                              String status, String recheckHash, String fingerprint) {
    }

    // ------------------------------------------------------------------
    // 1. 管理员登记恢复批次（备份日期、涉及表、文件目录、备份清单）
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> registerBatch(RegisterBatchRequest req, Principal principal) {
        if (req.backupDate() == null) {
            throw ApiException.badRequest("必须登记备份日期");
        }
        List<Map<String, Object>> catalog = jdbc.queryForList(
            "SELECT * FROM quarantine.backup_catalog ORDER BY id", Map.of());
        List<String> wantedTables = req.involvedTables() == null || req.involvedTables().isEmpty()
            ? catalog.stream().filter(c -> "TABLE".equals(c.get("source_type")))
                .map(c -> (String) c.get("target_ref")).toList()
            : req.involvedTables();
        List<String> wantedDirs = req.fileDirectories() == null || req.fileDirectories().isEmpty()
            ? catalog.stream().filter(c -> "FILE".equals(c.get("source_type")))
                .map(c -> (String) c.get("target_ref")).toList()
            : req.fileDirectories();

        String batchNo = "RSTR-" + LocalDate.now().toString().replace("-", "")
                         + "-" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        Long batchId = jdbc.queryForObject("""
            INSERT INTO compliance.restore_batch
              (batch_no, backup_date, source_ref, involved_tables, file_directories, manifest_files,
               status, created_by)
            VALUES (:no, :bd, :ref, CAST(:t AS jsonb), CAST(:d AS jsonb), CAST(:m AS jsonb),
                    'REGISTERED', :by)
            RETURNING id
            """, new MapSqlParameterSource()
            .addValue("no", batchNo).addValue("bd", req.backupDate()).addValue("ref", req.sourceRef())
            .addValue("t", toJson(wantedTables)).addValue("d", toJson(wantedDirs))
            .addValue("m", toJson(req.manifestFiles() == null ? List.of() : req.manifestFiles()))
            .addValue("by", principal.getName()), Long.class);

        int locations = 0;
        for (String ref : wantedTables) {
            locations += createLocation(batchId, catalog, ref);
        }
        for (String ref : wantedDirs) {
            // 目录实际清单是比对来源，不是可恢复位置；只把附件登记表建成 FILE 位置
            if (SCAN_SPECS.containsKey(ref)) {
                locations += createLocation(batchId, catalog, ref);
            }
        }
        audit.log(null, principal.getName(), "RESTORE_BATCH_REGISTERED",
                  Map.of("batchId", batchId, "batchNo", batchNo, "backupDate", req.backupDate(),
                         "involvedTables", wantedTables, "fileDirectories", wantedDirs,
                         "manifestFiles", req.manifestFiles() == null ? List.of() : req.manifestFiles()));
        return Map.of("batchId", batchId, "batchNo", batchNo, "status", "REGISTERED",
                      "locationCount", locations);
    }

    private int createLocation(long batchId, List<Map<String, Object>> catalog, String targetRef) {
        Map<String, Object> entry = catalog.stream()
            .filter(c -> targetRef.equals(c.get("target_ref"))).findFirst()
            .orElseThrow(() -> ApiException.badRequest("备份来源未登记，无法建立隔离位置: " + targetRef));
        if (!SCAN_SPECS.containsKey(targetRef)) {
            return 0;
        }
        String subjectColumn = (String) entry.get("subject_column");
        boolean linkable = subjectColumn != null && !subjectColumn.isBlank();
        jdbc.update("""
            INSERT INTO compliance.restore_location
              (batch_id, location_type, asset_code, target_ref, quarantine_ref,
               subject_linkable, missing_field, check_status)
            VALUES (:bid, :type, :code, :ref, :ref, :link, :miss,
                    CASE WHEN :link THEN 'PENDING' ELSE 'PENDING_VERIFY' END)
            ON CONFLICT (batch_id, quarantine_ref) DO NOTHING
            """, new MapSqlParameterSource()
            .addValue("bid", batchId).addValue("type", entry.get("source_type"))
            .addValue("code", entry.get("asset_code")).addValue("ref", targetRef)
            .addValue("link", linkable)
            .addValue("miss", linkable ? null : "subject_hash（备份缺少主体关联字段）"));
        return 1;
    }

    public List<Map<String, Object>> listCatalog() {
        return jdbc.queryForList("SELECT * FROM quarantine.backup_catalog ORDER BY id", Map.of());
    }

    public List<Map<String, Object>> listBatches() {
        return jdbc.queryForList("""
            SELECT id, batch_no, backup_date, source_ref, status, credential_count,
                   last_scan_at, reopen_check_at, opened_at, created_by, created_at
            FROM compliance.restore_batch ORDER BY id DESC
            """, Map.of());
    }

    public Map<String, Object> getBatch(long batchId) {
        Map<String, Object> batch = jdbc.query("""
            SELECT * FROM compliance.restore_batch WHERE id = :id
            """, new MapSqlParameterSource("id", batchId), rs -> rs.next() ? rsRow(rs) : null);
        if (batch == null) {
            throw ApiException.notFound("恢复批次不存在: " + batchId);
        }
        batch.put("locations", jdbc.query("""
            SELECT * FROM compliance.restore_location WHERE batch_id = :id ORDER BY id
            """, new MapSqlParameterSource("id", batchId), (rs, i) -> rsRow(rs)));
        batch.put("reviewItems", jdbc.queryForList("""
            SELECT * FROM compliance.restore_review_item WHERE batch_id = :id ORDER BY id
            """, new MapSqlParameterSource("id", batchId)));
        batch.put("fileDiffs", jdbc.queryForList("""
            SELECT * FROM compliance.restore_file_diff WHERE batch_id = :id ORDER BY id
            """, new MapSqlParameterSource("id", batchId)));
        batch.put("openRequest", jdbc.queryForList("""
            SELECT * FROM compliance.restore_open_request WHERE batch_id = :id ORDER BY id DESC
            """, new MapSqlParameterSource("id", batchId)));
        batch.put("residualRecords", jdbc.queryForList("""
            SELECT * FROM compliance.restore_residual_record WHERE batch_id = :id ORDER BY id
            """, new MapSqlParameterSource("id", batchId)));
        return batch;
    }

    // ------------------------------------------------------------------
    // 2. 恢复进隔离区（演示环境数据已在 quarantine 表中，这里做装载确认与文件清单比对）
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> restoreToQuarantine(long batchId, Principal principal) {
        long id = requireBatch(batchId);
        String status = batchStatus(id);
        if (!"REGISTERED".equals(status) && !"BLOCKED".equals(status)) {
            throw ApiException.conflict("批次当前状态 " + status + " 不允许恢复装载");
        }
        List<Map<String, Object>> locations = locationsOf(id);
        for (Map<String, Object> loc : locations) {
            String ref = (String) loc.get("quarantine_ref");
            ScanSpec spec = SCAN_SPECS.get(ref);
            if (spec == null) {
                continue;
            }
            Integer cnt = jdbc.queryForObject("SELECT count(*) FROM " + spec.quarantineTable(),
                                              Map.of(), Integer.class);
            jdbc.update("UPDATE compliance.restore_location SET restored_count = :c, updated_at = now() WHERE id = :lid",
                        new MapSqlParameterSource().addValue("c", cnt).addValue("lid", loc.get("id")));
        }
        jdbc.update("UPDATE compliance.restore_batch SET status = 'RESTORED', updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource("id", id));
        audit.log(null, principal.getName(), "RESTORE_LOADED_TO_QUARANTINE",
                  Map.of("batchId", id, "note", "恢复内容仅进入隔离区，未触碰线上 biz 表"));
        return Map.of("batchId", id, "status", "RESTORED", "quarantineOnly", true);
    }

    // ------------------------------------------------------------------
    // 3. 隔离复查扫描（可安全重试：只处理未完成位置，已判定/已释放条目不重放）
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> runScan(long batchId, Principal principal) {
        long id = requireBatch(batchId);
        String status = batchStatus(id);
        if ("REGISTERED".equals(status)) {
            throw ApiException.conflict("恢复内容尚未装载进隔离区，请先执行恢复装载");
        }
        if ("OPEN_APPLIED".equals(status) || "OPENED".equals(status)) {
            throw ApiException.conflict("批次已进入开放流程，不能再扫描");
        }
        jdbc.update("UPDATE compliance.restore_batch SET status = 'SCANNING', updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource("id", id));

        // 3.1 每次扫描都从“当前仍有效”的删除记录重新提取凭证——检查期间新办结的注销会被追加
        List<Credential> credentials = refreshCredentials();
        Map<String, List<Credential>> bySubject = new LinkedHashMap<>();
        for (Credential c : credentials) {
            bySubject.computeIfAbsent(c.subjectHash(), k -> new ArrayList<>()).add(c);
        }
        int nextRound = jdbc.queryForObject("""
            SELECT COALESCE(max(scan_round), 0) + 1 FROM compliance.restore_review_item WHERE batch_id = :id
            """, new MapSqlParameterSource("id", id), Integer.class);
        String setFingerprint = credentialSetFingerprint(credentials);

        int scanned = 0, suppressed = 0, clean = 0, manual = 0;
        List<Map<String, Object>> locations = locationsOf(id);
        // 主体 → 已登记标识符哈希集合：账号条目据此核验其标识符确实归属于该主体
        Map<String, java.util.Set<String>> subjectIdents = loadSubjectIdentifiers(bySubject.keySet());
        for (Map<String, Object> loc : locations) {
            long locId = ((Number) loc.get("id")).longValue();
            String locStatus = (String) loc.get("check_status");
            if ("RELEASED".equals(locStatus) || "EXCLUDED".equals(locStatus)) {
                continue; // 重试不重复释放已核验位置
            }
            String ref = (String) loc.get("quarantine_ref");
            if (Boolean.FALSE.equals(loc.get("subject_linkable"))) {
                jdbc.update("""
                    UPDATE compliance.restore_location SET check_status = 'PENDING_VERIFY', updated_at = now()
                     WHERE id = :id
                    """, new MapSqlParameterSource("id", locId));
                continue;
            }
            if ("FILE".equals(loc.get("location_type"))) {
                try {
                    reconcileFileDiffs(id, locId);
                    Long openDiffs = jdbc.queryForObject(
                        "SELECT count(*) FROM compliance.restore_file_diff WHERE batch_id = :b AND status = 'OPEN'",
                        new MapSqlParameterSource("b", id), Long.class);
                    if (openDiffs != null && openDiffs > 0) {
                        jdbc.update("""
                            UPDATE compliance.restore_location SET check_status = 'BLOCKED',
                                   last_error = :err, updated_at = now() WHERE id = :id
                            """, new MapSqlParameterSource().addValue("id", locId)
                                .addValue("err", "文件目录与备份清单不一致，缺件/多件未补齐"));
                        continue;
                    }
                } catch (Exception e) {
                    markLocationFailed(locId, e);
                    audit.log(null, principal.getName(), "RESTORE_SCAN_LOCATION_FAILED",
                              Map.of("batchId", id, "locationId", locId, "error", e.getMessage()));
                    continue;
                }
            }
            ScanSpec spec = SCAN_SPECS.get(ref);
            if (spec == null) {
                continue;
            }
            final Map<String, Object> locFinal = loc;
            try {
                Map<String, Object> counters = txTemplate.execute(txStatus ->
                    scanOneLocation(id, locId, locFinal, spec, bySubject, subjectIdents, nextRound));
                scanned += (int) counters.get("scanned");
                suppressed += (int) counters.get("suppressed");
                clean += (int) counters.get("clean");
                manual += (int) counters.get("manual");
            } catch (Exception e) {
                // 单位置独立事务已回滚，外层批次事务不受污染；记录错误保留 PENDING 供重试接着处理
                markLocationFailed(locId, e);
                audit.log(null, principal.getName(), "RESTORE_SCAN_LOCATION_FAILED",
                          Map.of("batchId", id, "locationId", locId, "error", e.getMessage()));
            }
        }

        refreshLocationCounters(id);
        Long pendingManual = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.restore_review_item WHERE batch_id = :b AND review_status = 'PENDING'",
            new MapSqlParameterSource("b", id), Long.class);
        Long blocked = jdbc.queryForObject("""
            SELECT count(*) FROM compliance.restore_location
             WHERE batch_id = :b AND check_status IN ('PENDING_VERIFY','BLOCKED','PENDING')
            """, new MapSqlParameterSource("b", id), Long.class);
        String nextStatus = (pendingManual != null && pendingManual > 0) ? "PENDING_HUMAN"
                            : (blocked != null && blocked > 0) ? "BLOCKED" : "READY_TO_OPEN";
        jdbc.update("""
            UPDATE compliance.restore_batch SET status = :st, credential_count = :cc,
                   credential_fingerprint = :fp, last_scan_at = now(), updated_at = now() WHERE id = :id
            """, new MapSqlParameterSource().addValue("st", nextStatus).addValue("cc", credentials.size())
                .addValue("fp", setFingerprint).addValue("id", id));
        audit.log(null, principal.getName(), "RESTORE_SCAN_DONE",
                  Map.of("batchId", id, "scanRound", nextRound, "credentials", credentials.size(),
                         "scanned", scanned, "suppressed", suppressed, "clean", clean,
                         "manualPending", manual, "nextStatus", nextStatus));
        return Map.of("batchId", id, "scanRound", nextRound, "credentialCount", credentials.size(),
                      "credentialFingerprint", setFingerprint, "scanned", scanned,
                      "suppressed", suppressed, "clean", clean, "manualPending", manual,
                      "nextStatus", nextStatus);
    }

    private Map<String, Object> scanOneLocation(long batchId, long locId, Map<String, Object> loc,
                                                ScanSpec spec, Map<String, List<Credential>> bySubject,
                                                Map<String, java.util.Set<String>> subjectIdents,
                                                int scanRound) {
        jdbc.update("UPDATE compliance.restore_location SET check_status = 'SCANNING', updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource("id", locId));
        Map<String, Object> counters = scanLocation(batchId, locId, loc, spec, bySubject,
                                                    subjectIdents, scanRound);
        jdbc.update("""
            UPDATE compliance.restore_location SET check_status = 'CHECKED', last_error = NULL,
                   checked_at = now(), updated_at = now() WHERE id = :id
            """, new MapSqlParameterSource("id", locId));
        return counters;
    }

    private void markLocationFailed(long locId, Exception e) {
        String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
        if (msg.length() > 500) {
            msg = msg.substring(0, 500);
        }
        jdbc.update("""
            UPDATE compliance.restore_location SET check_status = 'PENDING', last_error = :err,
                   updated_at = now() WHERE id = :id
            """, new MapSqlParameterSource().addValue("id", locId).addValue("err", msg));
    }

    private Map<String, Object> scanLocation(long batchId, long locId, Map<String, Object> loc,
                                             ScanSpec spec, Map<String, List<Credential>> bySubject,
                                             Map<String, java.util.Set<String>> subjectIdents,
                                             int scanRound) {
        int scanned = 0, suppressed = 0, clean = 0, manual = 0;
        List<Map<String, Object>> rows = jdbc.queryForList(
            "SELECT * FROM " + spec.quarantineTable(), Map.of());
        Map<String, String> linkage = loadLinkage(loc);
        for (Map<String, Object> row : rows) {
            String itemRef = itemRefOf(spec.quarantineTable(), row);
            String subjectHash = row.get("subject_hash") == null
                ? linkage.get(itemRef) : (String) row.get("subject_hash");
            if (subjectHash == null) {
                throw ApiException.conflict("位置 " + spec.quarantineTable()
                    + " 缺少主体关联且未补齐映射，条目 " + itemRef);
            }
            // 账号条目：核验备份中的标识符哈希确实归属于备份标注的主体（归并后主体哈希由多账号
            // 标识符联合算出，单账号部分标识符无法重算出同一主体哈希，因此做集合归属校验而非重算相等）
            String rowFingerprint = subjectHash;
            boolean fingerprintMismatch = false;
            String recomputed = null;
            if (spec.accountFingerprint()) {
                List<String> idents = new ArrayList<>();
                for (String col : List.of("phone_hash", "email_hash", "idcard_hash")) {
                    if (row.get(col) != null) {
                        idents.add((String) row.get(col));
                    }
                }
                java.util.Set<String> known = subjectIdents.getOrDefault(subjectHash, java.util.Set.of());
                if (!idents.isEmpty() && idents.stream().noneMatch(known::contains)) {
                    fingerprintMismatch = true;
                    recomputed = HashUtil.subjectHash(idents);
                }
            }
            String verdict;
            String reason = null;
            Credential matched = null;
            List<Credential> creds = bySubject.get(subjectHash);
            if (creds == null || creds.isEmpty()) {
                verdict = "CLEAN";
            } else if (spec.accountFingerprint() && fingerprintMismatch) {
                verdict = "MANUAL";
                reason = "账号标识符均不在该主体已确认标识符集合内，需人工核验（重算指纹 " + recomputed + "）";
                matched = creds.get(0);
            } else {
                String assetCode = (String) loc.get("asset_code");
                List<Credential> sameAsset = creds.stream().filter(c -> assetCode != null && assetCode.equals(c.assetCode())).toList();
                if (sameAsset.isEmpty()) {
                    verdict = "MANUAL";
                    reason = "主体存在有效注销凭证，但该恢复位置无对应处置结果，无法自动判定";
                    matched = creds.get(0);
                } else {
                    matched = sameAsset.get(0);
                    verdict = switch (matched.status()) {
                        case "EXECUTED", "RECEIPT_RECEIVED", "SCHEDULED", "WAITING_RECEIPT" -> "SUPPRESSED";
                        default -> "MANUAL";
                    };
                    if ("MANUAL".equals(verdict)) {
                        reason = "处置状态 " + matched.status() + " 不支持自动拦截";
                    }
                }
            }
            String reviewStatus = "MANUAL".equals(verdict) ? "PENDING" : "AUTO_DONE";
            int n = jdbc.update("""
                INSERT INTO compliance.restore_review_item
                  (batch_id, location_id, item_ref, item_kind, subject_hash, subject_fingerprint,
                   credential_id, original_ticket_id, disposition_snapshot, verdict, review_status,
                   manual_reason, scan_round)
                VALUES (:bid, :lid, :ref, :kind, :sh, :fp, :cid, :tid, :disp, :v, :rs, :reason, :round)
                ON CONFLICT (location_id, item_ref) DO UPDATE SET
                   subject_hash = EXCLUDED.subject_hash,
                   subject_fingerprint = EXCLUDED.subject_fingerprint,
                   credential_id = EXCLUDED.credential_id,
                   original_ticket_id = EXCLUDED.original_ticket_id,
                   disposition_snapshot = EXCLUDED.disposition_snapshot,
                   verdict = EXCLUDED.verdict,
                   review_status = CASE WHEN restore_review_item.review_status = 'PENDING'
                                        THEN EXCLUDED.review_status
                                        ELSE restore_review_item.review_status END,
                   manual_reason = EXCLUDED.manual_reason,
                   scan_round = GREATEST(restore_review_item.scan_round, EXCLUDED.scan_round),
                   updated_at = now()
                 WHERE restore_review_item.review_status = 'PENDING'
                    OR restore_review_item.credential_id IS DISTINCT FROM EXCLUDED.credential_id
                """, new MapSqlParameterSource()
                .addValue("bid", batchId).addValue("lid", locId).addValue("ref", itemRef)
                .addValue("kind", spec.itemKind()).addValue("sh", subjectHash).addValue("fp", rowFingerprint)
                .addValue("cid", matched == null ? null : matched.id())
                .addValue("tid", matched == null ? null : matched.ticketId())
                .addValue("disp", matched == null ? null : matched.status())
                .addValue("v", verdict).addValue("rs", reviewStatus).addValue("reason", reason)
                .addValue("round", scanRound));
            if (n > 0) {
                scanned++;
                switch (verdict) {
                    case "SUPPRESSED" -> suppressed++;
                    case "CLEAN" -> clean++;
                    default -> manual++;
                }
            }
        }
        return Map.of("scanned", scanned, "suppressed", suppressed, "clean", clean, "manual", manual);
    }

    private String itemRefOf(String table, Map<String, Object> row) {
        return switch (table) {
            case "quarantine.q_account" -> (String) row.get("account_id");
            case "quarantine.q_resume" -> "resume#" + row.get("resume_id");
            case "quarantine.q_search_index_doc" -> "doc#" + row.get("doc_id");
            case "quarantine.q_backup_file" -> (String) row.get("object_key");
            case "quarantine.q_legacy_dump" -> "dump#" + row.get("dump_id");
            default -> throw new IllegalArgumentException("未知隔离表: " + table);
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> loadLinkage(Map<String, Object> loc) {
        Object v = loc.get("linkage_json");
        if (v == null) {
            return Map.of();
        }
        if (v instanceof String s) {
            try {
                return objectMapper.readValue(s, new TypeReference<Map<String, String>>() {
                });
            } catch (Exception e) {
                throw new IllegalStateException("补齐映射 JSON 解析失败", e);
            }
        }
        return objectMapper.convertValue(v, new TypeReference<Map<String, String>>() {
        });
    }

    private void refreshLocationCounters(long batchId) {
        List<Map<String, Object>> locations = locationsOf(batchId);
        for (Map<String, Object> loc : locations) {
            long locId = ((Number) loc.get("id")).longValue();
            Map<String, Object> c = jdbc.queryForObject("""
                SELECT count(*) AS total,
                       sum(CASE WHEN verdict = 'SUPPRESSED' THEN 1 ELSE 0 END) AS sup,
                       sum(CASE WHEN verdict = 'CLEAN' THEN 1 ELSE 0 END) AS clean,
                       sum(CASE WHEN verdict = 'MANUAL' THEN 1 ELSE 0 END) AS man,
                       sum(CASE WHEN review_status = 'PENDING' THEN 1 ELSE 0 END) AS pending
                FROM compliance.restore_review_item WHERE location_id = :id
                """, new MapSqlParameterSource("id", locId), (rs, i) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("total", rs.getInt("total"));
                m.put("sup", rs.getInt("sup"));
                m.put("clean", rs.getInt("clean"));
                m.put("man", rs.getInt("man"));
                m.put("pending", rs.getInt("pending"));
                return m;
            });
            jdbc.update("""
                UPDATE compliance.restore_location
                   SET residual_count = :man, suppressed_count = :sup, clean_count = :clean,
                       manual_pending_count = :pending, updated_at = now()
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("man", c.get("man")).addValue("sup", c.get("sup"))
                .addValue("clean", c.get("clean")).addValue("pending", c.get("pending"))
                .addValue("id", locId));
        }
    }

    // ------------------------------------------------------------------
    // 4. 删除凭证：从当前仍有效的删除记录提取（长期保留可比对指纹）
    // ------------------------------------------------------------------
    private Map<String, java.util.Set<String>> loadSubjectIdentifiers(java.util.Set<String> subjectHashes) {
        if (subjectHashes.isEmpty()) {
            return Map.of();
        }
        Map<String, java.util.Set<String>> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT subject_hash, id_hash FROM compliance.identifier_match
             WHERE subject_hash IN (:hashes)
            """, new MapSqlParameterSource("hashes", subjectHashes));
        for (Map<String, Object> r : rows) {
            out.computeIfAbsent((String) r.get("subject_hash"), k -> new java.util.HashSet<>())
               .add((String) r.get("id_hash"));
        }
        return out;
    }

    private List<Credential> refreshCredentials() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT d.id AS disposition_id, d.ticket_id, w.subject_hash, d.asset_code,
                   d.category, d.status AS disposition_status, d.recheck_hash, d.executed_at
            FROM compliance.disposition_record d
            JOIN compliance.work_order w ON w.id = d.ticket_id
            WHERE w.status <> 'CANCELLED'
              AND d.status IN ('EXECUTED','RECEIPT_RECEIVED','SCHEDULED','WAITING_RECEIPT')
            ORDER BY d.id
            """, Map.of());
        List<Credential> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            String subjectHash = (String) r.get("subject_hash");
            String fp = HashUtil.sha256(r.get("ticket_id") + "|" + r.get("asset_code") + "|"
                + r.get("disposition_status") + "|" + r.get("recheck_hash"));
            Long id = jdbc.queryForObject("""
                INSERT INTO compliance.deletion_credential
                  (ticket_id, subject_hash, asset_code, disposition_id, disposition_category,
                   disposition_status, recheck_hash, subject_fingerprint, credential_fingerprint, executed_at)
                VALUES (:tid, :sh, :code, :did, :cat, :st, :rh, :sh, :fp, :at)
                ON CONFLICT (disposition_id) DO UPDATE SET
                   disposition_status = EXCLUDED.disposition_status,
                   recheck_hash = EXCLUDED.recheck_hash,
                   credential_fingerprint = EXCLUDED.credential_fingerprint
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("tid", r.get("ticket_id")).addValue("sh", subjectHash)
                .addValue("code", r.get("asset_code")).addValue("did", r.get("disposition_id"))
                .addValue("cat", r.get("category")).addValue("st", r.get("disposition_status"))
                .addValue("rh", r.get("recheck_hash")).addValue("fp", fp).addValue("at", r.get("executed_at")),
                Long.class);
            out.add(new Credential(id, ((Number) r.get("ticket_id")).longValue(), subjectHash,
                                   (String) r.get("asset_code"), (String) r.get("disposition_status"),
                                   (String) r.get("recheck_hash"), fp));
        }
        return out;
    }

    private String credentialSetFingerprint(List<Credential> credentials) {
        TreeSet<String> fps = new TreeSet<>();
        credentials.forEach(c -> fps.add(c.fingerprint()));
        return HashUtil.sha256(String.join("\n", fps));
    }

    // ------------------------------------------------------------------
    // 5. 文件目录 vs 备份清单：缺件 / 多件
    // ------------------------------------------------------------------
    private void reconcileFileDiffs(long batchId, long fileLocationId) {
        String manifestJson = jdbc.queryForObject(
            "SELECT manifest_files FROM compliance.restore_batch WHERE id = :id",
            new MapSqlParameterSource("id", batchId),
            (rs, i) -> rs.getString("manifest_files"));
        List<String> manifest = jsonbToList(manifestJson);
        List<String> actual = jdbc.queryForList(
            "SELECT file_path FROM quarantine.q_directory_listing ORDER BY file_path", Map.of(), String.class);

        for (String f : manifest) {
            if (!actual.contains(f)) {
                insertDiff(batchId, fileLocationId, f, "MISSING");
            }
        }
        for (String f : actual) {
            if (!manifest.contains(f)) {
                insertDiff(batchId, fileLocationId, f, "EXTRA");
            }
        }
    }

    private void insertDiff(long batchId, long locId, String file, String type) {
        Integer exists = jdbc.queryForObject("""
            SELECT count(*) FROM compliance.restore_file_diff
             WHERE batch_id = :b AND file_path = :f AND diff_type = :t
            """, new MapSqlParameterSource().addValue("b", batchId).addValue("f", file).addValue("t", type),
            Integer.class);
        if (exists != null && exists > 0) {
            return;
        }
        jdbc.update("""
            INSERT INTO compliance.restore_file_diff (batch_id, location_id, file_path, diff_type)
            VALUES (:b, :l, :f, :t)
            """, new MapSqlParameterSource().addValue("b", batchId).addValue("l", locId)
                .addValue("f", file).addValue("t", type));
    }

    /** 重新扫描目录：缺件已补齐的自动关闭；多件需管理员说明后人工关闭。 */
    @Transactional
    public Map<String, Object> recheckFiles(long batchId, Principal principal) {
        long id = requireBatch(batchId);
        List<String> actual = jdbc.queryForList(
            "SELECT file_path FROM quarantine.q_directory_listing", Map.of(), String.class);
        List<Map<String, Object>> openMissing = jdbc.queryForList("""
            SELECT * FROM compliance.restore_file_diff
             WHERE batch_id = :b AND diff_type = 'MISSING' AND status = 'OPEN'
            """, new MapSqlParameterSource("b", id));
        int autoClosed = 0;
        for (Map<String, Object> d : openMissing) {
            if (actual.contains(d.get("file_path"))) {
                jdbc.update("""
                    UPDATE compliance.restore_file_diff SET status = 'RESOLVED', resolved_by = :actor,
                           resolution_note = '目录重扫确认缺件已补齐', resolved_at = now() WHERE id = :id
                    """, new MapSqlParameterSource().addValue("actor", principal.getName()).addValue("id", d.get("id")));
                autoClosed++;
            }
        }
        Long open = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.restore_file_diff WHERE batch_id = :b AND status = 'OPEN'",
            new MapSqlParameterSource("b", id), Long.class);
        audit.log(null, principal.getName(), "RESTORE_FILE_RECHECK",
                  Map.of("batchId", id, "autoClosed", autoClosed, "openDiffs", open));
        return Map.of("batchId", id, "autoClosed", autoClosed, "openDiffs", open);
    }

    @Transactional
    public void resolveFileDiff(long diffId, String note, Principal principal) {
        int n = jdbc.update("""
            UPDATE compliance.restore_file_diff SET status = 'RESOLVED', resolved_by = :actor,
                   resolution_note = :note, resolved_at = now() WHERE id = :id AND status = 'OPEN'
            """, new MapSqlParameterSource().addValue("actor", principal.getName())
                .addValue("note", note).addValue("id", diffId));
        if (n == 0) {
            throw ApiException.conflict("文件差异不存在或已解决");
        }
        audit.log(null, principal.getName(), "RESTORE_FILE_DIFF_RESOLVED",
                  Map.of("diffId", diffId, "note", note));
    }

    // ------------------------------------------------------------------
    // 6. 缺主体关联字段：管理员补齐来源映射后才能重新检查
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> supplyLinkage(long locationId, LinkageRequest req, Principal principal) {
        Map<String, Object> loc = jdbc.query("""
            SELECT * FROM compliance.restore_location WHERE id = :id
            """, new MapSqlParameterSource("id", locationId), rs -> rs.next() ? rsRow(rs) : null);
        if (loc == null) {
            throw ApiException.notFound("隔离位置不存在: " + locationId);
        }
        if (!Boolean.FALSE.equals(loc.get("subject_linkable"))
            && !"PENDING_VERIFY".equals(loc.get("check_status"))) {
            throw ApiException.conflict("该位置具备主体关联字段，不需要补齐映射");
        }
        if (req.linkage() == null || req.linkage().isEmpty()) {
            throw ApiException.badRequest("必须提供 item_ref -> subject_hash 的关联映射");
        }
        jdbc.update("""
            UPDATE compliance.restore_location SET subject_linkable = true, missing_field = NULL,
                   linkage_json = CAST(:j AS jsonb), asset_code = COALESCE(asset_code, :code),
                   check_status = 'PENDING', updated_at = now() WHERE id = :id
            """, new MapSqlParameterSource().addValue("j", toJson(req.linkage()))
                .addValue("code", req.assetCode() == null ? "AST_LEGACY" : req.assetCode())
                .addValue("id", locationId));
        audit.log(null, principal.getName(), "RESTORE_LINKAGE_SUPPLIED",
                  Map.of("locationId", locationId, "mappingCount", req.linkage().size(),
                         "assetCode", req.assetCode() == null ? "AST_LEGACY" : req.assetCode()));
        return Map.of("locationId", locationId, "linkable", true, "nextStep", "重新执行复查扫描");
    }

    // ------------------------------------------------------------------
    // 7. 合规专员处理无法自动判定的复查项
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> decideManualItem(long itemId, String decision, String note, Principal principal) {
        Map<String, Object> item = jdbc.query("""
            SELECT * FROM compliance.restore_review_item WHERE id = :id
            """, new MapSqlParameterSource("id", itemId), rs -> rs.next() ? rsRow(rs) : null);
        if (item == null) {
            throw ApiException.notFound("复查项不存在: " + itemId);
        }
        if (!"PENDING".equals(item.get("review_status"))) {
            throw ApiException.conflict("复查项已处理，不能重复判定");
        }
        if (!"SUPPRESS".equals(decision) && !"RELEASE".equals(decision)) {
            throw ApiException.badRequest("decision 只允许 SUPPRESS 或 RELEASE");
        }
        long batchId = ((Number) item.get("batch_id")).longValue();
        Long ticketId = item.get("original_ticket_id") == null
            ? findAnyTicketForSubject((String) item.get("subject_hash"))
            : ((Number) item.get("original_ticket_id")).longValue();
        jdbc.update("""
            UPDATE compliance.restore_review_item SET verdict = :v, review_status = 'MANUAL_DONE',
                   decided_by = :actor, decided_at = now(), decision_note = :note,
                   original_ticket_id = :tid, updated_at = now() WHERE id = :id
            """, new MapSqlParameterSource()
            .addValue("v", "SUPPRESS".equals(decision) ? "SUPPRESSED" : "CLEAN")
            .addValue("actor", principal.getName()).addValue("note", note)
            .addValue("tid", ticketId).addValue("id", itemId));

        if (ticketId != null) {
            boolean suppress = "SUPPRESS".equals(decision);
            jdbc.update("""
                INSERT INTO compliance.restore_residual_record
                  (batch_id, original_ticket_id, subject_hash, asset_code, location_ref, item_ref,
                   residual_type, action_taken, basis, review_item_id, recorded_by)
                VALUES (:bid, :tid, :sh, :code, :loc, :ref, :type, :action, :basis, :iid, :actor)
                """, new MapSqlParameterSource()
                .addValue("bid", batchId).addValue("tid", ticketId)
                .addValue("sh", item.get("subject_hash")).addValue("code", item.get("asset_code"))
                .addValue("loc", item.get("item_kind")).addValue("ref", item.get("item_ref"))
                .addValue("type", suppress ? "MANUAL_SUPPRESSED" : "MANUAL_RELEASED")
                .addValue("action", suppress ? "SUPPRESSED_IN_QUARANTINE" : "RELEASED_ONLINE")
                .addValue("basis", "合规人工判定：" + note).addValue("iid", itemId)
                .addValue("actor", principal.getName()));
        }
        refreshLocationCounters(batchId);
        audit.log(null, principal.getName(), "RESTORE_MANUAL_DECIDED",
                  Map.of("itemId", itemId, "decision", decision, "ticketId", ticketId));
        return Map.of("itemId", itemId, "decision", decision, "originalTicketId", ticketId);
    }

    private Long findAnyTicketForSubject(String subjectHash) {
        return jdbc.query("""
            SELECT id FROM compliance.work_order
             WHERE subject_hash = :sh AND status <> 'CANCELLED' ORDER BY id LIMIT 1
            """, new MapSqlParameterSource("sh", subjectHash), rs -> rs.next() ? rs.getLong(1) : null);
    }

    // ------------------------------------------------------------------
    // 8. 登记资产血缘：所有位置登记后才能提交开放申请
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> registerLineage(long batchId, Principal principal) {
        long id = requireBatch(batchId);
        List<Map<String, Object>> locations = locationsOf(id);
        int registered = 0;
        for (Map<String, Object> loc : locations) {
            if (Boolean.TRUE.equals(loc.get("lineage_registered"))) {
                continue;
            }
            String assetCode = (String) loc.get("asset_code");
            if (assetCode == null || assetCode.isBlank()) {
                throw ApiException.conflict("位置 " + loc.get("quarantine_ref") + " 尚未补齐资产关联，不能登记血缘");
            }
            Integer exists = jdbc.queryForObject(
                "SELECT count(*) FROM compliance.data_asset WHERE asset_code = :c",
                new MapSqlParameterSource("c", assetCode), Integer.class);
            if (exists == null || exists == 0) {
                // 隔离恢复来源作为新资产登记，血缘父节点为每日备份归档（同事务，失败整体回滚）
                Integer parent = jdbc.queryForObject(
                    "SELECT count(*) FROM compliance.data_asset WHERE asset_code = 'AST_BACKUP'",
                    Map.of(), Integer.class);
                if (parent == null || parent == 0) {
                    throw ApiException.badRequest("父资产 AST_BACKUP 未登记，血缘注册失败，整体回滚");
                }
                jdbc.update("""
                    INSERT INTO compliance.data_asset
                      (asset_code, asset_name, storage_type, target_ref, disposition_default)
                    VALUES (:code, :name, :st, :ref, 'PHYSICAL_DELETE')
                    """, new MapSqlParameterSource()
                    .addValue("code", assetCode).addValue("name", "恢复隔离资产:" + assetCode)
                    .addValue("st", "FILE".equals(loc.get("location_type")) ? "FILE" : "TABLE")
                    .addValue("ref", loc.get("quarantine_ref")));
                jdbc.update("""
                    INSERT INTO compliance.lineage_edge (from_asset_code, to_asset_code)
                    VALUES ('AST_BACKUP', :to) ON CONFLICT DO NOTHING
                    """, new MapSqlParameterSource("to", assetCode));
            }
            jdbc.update("""
                UPDATE compliance.restore_location SET lineage_registered = true,
                       lineage_note = :note, updated_at = now() WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", loc.get("id"))
                    .addValue("note", "已挂接资产血缘（复用/新登记）:" + assetCode));
            registered++;
        }
        audit.log(null, principal.getName(), "RESTORE_LINEAGE_REGISTERED",
                  Map.of("batchId", id, "registered", registered));
        return Map.of("batchId", id, "registered", registered);
    }

    // ------------------------------------------------------------------
    // 9. 开放前再次核对 + 提交开放申请（不能只拿启动时名单查一遍）
    // ------------------------------------------------------------------
    @Transactional
    public Map<String, Object> submitOpenRequest(long batchId, String basis, Principal principal) {
        long id = requireBatch(batchId);
        List<Map<String, Object>> locations = locationsOf(id);
        for (Map<String, Object> loc : locations) {
            String s = (String) loc.get("check_status");
            if (!"CHECKED".equals(s) && !"RELEASED".equals(s)) {
                throw ApiException.conflict("位置 " + loc.get("quarantine_ref")
                    + " 状态为 " + s + "，全部位置通过检查后才能申请开放");
            }
            if (!Boolean.TRUE.equals(loc.get("lineage_registered"))) {
                throw ApiException.conflict("位置 " + loc.get("quarantine_ref") + " 尚未登记资产血缘");
            }
        }
        Long pendingManual = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.restore_review_item WHERE batch_id = :b AND review_status = 'PENDING'",
            new MapSqlParameterSource("b", id), Long.class);
        if (pendingManual != null && pendingManual > 0) {
            throw ApiException.conflict("仍有 " + pendingManual + " 个复查项待合规专员人工判定");
        }
        Long openDiffs = jdbc.queryForObject(
            "SELECT count(*) FROM compliance.restore_file_diff WHERE batch_id = :b AND status = 'OPEN'",
            new MapSqlParameterSource("b", id), Long.class);
        if (openDiffs != null && openDiffs > 0) {
            throw ApiException.conflict("仍有 " + openDiffs + " 个文件缺件/多件差异未解决");
        }

        // 开放前再次核对：实时重算删除凭证，与最近一次扫描的指纹比对，捕获检查期间新办结的注销
        List<Credential> current = refreshCredentials();
        String currentFp = credentialSetFingerprint(current);
        Map<String, Object> batch = jdbc.query("""
            SELECT credential_fingerprint, credential_count, status FROM compliance.restore_batch WHERE id = :id
            """, new MapSqlParameterSource("id", id), rs -> rs.next() ? rsRow(rs) : null);
        if (!currentFp.equals(batch.get("credential_fingerprint"))) {
            throw ApiException.conflict("开放前复核发现删除记录已更新（凭证指纹变化），"
                + "说明检查期间有注销申请新办结，请重新执行复查扫描追加后再申请开放");
        }
        if (basis == null || basis.isBlank()) {
            throw ApiException.badRequest("必须填写放行依据");
        }
        Long reqId = jdbc.queryForObject("""
            INSERT INTO compliance.restore_open_request
              (batch_id, basis, credential_count, credential_fingerprint, status, created_by)
            VALUES (:bid, :basis, :cc, :fp, 'SUBMITTED', :actor)
            RETURNING id
            """, new MapSqlParameterSource().addValue("bid", id).addValue("basis", basis)
                .addValue("cc", current.size()).addValue("fp", currentFp)
                .addValue("actor", principal.getName()), Long.class);
        jdbc.update("""
            UPDATE compliance.restore_batch SET status = 'OPEN_APPLIED', reopen_check_at = now(),
                   reopen_credential_count = :cc, reopen_basis = :basis, updated_at = now() WHERE id = :id
            """, new MapSqlParameterSource().addValue("cc", current.size()).addValue("basis", basis)
                .addValue("id", id));
        audit.log(null, principal.getName(), "RESTORE_OPEN_REQUEST_SUBMITTED",
                  Map.of("batchId", id, "requestId", reqId, "credentialCount", current.size(),
                         "recheckFingerprint", currentFp));
        return Map.of("requestId", reqId, "batchId", id, "status", "SUBMITTED",
                      "reopenCredentialCount", current.size());
    }

    @Transactional
    public Map<String, Object> approveOpenRequest(long requestId, String note, Principal principal) {
        Map<String, Object> req = jdbc.query("""
            SELECT * FROM compliance.restore_open_request WHERE id = :id
            """, new MapSqlParameterSource("id", requestId), rs -> rs.next() ? rsRow(rs) : null);
        if (req == null) {
            throw ApiException.notFound("开放申请不存在: " + requestId);
        }
        if (!"SUBMITTED".equals(req.get("status"))) {
            throw ApiException.conflict("开放申请已处理: " + req.get("status"));
        }
        long batchId = ((Number) req.get("batch_id")).longValue();
        String batchNo = jdbc.queryForObject("SELECT batch_no FROM compliance.restore_batch WHERE id = :id",
            new MapSqlParameterSource("id", batchId), String.class);

        List<Map<String, Object>> items = jdbc.queryForList("""
            SELECT ri.*, l.quarantine_ref FROM compliance.restore_review_item ri
            JOIN compliance.restore_location l ON l.id = ri.location_id
            WHERE ri.batch_id = :b ORDER BY ri.id
            """, new MapSqlParameterSource("b", batchId));
        int released = 0, suppressed = 0;
        for (Map<String, Object> item : items) {
            long itemId = ((Number) item.get("id")).longValue();
            String reviewStatus = (String) item.get("review_status");
            if ("RELEASED".equals(reviewStatus) || "SUPPRESSED".equals(reviewStatus)) {
                continue; // 幂等：已处理条目不重复释放
            }
            boolean suppress = "SUPPRESSED".equals(item.get("verdict"));
            if (suppress) {
                jdbc.update("UPDATE compliance.restore_review_item SET review_status = 'SUPPRESSED', updated_at = now() WHERE id = :id",
                            new MapSqlParameterSource("id", itemId));
                recordSuppressedResidual(batchId, batchNo, item, principal);
                suppressed++;
            } else {
                releaseItemOnline(item);
                jdbc.update("UPDATE compliance.restore_review_item SET review_status = 'RELEASED', updated_at = now() WHERE id = :id",
                            new MapSqlParameterSource("id", itemId));
                released++;
            }
        }
        jdbc.update("""
            UPDATE compliance.restore_location SET check_status = 'RELEASED', released_at = now(),
                   updated_at = now() WHERE batch_id = :b AND check_status = 'CHECKED'
            """, new MapSqlParameterSource("b", batchId));

        // 每个涉及的原删除工单追加一条放行依据（不更换当年证据说明）
        List<Long> ticketIds = jdbc.queryForList("""
            SELECT DISTINCT original_ticket_id FROM compliance.restore_review_item
             WHERE batch_id = :b AND original_ticket_id IS NOT NULL
            """, new MapSqlParameterSource("b", batchId), Long.class);
        for (Long ticketId : ticketIds) {
            Integer basisExists = jdbc.queryForObject("""
                SELECT count(*) FROM compliance.restore_residual_record
                 WHERE batch_id = :b AND original_ticket_id = :t AND residual_type = 'OPEN_BASIS'
                """, new MapSqlParameterSource().addValue("b", batchId).addValue("t", ticketId), Integer.class);
            if (basisExists == null || basisExists == 0) {
                jdbc.update("""
                    INSERT INTO compliance.restore_residual_record
                      (batch_id, original_ticket_id, subject_hash, residual_type, action_taken,
                       basis, recorded_by)
                    SELECT :b, :t, subject_hash, 'OPEN_BASIS', 'BASIS_RECORDED', :basis, :actor
                    FROM compliance.restore_review_item
                    WHERE original_ticket_id = :t AND batch_id = :b LIMIT 1
                    """, new MapSqlParameterSource().addValue("b", batchId).addValue("t", ticketId)
                        .addValue("basis", "批次 " + batchNo + " 开放放行依据：" + req.get("basis")
                            + "；批准说明：" + (note == null ? "" : note))
                        .addValue("actor", principal.getName()));
            }
        }

        jdbc.update("""
            UPDATE compliance.restore_open_request SET status = 'APPROVED', decided_by = :actor,
                   decision_note = :note, decided_at = now() WHERE id = :id
            """, new MapSqlParameterSource().addValue("actor", principal.getName())
                .addValue("note", note).addValue("id", requestId));
        jdbc.update("""
            UPDATE compliance.restore_batch SET status = 'OPENED', opened_at = now(),
                   opened_by = :actor, updated_at = now() WHERE id = :id
            """, new MapSqlParameterSource().addValue("actor", principal.getName()).addValue("id", batchId));
        audit.log(null, principal.getName(), "RESTORE_OPEN_APPROVED",
                  Map.of("batchId", batchId, "requestId", requestId, "released", released,
                         "suppressed", suppressed));
        return Map.of("batchId", batchId, "released", released, "suppressed", suppressed, "status", "OPENED");
    }

    @Transactional
    public Map<String, Object> rejectOpenRequest(long requestId, String note, Principal principal) {
        int n = jdbc.update("""
            UPDATE compliance.restore_open_request SET status = 'REJECTED', decided_by = :actor,
                   decision_note = :note, decided_at = now() WHERE id = :id AND status = 'SUBMITTED'
            """, new MapSqlParameterSource().addValue("actor", principal.getName())
                .addValue("note", note).addValue("id", requestId));
        if (n == 0) {
            throw ApiException.conflict("开放申请不存在或已处理");
        }
        Long batchId = jdbc.queryForObject("SELECT batch_id FROM compliance.restore_open_request WHERE id = :id",
            new MapSqlParameterSource("id", requestId), Long.class);
        jdbc.update("UPDATE compliance.restore_batch SET status = 'PENDING_HUMAN', updated_at = now() WHERE id = :id",
                    new MapSqlParameterSource("id", batchId));
        audit.log(null, principal.getName(), "RESTORE_OPEN_REJECTED",
                  Map.of("requestId", requestId, "note", note));
        return Map.of("requestId", requestId, "status", "REJECTED");
    }

    private void recordSuppressedResidual(long batchId, String batchNo, Map<String, Object> item,
                                          Principal principal) {
        Long ticketId = item.get("original_ticket_id") == null
            ? findAnyTicketForSubject((String) item.get("subject_hash"))
            : ((Number) item.get("original_ticket_id")).longValue();
        if (ticketId == null) {
            return;
        }
        Integer exists = jdbc.queryForObject("""
            SELECT count(*) FROM compliance.restore_residual_record
             WHERE review_item_id = :iid AND residual_type = 'RESTORED_RESIDUAL'
            """, new MapSqlParameterSource("iid", item.get("id")), Integer.class);
        if (exists != null && exists > 0) {
            return;
        }
        jdbc.update("""
            INSERT INTO compliance.restore_residual_record
              (batch_id, original_ticket_id, subject_hash, location_ref, item_ref, residual_type,
               action_taken, basis, review_item_id, recorded_by)
            VALUES (:bid, :tid, :sh, :loc, :ref, 'RESTORED_RESIDUAL', 'SUPPRESSED_IN_QUARANTINE',
                    :basis, :iid, :actor)
            """, new MapSqlParameterSource()
            .addValue("bid", batchId).addValue("tid", ticketId)
            .addValue("sh", item.get("subject_hash")).addValue("loc", item.get("quarantine_ref"))
            .addValue("ref", item.get("item_ref"))
            .addValue("basis", "批次 " + batchNo + " 隔离复查命中当前有效删除凭证（处置结果 "
                + item.get("disposition_snapshot") + "），旧简历在隔离区拦截，未释放到线上，避免重新出现在搜索结果")
            .addValue("iid", item.get("id")).addValue("actor", principal.getName()));
    }

    private void releaseItemOnline(Map<String, Object> item) {
        String qRef = (String) item.get("quarantine_ref");
        ReleaseTarget target = RELEASE_TARGETS.get(qRef);
        if (target == null) {
            // 无可上线映射的位置（如补齐前的旧残片）：仅保留在隔离区，不释放到线上
            return;
        }
        String cols = String.join(", ", target.columns());
        // 自然键已存在则跳过，保证重试不重复释放
        jdbc.update("""
            INSERT INTO %s (%s)
            SELECT %s FROM %s q
             WHERE %s
               AND NOT EXISTS (SELECT 1 FROM %s o WHERE %s)
            """ .formatted(target.onlineTable(), cols, cols, qRef, target.sourceWhere(),
                          target.onlineTable(), target.existsWhere()),
            new MapSqlParameterSource().addValue("ref", item.get("item_ref")));
    }

    // ====================== 辅助 ======================

    private long requireBatch(long batchId) {
        Long id = jdbc.queryForObject("SELECT id FROM compliance.restore_batch WHERE id = :id",
            new MapSqlParameterSource("id", batchId), Long.class);
        if (id == null) {
            throw ApiException.notFound("恢复批次不存在: " + batchId);
        }
        return id;
    }

    private String batchStatus(long batchId) {
        return jdbc.queryForObject("SELECT status FROM compliance.restore_batch WHERE id = :id",
            new MapSqlParameterSource("id", batchId), String.class);
    }

    private List<Map<String, Object>> locationsOf(long batchId) {
        return jdbc.query("""
            SELECT * FROM compliance.restore_location WHERE batch_id = :id ORDER BY id
            """, new MapSqlParameterSource("id", batchId), (rs, i) -> rsRow(rs));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private List<String> jsonbToList(Object v) {
        if (v == null) {
            return List.of();
        }
        if (v instanceof String s) {
            try {
                return objectMapper.readValue(s, new TypeReference<List<String>>() {
                });
            } catch (Exception e) {
                throw new IllegalStateException("jsonb 清单解析失败", e);
            }
        }
        return objectMapper.convertValue(v, new TypeReference<List<String>>() {
        });
    }

    private Map<String, Object> rsRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Map<String, Object> m = new LinkedHashMap<>();
        int cols = rs.getMetaData().getColumnCount();
        for (int i = 1; i <= cols; i++) {
            String label = rs.getMetaData().getColumnLabel(i);
            String typeName = rs.getMetaData().getColumnTypeName(i);
            Object value;
            if ("jsonb".equalsIgnoreCase(typeName) || "json".equalsIgnoreCase(typeName)) {
                // jsonb 驱动默认返回 PGobject（runtime 作用域，编译期不可见）；直接取文本解析为 Java 集合
                String text = rs.getString(i);
                if (text == null) {
                    value = null;
                } else {
                    try {
                        value = objectMapper.readValue(text, Object.class);
                    } catch (Exception e) {
                        value = text;
                    }
                }
            } else {
                value = rs.getObject(i);
            }
            m.put(label, value);
        }
        return m;
    }
}
