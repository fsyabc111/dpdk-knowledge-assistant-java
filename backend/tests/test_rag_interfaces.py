"""RAG interface conformance tests.

All external components (rewriter, embedding, retriever, chat model) are
injected as static doubles, so these tests exercise the service plumbing
without any network or model access.

S4 收敛注记：旧 ``IngestionService`` 与其注入式管线已随 legacy 数据面删除；
仍在服役的源/切块助手收在 ``app.services.knowledge_sources``（由
``rag_index_service`` 使用，覆盖见 test_rag_product.py）。
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.rag.types import RetrievedChunk
from app.services.retrieval_service import CandidateRanks, RetrievalService


class StaticRewriter:
    async def rewrite(self, original: str) -> str:
        return "main.c"


class EmptyEmbedding:
    async def embed(self, texts, *, usage=None, stage=""):
        return [[] for _ in texts]


class StaticRetriever:
    async def search(self, query: str, limit: int = 6, **kwargs):
        # This is an interface test, not a storage test. Do not return a fake
        # chunk id here: the answer path persists citations and PostgreSQL
        # rightly rejects ids that are absent from knowledge_chunks.
        return []

    def get_chunk(self, chunk_id: str):
        return None


async def test_retrieval_accepts_injected_components():
    service = RetrievalService(
        query_rewriter=StaticRewriter(),
        embedding_model=EmptyEmbedding(),
        retrieval_store=object(),
    )
    rewritten = await service._rewrite_query("anything")
    embedded = await service._embed_query("anything")
    assert rewritten == "main.c"
    assert embedded == []


async def test_agent_accepts_injected_pipeline(sqlite_db):
    """编排层接受注入的检索/模型替身：同形 ChatResponse 契约 + debug_trace。"""
    from langchain_core.messages import AIMessage
    from test_agent_service import FakeMcp, FakeSummaryChat, ScriptedChatModel

    from app.agent.service import AgentOrchestrator
    from app.services.conversation_memory import ConversationMemoryService
    from app.services.session_service import SessionService

    sessions = SessionService()
    model = ScriptedChatModel(responses=[AIMessage(content="answer from injected pipeline")])
    service = AgentOrchestrator(
        retrieval=StaticRetriever(),
        memory_service=ConversationMemoryService(sessions=sessions, chat_model=FakeSummaryChat()),
        sessions=sessions,
        model=model,
        mcp_caller=FakeMcp({"ok": True}),
    )
    response = await service.answer(None, "what is main", include_debug_trace=True)
    assert response.answer == "answer from injected pipeline"
    assert response.confidence == "low"  # 无检索命中 → 低置信度
    assert response.citations == []
    assert response.debug_trace is not None
    assert response.debug_trace["metadata"]["pipeline"] == "langgraph_agent"


async def test_retrieval_fusion_ranks_multiple_sources():
    primary = CandidateRanks(fts_rewritten=1)
    fused = CandidateRanks(fts_rewritten=3, fts_original=2, path=1)
    assert fused.rrf_score() > primary.rrf_score()


async def test_retrieval_diversifies_large_files():
    service = RetrievalService(
        query_rewriter=StaticRewriter(),
        embedding_model=EmptyEmbedding(),
        retrieval_store=object(),
    )
    chunks = [
        RetrievedChunk(
            id=f"same_{index}",
            source_path="same.c",
            title="same.c",
            section=f"fn_{index}",
            content=f"int same_{index}(void) {{ return {index}; }}",
            metadata={},
            score=1.0 - index * 0.01,
        )
        for index in range(5)
    ]
    chunks.append(
        RetrievedChunk(
            id="other",
            source_path="other.c",
            title="other.c",
            section="other",
            content="int other(void) { return 0; }",
            metadata={},
            score=0.8,
        )
    )
    selected = service._select_diverse_chunks(chunks, limit=4)
    assert sum(1 for chunk in selected if chunk.source_path == "same.c") <= 2
    assert any(chunk.source_path == "other.c" for chunk in selected)


if __name__ == "__main__":
    import sys

    sys.exit(pytest.main([__file__, "-v"]))
