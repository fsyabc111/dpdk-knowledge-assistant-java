"""S3-e「N08 离线 judge」回归测试。

N08 决策（docs/decisions.md）：模型 judge 转离线验收，在线只保留确定性
检查。本文件锁定：

- 离线口径（``judge_enabled=False`` / 开关默认关）：empty_answer →
  invalid_citation → no_evidence → missing_citation 四判据顺序判定，
  全过即 ``answered/supported``；model 传「调用即 raise」替身证明不打模型；
- 在线口径回归（``judge_enabled=True`` / 显式注入模型）：stub model 返回
  合法 verdict → 行为与 S3-e 前逐字节一致；模型异常 → system_error/
  validation_failed；
- ``_candidate`` 集成：开关关时产品路径完整跑通，judge 模型不构造不调用；
- ``tests/rag_eval/judge_cases.yml``：deterministic 例读文件驱动断言
  （进默认集），judge 例是离线验收工具的标注、本套件不跑；
- 指标：离线路径照常记 ``rag_validation``。

替身沿用 test_agent_service.py：ScriptedChatModel / FakeMcp / make_orchestrator；
数据库走 conftest 的 sqlite_db 临时库，无网络。
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest
import yaml
from langchain_core.messages import AIMessage

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from test_agent_service import FakeMcp, ScriptedChatModel, kb_call, make_orchestrator

from app.config import get_settings
from app.core.auth import Principal
from app.schemas.chat import ChatRequest
from app.services.rag_product_service import (
    UNANSWERED,
    UNAVAILABLE,
    RagProductService,
    validate_answer,
)

JUDGE_CASES_FILE = ROOT / "tests" / "rag_eval" / "judge_cases.yml"


class RaisingJudge:
    """调用即失败的 judge 替身：离线口径下绝不应被调到。"""

    def __init__(self):
        self.calls = 0

    async def generate(self, messages, *, usage=None, stage=""):
        self.calls += 1
        raise AssertionError("offline path must not call the judge model")


class StubJudge:
    """返回固定 JSON verdict 的 judge 替身（在线口径回归用）。"""

    def __init__(self, result):
        self.result = result
        self.calls = 0

    async def generate(self, messages, *, usage=None, stage=""):
        self.calls += 1
        return self.result


def _validation_spy(monkeypatch):
    """替换 metrics.record_rag_validation 为间谍，返回 (result, seconds) 调用记录。"""
    from app.core import metrics
    calls = []
    monkeypatch.setattr(metrics, "record_rag_validation", lambda result, seconds: calls.append((result, seconds)))
    return calls


# ----------------------------------------------------------------------
# 离线口径：四判据 + 全过 supported，model 替身证明不打模型
# ----------------------------------------------------------------------


@pytest.mark.parametrize("answer,evidence,status,reason", [
    ("", {"chunks": []}, "unanswered", "empty_answer"),
    ("事实 [2]", {"chunks": [{"content": "x"}]}, "unanswered", "invalid_citation"),
    ("事实", {"chunks": [], "tools": []}, "unanswered", "no_evidence"),
    ("事实", {"chunks": [{"content": "x"}]}, "unanswered", "missing_citation"),
    ("事实 [1]", {"chunks": [{"content": "x"}]}, "answered", "supported"),
    # 工具评测事实也算证据：无 chunks 但有成功 tools → 不判 no_evidence
    ("评测完成 5/8 通过",
     {"chunks": [], "tools": [{"tool": "grade_plugin", "result": {"ok": True}}]},
     "answered", "supported"),
])
async def test_offline_judge_deterministic_branches(answer, evidence, status, reason):
    judge = RaisingJudge()
    verdict = await validate_answer("问题", answer, evidence, judge, 1, judge_enabled=False)
    assert (verdict.status, verdict.reason) == (status, reason)
    assert judge.calls == 0, "离线口径不得调用 judge 模型"
    if status == "unanswered":
        assert verdict.answer == UNANSWERED


async def test_offline_judge_allows_none_model():
    """model=None 是离线口径的合法形态（_candidate 关判时就传 None）。"""
    verdict = await validate_answer("问题", "事实 [1]", {"chunks": [{}]}, None, 1,
                                    judge_enabled=False)
    assert (verdict.status, verdict.reason) == ("answered", "supported")


async def test_judge_enabled_none_reads_settings_and_injection(monkeypatch):
    """judge_enabled=None 的解析：读配置；显式注入 model 视为启用。

    注入即启用是既有测试替身与 api/rag.py 原题验证入口依赖的语义：
    它们本来就为 judge 构造了模型，开关关闭不应让它们静默降级。
    """
    # 显式钉死关：本机 .env 若打开开关也不能影响本用例
    monkeypatch.setenv("RAG_ONLINE_JUDGE_ENABLED", "false")
    get_settings.cache_clear()
    verdict = await validate_answer("问题", "事实 [1]", {"chunks": [{}]}, None, 1)
    assert (verdict.status, verdict.reason) == ("answered", "supported")

    # 注入模型 → 视为启用（即使配置关着）
    judge = StubJudge('{"status":"unanswered","supported":false,"reason":"insufficient_evidence"}')
    verdict = await validate_answer("问题", "事实 [1]", {"chunks": [{}]}, judge, 1)
    assert (verdict.status, verdict.reason) == ("unanswered", "insufficient_evidence")
    assert judge.calls == 1

    # 配置开启 → 走在线核验
    monkeypatch.setenv("RAG_ONLINE_JUDGE_ENABLED", "true")
    get_settings.cache_clear()
    judge = StubJudge('{"status":"answered","supported":true}')
    verdict = await validate_answer("问题", "事实 [1]", {"chunks": [{}]}, judge, 1)
    assert (verdict.status, verdict.reason) == ("answered", "supported")
    assert judge.calls == 1


async def test_offline_path_records_validation_metric(monkeypatch):
    """离线口径照常记 rag_validation（result 标签 = verdict.status）。"""
    calls = _validation_spy(monkeypatch)
    verdict = await validate_answer("问题", "事实 [1]", {"chunks": [{}]}, RaisingJudge(), 1,
                                    judge_enabled=False)
    assert verdict.status == "answered"
    assert [call[0] for call in calls] == ["answered"]
    assert calls[0][1] >= 0


# ----------------------------------------------------------------------
# 在线口径回归：judge_enabled=True 行为与 S3-e 前逐字节一致
# ----------------------------------------------------------------------


@pytest.mark.parametrize("answer,evidence,result,status,reason", [
    ("事实 [1]", {"chunks": [{"content": "事实"}]}, '{"status":"answered","supported":true}', "answered", "supported"),
    ("部分事实 [1]，其他资料不足", {"chunks": [{}]}, '{"status":"partial","supported":true}', "partial", "insufficient_evidence"),
    ("你好", {"chunks": []}, '{"status":"not_applicable","supported":true}', "not_applicable", "non_knowledge"),
    ("事实 [1]", {"chunks": [{}]}, '{"status":"unanswered","supported":false,"reason":"conflicting_evidence"}', "unanswered", "conflicting_evidence"),
    ("事实 [1]", {"chunks": [{}]}, '{"status":"unanswered","supported":false}', "unanswered", "insufficient_evidence"),
    ("问题没说清", {"chunks": [{}]}, '{"status":"needs_clarification","supported":true}', "needs_clarification", "clarification"),
    # 后置确定性复核照常：模型判 answered 但无 chunks 无 tools → no_evidence
    ("事实", {"chunks": [], "tools": []}, '{"status":"answered","supported":true}', "unanswered", "no_evidence"),
    # 模型判 answered 但有 chunks 无 [n] → missing_citation
    ("事实", {"chunks": [{}]}, '{"status":"answered","supported":true}', "unanswered", "missing_citation"),
    # 前置确定性检查先于模型：引用越界根本不打模型
    ("事实 [9]", {"chunks": [{}]}, '{"status":"answered","supported":true}', "unanswered", "invalid_citation"),
])
async def test_online_judge_path_unchanged(answer, evidence, result, status, reason):
    judge = StubJudge(result)
    verdict = await validate_answer("问题", answer, evidence, judge, 1, judge_enabled=True)
    assert (verdict.status, verdict.reason) == (status, reason)
    # 前置确定性检查（empty/invalid_citation）先于模型；其余路径恰好打一次
    expected_calls = 0 if (status == "unanswered" and reason in {"empty_answer", "invalid_citation"}) else 1
    assert judge.calls == expected_calls


async def test_online_judge_model_failure_is_system_error():
    """模型异常 → UNAVAILABLE/system_error/validation_failed（逐字节回归）。"""
    verdict = await validate_answer("问题", "事实 [1]", {"chunks": [{}]}, RaisingJudge(), 1,
                                    judge_enabled=True)
    assert (verdict.status, verdict.reason) == ("system_error", "validation_failed")
    assert verdict.answer == UNAVAILABLE


# ----------------------------------------------------------------------
# _candidate 集成：开关关时产品路径跑通，judge 模型不构造不调用
# ----------------------------------------------------------------------


@pytest.fixture
def product_on(sqlite_db, monkeypatch):
    """产品化管线（S4 起唯一路径，无开关）；judge 显式钉死关。"""
    monkeypatch.setenv("RAG_ONLINE_JUDGE_ENABLED", "false")
    get_settings.cache_clear()


def _spy_validate(monkeypatch):
    """包一层真 validate_answer：记录调用方传入的 model，行为不变。"""
    import app.services.rag_product_service as rps
    real_validate = rps.validate_answer
    seen = {}

    async def spy(question, answer, evidence, model, timeout, usage=None, request_id=""):
        seen["model"] = model
        return await real_validate(question, answer, evidence, model, timeout,
                                   usage=usage, request_id=request_id)

    monkeypatch.setattr(rps, "validate_answer", spy)
    return seen, rps


async def _run_product_question(product_on, monkeypatch, answer_text="qname 按标签序列编码 [1]。"):
    """装配替身 agent 路径并跑一次产品问答，返回 (response, seen, constructed)。"""
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[kb_call()]),
        AIMessage(content=answer_text),
    ])
    orchestrator = make_orchestrator(model, FakeMcp({"ok": True}))
    monkeypatch.setattr("app.agent.service.AgentOrchestrator", lambda: orchestrator)

    seen, rps = _spy_validate(monkeypatch)
    constructed = []

    def fake_ctor(settings):
        constructed.append(settings)
        return RaisingJudge()

    monkeypatch.setattr(rps, "DirectOpenAIChatModel", fake_ctor)

    product = RagProductService(Principal(subject="student", roles=["viewer"], daily_quota=200),
                                ChatRequest(message="dns 的 qname 怎么编码？"))
    await product.prepare()

    async def discard(event):
        pass

    response = await product.run(discard)
    return response, seen, constructed


async def test_candidate_offline_judge_skips_model(product_on, monkeypatch):
    """N08 默认口径：RAG_ONLINE_JUDGE_ENABLED=false → 不打 judge 模型。

    端到端走真 _candidate（替身只换掉 agent 图模型与 MCP）：确定性四判据
    全过即 answered/supported；validate_answer 收到的 model 必须是 None，
    DirectOpenAIChatModel 一次都不构造。
    """
    response, seen, constructed = await _run_product_question(product_on, monkeypatch)
    assert response.answer_status == "answered"
    assert response.answer_reason == "supported"
    assert "qname" in response.answer
    assert seen["model"] is None, "离线口径下不应有 judge 模型"
    assert constructed == [], "离线口径下不应构造 DirectOpenAIChatModel"


async def test_candidate_online_judge_roundtrip(product_on, monkeypatch):
    """开关开启 → _candidate 构造 judge 模型并按在线口径送审。"""
    monkeypatch.setenv("RAG_ONLINE_JUDGE_ENABLED", "true")
    get_settings.cache_clear()

    judge = StubJudge('{"status":"unanswered","supported":false,"reason":"insufficient_evidence"}')
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[kb_call()]),
        AIMessage(content="qname 按标签序列编码 [1]。"),
    ])
    orchestrator = make_orchestrator(model, FakeMcp({"ok": True}))
    monkeypatch.setattr("app.agent.service.AgentOrchestrator", lambda: orchestrator)

    seen, rps = _spy_validate(monkeypatch)
    monkeypatch.setattr(rps, "DirectOpenAIChatModel", lambda settings: judge)

    product = RagProductService(Principal(subject="student", roles=["viewer"], daily_quota=200),
                                ChatRequest(message="dns 的 qname 怎么编码？"))
    await product.prepare()

    async def discard(event):
        pass

    response = await product.run(discard)
    assert seen["model"] is judge, "开关开启时应构造 judge 模型送审"
    assert judge.calls == 1
    assert response.answer_status == "unanswered"
    assert response.answer_reason == "insufficient_evidence"


# ----------------------------------------------------------------------
# 题集驱动：judge_cases.yml 的 deterministic 例进默认集，judge 例跳过
# ----------------------------------------------------------------------


def _load_judge_cases():
    payload = yaml.safe_load(JUDGE_CASES_FILE.read_text(encoding="utf-8")) or {}
    return payload.get("cases") or []


_DETERMINISTIC_CASES = [c for c in _load_judge_cases() if c.get("mode") == "deterministic"]


@pytest.mark.parametrize("case", _DETERMINISTIC_CASES, ids=[c["name"] for c in _DETERMINISTIC_CASES])
async def test_judge_cases_file_deterministic(case):
    """题集 deterministic 例：与在线现状（judge 关闭）同构，不打模型。"""
    verdict = await validate_answer(str(case["question"]), str(case["answer"]),
                                    dict(case.get("evidence") or {}), RaisingJudge(), 1,
                                    judge_enabled=False)
    assert verdict.status == case["expect_status"], f"{case['name']}: {verdict.status} != {case['expect_status']}"
    if case.get("expect_reason") is not None:
        assert verdict.reason == case["expect_reason"], f"{case['name']}: {verdict.reason} != {case['expect_reason']}"


def test_judge_cases_file_covers_both_modes():
    """题集必须同时含两模态：deterministic 五判据 + judge 标注例。

    judge 例是 scripts/rag_judge_eval.py 的输入，需要真实 LLM，默认集
    只校验题集结构完整（name/question/answer/mode/expect_status 齐全）。
    """
    cases = _load_judge_cases()
    deterministic = [c for c in cases if c.get("mode") == "deterministic"]
    judged = [c for c in cases if c.get("mode") == "judge"]
    assert len(deterministic) >= 5, "deterministic 例须覆盖四判据 + supported"
    assert judged, "题集应含 judge 标注例（供离线验收脚本跑）"
    for case in cases:
        assert case.get("name") and case.get("mode") in {"deterministic", "judge"}
        assert "question" in case and "answer" in case
        assert case.get("expect_status") in {
            "answered", "partial", "needs_clarification", "unanswered",
            "not_applicable", "system_error",
        }


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
