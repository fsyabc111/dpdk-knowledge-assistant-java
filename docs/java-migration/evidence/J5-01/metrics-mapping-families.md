# 源34 family到Java现有语义逐项映射

本表来自只读P0-v2冻结GET /metrics case；不改变黄金、strict比较器或豁免。Java类型/缺项来自当前OperationsMetrics及OperationsDomainMetricsAdapter。持久账本gauge留存后可下降，不能当作源进程counter。

| 源family | 源TYPE | Java实际/缺项 | 语义边界 |
|---|---|---|---|
| python_gc_objects_collected_total | counter | 未导出 | Python GC/runtime专属观测；Java未伪造python_*，当前无运行时family替代 |
| python_gc_objects_uncollectable_total | counter | 未导出 | Python GC/runtime专属观测；Java未伪造python_*，当前无运行时family替代 |
| python_gc_collections_total | counter | 未导出 | Python GC/runtime专属观测；Java未伪造python_*，当前无运行时family替代 |
| python_info | gauge | 未导出 | Python GC/runtime专属观测；Java未伪造python_*，当前无运行时family替代 |
| assistant_llm_requests_total | counter | 未导出；available{metric=该源名}=0 | 缺少真实过程观测证据，禁止伪造静态0原family |
| assistant_llm_request_duration_seconds | histogram | 未导出；available{metric=该源名}=0 | 缺少真实过程观测证据，禁止伪造静态0原family |
| assistant_chat_retrieval_duration_seconds | histogram | 未导出；available{metric=该源名}=0 | 缺少真实过程观测证据，禁止伪造静态0原family |
| assistant_chat_retrieval_duration_seconds_created | gauge | 未导出 | Python客户端创建时间元数据，Java未导出；不是业务count缺失补0 |
| assistant_chat_stage_duration_seconds | histogram | 未导出；available{metric=该源名}=0 | 缺少真实过程观测证据，禁止伪造静态0原family |
| assistant_chat_fallbacks_total | counter | 未导出；available{metric=该源名}=0 | 缺少真实过程观测证据，禁止伪造静态0原family |
| assistant_chat_fallbacks_created | gauge | 未导出 | Python客户端创建时间元数据，Java未导出；不是业务count缺失补0 |
| assistant_rate_limit_hits_total | counter | 未导出；available{metric=该源名}=0 | 缺少真实过程观测证据，禁止伪造静态0原family |
| assistant_rag_outcomes_total | counter | assistant_rag_outcomes_total / gauge | 非pending现存任务行数，status/reason；留存删除可下降 |
| assistant_rag_duration_seconds | histogram | 未导出；available{metric=该源名}=0 | 缺少真实过程观测证据，禁止伪造静态0原family |
| assistant_rag_duration_seconds_created | gauge | 未导出 | Python客户端创建时间元数据，Java未导出；不是业务count缺失补0 |
| assistant_rag_validation_duration_seconds | histogram | 未导出；available{metric=该源名}=0 | 缺少真实过程观测证据，禁止伪造静态0原family |
| assistant_agent_tool_calls_total | counter | 未导出；available{metric=该源名}=0 | 缺少真实过程观测证据，禁止伪造静态0原family |
| assistant_agent_tool_duration_seconds | histogram | 未导出；available{metric=该源名}=0 | 缺少真实过程观测证据，禁止伪造静态0原family |
| assistant_agent_tool_result_bytes | histogram | 未导出；available{metric=该源名}=0 | 缺少真实过程观测证据，禁止伪造静态0原family |
| assistant_agent_request_tokens | histogram | 未导出；available{metric=该源名}=0 | 缺少真实过程观测证据，禁止伪造静态0原family |
| assistant_agent_request_tokens_created | gauge | 未导出 | Python客户端创建时间元数据，Java未导出；不是业务count缺失补0 |
| assistant_upstream_usage_tokens_total | counter | assistant_upstream_usage_tokens_total / gauge | 仅已知total_tokens之和，全NULL组不输出0 |
| assistant_upstream_usage_cost_micros_total | counter | assistant_upstream_usage_cost_micros_total / gauge | 仅已知已定价成本，model/currency分别聚合 |
| assistant_upstream_usage_unknown_total | counter | assistant_upstream_usage_unknown_total / gauge | usage_source=unknown账本行数，不能当成0 |
| http_requests_total | counter | assistant_java_http_requests_total / counter | 真实servlet dispatch method/status计数；标签/框架域不同，不是源handler family逐字兼容 |
| http_requests_created | gauge | 未导出 | Python客户端创建时间元数据，Java未导出；不是业务count缺失补0 |
| http_request_size_bytes | summary | 未导出 | Java未实现请求/响应bytes summary；当前无available0 marker，不伪称覆盖 |
| http_request_size_bytes_created | gauge | 未导出 | Python客户端创建时间元数据，Java未导出；不是业务count缺失补0 |
| http_response_size_bytes | summary | 未导出 | Java未实现请求/响应bytes summary；当前无available0 marker，不伪称覆盖 |
| http_response_size_bytes_created | gauge | 未导出 | Python客户端创建时间元数据，Java未导出；不是业务count缺失补0 |
| http_request_duration_highr_seconds | histogram | assistant_java_http_dispatch_duration_seconds / summary | 仅dispatch count/sum；无源histogram bucket/高精度分布或完整SSE流寿命等价 |
| http_request_duration_highr_seconds_created | gauge | 未导出 | Python客户端创建时间元数据，Java未导出；不是业务count缺失补0 |
| http_request_duration_seconds | histogram | assistant_java_http_dispatch_duration_seconds / summary | 仅dispatch count/sum；无源histogram bucket/高精度分布或完整SSE流寿命等价 |
| http_request_duration_seconds_created | gauge | 未导出 | Python客户端创建时间元数据，Java未导出；不是业务count缺失补0 |

规格依据：foundation-slices.md:48、55；P4 review-spec.md:23与review-standards.md:17（主集成ab9c603树）。前者明确持久gauge/过程unavailable不是完整counter兼容，后者明确不据已明示不可用映射自动新增P4阻断。它们支持有边界的映射验收，不等于批准将34条TYPE逐字比较改成通过。

原始分组：Python runtime4、业务domain16（4持久映射/12过程缺项）、Python业务created元数据4、自动HTTP10。自动HTTP中的created元数据同样未仿造；请求/响应大小summary未实现；dispatch summary不是源请求histogram或SSE流寿命。
