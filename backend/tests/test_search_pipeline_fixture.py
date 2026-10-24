"""search() 主路径离线 fixture 测试（roadmap C-1）。

用预置 embedding 小语料 +「真实行为」fake store（子串匹配 FTS、路径片段、
cosine 排序都在测试内自洽实现），把 `_fetch_candidate_rows → RRF 融合 →
_retrieve_sync 重排 → MMR 多样性` 整条主路径搬进默认集——只有 DB/LLM/embedding
网络被 fake，排序与多样性逻辑全是真实代码。B3 之后权重可调，本文件是调参的
离线回归网。

语义侧约定：词表维度的 one-hot-ish 向量（词在文本出现即该维 1.0），cosine
差异由词重叠决定；每个用例用不同查询词，避免类级 `_embedding_cache` 跨用例
串扰。
"""
from __future__ import annotations

import json
import re

import pytest

from app.config import get_settings
from app.services.retrieval_service import RetrievalService
from app.services.vector_math import cosine_similarity

_VOCAB = ["dns", "parse", "port", "tls", "cert", "logging", "event", "batch", "flush", "session"]


def _vec(text: str) -> list[float]:
    lower = text.lower()
    return [1.0 if word in lower else 0.0 for word in _VOCAB]


class _VocabEmbedding:
    async def embed(self, texts, *, usage=None, stage=""):
        return [_vec(text) for text in texts]


class _FailEmbedding:
    async def embed(self, texts, *, usage=None, stage=""):
        raise RuntimeError("upstream down")


class _AsIsRewriter:
    async def rewrite(self, original, conversation_context=None):
        return original


def _chunk(chunk_id, source_path, section, content):
    return {
        "id": chunk_id, "source_path": source_path, "section": section, "title": section or source_path,
        "content": content, "vector": _vec(f"{content} {source_path} {section}"),
    }


# 小语料：协议实现 ×2、主题文档 ×2、近重复簇 ×3（MMR 用）、弱相关 ×1
_CORPUS = [
    _chunk("dns1", "engine_dns.cpp", "parse_qname", "DNS 协议解析 parse_qname 域名标签序列编码"),
    _chunk("dns2", "engine_dns.cpp", "can_handle", "dns can_handle port 53 识别"),
    _chunk("tls1", "engine_tls_infra.cpp", "cert", "tls 证书提取 cert_extractor 调用链"),
    _chunk("log1", "faq.md", "", "日志系统 设计 logging 轮转 说明"),
    _chunk("big1", "big.c", "fn_0", "event 批处理 flush 逻辑 batch_flush 阶段零"),
    _chunk("big2", "big.c", "fn_1", "event 批处理 flush 逻辑 batch_flush 阶段一"),
    _chunk("big3", "big.c", "fn_2", "event 批处理 flush 逻辑 batch_flush 阶段二"),
    _chunk("sess1", "session.c", "aging", "event 流表老化 session_entry 五元组"),
]


class _MiniCorpusStore:
    """行结构同 PG store（metadata_json/embedding_json 字符串），匹配行为自洽。"""

    def fetch_fts_candidate_ids(self, match_query, limit):
        terms = re.findall(r'"([^"]+)"', match_query or "")
        scored = []
        for row in _CORPUS:
            hits = sum(1 for term in terms if term.lower() in (row["content"] + " " + row["source_path"]).lower())
            if hits:
                scored.append((row["id"], hits))
        scored.sort(key=lambda item: (-item[1], item[0]))  # 命中数降序、id 升序定序
        return [chunk_id for chunk_id, _ in scored[:limit]]

    def fetch_path_candidate_ids(self, fragments, limit):
        matched = [row for row in _CORPUS
                   if any(fragment.lower() in row["source_path"].lower() for fragment in fragments)]
        matched.sort(key=lambda row: (row["source_path"], row["id"]))
        return [row["id"] for row in matched[:limit]]

    def fetch_semantic_candidate_ids(self, query_embedding, limit):
        if not query_embedding:
            return []
        scored = [(row["id"], cosine_similarity(query_embedding, row["vector"])) for row in _CORPUS]
        scored = [item for item in scored if item[1] > 0]
        scored.sort(key=lambda item: (-item[1], item[0]))
        return [chunk_id for chunk_id, _ in scored[:limit]]

    def fetch_fallback_candidate_ids(self):
        return []  # 与 li 数据面一致：无证据不硬凑

    def fetch_rows_by_ids(self, chunk_ids):
        by_id = {row["id"]: row for row in _CORPUS}
        return [
            {"id": row["id"], "source_path": row["source_path"], "section": row["section"],
             "title": row["title"], "content": row["content"],
             "metadata_json": "{}", "embedding_json": json.dumps(row["vector"])}
            for chunk_id in chunk_ids if (row := by_id.get(chunk_id))
        ]

    def get_chunk(self, chunk_id):
        return None


def _service(fail_embedding: bool = False) -> RetrievalService:
    get_settings.cache_clear()
    return RetrievalService(
        query_rewriter=_AsIsRewriter(),
        embedding_model=_FailEmbedding() if fail_embedding else _VocabEmbedding(),
        retrieval_store=_MiniCorpusStore(),
    )


async def test_pipeline_topic_query_ranks_protocol_impl_first():
    chunks = await _service().search("dns 域名解析", limit=6)
    assert chunks, "应有召回"
    assert chunks[0].source_path == "engine_dns.cpp"
    assert {chunk.source_path for chunk in chunks} >= {"engine_dns.cpp"}
    # 主题词命中把无关文档（faq/tls）压在协议实现之后
    sources = [chunk.source_path for chunk in chunks]
    assert sources.index("engine_dns.cpp") < sources.index("faq.md") if "faq.md" in sources else True


async def test_pipeline_explicit_filename_dominates():
    chunks = await _service().search("engine_tls_infra.cpp 证书提取", limit=6)
    assert chunks[0].source_path == "engine_tls_infra.cpp"


async def test_pipeline_mmr_caps_near_duplicate_cluster():
    chunks = await _service().search("event 批处理 flush", limit=6)
    big = [chunk for chunk in chunks if chunk.source_path == "big.c"]
    assert 1 <= len(big) <= 2, "近重复簇不得淹没结果（MMR 同文件上限）"
    assert any(chunk.source_path == "session.c" for chunk in chunks), "弱相关他文文件应保留一席"


async def test_pipeline_embedding_failure_still_ranks_lexically():
    chunks = await _service(fail_embedding=True).search("dns 解析", limit=6)
    assert chunks, "语义腿缺席时词法/主题信号仍应给出结果"
    assert chunks[0].source_path == "engine_dns.cpp"


async def test_pipeline_no_evidence_returns_empty():
    chunks = await _service().search("zzqqx 无中无", limit=6)
    assert chunks == [], "零证据不得兜底硬凑（与 li 数据面 fallback=[] 契约一致）"


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
