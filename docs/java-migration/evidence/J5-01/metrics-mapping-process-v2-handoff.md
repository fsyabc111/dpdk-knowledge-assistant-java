# 过程指标WIP恢复点（CPU独占窗口内，仅源码准备）

- 当前新用户要求：补齐源16过程/用量指标；旧unavailable/gauge语义不作豁免。历史证据保留为诊断，默认validator已换source-process-v2。
- worker基线已merge ccf5e97，保留此前metrics/KWS已集成修改。公共ProcessMetrics由root单写；wiring与业务hook由root单写。
- 本worker独占：platform/observability/**、OperationsMetrics.java、新相关测试、acceptance/metrics/**及此metrics证据。其他business/共享wiring/pom/黄金/compare/STATUS未改。
- 已写：正式ProcessMetricsRegistry与JVM实际观测；16源family原buckets/created/lazy labels；HTTP头长度/分组status/完整异步duration；默认endpoint process-only、旧snapshot仅诊断；新source validator与拒绝反例源码，历史诊断validator另存。
- 正式Bean无@Component，root用ObservabilityWiring唯一@Bean。默认Ops构造@Autowired registry，不能silent fallback NONE。工具结果单位保持源Unicode码点，未知tool归unknown；created不消费业务ClockPort。
- CPU窗口前首次短纯test exec82408已exit0；后续async测试/validator等源码写入后尚未运行，不能据此宣称当前快照全绿。
- 进入Python3×(120+600)正式基线窗口后无Maven/测试/服务残留，未运行PG/HTTP/浏览器/容器；仅轻量文件编辑和读取。root释放前不执行验证。
- 窗口释放后的准确动作：先compile定向ProcessMetricsRegistryTest+ProcessHttpFilterTest+旧OperationsMetricsTest→实际Java ExportProcessFixture导出到全新ignore路径→生成/保存新process fixture→Python新旧分类纯测试→按独立已知事件预期跑正式validator。之后与root合并唯一wiring/hooks，另申请局部独立PG/真实HTTP，fresh最终source矩阵/事件值核对。
- 尚未提交本WIP，无自起服务/数据库。所有旧image archives、备份/恢复资产和失败证据均保留。

准备提交补充：Python新validator的纯测试使用明确人工事件模型process_fixture.py，不依赖缺失文件/skip，也不伪称实际JVM采样。Java ExportProcessFixture将在窗口释放后产生独立实际registry事件文本，再用人工已知预期核对；完整业务hook仍由root接入后的fresh实际请求验证。当前ctor无参、renderPrometheus API未变，root可先只读审查并集成此意义清晰WIP，禁止据未运行的新测试写当前全绿。
