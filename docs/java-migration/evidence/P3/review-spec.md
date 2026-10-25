# P3 独立规格轴审查

审查快照 `3718d21a0144f6a6fceb6cd416ba10e528778317`，基线 `19a2d47310a50114a364a4a72bed50c1dca5a16e`；已核实 HEAD、非空三点 diff 和提交链。依据 U12 续接书、原任务书 §4/5、J3-01～04、冻结 Python 与 COMP-003。业务源码只读；本审查未执行测试、PG、真实上游或提交推送。

**结论：暂不放行 P3 工程门禁。** 以下两项为源码可确定的行为缺口；585/585 是已有执行证据，不能覆盖这些未测试组合。

## SP-P3-01 [P1] 请求 token 预算在副作用完成后才检查

要求：J3-02 原句“轮次/token 达限转 finalize”“预算、执行源和账本规则没有依赖提示词来实现”；产品冻结基线 `backend/app/services/rag_product_service.py:544-547` 在每次 planner/finalize 更新时检查独立的 `rag_request_token_budget`，超限立即终止。

位置：`backend-java/src/main/java/io/dpdk/assistant/chat/ChatTaskExecutor.java:353-380` 整个 `runProduct` 返回后才检查产品预算；`agent/graph/AgentGraph.java:169-185` 仅依据另一项 Agent 预算调度工具。

可复现触发：设 `RAG_REQUEST_TOKEN_BUDGET=10`、`AGENT_REQUEST_TOKEN_BUDGET=48000`，固定模型首轮返回 total_tokens=11 和合法 grade_plugin 调用。Java 仍执行工具、扣评测配额并保存成绩，再返回 generation_failed；Python 在该 planner 更新即停止。即使默认两预算相同，超产品预算后仍可能多调用一次 finalize。

修复建议：把产品预算检查放到每轮模型结果产生后、工具/后续模型调用前，保持两项预算各自语义；用真实产品任务和账本、仅外部替身验证超限时 grade=0、评测扣次/成绩=0，且不再调用 finalize。

## SP-P3-02 [P2] 产品检索丢失会话追问语境

要求：J3-03 原句“提供稳定 `/api/chat*` 接口，集成任务、Agent”；其冻结行为入口 `rag_product_service.py:535` → `agent/tools.py:262-265` → `services/retrieval_service.py:421-437` 将记忆传给检索并先消解追问。

位置：`chat/ChatTaskExecutor.java:225-238` 收到 conversation 却只构造 `SearchInput(query,null)`；`retrieval/PgReleaseReader.java:129-130` 仅调用单参数 rewrite；`HeuristicQueryRewriter.java:50-63` 的双参数追问消解分支因此在产品链不可达。

可复现触发：已有历史“正在讨论 DNS 插件”，固定 planner 调用 `kb_search(query="它如何实现？")`。Java 改写请求不含历史；即便 followup 替身配置为解析到 DNS，该分支也不被调用，候选/引用可能偏离 Python。更换历史为 HTTP 时仍丢掉同样的信息。

修复建议：登记最小检索端口扩展，完整传递 conversation 到双参数 rewrite；真实检索链固定两种历史和相同追问，断言 followup 请求、embedding 输入及引用。

## 证据边界

已检查双 JVM ProcessBuilder、保存失败触发器禁止 delta、取消拒绝迟到成绩、COMP-003 查等/未知终态的源码与测试入口；未发现需推翻相应已有验证的依据。浏览器、live 和 P5 部署/容量不是本次阻断项；讲解固定模型的零重评证据不扩大成服务端强授权保证。修复两项后应定向复验并更新审查快照，再裁决工程门禁；用户验收保持独立。
