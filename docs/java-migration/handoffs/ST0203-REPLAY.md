# ST0203 交接（U12 批次：ST-02 严格重放判定器 + ST-03 可独立复现入口）

- owner/工作树：U12 重放工具改造 worker；worktree `.migration-artifacts/worktrees/u12-st0203`（分支 `worker/u12-st0203`）。
- 源基线：`codex/p3-p5-longrun@4637ac9`。
- 当前授权/契约/fixture 版本：验收报告 `evidence/P0-P2-acceptance-20261025/README.md` ST-02/ST-03 两节；
  黄金 `migration/fixtures/p0-v2/python-golden.json` 只读未动（未用任何 Java 输出反向改写）。
- 状态：
  - 实现：完成（ST-02 判定器、声明表、--selftest；ST-03 路径/配置/身份文件/种子入口/依赖定位）。
  - 自动验证：完成（--selftest 17/17、py_compile、bash -n、内联 python 片段编译、fail-fast 路径、bcrypt/java/jar 定位、gitignore 覆盖）。
  - 场景验收：未执行（需运行中的应用+修复后 Java+受控库，属集成者执行项）。
  - 用户验收：未开始。

## 完成与文件

| 路径 | 改动目的 | 是否符合 owner 范围 |
|---|---|---|
| evidence/P2-regression/scripts/run_08_golden.py | ST-02：重写为严格判定器（见"决策"），新增 --selftest 与显式配置；ST-03：路径由 `__file__` 推导、`--state` 身份文件、环境变量配置 fail-fast | 是（scripts/**） |
| evidence/P2-regression/scripts/common.sh | ST-03：PG 参数 `DPDK_TEST_PG_*` 化（保留原默认值）、口令仅 `DPDK_TEST_PG_PASSWORD`、去 /tmp/p2reg（改 `logs/tmp`）、JAVA_HOME 不硬编码、新增 state_get/state_set/bcrypt_hash | 是 |
| evidence/P2-regression/scripts/run_00_setup.sh | ST-03：受控空库种子入口（零写入身份核验 → 建本轮库 → 写身份文件） | 是（新增） |
| evidence/P2-regression/scripts/seed_accounts.sh | ST-03：种 U1..U5（口令=学号、bcrypt 运行时生成）；写库前复核库身份 | 是（新增） |
| evidence/P2-regression/scripts/collect_state.py | ST-03：幂等确保 U6/U7（admin 批量创建契约）→ 登录 8 角色 → token 写身份文件 | 是（新增） |
| evidence/P2-regression/scripts/run_01_auth.sh…run_06_usage.sh | ST-03：/tmp/p2reg → `$REPLAY_TMP`；token/作业 id 交换改走身份文件（state_get/state_set）；run_02 的 BcryptGen 调用改 `bcrypt_hash` | 是 |
| evidence/P2-regression/scripts/fake_mcp_harness.py | ST-03：默认 state/payload/protocols 文件落 `evidence/logs/tmp`（DPDK_REPLAY_TMP 可覆盖） | 是 |
| evidence/P2-regression/tools/BcryptGen.java | ST-03：原 /tmp/p2reg/tools 下工具收编交付（内容同源，注释更新） | 是（tools/** 新建） |
| evidence/P2-regression/logs/.gitignore | ST-03：身份文件 replay-state.json 与 tmp/ 不入库 | 是（任务书 B-3 明确要求 state 文件 .gitignore；位于 logs/，特此说明） |
| evidence/P2-regression/logs/selftest-standalone.txt | ST-02：--selftest 输出存证（合成数据，无真实 token） | 是（任务书 A-5 指定路径） |
| evidence/P2-regression/README.md | 仅更新可复现性相关段落（内容表、可复现入口、环境变量表、自检、审计结论、范围外补充） | 是 |
| handoffs/ST0203-REPLAY.md | 本交接 | 是（新建） |

未动：backend-java/、migration/fixtures/、STATUS.md、evidence 其他目录、run_07（不存在）。

## 决策与接口差异

### ST-02 判定器（run_08_golden.py）

1. **HTTP 状态硬前置**：`compare_case()` 先比状态，不等即记 STATUS 差异；任何分支无状态豁免。
2. **数组保序保重复 + 实体双射**：`norm_eq()` 对列表逐元素按序比较、长度必等；对象数组额外比较
   "去重后原始实体数"（未归一化原值序列化去重）——`[10,20]→[10,10]`（2 vs 1）与"同分不同人 vs
   同一行重复"（逐元素归一化相等但实体数 2 vs 1）均被捕获。
3. **废除隐式 STATE-DIFF**：verdict 只有 PASS / DIFF / ALLOWED-DIFF / REPLAYED / SKIP。
   旧"格式类一致取值不同=STATE-DIFF（不计失败）"口径删除；同类型标量值差（score:80→99）= DIFF。
4. **归一化保留但只作用于值级**：identity/TS/DATE/USER_ID/token 五类正则与旧版一致；
   不命中模式的值原样比较；类型类先比（bool 与 int 不相等）。
5. **允许差异声明表 `DECLARATIONS`**（脚本内建；结构 `Decl(cid, suffix, route, kind, allow, expect_status, reason, ref)`）：
   - 匹配优先级：精确 case_id > 后缀包含 > route 子串，首个命中生效；未命中即无豁免。
   - `kind="SKIP"`：原脚本跳过清单逐条平移（chat/SSE/RAG/tasks/explain/internal、login::success、
     状态面 10 个 success、作业流 6 个 success、submit::success、sources::success），理由与
     留证引用（logs/01..05）原样保留——语义不放宽，仅显式化。
   - `kind="EXPECT_STATUS"`：Python 平台路由 12 条（health/ready/docs/openapi/redoc/metrics 及
     HEAD 变体）声明 live 期望 404（Java 未迁移，登记平台差异）；期望不满足 = DIFF（较旧脚本
     "非 404 记 DIFF"一致，且新增"状态/体偶然全等则 PASS"的严格通道）。
   - `kind="ALLOW"`：CORS 预检（OPTIONS /api/chat::cors-400 及其余 cors 后缀、OPTIONS /api/health）
     允许 STATUS+BODY 差异，依据 DECISIONS.md P2-D1（Python 400 / Spring 403 同语义拒绝）；
     成功对象类 case 无任何 BODY/STATUS 允许声明。
   - `ALLOWED-DIFF`/`REPLAYED` 在 SUMMARY 中单列，不计 DIFF 失败，但明细必须携带 reason+ref。
6. **--selftest**：不启动应用、不连库、不读黄金；17 项检查覆盖任务书 a-f 全部六项（另加
   d2 实体双射专项、e2/e3/e4 控制组、f2 无声明对照、f3-f6 生产声明表命中与不满足、x/x2 键集/长度）。
   退出码非 0 即失败；输出存证 `logs/selftest-standalone.txt`（rc=0，17/17）。

### ST-03 独立复现

1. **路径**：`REPO_ROOT = scripts/../../../../../..`（`__file__` 推导）；黄金默认
   `migration/fixtures/p0-v2/python-golden.json`，输出默认 `logs/08-golden-replay.log`、
   `logs/golden-diff.json`。旧硬编码 worktree 路径与 `ASG2`（无引用旧作业 id）删除。
2. **配置解析**（env > 身份文件 > fail-fast 列全缺）：BASE=`DPDK_REPLAY_BASE_URL` 或
   `state.base_url`；PG host/port/user/dbname=`DPDK_REPLAY_PG_*`（兼容 `DPDK_TEST_PG_*`）或
   `state.pg.*`；口令=`DPDK_REPLAY_PG_PASSWORD`/`DPDK_TEST_PG_PASSWORD`（无默认，绝不入 state）；
   token=`DPDK_REPLAY_TOKEN_<角色>` 或 `state.tokens.<角色>`（8 角色）；ASG1=`DPDK_REPLAY_ASG1`
   或 `state.assignment_ids.asg1`。
3. **身份文件** `logs/replay-state.json`（.gitignore，600）：`run_00_setup.sh` 写
   round_id/generated_at/base_url/pg.{host,port,user,database,data_dir,maintdb}/accounts.*；
   场景脚本与 collect_state.py 增量写 tokens.*、assignment_ids.asg1。格式为扁平 JSON +
   点路径写手（state_set/state_get）。
4. **run_00_setup.sh 零写入纪律**：核验（只读 SELECT current_user / data_directory 前缀比对
   `DPDK_TEST_PG_DATA_DIR` / pg_database 占用）→ 全过才 CREATE DATABASE + COMMENT → 再核验
   current_database/comment/data_directory → 写身份文件。任何不符 exit 2 且零写入。
5. **BcryptGen 定位**：java 取 `JAVA_HOME/bin/java` 或 PATH；jar 取 `M2_REPO`（默认
   ~/.m2/repository）下 spring-security-crypto/spring-core/commons-logging 版本最高 jar
   （spring-security-crypto 7.x 运行期需 spring-core，实测必要）；java 单文件源模式运行
   `tools/BcryptGen.java`。实测本机 M2_REPO 解析出 spring-security-crypto-7.1.0 并成功出哈希。
6. **附带缺陷修复**：run_04 submit#1 内联 python `write(str(d.get('submission_id'))))` 多一括号、
   自首轮起即静默 SyntaxError（其产物 sub1_id.txt 全仓库无读者）——删除该死语句（实际行为不变）。
7. **行为差异（集成者需知）**：
   - 旧 `POST /api/chat` SKIP 分支先发一次探测再记 SKIP；现在纯 SKIP 声明不发请求（verdict 不变）。
   - 平台路由 case 旧脚本"live 404 → REPLAYED、非 404 → DIFF"；新口径一致，另若 live 状态与
     golden 全等且体逐字段相等则记 PASS（更严不更松）。
   - 等价流（change-password/删会话/空体摘要）保留旧附加断言（空体/新密登录 200/删除后 404/sha256），
     断言失败一律 DIFF，不再有 STATE-DIFF 中间态。
   - 严格化后历史 "DIFF=0" 不再被引用为准（README 已注明）；首轮严格重放出现 DIFF 属预期，
     逐条按声明表+明细定位。

## 验证证据

| 命令、工作目录及非秘密参数 | 构建/环境 | 退出码与断言 | 证据路径 |
|---|---|---|---|
| `python3 scripts/run_08_golden.py --selftest` | 本机 python3（无应用/无库/无黄金） | rc=0；SELFTEST SUMMARY total=17 ok=17 failed=0；a-d/d2 变异全 DIFF（含"实体双射"字样），e/e2 PASS，f 系按声明判定 | evidence/P2-regression/logs/selftest-standalone.txt |
| `python3 -m py_compile scripts/*.py`（run_08_golden/collect_state/fake_mcp_harness） | 同上 | 全部 rc=0 | 本交接（命令级） |
| `bash -n scripts/*.sh`（9 个） | bash | 全部 rc=0 | 本交接（命令级） |
| 内联 python 片段提取编译（`python3 -c` 与 heredoc 全量） | 自写检查脚本 | run_04 一处历史括号错误（已修），其余 ALL OK | 本交接"决策 6" |
| `env -i PATH=… python3 run_08_golden.py`（空环境） | 隔离 env | rc=2，列出全部 15 项缺失配置，含角色 token/口令/BASE/ASG1 | 本交接（命令级） |
| `bash run_00_setup.sh`（缺 DPDK_TEST_PG_PASSWORD / 缺 DPDK_TEST_PG_DATA_DIR） | 同上 | 各自 fail-fast 报缺，不触碰网络 | 本交接（命令级） |
| `python3 collect_state.py` / `bash seed_accounts.sh`（无身份文件） | 同上 | FAIL-FAST: 身份文件不存在 | 本交接（命令级） |
| `source common.sh` + state_set/state_get 往返 + `bcrypt_hash U90000000003` + python 侧 `bcrypt_hash/resolve_java/find_jar` | 本机（java=M2 spring-security-crypto-7.1.0 + spring-core-7.0.10） | state 文件 600、缺失键 rc=1；哈希 60 位 `$2` 前缀；jar/java 解析成功 | 本交接（命令级） |
| `grep -rn "tmp/p2reg\|/Users/fsy\|/opt/homebrew" scripts/ tools/` 与旧合成 PG 口令字面量复核 | worktree | 0 命中（口令字面量/旧临时目录/绝对用户路径均无） | 本交接（命令级） |
| `git check-ignore logs/replay-state.json logs/tmp/x` | worktree | 均被 logs/.gitignore 命中；selftest-standalone.txt 不被忽略（可入库） | 本交接（命令级） |

未运行：完整重放（run_00→…→run_08 真实链路）、任何数据库写读、任何对运行中应用的 HTTP 调用——
按任务边界留给集成者。

## 未完成、阻塞与回退

- **真实链路未跑通（阻塞场景验收）**：解除条件=集成者按 README"可复现入口"流水线执行
  （run_00 → 启动应用+fake harness → seed_accounts → collect_state → run_01..06 → run_08）。
  风险点已列：等价流在严格值级口径下的首次真实结果未知（历史 shape 口径较松）；U6/U7 依赖
  admin 批量创建契约与历史一致；`STATE_FILE` 由场景脚本增量写入的时序（run_01 登出后的
  student_a 旧 token 会被 run_02/03/04 重登覆盖，run_08 前建议再跑一次 collect_state.py）。
- **回退**：全部改动位于 worker/u12-st0203 单分支，revert 对应 3 个提交即可；无数据库/外部副作用
  （本轮零写库、零发请求）。
- **遗留建议**（不阻塞）：`logs/replay-state.json` 的 .gitignore 系任务书明确要求、但位于
  scripts/tools 之外，请集成者确认接受该文件归属；运行_00 前需确认 `DPDK_TEST_PG_DATA_DIR`
  与实际受控实例一致（负例核验路径本轮未连真实库演练）。

## 恢复点

- 未提交文件与仍运行进程/端口/容器：无（工作树干净后推送；无进程/容器启动）。
- 锁定/释放的共享文件：未触碰黄金与共享契约文件；scripts/** 内全部改动已提交。
- 下一步的准确命令或动作：集成者合入 `worker/u12-st0203` 后按 README"可复现入口"六步执行，
  首轮严格重放结果回填 golden-diff.md（新口径）。
- 主 Agent 需要决定的最小问题：是否放行集成者执行完整重放（需应用+库），及接受
  `logs/.gitignore` 这一超 scripts/ 范围的最小新增。
