"""
Retrieval system regression tests.

检索回归断言的唯一事实来源是 tests/rag_eval/queries.yml，本文件从这里
parametrize 生成用例（保持迁移前的二值断言语义：expected_sources 全部组
在 limit 内命中、must_top 要求 top-1、expect_nonempty 要求非空；observe
用例只执行不卡线）。量化指标（recall@k / MRR / 基线漂移）由同目录的
rag_eval_runner.py 负责。

These tests exercise the real RetrievalService against an ingested knowledge
base and the configured embedding/LLM APIs, so the whole module is marked
``live`` and deselected by default in offline runs.

Run:
    cd ai_chat_assistant/backend
    .venv/bin/python -m pytest tests/test_retrieval.py -v -m live

Or standalone:
    .venv/bin/python tests/test_retrieval.py
"""

import sys
from pathlib import Path

import pytest
import yaml

from app.services.retrieval_service import RetrievalService

pytestmark = pytest.mark.live

QUERIES_FILE = Path(__file__).resolve().parent / "rag_eval" / "queries.yml"
DEFAULT_LIMIT = 10


def _load_eval_cases():
    with open(QUERIES_FILE, encoding="utf-8") as handle:
        payload = yaml.safe_load(handle) or {}
    return payload.get("cases") or []


EVAL_CASES = _load_eval_cases()


async def _search(q: str, limit: int = DEFAULT_LIMIT):
    # 唯一检索数据面：LlamaIndex 发布版本适配器（内部组装 RetrievalService）。
    from app.services.llama_index_service import LlamaIndexRetrievalAdapter

    return await LlamaIndexRetrievalAdapter().search(q, limit=limit)


async def _rewrite(q: str) -> str:
    # 改写不触碰 store，注入占位对象即可复用共享管线。
    svc = RetrievalService(retrieval_store=object())
    return await svc._rewrite_query(q)


# ── 题库驱动的检索回归（queries.yml）──────────────────────────────────


@pytest.mark.parametrize("eval_case", EVAL_CASES, ids=[case["id"] for case in EVAL_CASES])
async def test_retrieval_case(eval_case):
    limit = int(eval_case.get("limit") or DEFAULT_LIMIT)
    chunks = await _search(eval_case["question"], limit=limit)
    paths = [chunk.source_path.lower() for chunk in chunks]

    if eval_case.get("observe"):
        return  # 观察例（负例/模糊问句）：只执行，不卡线
    if eval_case.get("expect_nonempty"):
        assert chunks, f"{eval_case['id']} 应返回非空结果"
        return

    for group in eval_case.get("expected_sources") or []:
        alternatives = [group] if isinstance(group, str) else [str(item) for item in group]
        assert any(fragment.lower() in path for fragment in alternatives for path in paths), (
            f"{eval_case['id']} 未命中相关组 {alternatives}，实际 top 路径：{paths[:5]}"
        )

    must_top = eval_case.get("must_top")
    if must_top:
        alternatives = [must_top] if isinstance(must_top, str) else [str(item) for item in must_top]
        assert chunks and any(
            fragment.lower() in paths[0] for fragment in alternatives
        ), f"{eval_case['id']} 要求 top-1 命中 {alternatives}，实际 top1：{paths[0] if paths else '<empty>'}"


# ── Query Rewrite（断言改写文本特征，不走检索）─────────────────────────


async def test_rewrite_translates_chinese_to_english():
    """Chinese protocol query → English keywords + filenames"""
    rw = await _rewrite("FTP 协议检测插件怎么实现的")
    assert "FTP" in rw
    assert "engine_ftp.c" in rw


async def test_rewrite_conceptual_skips_filenames():
    """Conceptual questions (什么是) should NOT add filenames"""
    rw = await _rewrite("什么是插件系统")
    assert "engine" not in rw.lower() or "." not in rw  # no filenames


async def test_rewrite_decomposes_compound():
    """Compound questions use ; to separate dimensions"""
    rw = await _rewrite("SMTP 和 POP3 有什么不同")
    assert ";" in rw
    assert "smtp" in rw.lower()
    assert "pop3" in rw.lower()


async def test_rewrite_skips_pure_english_short():
    """Pure English short queries are returned unchanged"""
    rw = await _rewrite("how does session hash table work")
    assert rw == "how does session hash table work"


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
