"""M2 参考答案注入与粘贴路径协议推断的测试（mock 级、无网络）。

ReferenceAnswerProvider 覆盖缓存 / 截断 / 降级（异常、错误返回、空答案）分支；
prompt_builder 注入走 AgentOrchestrator 内存装配，harness 以内存 callable 代替。
"""
from __future__ import annotations

import sys
from pathlib import Path

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage
from langchain_core.outputs import ChatGeneration, ChatResult

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.agent.reference import ReferenceAnswerProvider
from app.agent.service import AgentOrchestrator, _failed_graded_protocol
from app.agent.tools import AgentToolContext
from app.schemas.chat import UploadedCode
from app.services.conversation_memory import ConversationMemory

REFERENCE_SOURCE = "/* reference */\nstatic bool dns_can_handle(...) { return true; }\n"

GRADING_WITH_FAIL = {
    "ok": True, "protocol": "dns", "score": 62, "cases_total": 8, "passed": 5,
    "results": [
        {"name": "dns_basic_3", "category": "[POSITIVE]", "result": "FAIL", "failures": []},
    ],
}

GRADING_ALL_PASS = {
    "ok": True, "protocol": "dns", "score": 100, "cases_total": 8, "passed": 8,
    "results": [
        {"name": "dns_basic_1", "category": "[POSITIVE]", "result": "PASS", "failures": []},
    ],
}


class FakeRetriever:
    async def search(self, query, limit=6, trace=None, conversation_context=None):
        return []


def make_context(settings):
    async def caller(tool_name, arguments):
        return {"ok": True, "protocol": "dns", "source": REFERENCE_SOURCE}

    return AgentToolContext(
        settings=settings, user_id="stu_1", user_roles=["viewer"],
        session_id=None, retriever=FakeRetriever(), mcp_caller=caller,
    )


# ---------------------------------------------------------------------------
# ReferenceAnswerProvider
# ---------------------------------------------------------------------------


async def test_provider_caches_per_protocol():
    calls = []

    async def caller(tool_name, arguments):
        calls.append((tool_name, arguments))
        return {"ok": True, "protocol": "dns", "source": REFERENCE_SOURCE}

    provider = ReferenceAnswerProvider(caller, max_chars=20000)
    first = await provider.get("dns")
    second = await provider.get("dns")
    assert first == REFERENCE_SOURCE == second
    assert calls == [("get_reference_answer", {"protocol": "dns"})]  # 同请求只打一次


async def test_provider_truncates_overlong_answer():
    async def caller(tool_name, arguments):
        return {"ok": True, "protocol": "dns", "source": "x" * 5000}

    provider = ReferenceAnswerProvider(caller, max_chars=1000)
    answer = await provider.get("dns")
    assert answer is not None
    assert answer.startswith("x" * 1000)
    assert answer.endswith("[已截断]")
    assert len(answer) == 1000 + len("\n[已截断]")


async def test_provider_degrades_on_exception():
    async def caller(tool_name, arguments):
        raise ConnectionError("harness down")

    provider = ReferenceAnswerProvider(caller, max_chars=20000)
    assert await provider.get("dns") is None  # 绝不抛穿
    # 失败结果同样缓存，不反复打 harness。
    assert await provider.get("dns") is None


async def test_provider_degrades_on_error_and_empty_payload():
    async def error_caller(tool_name, arguments):
        return {"ok": False, "error": "unknown_tool", "detail": "未知工具"}

    provider = ReferenceAnswerProvider(error_caller, max_chars=20000)
    assert await provider.get("dns") is None

    async def empty_caller(tool_name, arguments):
        return {"ok": True, "protocol": "dns", "source": "  "}

    provider = ReferenceAnswerProvider(empty_caller, max_chars=20000)
    assert await provider.get("dns") is None


# ---------------------------------------------------------------------------
# _failed_graded_protocol 与 prompt_builder 注入
# ---------------------------------------------------------------------------


def test_failed_graded_protocol_detection():
    assert _failed_graded_protocol([]) is None
    assert _failed_graded_protocol([GRADING_ALL_PASS]) is None
    assert _failed_graded_protocol([GRADING_WITH_FAIL]) == "dns"
    # 取最近一条含非 PASS 的结果。
    http_fail = dict(GRADING_WITH_FAIL, protocol="http")
    assert _failed_graded_protocol([http_fail, GRADING_ALL_PASS]) == "http"
    assert _failed_graded_protocol([GRADING_WITH_FAIL, http_fail]) == "http"


class _DummyChatModel(BaseChatModel):
    """prompt_builder 测试只用到编排器的装配，不经图调用模型。"""

    @property
    def _llm_type(self) -> str:
        return "dummy"

    def bind_tools(self, tools, **kwargs):
        return self

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        return ChatResult(generations=[ChatGeneration(message=AIMessage(content="ok"))])


def make_orchestrator(mcp):
    from app.services.conversation_memory import ConversationMemoryService
    from app.services.session_service import SessionService

    sessions = SessionService()
    return AgentOrchestrator(
        retrieval=FakeRetriever(),
        memory_service=ConversationMemoryService(sessions=sessions, chat_model=_DummyChatModel()),
        sessions=sessions,
        model=_DummyChatModel(),
        mcp_caller=mcp,
    )


async def test_prompt_builder_injects_reference_on_failed_cases(sqlite_db):
    async def caller(tool_name, arguments):
        assert tool_name == "get_reference_answer"
        return {"ok": True, "protocol": "dns", "source": REFERENCE_SOURCE}

    orchestrator = make_orchestrator(caller)
    ctx = make_context(orchestrator.settings)
    ctx.grading_results.append(dict(GRADING_WITH_FAIL))
    provider = ReferenceAnswerProvider(caller, orchestrator.settings.reference_answer_max_chars)
    build_prompt = orchestrator._build_prompt_builder(ctx, "看看哪里错了", provider)
    prompt = await build_prompt({"messages": []})
    assert "## 教师参考实现（仅供你理解错因）" in prompt
    assert REFERENCE_SOURCE in prompt
    assert "红线重述" in prompt


async def test_prompt_builder_skips_injection_when_all_pass_or_no_answer(sqlite_db):
    calls = []

    async def caller(tool_name, arguments):
        calls.append(tool_name)
        return {"ok": False, "error": "harness_unavailable", "detail": "不可达"}

    orchestrator = make_orchestrator(caller)
    # 全部 PASS：不触发拉取。
    ctx = make_context(orchestrator.settings)
    ctx.grading_results.append(dict(GRADING_ALL_PASS))
    provider = ReferenceAnswerProvider(caller, orchestrator.settings.reference_answer_max_chars)
    prompt = await orchestrator._build_prompt_builder(ctx, "问题", provider)({"messages": []})
    assert "教师参考实现" not in prompt
    assert calls == []
    # 有 FAIL 但 harness 失败：静默降级，提示词不受影响。
    ctx.grading_results.append(dict(GRADING_WITH_FAIL))
    prompt = await orchestrator._build_prompt_builder(ctx, "问题", provider)({"messages": []})
    assert "教师参考实现" not in prompt
    assert calls == ["get_reference_answer"]


# ---------------------------------------------------------------------------
# 粘贴路径协议推断在入图消息中的体现
# ---------------------------------------------------------------------------

EMPTY_MEMORY = ConversationMemory(
    summary="", recent_messages=[], history_turns=0, summary_used=False,
)


def _graph_question_text(orchestrator, question, attachment=None):
    messages = orchestrator._build_graph_messages(EMPTY_MEMORY, question, attachment)
    return messages[-1].content


def test_text_inference_hint_appended_on_unique_hit(sqlite_db):
    orchestrator = make_orchestrator(FakeRetriever().search)
    content = _graph_question_text(orchestrator, "帮我看看这段代码是哪的问题")
    assert "协议可能是" not in content
    content = _graph_question_text(orchestrator, "帮我看看这段域名解析代码")
    assert "协议可能是 dns" in content


def test_filename_hint_takes_priority_over_text_inference(sqlite_db):
    orchestrator = make_orchestrator(FakeRetriever().search)
    attachment = UploadedCode(
        filename="2024001_张三_dns.c", suffix=".c",
        source="int main(){}", protocol_hint="dns",
    )
    content = _graph_question_text(orchestrator, "评测这个文件共享插件", attachment)
    assert "文件名提示协议可能是 dns" in content
    assert "消息内容提示" not in content  # 文件名 hint 优先，不再叠加文本推断
