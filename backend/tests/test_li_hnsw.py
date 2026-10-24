"""HNSW 开关的默认集单测：0009 迁移门控 + PGVectorStore hnsw_kwargs 透传。

无真实数据库：迁移的 op.execute 用 fake 录制，PGVectorStore.from_params
用 monkeypatch 拦截（perform_setup=False 本就不连接）。
"""
from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

from app.config import get_settings

_MIGRATION_PATH = Path(__file__).resolve().parents[1] / "alembic" / "versions" / "0009_li_hnsw_index.py"


class _FakeOp:
    def __init__(self) -> None:
        self.sql: list[str] = []

    def execute(self, statement, *args, **kwargs):
        self.sql.append(str(statement))


def _load_migration(monkeypatch) -> tuple:
    spec = importlib.util.spec_from_file_location("m0009_li_hnsw_index", _MIGRATION_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    fake_op = _FakeOp()
    monkeypatch.setattr(module, "op", fake_op)
    return module, fake_op


def _env(monkeypatch, **overrides):
    env = {"DATABASE_BACKEND": "postgres", "DATABASE_URL": "postgresql://u:p@127.0.0.1:9/test",
           "LLAMA_INDEX_HNSW_ENABLED": "false"}
    env.update(overrides)
    for key, value in env.items():
        monkeypatch.setenv(key, value)
    get_settings.cache_clear()


def test_upgrade_noop_when_switch_off(monkeypatch):
    _env(monkeypatch)
    module, fake_op = _load_migration(monkeypatch)
    module.upgrade()
    assert fake_op.sql == []


def test_upgrade_creates_hnsw_index_when_enabled(monkeypatch):
    _env(monkeypatch, LLAMA_INDEX_HNSW_ENABLED="true", LLAMA_INDEX_HNSW_M="24",
         LLAMA_INDEX_HNSW_EF_CONSTRUCTION="128")
    module, fake_op = _load_migration(monkeypatch)
    module.upgrade()
    assert len(fake_op.sql) == 1
    sql = fake_op.sql[0]
    assert "CREATE INDEX IF NOT EXISTS data_li_knowledge_v1_embedding_idx" in sql
    assert "ON data_li_knowledge_v1 USING hnsw (embedding vector_cosine_ops)" in sql
    assert "m = 24" in sql and "ef_construction = 128" in sql


def test_upgrade_noop_on_sqlite_backend(monkeypatch):
    _env(monkeypatch, DATABASE_BACKEND="sqlite",
         DATABASE_URL="sqlite:///test.db", LLAMA_INDEX_HNSW_ENABLED="true")
    module, fake_op = _load_migration(monkeypatch)
    module.upgrade()
    assert fake_op.sql == []


def test_upgrade_rejects_unsafe_table_name(monkeypatch):
    _env(monkeypatch, LLAMA_INDEX_HNSW_ENABLED="true", LLAMA_INDEX_TABLE='bad";drop')
    module, _ = _load_migration(monkeypatch)
    with pytest.raises(ValueError):
        module.upgrade()


def test_downgrade_drops_index_regardless_of_switch(monkeypatch):
    _env(monkeypatch)  # 开关关闭也能回退
    module, fake_op = _load_migration(monkeypatch)
    module.downgrade()
    assert fake_op.sql == ["DROP INDEX IF EXISTS data_li_knowledge_v1_embedding_idx"]


def test_new_pgvector_store_passes_hnsw_kwargs_only_when_enabled(monkeypatch):
    from llama_index.vector_stores.postgres import PGVectorStore

    captured = {}

    @classmethod
    def fake_from_params(cls, **kwargs):
        captured.update(kwargs)
        return object()

    monkeypatch.setattr(PGVectorStore, "from_params", fake_from_params)
    from app.services.llama_index_service import _new_pgvector_store

    _env(monkeypatch, LLAMA_INDEX_HNSW_ENABLED="true", LLAMA_INDEX_HNSW_EF_SEARCH="66")
    _new_pgvector_store(get_settings(), perform_setup=False)
    assert captured["hnsw_kwargs"] == {
        "hnsw_m": 16, "hnsw_ef_construction": 64, "hnsw_ef_search": 66,
        "hnsw_dist_method": "vector_cosine_ops",
    }

    captured.clear()
    _env(monkeypatch)
    _new_pgvector_store(get_settings(), perform_setup=False)
    assert captured["hnsw_kwargs"] is None


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
