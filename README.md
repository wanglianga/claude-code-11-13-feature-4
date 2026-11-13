# 候选人注销（被遗忘权）工单与删除作业平台

招聘平台把候选人注销当成**要管到底的工单**，而不是在主库执行一条 `DELETE` 就关单。本平台围绕监管问询函场景，
覆盖从主体归并、范围枚举、不可变快照、四类处置、回执对账到证据包出具的完整闭环。

- 后端：Spring Boot 3.5（Java 25）+ Spring Security + JdbcTemplate + Flyway + PostgreSQL 18 + springdoc-openapi
- 前端：Vue 3 + Vite 6 + Element Plus + Pinia + Vue Router（HTTP Basic 登录）
- 部署：Dockerfile + docker-compose（PostgreSQL + 后端 + 前端 Nginx 反代）一键启动

## 一、需求口径与实现对照

| 题目要求 | 实现位置 |
| --- | --- |
| 按手机号/邮箱/证件号哈希把多账号、多版简历归并为候选主体，**姓名相同不构成合并依据** | `SubjectService` 联合查找（连通分量），姓名仅用于展示，不进入主体哈希 |
| 合规专员逐条确认标识符匹配，再确认主体 | `/api/identifier-matches/{id}/decide`、`/api/subjects/{hash}/confirm`，存在待决匹配时禁止确认主体 |
| 注销渠道、授权撤回时点、15 个工作日期限随工单记录 | `work_order` 表；`WorkdayUtil` 跳过周末计算法定到期日 |
| 范围枚举沿血缘**实时全图遍历**（不用静态资产清单，避免新接入表漏掉） | `LineageService.traverse()` 从根资产 BFS，血缘断链不跨越、转人工确认点 |
| 接入登记与血缘注册绑定为一次操作 | `/api/assets/register` 单事务，父资产缺失整体回滚（有集成测试） |
| 枚举结果执行前冻结为**不可变快照**；事后补登记只生成增补工单，不能追溯改写 | `enum_snapshot/snapshot_item` 带清单哈希；`supplement_ticket` 独立处置，原快照条目数与哈希不变（有集成测试） |
| 四类处置分别记账：物理清除 / 重算聚合 / 等待保留期 / 已流出通知 | `disposition_record.category`，漏斗重算记录 before/after/diff，报表不会“自己跳一下” |
| 同主体多渠道重复提交按“主体哈希+渠道”幂等并单 | `work_order.idempotency_key` 唯一约束，重复提交返回 `idempotentHit` |
| 删除作业按“工单+存储位置”幂等重放，备份不被清两遍 | `disposition_record.idempotency_key` 唯一约束 + 已终态位置跳过 |
| 执行前影响面预览、可撤回延迟窗口、候选人撤回、漏登记/回执未到退回当前节点 | `/preview`（设置 `cancel_before`）、`/cancel`、`/return`、`/resume`；退回保留已确认主体与冻结结论 |
| 第三方销毁不在本地事务边界，凭回执推进，回执未到只能挂着 | `notification_outbox` + `WAITING_RECEIPT` 状态；无回执时对账接口拒绝关单 |
| 逐位置对账，对不上的单列交合规复核 | `reconciliation_issue`，存在 OPEN 问题阻止关单（有集成测试） |
| 样本进过模型、参数无法回退；是否重训与效果变化写入模型卡，与物理删除分开展示 | `model_card` 表与独立页签，记录决策、前后效果、理由 |
| 抽样反查哈希、执行时间、证据包索引统一落库 | `recheck_hash`、`executed_at`、`evidence_package`（含 SHA-256 包哈希与落盘 JSON） |
| **备份恢复先入隔离区，复查后才开放，不直接恢复线上再补删** | `restore_batch/restore_location` + `quarantine` 隔离 schema；恢复仅落隔离表，开放批准后 CLEAN 数据才按列复制上线（有集成测试） |
| **从当前仍有效删除记录提取主体指纹与处置结果比对账号/附件/派生记录** | `deletion_credential` 每次扫描实时重建；命中有效凭证→隔离拦截，无凭证→放行，处置结论无法自动判定→转合规 |
| **检查期间新办结注销追加进本次扫描；开放前再次核对，不只查启动名单** | 扫描轮次 `scan_round` 动态追加；开放申请重算凭证集合指纹，指纹变化（`credential_fingerprint`）强制重扫（有集成测试） |
| **缺主体关联字段停待核验；文件目录与清单不一致列缺件/多件，补齐后重检** | `subject_linkable=false`→`PENDING_VERIFY`（补 `linkage_json` 映射）；`restore_file_diff` 记录 MISSING/EXTRA，未解决文件位置 `BLOCKED`（有集成测试） |
| **独立批次号；失败重试接着处理未完成位置，不重复释放已核验数据** | 每批次 `batch_no` 唯一；单位置独立事务（REQUIRES_NEW），复查项自然键幂等、上线插入带 NOT EXISTS 去重（有集成测试） |
| **全部位置通过检查并登记资产血缘后才能提交开放申请** | 开放前强制所有位置 `CHECKED` 且 `lineage_registered`，否则 409；血缘复用/新登记资产并挂接 `AST_BACKUP` |
| **残留、处理动作、放行依据作为新记录附原删除工单，不更换当年证据说明** | `restore_residual_record` 挂 `original_ticket_id`，工单详情“备份恢复复查附记”页签展示；原证据包与处置记录原样保留 |

## 二、快速启动

### 方式 A：Docker Compose 一键启动（推荐）

```bash
docker compose up -d --build
```

- 前端操作台：http://localhost:8088
- 后端 API：http://localhost:8080
- Swagger UI：http://localhost:8080/swagger-ui/index.html （或经前端 http://localhost:8088/swagger-ui/index.html）
- PostgreSQL：localhost:5432，库/用户/密码 `rtbf` 环境见 compose（默认 `ccdev/ccdev`）
- Flyway 在后端启动时自动建 schema（`compliance`、`biz`、`quarantine` 隔离区）并写入演示数据

停止：`docker compose down`（加 `-v` 同时清除数据卷）。

### 方式 B：本地开发启动（使用容器内预装运行时）

前置：本机 PostgreSQL 已运行，先建库：

```bash
PGPASSWORD=ccdev psql -h 127.0.0.1 -U ccdev -d postgres -c "CREATE DATABASE rtbf;"
```

后端（默认读取 `jdbc:postgresql://127.0.0.1:5432/rtbf`，可用 `DB_URL` 等环境变量覆盖）：

```bash
cd backend
mvn spring-boot:run
# 或打包后运行：mvn package -DskipTests && java -jar target/rtbf-console.jar
```

前端（Vite 已配置 `/api` 代理到 127.0.0.1:8080）：

```bash
cd frontend
npm install --registry=https://registry.npmmirror.com
npm run dev          # http://localhost:5173
```

## 三、测试账号（HTTP Basic / 登录页通用）

| 角色 | 用户名 | 密码 | 权限 |
| --- | --- | --- | --- |
| 合规专员 | `compliance` | `Compliance@2026` | 主体确认、断链纳入/排除、对账复核、模型卡决策、证据包、恢复复查人工判定/文件差异处置/开放审批 |
| 运维操作员 | `operator` | `Operator@2026` | 枚举冻结、预览、执行、回执登记、资产登记、增补执行、恢复批次登记/隔离装载/复查扫描/血缘登记/开放申请 |
| 只读审计 | `viewer` | `Viewer@2026` | 全量只读 |

## 四、演示数据与验证方式

演示库内置三个主体：

- **张伟 A**（主体哈希 `f08840a3…94c8c`）：两个账号 `ACC-1001/1002`，靠证件号哈希归并；有 PDF、搜索文档、特征向量、
  训练样本、3 条投递、已到期/未到期导出、未到期备份、第三方外发、断链遗留磁带——覆盖全部四类处置。
- **李娜 B**（`d6cd18bb…4ae48`）：单账号，含未到期导出，适合演示撤回注销与保留期。
- **张伟 C**（`8dc68703…29fd7`）：**与 A 同名但标识符哈希完全不同**，系统必须识别为两个独立主体。

### 推荐验证路径（前端）

1. 用 `compliance` 登录 → “主体归并确认” → 点“扫描账号并归并主体”，确认出现 3 个主体且两个张伟未被合并；
   逐条接受标识符匹配后“确认主体”。
2. 用 `operator` 登录 → “注销工单” → 选择张伟 A、渠道“监管问询函”建单；**重复提交一次**观察幂等并单提示。
3. 进工单处置台：冻结枚举快照（12 项，含 1 个断链 `PENDING_HUMAN`）→ 合规确认断链纳入 → 影响面预览
   （四类处置计数 8/1/2/1，可撤回窗口 30 分钟）→ 执行删除作业。
4. “处置记账”页签查看漏斗汇总 `before={JOB-01=2,JOB-02=2,JOB-03=1}`、`after` 与每职位 `-1` 差额；
   备份位置为 `SCHEDULED`（等保留期）。
5. “第三方回执”页签登记回执 → “对账复核”执行对账 → 工单关单 → 生成证据包。
6. “模型卡”页签对 `job-match-embedding` 做重训决策；“审计时间线”查看全过程留痕。
7. 对李娜 B 重复建单并在预览后“撤回注销”，核对其业务数据原样保留。
8. “资产与血缘”页用不存在的父资产编码登记，观察整事务回滚；冻结后接入新资产（如 `AST_INTERVIEW_VIDEO`），
   在工单里生成并执行增补工单，回查原快照仍为 12 条。
9. **备份恢复复查（先办结张伟 A 的注销，再恢复历史备份）**：用 `operator` 在“备份恢复复查”页登记批次
   （备份日期 `2026-09-30`，备份清单含 `pdf/zhangwei-v1.pdf`、`pdf/zhangwei-v2.pdf`、`pdf/lina-v1.pdf`）
   → “恢复进隔离区” → “复查扫描”：已注销 A 的账号/简历/索引/附件全部**隔离拦截**，李娜 B 与备份后新主体放行；
   旧残片表因缺主体关联字段停在“待核验”，文件位置因缺件 `lina-v1.pdf`、多件 `unknown-orphan.pdf` 阻断。
10. 补齐：目录重扫关闭缺件、人工说明多件、给旧残片补 `item_ref→subject_hash` 映射后重扫 → 全部位置 `CHECKED`
    → “登记资产血缘” → 提交开放申请 → `compliance` 批准；回查线上库 A 数据残留为 0（旧简历不会重现搜索结果），
    B/新主体数据上线；在 A 的原工单“备份恢复复查附记”页签可见残留记录与放行依据，原证据包不变。

### 命令行快速验证（curl）

```bash
# 归并
curl -s -u compliance:Compliance@2026 -X POST http://127.0.0.1:8080/api/subjects/scan
# 工单列表 / Swagger JSON
curl -s -u viewer:Viewer@2026 http://127.0.0.1:8080/api/tickets
curl -s http://127.0.0.1:8080/v3/api-docs | jq '.info.title, (.paths|keys|length)'

# 备份恢复复查：可恢复来源 → 登记批次 → 隔离装载 → 复查扫描（状态/拦截/放行计数）
curl -s -u operator:Operator@2026 http://127.0.0.1:8080/api/restore/catalog | jq 'length'
curl -s -u operator:Operator@2026 -X POST http://127.0.0.1:8080/api/restore/batches \
  -H 'Content-Type: application/json' \
  -d '{"backupDate":"2026-09-30","sourceRef":"snapshot-20260930",
       "manifestFiles":["pdf/zhangwei-v1.pdf","pdf/zhangwei-v2.pdf","pdf/lina-v1.pdf"]}'
curl -s -u operator:Operator@2026 -X POST http://127.0.0.1:8080/api/restore/batches/1/restore
curl -s -u operator:Operator@2026 -X POST http://127.0.0.1:8080/api/restore/batches/1/scan | jq
```

### 自动化集成测试

```bash
# 测试使用独立库 rtbf_test（需先建库），测试类启动前自动 DROP SCHEMA 重建
PGPASSWORD=ccdev psql -h 127.0.0.1 -U ccdev -d postgres -c "CREATE DATABASE rtbf_test;"
cd backend && mvn test
```

7 个工单测试覆盖：未认证 401、同名不同人不归并、A 主体全生命周期（幂等并单/断链挂起/四类处置/重算差额/
重放幂等/回执/模型卡/对账关单/证据包）、撤回数据不动、登记事务回滚、增补不改写快照、对账发现篡改并阻止关单。

3 个备份恢复复查测试（`RestoreReviewIntegrationTest`）覆盖：
隔离拦截已注销主体而放行其他数据、缺主体字段停待核验、文件缺件/多件阻断与补齐重检、补齐来源映射后复查、
全部位置检查+血缘后才开放、残留与放行依据附原工单；检查期间新办结注销动态追加且开放前凭证指纹复核拦截旧名单；
失败重试只处理未完成位置、已核验数据不重复释放、人工判定项与重复开放防护。

## 五、实际执行过的验证结论（本环境）

以下均为在本容器内实际执行并通过的结果，非推测：

1. `mvn test`：**Tests run: 10, Failures: 0, Errors: 0**（原 7 个工单测试 + 新增 3 个备份恢复复查测试），全部通过。
2. 全新空库 `rtbf_demo` 上运行打包产物 `java -jar rtbf-console.jar`：Flyway 成功应用 V1~V8 共 8 个迁移（含 `quarantine` 隔离 schema），应用正常启动。
3. jar 上完整跑通张伟 A 工单：冻结 12 项 → 断链确认 → 预览（四类 8/1/2/1）→ 执行（10 执行/1 排期/1 等回执）
   → 回执登记 → 对账 `passed=true` 关单 → 证据包生成（64 位 SHA-256 哈希）。
4. 幂等性实测：同主体同渠道二次建单返回 `idempotentHit=true` 并回原工单；删除作业二次执行 `executed=0`，无重复清除。
5. 数据隔离实测：处置后张伟 A 主表/简历等残留为 0，其他两个主体账号数不变；漏斗 `JOB-03` 由 1 重算为 0 且差额留痕。
6. 撤回实测：李娜 B 预览后撤回，工单 `CANCELLED`，账号与简历数据原样保留。
7. 增补实测：快照冻结后接入 `AST_INTERVIEW_VIDEO` 并执行增补，删除生效，原快照条目数仍为 12。
8. 备份恢复复查实测（独立 jar + 集成测试双重验证）：
   - 隔离复查命中当前有效删除凭证，已注销 A 的账号/简历/搜索索引/附件/旧残片在隔离区拦截，批准开放后线上 A 残留为 0，
     旧简历不会重新出现在搜索结果；李娜 B（注销已撤回）与备份后新主体数据正常放行上线。
   - 缺主体关联字段的 `q_legacy_dump` 停在 `PENDING_VERIFY`，补齐 `item_ref→subject_hash` 映射后才参与比对；
     文件目录缺件 `lina-v1.pdf`、多件 `unknown-orphan.pdf` 被列出并将文件位置置 `BLOCKED`，补齐/说明并解决后才能重检。
   - 检查期间办结主体 C 注销后，用启动时旧名单申请开放被 409 拒绝（凭证集合指纹变化），重扫动态追加（`scan_round` 递增）后才放行。
   - 单位置失败独立事务回滚不拖垮整批；重试复查项不重复建项、开放插入按自然键去重，已核验数据不重复释放；
     残留记录与放行依据写入 `restore_residual_record` 并附在原删除工单后，原证据包保持不变。
9. Swagger UI HTTP 200，`/v3/api-docs` 返回 41 个接口路径（其中 `/api/restore/**` 13 个）；前端 `npm run build` 成功，
   `vite preview`（8081）HTTP 200，经 Vite 代理访问 API 未认证正确返回 401。

> 说明：本容器未安装 docker 命令，`docker compose up` 未在本容器内实际执行；Dockerfile/compose 已按标准多阶段
> 构建编写，后端镜像构建逻辑与本地 `mvn package` 产物一致，前端镜像与 `npm run build` 产物一致。

## 六、目录结构

```
.
├── docker-compose.yml          # 一键启动 postgres + backend + frontend
├── backend/
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/
│       ├── java/com/example/rtbf/
│       │   ├── config/         # Security 三角色、OpenAPI
│       │   ├── service/        # SubjectService / LineageService / WorkOrderService / RestoreReviewService / AuditService
│       │   ├── web/            # REST 控制器与全局异常（含 RestoreReviewController）
│       │   └── util/           # SHA-256 主体哈希、脱敏、工作日计算
│       └── resources/db/migration/  # V1~V8 Flyway 迁移（合规域 + 演示业务库 + 隔离区恢复复查）
└── frontend/
    ├── Dockerfile + nginx.conf # Nginx 静态托管并反代 /api、/swagger-ui
    └── src/views/              # 登录、主体归并、工单列表、工单处置台、资产血缘、备份恢复复查（批次/复查台）
```

## 七、关键设计边界

- **快照不可变是硬约束**：冻结后只允许更新断链快照项的纳入/排除状态，不允许增删条目；补登记资产走增补工单。
- **第三方回执是异步边界**：本地数据库事务无法覆盖合作方销毁动作，工单状态机在 `WAITING_RECEIPT` 挂起，
  禁止无回执人工推进。
- **模型参数不承诺回退**：训练样本物理删除与模型参数处置分表陈列，模型卡如实记录重训决策与效果变化，供法务出具说明。
- **对账是关单前置条件**：任何 OPEN 对账问题都会阻止工单进入 `COMPLETED`。
- **备份恢复必须隔离检查后开放**：恢复内容只进 `quarantine` 隔离 schema，禁止直接恢复线上再补删（恢复与补删之间会暴露旧简历）；
  代价是多保留一份临时数据并等待检查完成。所有位置通过检查并登记资产血缘后才能提交开放申请。
- **复查基准是“当前仍有效”的删除凭证**：每次扫描实时重建凭证，检查期间新办结的注销会动态追加（`scan_round`），
  开放前重算凭证集合指纹再次核对；删除凭证（`deletion_credential`）长期保留可比对的指纹。
- **复查结论只追加不改写**：残留、处理动作、放行依据作为新记录附在原删除工单后，当年已出具的证据说明与原处置记录原样保留。
