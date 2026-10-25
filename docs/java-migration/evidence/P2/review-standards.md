# P2 规范轴独立审查报告

- 审查员：P2 规范轴独立审查（独立上下文，只读）
- 日期：2026-10-25
- 审查基准：`c7cbab3`（集成分支 `codex/p1-p2-longrun`）；派生范围 `d982f3a..c7cbab3`（d982f3a = longrun 分叉基线，P0 回执提交）
- 说明：审查开始时工作区 HEAD 已在 `49eae24` 且存在未提交的规范轴在途改动（`platform/errors/ApiExceptionHandler.java`、`migration/contracts/interface-changes.md`、`evidence/P2/review-spec.md`）。本报告全部结论仅以 `git show c7cbab3` / merge-base 差分为准，不涉及在途内容。

## 1. 写入范围核查（worker 分支独占路径）

方法：对 6 个合并提交取 `merge-base(P1,P2)..P2` 差分（避免把主线上后发生的变更误记为 worker 写入）；另核对各集成者独立提交的文件清单。

| 合并 | worker 分支 | 独占路径符合性 |
|---|---|---|
| 17b5c95（J2-01） | worker/j2-01@f7333b0（base ce6a139） | 仅 `MAIN/auth/**`、`TEST/auth/**`、`FIX/J2-01/**`、evidence/J2-01、handoffs/J2-01.md。✅ |
| f92ccab（J2-02） | worker/j2-02@b185f65（base c4f305a） | 仅 `admin/**`（含 NoOpLocalTaskCancellation/LocalTaskCancellationPort）、`TEST/admin/**`、`FIX/J2-02/**`、证据+handoff。✅ |
| 3a7f90e（J2-03） | worker/j2-03@5d3f93f（base bed823a） | 仅 `assignment/catalog|report/**`、`TEST/assignment/**`、`FIX/J2-03/**`。测试类位于 `TEST/assignment/` 根而非同名子路径——已在 interface-changes.md「布局登记」且与 J2-04 `submission/**` 无冲突。✅（登记偏差） |
| 3805e3d（J2-05） | worker/j2-05@e35bac0（base bed823a） | 仅 session/usage/source/template/grading.query + TEST 同名 + `FIX/J2-05/**`。未触碰 STATUS/MAIN/interface-changes（a6bf0d1 的 MessageInput 扩展为主 Agent 提交，响应 IP-J205-1 变更请求并登记）。✅ |
| 69fef5e（J2-04） | worker/j2-04@cdd8b20（base 44fdbec） | 仅 `assignment/submission/**`、`TEST/assignment/submission/**`、`FIX/J2-04/**`。未触碰 MCP/usage 基础包。✅ |
| c7cbab3（P2回归） | worker/p2-regression@fbbd54d（base 9ba2378） | 仅 `evidence/P2-regression/**`（含 scripts/）+ `handoffs/P2-REGRESSION.md`；未提交 backend-java 任何代码；未提交 STATUS/MAIN（P1..P2 直连差分中出现的 M STATUS/MAIN 为分支点产物，`git diff 9ba2378 fbbd54d -- STATUS MAIN` 为空）。✅ |

主 Agent 公共文件提交核对：c4f305a/bed823a/a6bf0d1/c85cf4b 仅 STATUS、handoffs/MAIN.md、interface-changes.md；7d54ee6/39abbd5 仅 MAIN.md。六个 worker 对 pom/contracts/platform/db-migration/STATUS/MAIN/DECISIONS 的触碰为 **0**（逐分支 `git diff --name-only <base>..tip | grep -E 'pom\.xml|migration/contracts/|platform/|STATUS|MAIN|DECISIONS'` 全空）。

启动装配修复批次核对：
- 44fdbec：`AdminUserRepository`+`@Repository`、`AuthSettingsProvider` 多构造器 `+@Autowired`，2 行/文件，纯注解。
- 9ba2378：`--numstat` 显示全部 M 文件仅 +2~4 行（import+注解），**0 删行**；新增仅 3 个 `@Configuration` Bean 装配类（SubmissionWiring/SessionWiring/SourceWiring，纯 @Value 绑定）；`R100` 将 catalog 基准 fixture 从 test→main/resources（纯移动）。无业务逻辑变更。✅

**结论：通过，无越权写入。**

## 2. 证据链核查

测试数与声明一致（`grep -c '^\s*@Test$'` 逐类核对，@TestInstance 不计入）：

| 卡 | 测试类（@Test 数） | 合计 | STATUS 声明 |
|---|---|---|---|
| J2-01 | AuthContract 13 / CredentialLifecycle 14 / LoginThrottle 6 | 33 | 33/33 ✅ |
| J2-02 | AdminTree 20 / AdminDeletion 10 / PasswordReset 5 | 35 | 35/35 ✅ |
| J2-03 | Catalog 13 / Report 4 / CsvExport 5 | 22 | 22/22 ✅ |
| J2-04 | Submit 11 / QuotaIntegration 6 / Race 4 | 21 | 21/21 ✅ |
| J2-05 | SessionLifecycle 9 / SessionQuery 7 / Quota 6 / Usage 6 / SourceTemplate 5 / GradingQuery 4 | 37 | 37/37 ✅ |

全量链条：P1 四卡 150（12+15+68+26+29 逐包核加）→183→218→240→277（240+37）→298（277+21）✅。

断言体抽查（均无空转断言）：
- `AuthContractTest`：golden 19 条 observation 逐字段断言；401 体**字节精确** `{"detail":"missing or malformed bearer token"}`；token 43 位 base64url 形态、expires_at=签发+7d 微秒序列区间断言。
- `CsvExportContractTest:106-108`：golden CSV **字节长度 + SHA256 相等**、Content-Type/Disposition 头、引号/组间空行/未分配最后。
- `GradingPersistenceRaceTest`：双原生连接行锁竞争 + CountDownLatch 确定性裁决；端到端 MCP 中途停号变体（onCall 钩子）。
- `QuotaTransactionTest`：`assertThatThrownBy` + 回滚后计数断言（`chat_count==2`、会话 0 行）、checkAndCountDaily 失败计数保留语义逐条断言。
- `AdminDeletionTransactionTest`：56 处 assertThat（真实 PG 故障注入）。

P2-regression 原始日志抽 2 份（01-07 中取 04、07）：
- `logs/04-submission.log` 与 behavior-evidence 场景 4 逐项一致：200 成绩体、best 80/100*20=16.0、submission_count=2、`hash=4ec45beb8c6b…` 仅 hash、源码特征串 leak 0、停号 401/他人不受影响、429 精确第 21 次。
- 配额计数 22 的算术可完整重建：学生04 = S4.4 成功 1 + 循环 #2..#21（20 次先递增后检查，#21 超额递增已提交）+ `run_04_submission.sh:91` 补拍 429 响应 1 次 = 22 ✅；harness tool_calls 23 = 场景3 list_protocols 1 + 学生03 2 + 学生04 成功 20 ✅。
- `logs/07-react-cors.log`：vite 200 + `<div id="root">`、预检放行五头、带 Origin 登录 200+ACAO、`evil.example` 403 对照。✅

golden 重放（08）：PASS 78 / STATE-DIFF 6（格式类一致、行数/账号集差异，逐条列出）/ REPLAYED 15 / SKIP 87（五类理由）与 `logs/golden-diff.json`、README 汇总一致。

**结论：通过。**

## 3. 安全纪律核查

- `backend-java/.env.example`：DB 口令留空（`DPDK_JAVA_DB_PASSWORD=`），无任何真实值；DB 仅指向 `127.0.0.1:55481`（jdbc URL 第 3、11 行）✅。
- 证据 token 明文扫描：43 位 base64url 正则命中均为测试方法名与 SHA-256 摘要（如 sensitive-scan.md:17、csv-bytes.md:9），无 token/bcrypt 字面量；P2-regression 日志统一 `<TOKEN_MASKED>/<PW_MASKED>/<BCRYPT_MASKED>`（common.sh mask() 五条规则，实测日志体确认为打码形态）✅。
- fake MCP 不落/不打印源码：
  - Java `FakeMcpServer`：仅 AtomicLong 计数 + 内存 payload，无源码记录/输出；
  - Python `fake_mcp_harness.py:110-116`：账本仅 tool/protocol/长度/sha256 前 12 位，注释与实现一致 ✅；
  - 日志内 SQL leak-check（code_hash/results_json/api_tokens）0 命中 ✅。
- 无真实模型/评测出网：全部证据 URL 为 127.0.0.1/localhost（evil.example 仅为 CORS 负例请求头，非外联）✅。

发现：
- **[建议] S-1** `evidence/P2-regression/scripts/common.sh:13` 硬编码 `export PGPASSWORD=p1-java-synthetic`（连同 run_04 脚本中种子口令=学号）。属合成隔离实例且库已 DROP，README 也声明「仅存在于独立测试库」；但与「配置交付使用模板/不提交凭据」纪律存在张力，建议终稿包装时改为环境注入或独立未入库 secret 文件。

**结论：通过（1 条建议）。**

## 4. integration_pending 诚实性核查

| 项 | 登记位置 | 现状核查 |
|---|---|---|
| IP-1 停号→任务取消 | J2-02/integration-pending.md（「不得把本项写入完成/通过记录」） | 未被任何报告写成通过；`NoOpLocalTaskCancellation` 注释经 9ba2378 后仍保留「不得据此宣称停号→任务取消集成完成」（该提交仅 +`@Component`）✅ |
| IP-2 auth 消费缺口 | J2-02/integration-pending.md → interface-changes.md:58-59「登记待后续消费者需要时再改，当前不阻塞」 | 如实挂起，未宣称修复 ✅ |
| IP-3 差分 suite 未跑 | J2-02/IP-3 → MAIN「本轮回归覆盖中」→ P2-regression golden 重放（契约 DIFF=0）闭环 | 有实证闭环 ✅ |
| IP-J205-1 MessageInput | J2-05/integration-pending.md（含「不得视为 appendUser 已通过」）→ 主 Agent a6bf0d1 扩展并登记后关闭 | 流程合规（先登记后改契约）✅ |
| IP-J205-2 活动会话删除×任务 | J2-05/integration-pending.md（「不得写成已通过」）→ interface-changes.md、MAIN:7 | 仍开放、归 J3-01 ✅ |
| TaskGradeParticipant | handoffs/J2-04.md:29,46（归 J3-01）| 未宣称实现 ✅ |
| secure-headers 四头缺失 | J2-01/endpoints-matrix.md:78,85 + golden-diff.md → P2-regression golden-diff.md 差异① live 确认 | 诚实保留为平台层缺口 ✅ |
| 500 异常渲染缺口 | interface-changes.md（「未补前不宣称该分支兼容」）、J2-04 handoff、MAIN:7 | 基准时点仍开放、如实登记 ✅（基准后 49eae24 在途改动疑似补此项，不在本审查范围） |

STATUS 任务表「场景/用户验收」列全部「未执行」，阶段放行表 P2「用户验收未验收」——无以自动验证冒充场景/用户验收的表述 ✅。

**结论：通过。**

## 5. STATUS / MAIN / handoff 一致性核查

- 数字链条自洽：33/35/22/21/37 逐类核对成立；全量 150→183→218→240→255→277→298。中间值 **255** 可解释：a07c9fe 提交于 J2-05 worker worktree（分支点 bed823a，其时主线基线 218），218+37=255 为该 worktree 真实全量；合并后主线口径 240+37=277。非矛盾，但提交信息未注明口径差异（见 S-3）。
- 各卡 handoff、interface-changes.md、evidence 与 STATUS 表格无相互矛盾；MAIN「P2 五卡全部集成（183/218/240/277/298）」与 STATUS 表一致。
- 发现：
  - **[建议] S-2** `docs/java-migration/STATUS.md:9` 仍写「当前实施阶段：P1实施中」，与同文件任务表（J2-01..J2-05 implemented、全量 298/298）及 MAIN「P2 五卡全部集成」矛盾；`STATUS.md:24` J1-01 仍「in_progress」（P1 已收口）；阶段放行表 P2 行仅「实施启动（J2-01起）」未更新到回归完成；`STATUS.md:13`「当前无在跑worker/应用/PG」与 P2 回归实际执行过不符。任务表为权威且正确，但 STATUS 是唯一状态源，叙事行滞后易误导恢复。建议 P2 phase-report 终稿时同步刷新。
  - **[建议] S-3** `handoffs/J2-05` 提交信息「全量 255/255」未注明 worktree 基线口径（218+37），建议在 handoff/evidence 中补一句口径说明，避免后续审查误判。
  - **[建议] S-4** `evidence/P2-regression/golden-diff.md` 差异④（非允许来源预检 Python 400 vs Spring 403）与差异①（secure-headers 四头缺失）属在网路由上的状态码/响应头差异，按「平台/环境差异」分类使「契约 DIFF=0」仅在该分类下成立。已透明登记，但建议在用户验收前将其升级登记入 DECISIONS.md/interface-changes.md 为「明示接受的偏差」，避免验收争议。
  - **[建议] S-5** `handoffs/MAIN.md:7-10` 存在两段分层重复（两处「运行资源」、两处「已知 integration_pending」，line 7 新、line 10 旧），内容一致但易误读，建议收口时合并为单段。

**结论：基本一致，5 条建议（S-2 优先）。**

## 总结论

- **阻断：0 条；建议：5 条（S-1..S-5，无阻断级）。**
- 写入范围、证据链、安全纪律、integration_pending 诚实性、状态一致性五轴全部通过；全部数字声明（33/35/22/21/37、150→298、78/6/15/87、配额 22）均可在源码、测试或原始日志中复核。
- P2 规范轴审查通过，可进入 P2 phase-report 收口；建议收口时一并处理 S-2（STATUS 叙事行刷新）与 S-4（偏差升级登记）。
