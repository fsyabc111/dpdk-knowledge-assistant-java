"""专用空 PG 库验证用量账本；不连接开发库或生产库。

双后端支持是本批的硬约束（SQLite 尚未退出），因此同一份
``usage_schema.sql`` 必须在两种后端上都能建表、写、查。SQLite 侧见
``tests/test_usage_records.py``（默认集），这里只覆盖 PG。

用法::

    RAG_TEST_DATABASE_URL=postgresql:///assistant_rag_test \
      .venv/bin/python -m pytest tests/test_usage_records_postgres.py -q

未设置该变量时整体跳过（默认集不带 -m postgres，本来也不会选中）。
"""
import os

import psycopg
import pytest

pytestmark = pytest.mark.postgres


def _service(monkeypatch):
    from app.config import get_settings
    from app.db import init_db
    from app.services.usage_service import UsageService

    url = os.environ.get("RAG_TEST_DATABASE_URL")
    if not url:
        pytest.skip("需要显式 RAG_TEST_DATABASE_URL 专用测试库")
    monkeypatch.setenv("DATABASE_BACKEND", "postgres")
    monkeypatch.setenv("DATABASE_URL", url)
    monkeypatch.setenv(
        "LLM_PRICES_JSON",
        '{"qwen3.8-flash": {"input_per_1k": "0.0008", "output_per_1k": "0.002",'
        ' "currency": "CNY"}, "text-embedding-v4": {"input_per_1k": "0.0005",'
        ' "currency": "CNY"}}',
    )
    monkeypatch.setenv("LLM_PRICE_VERSION", "official-2026-09")
    get_settings.cache_clear()
    init_db()
    return UsageService()


def _rows():
    from app.config import get_settings
    from app.core.usage import (
        KIND_EMBEDDING,
        KIND_HARNESS,
        KIND_LLM,
        SOURCE_CACHE_HIT,
        SOURCE_UNKNOWN,
        UsageRecorder,
        entries_to_rows,
        parse_prices,
    )

    recorder = UsageRecorder()
    recorder.record_llm("planner", "qwen3.8-flash",
                        {"prompt_tokens": 1000, "completion_tokens": 500, "total_tokens": 1500},
                        kind=KIND_LLM)
    recorder.record_llm("query_embed", "text-embedding-v4",
                        {"prompt_tokens": 200, "total_tokens": 200}, kind=KIND_EMBEDDING)
    recorder.record_llm("judge", "qwen3.8-flash", None)
    recorder.record_skipped("query_embed_cached", SOURCE_CACHE_HIT, model="text-embedding-v4")
    recorder.record_operation(KIND_HARNESS, "grade", duration_ms=1200)
    return entries_to_rows(
        recorder.entries,
        prices=parse_prices(get_settings().llm_prices_json),
        price_version="official-2026-09",
        request_id="trace_pg_1",
        subject="pg-student",
        session_id="pg-session",
        created_at="2026-09-17T10:00:00+00:00",
    ), SOURCE_UNKNOWN


def test_postgres_round_trips_usage_rows(monkeypatch):
    service = _service(monkeypatch)
    rows, _ = _rows()
    from app.db import get_conn

    # 专属库可能留有上次运行的痕迹：先清掉本次 subject 的行，保持用例可重跑。
    with get_conn() as conn:
        conn.execute("DELETE FROM upstream_usage_records WHERE subject = %s", ("pg-student",))

    assert service.record_rows(rows) == len(rows)

    summary = service.query(subject="pg-student")
    assert summary["truncated"] is False
    assert summary["totals"]["total_tokens"] == 1700
    assert summary["totals"]["cost_micros_by_currency"] == {"CNY": 1900}
    assert summary["totals"]["entries"] == 4
    assert summary["operations"]["calls"] == 1
    assert summary["operations"]["duration_ms"] == 1200

    with get_conn() as conn:
        conn.execute("DELETE FROM upstream_usage_records WHERE subject = %s", ("pg-student",))


def test_postgres_keeps_unknown_tokens_null(monkeypatch):
    """PG 侧同样必须把未知存成 NULL —— 写成 0 会让聚合把「没测到」算成「没花费」。"""
    service = _service(monkeypatch)
    rows, source_unknown = _rows()
    from app.db import get_conn

    with get_conn() as conn:
        conn.execute("DELETE FROM upstream_usage_records WHERE subject = %s", ("pg-student",))
    service.record_rows(rows)

    with get_conn() as conn:
        row = conn.execute(
            "SELECT total_tokens, cost_micros, usage_source FROM upstream_usage_records "
            "WHERE request_id = %s AND stage = %s",
            ("trace_pg_1", "judge"),
        ).fetchone()
    assert row["total_tokens"] is None
    assert row["cost_micros"] is None
    assert row["usage_source"] == source_unknown

    with get_conn() as conn:
        conn.execute("DELETE FROM upstream_usage_records WHERE subject = %s", ("pg-student",))


def test_postgres_subjects_filter_stays_in_scope(monkeypatch):
    """树隔离用的 IN 过滤必须真的过滤：范围算错就是泄露别人的花费。"""
    service = _service(monkeypatch)
    rows, _ = _rows()
    from app.db import get_conn

    with get_conn() as conn:
        conn.execute(
            "DELETE FROM upstream_usage_records WHERE subject IN (%s, %s)",
            ("pg-student", "pg-other"),
        )
    service.record_rows(rows)

    assert service.query(subjects=["pg-student"])["totals"]["total_tokens"] == 1700
    assert service.query(subjects=["pg-student", "pg-other"])["totals"]["total_tokens"] == 1700
    assert service.query(subjects=["pg-other"])["totals"]["entries"] == 0
    # 空集合 = 看不到任何主体，**不是**看到全部。
    assert service.query(subjects=[])["totals"]["entries"] == 0
    # subject 与 subjects 同时给出时以单主体为准（接口层不会这么传，DB 层不放大范围）。
    assert service.query(subject="pg-other", subjects=["pg-student"])["totals"]["entries"] == 0

    with get_conn() as conn:
        conn.execute("DELETE FROM upstream_usage_records WHERE subject = %s", ("pg-student",))


def test_postgres_primary_key_rejects_duplicate_ids(monkeypatch):
    """同 id 重复写入必须报错：账本改写历史比丢一笔更糟。"""
    service = _service(monkeypatch)
    rows, _ = _rows()
    from app.db import get_conn

    with get_conn() as conn:
        conn.execute("DELETE FROM upstream_usage_records WHERE subject = %s", ("pg-student",))
    service.record_rows(rows)

    with pytest.raises(psycopg.IntegrityError):
        service.record_rows(rows)

    with get_conn() as conn:
        conn.execute("DELETE FROM upstream_usage_records WHERE subject = %s", ("pg-student",))
