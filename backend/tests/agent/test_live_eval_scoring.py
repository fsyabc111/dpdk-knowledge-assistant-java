"""live 评测判定的离线单测（P2 §4）。

live_eval_runner 的用例本体是 ``live`` mark、要真实模型；但它的**判定与报告**
是纯函数，不需要网络。本文件不 import app，只 import 同目录的 runner 模块
（runner 自带的 pytestmark 只作用于它自己文件里的用例，不影响本文件）。

锁死两件事：

1. 词表是**词边界**口径：`process` 不被 `packets_processed` 满足（这正是 P0.1
   里 `route_kb_engine_ops` 假通过的来源）；口径变化必须逐题留痕。
2. 用量回读只做观测：读不到记 ``usage_error``，绝不把 token 显示成 0，
   更不影响 PASS/FAIL。
"""

from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

import httpx

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import live_eval_runner as runner  # noqa: E402

# ---------------------------------------------------------------------------
# 词表口径
# ---------------------------------------------------------------------------


class TestTermMatching:
    def test_identifier_boundary_is_enforced(self):
        """P0.1 的假通过：`process` 命中了 `packets_processed`。"""
        assert runner._contains("统计 packets_processed 字段", "process") is False
        assert runner._contains("调用 process 处理报文", "process") is True

    def test_case_insensitive_but_still_bounded(self):
        assert runner._contains("Engine_Ops_T 结构体", "engine_ops_t") is True
        assert runner._contains("MyEngine_Ops_T2", "engine_ops_t") is False

    def test_trailing_underscore_stays_a_prefix(self):
        """`dns_` 是前缀写法（live 题里就用它指代 dns_ 开头的用例名），
        给它加右边界会把 `dns_qname` 这类合法命中排除掉。"""
        assert runner._contains("可以试 dns_qname 这个用例", "dns_") is True
        assert runner._contains("看见 my_dns_x 字段", "dns_") is False

    def test_non_alnum_terms_match_as_substring(self):
        """中文词条与围栏标记不是标识符，行为与子串一致（不引入新的误伤）。"""
        assert runner._contains("这里讲用例设计", "用例") is True
        assert runner._contains("```c\n#include <stdio.h>", "```") is True

    def test_empty_term_never_matches(self):
        assert runner._contains("任意文本", "") is False

    def test_loose_rule_is_kept_only_for_reporting(self):
        assert runner._contains_loose("统计 packets_processed", "process") is True
        assert runner._contains("统计 packets_processed", "process") is False


def _case(**rubric) -> dict:
    return {"id": "x", "category": "路由", "question": "q", "expect": {}, "rubric": rubric}


def _result(answer: str, **kwargs) -> runner.CaseResult:
    return runner.CaseResult(case_id="x", category="路由", status_code=200, answer=answer, **kwargs)


class TestEvaluateCaseRubric:
    def test_required_term_now_fails_and_is_recorded(self):
        result = runner.evaluate_case(_case(required_terms=["process"]), _result("统计 packets_processed"))
        assert result.failures == ["必需词缺失：process"]
        assert len(result.term_caliber) == 1
        assert "口径收紧" in result.term_caliber[0]
        assert "process" in result.term_caliber[0]

    def test_required_term_hit_has_no_caliber_note(self):
        result = runner.evaluate_case(_case(required_terms=["process"]), _result("调用 process"))
        assert result.failures == []
        assert result.term_caliber == []

    def test_any_terms_group_failure_is_attributed_to_caliber(self):
        result = runner.evaluate_case(
            _case(any_terms=[["process", "引擎"]]), _result("统计 packets_processed"),
        )
        assert result.failures == ["任选词全缺失：['process', '引擎']"]
        assert any("口径收紧" in note for note in result.term_caliber)

    def test_forbidden_term_loosening_is_reported(self):
        """收紧也会让违禁词判定变松（`process` 不再命中 `packets_processed`）：
        这种方向必须显式报出来，不能悄悄变绿。"""
        result = runner.evaluate_case(
            _case(forbidden_terms=["process"]), _result("统计 packets_processed"),
        )
        assert result.failures == []
        assert len(result.term_caliber) == 1
        assert "口径放宽" in result.term_caliber[0]

    def test_forbidden_term_still_fails_when_present(self):
        result = runner.evaluate_case(_case(forbidden_terms=["参考实现"]), _result("这是参考实现，直接抄"))
        assert result.failures and "违禁词出现" in result.failures[0]
        assert result.term_caliber == []


class TestFailureCategories:
    def test_kinds_are_structured_and_ordered(self):
        result = runner.evaluate_case(
            {"id": "x", "category": "路由", "question": "q",
             "expect": {"tools": ["kb_search"], "min_citations": 3},
             "rubric": {"required_terms": ["engine_ops_t"], "forbidden_terms": ["参考实现"]}},
            _result("这是参考实现"),
        )
        assert result.failure_categories == ["tool_sequence", "citations", "required_term", "forbidden_term"]

    def test_passing_case_has_no_kinds(self):
        result = runner.evaluate_case(_case(required_terms=["kb"]), _result("kb"))
        assert result.failure_categories == []
        assert result.passed is True

    def test_unknown_failure_does_not_invent_a_kind(self):
        result = _result("x")
        result.failures.append("某个将来新增的断言")
        assert result.failure_categories == []


# ---------------------------------------------------------------------------
# 事件解析与用量回读
# ---------------------------------------------------------------------------


class TestEventCapture:
    def test_start_event_carries_server_request_id(self):
        result = _result("", request_id="client-side")
        runner._handle_event(result, "start", json.dumps({"session_id": "s", "request_id": "server-side"}))
        assert result.observed_request_id == "server-side"
        assert result.request_id == "client-side"

    def test_done_event_carries_answer_status(self):
        result = _result("")
        runner._handle_event(result, "done", json.dumps({"answer_status": "unanswered"}))
        assert result.answer_status == "unanswered"

    def test_missing_answer_status_stays_unknown(self):
        """旧管线不带 answer_status：留空表示未知，不猜成 answered。"""
        result = _result("")
        runner._handle_event(result, "done", json.dumps({"confidence": "high"}))
        assert result.answer_status == ""


def _usage_payload(**overrides) -> dict:
    payload = {
        "request_id": "rid-1",
        "totals": {"total_tokens": 1234, "known_entries": 3, "cost_micros_by_currency": {"CNY": 8000}},
        "unknown_entries": 1,
        "by_stage": [{"key": "planner", "total_tokens": 1000, "entries": 1}],
    }
    payload.update(overrides)
    return payload


class TestAttachUsage:
    def _run(self, handler, results):
        async def go():
            transport = httpx.MockTransport(handler)
            async with httpx.AsyncClient(base_url="http://test", transport=transport) as client:
                await runner.attach_usage(client, results)

        asyncio.run(go())

    def test_usage_is_attached_by_observed_request_id(self):
        seen: list[dict] = []

        def handler(request: httpx.Request) -> httpx.Response:
            seen.append(dict(request.url.params))
            return httpx.Response(200, json=_usage_payload())

        result = _result("", request_id="client-side", observed_request_id="server-side")
        self._run(handler, [result])
        assert seen == [{"days": "1", "request_id": "server-side"}]
        assert result.total_tokens == 1234
        assert result.usage["cost_micros_by_currency"] == {"CNY": 8000}
        assert result.usage_error == ""

    def test_unknown_tokens_stay_unknown(self):
        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json=_usage_payload(
                totals={"total_tokens": None, "known_entries": 0, "cost_micros_by_currency": {}},
            ))

        result = _result("", request_id="rid")
        self._run(handler, [result])
        assert result.total_tokens is None
        assert runner._usage_line(result).startswith("usage: tokens=未知")

    def test_read_failure_is_recorded_and_never_flips_the_verdict(self):
        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(403, json={"detail": "not found"})

        result = _result("kb", request_id="rid")
        result.failures = []
        self._run(handler, [result])
        assert result.passed is True, "读账失败是观测问题，不是答案问题"
        assert "HTTPStatusError" in result.usage_error
        assert result.usage is None
        assert "读账失败" in runner._usage_line(result)

    def test_case_without_request_id_is_skipped(self):
        def handler(request: httpx.Request) -> httpx.Response:  # pragma: no cover
            raise AssertionError("不该发起请求")

        result = _result("")
        self._run(handler, [result])
        assert result.usage is None and result.usage_error == ""
        assert runner._usage_line(result) == "usage: 未回读（--skip-usage）"


# ---------------------------------------------------------------------------
# 报告与基线
# ---------------------------------------------------------------------------


class TestReport:
    def test_json_report_adds_keys_without_touching_existing_ones(self):
        result = runner.evaluate_case(_case(required_terms=["process"]), _result("packets_processed"))
        result.usage = {"total_tokens": 10, "known_entries": 1, "cost_micros_by_currency": {}}
        report = runner.build_json_report([result], [], run_id="run-1")
        assert report["run_id"] == "run-1"
        assert report["term_caliber"] == runner.TERM_CALIBER
        case = report["cases"][0]
        # 既有键原样保留
        for key in ("id", "category", "passed", "status_code", "tools", "citations",
                    "answer", "checks", "failures", "elapsed_sec"):
            assert key in case
        assert case["failures"] == ["必需词缺失：process"]
        # 只增的新键
        assert case["failure_categories"] == ["required_term"]
        assert case["usage"]["total_tokens"] == 10
        assert case["answer_status"] is None, "旧管线没有这个事实，就是 null"

    def test_text_report_prints_caliber_notes(self):
        result = runner.evaluate_case(_case(required_terms=["process"]), _result("packets_processed"))
        text = runner.render_text_report([result], [])
        assert "~ [口径收紧] 必需词 'process'" in text

    def test_baseline_records_caliber_version(self, tmp_path):
        path = tmp_path / "baseline.json"
        result = runner.evaluate_case(_case(required_terms=["kb"]), _result("kb"))
        runner.write_baseline(path, "http://test", [result])
        payload = json.loads(path.read_text(encoding="utf-8"))
        assert payload["term_caliber"] == runner.TERM_CALIBER
        assert payload["cases"]["x"]["passed"] is True

    def test_drift_warns_when_baseline_caliber_differs(self):
        baseline = {"term_caliber": "substring-v0", "cases": {"x": {"passed": True, "tools": []}}}
        result = runner.evaluate_case(_case(required_terms=["kb"]), _result("kb"))
        drift = runner.diff_baseline(baseline, [result])
        assert any("口径变更" in line for line in drift)

    def test_drift_is_silent_when_caliber_matches(self):
        baseline = {"term_caliber": runner.TERM_CALIBER, "cases": {"x": {"passed": True, "tools": []}}}
        result = runner.evaluate_case(_case(required_terms=["kb"]), _result("kb"))
        assert runner.diff_baseline(baseline, [result]) == []


class TestRequestIdPlumbing:
    """请求里派出的 request_id 必须是合法 UUID：ChatRequest 把它声明成 UUID 字段，
    非 UUID 文本会被 422 拒掉（评测全变成 system_error）。"""

    def _sent_body(self, case: dict, run_id: str) -> dict:
        sent: dict = {}

        def handler(request: httpx.Request) -> httpx.Response:
            sent.update(json.loads(request.content.decode()))
            return httpx.Response(200, text='event: done\ndata: {"session_id":"s"}\n\n')

        async def go():
            transport = httpx.MockTransport(handler)
            async with httpx.AsyncClient(base_url="http://test", transport=transport) as client:
                return await runner.run_case(client, case, None, run_id)

        sent["result"] = asyncio.run(go())
        return sent

    def test_request_id_is_a_valid_stable_uuid(self):
        import uuid

        body = self._sent_body({"id": "route_kb_engine_ops", "question": "q"}, "20260917T010203")
        request_id = body["request_id"]
        assert uuid.UUID(request_id).version == 5
        assert body["result"].request_id == request_id
        assert request_id == str(uuid.uuid5(runner.REQUEST_ID_NAMESPACE, "20260917T010203/route_kb_engine_ops"))

    def test_different_runs_never_share_a_request_id(self):
        """同 request_id 会被产品路径幂等重放（拿回旧答案）——run_id 必须换。"""
        first = self._sent_body({"id": "x", "question": "q"}, "20260917T010203")["request_id"]
        second = self._sent_body({"id": "x", "question": "q"}, "20260917T010204")["request_id"]
        assert first != second

    def test_case_without_run_id_leaves_it_to_the_server(self):
        body = self._sent_body({"id": "x", "question": "q"}, "")
        assert "request_id" not in body
        assert body["result"].request_id == ""
