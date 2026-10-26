"""单端有效采集不冒充候选通过；离线组合拒绝缺端/缺同环境/缺完整轮次。"""
import asyncio
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from acceptance.load import run
from acceptance.load.statistics import (CORRECTNESS_KEYS, FROZEN_BACKEND_TREE, FROZEN_PYTHON_COMMIT,
                                        GROUPS, collection_status, compare_pair, evaluate)

ROOT = Path(__file__).resolve().parents[2]


def formal_report(runtime):
    def phase(number, seconds):
        return {"round": number, "target_seconds": seconds, "elapsed_including_drain_seconds": seconds+.1,
            "validity": {"valid": True}, "aborted_error_type": None,
            "groups": {g: {"n": 1000, "requests": 1000, "p95_ms": 1, "unexpected_errors": 0, "statuses": {"http_200": 1000}} for g in GROUPS},
            "resource_samples": [{"monotonic_seconds": t, "wall_seconds": 1000+t,
                "processes": {"app": {"rss_kib": 100000, "cpu_percent_one_core": 20}, "driver": {"rss_kib": 10000, "cpu_percent_one_core": 3}},
                "pg_active_by_database": {}} for t in range(seconds+1)]}
    return {"runtime": runtime, "qualification_only": False, "layout": "fixed-loopback-protocol-v2",
            "server_settings": {"pool": 32, "model_delay_seconds": 1, "retrieval_delay_seconds": .05, "mcp_delay_seconds": .2},
            "rounds": [phase(n, 600) for n in range(1, 4)], "warmups": [phase(n, 120) for n in range(1, 4)],
            "correctness": {k: True for k in CORRECTNESS_KEYS},
            "source_evidence": {"frozen_python_commit": FROZEN_PYTHON_COMMIT, "python_backend_tree": FROZEN_BACKEND_TREE,
                "source_commit": "clean-source", "dirty": False, "protocol_sha256": "same-protocol"},
            "environment": {"physical": {"host_id_sha256": "host", "platform": "os", "machine": "arm64", "cpu_model": "cpu", "memory_bytes": 16, "cpu_count": 10, "python": "3.11"},
                "postgres": {"host": "127.0.0.1", "port": 55481, "user": "p1_java", "data_directory": "explicit-pg-dir", "version": "17.10", "vector_version": "0.8.4", "max_connections": "100", "shared_buffers": "128MB", "work_mem": "4MB"}}}


class IndependentTests(unittest.TestCase):
    def setUp(self):
        self.threshold = json.loads((ROOT/"migration/contracts/thresholds.json").read_text())

    def test_complete_python_is_valid_baseline_and_never_candidate_pass(self):
        status = collection_status(formal_report("python"))
        self.assertTrue(status["baseline_complete"])
        self.assertTrue(status["baseline_valid"])
        self.assertIsNone(status["candidate_capacity_pass"])
        self.assertFalse(compare_pair(formal_report("python"), None, self.threshold)["candidate_capacity_pass"])

    def test_invalid_wall_or_truncated_window_is_not_valid_baseline(self):
        for mutate in (lambda r: r["rounds"][0].update(elapsed_including_drain_seconds=599),
                       lambda r: r["warmups"][1]["resource_samples"][-1].update(wall_seconds=999999),
                       lambda r: r["rounds"][2].update(resource_samples=[])):
            report = formal_report("python")
            mutate(report)
            self.assertFalse(collection_status(report)["baseline_valid"])

    def test_complete_collection_keeps_real_frozen_threshold_failure(self):
        report = formal_report("python")
        report["rounds"][0]["groups"]["chat"]["p95_ms"] = 2700
        self.assertTrue(collection_status(report)["baseline_valid"])
        self.assertFalse(evaluate(report, self.threshold, require_layout=False)["pass"])
        self.assertIsNone(collection_status(report)["candidate_capacity_pass"])

    def test_pair_requires_both_real_evidence_and_keeps_frozen_limits(self):
        a, b = formal_report("python"), formal_report("java")
        self.assertTrue(compare_pair(a, b, self.threshold)["candidate_capacity_pass"])
        mutations = (lambda x: x["environment"].pop("physical"),
                     lambda x: x["environment"]["postgres"].update(port=55555),
                     lambda x: x["source_evidence"].update(protocol_sha256="different"),
                     lambda x: x["source_evidence"].update(python_backend_tree="changed"),
                     lambda x: x["rounds"].pop(),
                     lambda x: x["rounds"][0].update(elapsed_including_drain_seconds=599),
                     lambda x: x["rounds"][0].update(resource_samples=[]),
                     lambda x: x["rounds"][0]["groups"]["chat"].update(p95_ms=2700),
                     lambda x: x["rounds"][0]["groups"]["chat"]["statuses"].update(http_200=999))
        for mutate in mutations:
            invalid = copy.deepcopy(b)
            mutate(invalid)
            self.assertFalse(compare_pair(a, invalid, self.threshold)["candidate_capacity_pass"])

    def test_python_main_saves_standalone_baseline_without_java_classpath_or_pair_green(self):
        artifacts = ROOT/".migration-artifacts"
        artifacts.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="pure-driver-", dir=artifacts) as directory:
            output = Path(directory)/"new-run"
            args = run.parser().parse_args(["--runtime", "python", "--output", str(output)])
            fake = formal_report("python")
            async def collect(runtime, *unused):
                self.assertEqual(runtime, "python")
                return copy.deepcopy(fake)
            with patch.object(run, "run_runtime", collect), patch.object(run, "PgIsolation") as guard, \
                    patch.object(run, "source_evidence", return_value=fake["source_evidence"]), \
                    patch.object(run, "physical_environment", return_value=fake["environment"]["physical"]), \
                    patch.object(run, "sleep_prevention", return_value={"tool": "pure-test"}):
                self.assertEqual(asyncio.run(run.main_async(args)), 0)
                guard.from_env.return_value.verify_instance.assert_called_once()
            report = json.loads((output/"python/summary.json").read_text())
            self.assertTrue(report["baseline_complete"] and report["baseline_valid"])
            self.assertIsNone(report["candidate_capacity_pass"])
            self.assertIsNone(report["evaluation"]["pass"])
            self.assertFalse(report["layout_gate"])
            self.assertFalse((output/"java").exists())
            self.assertTrue((output/"baseline-summary.json").exists())
            self.assertTrue(report["frozen_threshold_observation"]["pass"])
            with self.assertRaises(RuntimeError):
                with patch.object(run, "source_evidence", return_value=fake["source_evidence"]), \
                        patch.object(run, "physical_environment", return_value=fake["environment"]["physical"]), \
                        patch.object(run, "sleep_prevention", return_value={"tool": "pure-test"}):
                    asyncio.run(run.main_async(args))


if __name__ == "__main__":
    unittest.main()
