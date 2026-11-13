package com.example.rtbf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 备份恢复删除复查端到端测试：
 *  - 恢复先入隔离区，命中有效删除凭证的旧简历拦截，不重新出现在线上/搜索结果
 *  - 缺主体关联字段停待核验；文件缺件/多件阻断，补齐后重检
 *  - 检查期间新办结注销动态追加；开放前再次核对凭证指纹
 *  - 重试接着处理未完成位置，已核验数据不重复释放
 *  - 残留与放行依据作为新记录附在原删除工单后
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RestoreReviewIntegrationTest {

    private static final String SUBJ_A =
        "f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c";
    private static final String SUBJ_B =
        "d6cd18bbe1b818ca839a3d021c0b14944a3ff7942913c7655ad43ee2ef04ae48";
    private static final String SUBJ_C =
        "8dc687032fa94ca2852d239948f6edffa1bfa81f7c119061a06b3da507429fd7";
    private static final String SUBJ_NEW =
        "a17f3c92e84b4d6f9c0a5e7d2b8f1c4a6d0e3f5a7b9c2d4e6f8a0b2c4d6e8f01";

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Flyway flyway;
    private final ObjectMapper json = new ObjectMapper();

    static {
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/rtbf_test", "ccdev", "ccdev");
             java.sql.Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA IF EXISTS compliance CASCADE");
            st.execute("DROP SCHEMA IF EXISTS biz CASCADE");
            st.execute("DROP SCHEMA IF EXISTS quarantine CASCADE");
            st.execute("DROP TABLE IF EXISTS public.flyway_schema_history CASCADE");
        } catch (java.sql.SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @BeforeAll
    void resetDatabase() {
        jdbc.execute("DROP SCHEMA IF EXISTS compliance CASCADE");
        jdbc.execute("DROP SCHEMA IF EXISTS biz CASCADE");
        jdbc.execute("DROP SCHEMA IF EXISTS quarantine CASCADE");
        jdbc.execute("DROP TABLE IF EXISTS public.flyway_schema_history CASCADE");
        flyway.migrate();
    }

    private HttpHeaders auth(String user) {
        HttpHeaders h = new HttpHeaders();
        h.setBasicAuth(user, switch (user) {
            case "compliance" -> "Compliance@2026";
            case "operator" -> "Operator@2026";
            default -> "Viewer@2026";
        });
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private JsonNode post(String user, String path, Object body) {
        ResponseEntity<String> r = rest.exchange(path, HttpMethod.POST,
            new HttpEntity<>(body == null ? "" : write(body), auth(user)), String.class);
        assertTrue(r.getStatusCode().is2xxSuccessful(),
            () -> "POST " + path + " 失败: " + r.getStatusCode() + " " + r.getBody());
        return read(r.getBody());
    }

    private ResponseEntity<String> postRaw(String user, String path, Object body) {
        return rest.exchange(path, HttpMethod.POST,
            new HttpEntity<>(body == null ? "" : write(body), auth(user)), String.class);
    }

    private JsonNode get(String user, String path) {
        ResponseEntity<String> r = rest.exchange(path, HttpMethod.GET,
            new HttpEntity<>(auth(user)), String.class);
        assertTrue(r.getStatusCode().is2xxSuccessful(), () -> "GET " + path + " 失败: " + r.getBody());
        return read(r.getBody());
    }

    private String write(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private JsonNode read(String s) {
        try {
            return json.readTree(s);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 办结一个主体的完整注销流程，产出当前仍有效的删除凭证；已办结则直接复用原工单（跨测试幂等）。 */
    private long completeDeletion(String subjectHash) {
        post("compliance", "/api/subjects/scan", Map.of());
        JsonNode subject = get("compliance", "/api/subjects/" + subjectHash);
        if ("CANDIDATE".equals(subject.get("status").asText())) {
            for (JsonNode m : subject.get("identifierMatches")) {
                if ("PENDING".equals(m.get("match_status").asText())) {
                    post("compliance", "/api/identifier-matches/" + m.get("id").asLong() + "/decide",
                         Map.of("accept", true));
                }
            }
            post("compliance", "/api/subjects/" + subjectHash + "/confirm", Map.of());
        }
        List<Long> completed = jdbc.queryForList(
            "SELECT id FROM compliance.work_order WHERE subject_hash = ? AND status = 'COMPLETED' ORDER BY id",
            Long.class, subjectHash);
        if (!completed.isEmpty()) {
            return completed.get(0);
        }

        JsonNode created = post("operator", "/api/tickets",
            Map.of("subjectHash", subjectHash, "channel", "REGULATOR_LETTER"));
        long ticketId = created.get("ticketId").asLong();
        post("operator", "/api/tickets/" + ticketId + "/freeze-enumeration", Map.of());
        long gapId = get("viewer", "/api/lineage/gaps").get(0).get("id").asLong();
        post("compliance", "/api/tickets/" + ticketId + "/gaps/" + gapId + "/confirm",
             Map.of("inScope", true));
        post("operator", "/api/tickets/" + ticketId + "/preview", Map.of());
        post("operator", "/api/tickets/" + ticketId + "/execute", Map.of());

        JsonNode detail = get("viewer", "/api/tickets/" + ticketId);
        for (JsonNode n : detail.get("notifications")) {
            if ("SENT".equals(n.get("notice_status").asText())) {
                post("operator", "/api/tickets/" + ticketId + "/receipts", Map.of(
                    "noticeRef", n.get("notice_ref").asText(), "receiptRef", "RCPT-" + n.get("id").asLong()));
            }
        }
        JsonNode reconcile = post("compliance", "/api/tickets/" + ticketId + "/reconcile", Map.of());
        assertTrue(reconcile.get("passed").asBoolean(),
            () -> "前置注销工单应对账通过: " + reconcile);
        return ticketId;
    }

    private long registerDefaultBatch() {
        JsonNode reg = post("operator", "/api/restore/batches", Map.of(
            "backupDate", "2026-09-30",
            "sourceRef", "snapshot-20260930",
            "manifestFiles", List.of(
                "pdf/zhangwei-v1.pdf", "pdf/zhangwei-v2.pdf", "pdf/lina-v1.pdf")));
        return reg.get("batchId").asLong();
    }

    private JsonNode locationOf(JsonNode batch, String quarantineRef) {
        for (JsonNode loc : batch.get("locations")) {
            if (quarantineRef.equals(loc.get("quarantine_ref").asText())) {
                return loc;
            }
        }
        throw new IllegalStateException("隔离位置不存在: " + quarantineRef);
    }

    private JsonNode reviewItem(JsonNode batch, String itemRef) {
        for (JsonNode item : batch.get("reviewItems")) {
            if (itemRef.equals(item.get("item_ref").asText())) {
                return item;
            }
        }
        throw new IllegalStateException("复查项不存在: " + itemRef);
    }

    private int countSql(String sql, Object... args) {
        Integer c = jdbc.queryForObject(sql, Integer.class, args);
        return c == null ? 0 : c;
    }

    @Test
    @Order(1)
    void quarantineReview_suppressesResumedDeletedData_andReleasesOthers() {
        long ticketA = completeDeletion(SUBJ_A);
        long batchId = registerDefaultBatch();

        JsonNode batch = get("viewer", "/api/restore/batches/" + batchId);
        assertEquals(5, batch.get("locations").size(), "4 张表 + 1 个附件目录位置");
        JsonNode legacy = locationOf(batch, "quarantine.q_legacy_dump");
        assertFalse(legacy.get("subject_linkable").asBoolean(), "缺主体关联字段必须标记不可关联");
        assertEquals("PENDING_VERIFY", legacy.get("check_status").asText());

        // 未装载不能扫描
        assertEquals(HttpStatus.CONFLICT,
            postRaw("operator", "/api/restore/batches/" + batchId + "/scan", Map.of()).getStatusCode());

        post("operator", "/api/restore/batches/" + batchId + "/restore", Map.of());
        JsonNode scan = post("operator", "/api/restore/batches/" + batchId + "/scan", Map.of());
        assertEquals("BLOCKED", scan.get("nextStatus").asText(), "文件差异与待核验位置未消除，批次阻断");
        assertTrue(scan.get("credentialCount").asInt() > 0, "扫描必须从当前有效删除记录提取凭证");

        batch = get("viewer", "/api/restore/batches/" + batchId);
        // 已注销主体 A 的账号/简历/派生索引全部自动拦截
        assertEquals("SUPPRESSED", reviewItem(batch, "ACC-1001").get("verdict").asText());
        assertEquals("SUPPRESSED", reviewItem(batch, "resume#1").get("verdict").asText());
        assertEquals("SUPPRESSED", reviewItem(batch, "doc#1").get("verdict").asText());
        // 未注销的 B 与备份后新主体放行
        assertEquals("CLEAN", reviewItem(batch, "ACC-2001").get("verdict").asText());
        assertEquals("CLEAN", reviewItem(batch, "ACC-9001").get("verdict").asText());

        // 文件位置阻断：缺件 lina-v1.pdf、多件 unknown-orphan.pdf
        JsonNode fileLoc = locationOf(batch, "quarantine.q_backup_file");
        assertEquals("BLOCKED", fileLoc.get("check_status").asText());
        List<String> diffTypes = java.util.stream.StreamSupport.stream(batch.get("fileDiffs").spliterator(), false)
            .map(d -> d.get("diff_type").asText() + ":" + d.get("file_path").asText()).toList();
        assertTrue(diffTypes.contains("MISSING:pdf/lina-v1.pdf"));
        assertTrue(diffTypes.contains("EXTRA:pdf/unknown-orphan.pdf"));

        // 阻断未消除前不能申请开放、不能登记完成
        assertEquals(HttpStatus.CONFLICT,
            postRaw("operator", "/api/restore/batches/" + batchId + "/open-request",
                    Map.of("basis", "x")).getStatusCode());

        // 管理员补齐缺件来源 → 重扫目录自动关闭缺件；多件人工说明后关闭
        jdbc.update("INSERT INTO quarantine.q_directory_listing(file_path) VALUES ('pdf/lina-v1.pdf')");
        JsonNode recheck = post("operator", "/api/restore/batches/" + batchId + "/recheck-files", Map.of());
        assertEquals(1, recheck.get("autoClosed").asInt());
        long extraDiffId = java.util.stream.StreamSupport.stream(
                get("viewer", "/api/restore/batches/" + batchId).get("fileDiffs").spliterator(), false)
            .filter(d -> "EXTRA".equals(d.get("diff_type").asText()) && "OPEN".equals(d.get("status").asText()))
            .findFirst().orElseThrow().get("id").asLong();
        post("compliance", "/api/restore/file-diffs/" + extraDiffId + "/resolve",
             Map.of("note", "确认为备份后新增的公共附件，与注销主体无关，保留隔离不释放"));

        // 缺主体字段位置：补齐 item_ref → subject_hash 来源映射
        long legacyLocId = legacy.get("id").asLong();
        post("operator", "/api/restore/locations/" + legacyLocId + "/linkage", Map.of(
            "assetCode", "AST_LEGACY",
            "linkage", Map.of("dump#1", SUBJ_A, "dump#2", SUBJ_NEW)));

        // 重新扫描：全部位置通过
        JsonNode scan2 = post("operator", "/api/restore/batches/" + batchId + "/scan", Map.of());
        assertEquals("READY_TO_OPEN", scan2.get("nextStatus").asText());
        batch = get("viewer", "/api/restore/batches/" + batchId);
        assertEquals("SUPPRESSED", reviewItem(batch, "dump#1").get("verdict").asText(),
            "补齐关联后，旧残片中 A 的数据须命中删除凭证拦截");
        assertEquals("CLEAN", reviewItem(batch, "dump#2").get("verdict").asText());
        // 文件位置解除阻断后，A 的附件同样拦截、B 的附件放行
        assertEquals("SUPPRESSED", reviewItem(batch, "pdf/zhangwei-v1.pdf").get("verdict").asText());
        assertEquals("CLEAN", reviewItem(batch, "pdf/lina-v1.pdf").get("verdict").asText());

        // 未登记血缘不能开放
        assertEquals(HttpStatus.CONFLICT,
            postRaw("operator", "/api/restore/batches/" + batchId + "/open-request",
                    Map.of("basis", "x")).getStatusCode());
        post("operator", "/api/restore/batches/" + batchId + "/lineage", Map.of());
        batch = get("viewer", "/api/restore/batches/" + batchId);
        assertTrue(batch.get("locations").get(0).get("lineage_registered").asBoolean());

        // 提交开放申请并由合规批准
        post("operator", "/api/restore/batches/" + batchId + "/open-request", Map.of(
            "basis", "全部位置隔离复查通过，命中删除凭证者拦截，其余放行，凭证指纹开放前已复核"));
        long requestId = get("viewer", "/api/restore/batches/" + batchId).get("openRequest").get(0).get("id").asLong();
        JsonNode approved = post("compliance", "/api/restore/open-requests/" + requestId + "/approve",
                                 Map.of("note", "同意开放"));
        assertTrue(approved.get("suppressed").asInt() >= 6, "A 的账号/简历/索引/附件/残片残留全部拦截");
        assertTrue(approved.get("released").asInt() >= 3, "B、新主体等无注销凭证的数据放行");

        // 线上库：已注销的 A 绝不因恢复而重现；其他主体数据存在
        assertEquals(0, countSql("SELECT count(*) FROM biz.account WHERE subject_hash = ?", SUBJ_A));
        assertEquals(0, countSql("SELECT count(*) FROM biz.resume WHERE subject_hash = ?", SUBJ_A));
        assertEquals(0, countSql("SELECT count(*) FROM biz.resume_pdf WHERE subject_hash = ?", SUBJ_A));
        assertEquals(0, countSql("SELECT count(*) FROM biz.search_index_doc WHERE subject_hash = ?", SUBJ_A));
        assertEquals(1, countSql("SELECT count(*) FROM biz.account WHERE subject_hash = ?", SUBJ_B));
        assertEquals(1, countSql("SELECT count(*) FROM biz.account WHERE subject_hash = ?", SUBJ_NEW));

        // 残留、处理动作、放行依据作为新记录附在原删除工单后
        JsonNode ticket = get("viewer", "/api/tickets/" + ticketA);
        JsonNode residuals = ticket.get("restoreResiduals");
        assertTrue(residuals.size() >= 2, "原工单后须附残留记录与放行依据");
        List<String> types = java.util.stream.StreamSupport.stream(residuals.spliterator(), false)
            .map(r -> r.get("residual_type").asText()).toList();
        assertTrue(types.contains("RESTORED_RESIDUAL"));
        assertTrue(types.contains("OPEN_BASIS"));
    }

    @Test
    @Order(2)
    void scan_dynamicallyAppendsNewDeletion_andReopenRechecksFingerprint() {
        long ticketA = completeDeletion(SUBJ_A);
        long batchId = registerDefaultBatch();
        post("operator", "/api/restore/batches/" + batchId + "/restore", Map.of());
        post("operator", "/api/restore/batches/" + batchId + "/scan", Map.of());

        // 消除阻断：补齐文件 + 关闭差异 + 补齐旧残片关联，先让批次达到可开放状态
        jdbc.update("INSERT INTO quarantine.q_directory_listing(file_path) VALUES ('pdf/lina-v1.pdf')");
        post("operator", "/api/restore/batches/" + batchId + "/recheck-files", Map.of());
        for (JsonNode d : get("viewer", "/api/restore/batches/" + batchId).get("fileDiffs")) {
            if ("OPEN".equals(d.get("status").asText())) {
                post("compliance", "/api/restore/file-diffs/" + d.get("id").asLong() + "/resolve",
                     Map.of("note", "测试补齐"));
            }
        }
        long legacyLocId = locationOf(get("viewer", "/api/restore/batches/" + batchId),
                                      "quarantine.q_legacy_dump").get("id").asLong();
        post("operator", "/api/restore/locations/" + legacyLocId + "/linkage", Map.of(
            "assetCode", "AST_LEGACY", "linkage", Map.of("dump#1", SUBJ_A, "dump#2", SUBJ_NEW)));
        JsonNode scanReady = post("operator", "/api/restore/batches/" + batchId + "/scan", Map.of());
        assertEquals("READY_TO_OPEN", scanReady.get("nextStatus").asText());
        post("operator", "/api/restore/batches/" + batchId + "/lineage", Map.of());

        // 检查期间：隔离区出现 C 的恢复数据（使用 C 的真实标识符哈希），随后 C 的注销才办结（删除记录在首扫之后更新）
        jdbc.update("INSERT INTO quarantine.q_account(account_id, subject_hash, phone_hash, email_hash, idcard_hash, name) "
                    + "VALUES ('ACC-3001', ?, "
                    + "'412d65b4d8f37676654685a6a525363a8a635ed140a69ac30a30f344ae0dd3b1',"
                    + "'d5b6231d61840686113e975d519b97952bdd8803b28a1317ab692c5ace13c214',"
                    + "'7fe255b84945232919b4669bdaef6136e510a0dd6ce47b4b520c8d88ede5ad2d','张伟')", SUBJ_C);
        long ticketC = completeDeletion(SUBJ_C);

        // 所有位置虽已检查，但只拿启动时名单开放必须被拒：删除记录已更新，凭证指纹变化
        assertEquals(HttpStatus.CONFLICT,
            postRaw("operator", "/api/restore/batches/" + batchId + "/open-request",
                    Map.of("basis", "尝试用旧名单开放")).getStatusCode());

        // 重新扫描动态追加：C 的复查项本轮纳入并拦截
        JsonNode scanLatest = post("operator", "/api/restore/batches/" + batchId + "/scan", Map.of());
        assertEquals("READY_TO_OPEN", scanLatest.get("nextStatus").asText());
        JsonNode itemC = reviewItem(get("viewer", "/api/restore/batches/" + batchId), "ACC-3001");
        assertEquals("SUPPRESSED", itemC.get("verdict").asText());
        assertEquals(scanLatest.get("scanRound").asInt(), itemC.get("scan_round").asInt(),
            "检查期间新办结的注销须追加进本次扫描，而非沿用启动名单");
        assertEquals(ticketC, itemC.get("original_ticket_id").asLong());

        // 指纹已与当前删除记录对齐，开放申请通过
        post("operator", "/api/restore/batches/" + batchId + "/open-request",
             Map.of("basis", "重扫已追加检查期间新办结注销，开放前复核一致"));
        long requestId = get("viewer", "/api/restore/batches/" + batchId).get("openRequest").get(0).get("id").asLong();
        post("compliance", "/api/restore/open-requests/" + requestId + "/approve", Map.of("note", "同意"));
        assertEquals(0, countSql("SELECT count(*) FROM biz.account WHERE subject_hash = ?", SUBJ_C));
        assertNotNull(ticketA);
    }

    @Test
    @Order(3)
    void retry_resumesUnfinishedLocations_andNeverReleasesTwice() {
        completeDeletion(SUBJ_A);
        long batchId = registerDefaultBatch();
        post("operator", "/api/restore/batches/" + batchId + "/restore", Map.of());

        // 第一次扫描：复查项数量固定
        post("operator", "/api/restore/batches/" + batchId + "/scan", Map.of());
        int itemsAfterFirst = countSql(
            "SELECT count(*) FROM compliance.restore_review_item WHERE batch_id = ?", batchId);
        // 失败重试：再扫一遍，已自动判定的条目不重复建项、计数不翻倍
        post("operator", "/api/restore/batches/" + batchId + "/scan", Map.of());
        assertEquals(itemsAfterFirst, countSql(
            "SELECT count(*) FROM compliance.restore_review_item WHERE batch_id = ?", batchId));

        // 制造一个无法自动判定项：账号 subject_hash 指向 A 但标识符哈希对不上
        jdbc.update("INSERT INTO quarantine.q_account(account_id, subject_hash, phone_hash, email_hash, idcard_hash, name) "
                    + "VALUES ('ACC-1099', ?, 'x1','x2','x3','张伟?')", SUBJ_A);
        post("operator", "/api/restore/batches/" + batchId + "/scan", Map.of());
        JsonNode manual = reviewItem(get("viewer", "/api/restore/batches/" + batchId), "ACC-1099");
        assertEquals("MANUAL", manual.get("verdict").asText());
        assertEquals("PENDING", manual.get("review_status").asText());
        long manualItemId = manual.get("id").asLong();

        // 待人工判定时不能开放
        jdbc.update("INSERT INTO quarantine.q_directory_listing(file_path) VALUES ('pdf/lina-v1.pdf')");
        post("operator", "/api/restore/batches/" + batchId + "/recheck-files", Map.of());
        for (JsonNode d : get("viewer", "/api/restore/batches/" + batchId).get("fileDiffs")) {
            if ("OPEN".equals(d.get("status").asText())) {
                post("compliance", "/api/restore/file-diffs/" + d.get("id").asLong() + "/resolve",
                     Map.of("note", "补齐"));
            }
        }
        long legacyLocId = locationOf(get("viewer", "/api/restore/batches/" + batchId),
                                      "quarantine.q_legacy_dump").get("id").asLong();
        post("operator", "/api/restore/locations/" + legacyLocId + "/linkage", Map.of(
            "assetCode", "AST_LEGACY", "linkage", Map.of("dump#1", SUBJ_A, "dump#2", SUBJ_NEW)));
        post("operator", "/api/restore/batches/" + batchId + "/scan", Map.of());
        post("operator", "/api/restore/batches/" + batchId + "/lineage", Map.of());
        assertEquals(HttpStatus.CONFLICT,
            postRaw("operator", "/api/restore/batches/" + batchId + "/open-request",
                    Map.of("basis", "还有人工项")).getStatusCode());

        // 合规专员人工拦截
        post("compliance", "/api/restore/review-items/" + manualItemId + "/decide",
             Map.of("decision", "SUPPRESS", "note", "指纹虽不一致但确认为 A 的历史账号，隔离拦截"));
        post("operator", "/api/restore/batches/" + batchId + "/open-request",
             Map.of("basis", "人工项已处理，全部位置通过检查"));
        long requestId = get("viewer", "/api/restore/batches/" + batchId).get("openRequest").get(0).get("id").asLong();
        post("compliance", "/api/restore/open-requests/" + requestId + "/approve", Map.of("note", "同意"));

        // 重复批准被拒；线上数据不重复
        assertEquals(HttpStatus.CONFLICT,
            postRaw("compliance", "/api/restore/open-requests/" + requestId + "/approve",
                    Map.of("note", "再次批准")).getStatusCode());
        // B 在线上始终只有 1 条（演示基线原有），放行插入被自然键去重
        assertEquals(1, countSql("SELECT count(*) FROM biz.account WHERE subject_hash = ?", SUBJ_B));
        // 新主体只插入一次
        assertEquals(1, countSql("SELECT count(*) FROM biz.account WHERE subject_hash = ?", SUBJ_NEW));
        // 人工拦截项未上线
        assertEquals(0, countSql("SELECT count(*) FROM biz.account WHERE account_id = 'ACC-1099'"));
        // 批次终态
        assertEquals("OPENED",
            get("viewer", "/api/restore/batches/" + batchId).get("status").asText());
    }
}
