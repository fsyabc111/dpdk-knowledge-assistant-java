from __future__ import annotations

import asyncio
import json
import logging
import random
import time
from typing import Any, AsyncIterator, ClassVar, Dict, List, Mapping, Optional

import httpx

from ..config import Settings
from .upstream_gate import upstream_slot
from .usage import KIND_EMBEDDING, KIND_LLM, UsageRecorder

logger = logging.getLogger("assistant")


class ModelConfigError(RuntimeError):
    pass


def _elapsed_ms(started: float) -> int:
    return max(int((time.monotonic() - started) * 1000), 0)


def is_retriable_llm_error(exc: Exception) -> bool:
    """Return whether a transient transport or upstream error is retryable."""
    if isinstance(exc, (httpx.TimeoutException, httpx.NetworkError)):
        return True
    status_code = getattr(exc, "status_code", None)
    if status_code is None:
        response = getattr(exc, "response", None)
        status_code = getattr(response, "status_code", None)
    if isinstance(status_code, int):
        return status_code == 429 or status_code >= 500
    cause = exc.__cause__ or exc.__context__
    if isinstance(cause, Exception) and cause is not exc:
        return is_retriable_llm_error(cause)
    message = str(exc).lower()
    return any(marker in message for marker in ("connection error", "connection reset", "timed out", "temporarily unavailable"))


def llm_retry_delay_seconds(attempt: int, base_seconds: float) -> float:
    """Exponential backoff; attempt starts at one after the initial failure."""
    return base_seconds * (2 ** max(attempt - 1, 0))


class OpenAICompatibleClient:
    _llm_client: ClassVar[httpx.AsyncClient | None] = None
    _embedding_client: ClassVar[httpx.AsyncClient | None] = None

    def __init__(self, settings: Settings):
        self.settings = settings

    def _headers(self, api_key: str) -> Dict[str, str]:
        return {
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        }

    @classmethod
    def _build_client(cls, timeout_seconds: float) -> httpx.AsyncClient:
        return httpx.AsyncClient(
            timeout=httpx.Timeout(timeout_seconds, connect=10.0),
            limits=httpx.Limits(max_connections=200, max_keepalive_connections=50),
        )

    @classmethod
    def _get_llm_client(cls) -> httpx.AsyncClient:
        if cls._llm_client is None:
            cls._llm_client = cls._build_client(90.0)
        return cls._llm_client

    @classmethod
    def get_llm_http_client(cls) -> httpx.AsyncClient:
        """Expose the process-wide client for adapters such as LlamaIndex."""
        return cls._get_llm_client()

    @classmethod
    def _get_embedding_client(cls) -> httpx.AsyncClient:
        if cls._embedding_client is None:
            cls._embedding_client = cls._build_client(60.0)
        return cls._embedding_client

    @classmethod
    async def close_clients(cls) -> None:
        if cls._llm_client is not None:
            await cls._llm_client.aclose()
            cls._llm_client = None
        if cls._embedding_client is not None:
            await cls._embedding_client.aclose()
            cls._embedding_client = None

    async def embed(
        self,
        texts: List[str],
        *,
        usage: Optional[UsageRecorder] = None,
        stage: str = "",
    ) -> List[List[float]]:
        if not self.settings.embedding_api_base or not self.settings.embedding_api_key:
            raise ModelConfigError("Embedding API is not configured. Set EMBEDDING_API_BASE and EMBEDDING_API_KEY in .env.")
        url = self.settings.embedding_api_base.rstrip("/") + "/embeddings"
        if not texts:
            return []
        batch_size = max(1, min(int(self.settings.embedding_batch_size), 10))
        client = self._get_embedding_client()
        vectors: List[List[float]] = []
        for start in range(0, len(texts), batch_size):
            batch = texts[start:start + batch_size]
            payload = {"model": self.settings.embedding_model_name, "input": batch}
            started = time.monotonic()
            calls = 0
            # 每批各记一次（前几批已完成的花费不受后面批次取消影响）。理由同 chat：
            # 取消与未预期异常也要留下一笔 unknown。
            recorded = False
            try:
                for attempt in range(int(self.settings.embedding_max_retries) + 1):
                    calls = attempt + 1
                    try:
                        async with upstream_slot():
                            resp = await client.post(url, headers=self._headers(self.settings.embedding_api_key), json=payload)
                        resp.raise_for_status()
                        data = resp.json()
                        items = sorted(data.get("data", []), key=lambda item: item.get("index", 0))
                        batch_vectors = [item["embedding"] for item in items]
                        if len(batch_vectors) != len(batch):
                            raise RuntimeError(f"Embedding count mismatch: expected {len(batch)}, got {len(batch_vectors)}")
                        # usage 在真正拿到结果后才记；calls 让「重试过」这件事在账面上可见。
                        self._record_usage(
                            usage, stage, self.settings.embedding_model_name,
                            data.get("usage"), started, calls, KIND_EMBEDDING,
                        )
                        recorded = True
                        vectors.extend(batch_vectors)
                        break
                    except Exception as exc:
                        if attempt >= int(self.settings.embedding_max_retries) or not is_retriable_llm_error(exc):
                            # 重试后仍失败：上游可能已消耗算力但没有可用 usage —— 记未知，不记 0。
                            self._record_usage(
                                usage, stage, self.settings.embedding_model_name,
                                None, started, calls, KIND_EMBEDDING,
                            )
                            recorded = True
                            raise
                        retry_after = getattr(getattr(exc, "response", None), "headers", {}).get("Retry-After")
                        try:
                            delay = float(retry_after) if retry_after else 0.0
                        except (TypeError, ValueError):
                            delay = 0.0
                        if not delay:
                            delay = float(self.settings.embedding_retry_base_seconds) * (2 ** attempt)
                            delay += random.uniform(0, min(0.5, delay * 0.25))
                        await asyncio.sleep(delay)
            finally:
                if not recorded:
                    self._record_usage(
                        usage, stage, self.settings.embedding_model_name,
                        None, started, max(calls, 1), KIND_EMBEDDING,
                    )
        return vectors

    @staticmethod
    def _record_usage(
        usage: Optional[UsageRecorder],
        stage: str,
        model: str,
        raw_usage: Optional[Mapping[str, Any]],
        started: float,
        calls: int,
        kind: str,
    ) -> None:
        """把一次（或一串重试）的真实上游花费交给累加器。

        ``usage`` 为 None 表示调用方没要账本——静默跳过，不影响请求本身。
        """
        if usage is None:
            return
        usage.record_llm(
            stage, model, raw_usage,
            duration_ms=_elapsed_ms(started), kind=kind, calls=calls,
        )

    async def chat(
        self,
        messages: List[Dict[str, str]],
        *,
        usage: Optional[UsageRecorder] = None,
        stage: str = "",
        kind: str = KIND_LLM,
    ) -> str:
        if not self.settings.llm_api_base or not self.settings.llm_api_key:
            raise ModelConfigError("LLM API is not configured. Set LLM_API_BASE and LLM_API_KEY in .env.")
        url = self.settings.llm_api_base.rstrip("/") + "/chat/completions"
        payload = {
            "model": self.settings.model_name,
            "messages": messages,
            "temperature": 0.2,
            "max_tokens": self.settings.llm_max_tokens,
        }
        client = self._get_llm_client()
        started = time.monotonic()
        calls = 0
        # 只记一次。取消（judge 的 wait_for 超时就是这条路径）与未预期异常同样要在
        # finally 补一条 unknown：请求已经发出，上游可能已经算过钱，「没记」不等于
        # 「没花」；记 0 更糟——那是「确实没花」的意思。
        recorded = False
        try:
            for attempt in range(self.settings.llm_max_retries + 1):
                calls = attempt + 1
                try:
                    async with upstream_slot():
                        resp = await client.post(url, headers=self._headers(self.settings.llm_api_key), json=payload)
                    resp.raise_for_status()
                    data = resp.json()
                    # 先确保内容解析成功再记账：畸形响应不该记成一笔成功花费。
                    content = data["choices"][0]["message"]["content"]
                    self._record_usage(
                        usage, stage, self.settings.model_name, data.get("usage"), started, calls, kind,
                    )
                    recorded = True
                    return content
                except Exception as exc:
                    if attempt >= self.settings.llm_max_retries or not is_retriable_llm_error(exc):
                        self._record_usage(usage, stage, self.settings.model_name, None, started, calls, kind)
                        recorded = True
                        raise
                    await asyncio.sleep(
                        llm_retry_delay_seconds(attempt + 1, self.settings.llm_retry_base_seconds)
                    )
            raise AssertionError("unreachable")
        finally:
            if not recorded:
                self._record_usage(
                    usage, stage, self.settings.model_name, None, started, max(calls, 1), kind,
                )

    async def stream_chat(
        self,
        messages: List[Dict[str, str]],
        *,
        usage: Optional[UsageRecorder] = None,
        stage: str = "",
        kind: str = KIND_LLM,
    ) -> AsyncIterator[str]:
        """Yield OpenAI-compatible SSE deltas without buffering the completion."""
        if not self.settings.llm_api_base or not self.settings.llm_api_key:
            raise ModelConfigError("LLM API is not configured.")
        url = self.settings.llm_api_base.rstrip("/") + "/chat/completions"
        base_payload = {"model": self.settings.model_name, "messages": messages, "temperature": 0.2,
                        "max_tokens": self.settings.llm_max_tokens, "stream": True}
        payload: Dict[str, Any] = base_payload
        if self.settings.llm_stream_usage_enabled:
            # 流式默认不带 usage，要显式索取；尾帧是 choices 为空、只带 usage 的那一帧。
            payload = {**base_payload, "stream_options": {"include_usage": True}}
        sink: Dict[str, Any] = {}
        started = time.monotonic()
        calls = 0
        try:
            calls += 1
            try:
                async for delta in self._stream_deltas(url, payload, sink):
                    yield delta
            except httpx.HTTPStatusError as exc:
                # 上游不认 stream_options：去掉它重来一次。拿不到用量只能记未知，
                # 但绝不允许因为要计量就让用户的请求失败。
                #
                # 必须核对 400 的正文是不是真的冲着这个字段来的：把「上下文超长」
                # 之类的 400 也重试一遍，等于给每个真实错误白送一次上游调用。
                if "stream_options" not in payload or exc.response.status_code != 400:
                    raise
                if not await self._rejected_stream_options(exc.response):
                    raise
                logger.warning(json.dumps({
                    "event": "llm_stream_options_rejected",
                    "status": exc.response.status_code,
                }))
                payload = base_payload
                calls += 1
                async for delta in self._stream_deltas(url, base_payload, sink):
                    yield delta
        finally:
            # 放在 finally：消费方提前 break/aclose 时请求已经发出，账不能丢。
            self._record_usage(
                usage, stage, self.settings.model_name, sink.get("usage"), started, calls, kind,
            )

    @staticmethod
    async def _rejected_stream_options(response: httpx.Response) -> bool:
        """400 的正文是否指向 stream_options/include_usage 字段。

        流式响应体默认未读，需要显式 aread；读不出来一律当作「不是」——
        宁可把错误原样抛给调用方，也不要瞎重试。
        """
        try:
            body = response.text
        except httpx.ResponseNotRead:
            try:
                await response.aread()
            except Exception:
                return False
            try:
                body = response.text
            except Exception:
                return False
        lowered = body.lower()
        return "stream_options" in lowered or "include_usage" in lowered

    async def _stream_deltas(
        self,
        url: str,
        payload: Dict[str, Any],
        sink: Dict[str, Any],
    ) -> AsyncIterator[str]:
        """单次流式请求：yield 增量文本，并把 usage 尾帧写进 ``sink``。"""
        client = self._get_llm_client()
        async with client.stream("POST", url, headers=self._headers(self.settings.llm_api_key), json=payload) as resp:
            resp.raise_for_status()
            async for line in resp.aiter_lines():
                if not line.startswith("data:"):
                    continue
                raw = line[5:].strip()
                if raw == "[DONE]":
                    return
                try:
                    data = json.loads(raw)
                except json.JSONDecodeError:
                    continue
                frame_usage = data.get("usage")
                if isinstance(frame_usage, Mapping):
                    sink["usage"] = frame_usage
                try:
                    delta = data.get("choices", [{}])[0].get("delta", {}).get("content") or ""
                except (IndexError, AttributeError):
                    delta = ""
                if delta:
                    yield delta
