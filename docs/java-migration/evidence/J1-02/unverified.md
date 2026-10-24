# J1-02 未验证供应商能力清单

本切片通过仅指**本地替身契约**（JDK HttpServer mock / fake MCP server），以下能力
未经真实上游验证，保留限制待后续 live 批次授权：

1. **真实 OpenAI 兼容供应商**（DashScope qwen3.8-flash、text-embedding-v4）：
   - 真实 SSE 分帧/心跳/keep-alive 行为、非 UTF-8 分块边界；
   - 429 的 Retry-After 以 HTTP-date 形式给出时（Java 侧仅解析数值秒，其余走指数退避）；
   - 供应商对 `stream_options.include_usage` 尾帧的真实兼容性（400 兜底分支只在替身上验证）；
   - 真实鉴权失败文案与 401/403 差异。
2. **真实 teaching_harness MCP 服务**：
   - 官方 Python MCP SDK 的完整 Streamable HTTP 会话语义（协议版本协商、能力协商、
     DELETE 会话终止、SSE 进度通知 interleaving）——Java 客户端实现最小子集
     （initialize/initialized/tools/call + Mcp-Session-Id + JSON/SSE 双响应形态）；
   - 与真实 harness 的 `grade_plugin` 大 payload（完整 plugin_source）时延与读超时匹配；
   - 真实服务在建连阶段的 401/redirect 行为。
3. **连接池/长连接语义**：JDK HttpClient 复用连接的行为在高并发下未压测（源实现也不承诺）。
4. **取消的远端效果**：Java 取消只停止调用方等待，已发送请求背景收尾（对 Python
   asyncio 取消传播的差异已在 handoff 登记），真实供应商对该模式的计费行为未验证。
5. **价格表**：`LLM_PRICES_JSON` 解析与落库属后续计费切片，本切片仅交付 costMicros 边界。

## 有意的行为差异（对冻结源）

| # | 差异 | 理由 | 影响 |
|---|---|---|---|
| 1 | MCP 参数序列化提前到任何 HTTP 建连之前：不可序列化参数 → NOT_SUBMITTED、零 HTTP 请求（源实现序列化发生在 submitted=True 之后，落入 UNKNOWN 不重试分支） | Java 无法复刻 Python SDK 的时序；更保守且对调用方更有利（可安全重试） | 归类从 UNKNOWN 提前到 NOT_SUBMITTED；已记入接口差异 |
| 2 | `mcp_bad_response`/`mcp_call_failed` 日志不回显 payload（源打印 `payload[:500]`） | 任务卡验收 3：异常日志不得回显合成源码哨兵 | 仅日志；返回给调用方的 detail 仍按源截断 2000 字符 |
| 3 | chat 取消采用 shield 模型：调用线程停止等待，已发送请求在背景 IO 线程收尾（Python 取消会传播中止请求） | JDK HttpClient 不可安全中断；shield 保证账目与资源释放确定性 | 服务器可能完成被取消的请求；账记 unknown，不伪称远端已取消 |
| 4 | embedding 响应的 `index` 非数字时按 0 排序（Python 直接 TypeError） | 宽容解析，不引入新的失败面 | 极端形态下顺序语义可能不同 |
| 5 | harness 新增错误码 `harness_bad_request`（参数不可序列化）与 `harness_deadline_exceeded`（期限到期）；其余错误码与源一致 | 契约 Deadline 参数需要显式归类 | 调用方按 SubmissionPhase 分支不受影响 |
