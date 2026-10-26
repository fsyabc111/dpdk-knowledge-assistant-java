# 16领域指标真实因果验收（ff59cda）

固定生产源ff59cdab65c2c8977f3f9c45701b773ad9116ccd；worker生产src/main及pom与该集成相同，未修改生产或黄金。先重新编译当前源码，Java collector/filter/legacy诊断14/14（0 skip）、Python指标纯测22/22。旧源通过不继承当前。

实际启动正式Application，TestPg先维护实例user/data_dir校验，再新建owned动态库并用DatabaseIdentityGuard检查库名/user/comment/data_dir；正常唯一迁移初始化owned库，Application启动迁移关闭/任务后台关闭。只替换外部模型/embedding为本probe自带loopback HttpServer；无真实上游/模型，不改共享CandidateServer或fake_upstream。

| 真实入口/条件 | 已独立断言的因果结果 |
|---|---|
| POST /api/chat正常请求 | 真PG physical node+release membership检索，answered/1citation；产品engine=unknown、LLM success1；retrieval/rewrite/embedding、kb_search tool调用/时长/结果体积、validation answered、finish后的RAG outcome/耗时均实际产生；产品Agent tokens仍0。 |
| 外部已知usage与显式合成价格 | 首请求actual upstream返回token5（2次LLM各2+embedding1），入库后counter5；LLM假设input1/output2每1k的合成价→6000micros。配置明确synthetic-not-provider-price，不冒称真实供应商账单。 |
| active release合法空串+实际chat，fake省略usage | fail-closed PG无release，0citation，不制造chunk；LLM fallback1、fallback counter1，真实缺失usage进入unknown。 |
| 模型真实loopback503 | error1；success增量0，终态落库。 |
| ProductChatService.start+POST task cancel | 模型实际已进入阻塞传输后取消，cancelled1；success增量0；RAG finish event不增加。等待managed task和accounting ack排空后采样。 |
| 同request终态POST重放5次再429 | 真per_minute拒绝+counter1；模型调用、日quota与消息count前后均无增量；不直接调用rateLimit钩子。 |
| 独立AgentOrchestrator.run公开facade | 真实模型两轮+真实PG kb_search，actual tokens4→hist count1/sum4；仅给tool context接共享ProcessMetrics，未直接调用agentTokens或其他metric事件。 |

最终真实GET /metrics保留去敏wire：metrics-mapping-causal-ff59.prom。v2 validator依独立固定期望核验16/16领域family均已观测、25项已知断言、46families/236series，原bucket/TYPE/created/lazy label/unknown语义严格保留。metadata_created随真实label child出现，因此总family数随事件与创建元数据变化；不把family数量当事件验收，也不冒称Python34 wire原样等价。JVM四项依旧为实际能力映射，不能伪造python_*对象计数。完整指标来源/每步wire SHA/probe与期望SHA/清理结果见metrics-mapping-causal-ff59.json。

首尝试的probe把NOTNULL release设NULL，fixture失败且已保留本地；改用冻结baseline合法空串后当前实跑通过，未修生产、未弱化断言。正式Application/fake已close，owned TestPg库finally清理；没有Docker、stack或恢复资产操作。

本证据只证明固定源的16领域真实因果过程观测与正式HTTP导出。完整严格差分矩阵、真实provider质量/价格、真实MCP执行恢复、容量/RTO/RPO、J5-03部署演练及用户/live验收均不由此放行。
