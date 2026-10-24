# J1-02 重试次数与延迟矩阵

来源基线：`backend/app/core/llm_client.py`（chat/embed 重试）、`backend/app/agent/mcp_client.py`
（建连重试）。Java 实现逐条对位，测试锁定。

## chat（非流式 `OpenAiCompatibleChatClient.chat`）

| 场景 | 尝试次数 | 延迟 | 账目 | 锁定测试 |
|---|---|---|---|---|
| 成功 | 1 | — | 上游 usage 一笔，callCount=1 | `ChatClientHttpTest.chatRecordsUpstreamUsageWithoutChangingReturn` |
| 可重试失败后成功（5xx） | ≤ maxRetries+1 | 指数退避 `base*2^n`（n 从 0 起，无抖动） | 一笔（成功侧），callCount=实际尝试数 | `retriableStatusRetriesThenSuccessKeepsCallCountVisible` |
| 429 / 5xx 耗尽 | maxRetries+1 | 同上 | 一笔 unknown，callCount=maxRetries+1，异常透传 | `exhaustedRetriesRecordsUnknownAndRethrows` |
| 4xx（401/400/404） | 1 | 不重试 | 一笔 unknown，callCount=1 | `nonRetriableStatusFailsOnceAndRecordsUnknown` |
| 网络/读超时 | 同 5xx 规则（`ResourceAccessException` 对位 httpx Timeout/NetworkError） | 同上 | 同上 | `readTimeoutIsRetriableThenFailsWithUnknown` |
| 协议错误（空响应/缺 content） | 1（不可重试） | — | 一笔 unknown | `emptyResponseBodyIsProtocolErrorWithoutRetry`、`missingContentKeyIsProtocolError` |
| 等待中取消（线程中断） | 立即停止等待 | — | 一笔 unknown，callCount=已发出次数；已发送请求不伪称远端已取消 | `cancelledWaitRecordsUnknownAndReleasesGatePermit` |
| 配置缺失 | 0 | — | 无账目（源：ModelConfigError 在记账前抛出） | `unconfiguredEndpointFailsClosed` |

## stream（`OpenAiCompatibleChatClient.streamChat`）

| 场景 | 尝试次数 | 账目 | 锁定测试 |
|---|---|---|---|
| 正常（含 usage 尾帧） | 1 | 上游 usage，callCount=1，尾帧不产生空 delta | `ChatClientStreamHttpTest.streamParsesDeltasAndUsageTailFrame` |
| 400 且正文指向 stream_options/include_usage | 2（去掉 stream_options 重发一次） | 一笔 unknown，callCount=2（拿不到用量但绝不让请求失败） | `streamRetriesWithoutStreamOptionsOn400` |
| 其他 400（如 context length exceeded） | 1 | 一笔 unknown，异常透传 | `streamDoesNotRetryOther400Causes` |
| 5xx | 1（流式无传输层重试，与源一致） | 一笔 unknown，onError 投递 | `streamErrorStatusSurfacesOnError` |
| 坏帧（非法 JSON data 帧） | 跳过该帧，流继续 | 按到达的尾帧记账 | `streamIgnoresMalformedFrames` |
| 消费方提前取消 | 1 | 停止读取 → 尾帧未消费 → 一笔 unknown，callCount=1 | `earlyCancelStopsReadingAndRecordsUnknown` |

## embedding（`OpenAiCompatibleEmbeddingClient.embedTexts`，按批）

| 场景 | 尝试次数/批 | 延迟 | 账目/批 | 锁定测试 |
|---|---|---|---|---|
| 成功 | 1 | — | 上游 usage 一笔 | `EmbeddingClientHttpTest.embedRecordsUsagePerRequest` |
| 多批 | 每批独立 | — | 每批各一笔（前批成功不受后批失败影响） | `embedSplitsBatchesAndRecordsEach` |
| Retry-After 数值秒 | ≤ maxRetries+1 | **Retry-After 优先**；≤0/缺失/非法时 `base*2^attempt + uniform(0, min(0.5, delay*0.25))` 抖动 | 成功侧一笔 | `embedHonorsRetryAfterHeaderOverExponentialBackoff`（base=30s 仍秒级重试证明优先级） |
| 失败耗尽/不可重试 | 同 chat 规则 | 同上 | 该批 unknown，callCount=尝试数 | `embedFailureAfterRetriesRecordsUnknownPerBatch` |
| 数量不匹配 | 1（协议错误） | — | 该批 unknown | `embedCountMismatchIsProtocolError` |
| 空输入 | 0 | — | 无账目、无请求 | `embedEmptyInputSendsNothingAndRecordsNothing` |

## harness MCP（`StreamableHttpHarnessClient.call`）

| 阶段/场景 | 尝试次数 | 延迟 | SubmissionPhase | 锁定测试 |
|---|---|---|---|---|
| 正常（JSON 或 SSE 响应） | 1 次传输（initialize + initialized 通知 + tools/call 三次 POST） | — | SUBMITTED | `structuredContentTakesPrecedence`、`sseResponsesAreHandledLikeJsonBodies` |
| 建连/initialize 失败后恢复 | ≤ 3 次传输 | 退避 `0.5s * 2^attempt`（测试注入 0.01s，默认常量同源） | SUBMITTED | `handshakeFailuresRetryThenSucceed`（initPosts=3，toolCalls=1） |
| 建连持续失败 | 恰 3 次传输 | 同上 | NOT_SUBMITTED（error=harness_unavailable） | `persistentConnectFailureGivesUpAfterExactlyTwoRetries`（initPosts=3，toolCalls=0） |
| tools/call 提交后失败（500/读超时） | **1 次提交，绝不重发** | — | UNKNOWN（已提交，结果未知） | `submittedCallFailureNeverRetries`、`submittedCallTimeoutAfterSendIsUnknownNotResubmitted`（均断言 grade_plugin 只出现一次） |
| 参数无法序列化 | 0 次 HTTP | — | NOT_SUBMITTED（harness_bad_request） | `malformedArgumentsFailBeforeAnyHttpRequest`（服务器零命中） |
| deadline 到期（未提交前） | 0 次 HTTP | — | NOT_SUBMITTED（harness_deadline_exceeded） | `expiredDeadlineIsNotSubmitted` |

## 并发门 / 同 key 合并（基础设施）

| 语义 | 锁定测试 |
|---|---|
| 上游并发上限（Semaphore，全部通过客户端的每次尝试持有） | `UpstreamGateTest.gateCapsConcurrentUpstreamAttempts`、`ChatClientHttpTest.upstreamConcurrencyCapAppliesToChatRequests` |
| 许可异常/中断必释放 | `UpstreamGateTest.permitIsReleasedOnFailure`、`interruptedWaitConsumesNoPermit` |
| 同 key 单飞合并、失败不缓存、TTL/LRU、调用方取消不打断共享装载 | `SingleFlightTtlCacheTest` 5 例 |
