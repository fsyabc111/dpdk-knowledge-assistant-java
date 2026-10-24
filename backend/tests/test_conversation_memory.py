"""Network-free regression tests for server-side multi-turn conversation memory.

All chat models are in-process fakes (SummaryModel, CaptureModel, ...), so no
LLM/embedding call ever leaves the process; the database is a throwaway
SQLite file provided by the ``sqlite_db`` fixture.
"""
from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.rag.types import RetrievedChunk
from app.services.agent_service import GroundedAnswerGenerator
from app.services.conversation_memory import ConversationMemory, ConversationMemoryService
from app.services.retrieval_service import HeuristicLLMQueryRewriter
from app.services.session_service import SessionService


class SummaryModel:
    def __init__(self) -> None:
        self.calls = []

    async def generate(self, messages, *, usage=None, stage=""):
        self.calls.append(messages)
        return "压缩摘要：DNS 插件与 engine_dns.cpp。"


class FailingSummaryModel:
    async def generate(self, messages, *, usage=None, stage=""):
        raise RuntimeError("summary provider unavailable")


class CaptureModel:
    def __init__(self) -> None:
        self.calls = []

    async def generate(self, messages, *, usage=None, stage=""):
        self.calls.append(messages)
        return f"answer-{len(self.calls)}"


class FollowUpModel(CaptureModel):
    async def generate(self, messages, *, usage=None, stage=""):
        self.calls.append(messages)
        return "DNS 插件如何工作？"


class RecordingRetriever:
    def __init__(self) -> None:
        self.contexts = []

    async def search(self, query, limit=6, trace=None, conversation_context=None):
        self.contexts.append(conversation_context)
        return []


async def test_memory_is_isolated_and_incremental(sqlite_db):
    sessions = SessionService()
    first = await sessions.ensure_session(None, "DNS")
    second = await sessions.ensure_session(None, "FTP")
    for role, content in (("user", "DNS 插件是什么？"), ("assistant", "DNS 插件解析域名。"), ("user", "它如何调用？"), ("assistant", "查看 engine_dns.cpp。")):
        await sessions.add_message(first, role, content)
    await sessions.add_message(second, "user", "FTP 是什么？")

    model = SummaryModel()
    service = ConversationMemoryService(
        sessions=sessions,
        chat_model=model,
        settings=SimpleNamespace(conversation_recent_turns=1, conversation_summary_max_tokens=80),
    )
    memory = await service.build(first)
    assert memory.summary_used
    assert memory.history_turns == 2
    assert [item["content"] for item in memory.recent_messages] == ["它如何调用？", "查看 engine_dns.cpp。"]
    assert (await service.build(second)).summary == ""

    await sessions.add_message(first, "user", "继续说它的返回值")
    await sessions.add_message(first, "assistant", "返回检测结果。")
    updated = await service.build(first)
    assert updated.summary_used
    state = await sessions.get_memory_state(first)
    assert state["through_message_id"] is not None
    # The latest exchange remains raw and is never included in its own summary.
    assert "继续说它的返回值" not in model.calls[-1][1]["content"]


async def test_follow_up_rewrite_uses_history():
    model = FollowUpModel()
    rewriter = HeuristicLLMQueryRewriter(chat_model=model)
    rewritten = await rewriter.rewrite(
        "它如何工作？",
        conversation_context="user：介绍 DNS 插件\nassistant：DNS 插件负责域名解析。",
    )
    assert "dns" in rewritten.lower()
    assert "DNS 插件" in model.calls[0][1]["content"]


async def test_answer_cache_is_scoped_to_memory():
    model = CaptureModel()
    generator = GroundedAnswerGenerator(chat_model=model)
    chunk = RetrievedChunk(
        id="memory-chunk",
        source_path="engine_dns.cpp",
        title="dns",
        section="main",
        content="DNS context",
        metadata={},
        score=1.0,
    )
    first = ConversationMemory("DNS", [{"role": "user", "content": "DNS 插件"}], 1, True)
    second = ConversationMemory("FTP", [{"role": "user", "content": "FTP 插件"}], 1, True)
    assert (await generator.generate("它如何工作？", [chunk], conversation_memory=first)).answer == "answer-1"
    assert (await generator.generate("它如何工作？", [chunk], conversation_memory=second)).answer == "answer-2"
    assert len(model.calls) == 2


async def test_agent_passes_server_history_to_follow_up(sqlite_db):
    """追问的检索收到会话上下文（指代消解依据）。

    编排路径里检索发生在 kb_search 工具内：context.conversation_context
    由 ``answer()`` 按服务端记忆填充后传进 retriever.search。
    """
    from langchain_core.messages import AIMessage
    from test_agent_service import FakeMcp, ScriptedChatModel

    from app.agent.service import AgentOrchestrator

    sessions = SessionService()
    retriever = RecordingRetriever()
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[{
            "name": "kb_search", "args": {"query": "DNS 插件"}, "id": "k1"}]),
        AIMessage(content="DNS 插件负责域名解析。"),
        AIMessage(content="", tool_calls=[{
            "name": "kb_search", "args": {"query": "它如何工作"}, "id": "k2"}]),
        AIMessage(content="见参考资料。"),
    ])
    service = AgentOrchestrator(
        retrieval=retriever,
        memory_service=ConversationMemoryService(
            sessions=sessions,
            chat_model=SummaryModel(),
            settings=SimpleNamespace(conversation_recent_turns=6, conversation_summary_max_tokens=80),
        ),
        sessions=sessions,
        model=model,
        mcp_caller=FakeMcp({"ok": True}),
    )
    first = await service.answer(None, "请介绍 DNS 插件")
    await service.answer(first.session_id, "它如何工作？")
    assert retriever.contexts[0] is None
    assert "DNS 插件" in retriever.contexts[1]


async def test_summary_failure_never_expands_history(sqlite_db):
    sessions = SessionService()
    session = await sessions.ensure_session(None, "bounded")
    for index in range(8):
        await sessions.add_message(session, "user", f"old-{index}-" + "x" * 80)
        await sessions.add_message(session, "assistant", f"answer-{index}-" + "y" * 80)
    service = ConversationMemoryService(
        sessions=sessions,
        chat_model=FailingSummaryModel(),
        settings=SimpleNamespace(
            conversation_recent_turns=1,
            conversation_summary_max_tokens=80,
            conversation_summary_max_chars=120,
            conversation_memory_max_chars=180,
        ),
    )
    memory = await service.build(session)
    assert memory.summary_failed
    assert memory.memory_truncated
    assert len(memory.recent_messages) <= 2
    assert "old-0-" not in memory.as_retrieval_context()
    assert len(memory.as_retrieval_context()) < 260


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
