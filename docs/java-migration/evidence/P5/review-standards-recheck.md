# P5 规范轴复审

2026-10-26。固定 BASE=`f1c5bb5584e5ee5c71ed9f6fbd3f383e92b3c22f`、TARGET=`ff59cdab65c2c8977f3f9c45701b773ad9116ccd`，均已验证；仅据两者diff/log及TARGET源码。原首轮报告保留；本worker未编译、测试、连接PG/HTTP或操作容器。

## 三项阻断

1. **ST1：在P5-O01明确范围内关闭。** `backend-java/src/main/java/io/dpdk/assistant/usage/UsageCore.java:283-361` 的checkpoint与append共用monitor，条目只属于历史快照或迟到sink之一；sink锁外交付，deliveries计数覆盖其退出。完成条件同时要求checkpoint已ack、producers与deliveries均为0，重复lease.close无效；失败sticky，drain清receiver。`retrieval/RetrievalUsage.java:20-30` 在shared loader提交前取lease、finally归还；取消waiter仍记0调用而不杀owner。`chat/ChatTaskExecutor.java:212-233,266-309` 对candidate起步/取消用CAS归还lease，终态前最多握手100ms后安装迟到sink；快照与增量均经真实账本/afterCommit路径，完成后才publish指标。迟到闭包仅捕获身份字符串、策略、计量对象及服务引用，没有context、问题或源码；同task多轮ack使用集合。失败不盖业务结果，但sticky failure令`:172-181`排空失败；CandidateServer`:125-127`不报settled。满足原`interface-changes.md:185,193`收尾要求。`DECISIONS.md/P5-O01`明确只保证活进程真实receipt，transport发出尚未回执即SIGKILL的durable票据/RPO不在保证内，不能外推成本RPO0。

2. **ST2：关闭。** `chat/ChatTaskExecutor.java:484-490` 的null结果明确分类error/cancelled，只有真实返回结果才可success/fallback，engine恢复unknown。`agent/AgentGraph`移除产品raw graph的token事件，`AgentOrchestrator.java:312`仅独立facade记录。`ProductProcessMetricsPgTest`覆盖正常/重放与模型异常不增success；取消路径分类源码可达。`DECISIONS.md/P5-O02`已把源Python相同观测缺陷及Java有意修正单列，没有冒充逐字源等价；满足原真实失败归因要求。

3. **ST3：关闭验收器代码缺口。** `acceptance/deployment/business_proof.py:11-34,53-68` 枚举全部public表，在只读一致快照内计算完整行有序多重集与列结构摘要，保留重复行；仅排除rag_task_inputs数据，仍校验其结构与目标0行。`rehearse.py:84-99`实际调用该证明后才报告完整保留行相等，并明确sequence/constraints/index不由此摘要证明。`test_business_proof.py:30-45`及`proof_sql_smoke.py:36-46`拒绝同count错成绩；native摘要SQL证据不冒充真实Docker dump/restore。符合原`LONGRUN§5:79`和`COORDINATION:53-54`的证据边界。

## 新问题、证据与门禁

未发现此次增量新增规范阻断；完整Fowler baseline未另列需处置的possible建议。收据回归源码包含32并发条目并集/去重、锁外sink、producer与sink未结束时ack未完成、迟到PG失败与known/unknown真实HTTP；不是仅依据测试计数关闭。handoff登记纯16、HTTP/PG7项，属于实现者执行，本worker未复跑。

ff59的预算回归保留实际模型次数、总token、扣次及零grade守卫，单独约束新增rewrite为0调用，不是放宽错误分母。当前完整verify未形成全通过证据，3项DefaultBoundaryPgTest仍需显式owned环境；fresh矩阵、正式双端容量及实际部署恢复也继续pending。

**原3项代码阻断关闭；本次复审无新增规范阻断。支持修复增量的规范轴关闭，不代P5总工程门禁或用户/live验收。** 仅新增本报告，不修业务、不提交。
