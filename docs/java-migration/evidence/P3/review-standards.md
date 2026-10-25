# P3 独立规范轴审查

审查日期：2026-10-25。固定基线 `19a2d47310a50114a364a4a72bed50c1dca5a16e`；固定快照 `3718d21a0144f6a6fceb6cd416ba10e528778317`。已分别以 `git rev-parse` 核实；范围为 `git diff 19a2d47...3718d21` 的全部 162 文件、21086 新增/202 删除；提交链以 `git log 19a2d47..3718d21 --oneline` 核对，包含全部 P3。业务源码只读，未运行 PG、构建或测试，未提交/推送。

## 阻断：有文档依据的违规

1. **[P2] 统一成绩结果的序列化责任，补结构断言。** `backend-java/src/main/java/io/dpdk/assistant/agent/tools/AgentTools.java:542-552` 已将 `results` 数组包装为 `{source_origin,results}` 传入 `TaskGradeInput.resultsJson`；`backend-java/src/main/java/io/dpdk/assistant/task/TaskGradeLedger.java:126-128` 又将整个对象放进 `results`。实际落库形状因此为 `{source_origin,results:{source_origin,results:[...]}}`。同快照 `backend-java/src/main/java/io/dpdk/assistant/chat/ChatAgentPorts.java:125-129` 按数组逐项读取，无法识别这些失败用例，学习画像丢失常挂用例。规则：`AGENTS.md:21` 要求计分遵守冻结契约；`docs/java-migration/LONGRUN_P3_P5_DRAFT.md:73` 要求字段类型/值及跨表副作用严格比较。Python 参照 `backend/app/services/grading_service.py:93-95` 只包一层。`backend-java/src/test/java/io/dpdk/assistant/chat/RealAgentLedgerIntegrationTest.java:155-156` 仅检查来源字符串/源码缺失，不能发现此错；`backend-java/src/test/java/io/dpdk/assistant/task/TaskGradeLedgerTest.java:59-61` 则直接传数组，绕开真实生产者。修复后须经真实 Agent→账本断言 `results` 为数组、逐项内容等值，并验证画像仍有失败用例。

2. **[P2] 将成绩 SQL 收回业务模块。** `backend-java/src/main/java/io/dpdk/assistant/chat/ChatAgentPorts.java:81-158,166-207` 在聊天适配器直接查询和插入 `grading_submissions`，同时实现画像计算与成绩持久化；成绩格式或授权不变量变化时必须跨模块修改。规则：`docs/java-migration/ARCHITECTURE.md:7` 明确“调用者不直接拼接其他模块的SQL”；`:17-20` 划定模块职责；`migration/contracts/interface-changes.md:117-118` 仅确认 task 账本与成绩参与消费端口，没有批准 chat 接管成绩仓储。应由 grading/assignment 提供真实读写端口，chat 仅适配；非任务写入若没有产品调用方可删除。验证原任务成绩/配额/画像行为，并保留任务写入的账号、pending 与 job 锁。

## 建议：启发式判断

- **possible Duplicated Code**：`backend-java/src/main/java/io/dpdk/assistant/model/AgentModelAdapter.java:86-123` 的五工具说明/参数与 `agent/tools/AgentTools.java:78,140,181,227,268` 各工具元数据重复维护；后者 `description()` 当前没有生产消费者。可集中工具描述与参数定义，避免改工具后供应商 schema 漂移。此项为判断性建议，不单独阻断；已按完整 smell baseline 检查，其余未列出无可行动证据的猜测。

## 证据与结论

已核查受理/完成事务、取消与恢复、COMP003、安全日志、装配及 J3-01～04 交接。未把旧差异、P4/P5 的 TTL 运维/浏览器待验计作本轮新缺陷。`phase-report.md`、`validation.json` 的 585/585、前端 102 与 build 通过属于既有快照证据，本审查未复跑，也不将其推导为结构正确。

**规范轴暂不支持 P3 工程放行：2 项阻断、1 项非阻断建议。** 修复并补相应结构/模块边界验证后复审；本结论独立于规格轴及用户验收。
