"""用量账本的落库、聚合与价格配置测试（SQLite 默认集，不联网）。

聚合部分是纯函数，直接喂手写的行；落库部分走真实的 SQLite provider，
因此同一份 DDL 在两个后端上能否执行，至少在这里被验证了一半
（PG 侧见 tests/test_usage_records_postgres.py）。

Usage:
    cd ai_chat_assistant/backend
    .venv/bin/python -m pytest tests/test_usage_records.py -q
"""

from __future__ import annotations

import os
import sqlite3
import sys
import tempfile
from pathlib import Path
from typing import Any, Dict, List

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.config import Settings
from app.core.usage import (
    KIND_EMBEDDING,
    KIND_HARNESS,
    KIND_LLM,
    KIND_MCP,
    SOURCE_CACHE_HIT,
    SOURCE_OPERATION,
    SOURCE_UNKNOWN,
    SOURCE_UPSTREAM,
    UsageRecorder,
    entries_to_rows,
    parse_prices,
)
from app.services.usage_service import MAX_QUERY_ROWS, UsageService, aggregate_usage

PRICES_JSON = (
    '{"qwen3.8-flash": {"input_per_1k": "0.0008", "output_per_1k": "0.002", "currency": "CNY"},'
    ' "text-embedding-v4": {"input_per_1k": "0.0005", "currency": "CNY"}}'
)


def _row(**overrides: Any) -> Dict[str, Any]:
    row: Dict[str, Any] = {
        "id": "use_x", "request_id": "trace_1", "subject": "s1", "session_id": "sess1",
        "kind": KIND_LLM, "stage": "planner", "model": "qwen3.8-flash",
        "prompt_tokens": None, "completion_tokens": None, "total_tokens": None,
        "usage_source": SOURCE_UNKNOWN, "call_count": 1, "duration_ms": None,
        "cost_micros": None, "currency": None, "price_version": None,
        "created_at": "2026-09-17T10:00:00+00:00",
    }
    row.update(overrides)
    return row


# ---------------------------------------------------------------------------
# 纯聚合
# ---------------------------------------------------------------------------


def test_aggregate_empty_rows_reports_nothing_not_zero() -> None:
    summary = aggregate_usage([])
    assert summary["totals"]["total_tokens"] is None
    assert summary["totals"]["entries"] == 0
    assert summary["totals"]["cost_micros_by_currency"] == {}
    assert summary["operations"]["calls"] == 0
    assert summary["by_stage"] == []
    assert summary["unpriced_models"] == []
    assert summary["cost_currency_mixed"] is False


def test_aggregate_sums_only_known_tokens_and_reports_denominator() -> None:
    summary = aggregate_usage([
        _row(prompt_tokens=100, completion_tokens=20, total_tokens=120,
             usage_source=SOURCE_UPSTREAM, cost_micros=136, currency="CNY"),
        _row(prompt_tokens=50, completion_tokens=10, total_tokens=60,
             usage_source=SOURCE_UPSTREAM, cost_micros=60, currency="CNY"),
        _row(usage_source=SOURCE_UNKNOWN),          # 未知，不进合计
    ])
    totals = summary["totals"]
    assert totals["total_tokens"] == 180
    assert totals["known_entries"] == 2      # 分母：只有 2 条贡献了 180
    assert totals["unknown_entries"] == 1
    assert totals["entries"] == 3
    assert summary["unknown_entries"] == 1


def test_aggregate_does_not_treat_unknown_as_zero() -> None:
    """全未知时合计必须是 None。写成 0 就等于宣称「这次请求没花钱」。"""
    summary = aggregate_usage([_row(usage_source=SOURCE_UNKNOWN)])
    assert summary["totals"]["total_tokens"] is None
    assert summary["totals"]["prompt_tokens"] is None
    assert summary["totals"]["known_entries"] == 0
    assert summary["totals"]["unknown_entries"] == 1


def test_aggregate_never_sums_across_currencies() -> None:
    """跨币种相加是最糟的「看起来对了」——必须分列。"""
    summary = aggregate_usage([
        _row(total_tokens=10, usage_source=SOURCE_UPSTREAM, cost_micros=100, currency="CNY"),
        _row(total_tokens=10, usage_source=SOURCE_UPSTREAM, cost_micros=200, currency="USD"),
    ])
    assert summary["totals"]["cost_micros_by_currency"] == {"CNY": 100, "USD": 200}
    assert summary["cost_currency_mixed"] is True


def test_aggregate_keeps_operations_separate_from_priced_tokens() -> None:
    """harness/MCP 是非 token 用量：分列且不带成本（用户确认的口径）。"""
    summary = aggregate_usage([
        _row(kind=KIND_LLM, total_tokens=100, usage_source=SOURCE_UPSTREAM,
             cost_micros=80, currency="CNY"),
        _row(kind=KIND_HARNESS, stage="grade", model=None, usage_source=SOURCE_OPERATION,
             duration_ms=2400, call_count=1),
        _row(kind=KIND_MCP, stage="harness_call", model=None, usage_source=SOURCE_OPERATION,
             duration_ms=90, call_count=2),
    ])
    assert summary["totals"]["total_tokens"] == 100
    assert summary["totals"]["calls"] == 1
    assert summary["operations"]["calls"] == 3
    assert summary["operations"]["duration_ms"] == 2490
    assert "cost_micros" not in summary["operations"]
    assert summary["totals"]["cost_micros_by_currency"] == {"CNY": 80}


def test_aggregate_lists_unpriced_models_with_usage() -> None:
    summary = aggregate_usage([
        _row(model="unknown-model", total_tokens=500, usage_source=SOURCE_UPSTREAM),
        _row(model="priced-model", total_tokens=500, usage_source=SOURCE_UPSTREAM,
             cost_micros=100, currency="CNY"),
    ])
    assert summary["unpriced_models"] == ["unknown-model"]


def test_cache_hit_is_not_reported_as_unpriced_or_unknown() -> None:
    """缓存命中没有 token 也没有成本——它不构成价格缺口，也不是「账没算清」。"""
    summary = aggregate_usage([
        _row(model="text-embedding-v4", kind=KIND_EMBEDDING, stage="query_embed",
             usage_source=SOURCE_CACHE_HIT, call_count=0),
    ])
    assert summary["unpriced_models"] == []
    assert summary["totals"]["unknown_entries"] == 0
    assert summary["totals"]["calls"] == 0


def test_aggregate_groups_by_stage_model_and_day() -> None:
    summary = aggregate_usage([
        _row(stage="rewrite", total_tokens=10, usage_source=SOURCE_UPSTREAM,
             created_at="2026-09-16T10:00:00+00:00"),
        _row(stage="rewrite", total_tokens=20, usage_source=SOURCE_UPSTREAM,
             created_at="2026-09-17T10:00:00+00:00"),
        _row(stage="planner", model="other", total_tokens=5, usage_source=SOURCE_UPSTREAM,
             created_at="2026-09-17T11:00:00+00:00"),
    ])
    by_stage = {bucket["key"]: bucket for bucket in summary["by_stage"]}
    assert by_stage["rewrite"]["total_tokens"] == 30
    assert by_stage["rewrite"]["entries"] == 2
    assert by_stage["planner"]["total_tokens"] == 5

    by_model = {bucket["key"]: bucket for bucket in summary["by_model"]}
    assert by_model["qwen3.8-flash"]["total_tokens"] == 30
    assert by_model["other"]["total_tokens"] == 5

    by_day = {bucket["key"]: bucket for bucket in summary["by_day"]}
    assert by_day["2026-09-16"]["total_tokens"] == 10
    assert by_day["2026-09-17"]["total_tokens"] == 25


def test_aggregate_buckets_rows_without_stage_under_unknown() -> None:
    """stage 为空不该让整组行从分列里消失。"""
    summary = aggregate_usage([_row(stage="", total_tokens=7, usage_source=SOURCE_UPSTREAM)])
    assert [bucket["key"] for bucket in summary["by_stage"]] == ["unknown"]


def test_aggregate_handles_string_token_values() -> None:
    """后端可能把 INTEGER 列读成字符串；聚合口径不应因此漏算。"""
    summary = aggregate_usage([_row(total_tokens="15", usage_source=SOURCE_UPSTREAM)])
    assert summary["totals"]["total_tokens"] == 15
    assert summary["totals"]["known_entries"] == 1


# ---------------------------------------------------------------------------
# 价格配置（Settings）
# ---------------------------------------------------------------------------


def test_settings_accept_empty_prices() -> None:
    """未配置价格是合法状态（只报用量），不是配置错误。"""
    settings = Settings(llm_prices_json="")
    assert settings.llm_prices == {}


def test_settings_parse_prices_and_expose_them() -> None:
    settings = Settings(llm_prices_json=PRICES_JSON, llm_price_version="official-2026-09")
    assert set(settings.llm_prices) == {"qwen3.8-flash", "text-embedding-v4"}
    assert settings.llm_price_version == "official-2026-09"


def test_settings_reject_unparseable_prices() -> None:
    with pytest.raises(Exception, match="not valid JSON"):
        Settings(llm_prices_json="{not json")


def test_settings_reject_price_entry_without_currency() -> None:
    """启动期从严：写错的价格要当场报错，而不是上线后才发现成本列全空。"""
    with pytest.raises(Exception, match="currency"):
        Settings(llm_prices_json='{"m": {"input_per_1k": "0.001"}}')


def test_settings_reject_negative_price() -> None:
    with pytest.raises(Exception, match="input_per_1k"):
        Settings(llm_prices_json='{"m": {"input_per_1k": "-1", "currency": "CNY"}}')


def test_settings_defaults_keep_recording_on() -> None:
    settings = Settings()
    assert settings.llm_usage_recording_enabled is True
    assert settings.llm_stream_usage_enabled is True


# ---------------------------------------------------------------------------
# SQLite 落库（双后端共用同一份 DDL）
# ---------------------------------------------------------------------------


@pytest.fixture()
def sqlite_settings() -> Any:
    """独立临时库；不碰开发库，也不依赖 conftest 的既有 fixture。"""
    handle, path = tempfile.mkstemp(suffix=".db")
    os.close(handle)
    settings = Settings(
        database_backend="sqlite",
        database_url=f"sqlite:///{path}",
        llm_prices_json=PRICES_JSON,
        llm_price_version="official-2026-09",
    )
    yield settings
    Path(path).unlink(missing_ok=True)


@pytest.fixture()
def usage_service(sqlite_settings: Any) -> Any:
    """用一个真实 SQLite provider 建表，并在结束后还原进程级 provider。"""
    from app import db
    from app.storage.sqlite import SQLiteProvider

    previous = db._provider
    provider = SQLiteProvider(sqlite_settings)
    provider.init()
    db._provider = provider
    try:
        yield UsageService()
    finally:
        db._provider = previous


def _entries() -> List[Any]:
    recorder = UsageRecorder()
    recorder.record_llm("planner", "qwen3.8-flash",
                        {"prompt_tokens": 1000, "completion_tokens": 500, "total_tokens": 1500},
                        kind=KIND_LLM)
    recorder.record_llm("query_embed", "text-embedding-v4",
                        {"prompt_tokens": 200, "total_tokens": 200},
                        kind=KIND_EMBEDDING)
    recorder.record_llm("judge", "qwen3.8-flash", None)
    recorder.record_skipped("query_embed2", SOURCE_CACHE_HIT, model="text-embedding-v4")
    recorder.record_operation(KIND_HARNESS, "grade", duration_ms=1200)
    return entries_to_rows(
        recorder.entries,
        prices=parse_prices(PRICES_JSON),
        price_version="official-2026-09",
        request_id="trace_1",
        subject="student-1",
        session_id="sess-1",
        created_at="2026-09-17T10:00:00+00:00",
    )


def test_sqlite_accepts_schema_and_round_trips_rows(usage_service: Any) -> None:
    rows = _entries()
    assert usage_service.record_rows(rows) == len(rows)
    assert usage_service.count_rows() == len(rows)

    summary = usage_service.query()
    assert summary["truncated"] is False
    # planner: 1000×0.0008/1k + 500×0.002/1k = 0.0008 + 0.001 = 0.0018 元 = 1800 微元
    # embedding: 200×0.0005/1k = 0.0001 元 = 100 微元
    assert summary["totals"]["cost_micros_by_currency"] == {"CNY": 1900}
    assert summary["totals"]["total_tokens"] == 1700
    # totals 只含计费 kind（llm + embedding = 4 条）；harness 是非 token 操作，单列。
    assert summary["totals"]["entries"] == 4
    assert summary["totals"]["unknown_entries"] == 1     # judge
    assert summary["operations"]["calls"] == 1
    assert summary["operations"]["duration_ms"] == 1200


def test_sqlite_unknown_tokens_survive_the_round_trip_as_null(usage_service: Any) -> None:
    """未知必须在库里是 NULL，读回来还是 None。任何一环写成 0 都会污染聚合。"""
    usage_service.record_rows(_entries())
    from app.db import get_conn

    with get_conn() as conn:
        rows = conn.execute(
            "SELECT stage, total_tokens, cost_micros FROM upstream_usage_records "
            "WHERE stage = 'judge'"
        ).fetchall()
    assert len(rows) == 1
    assert rows[0]["total_tokens"] is None
    assert rows[0]["cost_micros"] is None


def test_sqlite_filters_by_stage_and_subject(usage_service: Any) -> None:
    usage_service.record_rows(_entries())
    assert usage_service.query(stage="judge")["totals"]["entries"] == 1
    assert usage_service.query(stage="grade")["operations"]["calls"] == 1
    # 5 行都属于 student-1：4 条计费 + 1 条 harness 操作。
    scoped = usage_service.query(subject="student-1")
    assert scoped["totals"]["entries"] == 4
    assert scoped["operations"]["calls"] == 1
    assert usage_service.query(subject="nobody")["totals"]["entries"] == 0


def test_sqlite_filters_by_since(usage_service: Any) -> None:
    usage_service.record_rows(_entries())
    assert usage_service.query(since="2026-09-17T00:00:00+00:00")["totals"]["entries"] == 4
    assert usage_service.query(since="2026-09-18T00:00:00+00:00")["totals"]["entries"] == 0
    assert usage_service.query(since="2026-09-18T00:00:00+00:00")["totals"]["total_tokens"] is None


def test_record_rows_with_empty_input_is_a_noop(usage_service: Any) -> None:
    assert usage_service.record_rows([]) == 0
    assert usage_service.count_rows() == 0


def test_record_rows_is_idempotent_on_primary_key_conflict(usage_service: Any) -> None:
    """同 id 重复写入必须报错而不是静默改写历史账。"""
    row = _row(id="use_dup", total_tokens=5, usage_source=SOURCE_UPSTREAM)
    usage_service.record_rows([row])
    with pytest.raises(sqlite3.IntegrityError):
        usage_service.record_rows([row])


def test_query_marks_truncation_at_the_row_limit(usage_service: Any, monkeypatch: Any) -> None:
    """触顶时必须如实标记，不能把被截断的合计当成完整账目。"""
    monkeypatch.setattr(
        "app.services.usage_service.MAX_QUERY_ROWS", 2,
    )
    rows = [
        _row(id=f"use_{index}", total_tokens=10, usage_source=SOURCE_UPSTREAM,
             created_at=f"2026-09-17T10:00:0{index}+00:00")
        for index in range(3)
    ]
    usage_service.record_rows(rows)
    summary = usage_service.query()
    assert summary["truncated"] is True
    assert summary["row_limit"] == 2
    assert summary["totals"]["entries"] == 2
    assert MAX_QUERY_ROWS == 20000      # 默认值不受 monkeypatch 影响


def test_schema_file_matches_alembic_revision() -> None:
    """运行期 DDL 与 alembic 迁移必须描述同一张表。

    两边都是 ``IF NOT EXISTS``，跑偏了不会报错——只会让新建库和升级库长得不一样。
    """
    import re

    schema = (
        Path(ROOT) / "app" / "storage" / "usage_schema.sql"
    ).read_text(encoding="utf-8")
    revision = (
        Path(ROOT) / "alembic" / "versions" / "0011_usage_records.py"
    ).read_text(encoding="utf-8")

    def columns(text: str) -> List[str]:
        match = re.search(r"upstream_usage_records \((.*?)\n\);", text, re.S)
        assert match, "未找到 upstream_usage_records 建表语句"
        names = []
        for line in match.group(1).splitlines():
            line = line.split("--")[0].strip().rstrip(",")
            if line:
                names.append(line.split()[0])
        return names

    assert columns(schema) == columns(revision)
