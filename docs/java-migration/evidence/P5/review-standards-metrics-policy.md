# P5 metrics 政策：独立规范轴复审

2026-10-28。固定 BASE=`9250d76ba01b554906043eb2dc1e61fb3f8bfc95`、TARGET=`a32d801c6721b7a6e8f7cd0aa3b58d355d9ae60d`，已验证；依据固定三点diff、提交列表和TARGET源码/证据。未构建、测试、PG、HTTP、浏览器、Docker或网络取证；仅新增本报告，不提交。

## 阻断

**0项：限定范围内未发现可行动的规范违规。** `migration/oracle/**`、冻结fixture和生产源码在本增量均未改。符合根AGENTS的契约边界、`COORDINATION.md:30-31`的黄金所有权及`LONGRUN_P3_P5_DRAFT.md:73`的严格比较要求。

- `acceptance/contract/metrics_case.py:39-55,119-140` 精确限定case_id、route_id和body键；重复descriptor拒绝。30项共同name/TYPE必须等于源schema，4项Python runtime与4项JVM能力按固定清单分列。仅替换该明确字段的内存副本，其余完整trace交回原compare，未排序业务数组、删身份或忽略状态/副作用。
- `metrics_case.py:57-117,131-134` 核对wire/trace descriptor相等，拒绝重复metadata/series、晚到TYPE、拆组及bucket逆序；canonical只重排完整独立metric组，组内原行不改，再调用正式source-process-v2 validator，未调用旧持久诊断validator。原始wire和canonical SHA均记录。
- `metrics_case_mutations.py:12-41` 将实际完整候选交回冻结golden；保留原16业务反例并增加权限及metrics反例。`full07-summary.json:26-59,194-215` 诚实保留原CLI rc1、185原始相等、政策28项/原比较器17项的不同证明域，没有把自比或政策结果改称原CLI通过。

## 非阻断边界与因果证据

政策结构校验本身不证明hook；`metrics_case.py:146-147`明确要求独立证据。`full07-summary.json:143-192`把早期checkpoint的0域观测/0因果断言，与eb719b6集成的独立16域/25断言证明分列，符合`acceptance.md`验收等级和`COORDINATION.md:53-54`的完成语义。

已静态核读 `ProcessMetricsCausalSmoke.java:118-179`：正式Application/owned PG，产品HTTP正常、fallback、503、公开start/cancel、终态重放与429，以及独立Agent facade触发真实共享registry；没有直接emit指标。独立fixture要求16域真实观测，已知token/合成成本、失败/取消success零增量和产品/独立Agent事件归属均有断言。late receipt/afterCommit竞态仍引用P5-O01专门证据，不能由这25项外推；JVM映射也不声称Python对象量等价。

完整Fowler baseline未发现需独立处置的`possible`气味建议。现有记录的实际结果归执行者，本复审未复跑；以后生产树变化必须重新绑定证据，不继承本次通过。

**结论：支持该显式单case政策的规范轴通过。** 政策CLI exit0、16域proof及原raw rc1保持各自含义；P5总工程门禁、容量/部署、用户/live验收仍由主Agent分别汇合，不由本报告代签。
