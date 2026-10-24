"""AgentOrchestrator 端到端（进程内）测试：同形接口 + SSE 事件契约 + fallback。

模型用脚本化 fake，MCP 用内存 callable，检索用 FakeRetriever——无网络、
无真实 harness；数据库走 sqlite_db 临时库。
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, AIMessageChunk
from langchain_core.messages.utils import message_chunk_to_message
from langchain_core.outputs import ChatGeneration, ChatGenerationChunk, ChatResult

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.agent.service import AgentOrchestrator
from app.rag.types import RetrievedChunk

HARNESS_OK = {
    "ok": True, "protocol": "dns", "score": 62, "cases_total": 8, "passed": 5,
    "failed": 3, "crashed": 0, "timedout": 0, "cheat": 0,
    "results": [
        {"name": "dns_basic_3", "category": "[POSITIVE]", "result": "FAIL",
         "failures": [{"desc": "qname", "expected": "example.com", "actual": "www.example.com"}]},
    ],
}


class ScriptedChatModel(BaseChatModel):
    """Replay scripted replies; bind_tools is a pass-through.

    每个脚本项的形态：
    - ``AIMessage``：整体返回（流式时转为单个 chunk）；
    - ``AIMessageChunk`` 列表：逐块吐给流式消费方（复现真实模型「正文前缀
      chunk + tool_call chunk」的分块形态）；非流式路径聚合成一条返回；
    - ``BaseException`` 实例：该次调用抛出此异常（复现工具执行成功后
      模型中途失败）。
    """

    responses: list
    fail: bool = False

    @property
    def _llm_type(self) -> str:
        return "scripted-fake"

    def bind_tools(self, tools, **kwargs):
        return self

    def _next_item(self):
        if self.fail:
            raise RuntimeError("model unavailable")
        index = min(getattr(self, "_call_index", 0), len(self.responses) - 1)
        object.__setattr__(self, "_call_index", index + 1)
        item = self.responses[index]
        if isinstance(item, BaseException):
            raise item
        return item

    @staticmethod
    def _to_chunks(item) -> list:
        if isinstance(item, list):
            return list(item)
        return [AIMessageChunk(content=item.content or "", tool_calls=list(item.tool_calls or []))]

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        chunks = self._to_chunks(self._next_item())
        message = chunks[0]
        for extra in chunks[1:]:
            message = message + extra
        return ChatResult(generations=[ChatGeneration(message=message_chunk_to_message(message))])

    async def _astream(self, messages, stop=None, run_manager=None, **kwargs):
        for chunk in self._to_chunks(self._next_item()):
            yield ChatGenerationChunk(message=chunk)


class FakeRetriever:
    async def search(self, query, limit=6, trace=None, conversation_context=None):
        return [RetrievedChunk(
            id="chk_1", source_path="docs/dns.md", title="DNS", section="报文格式",
            content="qname 标签编码", metadata={}, score=0.9,
        )]


class FakeSummaryChat:
    async def generate(self, messages, *, usage=None, stage=""):
        return "摘要"


class FakeMcp:
    def __init__(self, payload):
        self.payload = payload
        self.calls = []

    async def __call__(self, tool_name, arguments):
        self.calls.append((tool_name, arguments))
        return dict(self.payload)


def make_orchestrator(model, mcp):
    from app.services.conversation_memory import ConversationMemoryService
    from app.services.session_service import SessionService

    sessions = SessionService()
    return AgentOrchestrator(
        retrieval=FakeRetriever(),
        memory_service=ConversationMemoryService(sessions=sessions, chat_model=FakeSummaryChat()),
        sessions=sessions,
        model=model,
        mcp_caller=mcp,
    )


def kb_call():
    return {"name": "kb_search", "args": {"query": "dns qname"}, "id": "c1", "type": "tool_call"}


def grade_call():
    return {
        "name": "grade_plugin",
        "args": {"protocol": "dns", "plugin_source": "int main(){}"},
        "id": "c2", "type": "tool_call",
    }


def _tool_chunk(call: dict) -> dict:
    """tool_call（dict）→ 流式 tool_call_chunks 形态（args 为 JSON 字符串）。"""
    return {
        "name": call["name"],
        "args": json.dumps(call["args"], ensure_ascii=False),
        "id": call["id"],
        "index": 0,
    }


async def test_answer_runs_tool_round_and_keeps_response_contract(sqlite_db):
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[kb_call()]),
        AIMessage(content="DNS 的 qname 是按标签序列编码的[1]。"),
    ])
    orchestrator = make_orchestrator(model, FakeMcp(HARNESS_OK))
    response = await orchestrator.answer(None, "dns 的 qname 怎么编码？", user_id="stu_1", user_roles=["viewer"])
    assert response.answer == "DNS 的 qname 是按标签序列编码的[1]。"
    assert response.session_id
    assert response.citations and response.citations[0].source_path == "docs/dns.md"


async def test_answer_stream_emits_tool_events_and_records_submission(sqlite_db):
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[grade_call()]),
        AIMessage(content="dns_basic_3 挂了：期望 qname=example.com，你的插件上报了 www.example.com。"),
    ])
    mcp = FakeMcp(HARNESS_OK)
    orchestrator = make_orchestrator(model, mcp)
    events = [
        event async for event in orchestrator.answer_stream(
            None, "帮我评测这个 dns 插件", user_id="stu_1", user_roles=["viewer"]
        )
    ]
    names = [event["event"] for event in events]
    assert names[0] == "status" and "start" in names and names[-1] == "done"
    tool_start = next(e for e in events if e["event"] == "tool_start")
    assert tool_start["data"] == {"tool": "grade_plugin", "label": "正在评测 DNS 插件…"}
    tool_end = next(e for e in events if e["event"] == "tool_end")
    assert tool_end["data"]["ok"] is True
    assert tool_end["data"]["summary"] == "5/8 通过，得分 62"
    answer = "".join(e["data"].get("delta", "") for e in events)
    assert "dns_basic_3" in answer
    assert mcp.calls and mcp.calls[0][0] == "grade_plugin"

    session_id = events[-1]["data"]["session_id"]
    from app.db import get_conn

    with get_conn() as conn:
        row = conn.execute(
            "SELECT user_id, session_id, protocol, score, passed, total FROM grading_submissions"
        ).fetchone()
    assert row["user_id"] == "stu_1"
    assert row["session_id"] == session_id
    assert row["protocol"] == "dns"
    assert (row["score"], row["passed"], row["total"]) == (62, 5, 8)


async def test_stream_merged_text_and_tool_call_chunk_keeps_text(sqlite_db):
    """W9：模型把「正文 + tool_call」合在同一 chunk 时，正文照常下发。

    旧守卫按 ``tool_call_chunks`` 整块 continue，合并块中的正文被静默丢弃。
    """
    model = ScriptedChatModel(responses=[
        [
            AIMessageChunk(content="我先查一下知识库。",
                           tool_call_chunks=[_tool_chunk(kb_call())]),
        ],
        AIMessage(content="qname 按标签编码[1]。"),
    ])
    orchestrator = make_orchestrator(model, FakeMcp(HARNESS_OK))
    events = [
        event async for event in orchestrator.answer_stream(
            None, "qname 编码？", user_id="stu_1", user_roles=["viewer"]
        )
    ]
    deltas = [e["data"]["delta"] for e in events if e["event"] == "delta"]
    assert deltas[0] == "我先查一下知识库。"
    assert "qname" in "".join(deltas)


async def test_answer_stream_streams_kb_sources_event(sqlite_db):
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[kb_call()]),
        AIMessage(content="qname 按标签编码[1]。"),
    ])
    orchestrator = make_orchestrator(model, FakeMcp(HARNESS_OK))
    events = [
        event async for event in orchestrator.answer_stream(
            None, "qname 编码？", user_id="stu_1", user_roles=["viewer"]
        )
    ]
    sources = [e for e in events if e["event"] == "sources"]
    assert len(sources) == 1
    assert sources[0]["data"]["citations"][0]["source_path"] == "docs/dns.md"


async def test_model_failure_degrades_to_fallback_answer(sqlite_db):
    model = ScriptedChatModel(responses=[AIMessage(content="unused")], fail=True)
    orchestrator = make_orchestrator(model, FakeMcp(HARNESS_OK))
    response = await orchestrator.answer(None, "dns 插件怎么写？", user_id="stu_1", user_roles=["viewer"])
    # 无检索结果时的 fallback 文案（build_fallback_answer 无 chunks 分支）。
    assert "无法可靠回答这个问题" in response.answer
    assert response.confidence == "low"

    events = [
        event async for event in orchestrator.answer_stream(
            None, "dns 插件怎么写？", user_id="stu_1", user_roles=["viewer"]
        )
    ]
    names = [event["event"] for event in events]
    assert "error" not in names  # 无部分内容时退化为 fallback 文本而非 error 事件
    assert names[-1] == "done"
    answer = "".join(e["data"].get("delta", "") for e in events)
    assert "无法可靠回答这个问题" in answer


async def test_stream_failure_after_partial_keeps_grading_facts(sqlite_db):
    """B3/F2：SSE 已有部分正文后模型失败——已完成的评测结果必须随 fallback 追加。

    事件序列契约：delta(部分正文) → tool_start/tool_end(grade_plugin)
    → delta(fallback，含评测事实) → error → done；落库正文与流式内容一致。
    """
    model = ScriptedChatModel(responses=[
        # 第一次调用：先吐正文前缀 chunk，再吐 grade_plugin 调用 chunk。
        [
            AIMessageChunk(content="我先检查这份插件。"),
            AIMessageChunk(content="", tool_call_chunks=[_tool_chunk(grade_call())]),
        ],
        # 评测真实执行后，下一次模型调用失败。
        RuntimeError("model unavailable"),
    ])
    mcp = FakeMcp(HARNESS_OK)
    orchestrator = make_orchestrator(model, mcp)
    events = [
        event async for event in orchestrator.answer_stream(
            None, "帮我评测这个 dns 插件", user_id="stu_1", user_roles=["viewer"]
        )
    ]
    names = [event["event"] for event in events]
    assert names[-1] == "done"
    assert "error" in names  # 已有部分正文 → error 事件保留（契约不变）
    # 评测确实走到了 harness（配额已扣、成绩已落，丢弃分数即白评）。
    assert mcp.calls and mcp.calls[0][0] == "grade_plugin"

    deltas = [e["data"]["delta"] for e in events if e["event"] == "delta"]
    assert deltas[0] == "我先检查这份插件。"
    answer = "".join(deltas)
    # fallback 追加在部分正文之后，带来分数、通过数与失败用例。
    assert "本轮评测已完成" in answer
    assert "得分 62" in answer
    assert "通过 5/8" in answer
    assert "dns_basic_3" in answer
    # delta(fallback) 在 error 之前（契约：fallback delta + error）。
    last_delta_pos = max(i for i, e in enumerate(events) if e["event"] == "delta")
    assert names.index("error") > last_delta_pos

    session_id = events[-1]["data"]["session_id"]
    from app.db import get_conn

    with get_conn() as conn:
        row = conn.execute(
            "SELECT content, metadata_json FROM assistant_messages "
            "WHERE session_id = ? AND role = 'assistant' "
            "ORDER BY created_at DESC, id DESC LIMIT 1",
            (session_id,),
        ).fetchone()
    assert row["content"] == answer
    assert "得分 62" in row["content"]
    assert json.loads(row["metadata_json"])["stream_status"] == "completed"
    with get_conn() as conn:
        grade_row = conn.execute(
            "SELECT score, passed, total FROM grading_submissions"
        ).fetchone()
    assert (grade_row["score"], grade_row["passed"], grade_row["total"]) == (62, 5, 8)


async def test_stream_failure_after_partial_without_grading_stays_error_only(sqlite_db):
    """对照（形态四）：部分正文但无评测结果——只发 error，不追加 fallback delta。"""
    model = ScriptedChatModel(responses=[
        [
            AIMessageChunk(content="先检索一下："),
            AIMessageChunk(content="", tool_call_chunks=[_tool_chunk(kb_call())]),
        ],
        RuntimeError("model unavailable"),
    ])
    orchestrator = make_orchestrator(model, FakeMcp(HARNESS_OK))
    events = [
        event async for event in orchestrator.answer_stream(
            None, "qname 编码？", user_id="stu_1", user_roles=["viewer"]
        )
    ]
    names = [event["event"] for event in events]
    assert "error" in names
    assert names[-1] == "done"
    deltas = [e["data"]["delta"] for e in events if e["event"] == "delta"]
    assert deltas == ["先检索一下："]


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
