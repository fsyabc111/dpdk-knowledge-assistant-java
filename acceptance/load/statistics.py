"""冻结容量统计。纯函数，不接触应用、PG或历史证据。"""
from __future__ import annotations

import math


GROUPS = ("login", "assignment_read", "chat", "formal_submit", "explain_admission", "explain_terminal")
CORRECTNESS_KEYS = ("no_unauthorized_access", "all_accepted_terminal", "one_chat_charge_per_task", "no_duplicate_grade", "terminal_inputs_deleted",
                    "formal_successes_equal_grades", "mcp_formal_count_matches", "no_grade_from_explanation", "no_network_denial")
FROZEN_PYTHON_COMMIT = "80b29135faddd16d5e39fdbf0633291ea6a20489"
FROZEN_BACKEND_TREE = "38efd2a4d4ad284726989974bbb521ec4d4801dd"


def clock_validity(samples):
    """采样大空洞/墙钟与单调时钟分离使轮次不可判定；保留样本，不改容量门槛。"""
    failures = []
    if len(samples) < 2:
        failures.append("insufficient_clock_samples")
    for before, after in zip(samples, samples[1:]):
        mono = after["monotonic_seconds"] - before["monotonic_seconds"]
        wall = after["wall_seconds"] - before["wall_seconds"]
        if mono <= 0:
            failures.append("monotonic_non_increasing")
        if mono > 5:
            failures.append("sampler_gap_over_5s")
        if abs(wall-mono) > 2:
            failures.append("wall_monotonic_leap_over_2s")
    return {"valid": not failures, "failures": sorted(set(failures)),
            "sampler_gap_limit_seconds": 5, "wall_monotonic_difference_limit_seconds": 2}


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


def evaluate(report, thresholds, *, require_layout=True):
    frozen = thresholds["synthetic_capacity"]
    failures = []
    if require_layout and report.get("layout_gate") is not True:
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
        if round_.get("validity", {}).get("valid") is not True:
            failures.append(label + "睡眠/时钟/采样完整性门禁未通过")
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
        if warmup.get("validity", {}).get("valid") is not True:
            failures.append("预热睡眠/时钟/采样完整性门禁未通过")
    correctness = report.get("correctness", {})
    for name in CORRECTNESS_KEYS:
        if correctness.get(name) is not True:
            failures.append("账本断言缺失/失败:" + name)
    return {"pass": not failures, "failures": failures, "thresholds_changed": False}


def collection_status(report):
    """完整/有效采集与候选通过分离；单端也能独立交付，不借缺失Java放宽门禁。"""
    rounds, warmups = report.get("rounds", []), report.get("warmups", [])
    complete = (len(rounds) == len(warmups) == 3 and
        {r.get("round") for r in rounds} == {1, 2, 3} and {r.get("round") for r in warmups} == {1, 2, 3} and
        all(r.get("target_seconds") == 600 and r.get("elapsed_including_drain_seconds", -1) >= 600 for r in rounds) and
        all(r.get("target_seconds") == 120 and r.get("elapsed_including_drain_seconds", -1) >= 120 for r in warmups))
    def phase_valid(phase):
        samples = phase.get("resource_samples", [])
        try:
            return (phase.get("validity", {}).get("valid") is True and not phase.get("aborted_error_type") and
                clock_validity(samples)["valid"] and
                samples[-1]["monotonic_seconds"]-samples[0]["monotonic_seconds"] >= phase["target_seconds"])
        except (KeyError, TypeError, IndexError):
            return False
    valid = complete and all(phase_valid(r) for r in rounds+warmups)
    valid = valid and all(report.get("correctness", {}).get(k) is True for k in CORRECTNESS_KEYS)
    return {"baseline_complete": complete, "baseline_valid": valid, "candidate_capacity_pass": None}


def compare_pair(python, java, thresholds):
    """仅完整双端、同源负载/布局/物理实例证据与冻结逐轮门禁齐备才给候选结论。"""
    failures = []
    reports = {"python": python, "java": java}
    for runtime, report in reports.items():
        if report is None:
            failures.append(runtime+"报告缺失")
            continue
        if report.get("runtime") != runtime or report.get("qualification_only") is not False:
            failures.append(runtime+"不是正式目标端报告")
        status = collection_status(report)
        if not status["baseline_complete"] or not status["baseline_valid"]:
            failures.append(runtime+"三轮采集不完整或无效")
        source = report.get("source_evidence", {})
        if (source.get("frozen_python_commit") != FROZEN_PYTHON_COMMIT or source.get("python_backend_tree") != FROZEN_BACKEND_TREE
                or not source.get("source_commit") or source.get("dirty") is not False or not source.get("protocol_sha256")):
            failures.append(runtime+"源码/冻结Python/负载指纹证据缺失")
        environment = report.get("environment", {})
        physical, postgres = environment.get("physical", {}), environment.get("postgres", {})
        if not all(physical.get(k) for k in ("host_id_sha256", "platform", "machine", "cpu_model", "memory_bytes", "cpu_count", "python")):
            failures.append(runtime+"物理环境证据缺失")
        if not all(postgres.get(k) for k in ("host", "port", "user", "data_directory", "version", "vector_version", "max_connections", "shared_buffers", "work_mem")):
            failures.append(runtime+"PG实例/配置证据缺失")
        for round_ in report.get("rounds", [])+report.get("warmups", []):
            for name in GROUPS:
                group = round_.get("groups", {}).get(name, {})
                statuses = group.get("statuses", {})
                if sum(value for key, value in statuses.items() if key.startswith("http_")) != group.get("requests"):
                    failures.append(runtime+"/"+name+"状态与错误分母证据不完整")
            for sample in round_.get("resource_samples", []):
                if len(sample.get("processes", {})) < 2 or "pg_active_by_database" not in sample:
                    failures.append(runtime+"同步资源/PG采样域证据缺失")
        try:
            observed = evaluate(report, thresholds, require_layout=False)
            failures.extend(runtime+":"+reason for reason in observed["failures"])
        except (ValueError, KeyError, TypeError):
            failures.append(runtime+"冻结门禁统计不完整")
    if python is not None and java is not None:
        for key in ("layout", "server_settings"):
            if not python.get(key) or python.get(key) != java.get(key):
                failures.append(key+"未对齐")
        if python.get("source_evidence", {}).get("protocol_sha256") != java.get("source_evidence", {}).get("protocol_sha256"):
            failures.append("负载/边界协议source未对齐")
        for key in ("physical", "postgres"):
            if python.get("environment", {}).get(key) != java.get("environment", {}).get(key):
                failures.append(key+"不是同物理环境/配置")
    return {"candidate_capacity_pass": not failures, "pass": not failures, "failures": sorted(set(failures)), "thresholds_changed": False}
