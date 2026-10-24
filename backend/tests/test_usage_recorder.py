"""用量记录层（app/core/usage.py）的确定性单测——不联网、不碰数据库。

锁死三条语义，防止后来者「顺手」把它们改成更好看的数字：

1. 未知不是 0：上游没给 usage 时 token 列必须保持 None。
2. 零调用要留痕：缓存命中/幂等重放记 call_count=0，且不计费。
3. 未定价不出价：模型不在价格表里 → cost_micros 为 None，只报用量。

Usage:
    cd ai_chat_assistant/backend
    .venv/bin/python -m pytest tests/test_usage_recorder.py -q
"""

from __future__ import annotations

import inspect
import sys
from dataclasses import FrozenInstanceError
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.core.usage import (
    KIND_EMBEDDING,
    KIND_HARNESS,
    KIND_LLM,
    KIND_MCP,
    SOURCE_CACHE_HIT,
    SOURCE_OPERATION,
    SOURCE_REPLAY,
    SOURCE_UNKNOWN,
    SOURCE_UPSTREAM,
    UsageEntry,
    UsageRecorder,
    cost_micros,
    entries_to_rows,
    parse_prices,
    tokens_from_usage,
)

PRICES_JSON = (
    '{"qwen3.8-flash": {"input_per_1k": "0.0008", "output_per_1k": "0.002", "currency": "CNY"},'
    ' "text-embedding-v4": {"input_per_1k": "0.0005", "currency": "CNY"}}'
)


# ---------------------------------------------------------------------------
# 上游 usage 解析
# ---------------------------------------------------------------------------


def test_missing_usage_is_unknown_not_zero() -> None:
    """上游没返回 usage → 三项全 None。**绝不能是 0**（0 在账本里代表「真没花」）。"""
    for payload in (None, {}, {"other": 1}, "not-a-mapping", 0):
        tokens = tokens_from_usage(payload)
        assert tokens == {"prompt_tokens": None, "completion_tokens": None, "total_tokens": None}


def test_partial_usage_does_not_invent_total() -> None:
    """只给了一个分项时，total 保持 None——半个数是不可加的。"""
    tokens = tokens_from_usage({"prompt_tokens": 100})
    assert tokens["prompt_tokens"] == 100
    assert tokens["completion_tokens"] is None
    assert tokens["total_tokens"] is None


def test_total_derived_only_when_both_parts_present() -> None:
    tokens = tokens_from_usage({"prompt_tokens": 120, "completion_tokens": 30})
    assert tokens == {"prompt_tokens": 120, "completion_tokens": 30, "total_tokens": 150}


def test_accepts_langchain_usage_metadata_keys() -> None:
    tokens = tokens_from_usage({"input_tokens": 7, "output_tokens": 3, "total_tokens": 10})
    assert tokens == {"prompt_tokens": 7, "completion_tokens": 3, "total_tokens": 10}


def test_garbage_token_values_are_treated_as_unknown() -> None:
    """负值、布尔、小数、随机字符串都不是可用的 token 数，一律判未知。"""
    for value in (-1, True, False, 1.5, "abc", [], {}):
        tokens = tokens_from_usage({"prompt_tokens": value, "completion_tokens": value})
        assert tokens["prompt_tokens"] is None, value
        assert tokens["completion_tokens"] is None, value
        assert tokens["total_tokens"] is None, value


def test_numeric_string_tokens_are_accepted() -> None:
    """有些网关把 token 数序列化成字符串——这是有值的用量，不是未知。"""
    tokens = tokens_from_usage({"prompt_tokens": "12", "completion_tokens": "8"})
    assert tokens == {"prompt_tokens": 12, "completion_tokens": 8, "total_tokens": 20}


# ---------------------------------------------------------------------------
# 价格解析
# ---------------------------------------------------------------------------


def test_parse_prices_empty_is_not_an_error() -> None:
    for raw in (None, "", "   "):
        assert parse_prices(raw) == {}


def test_parse_prices_reads_decimal_exactly() -> None:
    prices = parse_prices(PRICES_JSON)
    assert set(prices) == {"qwen3.8-flash", "text-embedding-v4"}
    assert str(prices["qwen3.8-flash"].input_per_1k) == "0.0008"
    assert prices["qwen3.8-flash"].currency == "CNY"
    # embedding 只有输入侧：缺 output_per_1k 按 0 计，而不是判为非法。
    assert prices["text-embedding-v4"].output_per_1k == 0


def test_parse_prices_skips_malformed_entries() -> None:
    """运行期宽容：一条写错不该让整表作废（启动期由 Settings 从严拦截）。"""
    prices = parse_prices(
        '{"ok": {"input_per_1k": "0.001", "currency": "CNY"},'
        ' "no-currency": {"input_per_1k": "0.001"},'
        ' "bad-price": {"input_per_1k": "-1", "currency": "CNY"},'
        ' "not-object": "0.001"}'
    )
    assert set(prices) == {"ok"}


def test_parse_prices_broken_json_returns_empty() -> None:
    assert parse_prices("{not json") == {}
    assert parse_prices("[1, 2]") == {}


# ---------------------------------------------------------------------------
# 成本
# ---------------------------------------------------------------------------


def _price(input_per_1k: str, output_per_1k: str = "0") -> object:
    return parse_prices(
        f'{{"m": {{"input_per_1k": "{input_per_1k}", "output_per_1k": "{output_per_1k}",'
        ' "currency": "CNY"}}'
    )["m"]


def test_cost_micros_is_exact_integer_arithmetic() -> None:
    price = _price("0.0008", "0.002")
    # 1000 输入 → 0.0008 元 → 800 微元；1000 输出 → 0.002 元 → 2000 微元。
    assert cost_micros(1000, 1000, price) == 2800
    assert cost_micros(1000, None, price) == 800
    assert cost_micros(None, 1000, price) == 2000


def test_cost_micros_rounds_half_up() -> None:
    price = _price("1")
    # 1 token × 1/1000 元 = 0.001 元 = 1000 微元；再取奇数使其落在 .5 上。
    assert cost_micros(1, None, price) == 1000
    assert cost_micros(3, None, _price("0.0005")) == 2  # 1.5 微元 → 2（HALF_UP）


def test_cost_micros_returns_none_without_price_or_tokens() -> None:
    """未定价 → None；两个 token 都未知 → None。绝不用 0 冒充「免费」。"""
    assert cost_micros(1000, 1000, None) is None
    assert cost_micros(None, None, _price("0.001")) is None


def test_cost_micros_is_zero_when_genuinely_zero_tokens() -> None:
    """真·0 token 与「未知」必须区分得开：前者是 0 微元，后者是 None。"""
    assert cost_micros(0, 0, _price("0.001")) == 0


# ---------------------------------------------------------------------------
# 累加器
# ---------------------------------------------------------------------------


def test_record_llm_with_upstream_usage() -> None:
    recorder = UsageRecorder()
    entry = recorder.record_llm("rewrite", "qwen3.8-flash", {"total_tokens": 42, "prompt_tokens": 30,
                                                            "completion_tokens": 12})
    assert entry is not None
    assert entry.usage_source == SOURCE_UPSTREAM
    assert (entry.prompt_tokens, entry.completion_tokens, entry.total_tokens) == (30, 12, 42)
    assert entry.call_count == 1
    assert entry.priced is True


def test_record_llm_without_usage_stays_unknown() -> None:
    recorder = UsageRecorder()
    entry = recorder.record_llm("judge", "qwen3.8-flash", None, calls=3)
    assert entry is not None
    assert entry.usage_source == SOURCE_UNKNOWN
    assert entry.total_tokens is None
    assert entry.prompt_tokens is None
    # 重试过 3 次这件事必须可见——上游实际消耗可能高于账面。
    assert entry.call_count == 3


def test_record_skipped_leaves_a_zero_call_trace() -> None:
    """缓存命中/幂等重放：零调用要留痕，否则「这次为什么没花钱」无从审计。"""
    recorder = UsageRecorder()
    for reason in (SOURCE_CACHE_HIT, SOURCE_REPLAY):
        entry = recorder.record_skipped("query_embed", reason, model="text-embedding-v4")
        assert entry is not None
        assert entry.usage_source == reason
        assert entry.call_count == 0
        assert entry.total_tokens is None
    assert [entry.call_count for entry in recorder.entries] == [0, 0]


def test_record_skipped_rejects_unknown_reason_shape() -> None:
    """来源必须是白名单里的值，不接受调用方随手编的字符串。"""
    recorder = UsageRecorder()
    entry = recorder.record_skipped("query_embed", "whatever")
    assert entry is not None
    assert entry.usage_source == SOURCE_UNKNOWN


def test_record_operation_is_not_priced() -> None:
    """harness/MCP 是外部算力：记次数与耗时，显式不计费（编造费率比不计量更糟）。"""
    recorder = UsageRecorder()
    harness = recorder.record_operation(KIND_HARNESS, "grade", duration_ms=2400, calls=1)
    mcp = recorder.record_operation(KIND_MCP, "harness_call", duration_ms=90, calls=2)
    assert harness is not None and mcp is not None
    assert harness.usage_source == SOURCE_OPERATION
    assert harness.priced is False
    assert mcp.priced is False
    assert harness.total_tokens is None


def test_recorder_is_per_instance() -> None:
    """累加器必须请求级隔离；共享状态会让并发请求互相串账。"""
    first, second = UsageRecorder(), UsageRecorder()
    first.record_llm("rewrite", "m", {"total_tokens": 5})
    assert len(first.entries) == 1
    assert second.entries == []


def test_entries_property_returns_a_copy() -> None:
    recorder = UsageRecorder()
    recorder.record_llm("rewrite", "m", {"total_tokens": 5})
    recorder.entries.clear()
    assert len(recorder.entries) == 1


# ---------------------------------------------------------------------------
# 汇总
# ---------------------------------------------------------------------------


def test_totals_separate_known_sum_from_unknown_count() -> None:
    recorder = UsageRecorder()
    recorder.record_llm("planner", "m", {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120})
    recorder.record_llm("finalize", "m", {"prompt_tokens": 50, "completion_tokens": 10, "total_tokens": 60})
    recorder.record_llm("judge", "m", None)          # 未知
    recorder.record_skipped("query_embed", SOURCE_CACHE_HIT)
    recorder.record_operation(KIND_HARNESS, "grade", duration_ms=10)

    totals = recorder.totals()
    assert totals["entries"] == 5
    assert totals["total_tokens"] == 180
    assert totals["total_tokens_entries"] == 2   # 分母：只有 2 条贡献了这个合计
    assert totals["unknown_entries"] == 1        # judge 那条
    assert totals["zero_call_entries"] == 1      # cache_hit 那条
    # 三次生成 + 一次 harness 评测；缓存命中贡献 0 次。
    assert totals["calls"] == 4
    assert totals["stages"] == ["finalize", "grade", "judge", "planner", "query_embed"]


def test_totals_with_no_usage_is_none_not_zero() -> None:
    recorder = UsageRecorder()
    recorder.record_llm("judge", "m", None)
    totals = recorder.totals()
    assert totals["total_tokens"] is None
    assert totals["prompt_tokens"] is None
    assert totals["completion_tokens"] is None


def test_totals_ignore_non_priced_operations() -> None:
    """harness 的次数不该混进 token 合计的分母里。"""
    recorder = UsageRecorder()
    recorder.record_operation(KIND_HARNESS, "grade", duration_ms=10, calls=4)
    totals = recorder.totals()
    assert totals["total_tokens"] is None
    assert totals["total_tokens_entries"] == 0
    assert totals["unknown_entries"] == 0


# ---------------------------------------------------------------------------
# 落库行
# ---------------------------------------------------------------------------


def _rows(recorder: UsageRecorder):
    return entries_to_rows(
        recorder.entries,
        prices=parse_prices(PRICES_JSON),
        price_version="official-2026-09",
        request_id="trace_abc123",
        subject="student-1",
        session_id="session-1",
        created_at="2026-09-17T00:00:00Z",
        id_factory=lambda: "use_fixed",
    )


def test_rows_stamp_cost_and_price_version() -> None:
    recorder = UsageRecorder()
    recorder.record_llm("planner", "qwen3.8-flash", {"prompt_tokens": 1000, "completion_tokens": 1000})
    row = _rows(recorder)[0]
    assert row["cost_micros"] == 2800
    assert row["currency"] == "CNY"
    assert row["price_version"] == "official-2026-09"
    assert row["usage_source"] == SOURCE_UPSTREAM
    assert row["kind"] == KIND_LLM
    assert row["request_id"] == "trace_abc123"


def test_rows_leave_cost_null_for_unpriced_model() -> None:
    """模型不在价格表里 → 只报用量，不出价（也不盖 price_version）。"""
    recorder = UsageRecorder()
    recorder.record_llm("planner", "some-unlisted-model", {"prompt_tokens": 1000, "completion_tokens": 10})
    row = _rows(recorder)[0]
    assert row["total_tokens"] == 1010
    assert row["cost_micros"] is None
    assert row["currency"] is None
    assert row["price_version"] is None


def test_rows_leave_token_columns_null_when_unknown() -> None:
    """未知必须落成 NULL。如果这里写成 0，聚合就会把「没测到」算成「没花费」。"""
    recorder = UsageRecorder()
    recorder.record_llm("judge", "qwen3.8-flash", None)
    row = _rows(recorder)[0]
    assert row["prompt_tokens"] is None
    assert row["completion_tokens"] is None
    assert row["total_tokens"] is None
    assert row["usage_source"] == SOURCE_UNKNOWN
    assert row["cost_micros"] is None


def test_rows_zero_call_entries_are_never_priced() -> None:
    """缓存命中计费一次就是重复收费——即便模型有价、token 恰好为空。"""
    recorder = UsageRecorder()
    recorder.record_skipped("query_embed", SOURCE_CACHE_HIT, model="text-embedding-v4")
    row = _rows(recorder)[0]
    assert row["call_count"] == 0
    assert row["cost_micros"] is None


def test_rows_never_priced_for_non_token_kinds() -> None:
    """harness/MCP 的 kind 即使携带了 model 也不得计费。"""
    recorder = UsageRecorder()
    recorder.record_operation(KIND_HARNESS, "grade", duration_ms=10)
    row = _rows(recorder)[0]
    assert row["cost_micros"] is None
    assert row["currency"] is None
    assert row["usage_source"] == SOURCE_OPERATION


def test_rows_carry_no_user_content_columns() -> None:
    """账本是计量数据：不得出现提问、源码、prompt 或凭据任何列。"""
    recorder = UsageRecorder()
    recorder.record_llm("planner", "qwen3.8-flash", {"total_tokens": 5})
    row = _rows(recorder)[0]
    assert set(row) == {
        "id", "request_id", "subject", "session_id", "kind", "stage", "model",
        "prompt_tokens", "completion_tokens", "total_tokens", "usage_source",
        "call_count", "duration_ms", "cost_micros", "currency", "price_version",
        "created_at",
    }


def test_row_ids_are_unique_by_default() -> None:
    recorder = UsageRecorder()
    recorder.record_llm("planner", "m", {"total_tokens": 5})
    recorder.record_llm("finalize", "m", {"total_tokens": 5})
    rows = entries_to_rows(recorder.entries, created_at="2026-09-17T00:00:00Z")
    first, second = rows[0]["id"], rows[1]["id"]
    assert first != second
    assert first.startswith("use_")


def test_embedding_entries_use_embedding_kind_and_price() -> None:
    recorder = UsageRecorder()
    recorder.record_llm("ingest_embed", "text-embedding-v4", {"prompt_tokens": 2000},
                        kind=KIND_EMBEDDING)
    row = _rows(recorder)[0]
    assert row["kind"] == KIND_EMBEDDING
    assert row["cost_micros"] == 1000  # 2000 token × 0.0005/1k = 0.001 元


# ---------------------------------------------------------------------------
# 契约：所有模型实现都必须接受用量 sink
# ---------------------------------------------------------------------------


def test_all_provider_implementations_accept_usage_sink() -> None:
    """防静默漂移：新增一个 provider 却忘了 sink，用量就会不声不响地消失。"""
    from app.rag import providers

    expected = {
        providers.DirectOpenAIChatModel: ("generate", "stream"),
        providers.DirectOpenAIEmbeddingModel: ("embed",),
        providers.LlamaIndexChatModel: ("generate", "stream"),
        providers.LlamaIndexEmbeddingModel: ("embed",),
    }
    for cls, methods in expected.items():
        for method_name in methods:
            params = inspect.signature(getattr(cls, method_name)).parameters
            assert "usage" in params, f"{cls.__name__}.{method_name} 缺少 usage 参数"
            assert "stage" in params, f"{cls.__name__}.{method_name} 缺少 stage 参数"
            assert params["usage"].kind is inspect.Parameter.KEYWORD_ONLY, (
                f"{cls.__name__}.{method_name} 的 usage 必须是仅关键字参数，"
                "否则会改变既有调用方的位置参数语义"
            )
            assert params["usage"].default is None


def test_raw_client_methods_accept_usage_sink() -> None:
    from app.core.llm_client import OpenAICompatibleClient

    for method_name in ("chat", "stream_chat", "embed"):
        params = inspect.signature(getattr(OpenAICompatibleClient, method_name)).parameters
        assert "usage" in params and "stage" in params, method_name
        assert params["usage"].kind is inspect.Parameter.KEYWORD_ONLY
        assert params["usage"].default is None


def test_protocol_declares_usage_sink() -> None:
    """interfaces 里的 Protocol 是各实现的事实来源，不能只有实现侧对齐。"""
    from app.rag import interfaces

    for protocol, methods in (
        (interfaces.ChatModel, ("generate", "stream")),
        (interfaces.EmbeddingModel, ("embed",)),
    ):
        for method_name in methods:
            params = inspect.signature(getattr(protocol, method_name)).parameters
            assert "usage" in params, f"{protocol.__name__}.{method_name}"


def test_usage_entry_is_hashable_and_frozen() -> None:
    """条目是值对象：冻结以防累加后被人就地改写。"""
    entry = UsageEntry(kind=KIND_LLM, stage="planner", usage_source=SOURCE_UPSTREAM)
    with pytest.raises(FrozenInstanceError):
        entry.stage = "mutated"  # type: ignore[misc]
    assert hash(entry) is not None
