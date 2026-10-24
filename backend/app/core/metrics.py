"""Prometheus metrics for the assistant application.

HTTP-level metrics come from prometheus-fastapi-instrumentator (mounted in
main.py). This module owns the domain metrics: LLM call outcomes and latency,
retrieval latency, degraded answers and rate-limit hits.

``/metrics`` is intentionally unauthenticated at the app layer: in the
production topology nginx only reverse-proxies ``/api/``, so the endpoint is
unreachable from outside. Do not expose it through the reverse proxy.
"""
from __future__ import annotations

from prometheus_client import Counter, Histogram

LLM_REQUESTS = Counter(
    "assistant_llm_requests_total",
    "LLM answer-generation calls by engine and final result.",
    ["engine", "result"],
)
LLM_REQUEST_DURATION = Histogram(
    "assistant_llm_request_duration_seconds",
    "End-to-end LLM answer-generation latency including retries.",
    ["engine"],
)
CHAT_RETRIEVAL_DURATION = Histogram(
    "assistant_chat_retrieval_duration_seconds",
    "Chat retrieval latency reported by the request trace.",
)
# 检索链路单阶段耗时（rewrite/embedding），同样来自请求 trace 的 stages_ms
CHAT_STAGE_DURATION = Histogram(
    "assistant_chat_stage_duration_seconds",
    "Per-stage retrieval latency (rewrite/embedding) reported by the request trace.",
    ["stage"],
)
CHAT_FALLBACKS = Counter(
    "assistant_chat_fallbacks_total",
    "Chat requests that ended in a degraded fallback answer.",
)
RATE_LIMIT_HITS = Counter(
    "assistant_rate_limit_hits_total",
    "Rejected chat requests by limiter kind (per_minute / daily_quota).",
    ["kind"],
)


RAG_OUTCOMES = Counter("assistant_rag_outcomes_total", "RAG 校验后结果", ["status", "reason"])
RAG_DURATION = Histogram("assistant_rag_duration_seconds", "RAG 完整答案耗时", buckets=(1, 5, 10, 20, 30, 60, 120, 240))
RAG_VALIDATION_DURATION = Histogram(
    "assistant_rag_validation_duration_seconds", "RAG 证据校验耗时（按结论分类）", ["result"],
    buckets=(1, 5, 10, 20, 30),
)

# ---- M4a E6 agent 可观测性：工具级调用/耗时/结果体积 + 单请求累计 tokens ----
AGENT_TOOL_CALLS = Counter(
    "assistant_agent_tool_calls_total", "Agent 工具调用次数（按工具与成败）", ["tool", "ok"],
)
AGENT_TOOL_DURATION = Histogram(
    "assistant_agent_tool_duration_seconds", "Agent 工具调用耗时", ["tool"],
    buckets=(0.05, 0.25, 1, 5, 15, 30, 60, 120, 240),
)
AGENT_TOOL_RESULT_BYTES = Histogram(
    "assistant_agent_tool_result_bytes", "Agent 工具结果 JSON 体积", ["tool"],
    buckets=(256, 1024, 4096, 16384, 65536, 262144),
)
AGENT_REQUEST_TOKENS = Histogram(
    "assistant_agent_request_tokens",
    "Agent 单请求累计 tokens（planner+finalize 的 usage_metadata 合计）",
    buckets=(1000, 4000, 16000, 32000, 48000, 64000, 96000),
)

# ---- P1 全链路用量与成本 ----
# 标签只含阶段/种类/模型等系统侧标识，**不含**提问、源码、会话或用户内容：
# 「凭据、源码和完整 prompt 不进入指标标签或账单」是硬约束。
USAGE_TOKENS = Counter(
    "assistant_upstream_usage_tokens_total",
    "上游 token 用量（按阶段/种类/来源；只统计上游确实返回值的那部分）",
    ["stage", "kind", "source"],
)
USAGE_COST = Counter(
    "assistant_upstream_usage_cost_micros_total",
    "上游成本（整数微单位，按币种分列；未定价的模型不计入）",
    ["stage", "kind", "model", "currency"],
)
USAGE_UNKNOWN = Counter(
    "assistant_upstream_usage_unknown_total",
    "调了上游但没拿到用量的条目数——这个计数器不为 0 就说明账面不完整",
    ["stage", "kind"],
)


def record_agent_tool_call(tool: str, ok: bool, duration_seconds: float, result_bytes: int) -> None:
    AGENT_TOOL_CALLS.labels(tool=tool or "unknown", ok=str(bool(ok)).lower()).inc()
    AGENT_TOOL_DURATION.labels(tool=tool or "unknown").observe(max(duration_seconds, 0.0))
    AGENT_TOOL_RESULT_BYTES.labels(tool=tool or "unknown").observe(max(result_bytes, 0))


def record_agent_request_tokens(tokens_used: int) -> None:
    if tokens_used > 0:
        AGENT_REQUEST_TOKENS.observe(tokens_used)


def record_llm_call(engine: str, result: str, duration_seconds: float) -> None:
    LLM_REQUESTS.labels(engine=engine, result=result).inc()
    LLM_REQUEST_DURATION.labels(engine=engine).observe(duration_seconds)


def record_rate_limit_hit(kind: str) -> None:
    RATE_LIMIT_HITS.labels(kind=kind).inc()


def record_rag_validation(result: str, duration_seconds: float) -> None:
    RAG_VALIDATION_DURATION.labels(result=result or "unknown").observe(max(duration_seconds, 0.0))


def record_usage_entries(rows) -> None:
    """把账本行折进指标（按阶段/种类聚合）。

    只吃**已算好成本**的行：成本口径归 usage_service（Decimal + 微单位），
    指标层不重算，避免两处口径漂移。
    """
    from .usage import SOURCE_UNKNOWN

    for row in rows:
        stage = row.get("stage") or "unknown"
        kind = row.get("kind") or "unknown"
        source = row.get("usage_source") or "unknown"
        total = row.get("total_tokens")
        if isinstance(total, int) and total > 0:
            USAGE_TOKENS.labels(stage=stage, kind=kind, source=source).inc(total)
        if source == SOURCE_UNKNOWN:
            USAGE_UNKNOWN.labels(stage=stage, kind=kind).inc()
        cost = row.get("cost_micros")
        currency = row.get("currency")
        if isinstance(cost, int) and isinstance(currency, str) and currency:
            USAGE_COST.labels(
                stage=stage, kind=kind, model=row.get("model") or "unknown", currency=currency,
            ).inc(cost)


def record_chat_trace(trace) -> None:
    """Fold the per-request trace into domain metrics (single choke point)."""
    stages = trace.as_dict().get("stages_ms") or {}
    retrieval_ms = stages.get("retrieval_total_ms")
    if retrieval_ms is None:
        # The streaming path records granular stages instead of a total.
        retrieval_ms = stages.get("candidate_fetch_total_ms")
    if isinstance(retrieval_ms, (int, float)):
        CHAT_RETRIEVAL_DURATION.observe(retrieval_ms / 1000.0)
    for stage_key, stage_label in (("rewrite_ms", "rewrite"), ("embedding_ms", "embedding")):
        stage_ms = stages.get(stage_key)
        if isinstance(stage_ms, (int, float)):
            CHAT_STAGE_DURATION.labels(stage=stage_label).observe(stage_ms / 1000.0)
    answer_ms = stages.get("answer_generation_ms")
    engine = trace.metadata.get("rag_engine", "unknown")
    fallback = trace.as_dict().get("fallback_reason") or ""
    if isinstance(answer_ms, (int, float)):
        record_llm_call(engine, "fallback" if fallback else "success", answer_ms / 1000.0)
    if fallback:
        CHAT_FALLBACKS.inc()
