#!/usr/bin/env python3
"""J1-03 Python golden 生成器（只读提取，行为基线 = 冻结 Python 源）。

两个阶段：
  --stage rewrite  从 fixture-spec.json 计算每个 case 的改写文本/FTS 查询串/
                   路径片段/嵌入查询文本（真实 HeuristicLLMQueryRewriter），
                   输出 rewrite-intermediate.json 供 SQL 腿提取。
  --stage golden   读 fixture-spec.json + legs.json，用真实 RetrievalService
                   跑融合/重排/MMR，输出逐阶段 golden-retrieval.json。

LLM 腿：chat_model=None → generate 抛 AttributeError → except → 真实启发式
回退路径，与 Python 在 LLM 不可用时的行为一致。

运行（worktree 根目录，详见 evidence/J1-03/README.md）。
"""
from __future__ import annotations

import argparse
import asyncio
import importlib
import json
import sys
import types
from pathlib import Path
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[4]  # worktree 根
BACKEND = ROOT / "backend"


def _settings_defaults() -> SimpleNamespace:
    """backend/app/config.py 的默认值（J1-03 只读取这些字段）。"""
    return SimpleNamespace(
        retrieval_strategy="hybrid_fusion_v2",
        retrieval_w_semantic=0.38,
        retrieval_w_lexical=0.24,
        retrieval_w_ident=0.18,
        retrieval_w_lexical_no_semantic=0.46,
        retrieval_w_ident_no_semantic=0.26,
        retrieval_rrf_k=60,
        retrieval_rrf_prior_scale=14.0,
        retrieval_rrf_prior_cap=0.22,
        retrieval_mmr_redundancy_penalty=0.18,
        retrieval_mmr_per_file_penalty=0.04,
        retrieval_mmr_per_file_cap=2,
        retrieval_filename_boost_exact=0.50,
        retrieval_filename_boost_partial=0.30,
        retrieval_topic_boost_specific=0.95,
        retrieval_topic_boost_generic=0.85,
        retrieval_topic_boost_specific_bare=0.90,
        retrieval_topic_boost_generic_bare=0.80,
        retrieval_topic_boost_specific_infix=0.60,
        retrieval_topic_boost_generic_infix=0.50,
        retrieval_ident_header_score=1.0,
        retrieval_ident_body_score=0.45,
        retrieval_definition_boost_typedef=0.10,
        retrieval_definition_boost_funcsig=0.04,
        model_name="qwen3-max",
        embedding_api_base="",
        embedding_model_name="text-embedding-v4",
        llm_api_base="",
        llama_index_fts_config="simple",
        llama_index_table="li_knowledge_v1",
        llama_index_hnsw_enabled=False,
        llama_index_hnsw_ef_search=40,
    )


def _load_real_retrieval_service():
    """stub 重依赖（pydantic/openai/psycopg）后导入真实 retrieval_service。"""
    defaults = _settings_defaults()

    for name in ("app", "app.core", "app.rag", "app.services"):
        mod = types.ModuleType(name)
        mod.__path__ = []
        sys.modules[name] = mod
    sys.modules["app"].__path__ = [str(BACKEND / "app")]
    sys.modules["app.core"].__path__ = [str(BACKEND / "app/core")]
    sys.modules["app.rag"].__path__ = [str(BACKEND / "app/rag")]
    sys.modules["app.services"].__path__ = [str(BACKEND / "app/services")]

    cfg = types.ModuleType("app.config")
    cfg.get_settings = lambda: defaults
    sys.modules["app.config"] = cfg

    providers = types.ModuleType("app.rag.providers")
    providers.build_embedding_model = lambda settings: None
    providers.DirectOpenAIChatModel = object
    sys.modules["app.rag.providers"] = providers

    storage = types.ModuleType("app.storage")
    storage.__path__ = []
    sys.modules["app.storage"] = storage
    rs_store = types.ModuleType("app.storage.retrieval_store")
    rs_store.RetrievalStore = object
    rs_store.get_historical_chunk = lambda chunk_id: None
    sys.modules["app.storage.retrieval_store"] = rs_store

    # stdlib-only 的真实模块直接加载。
    sys.modules["app.core.usage"] = importlib.import_module("app.core.usage")
    sys.modules["app.core.tracing"] = importlib.import_module("app.core.tracing")
    sys.modules["app.core.async_cache"] = importlib.import_module("app.core.async_cache")
    sys.modules["app.rag.types"] = importlib.import_module("app.rag.types")
    sys.modules["app.rag.interfaces"] = importlib.import_module("app.rag.interfaces")
    sys.modules["app.services.vector_math"] = importlib.import_module("app.services.vector_math")

    return importlib.import_module("app.services.retrieval_service"), defaults


def _fixed_rewriter(rs, defaults):
    """object.__new__ 绕过 __init__（其 import DirectOpenAIChatModel）；
    chat_model=None → LLM 调用抛异常 → 真实启发式回退。"""
    rewriter = object.__new__(rs.HeuristicLLMQueryRewriter)
    rewriter.settings = defaults
    rewriter.chat_model = None
    rewriter._rewrite_prompt = None
    return rewriter


class _FixedEmbedding:
    def __init__(self, vector):
        self.vector = vector

    async def embed(self, texts, *, usage=None, stage=""):
        return [list(self.vector)] if self.vector else [[] for _ in texts]


class _FakeStore:
    """四条腿返回冻结的 SQL 结果；行结构与 LiNodeRetrievalStore 完全一致。"""

    def __init__(self, case_legs, rows_by_id):
        self.legs = case_legs
        self.rows_by_id = rows_by_id
        self.loaded_ids_history = []

    def fetch_fts_candidate_ids(self, match_query, limit):
        if not match_query:
            return []
        if match_query == self.legs["fts_query_rewritten"]:
            return list(self.legs["fts_rewritten_ids"])
        if match_query == self.legs["fts_query_original"]:
            return list(self.legs["fts_original_ids"])
        raise AssertionError(f"未冻结的 FTS 查询串: {match_query!r}")

    def fetch_path_candidate_ids(self, fragments, limit):
        if not fragments:
            return []
        assert fragments == self.legs["path_fragments"], (fragments, self.legs["path_fragments"])
        return list(self.legs["path_ids"])

    def fetch_semantic_candidate_ids(self, embedding, limit):
        if not embedding:
            return []
        return list(self.legs["semantic_ids"])

    def fetch_fallback_candidate_ids(self):
        return []

    def fetch_rows_by_ids(self, ids):
        self.loaded_ids_history.append(list(ids))
        return [self.rows_by_id[i] for i in ids if i in self.rows_by_id]

    def get_chunk(self, chunk_id):
        return None


def _vector(nonzero, dim=1024):
    v = [0.0] * dim
    for idx, val in nonzero or []:
        v[idx] = float(val)
    return v


def _vector_literal(nonzero, dim=1024):
    return "[" + ",".join(str(x) for x in _vector(nonzero, dim)) + "]"


def stage_rewrite(spec_path: Path, out_path: Path) -> None:
    spec = json.loads(spec_path.read_text(encoding="utf-8"))
    rs, defaults = _load_real_retrieval_service()
    rewriter = _fixed_rewriter(rs, defaults)
    # 未绑定 store 的检索服务实例只借用纯文本方法（_build_fts_query/_path_fragments）。
    svc = object.__new__(rs.RetrievalService)

    cases = []
    for case in spec["cases"]:
        release = case["release"]
        if release not in spec["releases"] or \
                spec["releases"][release]["configuration"] != "expected-fingerprint":
            cases.append({"case_id": case["id"], "pinned": False})
            continue
        query = case["query"]
        rewritten = asyncio.run(rewriter.rewrite(query))
        embedding_query = f"{query} {rewritten}" if rewritten != query else query
        cases.append({
            "case_id": case["id"],
            "pinned": True,
            "release": release,
            "query": query,
            "limit": case["limit"],
            "rewritten": rewritten,
            "embedding_query": embedding_query,
            "fts_query_rewritten": svc._build_fts_query(rewritten) or "",
            "fts_query_original": svc._build_fts_query(query) or "",
            "path_fragments": svc._path_fragments(query, rewritten),
            "query_vector_literal": (_vector_literal(case["query_vector"])
                                     if case["query_vector"] else None),
        })
        print(f"{case['id']}: rewritten={rewritten!r}")

    out_path.write_text(json.dumps({
        "spec_version": spec["spec_version"], "cases": cases,
    }, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {out_path}")


def stage_golden(spec_path: Path, legs_path: Path, out_path: Path) -> None:
    spec = json.loads(spec_path.read_text(encoding="utf-8"))
    legs_doc = json.loads(legs_path.read_text(encoding="utf-8"))

    rs, defaults = _load_real_retrieval_service()
    rewriter = _fixed_rewriter(rs, defaults)

    fingerprint = json.dumps(
        {"model": spec["embedding_config"]["model"],
         "api_base": spec["embedding_config"]["api_base"],
         "dimensions": spec["embedding_config"]["dimensions"],
         "chunker": spec["embedding_config"]["chunker"]},
        sort_keys=True)

    rows_by_id = {}
    for node in spec["nodes"]:
        rows_by_id[node["node_id"]] = {
            "id": node["node_id"],
            "content": node["text"],
            "source_path": node["metadata"]["source_path"],
            "section": node["metadata"]["section"],
            "title": node["metadata"]["title"],
            "metadata_json": legs_doc["metadata_text"][node["node_id"]],
            "embedding_json": legs_doc["embedding_text"][node["node_id"]],
        }

    golden_cases = []
    for case in spec["cases"]:
        case_id = case["id"]
        release = case["release"]

        entry = {
            "case_id": case_id,
            "release": release,
            "query": case["query"],
            "limit": case["limit"],
            "query_vector_literal": (_vector_literal(case["query_vector"])
                                     if case["query_vector"] else None),
        }

        if release not in spec["releases"] or \
                spec["releases"][release]["configuration"] != "expected-fingerprint":
            entry.update({
                "pinned": False,
                "expected_chunk_ids": [],
                "note": "release 缺失或配置指纹失配：fail-closed 返回空（对齐 HybridDpdkRetriever）",
            })
            golden_cases.append(entry)
            continue

        case_legs = legs_doc["cases"][case_id]

        rewritten = case_legs["rewritten"]
        vector = _vector(case["query_vector"]) if case["query_vector"] else []
        # 类级缓存按 case 重置：同文本在不同 case 期望不同冻结向量，
        # 进程内跨 case 复用缓存会把上一个 case 的空/旧向量带进来。
        rs.RetrievalService._embedding_cache = rs.AsyncTTLCache(ttl_seconds=600, maxsize=512)
        store = _FakeStore(case_legs, rows_by_id)
        service = rs.RetrievalService(
            query_rewriter=rewriter,
            embedding_model=_FixedEmbedding(vector),
            retrieval_store=store,
        )
        limit = case["limit"]
        candidate_limit = max(limit * 28, 160)
        selected = asyncio.run(service.search(case["query"], limit=limit, trace=None))

        rows, ranks = service._fetch_candidate_rows(
            case["query"], rewritten, vector, candidate_limit)
        fused = [cid for cid, _ in sorted(
            ranks.items(),
            key=lambda item: item[1].rrf_score(defaults.retrieval_rrf_k),
            reverse=True,
        )]
        ranks_plain = {
            cid: {"fts_rewritten": item.fts_rewritten, "fts_original": item.fts_original,
                  "path": item.path, "semantic": item.semantic, "rrf": item.rrf_score(defaults.retrieval_rrf_k)}
            for cid, item in ranks.items()
        }

        breakdown = []
        for row in rows:
            metadata = json.loads(row["metadata_json"] or "{}")
            embedding = json.loads(row["embedding_json"] or "[]")
            semantic = rs.cosine_similarity(vector, embedding) if vector and embedding else 0.0
            lexical = max(rs.keyword_score(rewritten, row["content"], metadata),
                          rs.keyword_score(case["query"], row["content"], metadata))
            ident = max(
                rs.identifier_score(case["query"], row["source_path"], row["section"] or "", row["content"], defaults),
                rs.identifier_score(rewritten, row["source_path"], row["section"] or "", row["content"], defaults))
            file_boost = max(rs.filename_boost(case["query"], row["source_path"], defaults),
                             rs.filename_boost(rewritten, row["source_path"], defaults))
            topic = max(rs.topic_boost(case["query"], row["source_path"], defaults),
                        rs.topic_boost(rewritten, row["source_path"], defaults))
            path_boost = max(file_boost, topic)
            rank_item = ranks.get(row["id"], rs.CandidateRanks())
            rrf = rank_item.rrf_score(defaults.retrieval_rrf_k)
            source_prior = min(rrf * defaults.retrieval_rrf_prior_scale,
                               defaults.retrieval_rrf_prior_cap)
            structural = rs.definition_boost(row["content"], defaults)
            if semantic:
                score = (semantic * defaults.retrieval_w_semantic
                         + lexical * defaults.retrieval_w_lexical
                         + ident * defaults.retrieval_w_ident
                         + source_prior + path_boost + structural)
            else:
                score = (lexical * defaults.retrieval_w_lexical_no_semantic
                         + ident * defaults.retrieval_w_ident_no_semantic
                         + source_prior + path_boost + structural)
            breakdown.append({
                "id": row["id"], "semantic": semantic, "lexical": lexical,
                "ident": ident, "file_boost": file_boost, "topic": topic,
                "path_boost": path_boost, "rrf": rrf,
                "source_prior": source_prior, "structural": structural,
                "branch": "semantic" if semantic else "no_semantic",
                "score": score if score > 0 else None,
            })

        entry.update({
            "pinned": True,
            "rewritten": rewritten,
            "embedding_query": case_legs["embedding_query"],
            "fts_query_rewritten": case_legs["fts_query_rewritten"],
            "fts_query_original": case_legs["fts_query_original"],
            "path_fragments": case_legs["path_fragments"],
            "legs": {
                "fts_rewritten_ids": case_legs["fts_rewritten_ids"],
                "fts_original_ids": case_legs["fts_original_ids"],
                "path_ids": case_legs["path_ids"],
                "semantic_ids": case_legs["semantic_ids"],
            },
            "ranks": ranks_plain,
            "fused_candidate_ids": fused,
            "loaded_row_ids": store.loaded_ids_history[-1],
            "breakdown": breakdown,
            "selected": [
                {"id": c.id, "source_path": c.source_path, "section": c.section,
                 "title": c.title, "score": c.score}
                for c in selected
            ],
            "expected_chunk_ids": [c.id for c in selected],
        })
        golden_cases.append(entry)

    out = {
        "golden_version": "j1-03-v1",
        "generated_by": "docs/java-migration/evidence/J1-03/generate_golden.py（真实冻结 Python 源驱动）",
        "fingerprint": fingerprint,
        "float_tolerance": 1e-12,
        "cases": golden_cases,
    }
    out_path.write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"wrote {out_path} ({len(golden_cases)} cases)")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--stage", choices=["rewrite", "golden"], required=True)
    parser.add_argument("--spec", required=True)
    parser.add_argument("--legs", help="legs.json（golden 阶段）")
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    if args.stage == "rewrite":
        stage_rewrite(Path(args.spec), Path(args.out))
    else:
        assert args.legs, "golden 阶段需要 --legs"
        stage_golden(Path(args.spec), Path(args.legs), Path(args.out))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
