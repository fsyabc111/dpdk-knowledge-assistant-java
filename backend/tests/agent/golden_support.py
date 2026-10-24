"""黄金场景测试共享基建（docs/archive/2026-09-18/agent_upgrade_plan.md §8，D14）。

复用 tests/test_agent_service.py 的 fake 手法并做两处增强：
- ScriptedChatModel 额外记录每次调用收到的系统提示词与消息，供"反馈纪律"
  场景断言当前生效的提示词档位（默认引导 vs 定位级升级）；
- make_e2e_client 把真实 AgentOrchestrator（脚本化模型 + 内存 MCP）接到
  TestClient 上，覆盖 multipart 上传的端到端场景。

数据库走根 conftest 的 sqlite_db 临时库；无网络、无真实 harness。
"""
from __future__ import annotations

import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.outputs import ChatGeneration, ChatResult
from pydantic import Field

ROOT = Path(__file__).resolve().parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.rag.types import RetrievedChunk

DNS_SOURCE = "/* 2024001 dns plugin */\n#include <stdint.h>\nint plugin_main(void){return 0;}\n"

HARNESS_OK = {
    "ok": True, "protocol": "dns", "score": 100, "cases_total": 8, "passed": 8,
    "failed": 0, "crashed": 0, "timedout": 0, "cheat": 0,
    "results": [
        {"name": "dns_basic_1", "category": "[POSITIVE]", "result": "PASS", "failures": []},
    ],
}

HARNESS_FAIL = {
    "ok": True, "protocol": "dns", "score": 62, "cases_total": 8, "passed": 5,
    "failed": 3, "crashed": 0, "timedout": 0, "cheat": 0,
    "results": [
        {"name": "dns_basic_1", "category": "[POSITIVE]", "result": "PASS", "failures": []},
        {"name": "dns_basic_3", "category": "[POSITIVE]", "result": "FAIL",
         "failures": [{"desc": "qname", "expected": "example.com", "actual": "www.example.com"}]},
    ],
}

# 反馈纪律断言用的提示词标记（与 app/agent/prompts.py 的分层文案对应）。
DEFAULT_FEEDBACK_MARK = "不提供修复代码"
ESCALATED_FEEDBACK_MARK = "升级到定位级"
ESCALATED_NO_FULL_IMPL_MARK = "不得输出完整实现"
REDLINE_MARK = "参考答案实质等价"
# P0.1 围栏硬化：默认反馈档禁围栏；索要参考答案档禁围栏（骨架档仍允许代码）。
FEEDBACK_NO_FENCE_MARK = "不使用任何代码围栏"
ANSWER_REQUEST_NO_FENCE_MARK = "不输出任何代码围栏"
# M2：参考答案注入段与红线重述（app/agent/service.py 的 prompt_builder 文案）。
REFERENCE_SECTION_MARK = "教师参考实现"
REFERENCE_REDLINE_MARK = "不得输出与该参考实现实质等价的代码"
# M2：BASE_PROMPT 规则 4（骨架是学习材料而非答案）的标记。
TEMPLATE_RULE_MARK = "学习骨架、不是作业答案"

LIST_PROTOCOLS_OK = {
    "ok": True,
    "protocols": [
        {"name": "dns", "cases": 8, "categories": {"[POSITIVE]": 5, "[NEGATIVE]": 3}},
        {"name": "http", "cases": 6, "categories": {"[POSITIVE]": 4, "[NEGATIVE]": 2}},
    ],
}

LIST_CASES_DNS = {
    "ok": True, "protocol": "dns",
    "cases": [
        {"name": "dns_basic_1", "category": "[POSITIVE]"},
        {"name": "dns_basic_3", "category": "[POSITIVE]"},
    ],
}

# 参考答案源码里的独特标记：注入后应出现在系统提示词、绝不应出现在学生可见输出。
REFERENCE_UNIQUE_MARK = "REF_DNS_QNAME_LABEL_CODEC_V2"
REFERENCE_DNS = {
    "ok": True, "protocol": "dns",
    "source": (
        f"/* 教师参考实现 {REFERENCE_UNIQUE_MARK} */\n"
        "#include <stdint.h>\n"
        "static int encode_qname_labels(uint8_t *out, const char *name)\n"
        "{\n"
        "    /* 按 '.' 拆成标签序列，逐段写长度字节与标签内容。 */\n"
        "    return 0;\n"
        "}\n"
    ),
}


class ScriptedChatModel(BaseChatModel):
    """Replay scripted AIMessages and record every invocation for assertions."""

    responses: list
    calls: int = 0
    system_prompts: List[str] = Field(default_factory=list)
    seen_messages: List[list] = Field(default_factory=list)

    @property
    def _llm_type(self) -> str:
        return "scripted-fake"

    def bind_tools(self, tools, **kwargs):
        return self

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        self.calls += 1
        self.seen_messages.append(list(messages))
        first = messages[0] if messages else None
        self.system_prompts.append(
            first.content if first is not None and getattr(first, "type", "") == "system" else ""
        )
        index = min(self.calls - 1, len(self.responses) - 1)
        return ChatResult(generations=[ChatGeneration(message=self.responses[index])])

    def human_texts(self) -> List[str]:
        """All HumanMessage contents the model ever saw (across invocations)."""
        return [
            message.content
            for invocation in self.seen_messages
            for message in invocation
            if getattr(message, "type", "") == "human" and isinstance(message.content, str)
        ]


class FakeRetriever:
    def __init__(self, chunks: Optional[list] = None):
        self.chunks = chunks if chunks is not None else [
            RetrievedChunk(
                id="chk_1", source_path="docs/dns.md", title="DNS", section="报文格式",
                content="qname 以标签序列编码", metadata={}, score=0.9,
            )
        ]
        self.queries: List[str] = []
        self.contexts: List[Optional[str]] = []

    async def search(self, query, limit=6, trace=None, conversation_context=None):
        self.queries.append(query)
        self.contexts.append(conversation_context)
        return self.chunks[:limit]


class FakeMcp:
    """In-memory MCP caller; ``error`` simulates an unreachable harness.

    ``payloads`` 按工具名分发返回值（M2 起同一请求里 grade_plugin 与
    get_reference_answer/list_cases 等返回不同 payload）；值为 Exception
    时仅对该工具抛错。未列在 payloads 里的工具退回 payload/error 旧行为。
    """

    def __init__(
        self,
        payload: Optional[Dict[str, Any]] = None,
        error: Optional[Exception] = None,
        payloads: Optional[Dict[str, Any]] = None,
    ):
        self.payload = payload if payload is not None else HARNESS_OK
        self.error = error
        self.payloads = payloads or {}
        self.calls: List = []

    async def __call__(self, tool_name, arguments):
        self.calls.append((tool_name, arguments))
        if tool_name in self.payloads:
            outcome = self.payloads[tool_name]
            if isinstance(outcome, Exception):
                raise outcome
            return dict(outcome)
        if self.error is not None:
            raise self.error
        return dict(self.payload)


class FakeSummaryChat:
    async def generate(self, messages, *, usage=None, stage=""):
        return "摘要"


def build_orchestrator(model, mcp, retriever=None):
    from app.agent.service import AgentOrchestrator
    from app.services.conversation_memory import ConversationMemoryService
    from app.services.session_service import SessionService

    sessions = SessionService()
    return AgentOrchestrator(
        retrieval=retriever or FakeRetriever(),
        memory_service=ConversationMemoryService(sessions=sessions, chat_model=FakeSummaryChat()),
        sessions=sessions,
        model=model,
        mcp_caller=mcp,
    )


def make_e2e_client(monkeypatch, model, mcp, retriever=None):
    """TestClient wired to a real AgentOrchestrator with scripted externals.

    只替换产品受理层的编排装配点（RagProductService._assemble_orchestrator），
    multipart 解析、校验、限流、请求登记、SSE/JSON 契约都走真实代码路径。
    """
    from fastapi.testclient import TestClient

    import app.api.chat as chat_api
    from app.main import app

    orchestrator = build_orchestrator(model, mcp, retriever)

    def _inject(service):
        service._service = orchestrator
        return orchestrator

    monkeypatch.setattr(chat_api.RagProductService, "_assemble_orchestrator", _inject)
    return TestClient(app)


def kb_call(query: str = "dns qname", call_id: str = "c_kb") -> Dict[str, Any]:
    return {"name": "kb_search", "args": {"query": query}, "id": call_id, "type": "tool_call"}


def list_protocols_call(call_id: str = "c_lp") -> Dict[str, Any]:
    return {"name": "list_protocols", "args": {}, "id": call_id, "type": "tool_call"}


def list_cases_call(protocol: str = "dns", call_id: str = "c_lc") -> Dict[str, Any]:
    return {"name": "list_cases", "args": {"protocol": protocol}, "id": call_id, "type": "tool_call"}


def template_call(protocol: str = "dns", call_id: str = "c_tpl") -> Dict[str, Any]:
    return {"name": "plugin_template", "args": {"protocol": protocol}, "id": call_id, "type": "tool_call"}


def grade_call(protocol: str = "dns", source: str = DNS_SOURCE, call_id: str = "c_grade") -> Dict[str, Any]:
    return {
        "name": "grade_plugin",
        "args": {"protocol": protocol, "plugin_source": source},
        "id": call_id,
        "type": "tool_call",
    }


def assert_grade_then_reference(calls: List, protocol: str = "dns") -> None:
    """M2 语义：grade_plugin 结果含非 PASS 用例时，prompt_builder 带外拉一次参考答案。

    允许（且只允许）序列形如 grade_plugin 后跟若干次 get_reference_answer
    （同一请求内按协议缓存，通常恰好一次）。
    """
    assert calls, "expected at least the grade_plugin call"
    assert calls[0][0] == "grade_plugin"
    for name, args in calls[1:]:
        assert name == "get_reference_answer"
        assert args == {"protocol": protocol}


async def collect_stream(orchestrator, question, user_id="stu_1", user_roles=("viewer",)):
    return [
        event async for event in orchestrator.answer_stream(
            None, question, user_id=user_id, user_roles=list(user_roles)
        )
    ]


def stream_text(events) -> str:
    return "".join(event["data"].get("delta", "") for event in events)


def submission_count() -> int:
    from app.db import get_conn

    with get_conn() as conn:
        return conn.execute("SELECT COUNT(*) AS n FROM grading_submissions").fetchone()["n"]
