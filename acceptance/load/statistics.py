"""冻结容量统计。纯函数，不接触应用、PG或历史证据。"""
from __future__ import annotations

import math


GROUPS = ("login", "assignment_read", "chat", "formal_submit", "explain_admission", "explain_terminal")


def percentiles(values):
    ordered = sorted(values)
    if not ordered:
        return {"n": 0, "p50_ms": None, "p95_ms": None, "p99_ms": None, "max_ms": None}
    def rank(p):
        return ordered[max(0, math.ceil(len(ordered) * p) - 1)]
    return {"n": len(ordered), "p50_ms": rank(.5), "p95_ms": rank(.95),
            "p99_ms": rank(.99) if len(ordered) >= 1000 else None, "max_ms": ordered[-1]}


def combine_resources(samples):
    """同一采样点先求和。CPU输入是同一Δt内每PID累计CPU差/真实Δt。"""
    if not samples:
        raise ValueError("没有资源样本")
    rss, cpu = [], []
    for sample in samples:
        if not sample["processes"]:
            raise ValueError("空比较域")
        rss.append(sum(p["rss_kib"] for p in sample["processes"].values()) / 1024)
        values = [p["cpu_percent_one_core"] for p in sample["processes"].values()]
        if all(v is not None for v in values):
            cpu.append(sum(values))
    return {"combined_cpu_p95_one_core_percent": percentiles(cpu)["p95_ms"],
            "combined_rss_peak_mib": max(rss), "samples": len(samples)}


def evaluate(report, thresholds):
    frozen = thresholds["synthetic_capacity"]
    failures = []
    if report.get("layout_gate") is not True:
        failures.append("layout_gate未通过")
    rounds = report.get("rounds", [])
    if len(rounds) != 3:
        failures.append("必须完整保留三轮测量")
    if len(report.get("warmups", [])) != 3:
        failures.append("必须完整保留三轮预热")
    for round_ in rounds:
        label = f"round-{round_.get('round')}"
        if round_.get("target_seconds") != 600:
            failures.append(label + "测量窗口不是600s")
        for name in GROUPS:
            group = round_.get("groups", {}).get(name)
            if group is None or group.get("requests", 0) <= 0:
                failures.append(label + "/" + name + "缺少请求")
                continue
            limit = frozen["groups"][name]
            value = group.get("p95_ms")
            if value is None or value > limit["absolute_p95_upper_ms"]:
                failures.append(label + "/" + name + "绝对p95超限")
            if value is None or value > limit["reference_python_worst_round_p95_ms"] + limit["allowed_p95_increase_ms"]:
                failures.append(label + "/" + name + "冻结相对p95超限")
            if group.get("n") != group["requests"]:
                failures.append(label + "/" + name + "耗时分母不完整")
            if group.get("unexpected_errors", group["requests"]) / group["requests"] > frozen["unexpected_error_rate_upper"]:
                failures.append(label + "/" + name + "异常率超限")
        resource = combine_resources(round_.get("resource_samples", []))
        if resource["combined_cpu_p95_one_core_percent"] is None or resource["combined_cpu_p95_one_core_percent"] > frozen["combined_cpu_sustained_p95_one_core_percent_upper"]:
            failures.append(label + "组合CPU超限或缺失")
        if resource["combined_rss_peak_mib"] > frozen["combined_rss_mib_upper"]:
            failures.append(label + "组合RSS超限")
    for warmup in report.get("warmups", []):
        if warmup.get("target_seconds") != 120:
            failures.append("预热窗口不是120s")
    correctness = report.get("correctness", {})
    required = ("no_unauthorized_access", "all_accepted_terminal", "one_chat_charge_per_task", "no_duplicate_grade", "terminal_inputs_deleted",
                "formal_successes_equal_grades", "mcp_formal_count_matches", "no_grade_from_explanation", "no_network_denial")
    for name in required:
        if correctness.get(name) is not True:
            failures.append("账本断言缺失/失败:" + name)
    return {"pass": not failures, "failures": failures, "thresholds_changed": False}
