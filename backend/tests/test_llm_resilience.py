"""Network-free regression tests for LLM retry, reuse and trace behavior.

Usage:
    cd ai_chat_assistant/backend
    .venv/bin/python tests/test_llm_resilience.py
"""

from __future__ import annotations

import asyncio
import sys
from pathlib import Path
from types import SimpleNamespace

import httpx
import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.core.async_cache import AsyncTTLCache
from app.core.llm_client import OpenAICompatibleClient
from app.core.tracing import RequestTrace
from app.rag.providers import LlamaIndexChatModel, build_chat_model, clear_model_caches
from app.rag.types import RetrievedChunk
from app.services.agent_service import GroundedAnswerGenerator
from app.services.llama_index_service import LlamaIndexAnswerGenerator


class _Message:
    def __init__(self, role: str, content: str) -> None:
        self.role = role
        self.content = content


class _FailOnceLLM:
    def __init__(self) -> None:
        self.calls = 0

    async def achat(self, messages):
        self.calls += 1
        if self.calls == 1:
            raise httpx.ConnectError("connection reset")
        return SimpleNamespace(message=SimpleNamespace(content="recovered answer"))


class _UnauthorizedLLM:
    def __init__(self) -> None:
        self.calls = 0

    async def achat(self, messages):
        self.calls += 1
        request = httpx.Request("POST", "https://example.invalid/chat/completions")
        response = httpx.Response(401, request=request)
        raise httpx.HTTPStatusError("unauthorized", request=request, response=response)


class _StreamingLLM:
    async def astream_chat(self, messages):
        yield SimpleNamespace(delta="你")
        yield SimpleNamespace(delta="好")


def _retry_model(fake_llm):
    model = object.__new__(LlamaIndexChatModel)
    model.settings = SimpleNamespace(llm_max_retries=2, llm_retry_base_seconds=0.001,
                                     model_name="test-model")
    model._chat_message_cls = _Message
    model._llm = fake_llm
    return model


async def test_retries_connection_error() -> None:
    fake = _FailOnceLLM()
    answer, attempts = await _retry_model(fake).generate_with_attempts(
        [{"role": "user", "content": "hello"}]
    )
    assert answer == "recovered answer"
    assert attempts == 2
    assert fake.calls == 2


async def test_does_not_retry_unauthorized() -> None:
    fake = _UnauthorizedLLM()
    try:
        await _retry_model(fake).generate_with_attempts([{"role": "user", "content": "hello"}])
    except httpx.HTTPStatusError as exc:
        assert exc.response.status_code == 401
    else:
        raise AssertionError("401 response should not be retried or hidden")
    assert fake.calls == 1


async def test_trace_records_attempts() -> None:
    generator = GroundedAnswerGenerator(chat_model=_retry_model(_FailOnceLLM()))
    trace = RequestTrace()
    chunk = RetrievedChunk(
        id="chunk-1",
        source_path="example.c",
        title="example",
        section="main",
        content="example context",
        metadata={},
        score=1.0,
    )
    result = await generator.generate("question", [chunk], trace)
    assert result.answer == "recovered answer"
    assert trace.metadata["answer_generation_attempts"] == 2


async def test_llama_index_streams_deltas() -> None:
    model = _retry_model(_StreamingLLM())
    assert "".join([part async for part in model.stream([{"role": "user", "content": "hello"}])]) == "你好"


async def test_embedding_batches_at_provider_limit() -> None:
    calls = []
    class FakeClient:
        async def post(self, url, headers, json):
            calls.append(json["input"])
            request = httpx.Request("POST", url)
            return httpx.Response(200, request=request, json={"data": [
                {"index": i, "embedding": [float(i)]} for i in range(len(json["input"]))
            ]})
    previous = OpenAICompatibleClient._embedding_client
    OpenAICompatibleClient._embedding_client = FakeClient()
    try:
        settings = SimpleNamespace(embedding_api_base="https://example.invalid/v1", embedding_api_key="key",
                                   embedding_model_name="text-embedding-v4", embedding_batch_size=50,
                                   embedding_max_retries=0, embedding_retry_base_seconds=0.01)
        values = await OpenAICompatibleClient(settings).embed([str(i) for i in range(23)])
        assert [len(batch) for batch in calls] == [10, 10, 3]
        assert len(values) == 23
    finally:
        OpenAICompatibleClient._embedding_client = previous


async def test_cancelled_waiter_does_not_poison_shared_cache() -> None:
    cache = AsyncTTLCache[str, str](ttl_seconds=60)
    started = asyncio.Event()
    release = asyncio.Event()
    calls = 0

    async def factory() -> str:
        nonlocal calls
        calls += 1
        started.set()
        await release.wait()
        return "ready"

    cancelled_waiter = asyncio.create_task(cache.get_or_create("same", factory))
    await started.wait()
    surviving_waiter = asyncio.create_task(cache.get_or_create("same", factory))
    cancelled_waiter.cancel()
    try:
        await cancelled_waiter
    except asyncio.CancelledError:
        pass
    else:
        raise AssertionError("cancelled waiter should be cancelled")

    release.set()
    assert await surviving_waiter == "ready"
    assert await cache.get_or_create("same", factory) == "ready"
    assert calls == 1


def test_build_chat_model_reuses_instance() -> None:
    clear_model_caches()
    settings = SimpleNamespace(
        rag_provider="direct",
        llm_api_base="https://example.invalid/v1",
        model_name="test-model",
        llm_api_key="test-key",
    )
    assert build_chat_model(settings) is build_chat_model(settings)
    clear_model_caches()


# --- LlamaIndexAnswerGenerator retry / degradation coverage -----------------

def _answer_generator(engine_factory, max_retries: int = 2) -> LlamaIndexAnswerGenerator:
    generator = object.__new__(LlamaIndexAnswerGenerator)
    generator.settings = SimpleNamespace(llm_max_retries=max_retries, llm_retry_base_seconds=0.001,
                                         rag_answer_cache_enabled=False, model_name="test-model")
    generator._engine = engine_factory
    return generator


class _FlakyQueryEngine:
    """Fails aquery() `failures` times with a retriable error, then responds."""

    def __init__(self, failures: int, response) -> None:
        self.failures = failures
        self.response = response
        self.calls = 0

    async def aquery(self, query):
        self.calls += 1
        if self.calls <= self.failures:
            raise httpx.ConnectError("connection reset")
        return self.response


class _UnauthorizedQueryEngine:
    def __init__(self) -> None:
        self.calls = 0

    async def aquery(self, query):
        self.calls += 1
        request = httpx.Request("POST", "https://example.invalid/chat/completions")
        response = httpx.Response(401, request=request)
        raise httpx.HTTPStatusError("unauthorized", request=request, response=response)


def _streaming_response(deltas, fail_after_first: bool = False):
    class _Response:
        def async_response_gen(self):
            async def _gen():
                for index, delta in enumerate(deltas):
                    if fail_after_first and index == 1:
                        raise httpx.ConnectError("connection reset mid-stream")
                    yield delta
            return _gen()
    return _Response()


async def test_li_generator_retries_transient_failure() -> None:
    engine = _FlakyQueryEngine(failures=1, response=SimpleNamespace(response="recovered answer"))
    generator = _answer_generator(lambda chunks, streaming: engine)
    trace = RequestTrace()
    result = await generator.generate("question", [SimpleNamespace()], trace=trace)
    assert result.answer == "recovered answer"
    assert engine.calls == 2
    assert trace.metadata["answer_generation_attempts"] == 2


async def test_li_generator_does_not_retry_unauthorized() -> None:
    engine = _UnauthorizedQueryEngine()
    generator = _answer_generator(lambda chunks, streaming: engine)
    try:
        await generator.generate("question", [SimpleNamespace()])
    except httpx.HTTPStatusError as exc:
        assert exc.response.status_code == 401
    else:
        raise AssertionError("401 should not be retried")
    assert engine.calls == 1


async def test_li_stream_retries_until_first_delta() -> None:
    engines = [
        _FlakyQueryEngine(failures=99, response=None),  # always raises on aquery
    ]

    def factory(chunks, streaming):
        if len(engines) == 1:
            engines.append(None)
            return engines[0]
        return _FlakyQueryEngine(failures=0, response=_streaming_response(["你", "好"]))

    generator = _answer_generator(factory)
    parts = [part async for part in generator.stream("question", [SimpleNamespace()])]
    assert "".join(parts) == "你好"
    assert engines[0].calls == 1


async def test_li_stream_mid_stream_failure_propagates() -> None:
    engine = _FlakyQueryEngine(
        failures=0, response=_streaming_response(["你", "好"], fail_after_first=True)
    )
    generator = _answer_generator(lambda chunks, streaming: engine)
    parts = []
    try:
        async for part in generator.stream("question", [SimpleNamespace()]):
            parts.append(part)
    except httpx.ConnectError:
        pass
    else:
        raise AssertionError("mid-stream failure must propagate instead of restarting")
    assert parts == ["你"]
    assert engine.calls == 1


async def test_answer_degrades_instead_of_500(sqlite_db) -> None:
    """编排层模型失败 → build_fallback_answer 降级，接口不 500、引用保留。"""
    from langchain_core.messages import AIMessage
    from test_agent_service import FakeMcp, FakeSummaryChat, ScriptedChatModel

    from app.agent.service import AgentOrchestrator
    from app.services.conversation_memory import ConversationMemoryService
    from app.services.session_service import SessionService

    class _Retrieval:
        async def search(self, question, limit=8, trace=None, conversation_context=None):
            return [RetrievedChunk(
                id="chk-1", source_path="example.c", title="example", section="main",
                content="ctx", metadata={}, score=1.0,
            )]

    # 第一轮：kb_search 取到证据；第二轮（finalize）模型故障 → 降级答案带引用。
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[{
            "name": "kb_search", "args": {"query": "example.c"}, "id": "k1"}]),
        RuntimeError("upstream exploded"),
    ])
    sessions = SessionService()
    service = AgentOrchestrator(
        retrieval=_Retrieval(),
        memory_service=ConversationMemoryService(sessions=sessions, chat_model=FakeSummaryChat()),
        sessions=sessions,
        model=model,
        mcp_caller=FakeMcp({"ok": True}),
    )

    response = await service.answer(None, "example.c 里有什么？")
    assert "模型生成暂时不可用" in response.answer
    assert response.confidence == "medium"
    assert response.citations[0].source_path == "example.c"


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
