# J5-01 metrics单case严格比较政策（待独立两轴复审）

依据本轮主Agent直接授权、DECISIONS P5-O01/O02、`acceptance/metrics/mapping.json`的source-process-v2及[Prometheus官方分组和排序规范](https://prometheus.io/docs/instrumenting/exposition_formats/#grouping-and-sorting)。官方要求每个度量的行保持单一组，HELP/TYPE位于样本前，TYPE和name+labels唯一；独立度量组的排序不强制，histogram bucket仍须按数值递增。本次已直接只读核实官方页面。

唯一适用点是`case_id=GET /metrics::success`、`route_id=GET /metrics`的`body.families`。原始golden、candidate、Prometheus wire和`migration.oracle.compare/verify`保持不变、原exit状态保留。新`metrics_case.py`在内存副本中，仅将该字段变为已验证的name→TYPE schema及明确runtime映射标记，再将完整trace交回原compare。不会排序/删除业务数组、引用、身份、状态、时间、数据库delta或其他字段，不新增通配route/regex允许差异。

源30个领域/HTTP/created descriptor的名字与TYPE逐项精确相等。4个Python runtime descriptor与4个真实JVM descriptor只能按已登记能力差异单列；JVM GC次数/耗时和heap/nonheap内存不是Python对象回收数或generation同义量。候选额外family、漏项、错误TYPE、重复TYPE/HELP、同名同标签series、非单一分组、TYPE晚于样本、bucket逆序均拒绝。

候选descriptor唯一map必须等于其真实wire。正式`acceptance.metrics.validate`（source-process-v2）继续验证counter/summary/histogram、原bucket、labels、created时机及未知/零值语义。为遵循官方独立组顺序规则，只按正式spec将完整metric组排序供该validator读取，组内样本/labels/数值/桶顺序不改；原始与排序后SHA分别保留。通用Prom parser由正式validator复用，**不调用旧persistent_diagnostic.validate**。

descriptor注册与结构正确不能证明16域业务hook正确。正常、失败、取消、重放、独立Agent入口、late receipt/afterCommit等因果证据必须独立通过，P5-O01/O02有意观测修正必须按原范围单列；这个比较器不宣告P5门禁或用户验收。

runtime名单固定如下，不按位置或数值建立一一同义关系：源为`python_gc_objects_collected_total counter`、`python_gc_objects_uncollectable_total counter`、`python_gc_collections_total counter`、`python_info gauge`；JVM为`jvm_gc_collections_total counter`、`jvm_gc_collection_seconds_total counter`、`jvm_memory_used_bytes gauge`、`jvm_info gauge`。没有Python对象量/分代不可用的伪零值，也没有12 unavailable或持久gauge豁免；源16个领域过程指标仍必须是真实counter/histogram/summary。

本轮独立因果proof为`docs/java-migration/evidence/J5-01/metrics-mapping-causal-ff59.{json,md,prom}`（production `ff59cdab65c2c8977f3f9c45701b773ad9116ccd`，root集成`eb719b6`）。独立fixture `acceptance/metrics/fixtures/process-causal.expectations.json`固定25断言；正式Application、owned PG和loopback外部替身经业务入口观测16/16领域family，46 family/236 series，原wire SHA `7eef43d7d26e008d0d2828db80b1551827075d032bf7f905ca56b33a6af2063a`。本worker只读重验该wire与fixture得到相同25断言通过，没有重复运行业务或直接emit指标。full07早期checkpoint只有TYPE注册，单列为0领域已观测/0因果断言，不拿它替代这份proof。late receipt/afterCommit竞态以root P5-O01测试证据单列，不能从这25断言推导。

反例要求包括：同类型重复descriptor也拒绝；未知/漏family；counter误为gauge；wire与trace不一致；metadata晚于样本；样本重复；桶逆序；metrics HTTP状态错误；另一个route伪装同case；产品score/role/业务数组/引用/跨case身份突变。正例仅允许此case的独立metric组顺序改变和明确runtime映射。实际完整候选必须再次执行，不能以人工fixture结果替代。

```sh
<python> -m acceptance.contract.metrics_case \
  --candidate <本轮java-candidate-trace-p0-v2.json> \
  --wire <本轮wire/30.prom> --out <本轮single-metrics-policy.json>
<python> -m acceptance.contract.metrics_case_mutations \
  --candidate <本轮java-candidate-trace-p0-v2.json> \
  --wire <本轮wire/30.prom> --out <本轮policy-mutants.json>
<python> acceptance/metrics/validate.py \
  --wire docs/java-migration/evidence/J5-01/metrics-mapping-causal-ff59.prom \
  --expectations acceptance/metrics/fixtures/process-causal.expectations.json
```

本轮完整候选：原`migration.oracle.verify` rc1原件保留（仅runtime4 descriptor）；单case policy控制与完整trace反例28/28，其中2允许正例、26必须拒绝的反例，false-pass 0。原判定器实际候选自比+16反例17/17另列，不混称原CLI通过。纯测试19个policy反例/边界与11个原helper检查共30/30；最初手写测试模块名`test_collect`不存在导致命令错误，改为discover实际模块后30/30，未将该错误命令计通过。
