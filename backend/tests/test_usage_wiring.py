"""P1 第 3 步「全链路接线」的确定性回归（离线，不联网、不调真实模型）。

本文件锁死的是**接线**：哪条路径把用量写进账本、写进哪个 stage、零上游调用时
标哪种来源、取消时 ``finally`` 是否仍然落库。真实客户端的解析细节另有测试
（``tests/test_llm_client_usage.py``、``tests/test_usage_recorder.py``）；这里的
fake 模型会像真实适配器那样调用 ``usage.record_llm``，因此断言的是「谁在什么时候
记了一笔」，而不是 fake 自己的行为。

Usage:
    cd ai_chat_assistant/backend
    .venv/bin/python -m pytest tests/test_usage_wiring.py -q
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path
from typing import Any, Dict, List

import pytest
from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, HumanMessage
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.tools import tool

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.agent.graph import build_agent_graph
from app.agent.tools import AgentToolContext, build_tools
from app.config import get_settings
from app.core.tracing import RequestTrace
from app.core.usage import (
    KIND_EMBEDDING,
    KIND_LLM,
    KIND_MCP,
    SOURCE_CACHE_HIT,
    SOURCE_LOCAL,
    SOURCE_OPERATION,
    SOURCE_UNKNOWN,
    SOURCE_UPSTREAM,
    UsageRecorder,
)
from app.services.retrieval_service import HeuristicLLMQueryRewriter, RetrievalService
from app.services.usage_service import UsageService

# ---------------------------------------------------------------------------
# 会记账的替身
# ---------------------------------------------------------------------------


class _RecordingChatModel:
    """Chat 替身：像 ``DirectOpenAIChatModel`` 一样把上游用量交给 sink。"""

    def __init__(self, reply: str = "改写后的关键词") -> None:
        self.reply = reply
        self.stages: List[str] = []
        self.payload: Dict[str, Any] = {
            "prompt_tokens": 11, "completion_tokens": 3, "total_tokens": 14,
        }

    async def generate(self, messages, *, usage=None, stage=""):
        self.stages.append(stage)
        if usage is not None:
            usage.record_llm(stage, "test-model", self.payload)
        return self.reply


class _RecordingEmbeddingModel:
    """Embedding 替身：真实适配器记的就是 ``kind=embedding``。"""

    def __init__(self) -> None:
        self.stages: List[str] = []
        self.calls = 0

    async def embed(self, texts, *, usage=None, stage=""):
        self.stages.append(stage)
        self.calls += 1
        if usage is not None:
            usage.record_llm(
                stage, "text-embedding-v4", {"prompt_tokens": 7, "total_tokens": 7},
                kind=KIND_EMBEDDING,
            )
        return [[0.1, 0.2, 0.3, 0.4] for _ in texts]


class _PassthroughRewriter:
    async def rewrite(self, original, conversation_context=None, *, usage=None, stage=""):
        return original


def _retrieval_service(rewriter=None, embedding=None) -> RetrievalService:
    return RetrievalService(
        query_rewriter=rewriter or _PassthroughRewriter(),
        embedding_model=embedding or _RecordingEmbeddingModel(),
        retrieval_store=object(),
    )


def _identity_heuristic(monkeypatch, rewriter: HeuristicLLMQueryRewriter) -> None:
    """把启发式改成「永远不命中」，强制走上游分支。

    启发式自身的行为另有测试；这里只关心「走上游那一刻账记在哪」。
    """
    monkeypatch.setattr(rewriter, "_heuristic_rewrite", lambda original: original, raising=False)


# ---------------------------------------------------------------------------
# rewrite / followup：两个独立 stage
# ---------------------------------------------------------------------------


async def test_heuristic_hit_records_local_zero_call() -> None:
    await HeuristicLLMQueryRewriter._rewrite_cache.clear()
    model = _RecordingChatModel()
    rewriter = HeuristicLLMQueryRewriter(chat_model=model)
    usage = UsageRecorder()

    await rewriter.rewrite("dns 插件怎么解析域名", usage=usage)

    assert model.stages == [], "本地启发式出结果时不该调上游"
    entry, = usage.entries
    assert (entry.stage, entry.usage_source) == ("rewrite", SOURCE_LOCAL)
    assert entry.call_count == 0
    assert (entry.prompt_tokens, entry.completion_tokens, entry.total_tokens) == (None, None, None)
    totals = usage.totals()
    assert totals["zero_call_entries"] == 1
    assert totals["unknown_entries"] == 0, "本地规则出结果是**已知的零**，不是『账没算清』"


async def test_short_ascii_query_skips_rewrite_as_local() -> None:
    await HeuristicLLMQueryRewriter._rewrite_cache.clear()
    model = _RecordingChatModel()
    rewriter = HeuristicLLMQueryRewriter(chat_model=model)
    usage = UsageRecorder()

    assert await rewriter.rewrite("qname label codec", usage=usage) == "qname label codec"

    assert model.stages == []
    entry, = usage.entries
    assert (entry.stage, entry.usage_source, entry.call_count) == ("rewrite", SOURCE_LOCAL, 0)


async def test_rewrite_llm_branch_records_upstream(monkeypatch) -> None:
    await HeuristicLLMQueryRewriter._rewrite_cache.clear()
    model = _RecordingChatModel(reply="qname 标签序列 编码")
    rewriter = HeuristicLLMQueryRewriter(chat_model=model)
    _identity_heuristic(monkeypatch, rewriter)
    usage = UsageRecorder()

    assert await rewriter.rewrite("这个东西到底是怎么编码的呢", usage=usage) == "qname 标签序列 编码"

    assert model.stages == ["rewrite"]
    entry, = usage.entries
    assert (entry.stage, entry.usage_source) == ("rewrite", SOURCE_UPSTREAM)
    assert entry.model == "test-model"
    assert (entry.prompt_tokens, entry.completion_tokens, entry.total_tokens) == (11, 3, 14)
    assert entry.call_count == 1


async def test_rewrite_cache_hit_records_zero_call_not_a_fake_charge(monkeypatch) -> None:
    await HeuristicLLMQueryRewriter._rewrite_cache.clear()
    model = _RecordingChatModel(reply="命中缓存的改写结果")
    rewriter = HeuristicLLMQueryRewriter(chat_model=model)
    _identity_heuristic(monkeypatch, rewriter)

    first = UsageRecorder()
    await rewriter.rewrite("完全相同的问题", usage=first)
    second = UsageRecorder()

    assert await rewriter.rewrite("完全相同的问题", usage=second) == "命中缓存的改写结果"

    assert model.stages == ["rewrite"], "第二次命中缓存，不得再调上游"
    entry, = second.entries
    assert (entry.stage, entry.usage_source, entry.call_count) == ("rewrite", SOURCE_CACHE_HIT, 0)
    assert entry.total_tokens is None, "缓存命中没有用量——不是 0 次 token，是没有这次调用"
    assert second.totals()["zero_call_entries"] == 1
    assert second.totals()["unknown_entries"] == 0


async def test_follow_up_resolution_and_rewrite_are_two_stages(monkeypatch) -> None:
    """追问解析与改写是两次独立的上游调用，必须分成两笔账。"""
    await HeuristicLLMQueryRewriter._rewrite_cache.clear()
    model = _RecordingChatModel(reply="engine_dns.cpp 的 qname 解析流程")
    rewriter = HeuristicLLMQueryRewriter(chat_model=model)
    _identity_heuristic(monkeypatch, rewriter)
    usage = UsageRecorder()

    await rewriter.rewrite("它怎么解析？", "学生：dns 插件怎么解析域名", usage=usage)

    assert model.stages == ["followup", "rewrite"]
    assert [entry.stage for entry in usage.entries] == ["followup", "rewrite"]
    assert all(entry.usage_source == SOURCE_UPSTREAM for entry in usage.entries)
    assert usage.totals()["stages"] == ["followup", "rewrite"]
    assert usage.totals()["total_tokens"] == 28


async def test_rewriter_without_usage_sink_still_works() -> None:
    """记账是旁路：不接受 ``usage`` 的既有 rewriter 照常工作，只是账上少这一步。"""

    class _LegacyRewriter:
        def __init__(self) -> None:
            self.calls = 0

        async def rewrite(self, original, conversation_context=None):
            self.calls += 1
            return f"legacy:{original}"

    legacy = _LegacyRewriter()
    usage = UsageRecorder()

    assert await _retrieval_service(rewriter=legacy)._rewrite_query("任意问题", usage=usage) == "legacy:任意问题"

    assert legacy.calls == 1
    assert usage.entries == [], "不能因为记账而让检索失败，也不能凭空造账"


# ---------------------------------------------------------------------------
# query embedding：缓存命中要分清
# ---------------------------------------------------------------------------


async def test_query_embedding_records_upstream_then_cache_hit() -> None:
    await RetrievalService._embedding_cache.clear()
    model = _RecordingEmbeddingModel()
    service = _retrieval_service(embedding=model)

    first = UsageRecorder()
    vector = await service._embed_query("qname 标签序列编码", usage=first)
    assert vector, "首次必须真的算出向量"
    assert model.stages == ["query_embed"]

    second = UsageRecorder()
    assert await service._embed_query("qname 标签序列编码", usage=second) == vector
    assert model.stages == ["query_embed"], "第二次命中缓存，不得再调上游"

    miss, = first.entries
    assert (miss.kind, miss.stage, miss.usage_source) == (KIND_EMBEDDING, "query_embed", SOURCE_UPSTREAM)
    assert miss.total_tokens == 7

    hit, = second.entries
    assert (hit.kind, hit.stage, hit.usage_source) == (KIND_EMBEDDING, "query_embed", SOURCE_CACHE_HIT)
    assert hit.call_count == 0
    assert hit.model == get_settings().embedding_model_name
    assert second.totals()["total_tokens"] is None


# ---------------------------------------------------------------------------
# LangGraph planner / finalize
# ---------------------------------------------------------------------------

_USAGE_META = {"input_tokens": 30, "output_tokens": 10, "total_tokens": 40}


class _ScriptedChatModel(BaseChatModel):
    responses: list
    calls: int = 0

    @property
    def _llm_type(self) -> str:
        return "scripted-usage-fake"

    def bind_tools(self, tools, **kwargs):
        return self

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        message = self.responses[min(self.calls, len(self.responses) - 1)]
        self.calls += 1
        return ChatResult(generations=[ChatGeneration(message=message)])


class _ExplodingChatModel(BaseChatModel):
    @property
    def _llm_type(self) -> str:
        return "exploding-usage-fake"

    def bind_tools(self, tools, **kwargs):
        return self

    def _generate(self, messages, stop=None, run_manager=None, **kwargs):
        raise RuntimeError("upstream exploded")


@tool
async def fake_lookup(query: str) -> dict:
    """查询测试资料。"""
    return {"ok": True, "data": {"echo": query}}


async def _static_prompt(state) -> str:
    return "系统提示"


def _tool_call_message() -> AIMessage:
    return AIMessage(
        content="",
        tool_calls=[{"name": "fake_lookup", "args": {"query": "x"}, "id": "call_1"}],
        usage_metadata=_USAGE_META,
    )


async def test_graph_records_planner_usage() -> None:
    usage = UsageRecorder()
    model = _ScriptedChatModel(responses=[AIMessage(content="直接回答", usage_metadata=_USAGE_META)])
    graph = build_agent_graph(
        model=model, tools=[], prompt_builder=_static_prompt, max_rounds=2,
        usage=usage, model_name="test-model",
    )

    await graph.ainvoke({"messages": [HumanMessage(content="你好")]})

    entry, = usage.entries
    assert (entry.kind, entry.stage, entry.usage_source) == (KIND_LLM, "planner", SOURCE_UPSTREAM)
    assert entry.model == "test-model"
    assert (entry.prompt_tokens, entry.completion_tokens, entry.total_tokens) == (30, 10, 40)


async def test_graph_records_planner_and_finalize_separately() -> None:
    """planner 与 finalize 是两次上游调用；合并成一个数就拆不出是哪一步贵。"""
    usage = UsageRecorder()
    model = _ScriptedChatModel(responses=[
        _tool_call_message(),
        _tool_call_message(),
        AIMessage(content="最终答复", usage_metadata=_USAGE_META),
    ])
    graph = build_agent_graph(
        model=model, tools=[fake_lookup], prompt_builder=_static_prompt, max_rounds=1,
        usage=usage, model_name="test-model",
    )

    await graph.ainvoke({"messages": [HumanMessage(content="dns 怎么解析")]})

    assert [entry.stage for entry in usage.entries] == ["planner", "planner", "finalize"]
    assert all(entry.usage_source == SOURCE_UPSTREAM for entry in usage.entries)
    assert usage.totals()["total_tokens"] == 120


async def test_graph_records_unknown_when_upstream_fails() -> None:
    """失败也留一条：请求已经发出去，上游可能已经算过钱。"""
    usage = UsageRecorder()
    graph = build_agent_graph(
        model=_ExplodingChatModel(), tools=[], prompt_builder=_static_prompt, max_rounds=1,
        usage=usage, model_name="test-model",
    )

    with pytest.raises(RuntimeError):
        await graph.ainvoke({"messages": [HumanMessage(content="你好")]})

    entry, = usage.entries
    assert (entry.stage, entry.usage_source) == ("planner", SOURCE_UNKNOWN)
    assert entry.total_tokens is None, "未知不是 0"


# ---------------------------------------------------------------------------
# harness / MCP：只记次数与耗时，显式不计费
# ---------------------------------------------------------------------------


class _StubMcp:
    def __init__(self, payload: Any = None, error: Exception | None = None) -> None:
        self.payload = {"ok": True, "protocols": [{"name": "dns", "cases": 8}]} if payload is None else payload
        self.error = error
        self.calls: List[Any] = []

    async def __call__(self, tool_name, arguments):
        self.calls.append((tool_name, arguments))
        if self.error is not None:
            raise self.error
        return dict(self.payload)


class _NullRetriever:
    async def search(self, query, limit=6, trace=None, conversation_context=None):
        return []


def _tool_context(mcp: _StubMcp, trace: RequestTrace) -> AgentToolContext:
    return AgentToolContext(
        settings=get_settings(), user_id="u1", user_roles=["student"], session_id="sess1",
        retriever=_NullRetriever(), mcp_caller=mcp, request_trace=trace,
    )


async def test_mcp_call_records_operation_and_is_never_priced() -> None:
    trace = RequestTrace()
    mcp = _StubMcp()
    tools = {item.name: item for item in build_tools(_tool_context(mcp, trace))}

    assert (await tools["list_protocols"].ainvoke({}))["ok"] is True

    entry, = trace.usage.entries
    assert (entry.kind, entry.stage, entry.usage_source) == (KIND_MCP, "list_protocols", SOURCE_OPERATION)
    assert entry.call_count == 1
    assert entry.duration_ms is not None and entry.duration_ms >= 0
    assert entry.priced is False, "外部算力本仓无从定价，不许编费率"
    assert entry.total_tokens is None


async def test_failed_mcp_call_still_lands_on_the_ledger() -> None:
    """超时/失败同样占用了外部算力，账上不能当没发生。"""
    trace = RequestTrace()
    mcp = _StubMcp(error=TimeoutError("harness unreachable"))
    tools = {item.name: item for item in build_tools(_tool_context(mcp, trace))}

    with pytest.raises(TimeoutError):
        await tools["list_protocols"].ainvoke({})

    entry, = trace.usage.entries
    assert (entry.stage, entry.usage_source) == ("list_protocols", SOURCE_OPERATION)
    assert entry.call_count == 1


async def test_mcp_operations_stay_out_of_token_totals(sqlite_db) -> None:
    """非 token 用量分列：``operations`` 里有次数，``totals`` 里不计。"""
    from app.services.usage_service import flush_usage_recorder

    trace = RequestTrace()
    trace.usage.bind(subject="u1")
    usage = trace.usage
    usage.record_llm("planner", "test-model", _USAGE_META)
    usage.record_operation(KIND_MCP, "grade_plugin", duration_ms=1200)

    assert flush_usage_recorder(usage, request_id=trace.request_id, subject=usage.subject) == 2

    summary = UsageService().query()
    assert summary["totals"]["total_tokens"] == 40
    assert summary["totals"]["entries"] == 1
    assert summary["operations"] == {
        "entries": 1, "calls": 1, "duration_ms": 1200, "priced": False,
    }
    assert summary["by_stage"][0]["key"] in {"grade_plugin", "planner"}


# ---------------------------------------------------------------------------
# 取消：已发生的花费仍然落库
# ---------------------------------------------------------------------------


async def test_cancelled_run_still_flushes_usage(sqlite_db) -> None:
    """取消（客户端断开/任务终止）时 ``run()`` 的 ``finally: _log_trace`` 仍要落库。

    账本挂在 ``_log_trace`` 上而不是产品状态机的接受分支上，正是为了这条路径：
    已经花掉的钱不因为中途取消就消失。``_candidate`` 记一笔 embedding 用量后
    挂住，模拟「已花钱、等上游时被取消」。
    """
    from app.core.auth import Principal
    from app.schemas.chat import ChatRequest
    from app.services.rag_product_service import RagProductService

    started = asyncio.Event()
    product = RagProductService(
        Principal(subject="u1", roles=["viewer"], daily_quota=200),
        ChatRequest(message="dns 怎么解析"),
    )

    async def blocking_candidate(emit):
        product.trace.usage.record_llm(
            "query_embed", "text-embedding-v4", {"prompt_tokens": 9, "total_tokens": 9},
            kind=KIND_EMBEDDING,
        )
        started.set()
        await asyncio.sleep(30)                  # 挂住，等外部取消
        return None

    product._candidate = blocking_candidate

    async def discard(event):
        pass

    task = asyncio.create_task(product.run(discard))
    await asyncio.wait_for(started.wait(), timeout=5)
    task.cancel()
    with pytest.raises(asyncio.CancelledError):
        await task

    ledger = UsageService()
    assert ledger.count_rows() == 1, "取消不得丢掉已经发生的花费"
    row = ledger.query()
    assert row["by_stage"][0]["key"] == "query_embed"
    assert row["totals"]["total_tokens"] == 9


async def test_unknown_usage_survives_the_flush_as_unknown(sqlite_db) -> None:
    """调了上游却没拿到用量：落库后仍然是「未知」，既不是 0 也不是一笔成功计费。"""
    from app.services.usage_service import flush_usage_recorder

    trace = RequestTrace()
    trace.usage.bind(subject="u1", session_id="sess1")
    trace.usage.record_llm("rewrite", "test-model", None)

    assert flush_usage_recorder(
        trace.usage, request_id=trace.request_id,
        subject=trace.usage.subject, session_id=trace.usage.session_id,
    ) == 1

    summary = UsageService().query()
    assert summary["unknown_entries"] == 1
    assert summary["totals"]["total_tokens"] is None, "未知绝不当 0 加总"
    assert summary["totals"]["calls"] == 1, "调过一次，这一点是确定的"


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
