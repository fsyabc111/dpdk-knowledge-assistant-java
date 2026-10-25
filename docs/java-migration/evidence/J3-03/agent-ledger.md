# P3 真实 Agent 作业账本集成回归

日期：2026-10-25。worker：model_adapter；工作树 `codex/p3-model-adapter`。完整验证对象：主集成 `b760ea4` + 测试 `3adcd1e`（合并树）。本切片只新增 `test/chat/RealAgentLedgerIntegrationTest.java` 与本证据，未改业务主代码。

## 验证范围与依赖

使用 ChatTestHarness/TaskTestHarness 的真实隔离PG、任务受理/临时输入/事务/账号锁/配额/TaskStore，以及 ChatAgentPorts 和 TaskGradeLedger（TaskGradeParticipant）。固定模型与固定MCP是仅有外部替身；不复制仓储或把账本/成绩换成内存fake。

初始核查发现并交 owner 修复的阻断：

- ChatAgentPorts.findJob 无行返回空Map，旧AgentTools以非null判为既有作业，导致空job_id与漏配额；chat修复提交 `2598ad1`。
- 旧AgentTools在running/unknown/查询失败时重投，违反已批准 `DECISIONS.md` P0-D13 / COMP-003；主Agent修复 `f4e933c`，产品映射 `71c1b72`、首次丢响应分类及主集成 `b760ea4` 后本轮完整实测通过。

## 场景与当前结果

| 场景 | 断言 | 当前验证 |
|---|---|---|
| 首评与finished重建上下文复用 | deterministic job_id、寄存权威源覆盖模型复述、1扣次/1成绩、finished后三次重建上下文零外部调用 | 通过 |
| 并发finished恢复 | 8个真实多连接上下文并发恢复、真实账号/任务锁、单成绩/submission_done/单扣次、零MCP调用 | 通过 |
| 显式取消与迟到MCP结果 | 在MCP提交后阻塞，真实取消任务后放回结果，拒写成绩，保留cancelled、清输入、不重扣 | 通过 |
| reserve中计数故障 | 唯一合成库临时CHECK约束拒绝grading计数，job insert与计数同事务回滚；零外部grade、零孤立job/扣次/成绩；pending输入保留 | 通过 |
| running→finished | 既有submitted job只查询/等待，不再次grade，不再次扣次、单成绩 | 通过 |
| unknown/查询抛错/错误payload（三分支） | 零重投、system_error及harness_result_unknown；消息/SSE failure_code同形；submitted账本保留、终态清输入 | 通过 |
| 首次SUBMITTED/UNKNOWN丢响应（两分支） | 仅首次一次提交；同上安全终态与清理，不生成成绩，不盲重投 | 通过 |

## 实际执行

2026-10-25 19:04（Asia/Taipei），PG排队授权窗口内：JDK21；四个DPDK_TEST_PG_*环境变量显式指向专属127.0.0.1:55481实例；TestPg先只读身份预检，再创建/迁移唯一合成库，结束删除。口令从gitignored凭据文件读入子进程env，无输出/提交。

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest='RealAgentLedgerIntegrationTest#firstSubmissionUsesRealReservedIdentityAndFinishedRestartsReuseWithoutChargingOrSubmitting+concurrentFinishedRecoveryWritesOneGradeWithRealAccountAndTaskLocks+cancellationWhileMcpIsInFlightRejectsLateGradeAndDoesNotRestoreTemporarySource+reservationCounterFaultRollsBackJobAndQuotaBeforeAnyGradeSubmission'
# 退出0，4/4，失败0、错误0、跳过0；测试时间1.544秒。
```

补丁集成后于2026-10-25 19:07（Asia/Taipei）重新执行完整集成类：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest=RealAgentLedgerIntegrationTest
# 退出0，10/10，失败0、错误0、跳过0；测试时间3.336秒，构建总时间7.115秒。
```

完整测试包含上述4项与安全语义6项，全部实际通过。原始日志保存在本worktree `.migration-artifacts/model/real-agent-ledger-*.log`（gitignored）。当前PG测试停止，排队锁已释放；没有遗留测试库/进程。未调用真实模型/embedding/harness，未跑全量verify。

## 接续与回退

COMP-003及产品映射已由owner修复并经本类完整验证；测试及证据提交供主Agent集成后进行全量verify和P3双轴审查。本切片不自行放行阶段。

四层状态：实现（本测试切片）完成；自动验证10/10；隔离PG场景10/10；用户验收未执行。主Agent已收回PG排队锁，后续真实应用HTTP与全量集成由主Agent执行。

回退只撤销新增测试及本文件；不改DDL迁移，不更改黄金，不触碰生产或恢复资产。故障约束只在本轮独立测试库，已随测试库安全删除。
