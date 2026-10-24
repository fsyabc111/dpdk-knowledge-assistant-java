"""冻结 Python 的真实 ASGI/PG 性能与恢复采集；执行输入仅在运行时生成。"""
from __future__ import annotations

import argparse
import asyncio
from collections import Counter, defaultdict
from datetime import datetime, timedelta, timezone
import hashlib
import importlib.metadata
import json
import math
import os
from pathlib import Path
import platform
import resource
import signal
import socket
import statistics
import subprocess
import sys
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
from migration.oracle.safety import BASELINE, configure, initialize_schema

EVIDENCE = ROOT / "docs/java-migration/evidence/J0-02/performance"
ARTIFACTS = ROOT / ".migration-artifacts/p0/performance"


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")
    temporary.replace(path)


def quantiles(values):
    values = sorted(values)
    def pct(percent):
        return round(values[max(0, math.ceil(len(values) * percent) - 1)], 3) if values else None
    return {"n": len(values), "p50_ms": pct(.5), "p95_ms": pct(.95),
            "p99_ms": pct(.99) if len(values) >= 1000 else None,
            "p99_policy": "至少1000样本才报告最近秩p99", "max_ms": round(max(values), 3) if values else None}


class Runtime:
    def __init__(self, model_delay=1.0, retrieval_delay=.05, mcp_delay=.2):
        self.identity = configure("p0_benchmark_oracle")
        self.network_denials = 0
        previous_connect, previous_connect_ex = socket.socket.connect, socket.socket.connect_ex
        def check_address(address):
            if isinstance(address, tuple) and not (address[0] in {"127.0.0.1", "::1"} and address[1] in {55473, 9}):
                self.network_denials += 1
                raise RuntimeError("性能oracle仅允许专用PG端口或9号死上游")
        def connect(sock, address):
            check_address(address)
            return previous_connect(sock, address)
        def connect_ex(sock, address):
            check_address(address)
            return previous_connect_ex(sock, address)
        socket.socket.connect, socket.socket.connect_ex = connect, connect_ex
        # 功能 oracle 的低成本/放宽限流设置不作为容量设置；此处恢复实际默认门限。
        os.environ.update({"BCRYPT_ROUNDS": "12", "RATE_LIMIT_CHAT_PER_MINUTE": "20",
                           "DATABASE_POOL_MAX_SIZE": "32", "GRADING_DAILY_QUOTA": "20"})
        from app.config import get_settings
        get_settings.cache_clear()
        self.settings = get_settings()
        initialize_schema()
        from app.db import init_db
        init_db()
        self.model_delay, self.retrieval_delay, self.mcp_delay = model_delay, retrieval_delay, mcp_delay
        # 实际执行字节含每次运行的新标记，不提交运行输入原件。
        self.source = "/* P0合成 " + uuid.uuid4().hex + " */\nint plugin_main(void){return 0;}\n"
        self.calls = Counter()
        self.job_calls = Counter()
        self.mcp_events = []
        self.hold_after_mcp_submit = False
        self.status_mode = "unknown"
        self.hold_model = asyncio.Event()
        self.hold_model.set()
        self.finish_gate = asyncio.Event()
        self.finish_gate.set()
        self.gate_after_grade = False
        self.marker = None
        self.install_boundaries()
        from app.main import app
        self.app = app
        self.students = [f"U2099000{i:04d}" for i in range(1, 61)]
        self.leader = "U20990000999"
        self.clients = []
        self.tokens = []
        self.assignment_id = None
        self.lifecycle_started = False

    def install_boundaries(self):
        from langchain_core.language_models.chat_models import BaseChatModel
        from langchain_core.messages import AIMessage, ToolMessage
        from langchain_core.outputs import ChatGeneration, ChatResult
        from app.rag.types import RetrievedChunk
        from app.agent import service as agent_service
        from app.agent.mcp_client import HarnessMcpClient
        from app.services import conversation_memory
        runtime = self

        class Model(BaseChatModel):
            @property
            def _llm_type(self):
                return "p0-fixed-local-script"
            def bind_tools(self, tools, **kwargs):
                return self
            def _generate(self, messages, **kwargs):
                raise RuntimeError("禁止同步模型出口")
            async def _agenerate(self, messages, **kwargs):
                runtime.calls["model"] += 1
                question = next((str(m.content) for m in reversed(messages) if m.type == "human"), "")
                if "__hold__" in question:
                    await runtime.hold_model.wait()
                await asyncio.sleep(runtime.model_delay)
                if isinstance(messages[-1], ToolMessage):
                    if runtime.gate_after_grade and messages[-1].name == "grade_plugin":
                        runtime.publish_marker("before_terminal")
                        await runtime.finish_gate.wait()
                    answer = "合成评测已完成。" if messages[-1].name == "grade_plugin" else "合成 DNS 标签按长度编码[1]。"
                    msg = AIMessage(content=answer, usage_metadata={"input_tokens": 32, "output_tokens": 12, "total_tokens": 44})
                else:
                    tool = "grade_plugin" if "__grade__" in question else "kb_search"
                    args = {"protocol": "dns", "plugin_source": runtime.source} if tool == "grade_plugin" else {"query": "合成DNS标签"}
                    msg = AIMessage(content="", tool_calls=[{"name": tool, "args": args, "id": "p0-call-1", "type": "tool_call"}],
                                    usage_metadata={"input_tokens": 32, "output_tokens": 12, "total_tokens": 44})
                return ChatResult(generations=[ChatGeneration(message=msg)])

        class Retriever:
            async def search(self, query, limit=6, trace=None, conversation_context=None):
                runtime.calls["retrieval"] += 1
                await asyncio.sleep(runtime.retrieval_delay)
                return [RetrievedChunk(id="p0-perf-kb-0001", source_path="synthetic/dns.md", title="合成DNS", section="标签",
                                       content="合成DNS标签按长度编码。", metadata={}, score=.9)]

        class SummaryModel:
            async def generate(self, messages, *, usage=None, stage=""):
                runtime.calls["summary_model"] += 1
                await asyncio.sleep(runtime.model_delay)
                return "合成摘要"

        # 仅替换外部 I/O 工厂；AgentOrchestrator、图、受理和持久化保持冻结实现。
        agent_service.build_agent_chat_model = lambda settings: Model()
        from app.services import llama_index_service
        llama_index_service.LlamaIndexRetrievalAdapter = Retriever
        # 检索边界为合成替身，无需创建真实索引/embedding客户端；其余startup/维护实际运行。
        llama_index_service.warm_llama_index_runtime = lambda: None
        conversation_memory.build_chat_model = lambda settings: SummaryModel()
        async def fake_mcp(_self, tool_name, arguments):
            return await runtime.mcp(tool_name, arguments)
        HarnessMcpClient.call_tool = fake_mcp

    async def mcp(self, tool_name, arguments):
        self.calls[f"mcp:{tool_name}"] += 1
        job = arguments.get("job_id", "formal-without-id")
        self.mcp_events.append({"sequence": len(self.mcp_events) + 1, "tool": tool_name, "job_id": job})
        if tool_name == "grade_plugin":
            self.job_calls[job] += 1
            if self.hold_after_mcp_submit:
                self.publish_marker("mcp-submitted-before-response", job_id=job, grade_submit_calls=self.job_calls[job])
                await asyncio.Event().wait()
        await asyncio.sleep(self.mcp_delay)
        if tool_name == "get_task_status":
            answer = {"ok": True, "job_id": job, "status": self.status_mode}
            if self.status_mode == "finished":
                answer["result"] = self.grade_payload()
            return answer
        if tool_name == "grade_plugin":
            return self.grade_payload()
        if tool_name == "list_protocols":
            return {"ok": True, "protocols": [{"name": "dns", "cases": 2, "categories": {"basic": 2}}]}
        if tool_name == "get_reference_answer":
            return {"ok": False, "error": "no_reference_in_synthetic"}
        return {"ok": False, "error": "unsupported_synthetic_tool"}

    @staticmethod
    def grade_payload():
        return {"ok": True, "protocol": "dns", "score": 100, "passed": 2, "cases_total": 2,
                "failed": 0, "crashed": 0, "timedout": 0, "cheat": 0,
                "results": [{"name": "synthetic_1", "result": "PASS"}, {"name": "synthetic_2", "result": "PASS"}]}

    def snapshot(self):
        from app.db import get_conn
        with get_conn() as conn:
            tables = {name: conn.execute(f"SELECT count(*) AS n FROM {name}").fetchone()["n"] for name in
                      ["users", "api_tokens", "assignments", "assistant_sessions", "assistant_messages", "rag_requests", "rag_task_inputs", "rag_task_jobs", "grading_submissions", "upstream_usage_records"]}
            states = {r["status"]: r["n"] for r in conn.execute("SELECT status,count(*) AS n FROM rag_requests GROUP BY status")}
            usage = {r["kind"]: r["n"] for r in conn.execute("SELECT kind,sum(chat_count) AS n FROM usage_counters GROUP BY kind")}
            duplicates = conn.execute("SELECT count(*) AS n FROM (SELECT task_job_id FROM grading_submissions WHERE task_job_id IS NOT NULL GROUP BY task_job_id HAVING count(*)>1) s").fetchone()["n"]
        return {"tables": tables, "states": states, "usage": usage, "duplicate_task_job_grades": duplicates,
                "boundary_calls": dict(self.calls), "blocked_network_attempts": self.network_denials}

    async def reset_and_seed(self):
        import httpx
        from app.db import get_conn
        from app.services.user_service import UserService
        from app.services.assignment_service import AssignmentService
        with get_conn() as conn:
            tables = [r["tablename"] for r in conn.execute("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename!='alembic_version'")]
            conn.execute("TRUNCATE " + ",".join('"' + t.replace('"', '""') + '"' for t in tables) + " CASCADE")
        users = UserService()
        assert len((await users.create_users([self.leader], roles=["leader"], daily_quota=100000))["created"]) == 1
        assert len((await users.create_users(self.students, roles=["viewer"], daily_quota=100000, created_by=self.leader))["created"]) == 60
        assignment = await AssignmentService().create_assignment(actor_id=self.leader, actor_roles=["leader"],
            title="P0合成性能作业", description="运行时合成", deadline=datetime.now(timezone.utc) + timedelta(days=1), items=[{"protocol": "dns", "points": 100}])
        self.assignment_id = assignment["id"]
        for i, user in enumerate(self.students):
            client = httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app, client=(f"198.18.0.{i+1}", 30000+i)), base_url="http://p0.invalid", timeout=60)
            token, _ = await users.issue_token(user)
            client.headers["Authorization"] = "Bearer " + token
            self.clients.append(client)
            self.tokens.append(token)

    async def attach_clients(self):
        import httpx
        from app.services.user_service import UserService
        for i, user in enumerate(self.students):
            client = httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app, client=(f"198.18.0.{i+1}", 30000+i)), base_url="http://p0.invalid", timeout=60)
            token, _ = await UserService().issue_token(user)
            client.headers["Authorization"] = "Bearer " + token
            self.clients.append(client)

    async def start(self):
        await self.app.router.startup()
        self.lifecycle_started = True

    def publish_marker(self, stage, **extra):
        if self.marker is not None:
            write_json(self.marker, {"stage": stage, **extra})

    async def close(self):
        from app.db import close_database_provider
        if self.lifecycle_started:
            await self.app.router.shutdown()
        for client in self.clients:
            await client.aclose()
        close_database_provider()


class Samples:
    def __init__(self):
        self.groups = defaultdict(list)
        self.errors = defaultdict(Counter)
    def record(self, group, elapsed, status, okay, expected=False):
        self.groups[group].append(elapsed * 1000)
        self.errors[group]["requests"] += 1
        self.errors[group][f"http_{status}"] += 1
        if not okay:
            self.errors[group]["expected_backpressure" if expected else "unexpected_errors"] += 1
    def summary(self):
        return {name: {**quantiles(values), **dict(self.errors[name]),
                       "unexpected_error_rate": self.errors[name]["unexpected_errors"] / len(values)}
                for name, values in self.groups.items()}


class Resources:
    def __init__(self, runtime):
        self.runtime, self.samples = runtime, []
        self.pg_pid = int((ROOT / ".migration-artifacts/p0/pgdata/postmaster.pid").read_text().splitlines()[0])
        self.previous = None
    @staticmethod
    def cpu_seconds(value):
        day, _, value = value.rpartition("-") if "-" in value else ("0", "", value)
        parts = list(map(float, value.split(":")))
        seconds = sum(part * 60**i for i, part in enumerate(reversed(parts)))
        return int(day) * 86400 + seconds
    def sample(self, phase):
        from app.services.rag_product_service import admission
        from app.db import get_conn
        now = time.monotonic()
        children = subprocess.run(["pgrep", "-P", str(self.pg_pid)], capture_output=True, text=True).stdout.split()
        pids = [str(os.getpid()), str(self.pg_pid), *children]
        output = subprocess.run(["ps", "-o", "pid=,rss=,time=", "-p", ",".join(pids)], capture_output=True, text=True, check=True).stdout
        rows = {int(p): (int(rss), self.cpu_seconds(cpu)) for p, rss, cpu in (line.split() for line in output.splitlines())}
        own_rss, own_cpu = rows[os.getpid()]
        pg_rss = sum(v[0] for p, v in rows.items() if p != os.getpid())
        pg_cpu = sum(v[1] for p, v in rows.items() if p != os.getpid())
        with get_conn() as conn:
            activities = {r["datname"]: r["n"] for r in conn.execute("SELECT datname,count(*) AS n FROM pg_stat_activity WHERE backend_type='client backend' AND state='active' AND pid!=pg_backend_pid() GROUP BY datname")}
        sample = {"wall_utc": datetime.now(timezone.utc).isoformat(), "monotonic_seconds": now, "phase": phase, "app_rss_kib": own_rss, "pg_rss_sum_kib": pg_rss,
                  "pg_active_sessions_by_database_excluding_sampler": activities,
                  "active": admission.active, "waiting": admission.waiting, "app_cpu_pct_one_core": None, "pg_cpu_pct_one_core": None}
        if self.previous is not None:
            previous_time, previous_own, previous_pg = self.previous
            sample["app_cpu_pct_one_core"] = max(0, (own_cpu - previous_own) / (now - previous_time) * 100)
            sample["pg_cpu_pct_one_core"] = max(0, (pg_cpu - previous_pg) / (now - previous_time) * 100)
        self.previous = now, own_cpu, pg_cpu
        self.samples.append(sample)
    def summary(self):
        return {"sample_interval_seconds": 1, "samples": len(self.samples),
                "cpu_basis": "ps累计CPU的相邻采样差，100%=一核；PG进程退出可能造成低估；PG与其他oracle共享实例",
                "rss_basis": "app单进程RSS；PG主进程及直接子进程RSS求和，含共享页重复，非独占内存",
                "app_cpu_pct_peak": max((s["app_cpu_pct_one_core"] or 0 for s in self.samples), default=0),
                "pg_cpu_pct_peak": max((s["pg_cpu_pct_one_core"] or 0 for s in self.samples), default=0),
                "app_rss_mib_peak": max((s["app_rss_kib"] / 1024 for s in self.samples), default=0),
                "pg_rss_sum_mib_peak": max((s["pg_rss_sum_kib"] / 1024 for s in self.samples), default=0),
                "active_peak": max((s["active"] for s in self.samples), default=0), "waiting_peak": max((s["waiting"] for s in self.samples), default=0)}


async def measure_phase(runtime, duration, phase, round_number, label):
    deadline = time.monotonic() + duration
    samples, resources = Samples(), Resources(runtime)
    stop = asyncio.Event()
    question_slots = asyncio.Semaphore(10)
    next_account = 0

    async def request(group, client, method, url, *, valid=None, **kwargs):
        started = time.monotonic()
        try:
            response = await client.request(method, url, **kwargs)
            okay = response.status_code == 200 and (valid(response) if valid else True)
            samples.record(group, time.monotonic() - started, response.status_code, okay)
            return response
        except Exception as exc:
            samples.record(group, time.monotonic() - started, type(exc).__name__, False)
            return None

    async def reading(index):
        # 60账号在每轮固定登录一次；每人5秒一次看作业，错开启动。
        await asyncio.sleep(index * .025)
        client, user = runtime.clients[index], runtime.students[index]
        await request("login", client, "POST", "/api/auth/login", json={"username": user, "password": user}, valid=lambda r: r.json().get("subject") == user)
        while time.monotonic() < deadline:
            await request("assignment_read", client, "GET", "/api/assignments", valid=lambda r: any(a["id"] == runtime.assignment_id for a in r.json().get("assignments", [])))
            await asyncio.sleep(min(5, max(0, deadline - time.monotonic())))

    async def asking(worker):
        nonlocal next_account
        while time.monotonic() < deadline:
            index = next_account % 60
            next_account += 1
            async with question_slots:
                await request("chat", runtime.clients[index], "POST", "/api/chat", json={"message": "__kb__ 合成DNS标签如何编码？", "request_id": str(uuid.uuid4())}, valid=lambda r: r.json().get("answer_status") == "answered")

    async def formal():
        index = 0
        while time.monotonic() < deadline:
            client = runtime.clients[index % 60]
            index += 1
            response = await request("formal_submit", client, "POST", f"/api/assignments/{runtime.assignment_id}/submit", json={"protocol": "dns", "code": runtime.source}, valid=lambda r: bool(r.json().get("submission_id")))
            if response is not None and response.status_code == 200:
                sid = response.json()["submission_id"]
                async with question_slots:
                    started = time.monotonic()
                    explained = await request("explain_admission", client, "POST", f"/api/assignments/{runtime.assignment_id}/submissions/{sid}/explain")
                    if explained is not None and explained.status_code == 200:
                        task_id = explained.json()["task_id"]
                        from app.services import task_service
                        managed = task_service.TASKS.get(task_id)
                        if managed is not None:
                            await asyncio.wait_for(managed.done.wait(), 30)
                        final = await client.get(f"/api/tasks/{task_id}")
                        okay = final.status_code == 200 and final.json().get("status") == "answered"
                        samples.record("explain_terminal", time.monotonic() - started, final.status_code, okay)
            await asyncio.sleep(min(10, max(0, deadline - time.monotonic())))

    async def sample_resources():
        while not stop.is_set():
            resources.sample(phase)
            try:
                await asyncio.wait_for(stop.wait(), 1)
            except asyncio.TimeoutError:
                pass

    monitor = asyncio.create_task(sample_resources())
    started = time.monotonic()
    try:
        await asyncio.gather(*(reading(i) for i in range(60)), *(asking(i) for i in range(10)), formal())
    finally:
        stop.set()
        await monitor
    elapsed = time.monotonic() - started
    answer = {"phase": phase, "round": round_number, "target_seconds": duration, "elapsed_seconds_including_drain": elapsed,
              "groups": samples.summary(), "resources": resources.summary(), "after": runtime.snapshot()}
    for group in answer["groups"].values():
        group["offered_completed_rps_over_target_window"] = group["n"] / duration
    write_json(ARTIFACTS / f"{label}-round-{round_number}-{phase}-latencies.json", dict(samples.groups))
    write_json(EVIDENCE / f"{label}-round-{round_number}-{phase}-resources.json", resources.samples)
    write_json(EVIDENCE / f"{label}-round-{round_number}-{phase}.json", answer)
    print(json.dumps({"round": round_number, "phase": phase, "elapsed": round(elapsed, 2), "groups": answer["groups"], "resources": answer["resources"]}), flush=True)
    return answer


def environment(runtime, args):
    import psycopg
    packages = {}
    for name in ["fastapi", "starlette", "httpx", "psycopg", "psycopg-pool", "langgraph", "langchain-core", "bcrypt"]:
        try:
            packages[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            packages[name] = "unavailable"
    with psycopg.connect(runtime.settings.database_url) as conn:
        pg = dict(conn.execute("SELECT name,setting FROM pg_settings WHERE name IN ('server_version','max_connections','shared_buffers','work_mem','max_worker_processes')").fetchall())
        vector = conn.execute("SELECT extversion FROM pg_extension WHERE extname='vector'").fetchone()[0]
        schema = conn.execute("SELECT version_num FROM alembic_version").fetchone()[0]
    cpu_model = subprocess.run(["sysctl", "-n", "machdep.cpu.brand_string"], capture_output=True, text=True).stdout.strip()
    physical_memory = subprocess.run(["sysctl", "-n", "hw.memsize"], capture_output=True, text=True).stdout.strip()
    return {"baseline": BASELINE, "contract": "p0-v1", "python": sys.version, "platform": platform.platform(),
            "cpu_model": cpu_model, "physical_memory_bytes": int(physical_memory) if physical_memory else None,
            "runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            "machine": platform.machine(), "logical_cpu_count": os.cpu_count(), "packages": packages,
            "postgres_identity": runtime.identity, "postgres_settings": pg, "vector_version": vector, "schema": schema,
            "parameters": vars(args), "boundary_delays_seconds": {"each_model_call": runtime.model_delay, "retrieval": runtime.retrieval_delay, "mcp": runtime.mcp_delay},
            "actual_call_chain": "httpx ASGI→FastAPI中间件/认证→chat/assignments路由→RagProductService/TaskService→真实AgentOrchestrator/LangGraph/tools→合成外部I/O→真实PG终态/消息/配额/成绩",
            "measurement_boundary": "同进程ASGI；不含TCP/TLS/nginx/真实模型/真实embedding/真实检索/harness执行；真实startup/shutdown/300秒维护/10秒属主心跳运行，仅索引I/O预热由合成检索边界跳过；GC/线程池/PG池为真实实现",
            "settings": {key: getattr(runtime.settings, key) for key in ["bcrypt_rounds", "rate_limit_chat_per_minute", "database_pool_max_size", "rag_max_active_requests", "rag_max_waiting_requests", "rag_queue_timeout_sec", "rag_task_max_seconds", "rag_task_input_ttl_hours", "rag_online_judge_enabled", "rag_request_timeout_sec", "grading_daily_quota"]},
            "account_daily_quota": 100000, "synthetic_accounts": 60, "upstream_quality": "live_not_authorized"}


async def benchmark(args):
    runtime = Runtime(args.model_delay, args.retrieval_delay, args.mcp_delay)
    try:
        await runtime.reset_and_seed()
        await runtime.start()
        write_json(EVIDENCE / f"{args.label}-environment.json", environment(runtime, args))
        results = []
        for round_number in range(1, args.rounds + 1):
            if args.warmup:
                results.append(await measure_phase(runtime, args.warmup, "warmup", round_number, args.label))
            results.append(await measure_phase(runtime, args.measurement, "measurement", round_number, args.label))
            write_json(EVIDENCE / f"{args.label}-summary.json", {"status": "采集中" if round_number < args.rounds else "完成采集、阈值待用户确认", "rounds": results, "real_quality": "未验证"})
        final = runtime.snapshot()
        expected_tasks = sum(r["groups"].get("chat", {}).get("http_200", 0) + r["groups"].get("explain_admission", {}).get("http_200", 0) for r in results)
        expected_submissions = sum(r["groups"].get("formal_submit", {}).get("http_200", 0) for r in results)
        checks = {"no_unexpected_http_or_business_errors": all(g.get("unexpected_errors", 0) == 0 for r in results for g in r["groups"].values()),
                  "all_accepted_tasks_answered": final["states"] == {"answered": expected_tasks},
                  "one_chat_charge_per_accepted_task": final["usage"].get("chat", 0) == expected_tasks,
                  "terminal_inputs_deleted": final["tables"]["rag_task_inputs"] == 0,
                  "formal_submissions_equal_successes": final["tables"]["grading_submissions"] == expected_submissions,
                  "one_mcp_submit_per_formal_success": final["boundary_calls"].get("mcp:grade_plugin", 0) == expected_submissions,
                  "no_grade_from_explanation": final["tables"]["rag_task_jobs"] == 0,
                  "no_external_network_attempt": final["blocked_network_attempts"] == 0}
        write_json(EVIDENCE / f"{args.label}-summary.json", {"status": "完成采集、阈值待用户确认" if all(checks.values()) else "存在异常，不宣称容量通过", "rounds": results,
                   "correctness_assertions": checks, "final": final, "real_quality": "未验证"})
        if not all(checks.values()):
            raise RuntimeError("性能运行的业务副作用断言失败，保留全部轮次")
    finally:
        await runtime.close()


async def queue(args):
    runtime = Runtime(.01, .01, .01)
    from app.services.rag_product_service import admission
    try:
        await runtime.reset_and_seed()
        runtime.hold_model.clear()
        before = runtime.snapshot()
        async def submit(i):
            start = time.monotonic()
            response = await runtime.clients[i].post("/api/chat", json={"message": "__hold__ __kb__", "request_id": str(uuid.uuid4())})
            return {"http": response.status_code, "ms": (time.monotonic() - start) * 1000, "detail": response.json().get("detail")}
        calls = [asyncio.create_task(submit(i)) for i in range(41)]
        peak_active = peak_waiting = 0
        started = time.monotonic()
        while sum(c.done() for c in calls) < 21 and time.monotonic() - started < 18:
            peak_active, peak_waiting = max(peak_active, admission.active), max(peak_waiting, admission.waiting)
            await asyncio.sleep(.01)
        rejected = [c.result() for c in calls if c.done()]
        runtime.hold_model.set()
        results = await asyncio.gather(*calls)
        after = runtime.snapshot()
        checks = {"20_active": peak_active == 20, "20_waiting": peak_waiting == 20,
                  "one_immediate_queue_full": sum(r["detail"] == "服务繁忙，请稍后重试" for r in results) == 1,
                  "20_queue_timeouts": sum(r["detail"] == "等待超时，请稍后重试" for r in results) == 20,
                  "20_success": sum(r["http"] == 200 for r in results) == 20,
                  "no_leaked_inputs": after["tables"]["rag_task_inputs"] == 0,
                  "exactly_20_chat_counts": after["usage"].get("chat") == 20,
                  "all_slots_released": admission.active == 0 and admission.waiting == 0}
        result = {"case_id": "p0-performance-queue-20-20", "baseline": BASELINE, "status": "通过" if all(checks.values()) else "失败", "checks": checks,
                  "peak_active": peak_active, "peak_waiting": peak_waiting, "denominator": len(results), "expected_backpressure": 21,
                  "rejections_before_release": rejected, "requests": results, "before": before, "after": after,
                  "actual_queue_timeout_seconds": runtime.settings.rag_queue_timeout_sec}
        write_json(EVIDENCE / "queue.json", result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
        if not all(checks.values()):
            raise RuntimeError("队列断言失败")
    finally:
        await runtime.close()


async def recovery_worker(args):
    runtime = Runtime(.01, .01, .01)
    from app.core.auth import Principal
    from app.schemas.chat import ChatRequest
    from app.services.rag_product_service import RagProductService
    from app.services import task_service
    runtime.marker = ARTIFACTS / (args.scenario + "-worker-marker.json")
    try:
        product = RagProductService(Principal(subject=runtime.students[0], roles=["viewer"], daily_quota=100000),
                                   ChatRequest(message="__grade__ 合成评测", request_id=uuid.uuid4()))
        await product.prepare(quota=100000)
        identity = {"task_id": product.task_id, "request_id": product.request_id, "session_id": product.session_id, "user_id": runtime.students[0]}
        write_json(ARTIFACTS / (args.scenario + "-identity.json"), identity)
        if args.scenario == "accepted":
            runtime.publish_marker("accepted")
        else:
            if args.scenario == "submitted-unknown":
                runtime.hold_after_mcp_submit = True
            else:
                runtime.gate_after_grade = True
                runtime.finish_gate.clear()
            await task_service.start_task(product)
        await asyncio.sleep(3600)
    finally:
        await runtime.close()


async def recovery(args):
    runtime = Runtime(.01, .01, .01)
    from app.core.auth import Principal
    from app.schemas.chat import ChatRequest
    from app.services.rag_product_service import RagProductService, admission
    from app.services.rag_issue_service import RagIssueService
    from app.services import task_service
    from app.db import get_conn
    issues, results = RagIssueService(), []
    try:
        await runtime.reset_and_seed()
        for scenario in ["accepted", "before-terminal", "submitted-unknown"]:
            marker = ARTIFACTS / (scenario + "-worker-marker.json")
            marker.unlink(missing_ok=True)
            # 子进程仍只连接相同专用库，安全入口重新核验；原始日志只进运行态目录。
            with (ARTIFACTS / (scenario + "-worker.log")).open("w") as log:
                child = subprocess.Popen([sys.executable, str(Path(__file__)), "recovery-worker", "--scenario", scenario], cwd=ROOT, stdout=log, stderr=log)
                try:
                    deadline = time.monotonic() + 45
                    while not marker.exists() and time.monotonic() < deadline:
                        if child.poll() is not None:
                            raise RuntimeError(f"故障worker过早退出，查看{scenario}-worker.log")
                        await asyncio.sleep(.05)
                    if not marker.exists():
                        raise RuntimeError("故障点未到达")
                    identity = json.loads((ARTIFACTS / (scenario + "-identity.json")).read_text())
                    fault_marker = json.loads(marker.read_text())
                    child.kill()
                    child.wait(timeout=10)
                finally:
                    if child.poll() is None:
                        child.kill()
                        child.wait()
            row = await asyncio.to_thread(issues.get_task, identity["task_id"], identity["user_id"])
            before = runtime.snapshot()
            event_start = len(runtime.mcp_events)
            # 只推进该合成行的孤儿年龄；报告RTO明确不含grace/维护扫描周期。
            stale = (datetime.now(timezone.utc) - timedelta(seconds=120)).isoformat()
            with get_conn() as conn:
                conn.execute("UPDATE rag_requests SET updated_at=%s WHERE id=%s", (stale, identity["task_id"]))
            started = time.monotonic()
            row = await asyncio.to_thread(issues.get_task, identity["task_id"], identity["user_id"])
            managed = await task_service.ensure_managed(row)
            if managed is None:
                raise RuntimeError("孤儿任务未恢复")
            await asyncio.wait_for(managed.done.wait(), 20)
            elapsed = time.monotonic() - started
            after = runtime.snapshot()
            fresh = await asyncio.to_thread(issues.get_task, identity["task_id"], identity["user_id"])
            first_calls = dict(runtime.calls)
            principal = Principal(subject=identity["user_id"], roles=["viewer"], daily_quota=100000)
            replay = RagProductService(principal, ChatRequest(message="__grade__ 合成评测", session_id=identity["session_id"], request_id=uuid.UUID(identity["request_id"])))
            await replay.probe_replay()
            after_replay = runtime.snapshot()
            checks = {"terminal_answered": fresh["status"] == "answered", "inputs_deleted": issues.load_task_inputs(identity["task_id"]) is None,
                      "chat_quota_not_recounted": before["usage"]["chat"] == after["usage"]["chat"],
                      "replay_without_model_or_mcp": dict(runtime.calls) == first_calls,
                      "replay_without_quota": after["usage"] == after_replay["usage"],
                      "no_duplicate_grade": after["duplicate_task_job_grades"] == 0,
                      "replayed_same_task": replay.replayed is not None and replay.replayed.task_id == identity["task_id"]}
            if scenario == "before-terminal":
                checks["finished_job_not_resubmitted"] = after["boundary_calls"].get("mcp:grade_plugin", 0) == before["boundary_calls"].get("mcp:grade_plugin", 0)
                checks["grade_count_unchanged"] = before["tables"]["grading_submissions"] == after["tables"]["grading_submissions"]
            mcp_events = runtime.mcp_events[event_start:]
            if scenario == "submitted-unknown":
                checks["unknown_does_not_blindly_resubmit"] = not any(e["tool"] == "grade_plugin" for e in mcp_events)
                checks["resubmission_uses_same_job"] = all(e["job_id"] == fault_marker["job_id"] for e in mcp_events)
            results.append({"case_id": "kill-restart-" + scenario, "status": "通过" if all(checks.values()) else "失败", "worker_exit": child.returncode,
                            "checks": checks, "recovery_execution_seconds": elapsed, "rto_boundary": "SIGKILL后重建进程环境；合成拨老updated_at绕过30秒grace；手动ensure_managed，未含300秒扫描等待",
                            "fault_marker": fault_marker, "recovery_mcp_events": mcp_events,
                            "actual_submit_calls_across_processes": fault_marker.get("grade_submit_calls", 0) + sum(e["tool"] == "grade_plugin" for e in mcp_events) if scenario == "submitted-unknown" else None,
                            "before": before, "after": after, "rpo": "已提交受理/已完成成绩和输入身份保持；未提交模型中间输出不保证"})
        # 断开订阅生成器，真实后台task必须继续并可终态重放。
        principal = Principal(subject=runtime.students[1], roles=["viewer"], daily_quota=100000)
        product = RagProductService(principal, ChatRequest(message="__kb__ 断开订阅", request_id=uuid.uuid4()))
        await product.prepare(quota=100000)
        managed = await task_service.start_task(product)
        stream = task_service.subscribe(managed)
        first_frame = await anext(stream)
        await stream.aclose()
        running_after_close = not managed.run_task.done()
        await asyncio.wait_for(managed.done.wait(), 20)
        frames = [item async for item in task_service.task_events(managed.task_id, principal.subject)]
        checks = {"start_received": first_frame["event"] == "start", "disconnect_does_not_cancel": running_after_close,
                  "terminal_replay": frames[-1]["event"] == "done" and frames[-1]["data"]["answer_status"] == "answered",
                  "inputs_deleted": issues.load_task_inputs(managed.task_id) is None}
        results.append({"case_id": "subscriber-disconnect", "status": "通过" if all(checks.values()) else "失败", "checks": checks,
                        "boundary": "真实task_service订阅生成器关闭；未验证TCP断线/浏览器/nginx"})
        # 真实600秒外墙：模型已返回后，在终态提交边界挂起工作线程，再推进事件循环时钟。
        # 内层120秒生成墙此时已结束，能独立检验外层硬时限，不等待真实10分钟。
        principal = Principal(subject=runtime.students[3], roles=["viewer"], daily_quota=100000)
        product = RagProductService(principal, ChatRequest(message="__kb__ 硬时限", request_id=uuid.uuid4()))
        await product.prepare(quota=100000)
        finish_entered, finish_release, finish_exited = threading.Event(), threading.Event(), threading.Event()
        original_finish = product.issues.finish
        late_guard = []
        def blocked_finish(*arguments):
            finish_entered.set()
            finish_release.wait(timeout=20)
            try:
                return original_finish(*arguments)
            except RuntimeError:
                late_guard.append(True)
                raise
            finally:
                finish_exited.set()
        product.issues.finish = blocked_finish
        managed = await task_service.start_task(product)
        entered_deadline = time.monotonic() + 10
        while not finish_entered.is_set() and time.monotonic() < entered_deadline:
            await asyncio.sleep(.01)
        if not finish_entered.is_set():
            raise RuntimeError("硬期限故障点未到达")
        loop = asyncio.get_running_loop()
        original_time, offset = loop.time, [0.0]
        loop.time = lambda: original_time() + offset[0]
        try:
            offset[0] = runtime.settings.rag_task_max_seconds - 1
            await asyncio.sleep(.01)
            alive_before_boundary = not managed.done.is_set()
            offset[0] = runtime.settings.rag_task_max_seconds + .5
            await asyncio.wait_for(managed.done.wait(), 10)
        finally:
            loop.time = original_time
            finish_release.set()
        exit_deadline = time.monotonic() + 10
        while not finish_exited.is_set() and time.monotonic() < exit_deadline:
            await asyncio.sleep(.01)
        terminal = await asyncio.to_thread(issues.get_task, product.task_id, principal.subject)
        checks = {"actual_configuration_600": runtime.settings.rag_task_max_seconds == 600,
                  "alive_at_virtual_599": alive_before_boundary,
                  "timeout_terminal_at_virtual_600_5": terminal["status"] == "system_error" and terminal["reason"] == "timeout",
                  "inputs_deleted": issues.load_task_inputs(product.task_id) is None,
                  "late_finish_blocked_by_frozen_guard": bool(late_guard), "slot_released": admission.active == 0}
        results.append({"case_id": "actual-600-second-hard-deadline-virtual-clock", "status": "通过" if all(checks.values()) else "失败", "checks": checks,
                        "boundary": "冻结TaskService外层asyncio.wait_for，真实PG终态；仅推进loop.time，不改600配置；在真实finish前人为挂起线程，非真实600秒墙钟容量测试"})
        # 账本submitted且上游unknown/running：只注入持久化前提，不改真实恢复分支。
        for mode in ["unknown", "running", "finished"]:
            runtime.status_mode = mode
            principal = Principal(subject=runtime.students[2], roles=["viewer"], daily_quota=100000)
            product = RagProductService(principal, ChatRequest(message="__grade__ 状态恢复", request_id=uuid.uuid4()))
            await product.prepare(quota=100000)
            code_hash = hashlib.sha256(runtime.source.encode()).hexdigest()
            job_id = f"{product.task_id}:dns:{code_hash[:16]}"
            await asyncio.to_thread(issues.reserve_grading_job, product.task_id, job_id, "dns", code_hash, principal.subject, 20)
            before_calls = runtime.calls.copy()
            event_start = len(runtime.mcp_events)
            before_usage = runtime.snapshot()["usage"]
            stale = (datetime.now(timezone.utc) - timedelta(seconds=120)).isoformat()
            with get_conn() as conn:
                conn.execute("UPDATE rag_requests SET updated_at=%s WHERE id=%s", (stale, product.task_id))
            row = await asyncio.to_thread(issues.get_task, product.task_id, principal.subject)
            managed = await task_service.ensure_managed(row)
            await asyncio.wait_for(managed.done.wait(), 20)
            delta = runtime.calls - before_calls
            mcp_events = runtime.mcp_events[event_start:]
            checks = {"queried_status_first": bool(mcp_events) and mcp_events[0]["tool"] == "get_task_status", "same_job_identity": runtime.job_calls[job_id] == delta["mcp:grade_plugin"],
                      "no_repeat_quota": before_usage == runtime.snapshot()["usage"], "zero_duplicate_grade": runtime.snapshot()["duplicate_task_job_grades"] == 0}
            expected_no_submit = mode in {"unknown", "finished"}
            if expected_no_submit:
                checks["no_blind_resubmit"] = delta["mcp:grade_plugin"] == 0
            results.append({"case_id": "submitted-status-" + mode, "status": "通过" if all(checks.values()) else "失败", "checks": checks,
                            "boundary_call_delta": dict(delta), "ordered_mcp_events": mcp_events, "same_job_grade_submit_count": runtime.job_calls[job_id],
                            "finding": "unknown经状态查询后再次grade_plugin，未确认旧执行归属/终止；违反拟冻结安全边界" if mode == "unknown" else
                                       "running以同job重新grade_plugin；仅证明调用身份相同，不证明真实harness幂等/加入既有执行" if mode == "running" else "finished复用状态结果，不重新grade_plugin"})
        result = {"baseline": BASELINE, "runner_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  "boundary_delays_seconds": {"each_model_call": runtime.model_delay, "retrieval": runtime.retrieval_delay, "mcp": runtime.mcp_delay},
                  "results": results, "status": "存在冻结缺陷" if any(r["status"] == "失败" for r in results) else "通过", "after": runtime.snapshot(),
                  "limits": ["无真实harness/模型/embedding质量证明", "不含生产恢复或多副本fencing", "24小时TTL由功能oracle负责，本文件不覆盖", "unknown不盲重试契约与当前源码冲突需用户决策"]}
        write_json(EVIDENCE / "recovery.json", result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
    finally:
        await runtime.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=["benchmark", "queue", "recovery", "recovery-worker"])
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--warmup", type=float, default=120)
    parser.add_argument("--measurement", type=float, default=600)
    parser.add_argument("--model-delay", type=float, default=1)
    parser.add_argument("--retrieval-delay", type=float, default=.05)
    parser.add_argument("--mcp-delay", type=float, default=.2)
    parser.add_argument("--label", default="baseline")
    parser.add_argument("--scenario", default="accepted")
    args = parser.parse_args()
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    EVIDENCE.mkdir(parents=True, exist_ok=True)
    asyncio.run({"benchmark": benchmark, "queue": queue, "recovery": recovery, "recovery-worker": recovery_worker}[args.mode](args))


if __name__ == "__main__":
    main()
