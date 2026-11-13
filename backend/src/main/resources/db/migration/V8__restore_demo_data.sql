-- =====================================================================
-- V8：备份恢复演示数据——“历史备份”恢复后落在 quarantine 隔离区的内容
-- 场景覆盖：
--   1) q_account/q_resume/q_search_index_doc：带主体关联字段，可自动指纹比对
--      含已注销主体 A（应隔离拦截）、已撤回注销的 B（应放行）、备份后新主体（放行）
--   2) q_legacy_dump：备份缺少主体关联字段（无 subject_hash），相关表必须停在待核验
--   3) q_backup_file + q_directory_listing：附件登记与目录实际文件不一致
--      （缺件 lina-v1.pdf、多件 unknown-orphan.pdf），补齐来源前文件位置阻断
-- 注意：隔离区表结构刻意与 biz 线上表保持同构，复查通过的 CLEAN 数据按列复制上线
-- =====================================================================

-- 隔离区：账号备份（同构 biz.account）
CREATE TABLE quarantine.q_account (
    account_id   varchar(32) PRIMARY KEY,
    subject_hash varchar(64) NOT NULL,
    phone_hash   varchar(64),
    email_hash   varchar(64),
    idcard_hash  varchar(64),
    name         varchar(64) NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);

-- 隔离区：简历多版本备份（同构 biz.resume）
CREATE TABLE quarantine.q_resume (
    resume_id    bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id   varchar(32) NOT NULL,
    subject_hash varchar(64) NOT NULL,
    version_no   integer NOT NULL,
    content_pii  text NOT NULL
);

-- 隔离区：搜索索引派生记录备份（同构 biz.search_index_doc）
CREATE TABLE quarantine.q_search_index_doc (
    doc_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_hash varchar(64) NOT NULL,
    index_name   varchar(64) NOT NULL,
    doc_ref      varchar(128) NOT NULL
);

-- 隔离区：旧系统导出残片——缺少主体关联字段（无 subject_hash / 标识符哈希），
-- 恢复复查无法自动判定，该位置必须停在 PENDING_VERIFY，待管理员补齐来源
CREATE TABLE quarantine.q_legacy_dump (
    dump_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    account_id   varchar(32) NOT NULL,
    name         varchar(64) NOT NULL,
    phone_cipher varchar(128) NOT NULL
);

-- 隔离区：附件备份登记（同构 biz.resume_pdf）
CREATE TABLE quarantine.q_backup_file (
    file_id      bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    subject_hash varchar(64) NOT NULL,
    object_key   varchar(256) NOT NULL,
    bucket       varchar(64) NOT NULL,
    restored_at  timestamptz NOT NULL DEFAULT now()
);

-- 隔离区：文件目录实际清单（由恢复作业扫描目录生成，与备份声明清单比对缺件/多件）
CREATE TABLE quarantine.q_directory_listing (
    id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    file_path    varchar(512) NOT NULL
);

-- 备份来源目录登记：供管理员登记恢复批次时选择（含每个来源是否具备主体关联字段）
CREATE TABLE quarantine.backup_catalog (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    source_type     varchar(16) NOT NULL,
    target_ref      varchar(256) NOT NULL,
    asset_code      varchar(64),
    subject_column  varchar(64),
    note            varchar(512)
);

-- 演示数据 ----------------------------------------------------------------
-- 已注销主体 A 的两个账号（删除作业完成后，恢复复查必须拦截）
INSERT INTO quarantine.q_account (account_id, subject_hash, phone_hash, email_hash, idcard_hash, name) VALUES
('ACC-1001','f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c',
 'b1c4769e3ad14f68ea1a96b73bbe5a83d90792f044828c5daf9d908b0738b177',
 'dae92ea63028947efcc58b3ef81d8bff37a0782f9d253fab62c55600fa36569c',
 '04e7358a07d7a12d426f8a3091293b19fd7a278b56163c5e9f7f0c7b41e7d233','张伟'),
('ACC-1002','f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c',
 NULL,
 '4a61631b42f1a4e8d8a1314e92bb7dc5ed9a8bde490996c2e9d291a91978ab77',
 '04e7358a07d7a12d426f8a3091293b19fd7a278b56163c5e9f7f0c7b41e7d233','张伟'),
-- 李娜 B：注销申请已撤回，线上数据合法保留，备份内容应放行
('ACC-2001','d6cd18bbe1b818ca839a3d021c0b14944a3ff7942913c7655ad43ee2ef04ae48',
 '965eac4d7b0698a18e3c59c34023d9cd529752c7e5fd4164209d2e7df010e2ad',
 '7440664a70a8af65857729eca8a636473994304b971a19aee68e4d6ba6209afe',
 'a844c45c60b52158aeb01e241e2c98bce9cfd583035c671c9ac08c19d7927df6','李娜'),
-- 备份时点之后注册的新主体，无任何删除凭证，应放行
('ACC-9001','a17f3c92e84b4d6f9c0a5e7d2b8f1c4a6d0e3f5a7b9c2d4e6f8a0b2c4d6e8f01',
 'c5d2e8f1a4b7c0d3e6f9a2b5c8d1e4f7a0b3c6d9e2f5a8b1c4d7e0f3a6b9c2d5',
 'd6e3f9c2b5a8d1e4f7a0b3c6d9e2f5a8b1c4d7e0f3a6b9c2d5e8f1a4b7c0d3e6',
 'e7f0a3b6c9d2e5f8a1b4c7d0e3f6a9b2c5d8e1f4a7b0c3d6e9f2a5b8c1d4e7f0','王磊');

INSERT INTO quarantine.q_resume (account_id, subject_hash, version_no, content_pii) VALUES
('ACC-1001','f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c',1,'张伟 简历 v1（备份恢复残留）'),
('ACC-1002','f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c',1,'张伟 工作邮箱账号简历（备份恢复残留）'),
('ACC-2001','d6cd18bbe1b818ca839a3d021c0b14944a3ff7942913c7655ad43ee2ef04ae48',1,'李娜 简历（注销已撤回，放行）'),
('ACC-9001','a17f3c92e84b4d6f9c0a5e7d2b8f1c4a6d0e3f5a7b9c2d4e6f8a0b2c4d6e8f01',1,'王磊 简历（备份后新主体，放行）');

INSERT INTO quarantine.q_search_index_doc (subject_hash, index_name, doc_ref) VALUES
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','resume_search_v3','doc-1001'),
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','resume_search_v3','doc-1002'),
('a17f3c92e84b4d6f9c0a5e7d2b8f1c4a6d0e3f5a7b9c2d4e6f8a0b2c4d6e8f01','resume_search_v3','doc-9001');

-- 缺主体关联字段的旧系统残片
INSERT INTO quarantine.q_legacy_dump (account_id, name, phone_cipher) VALUES
('ACC-1001','张伟','enc:phone:1001'),
('ACC-9001','王磊','enc:phone:9001');

-- 附件备份登记：A 的两个 PDF + 李娜的 PDF
INSERT INTO quarantine.q_backup_file (subject_hash, object_key, bucket) VALUES
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','pdf/zhangwei-v1.pdf','resume-bucket'),
('f08840a3c5820142aea70eedb9da3dde321aee093eae2c5d95a22aae18094c8c','pdf/zhangwei-v2.pdf','resume-bucket'),
('d6cd18bbe1b818ca839a3d021c0b14944a3ff7942913c7655ad43ee2ef04ae48','pdf/lina-v1.pdf','resume-bucket');

-- 目录实际文件：备份清单声明了 lina-v1.pdf 但目录里缺失（缺件）；
-- 目录里多出清单未声明的 unknown-orphan.pdf（多件）
INSERT INTO quarantine.q_directory_listing (file_path) VALUES
('pdf/zhangwei-v1.pdf'),
('pdf/zhangwei-v2.pdf'),
('pdf/unknown-orphan.pdf');

INSERT INTO quarantine.backup_catalog (source_type, target_ref, asset_code, subject_column, note) VALUES
('TABLE','quarantine.q_account','AST_ACCOUNT','subject_hash','账号主表备份，带主体哈希'),
('TABLE','quarantine.q_resume','AST_RESUME','subject_hash','简历多版本备份，带主体哈希'),
('TABLE','quarantine.q_search_index_doc','AST_SEARCH','subject_hash','搜索索引派生记录备份'),
('TABLE','quarantine.q_legacy_dump',NULL,NULL,'旧系统残片，缺主体关联字段，须补齐来源后重检'),
('FILE','quarantine.q_backup_file','AST_PDF','subject_hash','简历 PDF 附件备份登记'),
('FILE','quarantine.q_directory_listing',NULL,NULL,'文件目录实际清单（缺件/多件比对来源）');
