"""RAG 检索评估指标（纯函数，无网络/应用依赖，进默认测试集）。

题库里每个用例的 expected_sources 是「相关组」列表：一组内的片段互为替代
（任一命中即算该组召回），不同组都必须命中才算完全召回。指标定义：

- coverage@k：前 k 条结果中命中的相关组占比（单组用例退化为 0/1 命中）
- first_hit_rank：任一相关组的最高命中名次（1 起，未命中为 None）
- MRR：first_hit_rank 的倒数，未命中为 0
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional, Sequence


def normalize_groups(expected_sources: Sequence[Any]) -> List[List[str]]:
    """把 yaml 里的 expected_sources 归一成「组列表」：字符串项成单元素组。"""
    groups: List[List[str]] = []
    for item in expected_sources or []:
        if isinstance(item, str):
            groups.append([item])
        else:
            groups.append([str(fragment) for fragment in item])
    return groups


def _group_hit_rank(ranked_paths: Sequence[str], group: Sequence[str]) -> Optional[int]:
    """组内任一片段在结果中的最高名次（1 起）；未命中返回 None。"""
    best: Optional[int] = None
    for rank, path in enumerate(ranked_paths, start=1):
        lowered = path.lower()
        if any(fragment.lower() in lowered for fragment in group):
            if best is None or rank < best:
                best = rank
    return best


def group_hit_ranks(ranked_paths: Sequence[str], groups: Sequence[Sequence[str]]) -> List[Optional[int]]:
    return [_group_hit_rank(ranked_paths, group) for group in groups]


def coverage_at_k(hit_ranks: Sequence[Optional[int]], k: int) -> float:
    """前 k 条结果内命中的相关组占比；无相关组的用例返回 0（由调用方排除）。"""
    if not hit_ranks:
        return 0.0
    hits = sum(1 for rank in hit_ranks if rank is not None and rank <= k)
    return hits / len(hit_ranks)


def first_hit_rank(hit_ranks: Sequence[Optional[int]]) -> Optional[int]:
    ranks = [rank for rank in hit_ranks if rank is not None]
    return min(ranks) if ranks else None


def reciprocal_rank(hit_ranks: Sequence[Optional[int]]) -> float:
    rank = first_hit_rank(hit_ranks)
    return 1.0 / rank if rank else 0.0


def percentile(values: Sequence[float], ratio: float) -> float:
    """最近秩百分位；空序列返回 0。"""
    if not values:
        return 0.0
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int(len(ordered) * ratio + 0.999999) - 1))
    return ordered[index]


def summarize(records: Sequence[Dict[str, Any]], ks: Sequence[int] = (1, 5, 15)) -> Dict[str, Any]:
    """汇总单例评估记录（至少含 category / hit_ranks / must_top_ok / elapsed_sec）。

    无相关组的用例（负例/观察例，hit_ranks 为空）不计入 recall/MRR 分母，
    但计入用例总数与耗时统计。
    """
    def _bucket(rows: List[Dict[str, Any]]) -> Dict[str, Any]:
        judged = [row for row in rows if row["hit_ranks"]]
        total = len(rows)
        summary: Dict[str, Any] = {
            "cases": total,
            "judged_cases": len(judged),
            "mrr": round(sum(row["mrr"] for row in judged) / len(judged), 4) if judged else 0.0,
        }
        for k in ks:
            key = f"recall@{k}"
            summary[key] = (
                round(sum(row["coverage"][key] for row in judged) / len(judged), 4) if judged else 0.0
            )
        must_top_rows = [row for row in rows if row.get("must_top")]
        if must_top_rows:
            hits = sum(1 for row in must_top_rows if row.get("must_top_ok"))
            summary["must_top_hit_rate"] = round(hits / len(must_top_rows), 4)
            summary["must_top_cases"] = len(must_top_rows)
        latencies = [row["elapsed_sec"] for row in rows if row.get("elapsed_sec")]
        if latencies:
            summary["avg_latency_sec"] = round(sum(latencies) / len(latencies), 3)
            summary["p95_latency_sec"] = round(percentile(latencies, 0.95), 3)
        return summary

    by_category: Dict[str, List[Dict[str, Any]]] = {}
    for record in records:
        by_category.setdefault(str(record.get("category") or "未分类"), []).append(record)
    return {
        "overall": _bucket(list(records)),
        "by_category": {name: _bucket(rows) for name, rows in sorted(by_category.items())},
    }
