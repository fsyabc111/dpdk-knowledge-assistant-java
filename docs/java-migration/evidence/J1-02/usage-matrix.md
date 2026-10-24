# J1-02 usage 矩阵

来源基线：`backend/app/core/usage.py`（tokens_from_usage / UsageRecorder 三条锁死语义）。
Java 对位实现 `ModelUsageLedger` / `ModelUsageEntry` / `ChatResult.Usage`（契约）。

## token 解析矩阵（`ModelUsageLedger.tokensFromUsage`）

| 上游形态 | prompt | completion | total | 备注 |
|---|---|---|---|---|
| `{"prompt_tokens":120,"completion_tokens":30,"total_tokens":150}` | 120 | 30 | 150 | OpenAI 键 |
| `{"input_tokens":7,"output_tokens":3}` | 7 | 3 | **10** | LangChain 键；只给分项时 total=prompt+completion 是等价推导 |
| `{"input_tokens":7}` | 7 | null | **null** | 半个数不可加，不推导 |
| `{"total_tokens":"12"}`（纯数字串） | null | null | 12 | 字符串可收 |
| `"12.0"` / `1.5`（非整/带小数点串） | — | — | null | 不猜 |
| `true` / `-3` | null | null | null | 布尔/负数一律未知 |
| null / 缺失 / 非 mapping | null | null | null | **未知不是 0** |

## usageSource 判定

| 条目 | usageSource | callCount | 计费 |
|---|---|---|---|
| 上游返回且任一 token 非空 | `upstream` | 实际尝试数（重试可见） | llm/embedding 计价（有价才出价） |
| 调了上游但没拿到 usage（含重试耗尽、取消、协议失败、流式尾帧未达） | `unknown` | 同上 | 只报量不出价；token 列全 null |
| 缓存命中 / 幂等重放 / 本地规则 | `cache_hit`/`replay`/`local` | **0** | 零调用留痕 |
| harness / MCP 操作 | `operation` | 调用数 | 显式不计费（`PRICED_KINDS={llm,embedding}`） |

锁定测试：`ModelUsageLedgerTest`（11 例）、`ChatClientHttpTest`/`ChatClientStreamHttpTest`/
`EmbeddingClientHttpTest` 各失败/取消/缺失用例。

## totals 汇总形态

键与 Python 一致：`entries`、`calls`、`prompt_tokens`、`completion_tokens`、`total_tokens`、
`total_tokens_entries`（有值条目分母）、`unknown_entries`（只数调了上游没账的）、
`zero_call_entries`（已知零调用）、`stages`（排序去重）。token 求和只统计计费 kind。

## 计费边界样例（交付要求）

- `ModelUsageEntry.costMicros(1500, 500, price)`：0.0008/0.002 CNY 每 1k token → 2200 micros
  （BigDecimal + HALF_UP，不用二进制浮点表示钱）。
- 未配价 → null（只报用量不编造费用）；harness/MCP kind → 一律 null。
- 取消边界：`ChatClientHttpTest.cancelledWaitRecordsUnknownAndReleasesGatePermit`——
  请求已发出、账记 unknown、并发门许可在背景收尾后归还、服务器完整处理证明未伪称远端取消。
