# U12 R段：验收8项问题关闭与 P1/P2 工程复验报告

日期：2026-10-25。对象：`codex/p3-p5-longrun@d9364ed`（含 f38aff9 Java修复 + 8c89318 ST-02/03合并 + d9364ed 重放集成收口）。
对照：验收报告 `evidence/P0-P2-acceptance-20261025/README.md`（8项问题定义与修复前复现）。
授权：U12 长时批次（LONGRUN_P3_P5_DRAFT.md 任务书 + 用户启动指令）。

**结论：8项全部关闭。P1/P2 工程复验通过（工程门禁口径）。** 用户验收未登记（独立放行点）。

## 逐项关闭记录

### ST-01 [P1] PG 测试辅助类先写入后校验身份 → 已关闭

- 修复：`TestPg.createDatabase/dropDatabase` 在任何写操作前执行实例身份预检（`requireInstanceIdentity`：维护连接上核对 current_user 逐字相等 + SHOW data_directory 位于期望目录之内，复用 DatabaseIdentityGuard.isUnder 路径语义）；配置缺失/身份不符/连接失败一律抛出且零写入。负例注入点仅供测试构造错配环境。
- 新增负例 `TestPgIdentityGuardTest`（8项）：错 data-dir/错角色/错端口/错 host 零写入（pg_database 计数证明）、dropDatabase 受同一预检保护（错配时目标库不得被删）、hostile tag 只产生安全标识符、生命周期建删+comment、配置缺失拒绝。
- guard 库泄漏修复：`DatabaseIdentityGuardNegativeTest` 5个负例全部 finally 清理。历史遗留150个 guard 库（5类×30次套件运行泄漏）按 before/after 清单清除（`.migration-artifacts/acceptance-20261025/u12-guard-cleanup-{before,after}.txt`；清理范围仅 `p1_t_*` 前缀的 TestPg 命名空间，验收库 p1_java_accept_20261025 与恢复资产保留）。
- 验证：修复前探针（WRONG_EXPECTED_DATA_DIR_BUT_CREATED）见验收报告；修复后 8/8 负例通过，全量套件 316/316。

### ST-02 [P2] 重放判定器可假通过 → 已关闭

- 修复：`run_08_golden.py` 判定器重写为严格口径（worker/u12-st0203@1d435e7 + 集成收口 d9364ed）：HTTP状态为所有分支硬前置；数组保序保重复+实体双射（重复身份伪装可捕获）；废除隐式 STATE-DIFF；允许差异逐 case 显式声明（kind/reason/ref），未声明差异即 DIFF 计失败。
- `--selftest`（17项，存证 `logs/selftest-standalone.txt`，rc=0）：验收要求的三种变异全部 FAIL（golden 200/live 500 同体→DIFF；成绩行反序→DIFF；score 80→99→DIFF）+ 重复身份 [10,20]→[10,10] 经实体双射捕获 + 同构响应正例 PASS + 声明判定正例。
- 黄金只读：`migration/fixtures/p0-v2/python-golden.json` 未改动；重放只读黄金。
- 端到端复验：五轮受控空库全链重放（run_00→app→seed→collect→run_01..06→run_08），最终轮 **DIFF=0：PASS=72（值级严格：状态+保序保重复+实体双射+归一化值相等）、ALLOWED-DIFF=25（逐case声明：依据 DECISIONS P2-D1 / 场景日志字段级留证 / 框架500兜底缺口指向J4-03/J5-01）、SKIP=89（登记理由）**。机器可读：`logs/golden-diff.json`；逐case：`logs/08-golden-replay.log`（本轮重放）。
- 过程发现并修复的重放工具缺陷（均属交付物自身问题，非 Java 行为）：CREATE DATABASE 标识符未加引号；common.sh PGDATABASE 不读身份文件导致 psqlq 静默打错库；foreign-tree 等价主体 U7 被 batch create 挂进同班（越权案例失真，改 created_by=NULL 种子后该案例恢复真实重放并 PASS）；冻结登录限流（10次/分/IP，Python同款契约）下背靠背连跑必然429（场景脚本与 collect_state 加节拍参数）。

### ST-03 [P2] 回归入口不能独立复现 → 已关闭

- 修复（worker/u12-st0203@ea0cad6 + d9364ed）：路径全部由 `__file__` 推导；环境配置显式化（DPDK_REPLAY_*/DPDK_TEST_PG_*，缺失 fail-fast 列出缺项，不设默认口令）；身份文件 `logs/replay-state.json`（.gitignore，600）动态保存本轮 token/账号/作业id；`run_00_setup.sh` 受控空库种子入口（零写入身份核验→建库→写身份文件）；BcryptGen.java 入仓 `tools/`（M2_REPO/JAVA_HOME 定位依赖）；无 /tmp、无 worktree、无绝对用户路径依赖。
- 验证：干净临时目录五轮重跑（每轮全新库名+全新身份文件）全链通过；`python3 -m py_compile`/`bash -n`/口令与路径 grep 审计通过（worker 报告 + 集成复核）。

### SP-01 [P2] 单独配置 embedding 无法启动 → 已关闭

- 修复：`ModelWiring` 的 ModelClients 改为 chat.base-url 或 embedding.base-url 任一存在即装配（AnyNestedCondition）；chat-only/embedding-only/两者/均未配置四组合各自装配所属客户端。
- 验证：ApplicationContextRunner 四组合测试 4/4（`ModelWiringCombinationsTest`）；真实进程探针：embedding-only 配置（死端点 127.0.0.1:9，无真实调用）启动成功 health 200（修复前退出1缺 Bean）。日志 `.migration-artifacts/u12-rerun/sp01-embedding-only.log`。

### SP-02 [P2] 提交 JSON 解析不兼容 → 已关闭

- 修复：`SubmitRequestParser` 改用 `FAIL_ON_TRAILING_TOKENS` 严格 reader，对齐 Python `request.json()`（json.loads）语义：空/纯空白/尾随垃圾/多根对象 → 422 "request body must be valid JSON"；合法 JSON 非对象（null/标量/数组）保留 500 TypeError 面。
- 验证：契约测试 `submitJsonBoundariesMatchPythonLoads`（含零 MCP/零扣次/零落库断言 + 合法对象控制组）；strict probe 复跑 submit_json_empty/whitespace/trailing_garbage 三项与冻结 Python AST 抽取解析器同预期 422。

### SP-03 [P2] results:false/0 丢成绩 → 已关闭

- 修复：`AssignmentSubmissionService.resultsOf` 补 truthy() falsy 链——false/0/0.0/null/空串/空对象 → `or []` 空数组语义（200 + results:[]）；truthy 非列表（true/数字）保留 500 TypeError（照源评测已执行）。
- 验证：契约测试 `resultsFalsyPayloadsPreserveGradedOutcome`（6假值各1次MCP/扣次/落库 + 空数组/正常数组透传 + true/5→500且扣次已发生零成绩行）；strict probe 复跑 harness_results_false/0 → 200 + results:[]。

### SP-04 [P2] GRADING_* 环境变量未消费 → 已关闭

- 修复：`SubmissionWiring` 桥接冻结环境键 GRADING_TIMEOUT_SEC/GRADING_MAX_CODE_BYTES/GRADING_DAILY_QUOTA（static-inventory direct_environment_access）。唯一兼容优先级：`dpdk.grading.*` 显式配置 > GRADING_* 环境变量 > 源默认；非法值（负数/非数字）启动失败（record 紧凑构造器 + 类型转换）。
- 验证（规格轴审查 B-1 复验后补齐，全部为真实进程端到端）：
  - quota：真实进程 `GRADING_DAILY_QUOTA=2`（18092 实例）→ 第3次有效提交 **429** detail "今日评测配额已用完（2 次/天）…"，429 后失败计数保留（counter=3）。存证 `sp04-probe.json` 7/7。
  - max bytes：真实进程 `GRADING_MAX_CODE_BYTES=100`（18093 实例）→ 101字节代码 **413** detail 含"100 字节"；恰100字节边界 → **200** score 80；counter=1/成绩行=1。存证 `sp04b-probe-bytes.json` 7/7。
  - timeout：真实进程 `GRADING_TIMEOUT_SEC=1`（18094 实例）+ 慢 initialize（fake MCP --delay-init-sec 3）→ deadline 在 tools/call 提交前到期 → **504** detail "评测超过 1 秒未完成"，零成绩行（未提交任何评测）。存证 `sp04b-probe-timeout-init.json` 5/5。
  - 不中断语义：`GRADING_TIMEOUT_SEC=1`（18095 实例）+ 慢 tools/call（--delay-sec 3）→ 已发出请求不被 deadline 中断，迟到响应仍被接受 → **200** score 80（冻结语义：StreamableHttpHarnessClient 头注释"deadline 不中断已发出的请求"）。存证 `sp04b-probe-timeout-inflight.json` 4/4。
  - 非法值启动失败（真实进程）：`GRADING_TIMEOUT_SEC=abc` 与 `GRADING_MAX_CODE_BYTES=abc` → submissionSettings Bean 转换失败（"Failed to convert … For input string: \"abc\""）→ 应用启动失败退出。日志 `app-sp04b-invalid-{timeout,bytes}.log`；装配断言覆盖 quota -1/abc、timeout 0/abc、max bytes 0/abc（`SubmissionWiringEnvBridgeTest` 4/4）。
  - fake MCP 替身新增 `--delay-sec`/`--delay-init-sec` 参数（延迟注入仅用于本探针）。

### SP-05 [P2] README 默认联调被 CORS 拒绝 → 已关闭

- 修复：Java CORS 默认对齐冻结 Python 基线 `FRONTEND_ORIGINS`（config.py:116 = 5174，与 frontend/vite.config.js dev 端口一致）——`application.yml` 默认 5174、README 更正；frontend/package.json 无需改动（5174 即基线默认）。
- 验证：
  - HTTP 预检（strict probe 复跑）：Origin 5174 → 200（默认放行）；Origin 5173 → 403（非允许来源，Spring 拒绝语义，P2-D1 登记差异）。
  - 真实浏览器（README 原样命令：Java 8090 + `cd frontend && VITE_API_BASE=http://127.0.0.1:8090 npm run dev` 默认5174，无 --port 覆盖）：页面加载/root挂载、教师登录→作业列表（作业管理页）→成绩总览（截图 teacher-grades-u12.png）、学生登录→学习中心作业页、**console 零错误**。存证 `.migration-artifacts/u12-rerun/browser-summary-u12.json`。

## 全量复验

| 验证 | 结果 | 边界 |
|---|---|---|
| Java `mvnw verify`（显式专属PG 55481） | **316/316**，0失败/0错误/0跳过，BUILD SUCCESS | 298既有 + 18新增（ST-01负例8、SP-01四组合4、SP-04装配4、SP-02/03契约2） |
| 判定器 --selftest | 17/17，rc=0 | 无应用/无库/无黄金 |
| 受控全链重放 run_00→run_08 | DIFF=0（PASS 72 / ALLOWED-DIFF 25 / SKIP 89） | fake MCP 8100 替身；J3+ 路由未实施为显式SKIP |
| strict probe（32项HTTP） | 32/32，0差异 | 修复前 25/32、7项差异（验收报告） |
| SP-04 专用实例探针 | 7/7 | 真实进程环境 GRADING_DAILY_QUOTA=2 |
| SP-05 浏览器（README原样） | 全通过，console 零错误 | 独立Chrome CDP；不含 chat/讲解（J3+） |
| SP-01 embedding-only 真实启动 | 启动成功 health 200 | 死端点，无真实调用 |

构建：JDK 21.0.12.1，Boot 4.0.9。JAR SHA256 `8af498ebf5daf44617f915474a5292bad78fd57ee948b51947f8d1403f420bbb`（f38aff9 构建产物，target/）。

## 资源与清理

- 本轮停止：Java 探针实例（18091/18092/8090×多次）、Vite 5174、fake MCP（18100/8100）、独立Chrome。PG 55481 保留运行（专属实例，恢复命令见 MAIN）。验收库 p1_java_accept_20261025 保留；重放轮临时库 p2replay-20261025_* 已DROP（161030/161252/162011/162928；首轮建库失败未成库）。
- 原始探针/日志在 `.migration-artifacts/u12-rerun/`（gitignored）：strict_probe_u12.py、sp04_probe.py、browser_probe_u12.py、browser-summary-u12.json、sp04-probe.json、teacher-grades-u12.png、各app日志。口令仅存 `.migration-artifacts/p1/local-credentials.txt`（600，gitignored；U12重设记录）。
- 提交链：f38aff9（Java修复）→ 4637ac9..9b4de06（worker ST-02/03）→ 8c89318（合并）→ d9364ed（重放集成收口）。本地/远程 SHA 一致（ls-remote 核验）。

## 遗留与边界（不阻塞 R 段门禁）

- 框架未匹配路由/方法兜底 500（golden 404/405 面）：已登记平台缺口，J4-03/J5-01 复核修复；重放中以 ALLOW 声明显式指向。
- chat/RAG/tasks/internal 与平台 docs/metrics/health 路由未实施：J3+/J5 范围（重放 SKIP 登记）。
- 登录限流锁定状态为内存态，重启即清（Python 同语义）；重放工具以节拍参数适配冻结限流。
- 本报告为工程门禁口径；用户验收、真实模型/embedding/真实harness、生产切换等按任务书独立放行。

## 独立审查与处置（2026-10-25，双轴只读）

- **规范轴（review-standards.md）：通过，0阻断、5建议**；**规格轴（review-spec.md）：1阻断（B-1 SP-04实测证据缺口）、5建议**，其余7项通过。
- **B-1 处置（阻断关闭）**：按上文 SP-04 节补齐真实进程端到端实测——max bytes 非默认 413/边界200（7/7）、timeout 非默认 504+零成绩行（5/5）、不中断语义 200（4/4）、非法值启动失败×2（真实进程日志）；装配断言补 max bytes 非法值。修复后全量 verify 复跑（316/316）。
- 建议处置：
  - S-1（ST-01 标记维度零写入直接化）：`hostileTagYieldsSafeIdentifierOnly` 增加 p1_guard 不存在 + 计数不变断言。
  - S-2（SP-01 探针自描述）：探针日志含完整启动命令与 health 响应（app-sp04b-*.log / sp01-embedding-only.log）；本报告补实例端口与生效环境变量。
  - S-3（状态面 ALLOW(BODY) 残余风险）：在此显式登记——10个状态相关列表端点的 BODY 值级回归会被声明吞掉（HTTP状态仍硬前置；行形态/键集由场景日志兜底）。收窄声明为"结构一致+行数对账"列入 P5 全矩阵复核范围，本批次不静默视为豁免。
  - S-4（逐轮重放记录）：五轮库名与结果——161030（common.sh DB解析缺陷，半污染废弃）、161252（限流429致token空，废弃）、162011（同161030，废弃）、162928（声明表不全，DIFF=1→补声明）、终轮 DIFF=0 入库；前四轮库已DROP，无证据留存（本行即登记）。
  - S-5（PGDATABASE 兜底）：common.sh psqlq 内 fail-fast（空库名拒绝执行），run_00（不经psqlq）不受影响。
  - 规范轴①（CORS顺序）：yml 默认对齐 config.py:116 逐字符顺序（127.0.0.1 在前）。
  - 规范轴②（FRONTEND_ORIGINS 桥接）：cors-origins 解析链 DPDK_JAVA_CORS_ORIGINS > FRONTEND_ORIGINS（冻结环境键）> 源默认。
  - 规范轴⑤（REPLAYED 分支）：保留——它是合法声明种类（EXPECT_STATUS），当前声明表未使用该种类而非死代码；留作 J4-03/J5-01 修复后平台路由的精确断言语义。
