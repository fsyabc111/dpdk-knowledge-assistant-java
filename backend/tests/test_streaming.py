"""Network-free SSE event contract test."""
from __future__ import annotations

import sys

import pytest


class FakeRetriever:
    async def search(self, query, limit=6, trace=None, conversation_context=None):
        from app.rag.types import RetrievedChunk
        return [RetrievedChunk(
            id="chk_1", source_path="docs/dns.md", title="DNS", section="报文格式",
            content="qname 标签编码", metadata={}, score=0.9,
        )]


class FakeChat:
    async def generate(self, messages, *, usage=None, stage=""):
        return "summary"


class FakeMcp:
    async def __call__(self, tool_name, arguments):
        return {"ok": True}


@pytest.fixture
async def streamed(sqlite_db):
    """Run AgentOrchestrator.answer_stream once against a throwaway SQLite DB.

    脚本化模型先调 kb_search（产出来源帧），再流式吐出两个正文 delta；
    所有 LLM/retrieval 组件都是 fake，无网络调用出进程。
    Returns (events, sessions) so tests can assert on the SSE sequence and
    on citation persistence for the produced message id.
    """
    from langchain_core.messages import AIMessage, AIMessageChunk
    from test_agent_service import ScriptedChatModel

    from app.agent.service import AgentOrchestrator
    from app.services.conversation_memory import ConversationMemoryService
    from app.services.session_service import SessionService

    sessions = SessionService()
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[{
            "name": "kb_search", "args": {"query": "DNS"}, "id": "k1"}]),
        [AIMessageChunk(content="你"), AIMessageChunk(content="好")],
    ])
    service = AgentOrchestrator(
        retrieval=FakeRetriever(),
        memory_service=ConversationMemoryService(sessions=sessions, chat_model=FakeChat()),
        sessions=sessions,
        model=model,
        mcp_caller=FakeMcp(),
    )
    events = [event async for event in service.answer_stream(None, "DNS 是什么？")]
    return events, sessions


async def test_sse_event_sequence(streamed):
    events, _ = streamed
    assert [event["event"] for event in events] == [
        "status", "start", "status", "tool_start", "tool_end", "sources",
        "delta", "delta", "done",
    ]


async def test_deltas_join_to_answer(streamed):
    events, _ = streamed
    assert "".join(event["data"].get("delta", "") for event in events) == "你好"


async def test_done_event_has_message_id(streamed):
    events, _ = streamed
    assert events[-1]["data"]["message_id"]


async def test_citation_persists_llama_index_namespace(streamed):
    events, sessions = streamed
    # LlamaIndex node ids live outside knowledge_chunks, so persisted
    # citations retain their own namespace instead of using a legacy-only FK.
    await sessions.add_citation(events[-1]["data"]["message_id"], "li_test_node", 0.9, 1, "llama_index")
    from app.db import get_conn

    with get_conn() as conn:
        row = conn.execute(
            "SELECT chunk_id, citation_engine FROM assistant_citations WHERE chunk_id = ?",
            ("li_test_node",),
        ).fetchone()
    assert row["citation_engine"] == "llama_index"


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
