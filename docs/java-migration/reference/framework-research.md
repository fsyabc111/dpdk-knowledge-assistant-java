# DPDK 助教 Java 迁移：生态能力调查

调查日期：2026-10-24。只查官方资料，未编译 Java、未连接本项目模型、数据库或 harness。本笔记是可行性证据，不是迁移验收。

## 判断

Java 可以承担本项目业务 API、模型调用、工具编排与 pgvector 检索。建议以 Spring Boot + Spring AI 作为候选，保留现有 React、PostgreSQL 和隔离 harness；先迁业务，再迁 AI 任务内核。这里的顺序是针对本项目复杂度的工程建议，并非官方迁移指南。

## 官方能力证据

| 需求 | 查证结果 | 依据 |
|---|---|---|
| OpenAI-compatible 模型 | Spring AI 可配置通用和 chat 专用 base-url、API key；文档明确覆盖兼容服务与额外请求参数。只能证明有适配入口，当前供应商的工具调用、流式字段、usage、重试行为仍需冒烟。 | [OpenAI Chat](https://docs.spring.io/spring-ai/reference/api/chat/openai-chat.html) |
| 工具调用 | 支持 @Tool 与 ToolCallback；2.0 的 ToolCallingAdvisor 组织工具循环。应用仍负责执行工具及权限策略。 | [Tool Calling](https://docs.spring.io/spring-ai/reference/api/tools.html) |
| MCP Streamable HTTP | 客户端 starter 支持此传输、同步/异步客户端、连接生命周期及工具集成；可连接独立服务。 | [MCP Client Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html) |
| PostgreSQL 向量 | PgVectorStore 提供向量存储、相似度查询及元数据过滤；有自己的 vector_store schema，不能据此假设直接兼容现有 LlamaIndex 节点表。建议迁移初期用 JDBC/SQL 适配现有表，关闭框架自动建表。 | [PGvector](https://docs.spring.io/spring-ai/reference/api/vectordbs/pgvector.html) |
| Java 与 Boot | 当前 Spring AI 文档标为 2.0.2，并说明 2.0.x 支持 Boot 4.0.x/4.1.x；Boot 当前需求页显示 4.1.2 最低 Java 17，支持到 Java 27。因此 Java 21 属于该范围，但这不是本项目实际依赖构建验证。采用前锁定 BOM 与补丁版本。 | [Spring AI Getting Started](https://docs.spring.io/spring-ai/reference/getting-started.html)、[Boot System Requirements](https://docs.spring.io/spring-boot/system-requirements.html) |

## LangChain4j 备选

LangChain4j 同样支持 [OpenAI-compatible baseUrl](https://docs.langchain4j.dev/integrations/language-models/openai-compatible/)、[MCP Streamable HTTP 工具](https://docs.langchain4j.dev/tutorials/mcp/) 和 [PGvector](https://docs.langchain4j.dev/integrations/embedding-stores/pgvector/)。当前 PGvector 文档还包含 HYBRID + RRF，因此不应笼统声称它没有混合检索；但它的通用融合不等于本项目多路召回、代码信号重排和 MMR 的同等实现。最低 JDK 为 17，部分模块仍为 beta，需逐模块锁版本和验证，见 [Get Started](https://docs.langchain4j.dev/get-started/)。建议二选一，不在首批同时引入两套 AI 抽象。

## 必须自行迁移的业务语义（工程推断）

上述基础能力不会自动迁移本项目的会话 release 绑定、配置指纹校验、版本发布 CAS、历史引用、RRF/MMR 参数、request_id/task_id/input_hash 身份、配额同事务受理、任务心跳认领、断线续跑、取消、harness job_id 去重、源码临时寄存与清理。需要显式 Java 服务与契约测试。

Spring AI 工具循环也不能直接视为现有 LangGraph 行为的替换件：轮数/token 预算、摘要裁剪、未执行 tool_call 的收口、工具授权、失败文本和用量账本均须单独对齐。MCP 兼容性需要针对当前 harness 的实际协议版本及 job_id/get_task_status 参数验收；连接重试不能变成已提交评测的自动重试。

## 首个验证切片建议

在独立测试环境固定 Java/Boot/AI 版本，验证：同一供应商模型一次正常对话与一次工具调用；Java MCP 客户端对现有 harness 的工具枚举及无副作用查询；JDBC 对已有索引快照执行只读查询并校验 release 范围；最终才验证评测幂等。禁止把示例默认建表、默认日志与默认重试配置直接带入现有生产库。
