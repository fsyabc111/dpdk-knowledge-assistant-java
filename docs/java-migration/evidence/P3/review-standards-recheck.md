# P3 规范轴独立复审

日期：2026-10-25。原报告 `review-standards.md` 保留；本次以 `git diff 3718d21...5d0d6a3` 复核。已核实修复快照为 `5d0d6a3a0c01bfc1fc0af01dbf5b73bf98674f61`，开始复审时 HEAD 与其一致。重点审查 `c2adbfa`、`8002beb` 与公共说明 `cb466e7`，同时检查树内其余业务变更有无新的规范阻断；不代替规格轴裁决。

## 原阻断结论

1. **成绩 JSON 重复包装：关闭。** `backend-java/src/main/java/io/dpdk/assistant/agent/tools/AgentTools.java:541-549` 现在只传原始 `results` 数组 JSON；`contracts/grading/TaskGradeInput.java:3` 已明确该语义。`task/TaskGradeLedger.java:108-110,126-128` 仍从临时输入取来源并独占包装责任，结果恢复为 `{source_origin,results:[...]}`。这满足根 `AGENTS.md:21` 的冻结计分契约及 `LONGRUN_P3_P5_DRAFT.md:73` 的结构比较要求。`backend-java/src/test/java/io/dpdk/assistant/chat/RealAgentLedgerIntegrationTest.java:219-248` 确实执行真实 Agent→任务→成绩链，断言数组类型、两条 PASS/FAIL 用例逐项等值、来源、源码不泄露、失败用例进入画像及一次扣次；不再依靠字符串包含掩盖结构错误。`agent/TaskLedgerTest.java:89-93` 也约束生产者输出为数组。

2. **chat 越界拥有成绩 SQL：关闭。** `backend-java/src/main/java/io/dpdk/assistant/chat/ChatAgentPorts.java:80-85` 仅委托 grading 域；SQL 与画像计算归 `grading/query/AgentGradingStatsService.java:24-101`。原非任务 INSERT 已删除，`:88-98` 明确拒绝绕过 taskGrade；真实 PG 回归 `backend-java/src/test/java/io/dpdk/assistant/chat/RealAgentLedgerIntegrationTest.java:252-272` 检查拒绝且成绩零行。原 `TaskGradeLedger.java:49-121` 的 enabled user→pending task→job 锁及成绩/完成标志同事务未被修改。满足 `ARCHITECTURE.md:7` 的模块 SQL 边界；接口语义已登记于 `migration/contracts/interface-changes.md:149`。

## 新阻断与建议

本次变更中未发现新增规范阻断：无 DDL/部署变化，统计迁移保留查询过滤与排序，原事务所有权和 COMP003 未被削弱；上下文与预算修复的新增公共接口保留旧构造/默认入口，并有登记。没有把适配层委托本身当作 Middle Man 缺陷，冻结消费端口优先。

原 **possible Duplicated Code**（五工具元数据/schema 重复）继续作为非阻断后续建议，不扩大本轮重构。

## 验证与门禁

本 worker 仅只读复审，未运行 PG、构建或测试，未提交/推送。已读取主 Agent 执行的 `.migration-artifacts/p3-reviewed-full-verify.log`：**591 项、0失败/0错误/0跳过、BUILD SUCCESS**，包含 TaskLedgerTest 20、RealAgentLedgerIntegrationTest 12、TaskGradeLedgerTest 6；该结果属于主 Agent 当前统一验证，区别于 worker 文档中的此前 20+18 项结果。

**规范轴工程门禁：通过；原 2 项阻断均关闭，新增阻断 0，保留非阻断建议 1。** 本结论限固定修复快照及隔离合成范围；P3 总门禁仍由主 Agent 汇合独立规格轴和当前验证证据判断，用户/live/生产验收不随之改变。
