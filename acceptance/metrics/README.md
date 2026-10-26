# 正式过程指标验收（source-process-v2）

用户已明确要求补齐源过程观测；**原12 unavailable与持久gauge映射不再作为正式验收豁免**。默认`validate.py`验证真正的16领域counter/histogram及原buckets/created时机，HTTP采用冻结instrumentator7.0.2默认观测，JVM runtime能力差异逐项明示。旧`persistent_diagnostic.py`与旧fixtures只用于历史诊断/重现旧证据，不能接受当前候选。

- 正式Bean是`platform.observability.ProcessMetricsRegistry implements ProcessMetrics`；无@Component，由主集成者共享ObservabilityWiring唯一@Bean。OperationsMetrics生产构造必须注入它，默认GET /metrics不查询持久快照，不输出旧unavailable或持久gauge。
- `backend/app/core/metrics.py`16领域family按源注册顺序。所有带标签family未被真实事件观察时只有注册TYPE，不能伪造sample/created child；无标签实例的零count与created由真正实例初始化产生，按Python客户端语义保留。
- 过程counter从本次真实观测单调增加；不从SQL当前行数复原，不因留存减少。histogram原边界、累计bucket、+Inf/count/sum均核验。只有TYPE注册不足以证明业务hook已喂入，正例需独立已知事件预期。
- usage只累加确实返回的正token；unknown另计；成本仅有整数成本且币种明确时计入。零token不伪造label child，零成本的真实记录可创建0 child，与冻结Python inc(0)一致。
- `assistant_agent_tool_result_bytes`沿冻结源命名，但实际值是`len(json.dumps(result,ensure_ascii=False))`的Unicode码点数，不是UTF8字节；调用方按该口径喂入，不悄改单位。非已知系统tool归unknown，标签不接收问题/源码/身份。
- HTTP同名size Summary严格量Content-Length header，缺失0；不读、缓存或重读正文。状态分组Nxx，handler来自匹配模板/none，duration包含完整异步SSE直到完成；中途错误保留已提交status，完成去重。不是网络帧/TLS/已消费body字节统计。
- JVM真实输出GC次数、GC耗时、heap/nonheap已用内存与JVM版本；没有等价Python对象回收/不可回收计数，不输出伪python_*。这是语言运行时能力差异，不是12领域缺项的替代。

CPU窗口结束后才允许运行的验证命令：

```bash
# 先由主Agent释放36分钟基线CPU窗口；未释放时不执行以下命令。
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
./backend-java/mvnw -o -f backend-java/pom.xml \
  -Dtest=ProcessMetricsRegistryTest,ProcessHttpFilterTest,OperationsMetricsTest test
# 真正Java registry导出显式合成过程事件；这只证明port/registry，不冒充业务hook端到端。
task_metrics_output_dir="$(mktemp -d)"
"$JAVA_HOME/bin/java" --class-path backend-java/target/classes acceptance/metrics/ExportProcessFixture.java \
  "$task_metrics_output_dir/process-events.prom"
python3 -m unittest discover -s acceptance/metrics -p 'test_*.py' -v
python3 acceptance/metrics/validate.py --wire "$task_metrics_output_dir/process-events.prom" \
  --expectations acceptance/metrics/fixtures/process-events.expectations.json
```

`process-spec.json`是正式family/type/labels/buckets/created规则；`mapping.json`反映用户新要求。历史source34与raw strict差异不覆盖或删除；新输出需要在主业务hooks接入后fresh采样，真实PG/HTTP另由主Agent开窗，不能借用UI种子。当前窗口内轻量源码实现未完成后续验证，不宣称新全矩阵或live通过。
