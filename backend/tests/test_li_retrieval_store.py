"""LiNodeRetrievalStore 四路召回 SQL 的默认集单测（mock get_conn，无真实 PG）。

对应 roadmap B1 验证 1：新增 store 的 SQL 用 mock/fixture 覆盖——版本过滤
JOIN、LIKE 通配符转义、limit 上限、HNSW ef_search 前置语句都在此锁定。
PG 真实行为另由 test_rag_versioned_postgres.py（postgres mark）覆盖；
FTS regconfig 参数化与错配降级由 test_li_fts_config.py 覆盖。
"""
from __future__ import annotations

import contextlib

import pytest

import app.storage.li_retrieval_store as li_store
from app.config import get_settings
from app.storage.li_retrieval_store import LiNodeRetrievalStore


class _FakeResult:
    def __init__(self, rows):
        self._rows = rows

    def fetchall(self):
        return self._rows

    def fetchone(self):
        return self._rows[0] if self._rows else None


class _FakeConn:
    def __init__(self) -> None:
        self.rows: list = []
        self.calls: list = []  # (sql, params)

    def execute(self, sql, params=()):
        self.calls.append((sql, tuple(params)))
        return _FakeResult(self.rows)


@contextlib.contextmanager
def _ctx(conn):
    yield conn


@pytest.fixture
def fake_db(monkeypatch):
    conn = _FakeConn()
    monkeypatch.setattr("app.storage.li_retrieval_store.get_conn", lambda: _ctx(conn))
    # 活体 regconfig 探查是进程级缓存（B2）；预置 None（表不存在=不核对），
    # 让本文件用例聚焦 SQL 形状，探查/错配行为在 test_li_fts_config.py 覆盖。
    monkeypatch.setitem(li_store._LIVE_REGCONFIG_CACHE, '"data_li_knowledge_v1"', None)
    return conn


def _store(release_id: str = "rel-1") -> LiNodeRetrievalStore:
    return LiNodeRetrievalStore('"data_li_knowledge_v1"', release_id)


def test_fts_sql_shape_with_release(fake_db):
    fake_db.rows = [{"node_id": "n1"}]
    assert _store().fetch_fts_candidate_ids('"DNS" OR "qname"', 10) == ["n1"]
    sql, params = fake_db.calls[0]
    assert "JOIN rag_release_nodes" in sql
    assert "websearch_to_tsquery(CAST(%s AS regconfig), %s)" in sql and "ts_rank" in sql
    assert "LIMIT %s" in sql
    # release_id 在最前；regconfig 与 FTS 查询串供 WHERE 与 ts_rank 两处复用。
    assert params == ("rel-1", "simple", '"DNS" OR "qname"', "simple", '"DNS" OR "qname"', 10)


def test_fts_empty_query_shortcircuits(fake_db):
    assert _store().fetch_fts_candidate_ids("", 10) == []
    assert fake_db.calls == []


def test_no_release_has_no_join(fake_db):
    _store(release_id="").fetch_fts_candidate_ids('"DNS"', 5)
    sql, params = fake_db.calls[0]
    assert "JOIN rag_release_nodes" not in sql and "WHERE TRUE" in sql
    assert params == ("simple", '"DNS"', "simple", '"DNS"', 5)


def test_path_fragments_are_escaped_and_capped(fake_db):
    fragments = ["en%gine_x.c", "a\\b"] + [f"f{i}" for i in range(25)]
    _store().fetch_path_candidate_ids(fragments, 1000)
    sql, params = fake_db.calls[0]
    assert "ILIKE %s" in sql
    # %/_/\ 全部转义；片段截断到 20；limit 封顶 512。
    assert params[1] == "%en\\%gine\\_x.c%"
    assert params[2] == "%a\\\\b%"
    assert len(params) == 1 + 20 + 1
    assert params[-1] == 512


def test_path_empty_fragments_shortcircuit(fake_db):
    assert _store().fetch_path_candidate_ids([], 10) == []
    assert fake_db.calls == []


def test_semantic_empty_embedding_shortcircuits(fake_db):
    assert _store().fetch_semantic_candidate_ids([], 10) == []
    assert fake_db.calls == []


def test_semantic_vector_literal_and_default_no_prelude(fake_db, monkeypatch):
    monkeypatch.setenv("LLAMA_INDEX_HNSW_ENABLED", "false")
    get_settings.cache_clear()
    _store().fetch_semantic_candidate_ids([1, 0.5], 40)
    assert len(fake_db.calls) == 1  # 开关关闭时无前导 set_config
    sql, params = fake_db.calls[0]
    assert "c.embedding <=> %s::vector" in sql
    assert params == ("rel-1", "[1.0,0.5]", 40)


def test_semantic_hnsw_enabled_sets_ef_search_first(fake_db, monkeypatch):
    monkeypatch.setenv("LLAMA_INDEX_HNSW_ENABLED", "true")
    monkeypatch.setenv("LLAMA_INDEX_HNSW_EF_SEARCH", "55")
    get_settings.cache_clear()
    _store().fetch_semantic_candidate_ids([1.0], 40)
    assert len(fake_db.calls) == 2
    prelude_sql, prelude_params = fake_db.calls[0]
    assert "set_config('hnsw.ef_search'" in prelude_sql
    assert prelude_params == ("55",)
    assert "<=> %s::vector" in fake_db.calls[1][0]


def test_fallback_never_fills_candidates(fake_db):
    assert _store().fetch_fallback_candidate_ids() == []
    assert fake_db.calls == []


def test_fetch_rows_by_ids_version_filter_and_columns(fake_db):
    fake_db.rows = [{"id": "n1", "content": "正文", "source_path": "dns.c",
                     "section": "parse", "title": "dns.c",
                     "metadata_json": "{}", "embedding_json": "[]"}]
    rows = _store().fetch_rows_by_ids(["n1", "n2"])
    sql, params = fake_db.calls[0]
    assert "r.release_id=%s AND" in sql
    assert "node_id AS id" in sql and "metadata_->>'source_path'" in sql
    assert "embedding::text" in sql
    assert params == ("rel-1", "n1", "n2")
    assert rows[0]["content"] == "正文"


def test_fetch_rows_by_ids_empty_shortcircuits(fake_db):
    assert _store().fetch_rows_by_ids([]) == []
    assert fake_db.calls == []


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
