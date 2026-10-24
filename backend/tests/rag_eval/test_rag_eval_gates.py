"""rag_eval_runner 环境元信息与门禁判定的纯函数单测（无网络/数据库，进默认集）。"""

from __future__ import annotations

import json
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import rag_eval_runner as runner  # noqa: E402
from rag_eval_runner import (  # noqa: E402
    RETRIEVAL_PARAM_FIELDS,
    CaseResult,
    compare_env,
    evaluate_gates,
    load_baseline,
    write_baseline,
)


def _meta(**overrides):
    """最小合法环境元信息；overrides 覆盖顶层键。"""
    meta = {
        "code_revision": "abc123",
        "rag_provider": "llama_index",
        "retrieval_strategy": "hybrid_fusion_v2",
        "model_name": "m1",
        "embedding_model_name": "e1",
        "llama_index_fts_config": "simple",
        "llama_index_hnsw_enabled": False,
        "active_release_id": "rel1",
        "release_configuration": "cfg1",
        "retrieval_params": {"retrieval_w_semantic": 0.38, "retrieval_rrf_k": 60},
        "cases": {"file": "queries.yml", "count": 46, "sha256": "deadbeef"},
        "limit": 15,
    }
    meta.update(overrides)
    return meta


def _result(case_id="c1", error=""):
    return CaseResult(case_id=case_id, category="分类", question="问题", hit_ranks=[1], error=error)


def _summary(recall15=1.0, avg=2.0, p95=5.0):
    overall = {"recall@15": recall15}
    if avg is not None:
        overall["avg_latency_sec"] = avg
    if p95 is not None:
        overall["p95_latency_sec"] = p95
    return {"overall": overall}


def _gates(**kwargs):
    defaults = {
        "results": [_result()],
        "summary": _summary(),
        "baseline": {},
        "current_metadata": _meta(),
        "fail_on_regression": False,
        "regression_threshold": 0.0,
        "max_avg_latency": None,
        "max_p95_latency": None,
        "ignore_env_mismatch": False,
    }
    defaults.update(kwargs)
    return evaluate_gates(**defaults)


class TestCollectEnvMetadata:
    def _collect(self, tmp_path, monkeypatch):
        monkeypatch.setattr(runner, "_detect_code_revision", lambda: "testrev")
        monkeypatch.setattr(runner, "_detect_active_release", lambda: ("rel1", "cfg1"))
        settings = SimpleNamespace(
            rag_provider="llama_index",
            retrieval_strategy="hybrid_fusion_v2",
            model_name="m1",
            embedding_model_name="e1",
            llama_index_fts_config="simple",
            llama_index_hnsw_enabled=False,
            llm_api_base="https://should-not-leak.example",
            llm_api_key="sk-should-not-leak",
            **{name: 0.1 for name in RETRIEVAL_PARAM_FIELDS},
        )
        cases_path = tmp_path / "queries.yml"
        cases_path.write_text("cases:\n  - id: a\n", encoding="utf-8")
        return runner.collect_env_metadata(settings, cases_path, [{"id": "a"}], 15), cases_path

    def test_fields_and_cases_digest(self, tmp_path, monkeypatch):
        import hashlib

        metadata, cases_path = self._collect(tmp_path, monkeypatch)
        assert metadata["code_revision"] == "testrev"
        assert metadata["active_release_id"] == "rel1"
        assert metadata["release_configuration"] == "cfg1"
        assert metadata["model_name"] == "m1"
        assert len(metadata["retrieval_params"]) == 23
        cases_meta = metadata["cases"]
        assert cases_meta["file"] == "queries.yml"
        assert cases_meta["count"] == 1
        assert cases_meta["sha256"] == hashlib.sha256(cases_path.read_bytes()).hexdigest()

    def test_whitelist_excludes_credentials(self, tmp_path, monkeypatch):
        metadata, _ = self._collect(tmp_path, monkeypatch)
        payload = json.dumps(metadata, ensure_ascii=False).lower()
        for forbidden in ("api_key", "api_base", "secret", "sk-should-not-leak", "should-not-leak"):
            assert forbidden not in payload

    def test_release_detection_degrades_to_unknown(self, monkeypatch):
        # 老库无 rag_index_state 表等任何异常都降级 unknown，不影响评估主流程
        import app.services.rag_index_service as index_service

        monkeypatch.setattr(index_service, "active_release", lambda: (_ for _ in ()).throw(RuntimeError("no table")))
        assert runner._detect_active_release() == ("unknown", "unknown")


class TestCompareEnv:
    def test_identical_is_clean(self):
        blocking, warnings = compare_env(_meta(), _meta())
        assert blocking == [] and warnings == []

    def test_blocking_scalar_diff(self):
        blocking, warnings = compare_env(_meta(), _meta(model_name="m2"))
        assert any("model_name" in line for line in blocking)
        assert not any("code_revision" in line for line in blocking + warnings)

    def test_code_revision_is_warning_only(self):
        blocking, warnings = compare_env(_meta(), _meta(code_revision="def456"))
        assert blocking == []
        assert any("code_revision" in line for line in warnings)

    def test_unknown_side_skips_with_warning(self):
        blocking, warnings = compare_env(_meta(active_release_id="unknown"), _meta())
        assert blocking == []
        assert any("active_release_id" in line for line in warnings)

    def test_legacy_baseline_without_metadata(self):
        blocking, warnings = compare_env({}, _meta())
        assert blocking == []
        assert any("旧格式基线" in line for line in warnings)

    def test_retrieval_params_single_key_diff(self):
        current = _meta()
        current["retrieval_params"] = {**current["retrieval_params"], "retrieval_rrf_k": 61}
        blocking, _ = compare_env(_meta(), current)
        assert any("retrieval_rrf_k" in line for line in blocking)

    def test_cases_digest_and_count_diff(self):
        blocking, _ = compare_env(_meta(), _meta(cases={"file": "queries.yml", "count": 47, "sha256": "deadbeef"}))
        assert any("count" in line for line in blocking)
        blocking, _ = compare_env(_meta(), _meta(cases={"file": "queries.yml", "count": 46, "sha256": "beefde"}))
        assert any("sha256" in line for line in blocking)


class TestEvaluateGates:
    def test_all_green(self):
        exit_code, _ = _gates()
        assert exit_code == 0

    def test_case_failure_exits_1(self):
        exit_code, lines = _gates(results=[_result(), _result("c2", error="boom")])
        assert exit_code == 1
        assert any("c2" in line for line in lines)

    def test_regression_with_zero_threshold(self):
        baseline = {"overall": {"recall@15": 0.9}, "metadata": _meta()}
        exit_code, lines = _gates(
            summary=_summary(recall15=0.85), baseline=baseline, fail_on_regression=True,
        )
        assert exit_code == 1
        assert any("回归" in line for line in lines)

    def test_regression_threshold_relaxes(self):
        baseline = {"overall": {"recall@15": 0.9}, "metadata": _meta()}
        exit_code, _ = _gates(
            summary=_summary(recall15=0.85), baseline=baseline,
            fail_on_regression=True, regression_threshold=0.1,
        )
        assert exit_code == 0

    def test_env_mismatch_refuses_with_exit_2(self):
        baseline = {"overall": {"recall@15": 0.9}, "metadata": _meta(model_name="old-model")}
        exit_code, lines = _gates(baseline=baseline, fail_on_regression=True)
        assert exit_code == 2
        assert any("拒绝判定" in line for line in lines)

    def test_env_mismatch_overridden_falls_back_to_metric_compare(self):
        baseline = {"overall": {"recall@15": 0.9}, "metadata": _meta(model_name="old-model")}
        exit_code, lines = _gates(
            summary=_summary(recall15=0.8), baseline=baseline,
            fail_on_regression=True, ignore_env_mismatch=True,
        )
        assert exit_code == 1  # 忽略环境差异后继续指标比对，命中回归
        assert any("ignore-env-mismatch" in line for line in lines)

    def test_exit_2_takes_precedence_over_failures(self):
        baseline = {"overall": {"recall@15": 1.0}, "metadata": _meta(model_name="old-model")}
        exit_code, _ = _gates(results=[_result(error="boom")], baseline=baseline, fail_on_regression=True)
        assert exit_code == 2

    def test_legacy_baseline_still_compares_metrics(self):
        # 旧格式基线（无 metadata）：只警告，回归判定照常工作
        baseline = {"overall": {"recall@15": 0.9}}
        exit_code, lines = _gates(summary=_summary(recall15=0.8), baseline=baseline, fail_on_regression=True)
        assert exit_code == 1
        assert any("旧格式基线" in line for line in lines)

    def test_latency_gates(self):
        assert _gates(max_avg_latency=1.0)[0] == 1
        assert _gates(max_p95_latency=4.0)[0] == 1
        assert _gates(max_avg_latency=3.0, max_p95_latency=9.0)[0] == 0

    def test_latency_gate_without_data_warns_not_fails(self):
        exit_code, lines = _gates(summary=_summary(avg=None), max_avg_latency=1.0)
        assert exit_code == 0
        assert any("未生效" in line for line in lines)


class TestBaselineRoundtrip:
    def test_metadata_written_and_loaded(self, tmp_path):
        path = tmp_path / "baseline.json"
        write_baseline(path, "llama_index_hybrid", [_result()], _summary(), metadata=_meta())
        loaded = load_baseline(path)
        assert loaded["metadata"]["model_name"] == "m1"
        assert loaded["cases"]["c1"]["passed"] is True

    def test_partial_run_keeps_previous_cases_and_refreshes_metadata(self, tmp_path):
        path = tmp_path / "baseline.json"
        write_baseline(path, "llama_index_hybrid", [_result()], _summary(), metadata=_meta())
        write_baseline(
            path, "llama_index_hybrid", [_result("c2")], _summary(),
            previous=load_baseline(path), metadata=_meta(code_revision="def456"),
        )
        loaded = load_baseline(path)
        assert set(loaded["cases"]) == {"c1", "c2"}
        assert loaded["metadata"]["code_revision"] == "def456"

    def test_legacy_baseline_loads(self, tmp_path):
        path = tmp_path / "baseline.json"
        path.write_text(json.dumps({"version": 1, "overall": {"recall@15": 1.0}, "cases": {}}), encoding="utf-8")
        loaded = load_baseline(path)
        assert loaded.get("metadata") is None  # 缺 metadata 由 compare_env 兜底警告


if __name__ == "__main__":
    raise SystemExit(pytest.main([__file__, "-q"]))
