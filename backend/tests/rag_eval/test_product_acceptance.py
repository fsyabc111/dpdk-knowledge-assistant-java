"""acceptance_lib 与 product_acceptance_runner 的离线单测（无网络，进默认测试集）。"""

from __future__ import annotations

import asyncio
import json
import os
import subprocess
import sys
from pathlib import Path

import httpx
import pytest

SCRIPT_DIR = Path(__file__).resolve().parent
if str(SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPT_DIR))

import acceptance_lib as lib  # noqa: E402
import product_acceptance_runner as runner  # noqa: E402

CASES_FILE = SCRIPT_DIR / "product_acceptance.yml"
ALL_CASES = lib.load_cases(CASES_FILE)


class TestExpandCases:
    def test_total_is_100(self):
        assert len(ALL_CASES) == 100

    def test_ids_unique(self):
        ids = [case["id"] for case in ALL_CASES]
        assert len(ids) == len(set(ids))

    def test_protocol_template_ids_and_format(self):
        template_cases = ALL_CASES[:60]
        assert [case["id"] for case in template_cases[:3]] == ["protocol_dns_1", "protocol_dns_2", "protocol_dns_3"]
        assert template_cases[-1]["id"] == "protocol_v2ray_3"
        for case in template_cases:
            prefix, _, index = case["id"].rpartition("_")
            assert prefix.startswith("protocol_")
            assert index in {"1", "2", "3"}
            assert case["expected"] == "answered"
            assert case["history"] == []
            assert "{protocol}" not in case["question"]

    def test_standalone_cases_follow_templates(self):
        assert len(ALL_CASES[60:]) == 40
        assert ALL_CASES[60]["id"] == "flow_path"
        followup = next(case for case in ALL_CASES if case["id"] == "followup_fields")
        assert followup["history"] == ["项目里 DNS 插件负责什么？"]


class TestSelectCases:
    def test_ids_filter_keeps_file_order(self):
        selected = lib.select_cases(ALL_CASES, ids=["flow_path", "protocol_dns_1"])
        assert [case["id"] for case in selected] == ["protocol_dns_1", "flow_path"]

    def test_expected_filter(self):
        selected = lib.select_cases(ALL_CASES, expected="unanswered")
        assert selected
        assert all(case["expected"] == "unanswered" for case in selected)

    def test_unknown_id_raises_and_lists_options(self):
        with pytest.raises(ValueError, match="未知用例 id") as exc_info:
            lib.select_cases(ALL_CASES, ids=["no_such_case"])
        assert "protocol_dns_1" in str(exc_info.value)

    def test_sample_deterministic(self):
        first = lib.select_cases(ALL_CASES, sample=10)
        second = lib.select_cases(ALL_CASES, sample=10)
        assert len(first) == 10
        assert [case["id"] for case in first] == [case["id"] for case in second]

    def test_sample_covers_all_expected_labels(self):
        selected = lib.select_cases(ALL_CASES, sample=8)
        assert {case["expected"] for case in selected} == set(lib.EXPECTED_LABELS)

    def test_sample_larger_than_pool_truncates(self):
        pool = [case for case in ALL_CASES if case["expected"] == "unanswered"]
        selected = lib.select_cases(ALL_CASES, expected="unanswered", sample=100)
        assert len(selected) == len(pool)

    def test_sample_must_be_positive(self):
        with pytest.raises(ValueError, match="正整数"):
            lib.select_cases(ALL_CASES, sample=0)


def _response(status, citations=None):
    return {"answer_status": status, "answer": "...", "citations": citations or []}


class TestClassifyCase:
    @pytest.mark.parametrize(
        ("expected", "response", "error", "category"),
        [
            ("answered", None, "HTTPStatusError: 503", "system_error"),
            ("answered", _response("system_error"), "", "system_error"),
            ("answered", None, "", "system_error"),  # 无响应且无错误，防御分支
            ("answered", _response("answered"), "", "pending_review"),
            ("answered", _response("unanswered"), "", "retrieval_miss"),
            ("answered", _response("unanswered", [{"ref": 1}]), "", "false_reject"),
            ("answered", _response("needs_clarification"), "", "retrieval_miss"),
            ("answered", _response("needs_clarification", [{"ref": 1}]), "", "insufficient_evidence"),
            ("unanswered", _response("answered"), "", "generation_error"),
            ("needs_clarification", _response("answered"), "", "generation_error"),
            ("not_applicable", _response("answered"), "", "generation_error"),
            ("unanswered", _response("unanswered"), "", "pending_review"),
            ("unanswered", _response("needs_clarification"), "", "pending_review"),
            ("not_applicable", _response("unanswered"), "", "pending_review"),
        ],
    )
    def test_mapping(self, expected, response, error, category):
        result = lib.classify_case({"id": "x", "expected": expected}, response, error)
        assert result["auto_category"] == category
        assert result["auto_basis"]

    def test_missing_citations_treated_as_empty(self):
        result = lib.classify_case({"id": "x", "expected": "answered"}, {"answer_status": "unanswered"}, "")
        assert result["auto_category"] == "retrieval_miss"

    def test_reject_mismatch_basis_mentions_manual_review(self):
        result = lib.classify_case({"id": "x", "expected": "unanswered"}, _response("needs_clarification"), "")
        assert "拒答类别不一致" in result["auto_basis"]


class TestValidateReview:
    def test_valid_entry(self):
        assert lib.validate_review("a", {"correct": True, "failure_category": "retrieval_miss"}) == []

    def test_bad_failure_category(self):
        errors = lib.validate_review("a", {"failure_category": "nope"})
        assert len(errors) == 1
        assert "failure_category" in errors[0]

    def test_non_bool_field(self):
        errors = lib.validate_review("a", {"correct": "yes"})
        assert len(errors) == 1
        assert "correct" in errors[0]

    def test_non_dict_entry(self):
        assert lib.validate_review("a", "oops")


def _run_payload():
    return {
        "version": 1,
        "generated_at": "2026-09-13T00:00:00+00:00",
        "records": [
            lib.build_run_record(
                {"id": "a", "question": "q1", "expected": "answered"},
                {"answer_status": "answered", "answer": "x", "citations": []}, "", 12,
            ),
            lib.build_run_record(
                {"id": "b", "question": "q2", "expected": "unanswered"},
                {"answer_status": "answered", "answer": "y", "citations": [{"ref": 1}]}, "", 34,
            ),
        ],
    }


class TestMergeReview:
    def test_merge_applies_fields_and_defaults(self):
        payload = _run_payload()
        merged, errors = lib.merge_review(payload, {
            "reviewer": "张三",
            "reviewed_at": "2026-09-13T10:00:00+00:00",
            "reviews": {"a": {"correct": True, "citation_ok": False}},
        })
        assert errors == []
        review = merged["records"][0]["human_review"]
        assert review["correct"] is True
        assert review["citation_ok"] is False
        assert review["claims_supported"] is None  # 未提供字段保持 None
        assert review["reviewer"] == "张三"
        assert review["reviewed_at"] == "2026-09-13T10:00:00+00:00"
        assert merged["records"][1]["human_review"] == lib.empty_review()

    def test_entry_level_reviewer_overrides_default(self):
        merged, errors = lib.merge_review(_run_payload(), {
            "reviewer": "张三",
            "reviews": {"a": {"correct": True, "reviewer": "李四"}},
        })
        assert errors == []
        assert merged["records"][0]["human_review"]["reviewer"] == "李四"

    def test_unknown_case_id_reported(self):
        _, errors = lib.merge_review(_run_payload(), {"reviews": {"ghost": {"correct": True}}})
        assert len(errors) == 1
        assert "未知 case id" in errors[0]

    def test_invalid_entries_not_merged(self):
        payload = _run_payload()
        _, errors = lib.merge_review(payload, {
            "reviews": {"a": {"failure_category": "bad"}, "b": {"correct": 1}},
        })
        assert len(errors) == 2
        assert payload["records"][0]["human_review"] == lib.empty_review()
        assert payload["records"][1]["human_review"] == lib.empty_review()


class TestSummarizeRuns:
    def _payload(self):
        record_a = lib.build_run_record(
            {"id": "a", "question": "q", "expected": "answered"},
            {"answer_status": "answered", "answer": "x", "citations": []}, "", 10,
        )
        record_a["human_review"].update({"correct": True, "failure_category": "generation_error"})
        record_b = lib.build_run_record(
            {"id": "b", "question": "q", "expected": "answered"},
            {"answer_status": "unanswered", "answer": "", "citations": []}, "", 20,
        )
        record_c = lib.build_run_record(
            {"id": "c", "question": "q", "expected": "unanswered"}, None, "HTTPStatusError: 503", 30,
        )
        return {"version": 1, "generated_at": "2026-09-13T00:00:00+00:00", "records": [record_a, record_b, record_c]}

    def test_totals_categories_and_coverage(self):
        summary = lib.summarize_runs([self._payload()])
        assert summary["human_review_required"] is True
        assert summary["disclaimer"] == lib.DISCLAIMER
        assert summary["totals"] == {"cases": 3, "status_match": 1, "status_mismatch": 1, "errors": 1}
        # 逐标签桶：P2 起在原有 4 个计数之外附带 reviewed/verdicts（只增不改）
        answered = summary["by_expected"]["answered"]
        assert {key: answered[key] for key in ("cases", "status_match", "status_mismatch", "errors")} == {
            "cases": 2, "status_match": 1, "status_mismatch": 1, "errors": 0,
        }
        assert answered["reviewed"] == 1
        assert answered["verdicts"]["correct"] == {"true": 1, "false": 0, "unrated": 1}
        assert summary["by_expected"]["unanswered"]["errors"] == 1
        # auto 初判与人工确认分类分列：b 召回缺失 + c 系统错误进 auto；人工只有 a 的 generation_error
        assert summary["auto_failure_categories"]["retrieval_miss"] == 1
        assert summary["auto_failure_categories"]["system_error"] == 1
        assert summary["auto_failure_categories"]["generation_error"] == 0
        assert summary["human_failure_categories"]["generation_error"] == 1
        assert summary["human_failure_categories"]["retrieval_miss"] == 0
        assert summary["review_coverage"] == {"reviewed": 1, "total": 3, "rate": round(1 / 3, 4)}
        assert summary["runs"] == [
            {"generated_at": "2026-09-13T00:00:00+00:00", "cases": 3, "status_match": 1, "status_mismatch": 1, "errors": 1}
        ]

    def test_empty(self):
        summary = lib.summarize_runs([])
        assert summary["totals"]["cases"] == 0
        assert summary["review_coverage"] == {"reviewed": 0, "total": 0, "rate": 0.0}


class TestRunnerRunMode:
    def _args(self, tmp_path, *extra):
        return runner.build_parser().parse_args(["--run", "--output", str(tmp_path / "run.json"), *extra])

    def test_run_records_payload_and_hides_token(self, tmp_path, monkeypatch):
        monkeypatch.setenv("RAG_EVAL_TOKEN", "secret-token")
        requests: list[httpx.Request] = []

        def handler(request: httpx.Request) -> httpx.Response:
            requests.append(request)
            body = json.loads(request.content.decode("utf-8"))
            return httpx.Response(200, json={
                "session_id": "sess-1",
                "answer": f"回答：{body['message']}",
                "answer_status": "answered",
                "citations": [{"source": "engine_dns.c"}],
            })

        args = self._args(tmp_path, "--case", "followup_fields")
        assert asyncio.run(runner.run_mode(args, transport=httpx.MockTransport(handler))) == 0

        # history 顺序调用：先历史问题（无 session），后正文（带上一跳 session）
        assert len(requests) == 2
        assert all(request.headers.get("Authorization") == "Bearer secret-token" for request in requests)
        bodies = [json.loads(request.content.decode("utf-8")) for request in requests]
        assert bodies[0] == {"message": "项目里 DNS 插件负责什么？", "session_id": None}
        assert bodies[1] == {"message": "它从报文中提取哪些字段？请引用代码。", "session_id": "sess-1"}

        raw = (tmp_path / "run.json").read_text(encoding="utf-8")
        assert "secret-token" not in raw  # 令牌绝不落盘
        payload = json.loads(raw)
        assert payload["version"] == 1
        assert payload["human_review_required"] is True
        assert payload["disclaimer"] == lib.DISCLAIMER
        assert len(payload["records"]) == 1
        record = payload["records"][0]
        assert record["case"]["id"] == "followup_fields"
        assert record["case"]["history"] == ["项目里 DNS 插件负责什么？"]
        assert record["answer_status"] == "answered"
        assert record["status_matches"] is True
        assert record["http_error"] == ""
        assert isinstance(record["elapsed_ms"], int)
        assert record["auto"]["auto_category"] == "pending_review"
        assert record["human_review"] == lib.empty_review()

    def test_http_error_recorded_not_raised(self, tmp_path, monkeypatch):
        monkeypatch.delenv("RAG_EVAL_TOKEN", raising=False)

        def handler(request: httpx.Request) -> httpx.Response:
            assert "Authorization" not in request.headers  # 无令牌时不发该头
            return httpx.Response(503, json={"detail": "busy"})

        args = self._args(tmp_path, "--case", "flow_path")
        assert asyncio.run(runner.run_mode(args, transport=httpx.MockTransport(handler))) == 0
        record = json.loads((tmp_path / "run.json").read_text(encoding="utf-8"))["records"][0]
        assert "503" in record["http_error"]
        assert record["answer_status"] is None
        assert record["status_matches"] is None
        assert record["auto"]["auto_category"] == "system_error"

    def test_limit_caps_selection(self, tmp_path, monkeypatch):
        monkeypatch.delenv("RAG_EVAL_TOKEN", raising=False)

        def handler(request: httpx.Request) -> httpx.Response:
            return httpx.Response(200, json={"session_id": "s", "answer": "x",
                                             "answer_status": "answered", "citations": []})

        args = self._args(tmp_path, "--limit", "2")
        assert asyncio.run(runner.run_mode(args, transport=httpx.MockTransport(handler))) == 0
        records = json.loads((tmp_path / "run.json").read_text(encoding="utf-8"))["records"]
        assert [record["case"]["id"] for record in records] == ["protocol_dns_1", "protocol_dns_2"]


class TestRunnerListMode:
    def test_list_all_cases(self, capsys):
        assert runner.main([]) == 0
        payload = json.loads(capsys.readouterr().out)
        assert payload["cases"] == 100
        assert payload["human_review_required"] is True
        assert len(payload["items"]) == 100

    def test_list_respects_filters(self, capsys):
        assert runner.main(["--expected", "unanswered", "--sample", "4"]) == 0
        payload = json.loads(capsys.readouterr().out)
        assert payload["cases"] == 4
        assert all(item["expected"] == "unanswered" for item in payload["items"])

    def test_list_unknown_case_id_exits(self):
        with pytest.raises(SystemExit):
            runner.main(["--case", "ghost"])


class TestRunnerMergeAndSummarize:
    def _write_run(self, path: Path) -> None:
        records = [
            lib.build_run_record(
                {"id": "a", "question": "q", "expected": "answered"},
                {"answer_status": "answered", "answer": "x", "citations": []}, "", 5,
            )
        ]
        path.write_text(
            json.dumps({"version": 1, "generated_at": "2026-09-13T00:00:00+00:00",
                        "human_review_required": True, "records": records}, ensure_ascii=False),
            encoding="utf-8",
        )

    def test_merge_mode_yaml_overwrites_input(self, tmp_path, capsys):
        run_file = tmp_path / "run.json"
        self._write_run(run_file)
        scores = tmp_path / "scores.yml"
        scores.write_text('reviewer: 张三\nreviews:\n  a:\n    correct: true\n    notes: "通过"\n', encoding="utf-8")
        assert runner.main(["--input", str(run_file), "--scores", str(scores)]) == 0
        merged = json.loads(run_file.read_text(encoding="utf-8"))
        assert merged["records"][0]["human_review"]["correct"] is True
        assert merged["records"][0]["human_review"]["reviewer"] == "张三"
        assert "1/1" in capsys.readouterr().out

    def test_merge_mode_json_unknown_id_nonzero_exit(self, tmp_path):
        run_file = tmp_path / "run.json"
        self._write_run(run_file)
        scores = tmp_path / "scores.json"
        scores.write_text(json.dumps({"reviews": {"ghost": {"correct": True}}}), encoding="utf-8")
        assert runner.main(["--input", str(run_file), "--scores", str(scores)]) == 1

    def test_summarize_mode(self, tmp_path, capsys):
        run_file = tmp_path / "run.json"
        self._write_run(run_file)
        assert runner.main(["--summarize", str(run_file)]) == 0
        summary = json.loads(capsys.readouterr().out)
        assert summary["totals"] == {"cases": 1, "status_match": 1, "status_mismatch": 0, "errors": 0}
        assert summary["human_review_required"] is True


# ---------------------------------------------------------------------------
# P2：引用级盲审、门禁判定与基线（全部离线）
# ---------------------------------------------------------------------------


def _make_payload(entries: list[dict]) -> dict:
    """构造 run payload：entries 每项 = id/expected/status/citations/review/elapsed_ms。"""
    records = []
    for entry in entries:
        expected = entry.get("expected", "answered")
        record = lib.build_run_record(
            {"id": entry["id"], "question": "q", "expected": expected},
            {
                "answer_status": entry.get("status", expected),
                "answer": entry.get("answer", ""),
                "citations": entry.get("citations", []),
            },
            entry.get("error", ""),
            entry.get("elapsed_ms", 1000),
        )
        record["human_review"].update(entry.get("review", {}))
        records.append(record)
    return {"version": 1, "generated_at": "2026-09-13T00:00:00+00:00", "records": records}


def _metrics(entries: list[dict]) -> dict:
    return lib.acceptance_metrics(lib.summarize_runs([_make_payload(entries)]))


class TestCitationReviewSchema:
    """引用级字段：越界编号必须报错，不能被静默丢弃。"""

    def test_out_of_range_is_an_error(self):
        entry = {"citation_ok": False, "unsupported_citation_indexes": [3]}
        errors = lib.validate_review("a", entry, citation_count=2)
        assert errors and "越界" in errors[0]
        assert lib.validate_review("a", entry, citation_count=3) == []

    def test_lower_bound_and_duplicates(self):
        """引用编号是 1-based：0 与负数都是笔误，必须报错；重复编号只报一次。"""
        for bad in (0, -1):
            errors = lib.validate_review("a", {"unsupported_citation_indexes": [bad]}, citation_count=2)
            assert errors and "越界" in errors[0]
        assert len(lib.validate_review("a", {"unsupported_citation_indexes": [9, 9]}, citation_count=3)) == 1

    @pytest.mark.parametrize("bad", [["3"], [True], "3", {"1": 1}])
    def test_non_int_list_is_an_error(self, bad):
        assert lib.validate_review("a", {"unsupported_citation_indexes": bad}, citation_count=5)

    def test_missing_evidence_must_be_bool(self):
        assert lib.validate_review("a", {"missing_evidence": "yes"})
        assert lib.validate_review("a", {"missing_evidence": False}) == []

    def test_old_score_files_stay_valid(self):
        """P2 新增字段全是可选的：旧评分文件不因此失效。"""
        assert lib.validate_review("a", {"correct": True, "citation_ok": False}) == []
        review = lib.empty_review()
        assert review["unsupported_citation_indexes"] is None
        assert review["missing_evidence"] is None

    def test_merge_rejects_out_of_range_and_keeps_other_entries(self):
        payload = _make_payload([{"id": "a", "citations": [{"chunk_id": "li_1"}]}])
        merged, errors = lib.merge_review(payload, {"reviews": {
            "a": {"unsupported_citation_indexes": [2]},
        }})
        assert errors and "越界" in errors[0]
        assert merged["records"][0]["human_review"]["unsupported_citation_indexes"] is None

    def test_merge_accepts_in_range(self):
        payload = _make_payload([{"id": "a", "citations": [{"chunk_id": "li_1"}, {"chunk_id": "li_2"}]}])
        merged, errors = lib.merge_review(payload, {"reviews": {
            "a": {"unsupported_citation_indexes": [2], "missing_evidence": True},
        }})
        assert errors == []
        assert merged["records"][0]["human_review"]["unsupported_citation_indexes"] == [2]
        assert merged["records"][0]["human_review"]["missing_evidence"] is True


class TestCitationContexts:
    def test_picks_the_sentence_carrying_the_marker(self):
        answer = "第一句。[1] DNS 插件解析 Question 段。另一句。[2] 见别处。"
        assert lib.citation_contexts(answer, 1) == ["[1] DNS 插件解析 Question 段。"]
        assert lib.citation_contexts(answer, 2) == ["[2] 见别处。"]
        assert lib.citation_contexts(answer, 9) == []

    def test_marker_is_not_matched_inside_a_longer_index(self):
        """[1] 不得被 [10] 满足——否则盲审会拿到错误的论断句。"""
        assert lib.citation_contexts("结论见 [10]。", 1) == []
        assert lib.citation_contexts("结论见 [10]。", 10) == ["结论见 [10]。"]

    def test_no_punctuation_returns_whole_text(self):
        assert lib.citation_contexts("[1] 无标点的一整段", 1) == ["[1] 无标点的一整段"]


class TestEvidenceReport:
    def _record(self) -> dict:
        record = lib.build_run_record(
            {"id": "a", "question": "DNS 插件做什么？", "expected": "answered"},
            {"answer_status": "answered", "answer": "如上。[1] DNS 插件解析 Question 段。",
             "citations": [{"chunk_id": "li_1", "source_path": "docs/dns.md",
                            "section": "解析流程", "title": "DNS", "score": 0.87}]},
            "", 1200,
        )
        return record

    def test_pairs_claim_with_evidence(self):
        text = "\n".join(lib.evidence_report(self._record(), {
            "li_1": {"content": "证据正文：Question 段", "source_path": "docs/dns.md"},
        }))
        assert "论断：[1] DNS 插件解析 Question 段。" in text
        assert "证据正文：Question 段" in text
        assert "score=0.87" in text
        assert "docs/dns.md" in text

    def test_missing_source_is_flagged(self):
        text = "\n".join(lib.evidence_report(self._record(), {"li_1": None}))
        assert "取不到证据正文" in text

    def test_answer_without_marker_is_flagged(self):
        record = self._record()
        record["answer"] = "没有任何编号的答案。"
        text = "\n".join(lib.evidence_report(record, {"li_1": {"content": "x"}}))
        assert "答案中未出现该编号" in text

    def test_no_citations(self):
        record = self._record()
        record["citations"] = []
        text = "\n".join(lib.evidence_report(record, {}))
        assert "引用：无" in text

    def test_evidence_is_truncated_but_flagged(self):
        text = "\n".join(lib.evidence_report(self._record(), {"li_1": {"content": "长" * 900}},
                                             evidence_chars=100))
        assert "证据正文共 900 字符" in text


class TestAcceptanceMetrics:
    def test_correct_rate_counts_answerable_only(self):
        metrics = _metrics([
            {"id": "a1", "review": {"correct": True}},
            {"id": "a2", "review": {"correct": False}},
            {"id": "u1", "expected": "unanswered", "status": "unanswered", "review": {"correct": False}},
        ])
        assert metrics["correct_rate"] == 0.5  # 1/2 可答题；u1 不进这个口径

    def test_unknown_is_not_zero(self):
        """没评分不等于评了不通过：比率类指标必须是 None（未知）。"""
        metrics = _metrics([{"id": "a1"}])
        assert metrics["correct_rate"] is None
        assert metrics["citation_ok_rate"] is None
        assert metrics["claims_supported_rate"] is None

    def test_false_reject_rate_uses_answerable_denominator(self):
        metrics = _metrics([
            {"id": "a1", "review": {"failure_category": "false_reject"}},
            {"id": "a2"},
            {"id": "u1", "expected": "unanswered", "status": "unanswered",
             "review": {"failure_category": "false_reject"}},
        ])
        assert metrics["false_reject_rate"] == 0.5  # 1/2 可答题

    def test_system_error_rate_and_pending_review(self):
        metrics = _metrics([
            {"id": "a1"},  # 状态与 expected 一致：正常题，不算「初判未定」
            {"id": "a2", "status": "unanswered", "error": "ReadTimeout"},  # 有错误 → system_error，优先
            {"id": "u1", "expected": "unanswered", "status": "needs_clarification"},  # 拒答类别不一致 → 未定
        ])
        assert metrics["system_error_rate"] == round(1 / 3, 4)
        assert metrics["pending_review_count"] == 1

    def test_healthy_run_has_no_pending_review(self):
        """门禁是「机器也说不出哪里不对的题数上限」：全绿的一轮必须是 0，
        否则 --max-pending-review 在健康轮必挂，等于一条永远误报的门禁。"""
        metrics = _metrics([
            {"id": "a1", "status": "answered"},
            {"id": "u1", "expected": "unanswered", "status": "unanswered"},
        ])
        assert metrics["pending_review_count"] == 0

    def test_latency_stats(self):
        metrics = _metrics([
            {"id": "a1", "elapsed_ms": 100},
            {"id": "a2", "elapsed_ms": 200},
            {"id": "a3", "elapsed_ms": 300},
        ])
        assert metrics["avg_latency_ms"] == 200
        assert metrics["p95_latency_ms"] == 300

    def test_citation_level_counts(self):
        metrics = _metrics([
            {"id": "a1", "citations": [{"chunk_id": "li_1"}, {"chunk_id": "li_2"}],
             "review": {"unsupported_citation_indexes": [1], "missing_evidence": True}},
            {"id": "a2", "review": {"unsupported_citation_indexes": [1, 2]}},
        ])
        assert metrics["unsupported_citations"] == 3
        assert metrics["missing_evidence"] == 1


class TestEvaluateAcceptance:
    def test_incomplete_without_full_review(self):
        summary = lib.summarize_runs([_make_payload([{"id": "a1"}, {"id": "a2", "review": {"correct": True}}])])
        verdict = lib.evaluate_acceptance(summary, {"min_correct_rate": 0.0})
        assert verdict["conclusion"] == "incomplete"
        assert "未评分即未验收" in verdict["blocking"][0]

    def test_no_gates_reports_facts_only(self):
        summary = lib.summarize_runs([_make_payload([{"id": "a1", "review": {"correct": True}}])])
        verdict = lib.evaluate_acceptance(summary)
        assert verdict["conclusion"] == "no_gates"

    def test_pass_and_fail(self):
        payload = _make_payload([
            {"id": "a1", "review": {"correct": True, "citation_ok": True}},
            {"id": "a2", "review": {"correct": True, "citation_ok": False}},
        ])
        summary = lib.summarize_runs([payload])
        assert lib.evaluate_acceptance(summary, {"min_correct_rate": 1.0})["conclusion"] == "pass"
        failed = lib.evaluate_acceptance(summary, {"min_citation_ok_rate": 1.0})
        assert failed["conclusion"] == "fail"
        assert "引用支撑率" in failed["blocking"][0]

    def test_metric_without_samples_fails_the_gate(self):
        """没有样本时报「未满足」而不是当成 0 通过。"""
        summary = lib.summarize_runs([_make_payload([{"id": "a1", "review": {"privacy_pass": True}}])])
        verdict = lib.evaluate_acceptance(summary, {"min_citation_ok_rate": 0.9})
        assert verdict["conclusion"] == "fail"
        assert verdict["checks"][0]["actual"] is None

    def test_unknown_gate_raises(self):
        with pytest.raises(ValueError, match="未知门禁"):
            lib.evaluate_acceptance(lib.summarize_runs([]), {"min_vibes": 0.9})


class TestBaseline:
    def test_records_cases_and_metrics(self):
        payload = _make_payload([{"id": "a1", "citations": [{"chunk_id": "li_1"}],
                                  "review": {"correct": True, "citation_ok": True}}])
        baseline = lib.baseline_payload([payload], generated_at="2026-09-17T00:00:00+00:00")
        assert baseline["version"] == lib.BASELINE_VERSION
        assert baseline["generated_at"] == "2026-09-17T00:00:00+00:00"
        assert baseline["human_review_required"] is True
        assert baseline["metrics"]["correct_rate"] == 1.0
        assert baseline["cases"]["a1"]["human_review"]["citation_ok"] is True
        assert baseline["cases"]["a1"]["answer_status"] == "answered"

    def test_regression_is_reported(self):
        baseline = lib.baseline_payload([_make_payload([
            {"id": "a1", "review": {"correct": True, "citation_ok": True}},
        ])], generated_at="T1")
        current = lib.baseline_payload([_make_payload([
            {"id": "a1", "status": "unanswered",
             "review": {"correct": False, "citation_ok": False}},
        ])], generated_at="T2")
        diff = lib.compare_with_baseline(baseline, current)
        assert "correct_rate" in diff["regressions"]
        assert "a1.correct" in diff["regressions"]
        fields = {(change["case"], change["field"]) for change in diff["cases"]}
        assert ("a1", "answer_status") in fields
        assert ("a1", "citation_ok") in fields

    def test_improvement_is_not_a_regression(self):
        baseline = lib.baseline_payload([_make_payload([{"id": "a1", "review": {"correct": False}}])])
        current = lib.baseline_payload([_make_payload([{"id": "a1", "review": {"correct": True}}])])
        diff = lib.compare_with_baseline(baseline, current)
        assert diff["regressions"] == []
        assert diff["cases"] == []

    def test_latency_noise_is_tolerated(self):
        base = lib.baseline_payload([_make_payload([{"id": "a1", "elapsed_ms": 1000,
                                                     "review": {"correct": True}}])])
        noisy = lib.baseline_payload([_make_payload([{"id": "a1", "elapsed_ms": 1050,
                                                      "review": {"correct": True}}])])
        assert "avg_latency_ms" not in lib.compare_with_baseline(base, noisy)["regressions"]
        slow = lib.baseline_payload([_make_payload([{"id": "a1", "elapsed_ms": 2000,
                                                     "review": {"correct": True}}])])
        assert "avg_latency_ms" in lib.compare_with_baseline(base, slow)["regressions"]

    def test_metric_absent_from_baseline_is_skipped(self):
        base = lib.baseline_payload([_make_payload([{"id": "a1"}])], generated_at="T")
        base["metrics"]["avg_latency_ms"] = None  # 未知指标不参与比较
        current = lib.baseline_payload([_make_payload([{"id": "a1"}])], generated_at="T")
        assert all(change["metric"] != "avg_latency_ms"
                   for change in lib.compare_with_baseline(base, current)["metrics"])


class TestRunnerGatesBaselineEvidence:
    def _write_run(self, path: Path, entries: list[dict]) -> None:
        path.write_text(json.dumps(_make_payload(entries), ensure_ascii=False), encoding="utf-8")

    def test_no_gates_keeps_exit_code_zero(self, tmp_path, capsys):
        run_file = tmp_path / "run.json"
        self._write_run(run_file, [{"id": "a1"}])  # 未评分
        assert runner.main(["--summarize", str(run_file)]) == 0
        assert json.loads(capsys.readouterr().out)["human_review_required"] is True

    def test_incomplete_exits_two(self, tmp_path, capsys):
        run_file = tmp_path / "run.json"
        self._write_run(run_file, [{"id": "a1"}, {"id": "a2", "review": {"correct": True}}])
        assert runner.main(["--summarize", str(run_file), "--min-correct-rate", "0.5"]) == 2
        report = json.loads(capsys.readouterr().out)
        assert report["verdict"]["conclusion"] == "incomplete"

    def test_fail_exits_one_and_pass_exits_zero(self, tmp_path, capsys):
        run_file = tmp_path / "run.json"
        self._write_run(run_file, [{"id": "a1", "review": {"citation_ok": False}}])
        assert runner.main(["--summarize", str(run_file), "--min-citation-ok-rate", "1.0"]) == 1
        capsys.readouterr()
        assert runner.main(["--summarize", str(run_file), "--min-citation-ok-rate", "0.0"]) == 0
        report = json.loads(capsys.readouterr().out)
        assert report["verdict"]["conclusion"] == "pass"

    def test_write_and_compare_baseline(self, tmp_path, capsys):
        run_file = tmp_path / "run.json"
        baseline_file = tmp_path / "baseline.json"
        self._write_run(run_file, [{"id": "a1", "review": {"correct": True, "citation_ok": True}}])
        assert runner.main(["--summarize", str(run_file), "--min-correct-rate", "1.0",
                            "--write-baseline", str(baseline_file)]) == 0
        capsys.readouterr()
        assert json.loads(baseline_file.read_text(encoding="utf-8"))["metrics"]["correct_rate"] == 1.0

        self._write_run(run_file, [{"id": "a1", "status": "unanswered",
                                    "review": {"correct": False, "citation_ok": True}}])
        assert runner.main(["--summarize", str(run_file), "--min-correct-rate", "1.0",
                            "--baseline", str(baseline_file)]) == 1
        report = json.loads(capsys.readouterr().out)
        assert "correct_rate" in report["baseline_diff"]["regressions"]

    def test_evidence_mode_prints_claim_and_evidence(self, tmp_path, capsys, monkeypatch):
        run_file = tmp_path / "run.json"
        self._write_run(run_file, [{
            "id": "a1",
            "answer": "如上。[1] DNS 插件解析 Question 段。",
            "citations": [{"chunk_id": "li_1", "source_path": "docs/dns.md",
                           "section": "s", "title": "t", "score": 0.87}],
        }])
        monkeypatch.setattr(runner, "_lookup_source", lambda chunk_id: {"content": "证据正文"})
        assert runner.main(["--evidence", str(run_file)]) == 0
        out = capsys.readouterr().out
        assert "论断：[1] DNS 插件解析 Question 段。" in out
        assert "证据正文" in out

    def test_evidence_mode_unknown_case_exits(self, tmp_path):
        run_file = tmp_path / "run.json"
        self._write_run(run_file, [{"id": "a1"}])
        with pytest.raises(SystemExit):
            runner.main(["--evidence", str(run_file), "--case", "ghost"])

    def test_lookup_source_bootstraps_backend_on_sys_path(self, tmp_path):
        """盲审模式必须自己把 `backend/` 加进 sys.path。

        实测踩到：按文档 `cd backend && python tests/rag_eval/product_acceptance_runner.py
        --evidence run.json` 调用时，Python 只把**脚本目录** `tests/rag_eval` 放进
        sys.path（**不含 cwd**），于是每条引用都取不到正文、全篇打成
        「取证据正文出错：ModuleNotFoundError: No module named 'app'」——
        看起来像证据库坏了，实际是 import 路径没搭好。

        必须在子进程里验证：pytest 进程早已 import 过 `app`，`find_spec` 会走
        `sys.modules` 短路，在进程内怎么改 sys.path 都测不出这个坑。
        """
        root = Path(runner.__file__).resolve().parents[2]
        probe = tmp_path / "probe.py"
        probe.write_text(
            "import importlib.util, json, os, sys\n"
            "backend = os.environ['PROBE_BACKEND']\n"
            # 复现脚本语义：sys.path[0] 是脚本目录，不是 cwd
            "sys.path.insert(0, os.path.join(backend, 'tests', 'rag_eval'))\n"
            "import product_acceptance_runner as r\n"
            "before = importlib.util.find_spec('app') is None\n"
            "r._ensure_app_importable()\n"
            "after = importlib.util.find_spec('app') is not None\n"
            "print(json.dumps({'before': before, 'after': after}))\n",
            encoding="utf-8",
        )
        env = {k: v for k, v in os.environ.items() if k != "PYTHONPATH"}
        env["PROBE_BACKEND"] = str(root)
        completed = subprocess.run(
            [sys.executable, str(probe)], cwd=root, env=env, capture_output=True, text=True, check=False,
        )
        assert completed.returncode == 0, completed.stderr
        assert json.loads(completed.stdout) == {"before": True, "after": True}

    def test_lookup_source_imports_app_before_dispatch(self, monkeypatch):
        """`_lookup_source` 必须先 bootstrap 再 import——否则修好的是死代码。"""
        calls: list[str] = []
        monkeypatch.setattr(runner, "_ensure_app_importable", lambda: calls.append("boot"))

        def _record_import(name, *args, **kwargs):
            calls.append(name)
            raise RuntimeError("到此为止")

        monkeypatch.setattr("builtins.__import__", _record_import)
        result = runner._lookup_source("li_1")
        assert calls[0] == "boot"
        assert result["content"].startswith("（取证据正文出错：")

    def test_timeout_is_configurable(self, monkeypatch):
        parser = runner.build_parser()
        assert runner._timeout(parser.parse_args(["--timeout", "300"])) == 300
        monkeypatch.setenv("RAG_EVAL_TIMEOUT_SEC", "200")
        assert runner._timeout(parser.parse_args([])) == 200
        monkeypatch.delenv("RAG_EVAL_TIMEOUT_SEC")
        assert runner._timeout(parser.parse_args([])) == runner.REQUEST_TIMEOUT_SEC
