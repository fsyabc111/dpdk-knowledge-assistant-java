"""把完整三轮合成证据汇总为候选门限；不修改原始测量与冻结契约。"""
from __future__ import annotations

from collections import defaultdict
import json
import math
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
from migration.oracle.performance.run import ARTIFACTS, EVIDENCE, quantiles, write_json


def ceiling(value, unit):
    return math.ceil(value / unit) * unit


def main():
    summary = json.loads((EVIDENCE / "baseline-summary.json").read_text())
    environment = json.loads((EVIDENCE / "baseline-environment.json").read_text())
    measured = [r for r in summary["rounds"] if r["phase"] == "measurement"]
    if len(measured) != 3 or any(r["target_seconds"] != 600 for r in measured):
        raise RuntimeError("完整三轮各600秒尚未采齐，不生成容量候选")
    warmups = [r for r in summary["rounds"] if r["phase"] == "warmup"]
    if len(warmups) != 3 or any(r["target_seconds"] != 120 for r in warmups):
        raise RuntimeError("完整三轮各120秒预热尚未采齐")
    all_values, counts = defaultdict(list), defaultdict(lambda: defaultdict(int))
    resource_samples, sampler_intervals_ms = [], []
    for result in measured:
        number = result["round"]
        raw = json.loads((ARTIFACTS / f"baseline-round-{number}-measurement-latencies.json").read_text())
        for group, values in raw.items():
            if len(values) != result["groups"][group]["n"]:
                raise RuntimeError("原始统计样本与报告分母不一致")
            all_values[group].extend(values)
            for key, value in result["groups"][group].items():
                if key in {"requests", "unexpected_errors", "expected_backpressure"} or key.startswith("http_"):
                    counts[group][key] += value
        phase_resources = json.loads((EVIDENCE / f"baseline-round-{number}-measurement-resources.json").read_text())
        resource_samples.extend(phase_resources)
        sampler_intervals_ms.extend((after["monotonic_seconds"] - before["monotonic_seconds"]) * 1000
                                    for before, after in zip(phase_resources, phase_resources[1:]))
    groups = {name: {**quantiles(values), **dict(counts[name]),
                     "unexpected_errors": counts[name]["unexpected_errors"],
                     "expected_backpressure": counts[name]["expected_backpressure"],
                     "unexpected_error_rate": counts[name]["unexpected_errors"] / len(values)}
              for name, values in all_values.items()}
    app_cpu = [r["app_cpu_pct_one_core"] for r in resource_samples if r["app_cpu_pct_one_core"] is not None]
    cpu_p95 = sorted(app_cpu)[math.ceil(len(app_cpu) * .95) - 1]
    app_rss_peak = max(s["app_rss_kib"] / 1024 for s in resource_samples)
    other_load = [s for s in resource_samples if any(n for db, n in s["pg_active_sessions_by_database_excluding_sampler"].items() if db != "p0_benchmark_oracle")]
    floors = {"login": 500, "assignment_read": 100, "chat": 3000, "formal_submit": 500, "explain_admission": 250, "explain_terminal": 3000}
    candidates = {}
    for group, floor in floors.items():
        reference = max(r["groups"][group]["p95_ms"] for r in measured)
        absolute = ceiling(max(floor, reference * 1.25), 50 if group not in {"chat", "explain_terminal"} else 100)
        candidates[group] = {"reference_python_worst_round_p95_ms": reference, "absolute_p95_upper_ms": absolute,
                             "allowed_p95_increase_ms": round(max(reference * .20, 50), 3),
                             "policy": "同硬件同PG/数据/替身/配置和相同窗口；绝对上限与相对差值两项同时满足；仅候选待用户确认"}
    recovery = json.loads((EVIDENCE / "recovery.json").read_text())
    execution = [r["recovery_execution_seconds"] for r in recovery["results"] if r["case_id"] in {"kill-restart-accepted", "kill-restart-before-terminal"}]
    proposal = {"status": "proposed_pending_user_confirmation", "capacity_pass": False,
                "groups": candidates, "unexpected_error_rate_upper": .001,
                "correctness_hard_limits": {"unauthorized_access": 0, "duplicate_business_charge": 0, "duplicate_chat_grade": 0, "never_terminal_task": 0},
                "combined_cpu_sustained_p95_one_core_percent_upper": ceiling(max(100, cpu_p95 * 1.5), 25),
                "combined_rss_mib_upper": ceiling(max(512, app_rss_peak * 1.5), 64),
                "resource_comparison_domain": {"python": "应用+负载器+统计/采样器合计，当前处于同一Python进程",
                                               "java": "Java应用进程+等价Python负载/统计/采样进程之和；按时间对齐每个样本后求和再计算p95/峰值，不能相加不同时刻的峰值或百分位",
                                               "layout_gate": "P1交付等价候选adapter或另测独立server基线；布局/采样域未对齐时仅观察，不判通过",
                                               "network": "当前Python同进程ASGI不含TCP/TLS/nginx；未来adapter须明确网络开销差异",
                                               "status": "P0可执行synthetic门禁候选，非应用独占或P5生产容量"},
                "pg_resource_gate": "本实例共享多个oracle库，当前只记录进程资源；尚未提出可归属本应用的PG独占硬上限",
                "recovery_execution_rto_seconds_upper": 1,
                "recovery_rto_scope": "仅10ms/10ms/10ms恢复脚本，进程已启动、槽位可用、PG健康，开始ensure_managed到终态；不含JVM/进程启动、grace、调度等待和真实上游",
                "default_scheduler_wait_seconds_upper_derived": 330,
                "derived_user_visible_rto": "30s grace + 300s扫描间隔保守上界 + 执行；仅推导，尚无真实自动循环最坏值实测，不作为生产恢复承诺",
                "rpo_candidate": "应用进程SIGKILL且PG继续健康时，已提交受理身份/配额/成绩/作业账本不丢失，局部测试RPO=0；未测PG/主机故障或备份恢复；未保存模型中间输出不保证，临时执行输入遵循24h TTL；外部MCP执行未知不等于业务无损",
                "unknown_submission_blocker": "当前Python unknown恢复跨进程grade_plugin调用2次；COMP003安全差异由主Agent/用户决定，不改Python、不视为恢复全通过"}
    write_json(EVIDENCE / "threshold-candidates.json", proposal)
    aggregate = {"baseline": environment["baseline"], "groups": groups, "rounds": measured,
                 "correctness_assertions": summary["correctness_assertions"], "final": summary["final"],
                 "resources": {"app_cpu_p95_one_core_percent": cpu_p95, "app_cpu_peak_one_core_percent": max(app_cpu),
                               "app_rss_peak_mib": app_rss_peak, "other_database_active_samples": len(other_load),
                               "actual_sampler_interval_ms": quantiles(sampler_intervals_ms),
                               "measurement_samples": len(resource_samples), "other_load_first_utc": other_load[0]["wall_utc"] if other_load else None,
                               "other_load_last_utc": other_load[-1]["wall_utc"] if other_load else None},
                 "known_good_recovery_execution_seconds_max": max(execution), "capacity_pass": False,
                 "status": "测量完成；阈值候选待用户确认；unknown恢复存在冻结缺陷；真实质量未验证"}
    write_json(EVIDENCE / "aggregate.json", aggregate)
    lines = ["# J0-02 Python 合成性能与恢复实测", "", aggregate["status"] + "。", "",
             f"基线 `{environment['baseline']}`；Python `{environment['python'].split()[0]}`；{environment['cpu_model']}，逻辑 CPU {environment['logical_cpu_count']}；PG {environment['postgres_settings']['server_version']} / pgvector {environment['vector_version']}，schema `{environment['schema']}`。", "",
             "正常负载完整执行 3 轮，每轮预热 120s、测量 600s。60 合成学生，最多 10 个问答/讲解槽位；模型每调用 1s、检索 50ms、MCP 200ms。同进程真实 FastAPI/认证/产品服务/TaskService/LangGraph/PG；外部 I/O 为替身。测量不含 TCP/TLS/nginx/真实上游。", "",
             "| 分组 | 测量 n | p50 ms | p95 ms | p99 ms | 异常/分母 |", "|---|---:|---:|---:|---:|---:|"]
    for name, group in groups.items():
        lines.append(f"| {name} | {group['n']} | {group['p50_ms']} | {group['p95_ms']} | {group['p99_ms'] if group['p99_ms'] is not None else '样本不足，不报'} | {group.get('unexpected_errors', 0)}/{group['n']} |")
    lines.extend(["", "| 轮次 | 含排空时长 s | chat p95 ms | 活动/等待峰值 | app RSS峰 MiB | app CPU峰 %一核 |", "|---|---:|---:|---:|---:|---:|"])
    for result in measured:
        r = result["resources"]
        lines.append(f"| {result['round']} | {result['elapsed_seconds_including_drain']:.3f} | {result['groups']['chat']['p95_ms']} | {r['active_peak']}/{r['waiting_peak']} | {r['app_rss_mib_peak']:.2f} | {r['app_cpu_pct_peak']:.2f} |")
    lines.extend(["", f"测量窗口组合 CPU p95={cpu_p95:.2f}%（100%=一核），组合 RSS峰={app_rss_peak:.2f}MiB。比较域为应用+负载生成器/统计/采样器合计；当前都在同一Python进程，采样器同步读取ps/PG的开销计入延迟，不是应用独占资源。未来Java若独立进程，须把Java应用与等价Python负载/采样进程按时间对齐的CPU/RSS求和后比较；布局未对齐时仅观察不判通过。P1需交付等价adapter或另采独立server基线，且当前ASGI不含TCP。其他数据库活动会话的资源样本为 {len(other_load)}/{len(resource_samples)}；PG资源是同一实例所有子进程的总值，RSS含共享页重复，不能作为本库独占内存。保留全部预热/测量轮次，没有剔除错误或异常尾部。", "",
                  "业务断言：" + "；".join(f"{k}={v}" for k, v in summary["correctness_assertions"].items()) + "。", "",
                  "队列边界：41 请求出现 20 活动/20 等待，20 成功、1 立即队满 429、20 等待 10s 后 429；这是预期背压，分母 41，不计作正常 mix 的异常率。终态临时输入为零，配额为 20，槽位归零。", "",
                  "恢复使用加速 10ms 外部脚本，SIGKILL 受理后和成绩提交后、答案终态前均能恢复，重放不重扣、不再调用 MCP，已完成成绩不重复。重驱动执行最大 " + f"{max(execution):.6f}s" + "；该值排除了 30s grace、300s扫描间隔及进程启动。默认调度保守等待上界 330s 属源码推导，未实测实际最坏自动恢复。", "",
                  "MCP已进入而响应尚未返回时 SIGKILL，恢复先查 unknown 再以相同 job 再提交：跨进程实际 grade_plugin 调用 2 次，本地配额/成绩各 1。外部执行次数与本地幂等不能混为一谈。这一安全边界为失败，未改冻结源码。running 同 job 再调用仅记录现状，不能证明真实 harness 会加入原执行。finished 能复用结果，不再提交。", "",
                  "600s TaskService 外墙通过虚拟 loop.time 的 599/600.5 边界验证；真实 PG 落 timeout/system_error、清临时输入和释放槽，迟到 finish 被已有终态守卫拒绝。该用例不等同于等候真实 600s 墙钟。断订阅验证服务生成器关闭后后台继续、终态可重放；TCP/浏览器断线未验证。24h TTL 由功能 oracle 承担。", "",
                  "候选阈值见 `threshold-candidates.json`，绝对 p95 与允许差值需同时满足。异常率候选 ≤0.1%，正确性硬限制为零越权/重复扣次/重复聊天成绩/永不终态。CPU/RSS/恢复候选均待用户确认，未冻结、未宣称容量通过。真实模型、embedding与harness质量均未验证。", "",
                  "精确重跑命令、替身设置、统计方法、资源归属与限制见 `migration/oracle/performance/README.md`。原始耗时数组和日志仅留 `.migration-artifacts/p0/performance/`；可提交证据只含合成统计/断言。"])
    (EVIDENCE / "REPORT.md").write_text("\n".join(lines) + "\n")
    print(json.dumps({"status": aggregate["status"], "groups": groups, "resources": aggregate["resources"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
