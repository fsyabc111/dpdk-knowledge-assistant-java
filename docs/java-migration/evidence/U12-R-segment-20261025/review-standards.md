# U12 R段独立审查：规范轴（review-standards）

日期：2026-10-25。审查对象：`codex/p3-p5-longrun@4debf9f`（R段报告 README.md 声明的 8 项关闭）。
审查角色：只读 worker（U12 R段规范轴）。对照：根 AGENTS.md、验收报告 `evidence/P0-P2-acceptance-20261025/README.md`（ST-01～03、SP-01～05）、任务书 LONGRUN_P3_P5_DRAFT.md §5。

**结论：通过。阻断 0 项，建议 5 项。** 8 项关闭声明经逐项核对成立，无红线违规。

## 逐项结论

### 1. ST-01 数据库纪律 — 通过

- 预检零写入：`TestPg.java:78,90`（createDatabase/dropDatabase 首行 `requireInstanceIdentity()`）；预检体（:102-131）仅 `SELECT current_user` + `SHOW data_directory`，配置缺失/身份不符/连接失败均抛 `IllegalStateException`，先于任何写操作。current_user 逐字相等 + `DatabaseIdentityGuard.isUnder`（`DatabaseIdentityGuard.java:83-88`，`Path.normalize().startsWith()` 组件级前缀，`/data/p1` 不会误匹配 `/data/p10`）。
- 负例五维覆盖：`TestPgIdentityGuardTest` 8 项 = 错 data-dir(:56)/错角色(:67，不存在角色→连接失败)/错端口(:78)/错 host(:89)/错标记注入(:116)/drop 同预检(:99)/生命周期+comment(:126)/配置缺失(:137)，各以 `p1DbCount()` 前后相等证明零写入。建库的 4 个正例路径均 finally 清理，`@AfterEach`(:26-32) 兜底清 override。
- override 注入点：`TestPg.java:33-36` 为 src/test 域包私有静态字段，主代码不可达；仅本负例类赋值且 afterEach/finally 恢复。滥用风险可忽略（观察项，非缺陷）。
- guard 库清理：`u12-guard-cleanup-before.txt` 150 行全部 `p1_t_*` 前缀（0 行例外）；after 仅余 `p1_java_accept_20261025`+postgres+template0/1，验收库与系统库保留。与报告口径一致。

### 2. ST-02 严格差分 — 通过

- 状态硬前置：`run_08_golden.py:283-310` `compare_case` 首比状态；全部等价流（change-password :581、delete-session :647、password-reset :666、等价作业 :635、judge 包装 :546）都经 `compare_case`，无绕行分支。`gstatus is None`→SKIP 仅限 golden 本就无状态断言的条目，不构成豁免。
- 保序保重复+双射：`norm_eq`(:108-138) 逐元素有序比较；dict 数组额外 `distinct_entity_count` 双射(:127-131)。反例搜寻：`[10,20]→[10,10]`（计数 2≠1 捕获）、同值重复身份（selftest d2：逐元素归一化全过、双射计数捕获）均可捕获；归一化类内不同身份互换（同形不同人）不捕获——与冻结 J2-01「身份→类」归一化规则一致，非缺陷。
- --selftest：17 项存证 `logs/selftest-standalone.txt`（total=17 ok=17，rc=0）；验收要求三种变异（a 错状态同体、b 数组反序、c 标量值差）+ 重复身份（d/d2）全部 DIFF，另含正例/声明判定/生产表命中（f3-f6）。声称"四种变异"实际覆盖（d 与 d2 两型重复伪装），成立。
- 声明表逐条核对：SKIP 平移自原脚本范围（chat/rag/tasks/internal/explain、登录 10 变体、STATE_FACE、ASSIGNMENT_FLOW，ref 指向在库场景日志）；平台路由 ALLOW(STATUS,BODY) 与 `OPTIONS cors 400→403` 依据 `DECISIONS.md:54-60`（P2-D1 确有登记）；HEAD /api/health 405→500 指向 J4-03/J5-01，与验收报告"框架兜底 500 不作第 9 阻断"口径一致。`logs/golden-diff.json` 实测 summary PASS=72/ALLOWED-DIFF=25/SKIP=89/DIFF=0，与报告一致。
- 残余风险（建议 3）：状态相关成功面 10 个 cid 为 ALLOW(BODY)，这些 case 的 body 级回归（含列表内容/权限面变化）会判 ALLOWED-DIFF 而非 DIFF，残余保障依赖场景日志字段级留证。属验收标准"逐 case 声明"允许范围且状态硬前置保留，不构成违规。

### 3. ST-03 可复现性 — 通过

- grep 审计（命令：`grep -rn "tmp/p2reg|/tmp/"`、`grep -rn "/Users/|/home/|fsy"`、口令字面量三组）对 `evidence/P2-regression/scripts/` 全部零命中。
- `run_00_setup.sh` 零写入顺序正确：核验 1/3 身份（只读 SELECT，:38-57）→ 核验 2/3 目标库不存在（:59-64）→ 通过后才 CREATE/COMMENT（:66-70，标识符已加引号）→ 建库后核验 3/3。口令仅从环境读取、缺失即 `:?` 退出（common.sh:38）。
- 身份文件 `replay-state.json` 在 `logs/.gitignore` 中且 `git ls-files` 确认未入库；BcryptGen.java 入仓 `tools/`（:19-20 与 :458 一致，M2_REPO/JAVA_HOME/PATH 定位无绝对路径）；common.sh:31-36 修复了 psqlq 不读身份文件的问题（见建议 4 的残余兜底）。
- `run_08_golden.py` load_config(:346-411) 缺项 fail-fast 列全清单、口令无默认；脚本内 PGDATABASE 一律显式传参（psql :467-473）。

### 4. SP-02/03 Python 语义 — 通过

- SP-02：冻结 `assignments.py:275-278`（request.json → JSONDecodeError/UnicodeDecodeError→422 同 detail；`AssignmentSubmitRequest(**body)` 非映射 TypeError→500）。Java `SubmitRequestParser.java:48-98`：FAIL_ON_TRAILING_TOKENS reader → 空/空白/尾随垃圾/多根 422；`null/123/"text"/[1,2]/true` 非对象→500；`{"a":1} + "  "` 尾随空白容忍→200（Python json.loads 同语义，契约测试 :768-776 显式覆盖）。契约测试 `submitJsonBoundariesMatchPythonLoads`（AssignmentSubmitContractTest.java:729-791）含全部拒绝情形零 MCP/零扣次/零落库断言与合法控制组。
- SP-03：冻结 `assignments.py:353` `list(payload.get('results') or [])`。Java `AssignmentSubmissionService.resultsOf`(:195-216) + `truthy`(:231-246)：false/0/0.0/null/缺失/空串/空数组/空对象→[]（Python or 链全 falsy 集，含 `{}`，与报告声明一致）；非空串拆字符（Python list(str)）、非空对象取键（Python list(dict)）、true/数字→TypeError 500 且评测/扣次已发生。契约测试 `resultsFalsyPayloadsPreserveGradedOutcome`（:798-859）6 假值各 1 次调用/扣次/落库 + 数组透传 + true/5→500 零成绩行，与 Python 语义逐分支对齐。

### 5. SP-04 配置契约 — 通过

- `static-inventory.json:11071/11080/11089`（direct_environment_access）与 :11598-11608 确认 GRADING_TIMEOUT_SEC/GRADING_MAX_CODE_BYTES/GRADING_DAILY_QUOTA 为冻结环境键；Python 校验规则（config.py:250-254 经 inventory 表达式）与 `SubmissionSettings` 紧凑构造器（≥1/≥1/≥0）一致。
- `SubmissionWiring.java:22-24` 嵌套占位符 `${dpdk.grading.*:${GRADING_*:默认}}` 实现"显式属性 > 冻结环境键 > 源默认（120/262144/20，config.py:158-160）"唯一优先级；非数字转换失败与负值校验均启动期拒绝。装配测试 4 项（SubmissionWiringEnvBridgeTest @Test 计数=4）。
- 真实进程探针存证 `.migration-artifacts/u12-rerun/sp04-probe.json` 在场（7 case 全 pass，含 quota_2 第 3 次提交 429）。

### 6. SP-05 基线对齐 — 通过（含建议 1）

- `config.py:116` 默认 `http://127.0.0.1:5174,http://localhost:5174`；`application.yml:32` 默认 `http://localhost:5174,http://127.0.0.1:5174`——**允许来源集合相同、顺序颠倒，非逐字符一致**。CORS 允许匹配为集合语义，功能等价（5174 放行/5173 拒绝已由预检与浏览器验证），但与"对齐冻结基线"的严格口径有差（建议 1）。
- README（backend-java/README.md:44-47）表述与实现一致：默认 5174、`npm run dev` 原样联调、`DPDK_JAVA_CORS_ORIGINS` 覆盖键；frontend/package.json 确认 dev 端口 5174 无需改动。
- 浏览器/预检存证 `browser-summary-u12.json` 在场。

### 7. 证据与提交纪律 — 通过

- 提交链核对：`git show --stat` f38aff9 仅改声明范围内的 12 个 Java/yml/README 文件（无黄金、无 Python 源码）；8c89318（合并，17 文件：脚本/README/selftest 存证/BcryptGen/交接）；d9364ed（20 文件：脚本+脱敏日志）；4debf9f（3 文件：R段 README/STATUS/MAIN）。无凭据、无运行输入、无原始未脱敏日志入库。
- 日志脱敏抽查：`grep -rlE '[A-Za-z0-9_-]{43}'` 对 8 个场景日志零命中；replay-state.json 未跟踪。
- STATUS.md 四层分列未混淆：P1/P2 行保留 U11"本轮复核不通过"历史记录；line 9 与"当前阻塞"节登记 R段"代码与验证完成/工程复验通过（工程门禁口径）/独立审查进行中/**用户验收未登记（独立放行点）**"，四层清晰。
- 边界声明：本审查为只读，未重跑 mvnw verify（316/316）、strict probe（32/32）、浏览器探针；上述数字依据 R段 README、提交记录与在场证据文件（golden-diff.json、selftest-standalone.txt、sp04-probe.json、browser-summary-u12.json、guard-cleanup before/after）交叉核验一致。JAR SHA256 未重算验证。

### 8. 红线 — 通过

- 黄金文件：`shasum -a 256 migration/fixtures/p0-v2/python-golden.json` = `8d0658661fedb31c6a0690ea97cb3167534504049c65861b3c67e6691337de14`，与冻结记录一致，未改动。
- `git diff --stat main...HEAD -- backend/ frontend/` 零命中（仅 fixtures 新增），Python 基线源码未触碰。
- 重放全程 fake MCP 替身（8100/18100），无真实模型/embedding/评测调用。
- 恢复资产保留：after 清单含验收库与系统库；guard 清理严格限于 `p1_t_` 前缀。

## 建议项（不阻断）

1. **SP-05 CORS 默认非逐字符一致**：`application.yml:32` origin 顺序与 `config.py:116` 相反（集合相同）。建议对调为 Python 同序，或在 R段/DECISIONS 注明"集合等价、顺序不计"。
2. **FRONTEND_ORIGINS 无 Java 桥接**：冻结环境键（static-inventory:10945/11553）仅默认值对齐，覆盖走 `DPDK_JAVA_CORS_ORIGINS`。若部署侧以 FRONTEND_ORIGINS 注入将不被消费；GRADING_* 已桥接而此键未桥接，宜登记映射决策口径。
3. **ST-02 声明表 ALLOW(BODY) 的残余风险**：10 个状态相关成功面 case 的 body 级回归（含权限/列表内容）将判 ALLOWED-DIFF。现有 reason 已写明依赖场景日志字段级留证，属验收口径允许；建议在 reason 中显式标注"BODY 全量豁免"字样，避免后续读者误读为值级严格。
4. **common.sh:34-35 PGDATABASE 兜底** `p1_java_candidate`：state 文件缺失时回退旧默认库名，若受控实例恰存同名旧库则 psqlq 静默命中。建议改为缺失即 fail-fast（与 run_08_golden.py 的 fail-fast 口径拉齐）。
5. **run_08_golden.py REPLAYED/EXPECT_STATUS 为死分支**：生产声明表无 EXPECT_STATUS 条目，docstring(:33-34) 与实际口径略漂移；纯文档性瑕疵，自检亦未覆盖该分支。

## 阻断项清单

无。
