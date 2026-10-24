# J1-02 脱敏请求/响应样本

机器可读样本：`backend-java/src/test/resources/migration/J1-02/wire-samples.json`
（由 `WireSamplesTest` 校验可解析、形态一致、无凭据值）。

## chat 非流式

请求（POST `{llm_api_base 去尾斜杠}/chat/completions`，头 `Authorization: Bearer ***`）：

```json
{"model": "<MODEL_NAME>", "messages": [{"role": "user", "content": "…"}],
 "temperature": 0.2, "max_tokens": 2048}
```

- temperature 固定 0.2（源硬编码）；max_tokens 来自 llm_max_tokens。
- `ChatInput.model` 非空时覆盖 settings.modelName。

成功响应（脱敏）：

```json
{"choices": [{"message": {"role": "assistant", "content": "…"}}],
 "usage": {"prompt_tokens": 120, "completion_tokens": 30, "total_tokens": 150}}
```

- usage 缺失 → 账目 unknown（token 列 null），不影响返回值。

## chat 流式（SSE）

请求在非流式基础上加 `"stream": true`，`llm_stream_usage_enabled` 时再加
`"stream_options": {"include_usage": true}`。响应帧（text/event-stream）：

```
data: {"choices":[{"delta":{"content":"你"}}]}
data: {"choices":[{"delta":{"content":"好"}}]}
data: {"choices":[],"usage":{"prompt_tokens":40,"completion_tokens":5,"total_tokens":45}}
data: [DONE]
```

- 增量按读取边界逐段投递（读取边界 ≠ SSE 帧边界）；坏帧跳过；尾帧只进账本。
- 400 且正文含 `stream_options`/`include_usage` → 去掉字段重发一次。

## embeddings

请求（POST `{embedding_api_base 去尾斜杠}/embeddings`）：

```json
{"model": "<EMBEDDING_MODEL>", "input": ["文本一", "文本二"]}
```

- input 按批（clamp 1..10）切分；空输入不发请求不记账。

响应（脱敏）：

```json
{"data": [{"index": 0, "embedding": [/* 1024 个浮点 */]}, ...],
 "usage": {"prompt_tokens": 14, "total_tokens": 14}}
```

- data 按 index 排序；数量与批发不匹配 → 协议错误（不重试）。
- 契约口 `embed` 产出 `Vector1024`（1024 维冻结校验）。

## harness MCP（Streamable HTTP，POST 同一 URL，Accept: application/json, text/event-stream）

initialize：

```json
{"jsonrpc": "2.0", "id": 1, "method": "initialize",
 "params": {"protocolVersion": "2025-06-18", "capabilities": {},
            "clientInfo": {"name": "dpdk-assistant-java", "version": "0.1.0"}}}
```

- 响应 `Mcp-Session-Id` 头（如 `sess-1`）在后续两个 POST 回传。

initialized 通知（无 id；响应 202 空体）：

```json
{"jsonrpc": "2.0", "method": "notifications/initialized"}
```

tools/call：

```json
{"jsonrpc": "2.0", "id": 2, "method": "tools/call",
 "params": {"name": "grade_plugin", "arguments": {"protocol": "dns", "plugin_source": "<合成源码占位>"}}}
```

- 提交阶段开始后任何失败 → UNKNOWN，不重发。

结果规范化（四分支，输出统一为 JSON 字符串）：

| 分支 | 服务器结果形态 | 规范化输出 |
|---|---|---|
| structuredContent | `result.structuredContent` 为对象 | 原样返回该对象 |
| 文本 JSON（对象） | content 文本可解析为 JSON 对象 | 原样返回解析结果 |
| 文本 JSON（非对象） | 文本可解析为标量/数组 | `{"ok": true, "data": <值>}` |
| isError | `result.isError=true` | `{"ok": false, "error": "harness_tool_error", "detail": <文本截断 2000>}` |
| 非 JSON | 文本不可解析 | `{"ok": false, "error": "harness_bad_response", "detail": <文本截断 2000>}` |

传输/协议层失败（不抛穿）：

```json
{"ok": false, "error": "harness_unavailable", "detail": "评测服务不可达：<异常类型>"}
```
