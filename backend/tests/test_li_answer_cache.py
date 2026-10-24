"""llama_index 引擎 answer cache 的默认集单测（E 阶段对称补齐）。

与旧管线 GroundedAnswerGenerator 同形：仅非流式 generate 走缓存
（RAG_ANSWER_CACHE_ENABLED，默认关）；命中跳过模型调用并落
answer_cache_hit/answer_generation_attempts=0 观测字段；键含问题、改写、
会话上下文与证据正文，证据变化即失效。
"""
from __future__ import annotations

from types import SimpleNamespace

import pytest

from app.config import get_settings
from app.core.usage import UsageRecorder
from app.rag.types import RetrievedChunk
from app.services.llama_index_service import LlamaIndexAnswerGenerator


class _Trace:
    def __init__(self) -> None:
        self.meta = {}
        # RequestTrace 上必然有 usage 累加器（P1 账本）；替身同形，否则缓存命中
        # 记账那一行会 AttributeError。
        self.usage = UsageRecorder()

    def set_meta(self, **kw):
        self.meta.update(kw)


def _generator(monkeypatch, enabled: str) -> LlamaIndexAnswerGenerator:
    monkeypatch.setenv("RAG_ANSWER_CACHE_ENABLED", enabled)
    get_settings.cache_clear()
    return LlamaIndexAnswerGenerator(get_settings(), SimpleNamespace(_llm=object()), "系统提示词")


def _chunk(content: str = "证据正文") -> RetrievedChunk:
    return RetrievedChunk(id="c1", source_path="engine_dns.cpp", title="engine_dns.cpp",
                          section="parse_qname", content=content, metadata={}, score=0.9)


def _counting_fake(monkeypatch, generator):
    state = {"calls": 0}

    async def fake_query(chunks, query, streaming, trace=None):
        state["calls"] += 1
        return SimpleNamespace(response=f"答案{state['calls']}")

    monkeypatch.setattr(generator, "_aquery_with_retry", fake_query)
    return state


async def test_cache_hit_skips_model_call(monkeypatch):
    generator = _generator(monkeypatch, "true")
    await LlamaIndexAnswerGenerator._answer_cache.clear()
    state = _counting_fake(monkeypatch, generator)

    first = await generator.generate("dns 解析", [_chunk()])
    trace = _Trace()
    second = await generator.generate("dns 解析", [_chunk()], trace=trace)
    assert first.answer == second.answer == "答案1", "命中缓存返回同一答案"
    assert state["calls"] == 1, "第二次生成不得再调模型"
    assert trace.meta.get("answer_cache_hit") is True
    assert trace.meta.get("answer_generation_attempts") == 0


async def test_cache_miss_sets_hit_false(monkeypatch):
    generator = _generator(monkeypatch, "true")
    await LlamaIndexAnswerGenerator._answer_cache.clear()
    state = _counting_fake(monkeypatch, generator)
    trace = _Trace()
    await generator.generate("dns 解析", [_chunk()], trace=trace)
    assert state["calls"] == 1
    assert trace.meta.get("answer_cache_hit") is False


async def test_cache_bypassed_when_switch_off(monkeypatch):
    generator = _generator(monkeypatch, "false")
    state = _counting_fake(monkeypatch, generator)
    await generator.generate("dns 解析", [_chunk()])
    await generator.generate("dns 解析", [_chunk()])
    assert state["calls"] == 2, "开关关闭时每次都调模型（与旧管线一致）"


async def test_cache_key_isolates_question_and_evidence(monkeypatch):
    generator = _generator(monkeypatch, "true")
    await LlamaIndexAnswerGenerator._answer_cache.clear()
    state = _counting_fake(monkeypatch, generator)
    await generator.generate("dns 解析", [_chunk()])
    await generator.generate("tls 提取", [_chunk()])
    await generator.generate("dns 解析", [_chunk(content="变化后的证据")])
    assert state["calls"] == 3, "问题或证据正文变化都不得命中旧缓存"


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-v"]))
