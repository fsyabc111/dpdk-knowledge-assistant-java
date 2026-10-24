"""专用空 PG 库验证发布事务与四路召回；不连接生产数据库。"""
import os

import pytest

pytestmark = pytest.mark.postgres


async def test_atomic_publish_reuse_rollback_and_single_surface(monkeypatch):
    url = os.environ.get("RAG_TEST_DATABASE_URL")
    if not url:
        pytest.skip("需要显式 RAG_TEST_DATABASE_URL 专用测试库")
    monkeypatch.setenv("DATABASE_BACKEND", "postgres")
    monkeypatch.setenv("DATABASE_URL", url)
    monkeypatch.setenv("LLAMA_INDEX_TABLE", "rag_test_nodes")
    from app.config import get_settings
    from app.db import get_conn, init_db
    from app.services import rag_index_service as index
    from app.services.llama_index_service import _new_pgvector_store, _quoted_data_table
    from app.storage.li_retrieval_store import LiNodeRetrievalStore
    get_settings.cache_clear()
    init_db()
    settings = get_settings()
    store = _new_pgvector_store(settings, True)
    store._initialize()
    table = _quoted_data_table(settings)
    # 先把本用例自己的产物清干净再跑。此前的版本假定「库是新的」，于是**同一库跑第二遍
    # 必然失败**：上一轮留下的同内容 release 会被复用，首轮 ingest 不再调用 embedding，
    # `embed.calls == 1` 拿到 0。这个失败看上去像发布逻辑坏了，实际是测试隔离问题，
    # 排查成本很高；专用测试库按契约本就是一次性的，删掉这三张表的残留不损失任何断言。
    # 清理必须排在 _initialize() 之后：全新库上 data_<table> 由 store 初始化才存在。
    with get_conn() as conn:
        conn.execute("DELETE FROM rag_release_nodes")
        conn.execute("DELETE FROM rag_releases")
        conn.execute("DELETE FROM data_rag_test_nodes")
    content = [{"content": "DNS parser uses parse_dns identifier", "metadata": {
        "source_key": "repo/dns.c", "source_path": "dns.c", "ordinal": 0, "section": "parse_dns", "title": "DNS"}}]
    monkeypatch.setattr(index, "snapshot_sources", lambda settings: [dict(n, metadata=dict(n["metadata"])) for n in content])
    class Embedding:
        calls = 0
        async def embed(self, texts, *, usage=None, stage=""):
            self.calls += 1
            return [[1.0] + [0.0] * 1023 for _ in texts]
    embed = Embedding()
    monkeypatch.setattr(index, "build_embedding_model", lambda settings: embed)
    try:
        first = await index.ingest_release(settings, table)
        again = await index.ingest_release(settings, table)
        assert first["release_id"] == again["release_id"] and embed.calls == 1
        retrieval = LiNodeRetrievalStore(table, first["release_id"])
        fts = retrieval.fetch_fts_candidate_ids('"DNS"', 10)
        assert fts
        assert retrieval.fetch_path_candidate_ids(["dns.c"], 10) == fts
        assert retrieval.fetch_semantic_candidate_ids([1.0] + [0.0] * 1023, 10) == fts
        assert retrieval.fetch_rows_by_ids(fts)[0]["content"] == content[0]["content"]
        content[0]["content"] = "DNS updated parser"
        second = await index.ingest_release(settings, table)
        assert second["release_id"] != first["release_id"]
        assert retrieval.fetch_rows_by_ids(fts)[0]["content"] != content[0]["content"]
        with pytest.raises(RuntimeError):
            index._publish(table, first["release_id"], "conflict", "{}", [], [])
        assert index.active_release() == second["release_id"]
        index.rollback_release(first["release_id"])
        assert index.active_release() == first["release_id"]
        with get_conn() as conn:
            assert conn.execute("SELECT COUNT(*) AS n FROM knowledge_chunks").fetchone()["n"] == 0
    finally:
        store._engine.dispose()
        if store._async_engine:
            await store._async_engine.dispose()
