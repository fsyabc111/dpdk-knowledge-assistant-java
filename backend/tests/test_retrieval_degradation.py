"""语义腿降级（roadmap B1 健壮性）：embedding 上游失败时查询不得整失败，
退回纯词法/路径候选并记结构化日志 query_embedding_failed。"""
from __future__ import annotations

import logging

import pytest

from app.services.retrieval_service import RetrievalService


class _FailEmbedding:
    async def embed(self, texts, *, usage=None, stage=""):
        raise RuntimeError("upstream 401")


class _AsIsRewriter:
    async def rewrite(self, original, conversation_context=None):
        return original


class _LexicalOnlyStore:
    """词法腿有一个候选、其余腿皆空的 fake store（行结构同 PG store）。"""

    def fetch_fts_candidate_ids(self, match_query, limit):
        return ["c1"] if match_query else []

    def fetch_path_candidate_ids(self, fragments, limit):
        return []

    def fetch_semantic_candidate_ids(self, query_embedding, limit):
        return []

    def fetch_fallback_candidate_ids(self):
        return []

    def fetch_rows_by_ids(self, chunk_ids):
        return [
            {"id": chunk_id, "source_path": "engine_dns.cpp", "section": "parse_qname",
             "title": "engine_dns.cpp", "content": "dns qname 标签序列编码 parse_qname",
             "metadata_json": "{}", "embedding_json": "[]"}
            for chunk_id in chunk_ids
        ]

    def get_chunk(self, chunk_id):
        return None


class _Capture(logging.Handler):
    def __init__(self) -> None:
        super().__init__()
        self.messages: list[str] = []

    def emit(self, record: logging.LogRecord) -> None:
        self.messages.append(record.getMessage())


async def test_embedding_failure_degrades_to_lexical_with_structured_log():
    # 直接挂 handler 到 assistant logger，与 propagate 配置无关。
    logger = logging.getLogger("assistant")
    capture = _Capture()
    logger.addHandler(capture)
    try:
        service = RetrievalService(
            query_rewriter=_AsIsRewriter(),
            embedding_model=_FailEmbedding(),
            retrieval_store=_LexicalOnlyStore(),
        )
        chunks = await service.search("dns qname 怎么编码", limit=6)
    finally:
        logger.removeHandler(capture)
    assert chunks and chunks[0].id == "c1"  # 词法候选正常返回，未抛错
    assert any('"event": "query_embedding_failed"' in message for message in capture.messages)
    assert any('"error_type": "RuntimeError"' in message for message in capture.messages)


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
