# P5 metrics 单 case 政策：独立规格轴复审

BASE=`9250d76ba01b554906043eb2dc1e61fb3f8bfc95`；TARGET=`a32d801c6721b7a6e8f7cd0aa3b58d355d9ae60d`。已核ref、非空三点diff及提交链；按code-review规格轴检查，未与规范轴交换发现。仅只读代码/证据，未构建、测试、访问PG/HTTP/浏览器/Docker，未提交。

**结论：未发现本政策新增的规格阻断，可按明确边界纳入J5-01判定；不等于P5阶段完成。**

1. **没有宽泛归一或新豁免。** J5-01原句“零未解释差分”“不改变fixture黄金结果”；LONGRUN第78行要求“不能把‘已登记’当‘已批准豁免’”。`acceptance/contract/metrics_case.py:51-55,119-140` 只接受exact `GET /metrics::success`及route/body形状；30个共同name→TYPE和既定4项runtime映射分别精确核验，最终仅替换该字段的内存副本，完整trace再交原compare。业务数组、引用、身份、权限、状态及数据库delta不参与新归一。运行时映射沿已有用户过程指标裁决，不把JVM能力称为Python对象量等价。

2. **顺序许可没有扩大为忽略结构。** 既有[Prometheus官方分组规范](https://prometheus.io/docs/instrumenting/exposition_formats/#grouping-and-sorting)允许独立metric组顺序不同。`metrics_case.py:57-117`仍拒绝重复TYPE/HELP、拆组、TYPE晚于样本及桶逆序；正式v2 parser/validator继续拒绝重复series、未知family、错误TYPE/labels/buckets/count/sum。只排序完整组，不动组内值，也不调用旧持久gauge验收器。此依据不许可更改业务数组顺序。

3. **注册和因果证明分开。** 用户原句“补齐源过程指标（保持冻结兼容默认）”；P5-O01/O02限定迟到真实收据与错误分类。full07单case输出明确是16域注册、0域已观测、0因果断言；不据TYPE宣称hook通过。独立 `ProcessMetricsCausalSmoke.java:110-184` 经正式Application、真实PG、产品/独立Agent入口及loopback外部替身触发事件；`process-causal.expectations.json`与去敏wire分别支撑16域/25断言，含正常、失败、取消、重放429、known/unknown及独立tokens。已核TARGET与该proof生产源ff59cda的`backend-java/src/main`同为`e8ff677b…`；late receipt/afterCommit仍按O01专项证据，不从25断言外推。

4. **原失败保留且反例边界明确。** `full07-summary.json`保留raw rc1及4个runtime descriptor差异、其他185观察与25表198步；225扩展独立列。原17项为候选自比/16反例；新28项为2正例/26拒绝、false-pass=0，不能混称原CLI通过。`metrics_case_mutations.py`仍覆盖成绩、角色、业务数组、引用、身份、状态、重复/未知/type/bucket突变。

本轮无新增缺失、越范围或误实现发现。后续生产源/政策变化需重绑因果与完整矩阵证据；容量、故障、部署恢复、最终交付及用户验收继续各自判定，不能由此单case成功替代。
