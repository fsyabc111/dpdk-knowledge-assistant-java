# J0-02 两轴审查修复记录

原审查报告保持原样；本文只记录修复与补验，不自行放行P0/P1。

当前状态：p0-v2动态双库/冻结/34项CLI/有效token停用4断言均已通过；最终结果和准确日志见文末。前两节为纯内存阶段历史，后续未覆盖的完整业务分支继续明确保留。

## 纯内存阶段

未导入app、未连接PostgreSQL、未运行动态oracle。正式长测第2轮期间仅实施和运行纯内存/CLI测试。

| 审查项 | 修复 |
|---|---|
| Spec S1 | `business_state.py`显式白名单SQL；一次完整业务初态、逐case每表实际removed/added业务行及前后count、完整终态。`validate_business_chain`可重建每步状态，不能以总行数或whole DB hash代替。包含users树/角色/enabled、token生命周期元信息、session/release/message字段指纹/metadata与引用rank、usage实际扣次、upstream unknown/费用类型、作业items/成绩/accepted快照、task status/purpose/input元信息/job result及submission_done、knowledge/revisions。10项PG专例+清理、raw检索结果/六引用内容进入trace。 |
| Spec S2 | 正式提交后增加teacher非空GET assignments，断言submission_count/student_count；再取student非空视角并核对80分→16.0折算。动态待长测后运行。模型枚举修复由J0-01负责。 |
| Spec S3 | SSE严格匹配冻结React LF空行帧，不再CRLF/CR改写；保留UTF8逐字节分块正例。 |
| Spec S4 | 合法error-only/start→error可以结束，明确标为error；payload需message/bool retryable，结束后任何事件失败。缺done/error和截断done仍失败。 |
| Standards S-01 | task/session/message/assignment/grade/knowledge等先按字段和实体格式校验再双射；不能通过全部关联替换跨类型身份。固定调用者和用户保留。code/input SHA逐值；运行时实际字节hash与PG持久化断言一致。 |
| Standards S-03 | CLI显式`--fixture p0-v1|p0-v2`；顶层/observations/SSE/delta容器、嵌套深度/节点数、非有限数值和非法日历统一输入错误2。业务值差异1；稳定JSON输出，不吞程序bug。 |

纯内存命令：

```sh
PYTHONDONTWRITEBYTECODE=1 .migration-artifacts/p0/venv/bin/python -m unittest migration.oracle.test_review_fixes -v
PYTHONDONTWRITEBYTECODE=1 .migration-artifacts/p0/venv/bin/python -m migration.oracle.verify --fixture p0-v1 --candidate .migration-artifacts/p0/candidate-trace.json
```

当前结果：7组通过；包含9种“HTTP/行数相同、业务值错写”的反例、跨类型全部关联替换、全部code_hash一致替换、两个Manager字段缺失、error流正例/CRLF和截断反例，以及CLI合法JSON非object、非法日历、2000层嵌套返回2/业务差异1。旧v1实际raw候选依然通过；未覆盖原v1黄金。

## 前期动态补验计划（现已执行，实际命令与结果见末节）

当时计划在主Agent确认正式长测进程退出后顺序执行；此窗口现已获准并执行：

```sh
.migration-artifacts/p0/venv/bin/python -m migration.oracle.run --fixture p0-v2 --database p0_python_oracle > .migration-artifacts/p0/oracle-python-p0-v2.log 2>&1
.migration-artifacts/p0/venv/bin/python -m migration.oracle.run --fixture p0-v2 --database p0_candidate_oracle > .migration-artifacts/p0/oracle-candidate-p0-v2.log 2>&1
.migration-artifacts/p0/venv/bin/python -m migration.oracle.freeze --fixture p0-v2
.migration-artifacts/p0/venv/bin/python -m migration.oracle.verify --fixture p0-v2 --candidate .migration-artifacts/p0/candidate-trace-p0-v2.json
.migration-artifacts/p0/venv/bin/python -m migration.oracle.check_account_disable --database p0_candidate_oracle
```

前期风险与边界：当时白名单SQL字段、双库fixture和有效token停用尚待实际PG核验；这些补验现已完成，详见末节。异步after采用settled checkpoint，事务组固定wallclock不承担消息单调测试，HTTP组仍走真实now_iso。最终独立复审由主Agent执行，本卡不自行宣布关闭。

## 最终动态结果（在正式长测退出后已执行）

前文“纯内存阶段/待动态”保留为阶段历史，以下为当前结果。

- Python与候选独立库均采集退出0：每端186条HTTP观测、66路由、14上游故障脚本、29表schema与25表白名单业务投影；10关键PG case及1清理case全部纳入比较trace。初态→逐case actual delta→终态可重建，零pending/零临时输入/源码标记零命中。
- 非空teacher管理列表实际12字段（继承10+新增submission_count/student_count），值为1/1；student列表10字段，80分→16.0折算。删任一Manager新增字段的实际CLI候选均退出1。
- v2黄金SHA256：`8d0658661fedb31c6a0690ea97cb3167534504049c65861b3c67e6691337de14`，4项manifest SHA校验通过；p0-v1修订2所有文件保持不变。
- 实际CLI 34/34通过：退出0的正常/容差内2例；业务差分退出1共20例；顶层/嵌套schema、非法日历、非有限JSON、70层/2000层嵌套及SSE data容器损坏退出2共12例。所有输出为JSON且无stderr/traceback；重复freeze退出1拒绝覆盖。
- 9组纯内存/真实子进程CLI测试通过。有效token新建后可认证、停用后同token失效、启用后旧token不复活、新token可认证四项独立PG断言通过。
- U09/P0-D15已接受raw检索score绝对误差≤1e-12，仅`$.transaction_cases[*].ranked[*].score`生效。容差内通过、外拒绝；排序、citation.round4、业务score等保持exact。manifest数字门槛权威指向`migration/contracts/thresholds.json`，P0阶段用户验收仍待进行。

| 实际命令（仓库根） | 退出 | 证据 |
|---|---:|---|
| `python -m migration.oracle.run --fixture p0-v2 --database p0_python_oracle`（指定venv） | 0 | `.migration-artifacts/p0/oracle-python-p0-v2-attempt06.log`；auto/p0_python_oracle-p0-v2.json |
| 同命令 `--database p0_candidate_oracle` | 0 | `.migration-artifacts/p0/oracle-candidate-p0-v2-attempt06.log`；auto/p0_candidate_oracle-p0-v2.json |
| 原始双库trace `compare(a,b)` | 0 | `.migration-artifacts/p0/v2-two-db-compare-final.log` |
| `python -m migration.oracle.freeze --fixture p0-v2` | 0 | migration/fixtures/p0-v2/manifest.json |
| `python -m migration.oracle.verify --fixture p0-v2 --candidate .migration-artifacts/p0/candidate-trace-p0-v2.json` | 0 | 186条差分通过；auto/p0-v2-cli-regressions.json |
| `python -m migration.oracle.verify_v2_regressions` | 0 | 34项；`.migration-artifacts/p0/v2-cli-regressions-final.log` |
| `python -m unittest migration.oracle.test_review_fixes -v` | 0 | 9组；`.migration-artifacts/p0/review-fix-final-tests.log` |
| `python -m migration.oracle.check_account_disable --database p0_candidate_oracle` | 0 | auto/account-disable-followup.json；4断言 |
| `python -m migration.oracle.verify --fixture p0-v1 --candidate .migration-artifacts/p0/candidate-trace.json` | 0 | 历史184观测仍可消费 |

### 资格失败与修正（不计入passed）

1. 初始自比较发现历史`cit_12hex`引用主键，源SessionService确实使用此格式；类型白名单补上合法历史分支，不放宽其他实体。
2. 第一次双库比较因讲解文本内随机grade身份导致字段指纹不同；改成仅识别本run实际登记实体的有序类型引用+字面剩余指纹，保留原字节数。
3. 随后比较仍发现源码redact会将随机grade ID的连续数字段省略。此现状登记ORACLE-D05，默认Java保留源算法；经主Agent接受，fixture只注入唯一、可重复、合法v4的grading UUID生成端口（不检验熵）。含/不含连续数字的两个固定redact向量均逐值纳入trace，不掩盖原行为。
4. 同一用量事务的两条planner行原先仅以随机主键打破并列，输出顺序不稳定；白名单投影SQL改用实际usage_source/token/call/cost业务列排序后才以id定序。比较器未重排API或引用数组，也未删业务值。

原失败日志保留：`.migration-artifacts/p0/v2-two-db-compare-attempt01.log`、`attempt02.log`、`attempt03.log`；各动态资格的`oracle-*-p0-v2-attempt01..05.log`仍保留。最终通过仅引用attempt06及final比较日志。没有修改冻结Python或产品实现。

剩余范围：28条完整重试/并发分支、全认证角色组合、合成0013升级演练仍按原业务卡/J5边界未完成；真实模型/embedding/harness质量未授权。以上不影响已执行S1–S4/S-01/S-03修复证据，但本卡不自行宣布独立复审或P0用户阶段验收通过。
