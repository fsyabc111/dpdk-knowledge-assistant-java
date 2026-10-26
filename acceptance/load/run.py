"""J5独立TCP容量入口；调用前须由主Agent分配PG/CPU独占窗口。"""
from __future__ import annotations

import argparse
import asyncio
from collections import Counter, defaultdict
from dataclasses import asdict
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import secrets
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from acceptance.contract.isolation import PgIsolation
from acceptance.load.statistics import GROUPS, clock_validity, combine_resources, evaluate, percentiles


def write_json(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w") as handle:
        json.dump(data, handle, ensure_ascii=False, indent=2)
    os.chmod(temporary, 0o600)
    temporary.replace(path)


def cpu_seconds(text):
    days, _, rest = text.rpartition("-") if "-" in text else ("0", "", text)
    return int(days) * 86400 + sum(float(v) * 60**i for i, v in enumerate(reversed(rest.split(":"))))


class Resources:
    def __init__(self, app_pid, isolation, db, control_pids=()):
        self.pids = (app_pid, os.getpid(), *control_pids)
        if app_pid == os.getpid() or len(set(self.pids)) != len(self.pids):
            raise ValueError("必须为独立应用与负载进程")
        self.isolation, self.db = isolation, db
        self.previous = None
        self.samples = []

    def sample(self):
        now = time.monotonic()
        wall_now = time.time()
        raw = subprocess.run(["ps", "-o", "pid=,rss=,time=", "-p", ",".join(map(str, self.pids))],
                             check=True, capture_output=True, text=True).stdout
        rows = {int(pid): (int(rss), cpu_seconds(cpu)) for pid, rss, cpu in (line.split() for line in raw.splitlines())}
        if set(rows) != set(self.pids):
            raise RuntimeError("测量进程消失，不能忽略资源样本")
        processes = {}
        for pid in self.pids:
            rss, total = rows[pid]
            percent = None if self.previous is None else max(0, total - self.previous[1][pid][1]) / (now-self.previous[0]) * 100
            processes[str(pid)] = {"rss_kib": rss, "cpu_seconds": total, "cpu_percent_one_core": percent}
        # PG独立域：记录实例活动库，禁止将共享页RSS声称应用独占。
        with self.isolation._connect(self.db.name) as conn:
            active = dict(conn.execute("SELECT datname,count(*) FROM pg_stat_activity WHERE backend_type='client backend' AND state='active' AND pid<>pg_backend_pid() GROUP BY datname").fetchall())
        sample = {"utc": datetime.fromtimestamp(wall_now, timezone.utc).isoformat(), "wall_seconds": wall_now, "monotonic_seconds": now,
                  "processes": processes, "pg_active_by_database": active}
        pg_pidfile = self.isolation.data_dir / "postmaster.pid"
        if pg_pidfile.exists():
            pg_pid = int(pg_pidfile.read_text().splitlines()[0])
            children = subprocess.run(["pgrep", "-P", str(pg_pid)], capture_output=True, text=True).stdout.split()
            pg_rows = subprocess.run(["ps", "-o", "pid=,rss=,time=", "-p", ",".join([str(pg_pid), *children])],
                                     check=True, capture_output=True, text=True).stdout
            sample["pg_processes"] = {pid: {"rss_kib": int(rss), "cpu_seconds": cpu_seconds(cpu)}
                                      for pid, rss, cpu in (line.split() for line in pg_rows.splitlines())}
        self.samples.append(sample)
        self.previous = now, rows


def snapshot(isolation, db):
    isolation.verify_owned(db)
    with isolation._connect(db.name) as conn:
        scalar = lambda sql: conn.execute(sql).fetchone()[0]
        return {"tasks": scalar("SELECT count(*) FROM rag_requests"),
                "pending": scalar("SELECT count(*) FROM rag_requests WHERE status='pending'"),
                "answered": scalar("SELECT count(*) FROM rag_requests WHERE status='answered'"),
                "inputs": scalar("SELECT count(*) FROM rag_task_inputs"),
                "chat_charges": scalar("SELECT coalesce(sum(chat_count),0) FROM usage_counters WHERE kind='chat'"),
                "grading_charges": scalar("SELECT coalesce(sum(chat_count),0) FROM usage_counters WHERE kind='grading'"),
                "grades": scalar("SELECT count(*) FROM grading_submissions"),
                "explain_tasks": scalar("SELECT count(*) FROM rag_requests WHERE purpose='explain'"),
                "duplicate_grades": scalar("SELECT count(*) FROM (SELECT task_job_id FROM grading_submissions WHERE task_job_id IS NOT NULL GROUP BY task_job_id HAVING count(*)>1) t"),
                "usage_unknown_cost": scalar("SELECT count(*) FROM upstream_usage_records WHERE cost_micros IS NULL")}


async def phase(manifest, isolation, db, seconds, number, label, output, source):
    import httpx
    clients = [httpx.AsyncClient(base_url=manifest["base_url"], trust_env=False, timeout=60,
                headers={"Authorization": "Bearer " + token, "X-Forwarded-For": f"198.18.0.{i+1}"})
               for i, token in enumerate(manifest["tokens"])]
    admin = httpx.AsyncClient(base_url=manifest["base_url"], trust_env=False, timeout=30,
                             headers={"X-J5-Control": manifest["control_key"]})
    deadline = time.monotonic() + seconds
    latency, errors = defaultdict(list), defaultdict(Counter)
    resource = Resources(manifest["pid"], isolation, db, manifest.get("measurement_control_pids", ()))
    slots = asyncio.Semaphore(10)
    next_account = 0
    stop = asyncio.Event()
    control = []
    async def request(group, client, method, url, valid=None, **kwargs):
        start = time.monotonic()
        try:
            response = await client.request(method, url, **kwargs)
            okay = response.status_code == 200 and (valid(response) if valid else True)
            status = str(response.status_code)
        except Exception as exc:
            response, okay, status = None, False, type(exc).__name__
        latency[group].append((time.monotonic()-start)*1000)
        errors[group]["requests"] += 1
        errors[group]["http_"+status] += 1
        if not okay:
            errors[group]["unexpected_errors"] += 1
        return response
    async def reader(index):
        await asyncio.sleep(index*.025)
        client = clients[index]
        user = manifest["students"][index]
        await request("login", client, "POST", "/api/auth/login", json={"username": user, "password": user},
                      valid=lambda r: r.json().get("subject") == user)
        while time.monotonic() < deadline:
            await request("assignment_read", client, "GET", "/api/assignments",
                          valid=lambda r: any(a["id"] == manifest["assignment_id"] for a in r.json().get("assignments", [])))
            await asyncio.sleep(min(5, max(0, deadline-time.monotonic())))
    async def chat():
        nonlocal next_account
        while time.monotonic() < deadline:
            index = next_account % 60
            next_account += 1
            async with slots:
                await request("chat", clients[index], "POST", "/api/chat", json={"message": "__kb__ 合成DNS标签如何编码？", "request_id": str(uuid.uuid4())},
                              valid=lambda r: r.json().get("answer_status") == "answered")
    async def formal():
        index = 0
        while time.monotonic() < deadline:
            client = clients[index % 60]
            index += 1
            result = await request("formal_submit", client, "POST", f"/api/assignments/{manifest['assignment_id']}/submit",
                                   json={"protocol": "dns", "code": source}, valid=lambda r: bool(r.json().get("submission_id")))
            if result is not None and result.status_code == 200:
                submission = result.json().get("submission_id")
                async with slots:
                    started = time.monotonic()
                    admitted = await request("explain_admission", client, "POST", f"/api/assignments/{manifest['assignment_id']}/submissions/{submission}/explain")
                    if admitted is not None and admitted.status_code == 200:
                        task = admitted.json().get("task_id")
                        polls, status, okay = 0, "timeout", False
                        try:
                            until = time.monotonic()+30
                            while time.monotonic() < until:
                                final = await client.get(f"/api/tasks/{task}")
                                polls += 1
                                status = str(final.status_code)
                                if final.status_code != 200:
                                    break
                                state = final.json().get("status")
                                if state not in ("pending", "running"):
                                    okay = state == "answered"
                                    break
                                await asyncio.sleep(.01)
                        except Exception as exc:
                            status = type(exc).__name__
                        latency["explain_terminal"].append((time.monotonic()-started)*1000)
                        errors["explain_terminal"]["requests"] += 1
                        errors["explain_terminal"]["http_"+status] += 1
                        errors["explain_terminal"]["polls"] += polls
                        errors["explain_terminal"]["unexpected_errors"] += not okay
            await asyncio.sleep(min(10, max(0, deadline-time.monotonic())))
    async def monitor():
        while not stop.is_set():
            resource.sample()
            response = await admin.get("/__j5/state")
            response.raise_for_status()
            control.append({"monotonic_seconds": time.monotonic(), **response.json()})
            try:
                await asyncio.wait_for(stop.wait(), 1)
            except asyncio.TimeoutError:
                pass
    started = time.monotonic()
    monitor_task = asyncio.create_task(monitor())
    work = asyncio.gather(*(reader(i) for i in range(60)), *(chat() for _ in range(10)), formal())
    failure = None
    try:
        finished, _ = await asyncio.wait({work, monitor_task}, return_when=asyncio.FIRST_COMPLETED)
        if monitor_task in finished:
            await monitor_task
            raise RuntimeError("资源监控意外提前结束")
        await work
    except Exception as exc:
        failure = type(exc).__name__
        work.cancel()
        await asyncio.gather(work, return_exceptions=True)
    finally:
        stop.set()
        monitor_result = await asyncio.gather(monitor_task, return_exceptions=True)
        if isinstance(monitor_result[0], Exception):
            failure = failure or type(monitor_result[0]).__name__
        try:
            resource.sample()  # 末尾样本也覆盖排空/采样停止前的睡眠空洞。
        except Exception as exc:
            failure = failure or type(exc).__name__
        for client in clients:
            await client.aclose()
        await admin.aclose()
    try:
        after = snapshot(isolation, db)
    except Exception as exc:
        after = {"error_type": type(exc).__name__}
        failure = failure or type(exc).__name__
    report = {"round": number, "phase": label, "target_seconds": seconds, "elapsed_including_drain_seconds": time.monotonic()-started,
        "groups": {g: {**percentiles(latency[g]), "requests": errors[g]["requests"], "unexpected_errors": errors[g]["unexpected_errors"],
                       "statuses": dict(errors[g])} for g in GROUPS},
        "resource_samples": resource.samples, "resource_summary": combine_resources(resource.samples) if resource.samples else None,
        "task_samples": control, "after": after}
    report["aborted_error_type"] = failure
    report["validity"] = clock_validity(resource.samples)
    write_json(output / f"round-{number}-{label}-latencies.json", dict(latency))
    write_json(output / f"round-{number}-{label}.json", report)
    if failure:
        raise RuntimeError("本轮提前终止，原始统计与失败已保留:"+failure)
    print(json.dumps({"runtime": manifest["runtime"], "round": number, "phase": label, "elapsed_seconds": report["elapsed_including_drain_seconds"],
                      "groups": report["groups"], "resources": report["resource_summary"]}), flush=True)
    return report


def launch(runtime, isolation, db, output, source, args, *, attach=False, extra=None):
    isolation.verify_owned(db)
    output.mkdir(parents=True, exist_ok=True)
    manifest = output / "server-manifest.json"
    clean_cwd = output / "clean-cwd"
    clean_cwd.mkdir()
    env = {k: v for k, v in os.environ.items() if k in {"PATH", "HOME", "TMPDIR", "LANG", "LC_ALL", "JAVA_HOME"}}
    env.update({k: os.environ[k] for k in PgIsolation.REQUIRED})
    env.update({"PYTHONDONTWRITEBYTECODE": "1", "J5_MANIFEST": str(manifest), "J5_OWNERSHIP": str(isolation.manifest_path),
                "J5_DB_NAME": db.name, "J5_JDBC_URL": "jdbc:"+db.url, "J5_DB_COMMENT": db.comment,
                "J5_DB_USER": isolation.user, "J5_DB_PASSWORD": isolation.password, "J5_DB_DATA_DIR": str(isolation.data_dir),
                "J5_CONTROL_KEY": secrets.token_hex(32), "J5_SOURCE": source, "J5_ATTACH": str(attach).lower()})
    if extra:
        env.update(extra)
    if runtime == "python":
        command = [sys.executable, "-B", str(ROOT / "acceptance/load/python_server.py")]
    else:
        java = str(Path(args.java_home) / "bin/java")
        classpath = str(ROOT / "backend-java/target/test-classes") + os.pathsep + str(ROOT / "backend-java/target/classes") + os.pathsep + Path(args.classpath).read_text().strip()
        command = [java, "-Xms64m", "-Xmx256m", "-XX:MaxMetaspaceSize=128m", "-XX:ReservedCodeCacheSize=48m", "-Xss512k", "-cp", classpath,
                   "io.dpdk.assistant.acceptance.load.CapacityServer"]
    log = (output / "server.log").open("w")
    process = subprocess.Popen(command, cwd=clean_cwd, env=env, stdout=log, stderr=log)
    log.close()
    deadline = time.monotonic()+180
    while time.monotonic() < deadline:
        if manifest.exists():
            data = json.loads(manifest.read_text())
            if data["pid"] != process.pid:
                raise RuntimeError("manifest PID与本轮子进程不符")
            return process, data
        if process.poll() is not None:
            raise RuntimeError("合成服务启动失败，查看本轮server.log")
        time.sleep(.1)
    process.terminate()
    raise RuntimeError("服务ready超时；保留资产")


def stop_process(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(20)
        except subprocess.TimeoutExpired:
            # 不在容量采集时悄悄制造SIGKILL恢复；留下明确失败供主Agent处理。
            raise RuntimeError("本轮服务未正常停止，保留PID和恢复资产")


async def ip_counterexample(manifest):
    """同一来源11次仍429；不同学生XFF的正式60次login不可被同IP限流掩盖。"""
    import httpx
    statuses = []
    async with httpx.AsyncClient(base_url=manifest["base_url"], trust_env=False, timeout=30,
            headers={"X-Forwarded-For": "198.18.1.250"}) as client:
        for _ in range(11):
            response = await client.post("/api/auth/login", json={"username": manifest["students"][0], "password": manifest["students"][0]})
            statuses.append(response.status_code)
    if statuses != [200]*10+[429]:
        raise RuntimeError("可信代理remoteAddr/真实同IP限流反例失败")
    return {"statuses": statuses, "expected": [200]*10+[429], "pass": True}


async def run_runtime(runtime, isolation, output, source, args):
    import httpx
    db = isolation.create(runtime)
    process, manifest = launch(runtime, isolation, db, output, source, args)
    manifest["measurement_control_pids"] = [args.sleep_guard["pid"]] if args.sleep_guard.get("pid") else []
    report = {"runtime": runtime, "db": asdict(db), "warmups": [], "rounds": [], "layout_gate": False,
              "layout": "loopback TCP; trusted XFF proxy=driver; real PG retrieval; per-call fixed external boundaries; app+driver synchronous resources",
              "server_settings": manifest["settings"], "environment": {"platform": platform.platform(), "python": sys.version,
                  "cpu_count": os.cpu_count(), "runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  "sleep_prevention": args.sleep_guard},
              "qualification_only": args.qualification}
    try:
        report["same_ip_rate_limit_counterexample"] = await ip_counterexample(manifest)
        for number in range(1, args.rounds+1):
            report["warmups"].append(await phase(manifest, isolation, db, args.warmup, number, "warmup", output, source))
            report["rounds"].append(await phase(manifest, isolation, db, args.measurement, number, "measurement", output, source))
            write_json(output / "summary.json", report)
        async with httpx.AsyncClient(base_url=manifest["base_url"], trust_env=False, timeout=30,
                    headers={"X-J5-Control": manifest["control_key"]}) as client:
            state = (await client.get("/__j5/state")).json()
        final = snapshot(isolation, db)
        with isolation._connect(db.name) as conn:
            owned_task = conn.execute("SELECT id,user_id FROM rag_requests ORDER BY id LIMIT 1").fetchone()
        if owned_task is None:
            unauthorized = False
        else:
            wrong = next(i for i, subject in enumerate(manifest["students"]) if subject != owned_task[1])
            async with httpx.AsyncClient(base_url=manifest["base_url"], trust_env=False, timeout=30,
                    headers={"Authorization": "Bearer "+manifest["tokens"][wrong]}) as client:
                unauthorized = (await client.get("/api/tasks/"+owned_task[0])).status_code == 404
        successes = sum(r["groups"]["formal_submit"]["requests"] - r["groups"]["formal_submit"]["unexpected_errors"] for r in report["warmups"]+report["rounds"])
        report["final"], report["boundary_calls"] = final, state["calls"]
        report["correctness"] = {"no_unauthorized_access": unauthorized,
            "all_accepted_terminal": final["pending"] == 0 and final["tasks"] == final["answered"],
            "one_chat_charge_per_task": final["tasks"] == final["chat_charges"], "no_duplicate_grade": final["duplicate_grades"] == 0,
            "terminal_inputs_deleted": final["inputs"] == 0, "formal_successes_equal_grades": final["grades"] == successes,
            "mcp_formal_count_matches": state["calls"].get("mcp:grade_plugin", 0) == successes,
            "no_grade_from_explanation": final["grades"] == final["grading_charges"] == successes,
            "no_network_denial": state["calls"].get("network_denial", 0) == 0}
    finally:
        # 默认永不drop。恢复与统计资产由主Agent验收后按manifest另行清理。
        write_json(output / "summary.json", report)
        stop_process(process)
    return report


def sleep_prevention():
    """macOS caffeinate先fork守护子进程，再exec目标命令；不假定它一定是父进程。"""
    children = subprocess.run(["pgrep", "-P", str(os.getpid())], capture_output=True, text=True).stdout.split()
    for pid in (*children, str(os.getppid())):
        command = subprocess.run(["ps", "-o", "comm=", "-p", pid], capture_output=True, text=True).stdout.strip()
        if Path(command).name != "caffeinate":
            continue
        arguments = subprocess.run(["ps", "-o", "args=", "-p", pid], capture_output=True, text=True).stdout.split()
        if "-i" in arguments:
            return {"tool": "caffeinate", "idle_sleep_disabled": True, "pid": int(pid), "included_in_resource_sum": True}
    raise RuntimeError("macOS正式测量必须由caffeinate -i启动；拒绝无睡眠保护运行")


async def main_async(args):
    args.sleep_guard = sleep_prevention() if sys.platform == "darwin" and not args.qualification else {"tool": "not_required_for_qualification_or_non_macos"}
    output = Path(args.output).resolve()
    if not any(parent.name == ".migration-artifacts" for parent in output.parents):
        raise RuntimeError("原始统计/manifest只能写入gitignored .migration-artifacts目录")
    if output.exists():
        raise RuntimeError("拒绝覆盖已有轮次证据")
    output.mkdir(parents=True)
    isolation = PgIsolation.from_env(output / "ownership.json", allowed_prefixes=("p5_capacity_", "p5_resilience_"))
    isolation.verify_instance()
    source = "/* P0合成 " + uuid.uuid4().hex + " */\nint plugin_main(void){return 0;}\n"
    reports = {}
    for runtime in (("python", "java") if args.runtime == "both" else (args.runtime,)):
        reports[runtime] = await run_runtime(runtime, isolation, output / runtime, source, args)
    threshold = json.loads((ROOT / "migration/contracts/thresholds.json").read_text())
    # 单端结果永远不自动满足layout。必须双端设置/负载协议相同且非qualification。
    aligned = len(reports) == 2 and reports["python"]["server_settings"] == reports["java"]["server_settings"] and not args.qualification
    for report in reports.values():
        report["layout_gate"] = aligned
        report["evaluation"] = evaluate(report, threshold) if not args.qualification else {"pass": False, "qualification_only": True}
        if args.qualification:
            phases = report["warmups"]+report["rounds"]
            report["qualification_pass"] = bool(phases) and all(report.get("correctness", {}).values()) and all(
                phase["validity"]["valid"] and all(phase["groups"][group]["requests"] > 0 and phase["groups"][group]["unexpected_errors"] == 0
                    for group in GROUPS) for phase in phases)
        write_json(output / report["runtime"] / "summary.json", report)
    write_json(output / "pair-summary.json", {r: {"layout_gate": v["layout_gate"], "evaluation": v["evaluation"],
        "qualification_pass": v.get("qualification_pass")} for r, v in reports.items()})
    if args.qualification:
        return 0 if reports and all(r["qualification_pass"] for r in reports.values()) else 1
    return 0 if all(r["evaluation"]["pass"] for r in reports.values()) else 1


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--runtime", choices=("python", "java", "both"), default="both")
    result.add_argument("--output", required=True, help="新的gitignored绝对运行目录；拒绝覆盖")
    result.add_argument("--classpath", required=True, help="Maven dependency:build-classpath输出文件")
    result.add_argument("--java-home", default=os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@21"))
    result.add_argument("--rounds", type=int, default=3)
    result.add_argument("--warmup", type=float, default=120)
    result.add_argument("--measurement", type=float, default=600)
    result.add_argument("--qualification", action="store_true", help="仅短测入口，永不判容量通过")
    return result


if __name__ == "__main__":
    args = parser().parse_args()
    if not args.qualification and (args.rounds, args.warmup, args.measurement) != (3, 120, 600):
        raise SystemExit("正式容量必须3轮120s预热+600s测量")
    raise SystemExit(asyncio.run(main_async(args)))
