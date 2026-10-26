# P5 两项规格阻断复审

固定BASE=`f1c5bb5584e5ee5c71ed9f6fbd3f383e92b3c22f`，TARGET=`ff59cdab65c2c8977f3f9c45701b773ad9116ccd`；已核ref/diff/log。只读源码、决策与证据；仅另抓取获准的Prometheus官方格式页，未构建、测试、访问PG/应用HTTP/容器或提交。下列Java包路径均相对 `backend-java/src/main/java/io/dpdk/assistant/`。

**结论：SP-P5-01/02均可关闭，未发现新增规格阻断；P5总门禁仍未通过。**

- **SP-P5-01关闭。** `retrieval/PgReleaseReader.java:190-225` 对非取消embedding失败置空向量、执行真实词法/路径召回并携源fallback标记；取消因果链继续传播。`chat/ChatTaskExecutor.java:336-342,500-506` 正确区分有真实候选的降级可答与无候选的retrieval_failed。`SP-P5-01-reader.md` 保留旧红及fresh17项绿：真实503失败unknown账、取消、缓存恢复、真实产品引用/空候选/重放零新增均覆盖；本审查未重跑。
- **SP-P5-02关闭。** `chat/ChatTaskExecutor.java:484-490` 产品engine改unknown；`agent/AgentOrchestrator.java:311-312` 仅非strict独立入口发tokens，graph不再普发。产品PG回归明确正常一次、重放零增量和产品tokens=0，符合冻结源入口。

**O01/O02范围成立，但必须保留有意差异。** `DECISIONS.md` 的两项主集成裁定依据用户“补齐源过程指标（保持冻结兼容默认）”，限定活进程观测完整性及失败分类，没有扩张权限、业务扣次、成绩、答案状态或COMP003。`UsageCore` 原子checkpoint/producer lease/锁外sink与 `ChatTaskExecutor.java:276-310` 的ack将历史和迟到实际收据分别归账；已读纯16项及实际HTTP/PG7项证据。取消仍有界，未回执SIGKILL的durable unknown不在保证内。error/cancelled替代源观测缺陷的success属于O02明列修复，不能标为逐字源等价；旧12 unavailable/gauge豁免没有恢复。

**指标比较边界。** 官方[Grouping and sorting](https://prometheus.io/docs/instrumenting/exposition_formats/#grouping-and-sorting)允许不同metric组不固定排序；按唯一descriptor映射比较可用于此单case。仍须拒绝重复descriptor/name+labels、TYPE后置、metric拆组及错误bucket/count/sum；hist桶递增不可忽略。四项runtime按真实JVM能力明列，不能抵扣16领域family。raw CLI差异继续保留，业务数组、黄金和核心比较器不得随之放宽。

**Pending gates。** TARGET的fresh完整metrics/业务增量矩阵、严格差分、J5故障、Java三轮及双端容量判定、实际隔离冷启/升级/备份恢复/应用回退、最终交付manifest与README复验仍待主 Agent。局部通过与Python单端基线不替代这些证据；本次关闭两项缺陷不签认阶段完成或用户验收。
