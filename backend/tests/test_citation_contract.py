"""S2：模型发号、响应引用与持久化顺序必须一致。"""
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest
from langchain_core.messages import AIMessage
from test_agent_service import FakeMcp, ScriptedChatModel, make_orchestrator

from app.agent.service import AgentOrchestrator
from app.agent.tools import AgentToolContext, build_tools
from app.config import get_settings
from app.rag.types import RetrievedChunk
from app.services.agent_service import GroundedAnswerGenerator
from app.services.citation_service import citations_from_chunks
from app.services.session_service import SessionService


def chunks():
    return [RetrievedChunk(str(i), "same.c", "同文件", f"片段{i}", f"正文{i}", {}, 0.9)
            for i in range(1, 8)]


async def test_cross_round_numbers_survive_citations_and_storage(sqlite_db):
    expected = chunks()
    retriever = SimpleNamespace(search=AsyncMock(side_effect=[expected[:4], expected[2:]]))
    context = AgentToolContext(get_settings(), "student", ["viewer"], None, retriever, AsyncMock())
    search = next(tool for tool in build_tools(context) if tool.name == "kb_search")
    first = await search.ainvoke({"query": "第一轮"})
    second = await search.ainvoke({"query": "第二轮"})
    assert [item["ref"] for item in first["data"]["chunks"]] == [1, 2, 3, 4]
    assert [item["ref"] for item in second["data"]["chunks"]] == [3, 4, 5, 6, 7]
    citations = AgentOrchestrator._citations(None, context)
    assert [item.chunk_id for item in citations] == [item.id for item in expected]
    for item in first["data"]["chunks"] + second["data"]["chunks"]:
        assert citations[item["ref"] - 1].section == item["section"]
    # 编排层的 _citations 与共享的 citations_from_chunks 同一映射。
    assert citations_from_chunks(expected) == citations
    model_context = GroundedAnswerGenerator._build_context(None, expected)
    for index, citation in enumerate(citations, 1):
        assert f"[{index}] source={citation.source_path} section={citation.section}" in model_context
    sessions = SessionService()
    session = await sessions.ensure_session(None, "引用契约")
    message = await sessions.add_message(session, "assistant", "事实 [7]")
    # 故意逆序插入，确保历史回读依赖 rank，而非碰巧的插入顺序。
    for rank, citation in reversed(list(enumerate(citations, 1))):
        await sessions.add_citation(message, citation.chunk_id, citation.score, rank)
    stored = (await sessions.get_citations([message]))[message]
    assert [row["chunk_id"] for row in stored] == [item.id for item in expected]
    assert [row["rank"] for row in stored] == list(range(1, 8))


@pytest.mark.parametrize("stream", [False, True])
async def test_agent_response_preserves_all_cross_round_citations(sqlite_db, stream):
    expected = chunks()
    calls = [AIMessage(content="", tool_calls=[{"name": "kb_search", "args": {"query": str(i)}, "id": str(i)}])
             for i in range(2)]
    orchestrator = make_orchestrator(ScriptedChatModel(responses=[*calls, AIMessage(content="事实 [7]")]), FakeMcp({}))
    orchestrator.retrieval = SimpleNamespace(search=AsyncMock(side_effect=[expected[:4], expected[2:]]))
    if stream:
        events = [event async for event in orchestrator.answer_stream(None, "协议事实", user_id="student")]
        # 每个 sources 事件携带「到当前为止」的完整累计列表，前端逐帧整体替换，
        # 最终渲染与持久化都以最后一帧为准——断言必须取最后一个。
        citations = [event["data"]["citations"] for event in events if event["event"] == "sources"][-1]
        result = next(event["data"] for event in events if event["event"] == "done")
    else:
        response = await orchestrator.answer(None, "协议事实", user_id="student")
        citations = [item.model_dump() for item in response.citations]
        result = response.model_dump()
    assert [item["chunk_id"] for item in citations] == [item.id for item in expected]
    messages = await orchestrator.sessions.get_messages(result["session_id"])
    message = next(item for item in reversed(messages) if item["role"] == "assistant")
    stored = (await orchestrator.sessions.get_citations([message["id"]]))[message["id"]]
    assert [item["chunk_id"] for item in stored] == [item.id for item in expected]
