# P2 集成回归证据目录

owner：P2 集成回归执行 Agent；worktree `p2-reg`（分支 `worker/p2-regression`，基线 9ba2378）。
对象：真实运行的 Java 应用（spring-boot:run，8090）+ 独立测试 PG 55481/p1_java_candidate +
fake MCP harness（8100）。不修改 backend-java 主代码；本目录与 handoffs/P2-REGRESSION.md 为唯一交付。

> ST-02/ST-03 改造说明（U12 批次，交接 handoffs/ST0203-REPLAY.md）：历史 golden-diff.md 的
> "DIFF=0" 基于旧判定器（成功对象分支漏比状态/列表转 set 排序/STATE-DIFF 不计失败），已被
> 严格判定器取代，不再被引用为准；本目录历史日志保留为当时行为的证据。

## 内容

| 路径 | 说明 |
|---|---|
| behavior-evidence.md | 场景 1-7 行为证据摘要（认证/管理员/作业/正式提交与配额/CSV/会话用量/React CORS） |
| golden-diff.md | IP-3 golden 重放差分报告（历史轮，旧判定器口径；严格化后以新口径重放为准） |
| logs/00-startup.log | 启动与迁移路径证据（EMPTY/HISTORY、health UP、非 account 模式负例） |
| logs/01-auth.log … 07-react-cors.log | 各场景原始 curl 请求/状态码/响应体（token/bcrypt/口令统一打码） |
| logs/08-golden-replay.log | golden 逐 case 重放明细（重放时生成） |
| logs/golden-diff.json | golden 重放机器可读汇总（verdict 分类；重放时生成） |
| logs/selftest-standalone.txt | ST-02 判定器 --selftest 存证（合成数据，17 项全过，退出码 0） |
| scripts/common.sh | 公共环境与打码/留证函数（req/reqsave/psqlq/state_get/state_set/bcrypt_hash） |
| scripts/fake_mcp_harness.py | fake MCP Streamable HTTP 替身（grade_plugin/list_protocols；只记计数与源码 sha256 指纹，不落源码） |
| scripts/run_00_setup.sh | 受控空库种子入口（零写入身份核验 → 建本轮库 → 写身份文件） |
| scripts/seed_accounts.sh | 合成账号种子（U1..U5；口令=学号，bcrypt 运行时生成） |
| scripts/collect_state.py | 身份收集器（经 API 确保 U6/U7 → 登录各角色 → token 写入身份文件） |
| scripts/run_01_auth.sh … run_06_usage.sh | 场景脚本（可复现；token/作业 id 交换改走身份文件，临时产物在 logs/tmp） |
| scripts/run_08_golden.py | golden 重放脚本（IP-3；ST-02 严格判定器 + 显式允许差异声明表 + --selftest） |
| tools/BcryptGen.java | bcrypt 生成工具（java 单文件源模式运行；依赖经 M2_REPO/JAVA_HOME 定位） |

## 可复现入口（ST-03 改造后）

所有路径由脚本位置推导，不依赖任何 worktree 或 /tmp 既存状态；场景脚本临时目录为
`logs/tmp`（可用 `DPDK_REPLAY_TMP` 覆盖），跨脚本动态身份统一走身份文件
`logs/replay-state.json`（.gitignore，权限 600；只含 token/账号/作业 id，绝不含口令）。

完整流水线（每步缺配置即 fail-fast 并列出缺失项；不设默认口令）：

1. `scripts/run_00_setup.sh` —— 零写入核验目标 PG 实例（`current_user`、`data_directory`
   前缀与 `DPDK_TEST_PG_DATA_DIR` 比对、库名占用），不符即退出且零写入；通过后创建
   全新库 `p2replay_<round>`（含本轮标记 COMMENT）并写身份文件（round_id/base_url/pg.*）。
2. 启动应用指向该库（完成迁移）；启动 `scripts/fake_mcp_harness.py`（默认落 logs/tmp）。
3. `scripts/seed_accounts.sh` —— 复核库身份后种入 U90000000001..05（口令=学号，
   bcrypt 由 tools/BcryptGen.java 运行时生成，不提交哈希字面量）。
4. `scripts/collect_state.py` —— 以 admin 经批量创建接口幂等确保 U90000000006/07，
   登录全部角色账号，把 8 个重放角色 token 写入身份文件 `tokens.*`。
5. `scripts/run_01_auth.sh` … `run_06_usage.sh` —— 场景 1-6（tokens.admin/student_a/
   student_b、assignment_ids.asg1 等动态身份增量写入身份文件）。
6. `scripts/run_08_golden.py` —— golden 重放（严格判定器；退出码非 0 = 存在计失败的 DIFF）。

判定口径与允许差异声明表（`DECLARATIONS`）见 `scripts/run_08_golden.py` 头注释：
HTTP 状态硬前置、数组保序保重复 + 实体双射、归一化仅用于值级比较；一切未声明差异 =
DIFF。SKIP/平台路由 404 登记/CORS 403 语义均为表内显式声明（依据 DECISIONS.md P2-D1 与
各场景留证日志），语义较旧脚本不放宽。

### 环境变量

| 变量 | 说明 |
|---|---|
| `DPDK_TEST_PG_HOST/PORT/USER` | 测试 PG 连接（common.sh 默认 127.0.0.1:55481 / p1_java，可覆盖） |
| `DPDK_TEST_PG_PASSWORD` | 测试 PG 口令（必填，无默认；绝不写入任何文件/日志） |
| `DPDK_TEST_PG_DATA_DIR` | 期望 data_directory 前缀（run_00_setup.sh 身份核验必填） |
| `DPDK_REPLAY_MAINTDB` | 维护库（默认 postgres） |
| `DPDK_REPLAY_ROUND` | 本轮标记（默认时间戳；进库名保证全新） |
| `DPDK_REPLAY_BASE_URL` | 应用 BASE URL（默认 http://127.0.0.1:8090，写入身份文件） |
| `DPDK_REPLAY_STATE` | 身份文件路径（默认 logs/replay-state.json） |
| `DPDK_REPLAY_TMP` | 临时目录（默认 logs/tmp） |
| `DPDK_REPLAY_PG_HOST/PORT/USER/DATABASE/PASSWORD` | run_08_golden.py 直接配置（优先于身份文件；PASSWORD 亦接受 DPDK_TEST_PG_PASSWORD） |
| `DPDK_REPLAY_TOKEN_<角色>` | 各角色 token（优先于身份文件；角色 leader/teacher_a/teacher_b/student_a/student_b/free/viewer/s7） |
| `DPDK_REPLAY_ASG1` | 等价流作业 id（优先于身份文件 assignment_ids.asg1） |
| `DPDK_REPLAY_ROLE_PASSWORD_<账号>` | collect_state.py 登录口令覆盖（默认合成约定口令=学号） |
| `M2_REPO` / `JAVA_HOME` | BcryptGen 依赖定位（M2_REPO 默认 ~/.m2/repository；java 取 JAVA_HOME 或 PATH） |

### 判定器自检（无应用/无库/无黄金）

`python3 scripts/run_08_golden.py --selftest`：合成 golden/live 对覆盖验收要求的三种
变异（错状态同体/数组反序/标量值差）+ 重复身份伪装（实体双射捕获）+ 同构响应正例 +
声明判定正例，另含 bool/int 混淆、键集/长度差异等附加项；退出码非 0 即失败。本轮存证：
`logs/selftest-standalone.txt`（17 项全过，退出码 0）。

## 敏感扫描结论

- 43 位 token、口令、reset_token 在日志中全部 `<TOKEN_MASKED>/<PW_MASKED>` 化（common.sh mask；run_08_golden.py 同口径 mask）。
- bcrypt 哈希字面量不出现在本目录（运行时以 tools/BcryptGen.java 生成）。
- 身份文件 logs/replay-state.json 含动态 token，已加入 logs/.gitignore，不入库。
- scripts/ 与 tools/ 审计：无口令字面量、无 /tmp/p2reg 依赖、无绝对用户路径（/Users/fsy、/opt/homebrew 等）残留（2026-10-25 U12-ST0203 复核）。
- 数据库内源码特征串（synthetic/printf/stdio）扫描 0 命中；成绩行仅 code_hash。
- fake harness 账本只含 tool/protocol/长度/sha256 前 12 位。

## 未验证/范围外

- 真实模型、真实 teaching_harness 容器、真实 embedding 未调用（按门禁使用等价替身）。
- chat 主路由（POST /api/chat）与 /api/rag/** 未在当前构建装配（J3+/J5 范围），golden 相应 case 已登记 SKIP。
- 浏览器端到端点击流程不在本卡（U11 为最小接线：页面 200+CORS 预检+带 Origin 登录）。
- 终态清理：应用/vite/fake harness 已停止，p1_java_candidate 已 DROP（2026-10-25），无残留进程/库。
- ST-03 改造后的完整流水线（run_00 → collect_state → run_01..08）尚未用真实应用+库跑通，
  属集成者执行项；本轮验证仅覆盖 --selftest、py_compile、bash -n、fail-fast 路径与
  依赖定位（bcrypt/java/jar），见 handoffs/ST0203-REPLAY.md。
