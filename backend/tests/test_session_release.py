"""S2/B14：真实会话持久绑定，向量检索由确定性替身隔离。"""
import asyncio
import threading
from concurrent.futures import ThreadPoolExecutor

import pytest

from app.config import get_settings
from app.db import get_conn
from app.services import llama_index_service, rag_index_service
from app.services.llama_index_service import LlamaIndexRetrievalAdapter
from app.services.session_service import SessionService


def publish(release):
    with get_conn() as conn:
        conn.execute("INSERT INTO rag_releases VALUES (?, ?, ?, ?)",
                     (release, rag_index_service.index_configuration(get_settings()), "2026-09-22", 0))
        conn.execute("UPDATE rag_index_state SET release_id=? WHERE name='active'", (release,))


@pytest.fixture
def recorded_retrieval(monkeypatch):
    class RecordedRetrieval:
        def __init__(self, retrieval_store):
            self.store = retrieval_store

        async def search(self, *args):
            return [self.store.release_id]

    monkeypatch.setattr(llama_index_service, "RetrievalService", RecordedRetrieval)


async def query(session):
    adapter = LlamaIndexRetrievalAdapter()
    adapter.bind_session(session)
    return await adapter.search("追问")


async def test_session_pins_release_across_new_instances_and_recreation(sqlite_db, recorded_retrieval):
    session = await SessionService().ensure_session(None, "问题")
    publish("r1")
    assert await query(session) == ["r1"]
    publish("r2")
    # 重建 service/retriever 不依赖进程内映射，模拟新请求与服务重启后的读取。
    assert SessionService().resolve_release(session) == "r1"
    assert await query(session) == ["r1"]
    other = await SessionService().ensure_session(None, "新问题")
    assert await query(other) == ["r2"]
    assert await SessionService().delete_session(session)
    recreated = await SessionService().ensure_session(session, "重建")
    assert recreated != session
    assert await query(recreated) == ["r2"]


async def test_no_release_waits_for_first_publication(sqlite_db, recorded_retrieval):
    session = await SessionService().ensure_session(None, "问题")
    assert await query(session) == []
    publish("r1")
    assert await query(session) == ["r1"]


async def test_pinned_release_config_mismatch_does_not_switch(sqlite_db, recorded_retrieval):
    session = await SessionService().ensure_session(None, "问题")
    publish("r1")
    assert await query(session) == ["r1"]
    with get_conn() as conn:
        conn.execute("UPDATE rag_releases SET configuration='{}' WHERE id='r1'")
    publish("r2")
    assert await query(session) == []
    assert SessionService().resolve_release(session) == "r1"


def test_parallel_first_resolution_uses_cas_winner(sqlite_db, monkeypatch):
    service = SessionService()
    session = service._ensure_session_sync(None, "问题")
    barrier = threading.Barrier(2)
    local = threading.local()

    def active():
        barrier.wait(timeout=5)
        return local.candidate

    monkeypatch.setattr(rag_index_service, "active_release", active)

    def resolve(candidate):
        local.candidate = candidate
        return SessionService().resolve_release(session)

    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(resolve, value) for value in ("r1", "r2")]
        results = [future.result(timeout=10) for future in futures]
    assert results[0] == results[1]
    assert results[0] in {"r1", "r2"}
    assert service.resolve_release(session) == results[0]


async def test_adapter_rebinding_does_not_carry_another_sessions_release(sqlite_db, recorded_retrieval):
    first, second = await asyncio.gather(
        SessionService().ensure_session(None, "一"), SessionService().ensure_session(None, "二"),
    )
    adapter = LlamaIndexRetrievalAdapter()
    publish("r1")
    adapter.bind_session(first)
    assert await adapter.search("问题") == ["r1"]
    publish("r2")
    adapter.bind_session(second)
    assert await adapter.search("问题") == ["r2"]
    adapter.bind_session(first)
    assert await adapter.search("问题") == ["r1"]


@pytest.mark.parametrize("stream", [False, True])
async def test_ordinary_entry_points_bind_before_retrieval(sqlite_db, monkeypatch, stream):
    """编排入口在检索前绑定会话的发布版本（普通问答的唯一编排实现）。"""
    from langchain_core.messages import AIMessage
    from test_agent_service import FakeMcp, FakeSummaryChat, ScriptedChatModel

    from app.agent.service import AgentOrchestrator
    from app.services.conversation_memory import ConversationMemoryService

    observed = []

    class EmptyRetrieval:
        def __init__(self, retrieval_store):
            self.store = retrieval_store

        async def search(self, *args):
            observed.append(self.store.release_id)
            return []

    monkeypatch.setattr(llama_index_service, "RetrievalService", EmptyRetrieval)
    session = await SessionService().ensure_session(None, "问题")
    for release in ("r1", "r2"):
        publish(release)
        model = ScriptedChatModel(responses=[
            AIMessage(content="", tool_calls=[{
                "name": "kb_search", "args": {"query": "问题"}, "id": "k1"}]),
            AIMessage(content="无资料"),
        ])
        service = AgentOrchestrator(
            retrieval=LlamaIndexRetrievalAdapter(),
            memory_service=ConversationMemoryService(
                sessions=SessionService(), chat_model=FakeSummaryChat()),
            sessions=SessionService(),
            model=model,
            mcp_caller=FakeMcp({"ok": True}),
        )
        if stream:
            events = [event async for event in service.answer_stream(session, "问题")]
            assert events[-1]["event"] == "done"
        else:
            response = await service.answer(session, "问题")
            assert response.session_id == session
    assert observed == ["r1", "r1"]


async def test_agent_graph_binds_session_before_tool_search(sqlite_db, recorded_retrieval):
    from langchain_core.messages import AIMessage
    from test_agent_service import ScriptedChatModel

    from app.agent.service import AgentOrchestrator

    session = await SessionService().ensure_session(None, "问题")
    for release in ("r1", "r2"):
        publish(release)
        adapter = LlamaIndexRetrievalAdapter()
        service = AgentOrchestrator(retrieval=adapter, model=ScriptedChatModel(responses=[AIMessage(content="好")]))
        _, context = service._build_run(session, "anonymous", [], "问题")
        assert await context.retriever.search("问题") == ["r1"]
