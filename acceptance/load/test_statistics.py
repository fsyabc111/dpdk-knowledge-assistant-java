"""比较器必须拒绝布局/尾部/单轮错误，不能只验证输出形状。"""
import copy
import json
from pathlib import Path
import unittest

from acceptance.load.statistics import GROUPS, clock_validity, combine_resources, evaluate, percentiles


class StatisticsTests(unittest.TestCase):
    def setUp(self):
        self.thresholds = json.loads((Path(__file__).resolve().parents[2] / "migration/contracts/thresholds.json").read_text())
        resource = [{"processes": {"app": {"rss_kib": 102400, "cpu_percent_one_core": 20},
                                    "driver": {"rss_kib": 10240, "cpu_percent_one_core": 5}}}]
        groups = {name: {"requests": 1000, "n": 1000, "p95_ms": 1, "unexpected_errors": 0} for name in GROUPS}
        self.report = {"layout_gate": True, "rounds": [{"round": i, "target_seconds": 600, "validity": {"valid": True}, "groups": copy.deepcopy(groups), "resource_samples": resource} for i in range(1, 4)],
                       "warmups": [{"target_seconds": 120, "validity": {"valid": True}} for _ in range(3)], "correctness": {name: True for name in (
                           "no_unauthorized_access", "all_accepted_terminal", "one_chat_charge_per_task", "no_duplicate_grade", "terminal_inputs_deleted", "formal_successes_equal_grades", "mcp_formal_count_matches", "no_grade_from_explanation", "no_network_denial")}}

    def test_complete_report_accepts(self):
        self.assertTrue(evaluate(self.report, self.thresholds)["pass"])

    def test_single_round_tail_layout_and_denominator_rejected(self):
        for mutation in (lambda r: r.update(layout_gate=False),
                         lambda r: r["rounds"].pop(),
                         lambda r: r["rounds"][1]["groups"]["chat"].update(p95_ms=2700),
                         lambda r: r["rounds"][0]["groups"]["chat"].update(n=999),
                         lambda r: r["rounds"][2]["groups"]["login"].update(unexpected_errors=2),
                         lambda r: r["correctness"].pop("no_duplicate_grade")):
            report = copy.deepcopy(self.report)
            mutation(report)
            self.assertFalse(evaluate(report, self.thresholds)["pass"])

    def test_synchronous_sum_is_not_sum_of_peaks(self):
        samples = [{"processes": {"app": {"rss_kib": a*1024, "cpu_percent_one_core": a},
                                  "driver": {"rss_kib": b*1024, "cpu_percent_one_core": b}}} for a, b in ((100, 1), (1, 100))]
        self.assertEqual(combine_resources(samples)["combined_rss_peak_mib"], 101)
        self.assertEqual(combine_resources(samples)["combined_cpu_p95_one_core_percent"], 101)

    def test_p99_requires_1000_including_failures(self):
        self.assertIsNone(percentiles(range(999))["p99_ms"])
        self.assertEqual(percentiles(range(1000))["p99_ms"], 989)

    def test_sleep_clock_leap_and_sampling_gap_invalidate_without_dropping_samples(self):
        normal = [{"monotonic_seconds": 1, "wall_seconds": 100}, {"monotonic_seconds": 2, "wall_seconds": 101}]
        self.assertTrue(clock_validity(normal)["valid"])
        for corrupted in ([normal[0], {"monotonic_seconds": 2, "wall_seconds": 121}],
                          [normal[0], {"monotonic_seconds": 21, "wall_seconds": 120}],
                          [normal[0], {"monotonic_seconds": 0, "wall_seconds": 101}]):
            self.assertFalse(clock_validity(corrupted)["valid"])
            self.assertEqual(len(corrupted), 2)
        report = copy.deepcopy(self.report)
        report["rounds"][1]["validity"] = {"valid": False}
        self.assertFalse(evaluate(report, self.thresholds)["pass"])


if __name__ == "__main__":
    unittest.main()
