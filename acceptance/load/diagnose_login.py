"""独立真实登录性能诊断；不改变冻结容量协议/阈值，不作为正式容量报告。"""
from __future__ import annotations
import argparse
import asyncio
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from acceptance.contract.isolation import PgIsolation
from acceptance.load.run import (Resources, cpu_seconds, launch, physical_environment,
    source_evidence, stop_process, write_json)
from acceptance.load.statistics import percentiles


def process_point(pids):
    raw = subprocess.run(["ps", "-o", "pid=,rss=,time=", "-p", ",".join(map(str, pids))],
                         check=True, capture_output=True, text=True).stdout
    rows = {int(pid): {"rss_kib": int(rss), "cpu_seconds": cpu_seconds(cpu)}
            for pid, rss, cpu in (line.split() for line in raw.splitlines())}
    if set(rows) != set(pids):
        raise RuntimeError("诊断进程消失，不能忽略")
    return {"monotonic_seconds": time.monotonic(), "processes": rows}


async def probe(manifest, isolation, db, *, stagger, sampling, output, profile=False):
    import httpx
    latencies, statuses = [], []
    stop = asyncio.Event()
    resources = Resources(manifest["pid"], isolation, db)
    async def monitor():
        while not stop.is_set():
            resources.sample()  # 同正式driver同步采样方式；只在sampling探针启用。
            try:
                await asyncio.wait_for(stop.wait(), 1)
            except asyncio.TimeoutError:
                pass
    clients = [httpx.AsyncClient(base_url=manifest["base_url"], timeout=30, trust_env=False,
               headers={"X-Forwarded-For": f"198.18.0.{i+1}"}) for i in range(60)]
    async def login(index):
        if stagger:
            await asyncio.sleep(index * .025)
        started = time.monotonic()
        user = manifest["students"][index]
        try:
            response = await clients[index].post("/api/auth/login", json={"username": user, "password": user})
            good = response.status_code == 200 and response.json().get("subject") == user
            status = {"http_status": response.status_code, "subject_matches": good}
        except Exception as exc:
            status = {"error_type": type(exc).__name__, "subject_matches": False}
        latencies.append((time.monotonic()-started)*1000)
        statuses.append({"student_index": index, **status})
    profile_exit = None
    before = process_point(resources.pids)
    monitor_task = asyncio.create_task(monitor()) if sampling else None
    if profile:
        jcmd = str(Path("/opt/homebrew/opt/openjdk@21/bin/jcmd"))
        started = subprocess.run([jcmd, str(manifest["pid"]), "JFR.start", "name=j5_login", "settings=profile",
            "filename="+str(output/"cpu-profile.jfr")], capture_output=True, text=True, check=True)
        (output/"jfr-start.txt").write_text(started.stdout)
    try:
        if stagger:
            await asyncio.gather(*(login(i) for i in range(60)))
        else:
            for index in range(60):
                await login(index)
    finally:
        stop.set()
        if monitor_task:
            await monitor_task
            resources.sample()
        for client in clients:
            await client.aclose()
    after = process_point(resources.pids)
    if profile:
        ended = subprocess.run([jcmd, str(manifest["pid"]), "JFR.stop", "name=j5_login"],
            capture_output=True, text=True, check=True)
        profile_exit = ended.returncode
        (output/"jfr-stop.txt").write_text(ended.stdout)
    elapsed = after["monotonic_seconds"]-before["monotonic_seconds"]
    cpu_delta = sum(after["processes"][pid]["cpu_seconds"]-before["processes"][pid]["cpu_seconds"] for pid in resources.pids)
    result = {"probe": output.name, "stagger_seconds": .025 if stagger else None,
        "sampling": sampling, "profile_sampling": profile, "profile_exit": profile_exit,
        "login": percentiles(latencies), "unexpected_errors": sum(not s["subject_matches"] for s in statuses),
        "elapsed_seconds": elapsed, "combined_cpu_mean_one_core_percent": cpu_delta/elapsed*100,
        "process_points": [before, after], "resource_samples": resources.samples,
        "rss_scope": "sampler phases have actual curve; non-sampler phases endpoints only, not peak",
        "profile_scope": "extra profile probe is observation only; profiler overhead not compared to frozen latency gate"}
    output.mkdir(exist_ok=True)
    write_json(output/"latencies.json", latencies)
    write_json(output/"statuses.json", statuses)
    write_json(output/"result.json", result)
    return result


def java_bcrypt_binding(server_dir):
    """按服务端启动日志记录NativeBcrypt实际选择的实现与库哈希，不按源码假定。"""
    lines = [line for line in (server_dir/"server.log").read_text(errors="replace").splitlines() if "bcrypt provider:" in line]
    if len(lines) != 1:
        raise RuntimeError("服务端日志须恰有一行bcrypt provider")
    detail = lines[0].split("bcrypt provider:", 1)[1].strip()
    binding = {"provider_log": detail}
    if detail.startswith("native (classpath:"):
        library = ROOT/"backend-java/target/classes"/detail[len("native (classpath:/"):-1]
        binding["native_library"] = {"path": str(library.relative_to(ROOT)), "sha256": hashlib.sha256(library.read_bytes()).hexdigest()}
    return binding


async def main(args):
    if args.runtime != "java" and (args.profile or args.profile_only):
        raise ValueError("JFR探针只允许Java运行时")
    output = Path(args.output).resolve()
    if output.exists() or not any(p.name == ".migration-artifacts" for p in output.parents):
        raise RuntimeError("诊断输出须全新gitignored路径")
    output.mkdir(parents=True)
    isolation = PgIsolation.from_env(output/"ownership.json", allowed_prefixes=("p5_capacity_", "p5_resilience_"))
    isolation.verify_instance()
    db = isolation.create("login_diagnostic", prefix="p5_resilience_")
    process = None
    results = []
    try:
        process, manifest = launch(args.runtime, isolation, db, output/"server", "/* J5 login-only synthetic */\nint plugin_main(void){return 0;}", args)
        with isolation._connect(db.name) as conn:
            hash_cost = conn.execute("SELECT substring(password_hash from 1 for 7),count(*) FROM users GROUP BY 1 ORDER BY 1").fetchall()
        for label, stagger, sampling in (() if args.profile_only else (("serial60", False, False), ("stagger60_no_sampler", True, False), ("stagger60_sampler", True, True))):
            result = await probe(manifest, isolation, db, stagger=stagger, sampling=sampling, output=output/label)
            results.append(result)
            print(json.dumps({"probe": label, "login": result["login"], "errors": result["unexpected_errors"],
                "combined_cpu_mean_one_core_percent": result["combined_cpu_mean_one_core_percent"]}), flush=True)
        if args.profile or args.profile_only:
            # 不将profile的额外开销混入上述3个区分假设的探针。
            profile_dir = output/"profile_stagger60"
            profile_dir.mkdir()
            result = await probe(manifest, isolation, db, stagger=True, sampling=False, output=profile_dir, profile=True)
            results.append(result)
        frozen = json.loads((ROOT/"migration/contracts/thresholds.json").read_text())["synthetic_capacity"]["groups"]["login"]
        limit = min(frozen["absolute_p95_upper_ms"], frozen["reference_python_worst_round_p95_ms"]+frozen["allowed_p95_increase_ms"])
        for result in results:
            result["login_frozen_effective_limit_ms"] = limit
            result["login_signal_pass"] = result["login"]["p95_ms"] <= limit and result["unexpected_errors"] == 0
        crypto_jars = [Path(p) for p in Path(args.classpath).read_text().strip().split(os.pathsep) if "spring-security-crypto" in p]
        summary = {"source_evidence": source_evidence(args.runtime), "runtime": args.runtime,
            "hasher_binding": {"active_implementation": java_bcrypt_binding(output/"server") if args.runtime == "java" else "Python bcrypt",
                "java_wrapper": "io.dpdk.assistant.auth.PasswordHasher.check",
                "native_wrapper_sha256": hashlib.sha256((ROOT/"backend-java/src/main/java/io/dpdk/assistant/auth/NativeBcrypt.java").read_bytes()).hexdigest(),
                "wrapper_sha256": hashlib.sha256((ROOT/"backend-java/src/main/java/io/dpdk/assistant/auth/PasswordHasher.java").read_bytes()).hexdigest(),
                "classpath_jars": [{"name": p.name, "sha256": hashlib.sha256(p.read_bytes()).hexdigest()} for p in crypto_jars],
                "python_source": "backend/app/services/user_service.py:_verify_credentials_sync -> bcrypt.checkpw",
                "installed_python_bcrypt_version": importlib.metadata.version("bcrypt")}, "diagnostic_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "physical": physical_environment(), "hash_cost_prefix_counts": hash_cost, "results": results,
            "diagnostic_only": True, "formal_capacity_pass": None, "assets_retained": True}
        write_json(output/"summary.json", summary)
        return 0 if all(r["unexpected_errors"] == 0 for r in results) and all(
            r["login_signal_pass"] for r in results if not r["profile_sampling"]) else 1
    finally:
        if process:
            stop_process(process)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--java-home", default="/opt/homebrew/opt/openjdk@21")
    parser.add_argument("--runtime", choices=("java", "python"), default="java")
    parser.add_argument("--profile", action="store_true")
    parser.add_argument("--profile-only", action="store_true", help="只执行独立JFR cost观察；不作延迟门禁")
    raise SystemExit(asyncio.run(main(parser.parse_args())))
