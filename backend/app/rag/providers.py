import asyncio
import inspect
import json
import logging
import time
from threading import Lock
from typing import Any, AsyncIterator, Dict, List, Mapping, Optional, Tuple

from ..config import Settings
from ..core.llm_client import (
    ModelConfigError,
    OpenAICompatibleClient,
    is_retriable_llm_error,
    llm_retry_delay_seconds,
)
from ..core.metrics import record_llm_call
from ..core.usage import UsageRecorder

logger = logging.getLogger("assistant")


_model_lock = Lock()
_chat_models: Dict[Tuple[str, str, str, str], object] = {}
_embedding_models: Dict[Tuple[str, str, str, str], object] = {}


def usage_payload_from_response(response: object) -> Optional[Mapping[str, Any]]:
    """从 LlamaIndex 响应里取 usage（取不到返回 None = 未知，绝不返回 0）。

    LlamaIndex 各版本把用量放在 ``raw``（OpenAI 原样响应）或 ``usage``
    （较新的 token 计数对象）里，两者取到即用。chat 适配器与 query engine
    两条路共用这一份判断，避免两处口径漂移。
    """
    raw = getattr(response, "raw", None)
    if isinstance(raw, Mapping):
        candidate = raw.get("usage")
        if isinstance(candidate, Mapping):
            return candidate
    token_counts = getattr(response, "usage", None)
    if token_counts is not None:
        return {
            "prompt_tokens": getattr(token_counts, "prompt_tokens", None),
            "completion_tokens": getattr(token_counts, "completion_tokens", None),
            "total_tokens": getattr(token_counts, "total_tokens", None),
        }
    return None


class DirectOpenAIChatModel:
    def __init__(self, settings: Settings) -> None:
        self.client = OpenAICompatibleClient(settings)

    async def generate(
        self,
        messages: List[Dict[str, str]],
        *,
        usage: Optional[UsageRecorder] = None,
        stage: str = "",
    ) -> str:
        return await self.client.chat(messages, usage=usage, stage=stage)

    async def stream(
        self,
        messages: List[Dict[str, str]],
        *,
        usage: Optional[UsageRecorder] = None,
        stage: str = "",
    ) -> AsyncIterator[str]:
        async for delta in self.client.stream_chat(messages, usage=usage, stage=stage):
            yield delta


class DirectOpenAIEmbeddingModel:
    def __init__(self, settings: Settings) -> None:
        self.client = OpenAICompatibleClient(settings)

    async def embed(
        self,
        texts: List[str],
        *,
        usage: Optional[UsageRecorder] = None,
        stage: str = "",
    ) -> List[List[float]]:
        return await self.client.embed(texts, usage=usage, stage=stage)


class LlamaIndexChatModel:
    """LlamaIndex-backed OpenAI-compatible chat model adapter."""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        if not settings.llm_api_base or not settings.llm_api_key:
            raise ModelConfigError("LLM API is not configured. Set LLM_API_BASE and LLM_API_KEY in .env.")
        try:
            from llama_index.core.llms import ChatMessage
            from llama_index.llms.openai_like import OpenAILike
        except ImportError as exc:
            raise ModelConfigError(
                "LlamaIndex chat provider is not installed. "
                "Install llama-index-core and llama-index-llms-openai-like, or set RAG_PROVIDER=direct."
            ) from exc

        self._chat_message_cls = ChatMessage
        self._llm = OpenAILike(
            model=settings.model_name,
            api_base=settings.llm_api_base.rstrip("/"),
            api_key=settings.llm_api_key,
            is_chat_model=True,
            temperature=0.2,
            max_tokens=settings.llm_max_tokens,
            # Retrying is handled below so both adapters follow the same
            # policy and traceable attempt count.
            max_retries=0,
            timeout=90.0,
            async_http_client=OpenAICompatibleClient.get_llm_http_client(),
        )

    async def generate_with_attempts(
        self,
        messages: List[Dict[str, str]],
        *,
        usage: Optional[UsageRecorder] = None,
        stage: str = "",
    ) -> tuple[str, int]:
        chat_messages = [
            self._chat_message_cls(role=item["role"], content=item["content"])
            for item in messages
        ]
        started = time.monotonic()
        calls = 0
        # 只记一次；取消（judge 的超时即走这条路径）与未预期异常在 finally 补一条
        # unknown。请求已经发出，账上不能当它没发生。
        recorded = False
        try:
            for attempt in range(self.settings.llm_max_retries + 1):
                calls = attempt + 1
                try:
                    response = await self._llm.achat(chat_messages)
                    record_llm_call("chat_model", "success", time.monotonic() - started)
                    self._record_usage(usage, stage, response, started, calls)
                    recorded = True
                    return response.message.content or "", calls
                except Exception as exc:
                    if attempt >= self.settings.llm_max_retries or not is_retriable_llm_error(exc):
                        record_llm_call("chat_model", "error", time.monotonic() - started)
                        # 重试耗尽仍是未知用量：记 unknown，不记 0、不成一笔成功计费。
                        if usage is not None:
                            usage.record_llm(stage, self.settings.model_name, None,
                                             duration_ms=int((time.monotonic() - started) * 1000), calls=calls)
                            recorded = True
                        raise
                    logger.warning(json.dumps({
                        "event": "llm_retry", "path": "chat_model",
                        "attempt": attempt + 1, "error_type": type(exc).__name__,
                    }))
                    await asyncio.sleep(
                        llm_retry_delay_seconds(attempt + 1, self.settings.llm_retry_base_seconds)
                    )

            raise AssertionError("unreachable")
        finally:
            if usage is not None and not recorded:
                usage.record_llm(stage, self.settings.model_name, None,
                                 duration_ms=int((time.monotonic() - started) * 1000),
                                 calls=max(calls, 1))

    def _record_usage(
        self,
        usage: Optional[UsageRecorder],
        stage: str,
        response: object,
        started: float,
        calls: int,
    ) -> None:
        if usage is None:
            return
        usage.record_llm(
            stage, self.settings.model_name, usage_payload_from_response(response),
            duration_ms=int((time.monotonic() - started) * 1000), calls=calls,
        )

    async def generate(
        self,
        messages: List[Dict[str, str]],
        *,
        usage: Optional[UsageRecorder] = None,
        stage: str = "",
    ) -> str:
        answer, _ = await self.generate_with_attempts(messages, usage=usage, stage=stage)
        return answer

    async def stream(
        self,
        messages: List[Dict[str, str]],
        *,
        usage: Optional[UsageRecorder] = None,
        stage: str = "",
    ) -> AsyncIterator[str]:
        chat_messages = [self._chat_message_cls(role=item["role"], content=item["content"]) for item in messages]
        started = time.monotonic()
        # 用量只在最后一帧出现，而消费方可能提前 break（GeneratorExit 会绕过正常
        # 返回路径），所以统一在 finally 收口：只要请求发出过就一定留痕。
        done = False
        try:
            try:
                response_gen = self._llm.astream_chat(chat_messages)
                # LlamaIndex's instrumentation wrapper is version-dependent: some
                # releases return an async generator directly, others return an
                # awaitable resolving to one.
                if inspect.isawaitable(response_gen):
                    response_gen = await response_gen
                last: object = None
                async for response in response_gen:
                    last = response
                    delta = getattr(response, "delta", None) or ""
                    if delta:
                        yield delta
                self._record_usage(usage, stage, last, started, 1)
                done = True
            except (AttributeError, NotImplementedError):
                # Some OpenAI-compatible gateways advertise no streaming support.
                # 记账交给 generate 自己（它走的是非流式路径）。
                done = True
                yield await self.generate(messages, usage=usage, stage=stage)
        finally:
            if not done and usage is not None:
                usage.record_llm(stage, self.settings.model_name, None,
                                 duration_ms=int((time.monotonic() - started) * 1000), calls=1)


class LlamaIndexEmbeddingModel:
    """LlamaIndex-backed OpenAI-compatible embedding model adapter."""

    def __init__(self, settings: Settings) -> None:
        if not settings.embedding_api_base or not settings.embedding_api_key:
            raise ModelConfigError(
                "Embedding API is not configured. Set EMBEDDING_API_BASE and EMBEDDING_API_KEY in .env."
            )
        try:
            from llama_index.core.embeddings import BaseEmbedding
            from pydantic import PrivateAttr
        except ImportError as exc:
            raise ModelConfigError(
                "LlamaIndex core embedding provider is not installed. "
                "Install llama-index-core, or set RAG_PROVIDER=direct."
            ) from exc

        class OpenAICompatibleEmbedding(BaseEmbedding):
            _client: OpenAICompatibleClient = PrivateAttr()

            def __init__(self, client: OpenAICompatibleClient, model_name: str) -> None:
                super().__init__(model_name=model_name)
                self._client = client

            def _get_query_embedding(self, query: str) -> List[float]:
                raise NotImplementedError("Use async embedding calls in this application.")

            async def _aget_query_embedding(self, query: str) -> List[float]:
                embeddings = await self._client.embed([query])
                return embeddings[0] if embeddings else []

            def _get_text_embedding(self, text: str) -> List[float]:
                raise NotImplementedError("Use async embedding calls in this application.")

            async def _aget_text_embedding(self, text: str) -> List[float]:
                embeddings = await self._client.embed([text])
                return embeddings[0] if embeddings else []

            async def _aget_text_embeddings(self, texts: List[str]) -> List[List[float]]:
                return await self._client.embed(texts)

        self._client = OpenAICompatibleClient(settings)
        self._embed_model = OpenAICompatibleEmbedding(
            client=self._client,
            model_name=settings.embedding_model_name,
        )

    async def embed(
        self,
        texts: List[str],
        *,
        usage: Optional[UsageRecorder] = None,
        stage: str = "",
    ) -> List[List[float]]:
        # Keep batching/rate limiting in the shared compatible client. Calling
        # LlamaIndex's batch helper here would schedule every node at once and
        # bypass DashScope's 10-item request limit.
        return await self._client.embed(texts, usage=usage, stage=stage)


def _chat_model_key(settings: Settings) -> Tuple[str, str, str, str]:
    return (settings.rag_provider, settings.llm_api_base, settings.model_name, settings.llm_api_key)


def _embedding_model_key(settings: Settings) -> Tuple[str, str, str, str]:
    return (
        settings.rag_provider,
        settings.embedding_api_base,
        settings.embedding_model_name,
        settings.embedding_api_key,
    )


def clear_model_caches() -> None:
    with _model_lock:
        _chat_models.clear()
        _embedding_models.clear()


def build_chat_model(settings: Settings):
    key = _chat_model_key(settings)
    with _model_lock:
        cached = _chat_models.get(key)
        if cached is not None:
            return cached
        model: object
        if settings.rag_provider == "direct":
            model = DirectOpenAIChatModel(settings)
        elif settings.rag_provider == "llama_index":
            model = LlamaIndexChatModel(settings)
        else:
            raise ValueError(f"Unsupported RAG_PROVIDER: {settings.rag_provider}")
        _chat_models[key] = model
        return model


def build_embedding_model(settings: Settings):
    key = _embedding_model_key(settings)
    with _model_lock:
        cached = _embedding_models.get(key)
        if cached is not None:
            return cached
        model: object
        if settings.rag_provider == "direct":
            model = DirectOpenAIEmbeddingModel(settings)
        elif settings.rag_provider == "llama_index":
            model = LlamaIndexEmbeddingModel(settings)
        else:
            raise ValueError(f"Unsupported RAG_PROVIDER: {settings.rag_provider}")
        _embedding_models[key] = model
        return model


# Backward-compatible aliases for older imports and tests.
OpenAIChatModel = DirectOpenAIChatModel
OpenAIEmbeddingModel = DirectOpenAIEmbeddingModel
