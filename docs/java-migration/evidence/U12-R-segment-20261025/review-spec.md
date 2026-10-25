# U12 R段 规格轴独立审查（review-spec）

日期：2026-10-25。审查对象：`codex/p3-p5-longrun@4debf9f`（含 f38aff9 / 8c89318 / d9364ed）。
对照：`evidence/P0-P2-acceptance-20261025/README.md` 各项"修复验收"原文；R段报告 `evidence/U12-R-segment-20261025/README.md`。
审查方式：只读。逐项核对验收文本、源码/测试、探针与重放证据；未运行任何写操作。

**总结论：不通过（1 项阻断：SP-04 实测证据缺口；其余 7 项通过、5 条建议）。阻断关闭后无需重开其他项。**

## 逐项结论

### 1. ST-01 —— 通过（附建议）

- 预检实现：`backend-java/.../platform/pg/TestPg.java` `requireInstanceIdentity()`（L102-131）在任何 CREATE/COMMENT/DROP 之前执行：配置缺失抛出（不连接）、current_user 逐字相等、`SHOW data_directory` 经 `DatabaseIdentityGuard.isUnder` 前缀语义核对；连接失败/身份不符一律抛出且零写入。`dropDatabase`（L89-95）受同一预检。
- 负例 `TestPgIdentityGuardTest`（8 项）逐条对照验收：错 data-dir（wrongDataDirectoryZeroWrites）、错角色（wrongUser）、错端口（wrongPort，连接失败）、错 host（wrongHost）均以 `pg_database` 中 `p1_t_%` 计数不变证明零写入；`dropAlsoRequiresIdentityAndKeepsTarget` 证明错配时目标库不被删除；`incompleteConfigRefuses` 配置缺失拒绝。`@EnabledIfEnvironmentVariable(DPDK_TEST_PG_URL)` 且全量 verify Skipped=0，负例确已执行。
- "测试标记"维度：负例存在但分两层——guard 层 `DatabaseIdentityGuardNegativeTest.missingCommentRejected`（注释标记不符拒绝）与 TestPg 层 `hostileTagYieldsSafeIdentifierOnly`（恶意 tag 只产生安全标识符）。后者未断言 `p1_guard` 不存在或计数不变，零写入证明是间接的（见建议 S-1）。
- finally 清理：TestPgIdentityGuardTest 3 个建库用例与 DatabaseIdentityGuardNegativeTest 5 个负例全部 finally `dropDatabase`。
- 150 guard 库清理：`.migration-artifacts/acceptance-20261025/u12-guard-cleanup-before.txt` 恰 150 条 `p1_t_*`；after 仅剩 `p1_java_accept_20261025`、postgres、template0、template1，验收库与系统库保留，范围限定正确。

### 2. ST-02 —— 通过（附建议）

- 严格判定器：`P2-regression/scripts/run_08_golden.py` `compare_case`（L283-310）HTTP 状态为硬前置（状态差且未声明即 DIFF，`ALLOW allow` 为集合包含校验）；`norm_eq` 保序保重复 + 实体双射；无隐式 STATE-DIFF，未声明差异一律 DIFF（L309-310）。
- 声明表：`DECLARATIONS`（L190-265）逐条 Decl(kind/reason/ref)，`match_decl` 精确 cid > suffix > route 子串，三段优先级各自顺序扫描。SKIP 语义经核实是精确的：`GET /api/chat/sessions` 不含子串 `POST /api/chat`，故会话存取路由被严格比较（invalid-input 422、DELETE 204 均为真 PASS），仅 POST /api/chat 及 stream 命中 SKIP。
- `--selftest` 17/17（`logs/selftest-standalone.txt`，rc=0）：验收要求的三种变异（错状态同体→DIFF、反序→DIFF、80→99→DIFF）全部 FAIL；另含重复身份双射捕获、bool/int、键集/长度差异；不读黄金、不连库。
- 黄金只读：`git log migration/fixtures/p0-v2/python-golden.json` 仅冻结提交 099dca2 触及；SHA256 `8d065866…7de14` 与验收报告冻结记录一致。重放只读黄金，未用 Java 输出重写。
- ALLOWED-DIFF 25 条逐一核验：每条 detail 含 `kinds=`、原因、`依据` ref；且与验收报告不冲突——平台路由（health/docs/metrics 等）兜底 500 是验收报告第 104 行自行登记的框架错误面（P2-D1 / J4-03/J5-01），CORS 400→403 为 P2-D1 已登记差异。
- PASS=72 / ALLOWED-DIFF=25 / SKIP=89 计数与 `logs/golden-diff.json`（186 cases）逐条复核一致。

### 3. ST-03 —— 通过（附建议）

- `run_00_setup.sh`：路径由脚本位置推导；`DPDK_TEST_PG_DATA_DIR`/`DPDK_TEST_PG_PASSWORD` 必填 fail-fast；三段零写入核验（实例身份、库名未占用）后才 CREATE，库名含本轮 round 标记；身份写入 `logs/replay-state.json`。
- 身份文件：`git check-ignore` 确认被 `logs/.gitignore` 覆盖；权限 600（实测 `-rw-------`）；内容含 round/base_url/pg.*/accounts(学号)/tokens/assignment_ids，无口令字段（口令仅从环境读取，common.sh L38 `:?` fail-fast，无字面量）。
- 依赖审计：scripts/tools 无 `/tmp/p2reg`、无 `/Users/fsy`、无绝对路径命中；BcryptGen.java 已入仓 `tools/`；`py_compile`/`bash -n`/空环境 fail-fast（列出 15 项缺失）有交接命令级记录（ST0203-REPLAY.md）。
- 干净临时目录五轮重跑：末轮链路完整入库（`08-golden-replay.log` 头部记录本轮库 `p2replay-20261025_162928` + 身份文件）；R段 README 登记四轮库名 DROP 清单 + 首轮建库失败未成库，轮数自洽；但逐轮日志未留存（见建议 S-4）。

### 4. SP-01 —— 通过（附建议）

- 四组合：`ModelWiringCombinationsTest` 4/4（chat-only / embedding-only / both / none），构造即止、无真实调用；none 组合明确断言无 ModelClients 及模型 Bean。`application.yml` 无任何模型 base-url 默认值（grep 证实），无默认外部端点成立。
- 真实进程探针：`sp01-embedding-only.log` 显示真实进程 18091 启动成功（修复前该配置退出 1 缺 Bean）；日志内未记录启动命令与环境变量、也无 health 200 响应原文，归因依赖文件命名与时间线（见建议 S-2）。

### 5. SP-02 —— 通过

- `AssignmentSubmitContractTest.submitJsonBoundariesMatchPythonLoads`（L729-789）逐边界：空→422、纯空白→422、尾随垃圾→422、多根对象→422、合法对象+尾随空白→200（Python json.loads 容忍，控制组含 1 次调用/扣次/落库）、合法非对象 null/123/"text"/[1,2]/true→500 TypeError 面；拒绝情形经 `assertNoSideEffects` 断言零 MCP / 零配额计数 / 零成绩行。实现 `SubmitRequestParser` 用 `FAIL_ON_TRAILING_TOKENS`（L50）对齐 json.loads。
- 真实进程：strict probe `submit_json_empty/whitespace/trailing_garbage` 422，与冻结 Python AST 抽取预期一致（`strict-probes-u12.json` 32/32）。

### 6. SP-03 —— 通过

- `resultsFalsyPayloadsPreserveGradedOutcome`（L797-845）逐值：false/0/0.0/null/空串/空对象 6 值各 200 + `results:[]`，合计 6 次 MCP、扣次 +6、成绩行 +6（HTTP/调用次数/扣次/落库/响应五维全比较）；空数组与正常数组透传（元素字段断言）；truthy 非列表 true/5→500 且调用与扣次已发生、零新增成绩行。实现 `resultsOf`（AssignmentSubmissionService L195-217）甚至覆盖 Python `list()` 对非空串（逐字符）与对象（键列表）的迭代语义，超出验收要求。

### 7. SP-04 —— **阻断**（代码修复成立，实测证据未达验收文本）

- 已证：唯一优先级 `dpdk.grading.* > GRADING_* > 源默认`（`SubmissionWiring` 嵌套占位符 + `explicitPropertyOverridesLegacyEnv`）；装配测试 4/4 证明三个环境键均被消费、缺省回退、非法值启动失败（quota -1/abc、timeout 0）。
- 真实进程实测仅覆盖 quota：`sp04_probe.py`/`sp04-probe.json` 7/7（GRADING_DAILY_QUOTA=2 → 第 3 次 429 + 失败计数保留）。
- **判断：装配测试不算"实测"**——它验证的是"环境键解析进 Bean 与优先级"，不是行为。验收原文要求"以非默认 quota/max bytes/timeout **分别实测**"：
  - timeout：非默认值行为有 JVM 内测试（`GradingQuotaIntegrationTest.deadlineExceededBeforeSubmissionMaps504` 用 `SubmissionSettings(1,…)` → 504"评测超过 1 秒"），但未走 GRADING_TIMEOUT_SEC 链路、非真实进程；
  - max bytes：仅有**默认值** 262144 的 413 行为测试（`sizeLimitOrdering` 断言文案含 262144），无任何非默认值实测；非法值启动失败断言也未覆盖 GRADING_MAX_CODE_BYTES。
  - 交接与探针记录均无这两个变量的实测证据（`sp04_probe.py` 注释自证仅 quota）。
- 阻断理由：R段报告据此声明"SP-04 已关闭"，超出了冻结修复验收的字面要求；本项目此前的教训正是关闭声明超出证据。补救成本低（见阻断项 B-1）。

### 8. SP-05 —— 通过

- 三项证据齐备：(a) 统一配置——`application.yml` L32 默认 `http://localhost:5174,http://127.0.0.1:5174`、`backend-java/README.md` L41-45 更正、`frontend/package.json` dev 固定 `--port 5174` 与 `vite.config.js` port 5174 一致（对齐冻结 Python `FRONTEND_ORIGINS` config.py:116）；(b) README 原样浏览器实测——`browser-summary-u12.json`（页面挂载/教师登录→作业列表→成绩总览/学生登录→作业页/console 零错误）+ 截图 `teacher-grades-u12.png`，无 `--port` 覆盖；(c) 仍拒绝非允许来源——strict probe `cors_vite_5173`→403、`cors_vite_5174`→200。

### 9. 复验完整性 —— 通过

- 316/316：`u12-verify-full.log` 末行 `Tests run: 316, Failures: 0, Errors: 0, Skipped: 0` + BUILD SUCCESS；298 既有 + 18 新增（8+4+4+2）数目自洽；Skipped=0 排除"跳过计通过"。
- 探针 32/32：`strict-probes-u12.json` 实测 32 项 0 失败，含 SP-02/03/05 关键面；与验收报告"修复前 25/32、7 差异"口径衔接。
- 重放 DIFF=0：`golden-diff.json` 机器可读，186 case 计数吻合；SKIP 89 条为独立 verdict 类别、逐条登记理由与依据，未混入 PASS；R段报告的复验表如实分列 PASS/ALLOWED-DIFF/SKIP。

### 10. 范围纪律 —— 通过

- R段 Java 变更仅 f38aff9（12 个文件，全部对应 8 项修复 + 测试），其后无任何 backend-java 变更（`git log f38aff9..4debf9f -- backend-java` 为空）；未新增 chat POST/SSE、任务、讲解路由。
- `STATUS.md`：J3-01～J5-04 仍为 planned 未分派；integration_pending 三项明确"留 P3"（L62、L67），未被悄悄标记完成；chat 会话存取路由系 J2-05 既有实现（fa887b7），重放中按严格口径真实比较，不属越界。

## 阻断项清单

- **B-1（SP-04，证据缺口，代码无需改动）**：补两项真实进程（或至少显式走 GRADING_* 环境链路的端到端）非默认实测并留证：`GRADING_MAX_CODE_BYTES`=小值 → 提交 413；`GRADING_TIMEOUT_SEC`=1（慢 fake MCP）→ 504；同时补 `GRADING_MAX_CODE_BYTES` 非法值启动失败的装配断言。完成后 SP-04 可关闭，其余项不受影响。若主 Agent 认为按装配+组合证据关闭，须先在 DECISIONS 登记对该验收句式的口径偏差，不得静默。

## 建议项清单（不阻塞）

- S-1（ST-01）：`hostileTagYieldsSafeIdentifierOnly` 增加断言——注入形态库名（如 `p1_guard`）不存在且建库前后 `p1_t_%` 计数不变，使"测试标记"维度的零写入证明直接化。
- S-2（SP-01）：探针日志自描述化——记录启动命令与生效环境变量（或在日志输出生效的 model 配置行）及 health 200 响应，避免归因依赖文件命名。
- S-3（ST-02 残余风险）：状态相关成功面声明对 10 个列表端点放开 BODY 整体差异（含 `GET /api/assignments::success`），行形态回归仅靠场景日志兜底；建议后续把 ALLOW 收窄为"结构/键集一致 + 行数差与场景日志一致"，或在 golden-diff.md 显式登记该残余风险。
- S-4（ST-03）：五轮重跑仅末轮日志入库，前几轮以库名清理记录佐证；后续留存逐轮 rc/摘要一行记录于交接。
- S-5（ST-03 次要）：`common.sh` L35 `PGDATABASE` 缺失时回退固定名 `p1_java_candidate`，与整体 fail-fast 风格不一致（非口令、误配时 psql 报错），建议改为缺失即退出。

## 证据索引（本文引用）

- 源码/测试：`backend-java/src/test/java/io/dpdk/assistant/platform/pg/TestPg.java`、`TestPgIdentityGuardTest.java`、`DatabaseIdentityGuardNegativeTest.java`；`.../platform/wiring/ModelWiringCombinationsTest.java`；`.../assignment/submission/AssignmentSubmitContractTest.java`（L729-845）、`SubmissionWiringEnvBridgeTest.java`、`SubmissionWiring.java`、`AssignmentSubmissionService.java`（L195-217）、`SubmitRequestParser.java`（L50）。
- 重放：`docs/java-migration/evidence/P2-regression/scripts/run_08_golden.py`（L190-310）、`logs/golden-diff.json`、`logs/08-golden-replay.log`、`logs/selftest-standalone.txt`、`logs/.gitignore`、`scripts/run_00_setup.sh`、`scripts/common.sh`、`logs/replay-state.json`（600，无口令）。
- 复验：`.migration-artifacts/u12-verify-full.log`；`.migration-artifacts/u12-rerun/strict-probes-u12.json`、`sp01-embedding-only.log`、`sp04_probe.py`、`sp04-probe.json`、`browser-summary-u12.json`、`teacher-grades-u12.png`；`.migration-artifacts/acceptance-20261025/u12-guard-cleanup-{before,after}.txt`。
- 状态：`docs/java-migration/STATUS.md`（J3+ 仍 planned；integration_pending 留 P3）；黄金 `migration/fixtures/p0-v2/python-golden.json`（SHA 8d065866…，与冻结记录一致）。
