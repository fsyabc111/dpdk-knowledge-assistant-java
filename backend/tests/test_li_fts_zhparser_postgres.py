"""zhparser FTS 迁移（alembic 0010）的 PG 端到端验证。

与默认集 test_li_fts_config.py（fake-op 锁 SQL 形状）互补：真实建 simple 表
ingest 中文 chunk → 0010 换列 → 中文单词命中 → downgrade 换回 simple。

需要 RAG_TEST_DATABASE_URL 专用测试库（会建/删 data_rag_test_zhparser 表），
且数据库镜像含 zhparser（deploy/postgres 自定义镜像）且有建扩展权限；任一
不满足即 skip，不污染 stock PG 环境。
"""
from __future__ import annotations

import importlib.util
import os
from pathlib import Path

import pytest

pytestmark = pytest.mark.postgres

_MIGRATION_PATH = Path(__file__).resolve().parents[1] / "alembic" / "versions" / "0010_li_fts_zhparser.py"


class _LiveOp:
    """把迁移的 op.execute 落到真实连接（get_conn 退出即提交）。"""

    def __init__(self, get_conn):
        self._get_conn = get_conn

    def execute(self, statement, *args, **kwargs):
        with self._get_conn() as conn:
            conn.execute(str(statement))


def _load_migration(op):
    spec = importlib.util.spec_from_file_location("m0010_li_fts_zhparser_pg", _MIGRATION_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.op = op
    return module


async def test_zhparser_column_swap_and_chinese_fts(monkeypatch):
    url = os.environ.get("RAG_TEST_DATABASE_URL")
    if not url:
        pytest.skip("需要显式 RAG_TEST_DATABASE_URL 专用测试库")
    monkeypatch.setenv("DATABASE_BACKEND", "postgres")
    monkeypatch.setenv("DATABASE_URL", url)
    monkeypatch.setenv("LLAMA_INDEX_TABLE", "rag_test_zhparser")
    monkeypatch.setenv("LLAMA_INDEX_FTS_CONFIG", "simple")
    from app.config import get_settings
    from app.db import get_conn, init_db
    get_settings.cache_clear()
    init_db()
    with get_conn() as conn:
        available = conn.execute(
            "SELECT 1 AS ok FROM pg_available_extensions WHERE name='zhparser'"
        ).fetchone()
    if not available:
        pytest.skip("当前 PostgreSQL 无 zhparser 扩展（需 deploy/postgres 自定义镜像）")

    import app.storage.li_retrieval_store as li_store
    from app.services import rag_index_service as index
    from app.services.llama_index_service import _new_pgvector_store, _quoted_data_table

    # 重复运行时遗留的表可能已是 zh：先删后建，保证起点是 simple。
    with get_conn() as conn:
        conn.execute("DROP TABLE IF EXISTS data_rag_test_zhparser CASCADE")
    monkeypatch.setattr(li_store, "_LIVE_REGCONFIG_CACHE", {})
    monkeypatch.setattr(li_store, "_MISMATCH_WARNED", set())

    settings = get_settings()
    store = _new_pgvector_store(settings, True)
    store._initialize()
    table = _quoted_data_table(settings)
    content = [{"content": "流量检测平台负责协议识别与插件调度", "metadata": {
        "source_key": "repo/flow.c", "source_path": "flow.c", "ordinal": 0,
        "section": "capture", "title": "流量"}}]
    monkeypatch.setattr(index, "snapshot_sources",
                        lambda settings: [dict(n, metadata=dict(n["metadata"])) for n in content])

    class Embedding:
        async def embed(self, texts, *, usage=None, stage=""):
            return [[1.0] + [0.0] * 1023 for _ in texts]

    monkeypatch.setattr(index, "build_embedding_model", lambda settings: Embedding())
    try:
        release = await index.ingest_release(settings, table)
        retrieval = li_store.LiNodeRetrievalStore(table, release["release_id"])

        # simple 列：中文整段是单个 token，单词「流量」查不到（对照组）。
        assert li_store.live_fts_regconfig(table) == "simple"
        assert retrieval.fetch_fts_candidate_ids("流量", 10) == []

        # 0010 升级（zh 开关）：建扩展/配置 + 换列。
        monkeypatch.setenv("LLAMA_INDEX_FTS_CONFIG", "zh")
        get_settings.cache_clear()
        migration = _load_migration(_LiveOp(get_conn))
        migration.upgrade()

        li_store._LIVE_REGCONFIG_CACHE.clear()
        assert li_store.live_fts_regconfig(table) == "zh"
        with get_conn() as conn:
            tokens = conn.execute(
                "SELECT to_tsvector('zh', '流量检测平台') AS v"
            ).fetchone()["v"]
        assert len(tokens.split()) >= 3 and "'流量'" in tokens  # 中文被切成多 token
        assert retrieval.fetch_fts_candidate_ids("流量", 10)  # 中文单词命中

        # downgrade 换回 simple，列定义复原。
        migration.downgrade()
        li_store._LIVE_REGCONFIG_CACHE.clear()
        assert li_store.live_fts_regconfig(table) == "simple"
    finally:
        store._engine.dispose()
        if store._async_engine:
            await store._async_engine.dispose()


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
