"""B3 启发式权重进 Settings 的默认集单测。

覆盖四组 23 个旋钮：Settings 校验、boosts 四函数自定义读取、合并权重公式
（语义腿在/缺两分支）、RRF 先验与 rrf_k 接线、MMR 同文件上限。行为保持
（默认值=线上现值）由现有测试原样全绿证明（test_rag_interfaces.py 多样性
用例、test_retrieval_degradation.py 等）。
"""
from __future__ import annotations

import pytest

from app.config import Settings, get_settings
from app.rag.types import RetrievedChunk
from app.services.retrieval_service import (
    CandidateRanks,
    RetrievalService,
    definition_boost,
    filename_boost,
    identifier_score,
    topic_boost,
)

# ---------------------------------------------------------------------------
# Settings 校验与默认值
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("key,value", [
    ("RETRIEVAL_W_SEMANTIC", "-0.1"),
    ("RETRIEVAL_TOPIC_BOOST_SPECIFIC", "-1"),
    ("RETRIEVAL_IDENT_BODY_SCORE", "-0.01"),
    ("RETRIEVAL_RRF_K", "0"),
    ("RETRIEVAL_MMR_PER_FILE_CAP", "0"),
])
def test_weight_validation_rejects_bad_values(monkeypatch, key, value):
    monkeypatch.setenv(key, value)
    with pytest.raises(ValueError, match=key):
        Settings(_env_file=None)


def test_weight_defaults_match_legacy_constants(monkeypatch):
    for name in dir(Settings):
        if name.startswith("retrieval_"):
            monkeypatch.delenv(name.upper(), raising=False)
    settings = Settings(_env_file=None)
    # 默认值=线上现值是「行为不变」的契约，逐项锁定。
    assert settings.retrieval_w_semantic == 0.38
    assert settings.retrieval_w_lexical == 0.24
    assert settings.retrieval_w_ident == 0.18
    assert settings.retrieval_w_lexical_no_semantic == 0.46
    assert settings.retrieval_w_ident_no_semantic == 0.26
    assert settings.retrieval_rrf_k == 60
    assert settings.retrieval_rrf_prior_scale == 14.0
    assert settings.retrieval_rrf_prior_cap == 0.22
    assert settings.retrieval_mmr_redundancy_penalty == 0.18
    assert settings.retrieval_mmr_per_file_penalty == 0.04
    assert settings.retrieval_mmr_per_file_cap == 2
    assert settings.retrieval_filename_boost_exact == 0.50
    assert settings.retrieval_filename_boost_partial == 0.30
    assert settings.retrieval_topic_boost_specific == 0.95
    assert settings.retrieval_topic_boost_generic == 0.85
    assert settings.retrieval_topic_boost_specific_bare == 0.90
    assert settings.retrieval_topic_boost_generic_bare == 0.80
    assert settings.retrieval_topic_boost_specific_infix == 0.60
    assert settings.retrieval_topic_boost_generic_infix == 0.50
    assert settings.retrieval_ident_header_score == 1.0
    assert settings.retrieval_ident_body_score == 0.45
    assert settings.retrieval_definition_boost_typedef == 0.10
    assert settings.retrieval_definition_boost_funcsig == 0.04


# ---------------------------------------------------------------------------
# boosts 四函数读取 settings
# ---------------------------------------------------------------------------


def test_boost_functions_read_custom_settings():
    custom = Settings(
        _env_file=None,
        retrieval_filename_boost_exact=0.9, retrieval_filename_boost_partial=0.2,
        retrieval_topic_boost_specific=0.7, retrieval_topic_boost_generic_infix=0.15,
        retrieval_ident_header_score=0.8, retrieval_ident_body_score=0.3,
        retrieval_definition_boost_typedef=0.5, retrieval_definition_boost_funcsig=0.1,
    )
    # 文件名：endswith 精确 / 包含部分
    assert filename_boost("engine_dns.cpp 怎么解析", "engine_dns.cpp", custom) == 0.9
    assert filename_boost("engine_dns.cpp", "old/engine_dns.cpp.bak", custom) == 0.2
    # 主题：特定词 endswith 带扩展名 / 通用词 infix
    assert topic_boost("证书怎么提取", "tls/cert_extractor.c", custom) == 0.7
    assert topic_boost("ftp 协议", "src/ftp_pasv_registry.c", custom) == 0.15
    # 标识符：命中路径+栏目 / 仅正文
    assert identifier_score("parse_qname", "engine_dns.cpp", "parse_qname", "...", custom) == 0.8
    assert identifier_score("parse_qname", "x.c", "", "call parse_qname now", custom) == 0.3
    # 结构体/函数签名定义
    assert definition_boost("typedef struct {\n} x;", custom) == 0.5
    assert definition_boost("static inline int foo(void) {", custom) == 0.1


# ---------------------------------------------------------------------------
# 检索主路径接线（fake store/rewriter/embedding，模式同 test_retrieval_degradation.py）
# ---------------------------------------------------------------------------


class _AsIsRewriter:
    async def rewrite(self, original, conversation_context=None):
        return original


class _FixedEmbedding:
    """查询向量固定 [1,0]；empty=True 时返回空（语义腿缺席分支）。"""

    def __init__(self, empty: bool = False) -> None:
        self.empty = empty

    async def embed(self, texts, *, usage=None, stage=""):
        return [] if self.empty else [[1.0, 0.0] for _ in texts]


class _TwoChunkStore:
    """两候选：a 与查询同向（semantic=1.0），b 正交（semantic=0.0）。

    term 参数化是为了让每个用例的查询词不同——_embedding_cache 是类级
    缓存，同词会跨用例命中上一个场景的 embedding 结果。
    """

    def __init__(self, term: str = "alpha") -> None:
        self.term = term

    def fetch_fts_candidate_ids(self, match_query, limit):
        return ["a", "b"] if match_query else []

    def fetch_path_candidate_ids(self, fragments, limit):
        return []

    def fetch_semantic_candidate_ids(self, query_embedding, limit):
        return ["a", "b"] if query_embedding else []

    def fetch_fallback_candidate_ids(self):
        return []

    def fetch_rows_by_ids(self, chunk_ids):
        rows = {
            "a": {"id": "a", "source_path": "alpha.c", "section": "", "title": "alpha.c",
                  "content": f"{self.term} beta", "metadata_json": "{}", "embedding_json": "[1.0, 0.0]"},
            "b": {"id": "b", "source_path": "beta.c", "section": "", "title": "beta.c",
                  "content": f"{self.term} gamma", "metadata_json": "{}", "embedding_json": "[0.0, 1.0]"},
        }
        return [rows[chunk_id] for chunk_id in chunk_ids if chunk_id in rows]

    def get_chunk(self, chunk_id):
        return None


def _service(monkeypatch, empty_embedding: bool = False, term: str = "alpha", **env):
    for key, value in env.items():
        monkeypatch.setenv(key, str(value))
    get_settings.cache_clear()
    return RetrievalService(
        query_rewriter=_AsIsRewriter(),
        embedding_model=_FixedEmbedding(empty=empty_embedding),
        retrieval_store=_TwoChunkStore(term=term),
    )


def _score(chunks, chunk_id: str) -> float:
    return next(chunk.score for chunk in chunks if chunk.id == chunk_id)


async def test_semantic_weight_wired_into_score(monkeypatch):
    default = _service(monkeypatch)
    score_default = _score(await default.search("alpha", limit=6), "a")
    zeroed = _service(monkeypatch, RETRIEVAL_W_SEMANTIC=0.0)
    score_zeroed = _score(await zeroed.search("alpha", limit=6), "a")
    # a 的 semantic=1.0：权重归零后分差恰为默认权重值。
    assert score_default - score_zeroed == pytest.approx(0.38)


async def test_rrf_prior_scale_wired_into_score(monkeypatch):
    # a 在两路均 rank1：rrf=2/(60+1)，先验=min(rrf×14, 0.22)=0.22（封顶生效）。
    default = _service(monkeypatch)
    score_default = _score(await default.search("alpha", limit=6), "a")
    zeroed = _service(monkeypatch, RETRIEVAL_RRF_PRIOR_SCALE=0.0)
    score_zeroed = _score(await zeroed.search("alpha", limit=6), "a")
    assert score_default - score_zeroed == pytest.approx(0.22)


async def test_no_semantic_branch_weight_wired(monkeypatch):
    # term 区别于其他用例，避免类级 embedding 缓存把语义腿带进本分支。
    default = _service(monkeypatch, empty_embedding=True, term="delta")
    score_default = _score(await default.search("delta", limit=6), "a")
    zeroed = _service(monkeypatch, empty_embedding=True, term="delta",
                      RETRIEVAL_W_LEXICAL_NO_SEMANTIC=0.0)
    score_zeroed = _score(await zeroed.search("delta", limit=6), "a")
    # lexical=1.0（"delta" 单词全命中）：分差恰为该权重默认值。
    assert score_default - score_zeroed == pytest.approx(0.46)


async def test_rrf_k_wired_from_settings(monkeypatch):
    seen = []
    original = CandidateRanks.rrf_score

    def spy(self, k=60):
        seen.append(k)
        return original(self, k)

    monkeypatch.setattr(CandidateRanks, "rrf_score", spy)
    service = _service(monkeypatch, RETRIEVAL_RRF_K=7)
    await service.search("alpha", limit=6)
    assert seen and all(k == 7 for k in seen)


def test_mmr_per_file_cap_read_from_settings(monkeypatch):
    service = _service(monkeypatch, RETRIEVAL_MMR_PER_FILE_CAP=1)
    chunks = [
        RetrievedChunk(
            id=f"same_{index}", source_path="same.c", title="same.c",
            section=f"fn_{index}", content=f"int same_{index}(void) {{ return {index}; }}",
            metadata={}, score=1.0 - index * 0.01,
        )
        for index in range(5)
    ]
    chunks.append(RetrievedChunk(
        id="other", source_path="other.c", title="other.c", section="other",
        content="int other(void) { return 0; }", metadata={}, score=0.8,
    ))
    selected = service._select_diverse_chunks(chunks, limit=4)
    assert sum(1 for chunk in selected if chunk.source_path == "same.c") <= 1
    assert any(chunk.source_path == "other.c" for chunk in selected)


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
