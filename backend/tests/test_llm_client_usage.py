"""OpenAICompatibleClient 的用量采集测试——用 fake httpx transport，不联网。

这里的重点是**增量**：加了 sink 之后，返回值、异常与重试语义必须与从前逐字一致，
只有账本多出来。因此每个用例都先断言行为不变，再断言账目。

Usage:
    cd ai_chat_assistant/backend
    .venv/bin/python -m pytest tests/test_llm_client_usage.py -q
"""

from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Dict, List

import httpx
import pytest

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.core.llm_client import OpenAICompatibleClient
from app.core.usage import (
    KIND_LLM,
    SOURCE_UNKNOWN,
    SOURCE_UPSTREAM,
    UsageRecorder,
)


def _llm_settings(**overrides: Any) -> SimpleNamespace:
    values: Dict[str, Any] = {
        "llm_api_base": "https://example.invalid/v1",
        "llm_api_key": "test-key",
        "model_name": "qwen3.8-flash",
        "llm_max_tokens": 64,
        "llm_max_retries": 2,
        "llm_retry_base_seconds": 0.001,
        "llm_stream_usage_enabled": True,
    }
    values.update(overrides)
    return SimpleNamespace(**values)


def _embed_settings(**overrides: Any) -> SimpleNamespace:
    values: Dict[str, Any] = {
        "embedding_api_base": "https://example.invalid/v1",
        "embedding_api_key": "test-key",
        "embedding_model_name": "text-embedding-v4",
        "embedding_batch_size": 10,
        "embedding_max_retries": 1,
        "embedding_retry_base_seconds": 0.001,
    }
    values.update(overrides)
    return SimpleNamespace(**values)


class _Recorder:
    """把 fake transport 装进 client 的类级缓存，退出时还原。

    类级缓存是进程级的，必须还原，否则会污染同进程的其他用例。
    """

    def __init__(self, transport: httpx.MockTransport) -> None:
        self.client = httpx.AsyncClient(transport=transport)
        self._saved: Dict[str, Any] = {}

    def __enter__(self) -> "_Recorder":
        self._saved = {
            "_llm_client": OpenAICompatibleClient._llm_client,
            "_embedding_client": OpenAICompatibleClient._embedding_client,
        }
        OpenAICompatibleClient._llm_client = self.client
        OpenAICompatibleClient._embedding_client = self.client
        return self

    def __exit__(self, *exc: Any) -> None:
        OpenAICompatibleClient._llm_client = self._saved["_llm_client"]
        OpenAICompatibleClient._embedding_client = self._saved["_embedding_client"]


def _chat_body(content: str, usage: Dict[str, Any] | None = None) -> bytes:
    body: Dict[str, Any] = {"choices": [{"message": {"role": "assistant", "content": content}}]}
    if usage is not None:
        body["usage"] = usage
    return json.dumps(body).encode()


def _sse(*frames: Dict[str, Any], done: bool = True) -> bytes:
    chunks = "".join(f"data: {json.dumps(frame)}\n\n" for frame in frames)
    if done:
        chunks += "data: [DONE]\n\n"
    return chunks.encode()


# ---------------------------------------------------------------------------
# chat
# ---------------------------------------------------------------------------


async def test_chat_records_upstream_usage_without_changing_return() -> None:
    captured: List[Dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        captured.append(json.loads(request.content))
        return httpx.Response(200, content=_chat_body(
            "答案", {"prompt_tokens": 120, "completion_tokens": 30, "total_tokens": 150},
        ))

    recorder = UsageRecorder()
    with _Recorder(httpx.MockTransport(handler)):
        answer = await OpenAICompatibleClient(_llm_settings()).chat(
            [{"role": "user", "content": "hi"}], usage=recorder, stage="rewrite",
        )

    assert answer == "答案"                      # 返回值不变
    assert captured[0]["model"] == "qwen3.8-flash"
    entry = recorder.entries[0]
    assert entry.stage == "rewrite"
    assert entry.kind == KIND_LLM
    assert entry.usage_source == SOURCE_UPSTREAM
    assert entry.model == "qwen3.8-flash"
    assert (entry.prompt_tokens, entry.completion_tokens, entry.total_tokens) == (120, 30, 150)
    assert entry.call_count == 1
    assert entry.duration_ms is not None


async def test_chat_without_usage_field_records_unknown_not_zero() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=_chat_body("答案"))

    recorder = UsageRecorder()
    with _Recorder(httpx.MockTransport(handler)):
        answer = await OpenAICompatibleClient(_llm_settings()).chat(
            [{"role": "user", "content": "hi"}], usage=recorder, stage="rewrite",
        )

    assert answer == "答案"
    entry = recorder.entries[0]
    assert entry.usage_source == SOURCE_UNKNOWN
    # 关键：不是 0。0 在账本里代表「确实没花」。
    assert entry.prompt_tokens is None
    assert entry.completion_tokens is None
    assert entry.total_tokens is None


async def test_chat_records_nothing_when_no_sink_given() -> None:
    """没传 sink 时行为与改造前完全一致——既有调用方不受影响。"""
    calls: List[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(1)
        return httpx.Response(200, content=_chat_body("答案", {"total_tokens": 5}))

    with _Recorder(httpx.MockTransport(handler)):
        answer = await OpenAICompatibleClient(_llm_settings()).chat([{"role": "user", "content": "hi"}])

    assert answer == "答案"
    assert calls == [1]


async def test_chat_retry_then_success_keeps_attempt_count_visible() -> None:
    """重试过的调用，上游实际消耗可能高于账面——calls 让这个差额可见。"""
    attempts: List[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        attempts.append(1)
        if len(attempts) == 1:
            raise httpx.ConnectError("connection reset")
        return httpx.Response(200, content=_chat_body("recovered", {"total_tokens": 20}))

    recorder = UsageRecorder()
    with _Recorder(httpx.MockTransport(handler)):
        answer = await OpenAICompatibleClient(_llm_settings()).chat(
            [{"role": "user", "content": "hi"}], usage=recorder, stage="planner",
        )

    assert answer == "recovered"
    assert len(attempts) == 2
    assert len(recorder.entries) == 1          # 只记一笔，不重复计费
    assert recorder.entries[0].call_count == 2
    assert recorder.entries[0].total_tokens == 20


async def test_chat_exhausted_retries_records_unknown_and_reraises() -> None:
    """重试耗尽：异常照旧抛出，但账上留一条未知——不能既吞掉错误又把它算成成功。"""

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection reset")

    recorder = UsageRecorder()
    with _Recorder(httpx.MockTransport(handler)):
        with pytest.raises(httpx.ConnectError):
            await OpenAICompatibleClient(_llm_settings()).chat(
                [{"role": "user", "content": "hi"}], usage=recorder, stage="planner",
            )

    assert len(recorder.entries) == 1
    assert recorder.entries[0].usage_source == SOURCE_UNKNOWN
    assert recorder.entries[0].total_tokens is None
    assert recorder.entries[0].call_count == 3   # 初次 + 2 次重试


async def test_chat_non_retriable_error_records_unknown_once() -> None:
    calls: List[int] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(1)
        return httpx.Response(401, json={"error": "unauthorized"})

    recorder = UsageRecorder()
    with _Recorder(httpx.MockTransport(handler)):
        with pytest.raises(httpx.HTTPStatusError):
            await OpenAICompatibleClient(_llm_settings()).chat(
                [{"role": "user", "content": "hi"}], usage=recorder, stage="planner",
            )

    assert calls == [1]                          # 401 不重试
    assert len(recorder.entries) == 1
    assert recorder.entries[0].call_count == 1


async def test_chat_cancelled_inflight_records_unknown() -> None:
    """取消（judge 的 wait_for 超时 / 客户端断开）也要留痕。

    请求已经发出去了，上游很可能已经算过钱。「没记」不等于「没花」，记 0 更糟
    ——那是「确实没花」的意思。因此取消路径只能记 unknown。
    """
    entered = asyncio.Event()

    async def handler(request: httpx.Request) -> httpx.Response:
        entered.set()
        await asyncio.sleep(30)                  # 挂住，等外部取消
        return httpx.Response(200, content=_chat_body("来不及返回"))

    recorder = UsageRecorder()
    with _Recorder(httpx.MockTransport(handler)):
        task = asyncio.create_task(OpenAICompatibleClient(_llm_settings()).chat(
            [{"role": "user", "content": "hi"}], usage=recorder, stage="judge",
        ))
        await asyncio.wait_for(entered.wait(), timeout=5)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task

    assert len(recorder.entries) == 1
    entry = recorder.entries[0]
    assert entry.stage == "judge"
    assert entry.usage_source == SOURCE_UNKNOWN
    assert entry.total_tokens is None            # 未知不是 0
    assert entry.call_count == 1                 # 这一次确实发出去了


# ---------------------------------------------------------------------------
# embed
# ---------------------------------------------------------------------------


async def test_embed_records_usage_per_request() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        batch = json.loads(request.content)["input"]
        return httpx.Response(200, json={
            "data": [{"index": i, "embedding": [float(i)]} for i in range(len(batch))],
            "usage": {"prompt_tokens": 7 * len(batch), "total_tokens": 7 * len(batch)},
        })

    recorder = UsageRecorder()
    with _Recorder(httpx.MockTransport(handler)):
        vectors = await OpenAICompatibleClient(_embed_settings()).embed(
            [str(i) for i in range(3)], usage=recorder, stage="query_embed",
        )

    assert len(vectors) == 3
    assert len(recorder.entries) == 1
    entry = recorder.entries[0]
    assert entry.kind == "embedding"
    assert entry.stage == "query_embed"
    assert entry.model == "text-embedding-v4"
    assert entry.total_tokens == 21
    assert entry.completion_tokens is None


async def test_embed_records_unknown_when_upstream_omits_usage() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        batch = json.loads(request.content)["input"]
        return httpx.Response(200, json={
            "data": [{"index": i, "embedding": [0.0]} for i in range(len(batch))],
        })

    recorder = UsageRecorder()
    with _Recorder(httpx.MockTransport(handler)):
        await OpenAICompatibleClient(_embed_settings()).embed(
            ["a"], usage=recorder, stage="ingest_embed",
        )

    assert recorder.entries[0].usage_source == SOURCE_UNKNOWN
    assert recorder.entries[0].total_tokens is None


async def test_embed_failure_records_unknown_after_retries() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection reset")

    recorder = UsageRecorder()
    with _Recorder(httpx.MockTransport(handler)):
        with pytest.raises(httpx.ConnectError):
            await OpenAICompatibleClient(_embed_settings()).embed(
                ["a"], usage=recorder, stage="query_embed",
            )

    assert len(recorder.entries) == 1
    assert recorder.entries[0].usage_source == SOURCE_UNKNOWN
    assert recorder.entries[0].call_count == 2   # 初次 + 1 次重试


async def test_embed_empty_input_records_nothing() -> None:
    """空输入根本不发请求，不该留下任何账目。"""
    recorder = UsageRecorder()
    with _Recorder(httpx.MockTransport(lambda request: httpx.Response(500))):
        assert await OpenAICompatibleClient(_embed_settings()).embed([], usage=recorder) == []
    assert recorder.entries == []


# ---------------------------------------------------------------------------
# stream_chat
# ---------------------------------------------------------------------------


async def test_stream_chat_requests_and_parses_usage_tail_frame() -> None:
    payloads: List[Dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        payloads.append(json.loads(request.content))
        return httpx.Response(200, content=_sse(
            {"choices": [{"delta": {"content": "你"}}]},
            {"choices": [{"delta": {"content": "好"}}]},
            {"choices": [], "usage": {"prompt_tokens": 40, "completion_tokens": 5, "total_tokens": 45}},
        ), headers={"content-type": "text/event-stream"})

    recorder = UsageRecorder()
    with _Recorder(httpx.MockTransport(handler)):
        deltas = [
            part async for part in OpenAICompatibleClient(_llm_settings()).stream_chat(
                [{"role": "user", "content": "hi"}], usage=recorder, stage="answer",
            )
        ]

    assert deltas == ["你", "好"]                       # 尾帧不产生空 delta
    assert payloads[0]["stream_options"] == {"include_usage": True}
    entry = recorder.entries[0]
    assert entry.usage_source == SOURCE_UPSTREAM
    assert entry.total_tokens == 45
    assert entry.stage == "answer"


async def test_stream_chat_omits_stream_options_when_disabled() -> None:
    payloads: List[Dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        payloads.append(json.loads(request.content))
        return httpx.Response(200, content=_sse(
            {"choices": [{"delta": {"content": "x"}}]},
        ), headers={"content-type": "text/event-stream"})

    recorder = UsageRecorder()
    with _Recorder(httpx.MockTransport(handler)):
        settings = _llm_settings(llm_stream_usage_enabled=False)
        deltas = [
            part async for part in OpenAICompatibleClient(settings).stream_chat(
                [{"role": "user", "content": "hi"}], usage=recorder, stage="answer",
            )
        ]

    assert deltas == ["x"]
    assert "stream_options" not in payloads[0]
    assert recorder.entries[0].usage_source == SOURCE_UNKNOWN


async def test_stream_chat_retries_without_stream_options_on_400() -> None:
    """上游不认 stream_options 时：去掉字段重来一次，用户仍拿到完整答案。

    拿不到用量只能记未知，但绝不允许因为要计量就让请求失败。
    """
    payloads: List[Dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        payload = json.loads(request.content)
        payloads.append(payload)
        if "stream_options" in payload:
            return httpx.Response(400, json={"error": {"message": "unknown field stream_options"}})
        return httpx.Response(200, content=_sse(
            {"choices": [{"delta": {"content": "fallback"}}]},
        ), headers={"content-type": "text/event-stream"})

    recorder = UsageRecorder()
    with _Recorder(httpx.MockTransport(handler)):
        deltas = [
            part async for part in OpenAICompatibleClient(_llm_settings()).stream_chat(
                [{"role": "user", "content": "hi"}], usage=recorder, stage="answer",
            )
        ]

    assert deltas == ["fallback"]
    assert len(payloads) == 2
    assert "stream_options" in payloads[0]
    assert "stream_options" not in payloads[1]
    entry = recorder.entries[0]
    assert entry.usage_source == SOURCE_UNKNOWN
    assert entry.call_count == 2       # 两次上游调用都发生在账上


async def test_stream_chat_does_not_retry_other_400_causes() -> None:
    """只有 stream_options 被拒才重试；其他 400 照旧抛出，不掩盖真实错误。"""
    payloads: List[Dict[str, Any]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        payloads.append(json.loads(request.content))
        return httpx.Response(400, json={"error": {"message": "context length exceeded"}})

    recorder = UsageRecorder()
    with _Recorder(httpx.MockTransport(handler)):
        with pytest.raises(httpx.HTTPStatusError):
            [part async for part in OpenAICompatibleClient(_llm_settings()).stream_chat(
                [{"role": "user", "content": "hi"}], usage=recorder, stage="answer",
            )]

    assert len(payloads) == 1
    assert recorder.entries[0].usage_source == SOURCE_UNKNOWN


async def test_stream_chat_records_even_if_consumer_breaks_early() -> None:
    """消费方提前 break 时请求已经发出，账不能因为 GeneratorExit 就丢掉。"""

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=_sse(
            {"choices": [{"delta": {"content": "a"}}]},
            {"choices": [{"delta": {"content": "b"}}]},
            {"choices": [], "usage": {"total_tokens": 99}},
        ), headers={"content-type": "text/event-stream"})

    recorder = UsageRecorder()
    with _Recorder(httpx.MockTransport(handler)):
        stream = OpenAICompatibleClient(_llm_settings()).stream_chat(
            [{"role": "user", "content": "hi"}], usage=recorder, stage="answer",
        )
        async for delta in stream:
            assert delta == "a"
            break
        await stream.aclose()

    assert len(recorder.entries) == 1
    entry = recorder.entries[0]
    # 用量尾帧还没到就被中断了 —— 只能记未知，但这一笔必须存在。
    assert entry.usage_source == SOURCE_UNKNOWN
    assert entry.call_count == 1


async def test_stream_chat_ignores_malformed_frames() -> None:
    """坏帧跳过而不是让整条流失败——这是改造前就有的行为，必须保持。"""
    body = (
        b'data: {"choices":[{"delta":{"content":"a"}}]}\n\n'
        b'data: {not json}\n\n'
        b'data: \n\n'
        b'data: [DONE]\n\n'
    )

    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=body, headers={"content-type": "text/event-stream"})

    recorder = UsageRecorder()
    with _Recorder(httpx.MockTransport(handler)):
        deltas = [
            part async for part in OpenAICompatibleClient(_llm_settings()).stream_chat(
                [{"role": "user", "content": "hi"}], usage=recorder, stage="answer",
            )
        ]

    assert deltas == ["a"]
    assert recorder.entries[0].usage_source == SOURCE_UNKNOWN


async def test_stream_chat_without_sink_behaves_as_before() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, content=_sse(
            {"choices": [{"delta": {"content": "ok"}}]},
        ), headers={"content-type": "text/event-stream"})

    with _Recorder(httpx.MockTransport(handler)):
        deltas = [
            part async for part in OpenAICompatibleClient(_llm_settings()).stream_chat(
                [{"role": "user", "content": "hi"}],
            )
        ]
    assert deltas == ["ok"]
