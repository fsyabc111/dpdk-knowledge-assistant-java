# J1-02 锁定依赖说明

基线提交 `f84d2c9`（Boot 4.0.9 / Java 21 / Maven Wrapper）。本切片**零新增 Maven 依赖**，
`pom.xml` 未改动；全部能力来自既有 classpath：

| 能力 | 使用的库（版本随 Boot 4.0.9 BOM 锁定） | 说明 |
|---|---|---|
| HTTP 客户端 | `spring-web` `RestClient` + `JdkClientHttpRequestFactory`（JDK 21 内置 `java.net.http.HttpClient`） | 对位 Python httpx.AsyncClient；连接超时 10s、读超时 90s（chat）/60s（embedding），与源 `httpx.Timeout(total, connect=10)` 一致 |
| JSON 树构建/解析 | Jackson 2 `com.fasterxml.jackson.core:jackson-databind:2.21.7` | 请求/响应树与 usage 解析；不把 Jackson 2 节点直接交给 RestClient 写（见下） |
| HTTP 消息转换 | classpath 同时存在 Jackson 3 `tools.jackson.core:jackson-databind:3.1.7` 与 Jackson 2；RestClient 默认转换器优先 Jackson 3 | **实测坑**：Jackson 2 `ObjectNode` 经 RestClient 写出会按 POJO 展开成属性 dump。规避：harness JSON-RPC 请求体自行序列化为 UTF-8 字节数组发送（`ByteArrayHttpMessageConverter` 原样上送）；chat/embedding 发送 `Map<String,Object>`（两种 Jackson 均按映射正确序列化）。已用测试锁定 |
| 日志 | SLF4J（Boot 自带） | `mcp_call_failed`/`mcp_call_retry`/`mcp_bad_response`/`llm_stream_options_rejected` 事件；**不回显响应体/合成源码哨兵**，只记类型与长度（对源 `payload[:500]` 日志的有意收紧） |
| 测试 | JUnit 5 + AssertJ（spring-boot-starter-test） + JDK `com.sun.net.httpserver` | 零新测试依赖 |

## 不承诺 / 未引入

- 未引入 MCP 官方 Java SDK：Streamable HTTP 传输按 JSON-RPC + `Mcp-Session-Id` 自实现，
  行为对齐冻结源 `backend/app/agent/mcp_client.py`（每次调用新传输，不池化）。
- 未宣称长连接/keep-alive 语义：JDK HttpClient 实例内自带连接复用，但客户端注册表只承诺
  「同 base URL 复用同一客户端实例」（对位 `get_harness_mcp_client` 与类级 httpx 缓存）。
- 未做 Spring Bean 装配/配置文件接线：`ChatClientSettings`/`EmbeddingClientSettings` 为
  显式构造的配置 record，Spring 绑定由集成者在后续切片完成。
