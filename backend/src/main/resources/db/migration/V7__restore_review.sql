-- =====================================================================
-- V7：备份恢复后的删除复查（隔离检查后开放）
-- 设计要点：
--   1) 恢复内容先进 quarantine 隔离区，所有位置通过检查并登记血缘后才能申请开放，
--      绝不允许直接恢复到线上再补删（恢复与补删之间会暴露旧简历）
--   2) 复查基准是“当前仍有效的删除记录”——每次扫描实时提取主体指纹与处置结果，
--      检查期间新办结的注销申请会追加进本次扫描；开放前必须再次核对，不能只查启动名单
--   3) 备份缺主体关联字段 → 相关表停在 PENDING_VERIFY；文件目录与备份清单不一致
--      → 列出缺件/多件，管理员补齐来源后才能重新检查
--   4) 每次恢复独立批次号；失败重试只接着处理未完成位置，已核验数据不重复释放
--   5) 复查残留、处理动作、放行依据作为新记录附在原删除工单后，不更换当年证据说明
-- =====================================================================

CREATE SCHEMA IF NOT EXISTS quarantine;

-- ---------------------------------------------------------------------
-- 恢复批次：管理员登记备份日期、涉及的表与文件目录、备份清单
-- ---------------------------------------------------------------------
CREATE TABLE compliance.restore_batch (
    id                      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_no                varchar(40) NOT NULL UNIQUE,
    backup_date             date NOT NULL,
    source_ref              varchar(256),
    quarantine_schema       varchar(64) NOT NULL DEFAULT 'quarantine',
    -- 涉及的表与文件目录（管理员登记）
    involved_tables         jsonb NOT NULL,
    file_directories        jsonb NOT NULL,
    -- 备份清单声明的文件相对路径，用于与目录实际文件比对缺件/多件
    manifest_files          jsonb NOT NULL DEFAULT '[]'::jsonb,
    status                  varchar(24) NOT NULL DEFAULT 'REGISTERED'
                            CHECK (status IN ('REGISTERED','RESTORED','SCANNING','CHECKING',
                                              'BLOCKED','PENDING_HUMAN','READY_TO_OPEN',
                                              'OPEN_APPLIED','OPENED','ABANDONED')),
    -- 最近一次扫描时纳入的有效删除凭证数；开放前复核必须重新计数并核对一致
    credential_count        integer NOT NULL DEFAULT 0,
    credential_fingerprint  varchar(64),
    last_scan_at            timestamptz,
    -- 开放前再次核对的结论
    reopen_check_at         timestamptz,
    reopen_credential_count integer,
    reopen_basis            varchar(1024),
    opened_at               timestamptz,
    opened_by               varchar(64),
    created_by              varchar(64) NOT NULL,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_restore_batch_status ON compliance.restore_batch (status);

-- ---------------------------------------------------------------------
-- 隔离区位置：每个被恢复的表/文件目录一个位置，独立检查、独立重试
-- subject_linkable = false 表示备份缺少主体关联字段，停在待核验，补齐前不参与自动比对
-- ---------------------------------------------------------------------
CREATE TABLE compliance.restore_location (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id            bigint NOT NULL REFERENCES compliance.restore_batch(id),
    location_type       varchar(16) NOT NULL CHECK (location_type IN ('TABLE','FILE')),
    asset_code          varchar(64),
    target_ref          varchar(256) NOT NULL,
    quarantine_ref      varchar(256) NOT NULL,
    subject_linkable    boolean NOT NULL DEFAULT true,
    missing_field       varchar(128),
    check_status        varchar(20) NOT NULL DEFAULT 'PENDING'
                        CHECK (check_status IN ('PENDING','SCANNING','PENDING_VERIFY',
                                                'CHECKED','BLOCKED','RELEASED','EXCLUDED')),
    -- 恢复出的记录/文件总数与各判定计数
    restored_count      integer NOT NULL DEFAULT 0,
    residual_count      integer NOT NULL DEFAULT 0,
    suppressed_count    integer NOT NULL DEFAULT 0,
    clean_count         integer NOT NULL DEFAULT 0,
    manual_pending_count integer NOT NULL DEFAULT 0,
    last_error          varchar(512),
    -- 管理员补齐来源后提供的 item_ref -> subject_hash 关联映射（缺主体字段场景）
    linkage_json        jsonb,
    checked_at          timestamptz,
    released_at         timestamptz,
    lineage_registered  boolean NOT NULL DEFAULT false,
    lineage_note        varchar(512),
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (batch_id, quarantine_ref)
);
CREATE INDEX idx_restore_location_batch ON compliance.restore_location (batch_id);

-- ---------------------------------------------------------------------
-- 隔离区文件清单比对结果：缺件（清单有、目录没有）/ 多件（目录有、清单没有）
-- 差异未消除前文件类位置停在 BLOCKED，管理员补齐来源后才能重新检查
-- ---------------------------------------------------------------------
CREATE TABLE compliance.restore_file_diff (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id        bigint NOT NULL REFERENCES compliance.restore_batch(id),
    location_id     bigint REFERENCES compliance.restore_location(id),
    file_path       varchar(512) NOT NULL,
    diff_type       varchar(12) NOT NULL CHECK (diff_type IN ('MISSING','EXTRA')),
    status          varchar(16) NOT NULL DEFAULT 'OPEN'
                    CHECK (status IN ('OPEN','RESOLVED')),
    resolved_by     varchar(64),
    resolution_note varchar(512),
    created_at      timestamptz NOT NULL DEFAULT now(),
    resolved_at     timestamptz
);
CREATE INDEX idx_restore_file_diff_batch ON compliance.restore_file_diff (batch_id);

-- ---------------------------------------------------------------------
-- 复查项：隔离区每条恢复记录/每个附件与“当前仍有效删除凭证”逐条比对
-- verdict:
--   SUPPRESSED  命中有效删除凭证（指纹+处置结果一致）→ 隔离拦截，不释放
--   CLEAN       不属于任何已注销主体 → 可释放
--   MANUAL      指纹命中但处置结论无法自动判定 → 转合规专员
-- ---------------------------------------------------------------------
CREATE TABLE compliance.restore_review_item (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id            bigint NOT NULL REFERENCES compliance.restore_batch(id),
    location_id         bigint NOT NULL REFERENCES compliance.restore_location(id),
    item_ref            varchar(256) NOT NULL,
    item_kind           varchar(16) NOT NULL CHECK (item_kind IN ('ACCOUNT','ATTACHMENT','DERIVED')),
    subject_hash        varchar(64),
    subject_fingerprint varchar(64),
    credential_id       bigint,
    original_ticket_id  bigint,
    disposition_snapshot varchar(24),
    verdict             varchar(16) NOT NULL DEFAULT 'MANUAL'
                        CHECK (verdict IN ('SUPPRESSED','CLEAN','MANUAL')),
    review_status       varchar(16) NOT NULL DEFAULT 'PENDING'
                        CHECK (review_status IN ('PENDING','AUTO_DONE','MANUAL_DONE','SUPPRESSED','RELEASED')),
    manual_reason       varchar(512),
    decided_by          varchar(64),
    decided_at          timestamptz,
    decision_note       varchar(512),
    -- 复查期间动态追加：该项由第几次扫描纳入（首扫=1，追加注销=2…）
    scan_round          integer NOT NULL DEFAULT 1,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    UNIQUE (location_id, item_ref)
);
CREATE INDEX idx_restore_review_batch ON compliance.restore_review_item (batch_id);
CREATE INDEX idx_restore_review_status ON compliance.restore_review_item (review_status);

-- ---------------------------------------------------------------------
-- 长期保留的删除凭证（可比对指纹）：从当前仍有效的删除记录中提取。
-- 只从终态处置记录提取，失败/跳过不构成有效凭证；凭证随删除作业长期保留。
-- ---------------------------------------------------------------------
CREATE TABLE compliance.deletion_credential (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ticket_id           bigint NOT NULL,
    subject_hash        varchar(64) NOT NULL,
    asset_code          varchar(64) NOT NULL,
    disposition_id      bigint NOT NULL UNIQUE,
    disposition_category varchar(24) NOT NULL,
    disposition_status  varchar(24) NOT NULL,
    -- 处置完成时留存的抽样反查指纹，供恢复复查长期比对
    recheck_hash        varchar(64),
    subject_fingerprint varchar(64) NOT NULL,
    credential_fingerprint varchar(64) NOT NULL,
    executed_at         timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_credential_subject ON compliance.deletion_credential (subject_hash);
CREATE INDEX idx_credential_ticket ON compliance.deletion_credential (ticket_id);

-- ---------------------------------------------------------------------
-- 复查残留/处理动作/放行依据：作为新记录附在原删除工单后面，
-- 不更换当年已经出具的证据说明（证据包与原处置记录保持原样）。
-- ---------------------------------------------------------------------
CREATE TABLE compliance.restore_residual_record (
    id                  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id            bigint NOT NULL REFERENCES compliance.restore_batch(id),
    original_ticket_id  bigint NOT NULL,
    subject_hash        varchar(64) NOT NULL,
    asset_code          varchar(64),
    location_ref        varchar(256),
    item_ref            varchar(256),
    residual_type       varchar(24) NOT NULL
                        CHECK (residual_type IN ('RESTORED_RESIDUAL','MANUAL_SUPPRESSED',
                                                  'MANUAL_RELEASED','OPEN_BASIS')),
    action_taken        varchar(24) NOT NULL
                        CHECK (action_taken IN ('SUPPRESSED_IN_QUARANTINE','RELEASED_ONLINE',
                                                'KEPT_PENDING','BASIS_RECORDED')),
    basis               varchar(1024) NOT NULL,
    review_item_id      bigint,
    recorded_by         varchar(64) NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_residual_ticket ON compliance.restore_residual_record (original_ticket_id);

-- 开放申请：所有位置通过检查并登记资产血缘后才能提交
CREATE TABLE compliance.restore_open_request (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    batch_id        bigint NOT NULL UNIQUE REFERENCES compliance.restore_batch(id),
    basis           varchar(1024) NOT NULL,
    -- 开放前再次核对时的删除凭证快照（数量+指纹），证明不是只拿启动时名单查一遍
    credential_count integer NOT NULL,
    credential_fingerprint varchar(64) NOT NULL,
    status          varchar(16) NOT NULL DEFAULT 'SUBMITTED'
                    CHECK (status IN ('SUBMITTED','APPROVED','REJECTED')),
    decided_by      varchar(64),
    decision_note   varchar(512),
    created_by      varchar(64) NOT NULL,
    created_at      timestamptz NOT NULL DEFAULT now(),
    decided_at      timestamptz
);
