"""metrics.py 的纯函数单测（无网络，进默认测试集）。"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from metrics import (  # noqa: E402
    coverage_at_k,
    first_hit_rank,
    group_hit_ranks,
    normalize_groups,
    percentile,
    reciprocal_rank,
    summarize,
)


class TestNormalizeGroups:
    def test_string_becomes_singleton_group(self):
        assert normalize_groups(["engine_dns.cpp", "session.h"]) == [["engine_dns.cpp"], ["session.h"]]

    def test_list_items_stay_groups(self):
        assert normalize_groups([["engine_smtp.c", "engine_openvpn.c"]]) == [["engine_smtp.c", "engine_openvpn.c"]]

    def test_mixed_and_empty(self):
        assert normalize_groups(["a", ["b", "c"]]) == [["a"], ["b", "c"]]
        assert normalize_groups([]) == []
        assert normalize_groups(None) == []


class TestGroupHitRanks:
    def test_case_insensitive_substring(self):
        ranks = group_hit_ranks(["Plugin/Engine_DNS.cpp", "session/session.h"], [["engine_dns.cpp"]])
        assert ranks == [1]

    def test_group_alternatives_take_best_rank(self):
        ranks = group_hit_ranks(["a/engine_openvpn.c", "b/engine_smtp.c"], [["engine_smtp.c", "engine_openvpn.c"]])
        assert ranks == [1]

    def test_miss_is_none(self):
        assert group_hit_ranks(["x.c"], [["engine_dns.cpp"]]) == [None]
        assert first_hit_rank([None, None]) is None
        assert reciprocal_rank([None]) == 0.0


class TestCoverageAndMrr:
    def setup_method(self):
        self.hit_ranks = group_hit_ranks(
            ["engine_pop3.c", "engine_ssh.c", "unrelated.c"],
            [["engine_pop3.c"], ["engine_ssh.c"], ["engine_smtp.c", "engine_openvpn.c"]],
        )

    def test_coverage_partial(self):
        # 3 组命中 2 组
        assert coverage_at_k(self.hit_ranks, 15) == 2 / 3
        assert coverage_at_k(self.hit_ranks, 1) == 1 / 3

    def test_coverage_empty_groups(self):
        assert coverage_at_k([], 15) == 0.0

    def test_reciprocal_rank(self):
        assert reciprocal_rank(self.hit_ranks) == 1.0
        assert reciprocal_rank(group_hit_ranks(["a.c", "b.c", "engine_ssh.c"], [["engine_ssh.c"]])) == 1 / 3


class TestPercentile:
    def test_empty(self):
        assert percentile([], 0.95) == 0.0

    def test_p95(self):
        values = [float(i) for i in range(1, 21)]
        assert percentile(values, 0.95) == 19.0
        assert percentile(values, 0.5) == 10.0


class TestSummarize:
    def _record(self, category, hit_ranks, elapsed=1.0, must_top=None, must_top_ok=None):
        coverage = {f"recall@{k}": coverage_at_k(hit_ranks, k) for k in (1, 5, 15)}
        return {
            "category": category,
            "hit_ranks": hit_ranks,
            "coverage": coverage,
            "mrr": reciprocal_rank(hit_ranks),
            "must_top": must_top,
            "must_top_ok": must_top_ok,
            "elapsed_sec": elapsed,
        }

    def test_overall_and_category_buckets(self):
        records = [
            self._record("协议", [1], must_top="engine_dns", must_top_ok=True),
            self._record("协议", [None, 3]),
            self._record("负例", []),  # 观察例：不进 recall 分母
        ]
        summary = summarize(records)
        overall = summary["overall"]
        assert overall["cases"] == 3
        assert overall["judged_cases"] == 2
        assert overall["recall@15"] == 0.75  # (1.0 + 0.5) / 2
        assert overall["recall@1"] == 0.5
        assert overall["mrr"] == round((1.0 + 1 / 3) / 2, 4)
        assert overall["must_top_hit_rate"] == 1.0
        assert summary["by_category"]["协议"]["cases"] == 2
        assert summary["by_category"]["负例"]["judged_cases"] == 0

    def test_empty(self):
        summary = summarize([])
        assert summary["overall"]["cases"] == 0
        assert summary["overall"]["recall@15"] == 0.0
