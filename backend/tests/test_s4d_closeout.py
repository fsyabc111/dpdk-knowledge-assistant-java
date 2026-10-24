"""S4-d 收口项回归测试。

1. 取消占位消息历史可见（S3-e 遗留）：``cancel_task`` 的占位消息带
   ``answer_status``/``answer_reason``，历史接口（``include_cancelled``）展示
   「已取消」终态；会话记忆口径不变（默认 ``get_messages`` 仍过滤）。
2. 上下文变换不变式断言（W10）：``prepare_messages`` 第 5 级丢弃依赖
   「各级变换保条数」的隐含约定，已固化为显式断言——变换改条数立即显形。
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.agent import context as agent_context
from app.agent.context import prepare_messages
from app.api.chat import load_session
from app.services.rag_issue_service import RagIssueService
from app.services.session_service import SessionService

# ---------------------------------------------------------------------------
# 取消占位消息：历史可见 / 记忆仍过滤
# ---------------------------------------------------------------------------


async def _cancelled_session(sqlite_db):
    """建会话 + 一条 pending rag_requests + 显式取消（真实 cancel_task 路径）。"""
    sessions = SessionService()
    session_id = await sessions.ensure_session(None, "cancel history test")
    issues = RagIssueService()
    task_id = issues.begin(
        "req-cancel-1", "student", session_id, "评测我的插件", "", "test-model",
    )
    assert issues.cancel_task(task_id, "user_cancelled", "student") is True
    return session_id, task_id


async def test_cancel_placeholder_hidden_from_memory_by_default(sqlite_db):
    session_id, _ = await _cancelled_session(sqlite_db)
    messages = await SessionService().get_messages(session_id)
    assert all(
        item["metadata"].get("stream_status") != "cancelled" for item in messages
    )


async def test_cancel_placeholder_visible_with_include_cancelled(sqlite_db):
    session_id, task_id = await _cancelled_session(sqlite_db)
    messages = await SessionService().get_messages(session_id, include_cancelled=True)
    placeholder = [m for m in messages if m["metadata"].get("stream_status") == "cancelled"]
    assert len(placeholder) == 1
    assert placeholder[0]["content"] == "（请求已取消）"
    assert placeholder[0]["metadata"]["task_id"] == task_id
    # answer_status/answer_reason 供历史接口与前端「已取消」终态展示。
    assert placeholder[0]["metadata"]["answer_status"] == "cancelled"
    assert placeholder[0]["metadata"]["answer_reason"] == "user_cancelled"


async def test_load_session_surfaces_cancelled_turn(sqlite_db):
    """会话历史接口返回取消占位：刷新后用户仍能看到「已取消」而非悬空提问。"""
    session_id, task_id = await _cancelled_session(sqlite_db)
    loaded = await load_session(session_id, limit=100)
    cancelled = [m for m in loaded.messages if m.answer_status == "cancelled"]
    assert len(cancelled) == 1
    assert cancelled[0].content == "（请求已取消）"
    assert cancelled[0].answer_reason == "user_cancelled"
    assert cancelled[0].task_id == task_id


# ---------------------------------------------------------------------------
# W10：变换保条数约定固化为断言
# ---------------------------------------------------------------------------


def _big_history() -> list:
    """足够大的历史：任何一级变换都无法塞进小预算，必然走到第 5 级断言处。"""
    payload = "长" * 5000
    return [
        HumanMessage(content="评测一下"),
        AIMessage(content="", tool_calls=[{
            "name": "kb_search", "args": {"query": "q"}, "id": "call-1", "type": "tool_call",
        }]),
        ToolMessage(content=payload, tool_call_id="call-1", name="kb_search"),
        AIMessage(content="", tool_calls=[{
            "name": "kb_search", "args": {"query": "q2"}, "id": "call-2", "type": "tool_call",
        }]),
        ToolMessage(content=payload, tool_call_id="call-2", name="kb_search"),
    ]


def test_transform_count_invariant_violation_fails_loudly(monkeypatch):
    """某级变换若改变条数，断言在静默错位前抛 AssertionError（W10 固化）。"""
    monkeypatch.setattr(
        agent_context, "_compress_all", lambda history, summarizer: list(history)[1:],
    )
    # max_chars < _MIN_TOOL_CHARS：各级变换都无法塞入，必然抵达第 5 级断言处。
    with pytest.raises(AssertionError, match="preserve message count"):
        prepare_messages("系统提示词", _big_history(), max_chars=60)


def test_prepare_messages_reaches_level5_without_assertion():
    """正常路径（各级变换保条数）断言不触发：预算地板下返回 floor_exceeded。"""
    prepared = prepare_messages("系统提示词", _big_history(), max_chars=60)
    assert prepared.over_budget is True


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
