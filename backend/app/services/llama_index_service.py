"""LlamaIndex-owned PostgreSQL index and DPDK-aware retriever.

S4 收敛后这是唯一生产检索数据面：版本化 ``data_li_*`` 表 + 活动发布固定。
旧 ``knowledge_chunks`` 表仅作历史引用回读（storage/retrieval_store.py 的
``get_historical_chunk``），不再参与候选召回。本模块使用 LlamaIndex
Node/Index/Retriever 抽象，并沿通用 RetrievalService 保住路径/符号感知的
候选选择与启发式重排。
"""
from __future__ import annotations

import asyncio
import json
import logging
import re
import time
from threading import RLock
from typing import Any, Dict, List, Optional

from ..config import get_settings
from ..core.async_cache import AsyncTTLCache
from ..core.llm_client import is_retriable_llm_error, llm_retry_delay_seconds
from ..core.metrics import record_llm_call
from ..core.usage import SOURCE_CACHE_HIT, record_zero_call
from ..db import get_conn
from ..rag.providers import build_embedding_model, usage_payload_from_response
from ..rag.types import AnswerDraft, RetrievedChunk
from .retrieval_service import RetrievalService

_SQL_IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")

logger = logging.getLogger("assistant")


def _data_table_name(settings=None) -> str:
    """Return the physical PGVectorStore table name after validating config."""
    configured = (settings or get_settings()).llama_index_table
    if not _SQL_IDENTIFIER.fullmatch(configured):
        raise ValueError("LLAMA_INDEX_TABLE must be a PostgreSQL identifier")
    return f"data_{configured}"


def _quoted_data_table(settings=None) -> str:
    return f'"{_data_table_name(settings)}"'


def _metadata(value: Any) -> Dict[str, Any]:
    if isinstance(value, dict):
        return value
    return json.loads(value or "{}")


def _llama_source_sync(node_id: str) -> Optional[Dict[str, Any]]:
    table = _quoted_data_table()
    with get_conn() as conn:
        row = conn.execute(
            f"SELECT node_id, text, metadata_::text AS metadata_json "
            f"FROM {table} WHERE node_id = %s LIMIT 1",
            (node_id,),
        ).fetchone()
    if not row:
        return None
    metadata = _metadata(row["metadata_json"])
    source_path = metadata.get("source_path", "")
    return {
        "chunk_id": row["node_id"],
        "source_path": source_path,
        "title": metadata.get("title") or source_path,
        "section": metadata.get("section", ""),
        "content": row["text"],
        "metadata": metadata,
    }


async def get_llama_index_source(node_id: str) -> Optional[Dict[str, Any]]:
    """Read a cited LlamaIndex node through the bounded application DB pool."""
    return await asyncio.to_thread(_llama_source_sync, node_id)


class _LlamaIndexRuntime:
    """One pgvector store/index per process, not per HTTP request.

    PGVectorStore lazily creates both a SQLAlchemy sync engine and an async
    engine. Creating it per request turns one burst of N requests into N
    independent connection pools, which exhausts PostgreSQL before the model
    can answer. This runtime owns exactly one bounded pool.
    """

    _lock = RLock()
    _store = None
    _index = None

    @classmethod
    def get_index(cls):
        with cls._lock:
            if cls._index is None:
                from llama_index.core import VectorStoreIndex

                settings = get_settings()
                embedding = build_embedding_model(settings)
                native_embedding = getattr(embedding, "_embed_model", None)
                if native_embedding is None:
                    raise RuntimeError("LlamaIndex semantic retrieval requires the LlamaIndex embedding provider.")
                store = _new_pgvector_store(settings, perform_setup=False)
                # PGVectorStore's lazy _initialize() is not concurrency-safe:
                # every simultaneous first query can otherwise create a fresh
                # SQLAlchemy engine. Initialize once while holding the lock.
                store._initialize()
                cls._store = store
                cls._index = VectorStoreIndex.from_vector_store(store, embed_model=native_embedding)
            return cls._index

    @classmethod
    async def close(cls) -> None:
        with cls._lock:
            store, cls._store, cls._index = cls._store, None, None
        if store is None:
            return
        engine = getattr(store, "_engine", None)
        if engine is not None:
            engine.dispose()
        async_engine = getattr(store, "_async_engine", None)
        if async_engine is not None:
            await async_engine.dispose()


def _new_pgvector_store(settings, perform_setup: bool):
    from llama_index.vector_stores.postgres import PGVectorStore
    # A bounded async pool makes extra requests wait instead of opening an
    # unbounded number of PostgreSQL clients. ``max_overflow=0`` is essential.
    engine_options = {
        "pool_size": settings.llama_index_db_pool_size,
        "max_overflow": 0,
        "pool_timeout": 30,
        "pool_pre_ping": True,
    }
    # HNSW 对照实验开关（默认关 = 精确检索）。开启后 perform_setup=True 的
    # ingest 路径会幂等确保 {table}_embedding_idx 索引（与 alembic 0009 同名）。
    hnsw_kwargs = None
    if settings.llama_index_hnsw_enabled:
        hnsw_kwargs = {
            "hnsw_m": settings.llama_index_hnsw_m,
            "hnsw_ef_construction": settings.llama_index_hnsw_ef_construction,
            "hnsw_ef_search": settings.llama_index_hnsw_ef_search,
            "hnsw_dist_method": "vector_cosine_ops",
        }
    return PGVectorStore.from_params(
        connection_string=_connection_string(), async_connection_string=_async_connection_string(),
        table_name=settings.llama_index_table,
        embed_dim=1024, hybrid_search=True, text_search_config=settings.llama_index_fts_config,
        use_jsonb=True,
        perform_setup=perform_setup, create_engine_kwargs=engine_options, hnsw_kwargs=hnsw_kwargs,
    )


def warm_llama_index_runtime() -> None:
    """Initialize the shared pgvector engines during application startup.

    li 数据面是 PG-only：非 PostgreSQL 后端（显式 SQLite 回退/测试库）没有
    可预热的向量存储，跳过并记日志；就绪探针仍会按活动发布如实报 503。
    """
    if get_settings().database_backend != "postgres":
        logger.info(json.dumps({
            "event": "llama_index_warmup_skipped",
            "reason": "non-postgres backend",
        }, ensure_ascii=False))
        return
    _LlamaIndexRuntime.get_index()


async def close_llama_index_runtime() -> None:
    await _LlamaIndexRuntime.close()


def _node_text(chunk: RetrievedChunk, index: int) -> str:
    """Keep source identity in the synthesizer context, not only in API JSON."""
    return (
        f"[{index}] source={chunk.source_path} section={chunk.section or chunk.title}\n"
        f"{chunk.content}"
    )


class _ChunkRetriever:
    """Per-request BaseRetriever that feeds already-ranked hybrid nodes to the query engine."""

    def __init__(self, chunks: List[RetrievedChunk]) -> None:
        from llama_index.core.retrievers import BaseRetriever
        from llama_index.core.schema import NodeWithScore, TextNode

        class _Retriever(BaseRetriever):
            def __init__(self, nodes):
                super().__init__()
                self.nodes = nodes

            def _retrieve(self, query_bundle):
                return self.nodes

            async def _aretrieve(self, query_bundle):
                return self.nodes

        self.nodes = [NodeWithScore(node=TextNode(
            id_=chunk.id, text=_node_text(chunk, index),
            metadata={"source_path": chunk.source_path, "section": chunk.section,
                      "title": chunk.title, **chunk.metadata}), score=chunk.score)
            for index, chunk in enumerate(chunks, start=1)]
        self.instance = _Retriever(self.nodes)


class LlamaIndexAnswerGenerator:
    """ResponseSynthesizer-backed answer generation for the llama_index engine."""

    # 与旧管线 GroundedAnswerGenerator 同形：非流式 generate 的答案缓存
    #（rag_answer_cache_enabled，默认关）；流式路径不缓存（与旧管线一致）。
    _answer_cache = AsyncTTLCache[str, str](ttl_seconds=300, maxsize=256)

    def __init__(self, settings, chat_model, system_prompt: str) -> None:
        from llama_index.core import PromptTemplate, get_response_synthesizer
        from llama_index.core.query_engine import RetrieverQueryEngine

        self.settings = settings
        self._system_prompt = system_prompt
        self.native_llm = getattr(chat_model, "_llm", None)
        if self.native_llm is None:
            raise RuntimeError("LlamaIndexAnswerGenerator requires the LlamaIndex chat adapter (RAG_PROVIDER=llama_index).")
        self._prompt = PromptTemplate(system_prompt + "\n\n"
            "以下是本轮检索到的参考上下文（包含来源编号；这是项目事实的唯一依据）：\n"
            "{context_str}\n\n"
            "当前问题及仅供消解指代的会话上下文：\n{query_str}\n\n"
            "请用中文回答。只引用本轮参考上下文中的 [编号] 和文件名；"
            "如果证据不足，请明确说明知识库未覆盖，不要把会话历史当作事实。")
        self._get_response_synthesizer = get_response_synthesizer
        self._query_engine_cls = RetrieverQueryEngine

    def _engine(self, chunks: List[RetrievedChunk], streaming: bool):
        from llama_index.core import get_response_synthesizer
        from llama_index.core.indices.prompt_helper import PromptHelper
        retriever = _ChunkRetriever(chunks).instance
        synthesizer = get_response_synthesizer(
            llm=self.native_llm, text_qa_template=self._prompt,
            # OpenAILike reports LlamaIndex's 3900-token fallback metadata.
            # Without an explicit helper, compact mode silently turns a
            # 15-node answer into multiple refine calls, severely increasing
            # latency and occasionally diluting citations.
            prompt_helper=PromptHelper(
                context_window=self.settings.llama_index_context_window,
                num_output=self.settings.llm_max_tokens,
            ),
            streaming=streaming, use_async=True,
        )
        return self._query_engine_cls(retriever=retriever, response_synthesizer=synthesizer)

    @staticmethod
    def _build_query(question, conversation_memory=None, retrieval_query=None) -> str:
        query = question
        if conversation_memory and conversation_memory.as_retrieval_context():
            query += "\n\n会话上下文（仅用于消解指代）：\n" + conversation_memory.as_retrieval_context()
        if retrieval_query and retrieval_query != question:
            query += "\n\n本轮检索改写：\n" + retrieval_query
        return query

    def _record_query_usage(self, usage, response, started: float, calls: int) -> None:
        """把 query engine 的用量交给账本。取不到计数就是未知——绝不写 0。"""
        if usage is None:
            return
        usage.record_llm(
            "answer", self.settings.model_name, usage_payload_from_response(response),
            duration_ms=int((time.monotonic() - started) * 1000), calls=calls,
        )

    async def _aquery_with_retry(self, chunks, query, streaming: bool, trace=None):
        """Retry the whole query on transient upstream failures.

        The native OpenAILike runs with max_retries=0 so that retry policy
        lives here and matches generate_with_attempts in rag/providers.py.
        """
        attempts = 0
        started = time.monotonic()
        usage = trace.usage if trace is not None else None
        # 只记一次；取消与未预期异常在 finally 补一条 unknown（请求已经发出）。
        recorded = False
        try:
            for attempt in range(self.settings.llm_max_retries + 1):
                try:
                    response = await self._engine(chunks, streaming=streaming).aquery(query)
                    attempts = attempt + 1
                    record_llm_call("query_engine", "success", time.monotonic() - started)
                    if trace is not None:
                        trace.set_meta(answer_generation_attempts=attempts)
                    self._record_query_usage(usage, response, started, attempts)
                    recorded = True
                    return response
                except Exception as exc:
                    if attempt >= self.settings.llm_max_retries or not is_retriable_llm_error(exc):
                        record_llm_call("query_engine", "error", time.monotonic() - started)
                        if usage is not None:
                            usage.record_llm(
                                "answer", self.settings.model_name, None,
                                duration_ms=int((time.monotonic() - started) * 1000),
                                calls=max(attempt + 1, 1),
                            )
                            recorded = True
                        raise
                    logger.warning(json.dumps({
                        "event": "llm_retry", "path": "query_engine",
                        "attempt": attempt + 1, "error_type": type(exc).__name__,
                    }))
                    await asyncio.sleep(
                        llm_retry_delay_seconds(attempt + 1, self.settings.llm_retry_base_seconds)
                    )
            raise AssertionError("unreachable")
        finally:
            if usage is not None and not recorded:
                usage.record_llm(
                    "answer", self.settings.model_name, None,
                    duration_ms=int((time.monotonic() - started) * 1000),
                    calls=max(attempts, 1),
                )

    def _answer_cache_key(self, question, chunks, conversation_memory=None, retrieval_query=None) -> str:
        """与旧管线同构的缓存键：模型 + 系统提示词 + 问题 + 改写 + 会话上下文 + 证据正文。"""
        memory_context = conversation_memory.as_retrieval_context() if conversation_memory else ""
        context = "\n\n".join(_node_text(chunk, index) for index, chunk in enumerate(chunks, start=1))
        return (f"{self.settings.model_name}\n{self._system_prompt}\n{question}\n"
                f"{retrieval_query or ''}\n{memory_context}\n{context}")

    async def generate(self, question, chunks, trace=None, conversation_memory=None, retrieval_query=None):
        query = self._build_query(question, conversation_memory, retrieval_query)
        if not self.settings.rag_answer_cache_enabled:
            response = await self._aquery_with_retry(chunks, query, streaming=False, trace=trace)
            answer = getattr(response, "response", "") or ""
        else:
            hit = True

            async def _generate() -> str:
                nonlocal hit
                hit = False
                response = await self._aquery_with_retry(chunks, query, streaming=False, trace=trace)
                return getattr(response, "response", "") or ""

            answer = await self._answer_cache.get_or_create(
                self._answer_cache_key(question, chunks, conversation_memory, retrieval_query), _generate)
            if trace is not None:
                # miss 时 attempts 已由 _aquery_with_retry 记录；hit 时零次模型调用。
                if hit:
                    record_zero_call(
                        trace.usage, "answer", SOURCE_CACHE_HIT, model=self.settings.model_name,
                    )
                trace.set_meta(answer_cache_hit=hit, **({"answer_generation_attempts": 0} if hit else {}))
        return AnswerDraft(answer=answer, confidence="high" if chunks else "low")

    async def stream(self, question, chunks, trace=None, conversation_memory=None, retrieval_query=None):
        query = self._build_query(question, conversation_memory, retrieval_query)
        attempts = 0
        started = time.monotonic()
        for attempt in range(self.settings.llm_max_retries + 1):
            # In streaming mode the upstream call only happens when the
            # response generator is consumed, so retry must cover the first
            # delta. Once a delta has been yielded the client already holds a
            # partial answer; mid-stream failures propagate to the caller's
            # degradation path instead of restarting the answer.
            try:
                response = await self._engine(chunks, streaming=True).aquery(query)
                generator = getattr(response, "async_response_gen", None)
                if callable(generator):
                    deltas = generator()
                    first = await deltas.__anext__()
                else:
                    first = getattr(response, "response_txt", None) or getattr(response, "response", "") or ""
                    deltas = None
                attempts = attempt + 1
            except StopAsyncIteration:
                attempts = attempt + 1
                record_llm_call("query_engine_stream", "success", time.monotonic() - started)
                if trace is not None:
                    trace.set_meta(answer_generation_attempts=attempts)
                self._record_query_usage(trace.usage if trace is not None else None,
                                         response, started, attempts)
                return
            except Exception as exc:
                if attempt >= self.settings.llm_max_retries or not is_retriable_llm_error(exc):
                    record_llm_call("query_engine_stream", "error", time.monotonic() - started)
                    raise
                logger.warning(json.dumps({
                    "event": "llm_retry", "path": "query_engine_stream",
                    "attempt": attempt + 1, "error_type": type(exc).__name__,
                }))
                await asyncio.sleep(
                    llm_retry_delay_seconds(attempt + 1, self.settings.llm_retry_base_seconds)
                )
                continue
            record_llm_call("query_engine_stream", "success", time.monotonic() - started)
            if trace is not None:
                trace.set_meta(answer_generation_attempts=attempts)
            if first:
                yield first
            try:
                if deltas is not None:
                    async for delta in deltas:
                        if delta:
                            yield delta
            finally:
                # 流式用量通常要等最后一帧才可见；消费方提前断开时同样留痕
                #（取不到计数就是 unknown），账不能因为断开而消失。
                self._record_query_usage(trace.usage if trace is not None else None,
                                         response, started, attempts)
            return



def _connection_string() -> str:
    url = get_settings().database_url
    if url.startswith("postgresql://"):
        return url.replace("postgresql://", "postgresql+psycopg2://", 1)
    return url


def _async_connection_string() -> str:
    url = get_settings().database_url
    if url.startswith("postgresql://"):
        return url.replace("postgresql://", "postgresql+asyncpg://", 1)
    return url.replace("+psycopg2://", "+asyncpg://", 1)


class HybridDpdkRetriever:
    """发布版本内单表召回；绑定会话时沿用数据库中的首次发布，否则仅实例内固定。"""

    def __init__(self) -> None:
        self.release_id: Optional[str] = None
        self.release_blocked = False
        self.session_id: Optional[str] = None

    async def retrieve(self, query: str, limit: int, trace=None, conversation_context=None):
        from ..storage.li_retrieval_store import LiNodeRetrievalStore
        from .rag_index_service import active_release, index_configuration, release_configuration
        if self.release_id is None and not self.release_blocked:
            if self.session_id:
                from .session_service import SessionService
                self.release_id = await asyncio.to_thread(SessionService().resolve_release, self.session_id)
            else:
                self.release_id = await asyncio.to_thread(active_release)
            if self.release_id:
                # 检索前强制核对：发布版本的 embedding 配置指纹必须与当前 Settings 一致，
                # 否则维度/模型错配会静默返回低质结果。fail-closed：返回空而非报错。
                configuration = await asyncio.to_thread(release_configuration, self.release_id)
                if configuration != index_configuration(get_settings()):
                    logger.warning(json.dumps({"event": "rag_release_config_mismatch",
                                               "release_id": self.release_id}, ensure_ascii=False))
                    self.release_blocked = True
        # fail-closed：无可用发布版本（含配置指纹不匹配）时不跨版本检索，
        # 返回空让上层如实报「资料不足/检索失败」，而不是拿错版本的结果充数。
        if self.release_blocked or not self.release_id:
            return []
        service = RetrievalService(
            retrieval_store=LiNodeRetrievalStore(_quoted_data_table(), self.release_id)
        )
        if trace is not None:
            trace.set_meta(release_id=self.release_id)
        return await service.search(query, limit, trace, conversation_context)


class LlamaIndexRetrievalAdapter:
    """Expose the LlamaIndex BaseRetriever through the application's retriever contract."""

    def __init__(self) -> None:
        self._retriever = HybridDpdkRetriever()

    def bind_session(self, session_id: Optional[str]) -> None:
        """每次编排绑定已通过归属检查的会话；重用适配器时清除前一会话状态。"""
        if self._retriever.session_id != session_id:
            self._retriever = HybridDpdkRetriever()
            self._retriever.session_id = session_id

    async def search(self, query: str, limit: int = 6, trace=None, conversation_context=None) -> List[RetrievedChunk]:
        return await self._retriever.retrieve(query, limit, trace, conversation_context)

    async def get_chunk(self, chunk_id: str) -> Optional[Dict[str, Any]]:
        return await get_llama_index_source(chunk_id)


class LlamaIndexIngestionService:
    """固定输入快照、完整构建、原子发布；不使用内容 hash。"""

    def __init__(self) -> None:
        self.settings = get_settings()

    async def ingest(self) -> dict:
        from ..db import init_db
        from ..storage.li_retrieval_store import live_fts_regconfig
        from .rag_index_service import ingest_release
        await asyncio.to_thread(init_db)
        store = _new_pgvector_store(self.settings, perform_setup=True)
        try:
            await asyncio.to_thread(store._initialize)
            # 存量表的生成列 regconfig 由建表/alembic 0010 固化，llama-index 对已有
            # 表不改列。设置与存活列不一致时继续 ingest 会让 FTS 腿口径错配，
            # 硬失败并指向迁移路径。
            live = await asyncio.to_thread(live_fts_regconfig, _quoted_data_table())
            if live is not None and live != self.settings.llama_index_fts_config:
                raise ValueError(
                    f"text_search_tsv 的 regconfig（{live}）与 LLAMA_INDEX_FTS_CONFIG"
                    f"（{self.settings.llama_index_fts_config}）不一致：请先 alembic upgrade head 迁移后再 ingest"
                )
            return await ingest_release(self.settings, _quoted_data_table())
        finally:
            engine = getattr(store, "_engine", None)
            if engine is not None:
                engine.dispose()
            async_engine = getattr(store, "_async_engine", None)
            if async_engine is not None:
                await async_engine.dispose()
