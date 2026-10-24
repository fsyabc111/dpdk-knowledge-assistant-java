"""LLAMA_INDEX_FTS_CONFIG（roadmap B2 zhparser）默认集单测。

无真实数据库：Settings 校验直接构造；0010 迁移的 op.execute 用 fake 录制；
PGVectorStore.from_params 与 get_conn 用 monkeypatch 拦截。模式复刻
test_li_hnsw.py；PG 真实行为由 test_li_fts_zhparser_postgres.py（postgres
mark）覆盖。
"""
from __future__ import annotations

import asyncio
import contextlib
import importlib.util
from pathlib import Path

import pytest

import app.storage.li_retrieval_store as li_store
from app.config import Settings, get_settings

_MIGRATION_PATH = Path(__file__).resolve().parents[1] / "alembic" / "versions" / "0010_li_fts_zhparser.py"


def _env(monkeypatch, **overrides):
    env = {"DATABASE_BACKEND": "postgres", "DATABASE_URL": "postgresql://u:p@127.0.0.1:9/test"}
    env.update(overrides)
    for key, value in env.items():
        monkeypatch.setenv(key, value)
    get_settings.cache_clear()


# ---------------------------------------------------------------------------
# Settings 校验
# ---------------------------------------------------------------------------


def test_fts_config_defaults_to_simple(monkeypatch):
    monkeypatch.delenv("LLAMA_INDEX_FTS_CONFIG", raising=False)
    assert Settings(_env_file=None).llama_index_fts_config == "simple"


def test_fts_config_accepts_zh(monkeypatch):
    monkeypatch.setenv("LLAMA_INDEX_FTS_CONFIG", "zh")
    assert Settings(_env_file=None).llama_index_fts_config == "zh"


@pytest.mark.parametrize("bad", ["english", "zhparser", 'zh";drop'])
def test_fts_config_rejects_unknown_values(monkeypatch, bad):
    # 白名单同时是注入防护：该值会被 llama-index 拼进建表 DDL。
    monkeypatch.setenv("LLAMA_INDEX_FTS_CONFIG", bad)
    with pytest.raises(ValueError, match="LLAMA_INDEX_FTS_CONFIG"):
        Settings(_env_file=None)


def test_new_pgvector_store_passes_fts_config(monkeypatch):
    from llama_index.vector_stores.postgres import PGVectorStore

    captured = {}

    @classmethod
    def fake_from_params(cls, **kwargs):
        captured.update(kwargs)
        return object()

    monkeypatch.setattr(PGVectorStore, "from_params", fake_from_params)
    from app.services.llama_index_service import _new_pgvector_store

    _env(monkeypatch, LLAMA_INDEX_FTS_CONFIG="zh")
    _new_pgvector_store(get_settings(), perform_setup=False)
    assert captured["text_search_config"] == "zh"

    captured.clear()
    _env(monkeypatch, LLAMA_INDEX_FTS_CONFIG="simple")
    _new_pgvector_store(get_settings(), perform_setup=False)
    assert captured["text_search_config"] == "simple"


# ---------------------------------------------------------------------------
# alembic 0010 门控与 SQL
# ---------------------------------------------------------------------------


class _FakeOp:
    def __init__(self) -> None:
        self.sql: list[str] = []

    def execute(self, statement, *args, **kwargs):
        self.sql.append(str(statement))


def _load_migration(monkeypatch):
    spec = importlib.util.spec_from_file_location("m0010_li_fts_zhparser", _MIGRATION_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    fake_op = _FakeOp()
    monkeypatch.setattr(module, "op", fake_op)
    return module, fake_op


def test_upgrade_noop_when_fts_simple(monkeypatch):
    _env(monkeypatch, LLAMA_INDEX_FTS_CONFIG="simple")
    module, fake_op = _load_migration(monkeypatch)
    module.upgrade()
    assert fake_op.sql == []


def test_upgrade_noop_on_sqlite_backend(monkeypatch):
    _env(monkeypatch, DATABASE_BACKEND="sqlite",
         DATABASE_URL="sqlite:///test.db", LLAMA_INDEX_FTS_CONFIG="zh")
    module, fake_op = _load_migration(monkeypatch)
    module.upgrade()
    assert fake_op.sql == []


def test_upgrade_zhparser_full_sequence(monkeypatch):
    _env(monkeypatch, LLAMA_INDEX_FTS_CONFIG="zh")
    module, fake_op = _load_migration(monkeypatch)
    module.upgrade()
    assert len(fake_op.sql) == 5
    assert fake_op.sql[0] == "CREATE EXTENSION IF NOT EXISTS zhparser"
    assert "CREATE TEXT SEARCH CONFIGURATION zh (PARSER = zhparser)" in fake_op.sql[1]
    assert fake_op.sql[2].startswith('ALTER TEXT SEARCH CONFIGURATION zh DROP MAPPING IF EXISTS FOR "a","b","c"')
    assert fake_op.sql[3].startswith('ALTER TEXT SEARCH CONFIGURATION zh ADD MAPPING FOR "a","b","c"')
    assert fake_op.sql[3].endswith("WITH simple")
    swap = fake_op.sql[4]
    assert "ALTER TABLE data_li_knowledge_v1 DROP COLUMN IF EXISTS text_search_tsv" in swap
    assert "to_tsvector('zh'::regconfig, text)" in swap
    assert "CREATE INDEX IF NOT EXISTS li_knowledge_v1_idx ON data_li_knowledge_v1 USING gin (text_search_tsv)" in swap


def test_upgrade_rejects_unsafe_table_name(monkeypatch):
    _env(monkeypatch, LLAMA_INDEX_FTS_CONFIG="zh", LLAMA_INDEX_TABLE='bad";drop')
    module, _ = _load_migration(monkeypatch)
    with pytest.raises(ValueError):
        module.upgrade()


def test_downgrade_restores_simple_regardless_of_switch(monkeypatch):
    _env(monkeypatch)  # 开关关闭也能回退（与 0009 回退语义一致）
    module, fake_op = _load_migration(monkeypatch)
    module.downgrade()
    assert len(fake_op.sql) == 1
    assert "to_tsvector('simple'::regconfig, text)" in fake_op.sql[0]
    assert "CREATE INDEX IF NOT EXISTS li_knowledge_v1_idx" in fake_op.sql[0]


# ---------------------------------------------------------------------------
# FTS 召回腿：regconfig 参数化与错配降级
# ---------------------------------------------------------------------------


class _FakeConn:
    """按 SQL 内容区分 regconfig 探查（fetchone）与召回查询（fetchall）。"""

    def __init__(self, expr=None) -> None:
        self.expr = expr
        self.queries: list[tuple[str, tuple]] = []

    def execute(self, sql, params=()):
        self.queries.append((str(sql), tuple(params)))
        return self

    def fetchone(self):
        return {"expr": self.expr} if self.expr is not None else None

    def fetchall(self):
        return []


@contextlib.contextmanager
def _ctx(conn):
    yield conn


def _store(monkeypatch, expr, config="zh"):
    _env(monkeypatch, LLAMA_INDEX_FTS_CONFIG=config)
    conn = _FakeConn(expr=expr)
    monkeypatch.setattr(li_store, "get_conn", lambda: _ctx(conn))
    monkeypatch.setattr(li_store, "_LIVE_REGCONFIG_CACHE", {})
    monkeypatch.setattr(li_store, "_MISMATCH_WARNED", set())
    return li_store.LiNodeRetrievalStore('"data_li_knowledge_v1"', "rel-1"), conn


def test_fts_uses_configured_regconfig_as_bind_param(monkeypatch):
    store, conn = _store(monkeypatch, expr="to_tsvector('zh'::regconfig, text)")
    assert store.fetch_fts_candidate_ids('"流量"', 10) == []
    assert len(conn.queries) == 2  # 探查 + 召回
    probe_sql, _ = conn.queries[0]
    assert "pg_attrdef" in probe_sql and "to_regclass" in probe_sql
    match_sql, params = conn.queries[1]
    assert "websearch_to_tsquery(CAST(%s AS regconfig), %s)" in match_sql
    assert params == ("rel-1", "zh", '"流量"', "zh", '"流量"', 10)


def test_fts_leg_skipped_with_single_warning_on_config_mismatch(monkeypatch, caplog):
    store, conn = _store(monkeypatch, expr="to_tsvector('simple'::regconfig, text)", config="zh")
    with caplog.at_level("WARNING", logger="assistant"):
        assert store.fetch_fts_candidate_ids('"流量"', 10) == []
        assert store.fetch_fts_candidate_ids('"流量"', 10) == []
    # 只有探查查询，match 查询被跳过；告警每进程只记一次。
    assert len(conn.queries) == 1
    assert "pg_attrdef" in conn.queries[0][0]
    messages = [record.getMessage() for record in caplog.records]
    assert sum("rag_fts_config_mismatch" in message for message in messages) == 1


def test_fts_proceeds_when_table_not_created(monkeypatch):
    # 表不存在（尚未 ingest）时探查返回 None：不核对、按配置直接查询。
    store, conn = _store(monkeypatch, expr=None)
    store.fetch_fts_candidate_ids('"流量"', 10)
    assert any("websearch_to_tsquery" in sql for sql, _ in conn.queries)


def test_live_regconfig_normalizes_schema_qualification(monkeypatch):
    store, conn = _store(monkeypatch, expr="to_tsvector('pg_catalog.simple'::regconfig, text)", config="simple")
    store.fetch_fts_candidate_ids('"x"', 10)
    assert any("websearch_to_tsquery" in sql for sql, _ in conn.queries)


def test_live_regconfig_unparseable_treated_as_unknown(monkeypatch):
    # 非预期表达式（如手工建列）提取不出 regconfig → 不核对、直接查询。
    store, conn = _store(monkeypatch, expr="to_tsvector(text)", config="zh")
    store.fetch_fts_candidate_ids('"x"', 10)
    assert any("websearch_to_tsquery" in sql for sql, _ in conn.queries)


# ---------------------------------------------------------------------------
# ingest 侧错配硬失败
# ---------------------------------------------------------------------------


class _FakeStore:
    _engine = None
    _async_engine = None

    def _initialize(self):
        return None


def _ingest_fakes(monkeypatch, live: str | None):
    import app.db as db_module
    import app.services.llama_index_service as svc
    import app.services.rag_index_service as index_svc

    monkeypatch.setattr(db_module, "init_db", lambda: None)
    monkeypatch.setattr(svc, "_new_pgvector_store", lambda settings, perform_setup: _FakeStore())
    monkeypatch.setattr(li_store, "live_fts_regconfig", lambda table: live)

    async def fake_ingest_release(settings, table):
        return {"release_id": "r1", "table": settings.llama_index_table}

    monkeypatch.setattr(index_svc, "ingest_release", fake_ingest_release)
    return svc


def test_ingest_fails_closed_on_fts_config_mismatch(monkeypatch):
    _env(monkeypatch, LLAMA_INDEX_FTS_CONFIG="zh")
    svc = _ingest_fakes(monkeypatch, live="simple")
    with pytest.raises(ValueError, match="alembic upgrade head"):
        asyncio.run(svc.LlamaIndexIngestionService().ingest())


def test_ingest_proceeds_when_fts_config_matches(monkeypatch):
    _env(monkeypatch, LLAMA_INDEX_FTS_CONFIG="zh")
    svc = _ingest_fakes(monkeypatch, live="zh")
    assert asyncio.run(svc.LlamaIndexIngestionService().ingest())["release_id"] == "r1"


def test_ingest_proceeds_on_fresh_table(monkeypatch):
    _env(monkeypatch, LLAMA_INDEX_FTS_CONFIG="zh")
    svc = _ingest_fakes(monkeypatch, live=None)
    assert asyncio.run(svc.LlamaIndexIngestionService().ingest())["release_id"] == "r1"


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
