"""J5双Java进程SIGKILL、真实队列和恢复矩阵；必须由主Agent放行独占窗口。"""
from __future__ import annotations

import argparse
import asyncio
from dataclasses import asdict
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))
from acceptance.contract.isolation import PgIsolation
from acceptance.load.run import launch, snapshot, stop_process, write_json


async def control(manifest, body=None):
    import httpx
    async with httpx.AsyncClient(base_url=manifest["base_url"], trust_env=False, timeout=30,
            headers={"X-J5-Control": manifest["control_key"]}) as client:
        response = await client.get("/__j5/state") if body is None else await client.post("/__j5/control", json=body)
        response.raise_for_status()
        return response.json()


def task_row(isolation, db, request):
    isolation.verify_owned(db)
    with isolation._connect(db.name) as conn:
        row = conn.execute("SELECT id,user_id,session_id,status,reason,input_hash,request_id FROM rag_requests WHERE request_id=%s", (request,)).fetchone()
        return None if row is None else dict(zip(("id", "subject", "session", "status", "reason", "input_hash", "request_id"), row))


async def until(predicate, *, seconds=30, interval=.02):
    deadline = time.monotonic()+seconds
    while time.monotonic() < deadline:
        value = predicate()
        if hasattr(value, "__await__"):
            value = await value
        if value:
            return value
        await asyncio.sleep(interval)
    raise RuntimeError("定向边界等待超时；保留失败与资产")


def events(path):
    return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []


def durable_projection(isolation, db, task):
    with isolation._connect(db.name) as conn:
        # 源原文只在内存参与hash断言，不输出到证据。
        row = conn.execute("SELECT source_text FROM rag_task_inputs WHERE task_id=%s", (task,)).fetchone()
        return {"task_count": conn.execute("SELECT count(*) FROM rag_requests WHERE id=%s", (task,)).fetchone()[0],
                "source_sha256": hashlib.sha256(row[0].encode()).hexdigest() if row and row[0] else None,
                "source_present": bool(row and row[0]),
                "jobs": conn.execute("SELECT job_id,status,submission_done FROM rag_task_jobs WHERE task_id=%s ORDER BY job_id", (task,)).fetchall(),
                "grades": conn.execute("SELECT id,task_job_id,score FROM grading_submissions WHERE task_job_id IN (SELECT job_id FROM rag_task_jobs WHERE task_id=%s) ORDER BY id", (task,)).fetchall(),
                "charges": conn.execute("SELECT kind,sum(chat_count) FROM usage_counters GROUP BY kind ORDER BY kind").fetchall()}


def source_sentinels(isolation, db, source, output, args):
    marker = source.split("\n", 1)[0]
    counts = {}
    with isolation._connect(db.name) as conn:
        tables = [r[0] for r in conn.execute("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")]
        for table in tables:
            if table == "rag_task_inputs":
                continue
            # 表名来自PG catalog；无输出源原文，仅记录命中总数。
            quoted = '"'+table.replace('"', '""')+'"'
            counts[table] = conn.execute("SELECT count(*) FROM "+quoted+" t WHERE row_to_json(t)::text LIKE %s", ("%"+marker+"%",)).fetchone()[0]
    log_hits = sum(path.read_text(errors="replace").count(marker) for path in output.rglob("*.log"))
    pg_dump = str(Path(args.pg_bin)/"pg_dump") if args.pg_bin else shutil.which("pg_dump")
    backup = {"executed": False, "pass": False, "reason": "未配置pg_dump"}
    if pg_dump:
        target = output / "terminal-backup.sql"
        env = {k: v for k, v in os.environ.items() if k in {"PATH", "HOME", "LANG", "LC_ALL"}}
        env["PGPASSWORD"] = isolation.password
        result = subprocess.run([pg_dump, "--host", isolation.host, "--port", str(isolation.port), "--username", isolation.user,
                                 "--dbname", db.name, "--no-owner", "--no-privileges", "--file", str(target)], env=env, capture_output=True)
        os.chmod(target, 0o600) if target.exists() else None
        hit = target.read_text(errors="replace").count(marker) if target.exists() else None
        backup = {"executed": True, "exit_code": result.returncode, "source_hits": hit, "pass": result.returncode == 0 and hit == 0}
    return {"long_term_table_hits": counts, "log_hits": log_hits, "backup": backup,
            "pass": not any(counts.values()) and log_hits == 0 and backup["pass"]}


async def fault_case(isolation, output, args, source, case):
    import httpx
    db = isolation.create(case["id"][:20], prefix="p5_resilience_")
    output.mkdir(parents=True)
    marker, ledger = output/"producer-marker.json", output/"external-ledger.jsonl"
    extra = {"J5_FAULT": case["point"], "J5_MARKER": str(marker), "J5_EXTERNAL_LEDGER": str(ledger),
             "J5_MODEL_DELAY": ".01", "J5_RETRIEVAL_DELAY": ".01", "J5_MCP_DELAY": ".01"}
    producer, original = launch("java", isolation, db, output/"producer", source, args, extra=extra)
    request_id = str(uuid.uuid4())
    request = {"request_id": request_id, "message": "__grade__ 请评测合成DNS插件" if case.get("grade") else "__kb__ 合成DNS标签如何编码？"}
    result = {"case_id": case["id"], "db": asdict(db), "point": case["point"], "status_mode": case.get("status", "finished"),
              "producer_pid": producer.pid, "rto_scope": "恢复JVM已经ready、PG健康/槽位可用；ensureManaged到终态；排除启动/grace/扫描"}
    recovery = None
    try:
        async with httpx.AsyncClient(base_url=original["base_url"], trust_env=False, timeout=120,
                headers={"Authorization": "Bearer "+original["tokens"][0], "X-Forwarded-For": "198.18.0.1"}) as client:
            # 原始源经真实multipart寄存，受理阶段也能验证RPO；不写长期消息。
            pending = asyncio.create_task(client.post("/api/chat", data=request,
                files={"file": ("U20990000001_dns.c", source.encode(), "text/x-c")}))
            await until(marker.exists, seconds=45)
            row = task_row(isolation, db, request_id)
            if row is None:
                raise RuntimeError("故障门栓未经过真实受理")
            result["before_kill"] = durable_projection(isolation, db, row["id"])
            if not result["before_kill"]["source_present"] and row["status"] == "pending":
                raise RuntimeError("受理临时源未持久化")
            killed = time.monotonic()
            producer.kill()  # Popen的本轮PID；POSIX真实SIGKILL，不按名称杀进程。
            await asyncio.to_thread(producer.wait, 10)
            result["producer_exit"] = producer.returncode
            try:
                await pending
            except httpx.HTTPError:
                pass  # 预期进程终止；故障请求不自动重试。
        result["after_kill"] = durable_projection(isolation, db, row["id"])
        if result["after_kill"] != result["before_kill"]:
            raise RuntimeError("SIGKILL后持久化RPO投影改变")
        # 新真实JVM attach同owned库、不seed、不复用A内存账本；外部fake账本仍在。
        recovery_extra = {"J5_EXTERNAL_LEDGER": str(ledger), "J5_STATUS": case.get("status", "finished"),
                          "J5_MODEL_DELAY": ".01", "J5_RETRIEVAL_DELAY": ".01", "J5_MCP_DELAY": ".01"}
        recovery, rebuilt = launch("java", isolation, db, output/"recoverer", source, args, attach=True, extra=recovery_extra)
        result["recovery_pid"] = recovery.pid
        result["kill_to_ready_seconds"] = time.monotonic()-killed
        if producer.pid == recovery.pid or producer.returncode != -signal.SIGKILL:
            raise RuntimeError("未证明生产者SIGKILL和新恢复JVM")
        # 唯一绕过grace操作：owned合成行明确拨旧；不把它宣称自动扫描RTO。
        with isolation._connect(db.name) as conn:
            conn.execute("UPDATE rag_requests SET updated_at=%s WHERE id=%s AND status='pending'",
                ((datetime.now(timezone.utc)-timedelta(seconds=31)).isoformat(), row["id"]))
        event_before = events(ledger)
        if row["status"] == "pending":
            recovering = asyncio.create_task(control(rebuilt, {"recover": row["id"], "subject": row["subject"]}))
            if case.get("status") == "running":
                async def queried():
                    state = await control(rebuilt)
                    return state["calls"].get("mcp:get_task_status", 0) >= 2
                await until(queried, seconds=10)
                checks = events(ledger)[len(event_before):]
                if any(e["tool"] == "grade_plugin" for e in checks):
                    raise RuntimeError("running不允许重新提交")
                await control(rebuilt, {"status": "finished"})
            result["recovery"] = await recovering
        else:
            result["recovery"] = {"status": row["status"], "execution_seconds": 0, "terminal_reused": True}
        final = task_row(isolation, db, request_id)
        post = durable_projection(isolation, db, row["id"])
        new_external = events(ledger)[len(event_before):]
        submitted_before = [e for e in event_before if e["tool"] == "grade_plugin"]
        submitted_after = [e for e in new_external if e["tool"] == "grade_plugin"]
        safe_unknown = case.get("status") in ("unknown", "query_failed")
        result["checks"] = {
            "producer_sigkill": producer.returncode == -signal.SIGKILL, "new_jvm": producer.pid != recovery.pid,
            "rpo_zero_committed_projection": result["before_kill"] == result["after_kill"],
            "same_identity": (final["id"], final["request_id"], final["input_hash"]) == (row["id"], row["request_id"], row["input_hash"]),
            "terminal": final["status"] != "pending", "source_deleted": not post["source_present"],
            "single_chat_charge": dict(post["charges"]).get("chat") == 1,
            "single_grade": len(post["grades"]) <= 1,
            "no_mcp_resubmit": not submitted_after if row["status"] != "pending" or case.get("grade") else True,
            "unknown_safety": (final["status"] == "system_error" and bool(post["jobs"]) and post["jobs"][0][1] == "submitted" and not submitted_after) if safe_unknown else True,
            "rto_1s": result["recovery"]["execution_seconds"] <= 1 if case.get("rto") else True}
        result["external_call_counts"] = {"before_kill_grade_submits": len(submitted_before), "after_ready_grade_submits": len(submitted_after),
                                            "after_ready_status_queries": sum(e["tool"] == "get_task_status" for e in new_external)}
        result["after_recovery"] = post
        result["source_sentinel"] = source_sentinels(isolation, db, source, output, args)
        result["pass"] = all(result["checks"].values()) and result["source_sentinel"]["pass"]
    except Exception as exc:
        result["pass"], result["error_type"] = False, type(exc).__name__
        raise
    finally:
        if producer.poll() is None:
            stop_process(producer)
        if recovery is not None:
            stop_process(recovery)
        write_json(output/"result.json", result)
    return result


async def queue_case(isolation, output, args, source):
    import httpx
    db = isolation.create("queue", prefix="p5_resilience_")
    process, manifest = launch("java", isolation, db, output/"server", source, args)
    results = []
    try:
        await control(manifest, {"hold": True})
        async def request(index):
            async with httpx.AsyncClient(base_url=manifest["base_url"], trust_env=False, timeout=45,
                    headers={"Authorization": "Bearer "+manifest["tokens"][index], "X-Forwarded-For": f"198.18.0.{index+1}"}) as client:
                started = time.monotonic()
                response = await client.post("/api/chat", json={"message": "__kb__ 合成DNS标签如何编码？", "request_id": str(uuid.uuid4())})
                result = {"status": response.status_code, "seconds": time.monotonic()-started,
                          "detail": response.json().get("detail"), "answer_status": response.json().get("answer_status")}
                results.append(result)
                return result
        tasks = [asyncio.create_task(request(i)) for i in range(41)]
        peak = {"active": 0, "waiting": 0}
        async def full():
            state = await control(manifest)
            for key in peak:
                peak[key] = max(peak[key], state[key])
            return state["active"] == 20 and state["waiting"] == 20
        await until(full, seconds=8)
        await until(lambda: len(results) >= 21, seconds=15)
        await control(manifest, {"hold": False})
        await asyncio.gather(*tasks)
        state, final = await control(manifest), snapshot(isolation, db)
        checks = {"requests_41": len(results) == 41, "peak_20_20": peak == {"active": 20, "waiting": 20},
            "one_immediate_full": sum(r["status"] == 429 and r["detail"] == "服务繁忙，请稍后重试" for r in results) == 1,
            "20_wait_timeouts": sum(r["status"] == 429 and r["detail"] == "等待超时，请稍后重试" and r["seconds"] >= 10 for r in results) == 20,
            "20_answered": sum(r["status"] == 200 and r["answer_status"] == "answered" for r in results) == 20,
            "quota_20": final["chat_charges"] == final["tasks"] == 20, "terminal_inputs_zero": final["inputs"] == 0,
            "slots_zero": state["active"] == state["waiting"] == 0}
        result = {"case_id": "queue-20-20-10", "db": asdict(db), "checks": checks, "responses": results, "peak": peak,
                  "final": final, "pass": all(checks.values()), "expected_backpressure": 21, "denominator": 41}
        write_json(output/"result.json", result)
        return result
    finally:
        stop_process(process)


def boundary_cases(isolation, output, args):
    db = isolation.create("clock_boundaries", prefix="p5_resilience_")
    env = dict(os.environ)
    env.update({"J5_BOUNDARY_DB_NAME": db.name, "J5_BOUNDARY_DB_COMMENT": db.comment, "JAVA_HOME": args.java_home})
    output.mkdir(parents=True)
    log = output/"boundary.log"
    with log.open("w") as handle:
        result = subprocess.run([str(ROOT/"backend-java/mvnw"), "-f", str(ROOT/"backend-java/pom.xml"), "-B",
            "-Dtest=DefaultBoundaryPgTest", "test"], cwd=ROOT, env=env, stdout=handle, stderr=handle)
    # 必须确认3项实际执行、0skip；只看Maven退出码不够。
    import xml.etree.ElementTree as ET
    report = ROOT/"backend-java/target/surefire-reports/TEST-io.dpdk.assistant.acceptance.resilience.DefaultBoundaryPgTest.xml"
    xml = ET.parse(report).getroot() if report.exists() else None
    passed = result.returncode == 0 and xml is not None and all(int(xml.attrib.get(k, -1)) == v for k, v in {"tests": 3, "failures": 0, "errors": 0, "skipped": 0}.items())
    summary = {"case_id": "default-600s-24h-300s-controlled-clock", "db": asdict(db), "exit_code": result.returncode,
               "pass": passed, "executed_test_counts": dict(xml.attrib) if xml is not None else None,
               "scope": "真实PG与TaskRunner；可控调度/时钟，不声称实际600s墙钟已等待"}
    write_json(output/"result.json", summary)
    return summary


async def lifecycle_case(isolation, output, args, source):
    import httpx
    db = isolation.create("lifecycle", prefix="p5_resilience_")
    process, manifest = launch("java", isolation, db, output/"server", source, args)
    checks = {}
    projection = lambda request: task_row(isolation, db, request)
    async def row_terminal(request):
        row = projection(request)
        return row if row is not None and row["status"] != "pending" else None
    def request_data(request):
        return {"message": "__kb__ 合成DNS标签如何编码？", "request_id": request}
    try:
        async with httpx.AsyncClient(base_url=manifest["base_url"], trust_env=False, timeout=45,
                headers={"Authorization": "Bearer "+manifest["tokens"][0], "X-Forwarded-For": "198.18.0.1"}) as client:
            before = snapshot(isolation, db)
            rejected = await client.post("/api/chat", json={"message": ""})
            unauthorized = await client.post("/api/chat", json={"message": "bad"}, headers={"Authorization": "Bearer invalid"})
            checks["before_admission_zero_side_effect"] = rejected.status_code == 422 and unauthorized.status_code == 401 and snapshot(isolation, db) == before
            # 真实TCP关闭stream；不是在Java内部直接cancel订阅者。
            await control(manifest, {"hold": True})
            request = str(uuid.uuid4())
            async with client.stream("POST", "/api/chat/stream", data=request_data(request),
                    files={"file": ("plugin_dns.c", source.encode(), "text/x-c")}) as stream:
                stream.raise_for_status()
                async for line in stream.aiter_lines():
                    if line.startswith("data:"):
                        break
            row = await until(lambda: projection(request))
            await asyncio.sleep(.05)
            checks["tcp_disconnect_not_cancelled"] = projection(request)["status"] == "pending"
            await control(manifest, {"hold": False})
            terminal = await until(lambda: row_terminal(request))
            checks["disconnected_task_commits"] = terminal["status"] == "answered"
            before_replay = await control(manifest)
            ledger_replay = snapshot(isolation, db)
            replay = await client.post("/api/chat", data=request_data(request),
                    files={"file": ("plugin_dns.c", source.encode(), "text/x-c")})
            after_replay = await control(manifest)
            checks["replay_no_model_mcp_or_charge"] = replay.status_code == 200 and replay.json().get("task_id") == row["id"] and before_replay["calls"] == after_replay["calls"] and snapshot(isolation, db) == ledger_replay
            # 故意忽略一次中断的模型，放行后返回迟到结果；终态守卫必须拒绝覆盖取消。
            await control(manifest, {"hold": True, "ignore_model_interrupt": True})
            cancelled_request = str(uuid.uuid4())
            pending = asyncio.create_task(client.post("/api/chat", data=request_data(cancelled_request),
                files={"file": ("plugin_dns.c", source.encode(), "text/x-c")}))
            cancelled_row = await until(lambda: projection(cancelled_request))
            cancelling = asyncio.create_task(client.post("/api/tasks/"+cancelled_row["id"]+"/cancel"))
            await until(lambda: row_terminal(cancelled_request))
            await control(manifest, {"hold": False})
            cancelled_response = await cancelling
            try:
                await pending
            except httpx.HTTPError:
                pass
            repeat = await client.post("/api/tasks/"+cancelled_row["id"]+"/cancel")
            await asyncio.sleep(.1)
            checks["explicit_cancel_repeat_and_late_save"] = cancelled_response.status_code == repeat.status_code == 200 and projection(cancelled_request)["status"] == "cancelled"
            # 活动任务停号走真实账号业务事务+本地取消，token撤销不可被正缓存绕过。
            await control(manifest, {"hold": True, "ignore_model_interrupt": False})
            disabled_request = str(uuid.uuid4())
            async with httpx.AsyncClient(base_url=manifest["base_url"], trust_env=False, timeout=45,
                    headers={"Authorization": "Bearer "+manifest["tokens"][1], "X-Forwarded-For": "198.18.0.2"}) as disabled:
                pending = asyncio.create_task(disabled.post("/api/chat", data=request_data(disabled_request),
                    files={"file": ("plugin_dns.c", source.encode(), "text/x-c")}))
                disabled_row = await until(lambda: projection(disabled_request))
                await control(manifest, {"disable": manifest["students"][1]})
                await control(manifest, {"hold": False})
                try:
                    await pending
                except httpx.HTTPError:
                    pass
                final = projection(disabled_request)
                token_read = await disabled.get("/api/tasks/"+disabled_row["id"])
                checks["disable_cancels_and_revokes"] = final["status"] == "cancelled" and final["reason"] == "account_disabled" and token_read.status_code == 401
            final, state = snapshot(isolation, db), await control(manifest)
            checks["no_incomplete_grades"] = final["grades"] == final["grading_charges"] == 0
            checks["terminal_source_clean_and_slots_zero"] = final["pending"] == final["inputs"] == state["active"] == state["waiting"] == 0
            sentinel = source_sentinels(isolation, db, source, output, args)
            result = {"case_id": "tcp-lifecycle-cancel-disable-replay", "db": asdict(db), "checks": checks,
                      "source_sentinel": sentinel, "final": final, "pass": all(checks.values()) and sentinel["pass"]}
            write_json(output/"result.json", result)
            return result
    finally:
        if process.poll() is None:
            await control(manifest, {"hold": False, "ignore_model_interrupt": False})
        stop_process(process)


CASES = (
    {"id": "accepted", "point": "model_first_before", "rto": True},
    {"id": "model_after", "point": "model_first_after"},
    {"id": "mcp_before", "point": "mcp_before", "grade": True, "status": "unknown"},
    {"id": "mcp_finished", "point": "mcp_submitted", "grade": True, "status": "finished"},
    {"id": "mcp_unknown", "point": "mcp_submitted", "grade": True, "status": "unknown"},
    {"id": "mcp_queryfailed", "point": "mcp_submitted", "grade": True, "status": "query_failed"},
    {"id": "mcp_running", "point": "mcp_submitted", "grade": True, "status": "running"},
    {"id": "grade_before", "point": "grade_before", "grade": True},
    {"id": "grade_after", "point": "grade_after", "grade": True},
    {"id": "before_terminal", "point": "finish_before", "grade": True, "rto": True},
    {"id": "after_terminal", "point": "finish_after", "grade": True},
)


async def main(args):
    output = Path(args.output).resolve()
    if not any(parent.name == ".migration-artifacts" for parent in output.parents):
        raise RuntimeError("故障源/manifest/备份只能写入gitignored .migration-artifacts目录")
    if output.exists():
        raise RuntimeError("拒绝覆盖既有故障资产")
    output.mkdir(parents=True)
    isolation = PgIsolation.from_env(output/"ownership.json", allowed_prefixes=("p5_capacity_", "p5_resilience_"))
    isolation.verify_instance()
    source = "/* J5-SOURCE-"+secrets.token_hex(16)+" */\nint plugin_main(void){return 0;}\n"
    results = []
    planned = []
    if args.case in ("all", "clock"):
        planned.append(("clock", lambda: boundary_cases(isolation, output/"clock", args)))
    if args.case in ("all", "queue"):
        planned.append(("queue", lambda: queue_case(isolation, output/"queue", args, source)))
    if args.case in ("all", "lifecycle"):
        planned.append(("lifecycle", lambda: lifecycle_case(isolation, output/"lifecycle", args, source)))
    for case in CASES:
        if args.case in ("all", case["id"]):
            planned.append((case["id"], lambda case=case: fault_case(isolation, output/case["id"], args, source, case)))
    for name, action in planned:
        try:
            result = action()
            results.append(await result if hasattr(result, "__await__") else result)
        except Exception as exc:
            # 完整异常仅留gitignored；摘要不泄露HTTP输入/环境/凭据。
            import traceback
            (output/"failure.log").write_text(traceback.format_exc())
            results.append({"case_id": name, "pass": False, "error_type": type(exc).__name__})
        write_json(output/"summary.json", {"pass": len(results) == len(planned) and all(r["pass"] for r in results),
            "cases": results, "planned_count": len(planned), "executed_count": len(results),
            "production_or_live_claim": False, "assets_retained": True})
        if not results[-1]["pass"]:
            break
    return 0 if results and all(r["pass"] for r in results) else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True)
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME", "/opt/homebrew/opt/openjdk@21"))
    parser.add_argument("--pg-bin", help="显式pg_dump所在目录；无备份哨兵不判完整矩阵通过")
    parser.add_argument("--case", choices=("all", "clock", "queue", "lifecycle", *(c["id"] for c in CASES)), default="all")
    raise SystemExit(asyncio.run(main(parser.parse_args())))
