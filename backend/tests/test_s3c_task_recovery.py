"""S3-c 运行时装配回归：任务注册表 / 断线续跑 / 显式取消 / 重启恢复 / 作业账本接续。

替身沿用 test_agent_service.py：ScriptedChatModel / FakeMcp / make_orchestrator；
数据库走 conftest 的 sqlite_db 临时库，无网络、无真实 harness。

每个用例对应规格里的一条验收：
1. 同 request_id 重提交 → in_progress 接续，_candidate 全程只执行一次；
2. SSE 订阅者断开 → run 续跑收口，终态行 + 消息落库 + inputs 清理，
   之后 task_events 能重建/回放 start、sources、delta、done；
3. 显式取消 → cancelled + 占位消息 + inputs 清理 + 占槽释放，重复取消幂等，
   终态行取消返回终态；
4. 恢复 + harness finished → 不打 grade_plugin，成绩落库恰好一次（claim 闸）；
5. 恢复 + harness unknown → 以同一 job_id 重驱动 grade_plugin；
6. origin=model_args 的执行源首评回填 inputs，恢复上下文按寄存字节重投；
7. 越权访问 → 404；
8. rag_task_max_seconds 超时 → system_error + inputs 清理；
9. 账号停用 → 该用户 pending 任务批量 cancelled。
"""
from __future__ import annotations

import asyncio
import hashlib
import json
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from uuid import uuid4

import pytest
from fastapi import HTTPException
from langchain_core.messages import AIMessage

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from test_agent_service import ScriptedChatModel, grade_call, make_orchestrator

from app.core.auth import Principal
from app.db import get_conn
from app.schemas.chat import ChatRequest
from app.services import task_service
from app.services.rag_issue_service import RagIssueService
from app.services.rag_product_service import RagProductService, Verdict, admission
from app.services.session_service import SessionService
from app.services.user_service import UserService

PASTED_SOURCE = "/* 粘贴的 dns 插件 */\n#include <stdint.h>\nint plugin_main(void){return 0;}\n"
MODEL_SOURCE = "/* 模型复述源码（恢复后仍要用这份字节） */\nint plugin_main(void){return -1;}\n"

HARNESS_OK = {
    "ok": True, "protocol": "dns", "score": 62, "cases_total": 8, "passed": 5,
    "failed": 3, "crashed": 0, "timedout": 0, "cheat": 0,
    "results": [
        {"name": "dns_basic_3", "category": "[POSITIVE]", "result": "FAIL",
         "failures": [{"desc": "qname", "expected": "example.com", "actual": "www.example.com"}]},
    ],
}


@pytest.fixture(autouse=True)
async def _clean_task_registry():
    """任务注册表/占槽是进程级全局状态：用例间清干净，残留协程一律取消收口。"""
    task_service.TASKS.clear()
    yield
    for managed in list(task_service.TASKS.values()):
        if managed.run_task is not None and not managed.run_task.done():
            managed.run_task.cancel()
    for managed in list(task_service.TASKS.values()):
        if managed.run_task is not None:
            await asyncio.gather(managed.run_task, return_exceptions=True)
    task_service.TASKS.clear()
    admission.active = 0
    admission.waiting = 0


class RoutingMcp:
    """按工具名路由的 MCP 替身：get_task_status 回 job 状态，其余回固定载荷。"""

    def __init__(self, grade_payload=None, job_status="unknown", job_result=None):
        self.grade_payload = dict(grade_payload or HARNESS_OK)
        self.job_status = job_status
        self.job_result = job_result
        self.calls = []

    async def __call__(self, tool_name, arguments):
        self.calls.append((tool_name, dict(arguments)))
        if tool_name == "get_task_status":
            payload = {"ok": True, "job_id": arguments.get("job_id"), "status": self.job_status}
            if self.job_result is not None:
                payload["result"] = self.job_result
            return payload
        return dict(self.grade_payload)


def _product(question="问题", user="student", request_id=None):
    return RagProductService(
        Principal(subject=user, roles=["viewer"], daily_quota=200),
        ChatRequest(message=question, request_id=request_id))


def _make_orphan(task_id):
    """把 updated_at 拨到 grace 窗口之前：orphaned_pending / 接续判定都认这一列。"""
    stale = (datetime.now(timezone.utc) - timedelta(seconds=120)).isoformat()
    with get_conn() as conn:
        conn.execute("UPDATE rag_requests SET updated_at=? WHERE id=?", (stale, task_id))


def _table_count(name, where="", params=()):
    with get_conn() as conn:
        return conn.execute(f"SELECT COUNT(*) FROM {name} {where}", params).fetchone()[0]


async def _fake_validate(question, answer, evidence, model, timeout, usage=None, request_id=""):
    return Verdict(answer or "正文", "answered", "supported")


def _patch_agent_path(monkeypatch, model, mcp):
    """产品路径的 agent 装配点换成进程内替身：orchestrator 真实、模型/MCP 脚本化。"""
    orchestrator = make_orchestrator(model, mcp)
    monkeypatch.setattr("app.agent.service.AgentOrchestrator", lambda: orchestrator)
    monkeypatch.setattr(
        "app.services.rag_product_service.validate_answer", _fake_validate)
    return orchestrator


# ---------------------------------------------------------------------------
# 1. 同 request_id 在 pending 时重提交 → in_progress 接续，只执行一次
# ---------------------------------------------------------------------------


async def test_resubmit_same_request_id_attaches_to_running_task(sqlite_db, monkeypatch):
    request = ChatRequest(message="问题", request_id=uuid4())
    issues = RagIssueService()
    calls = []
    gate = asyncio.Event()

    first = _product(request_id=request.request_id)
    await first.prepare()

    async def candidate(emit):
        calls.append(1)
        await emit({"event": "status", "data": {"stage": "validation"}})
        await gate.wait()
        return Verdict("可信正文", "answered", "supported"), [], "release"
    monkeypatch.setattr(first, "_candidate", candidate)
    managed = await task_service.start_task(first)
    await asyncio.sleep(0)   # 让执行协程进入 candidate（status 帧入回放日志）

    # 同 request_id 重提交 → probe 命中 in_progress，接续同一任务
    second = _product(request_id=request.request_id)
    await second.probe_replay()
    assert second.attached_row is not None
    assert second.attached_row["id"] == managed.task_id
    assert second.attached_row["status"] == "pending"

    events = []

    async def collect():
        async for event in task_service.task_events(managed.task_id, "student"):
            events.append(event)

    collector = asyncio.create_task(collect())
    await asyncio.sleep(0)
    gate.set()
    await asyncio.wait_for(collector, 5)

    names = [event["event"] for event in events]
    assert names[0] == "start" and names[-1] == "done"
    assert "status" in names          # 订阅前发出的中间帧经回放日志补齐
    assert events[-1]["data"]["answer_status"] == "answered"
    assert events[-1]["data"]["task_id"] == managed.task_id
    assert len(calls) == 1, "同一任务只执行一次 _candidate"
    assert issues.get_task(managed.task_id, "student")["status"] == "answered"


# ---------------------------------------------------------------------------
# 2. SSE 订阅者断开：run 续跑收口，终态可重建回放
# ---------------------------------------------------------------------------


async def test_disconnect_keeps_run_alive_and_terminal_replays(sqlite_db, monkeypatch):
    product = _product()
    await product.prepare()

    async def candidate(emit):
        await emit({"event": "status", "data": {"stage": "validation"}})
        await asyncio.sleep(0.05)
        return Verdict("断线后仍写完的正文", "answered", "supported"), [], "release"
    monkeypatch.setattr(product, "_candidate", candidate)
    managed = await task_service.start_task(product)

    stream = task_service.subscribe(managed)
    first_frame = await stream.__anext__()
    assert first_frame["event"] == "start"
    await stream.aclose()            # 订阅方断开：绝不是取消（B13 修复点）

    await asyncio.wait_for(managed.done.wait(), 5)   # run 自己收口
    assert managed.result is not None and managed.result.answer == "断线后仍写完的正文"

    row = RagIssueService().get_task(managed.task_id, "student")
    assert row["status"] == "answered" and row["message_id"]
    assert RagIssueService().load_task_inputs(managed.task_id) is None
    assert admission.active == 0     # 终态占槽已释放

    # 路径一：注册表条目还在 TTL 内 → 订阅回放（含执行期事件日志）
    replay = [event async for event in task_service.task_events(managed.task_id, "student")]
    names = [event["event"] for event in replay]
    assert names == ["start", "status", "sources", "delta", "done"]
    assert replay[-1]["data"]["answer_status"] == "answered"

    # 路径二：条目已被清出注册表（TTL 之后）→ 按终态行重建回放帧
    task_service.TASKS.pop(managed.task_id)
    rebuilt = [event async for event in task_service.task_events(managed.task_id, "student")]
    names = [event["event"] for event in rebuilt]
    assert names == ["start", "sources", "delta", "done"]
    assert rebuilt[-1]["data"]["answer_status"] == "answered"
    assert rebuilt[-1]["data"]["terminal"] is True
    assert rebuilt[2]["data"]["delta"] == "断线后仍写完的正文"


# ---------------------------------------------------------------------------
# 3. 显式取消：幂等、占位消息、inputs 清理、占槽释放
# ---------------------------------------------------------------------------


async def test_explicit_cancel_is_idempotent_and_releases(sqlite_db, monkeypatch):
    product = _product()
    await product.prepare()

    async def hanging(emit):
        await asyncio.sleep(30)
        return Verdict("正文", "answered", "supported"), [], "release"
    monkeypatch.setattr(product, "_candidate", hanging)
    managed = await task_service.start_task(product)
    await asyncio.sleep(0)
    assert admission.active == 1

    row = await task_service.cancel(managed.task_id, "student")
    assert row["status"] == "cancelled" and row["reason"] == "user_cancelled"
    assert row["message_id"], "取消必须回填占位消息 id"
    assert managed.run_task.cancelled() or managed.terminal
    assert RagIssueService().load_task_inputs(managed.task_id) is None
    assert admission.active == 0, "取消后占槽必须释放"

    with get_conn() as conn:
        message = conn.execute("SELECT content,metadata_json FROM assistant_messages WHERE id=?",
                               (row["message_id"],)).fetchone()
    assert message["content"] == "（请求已取消）"
    assert json.loads(message["metadata_json"])["stream_status"] == "cancelled"

    # 重复取消幂等：返回同一终态
    again = await task_service.cancel(managed.task_id, "student")
    assert again["status"] == "cancelled"

    # 终态行取消返回终态，不改写结果
    done = _product(request_id=uuid4())
    await done.prepare()

    async def quick(emit):
        return Verdict("正文", "answered", "supported"), [], "release"
    monkeypatch.setattr(done, "_candidate", quick)
    done_managed = await task_service.start_task(done)
    await asyncio.wait_for(done_managed.done.wait(), 5)
    terminal = await task_service.cancel(done_managed.task_id, "student")
    assert terminal["status"] == "answered" and terminal["message_id"]


# ---------------------------------------------------------------------------
# 4. 恢复 + harness finished：复用结果，不重投、落库恰好一次
# ---------------------------------------------------------------------------


async def test_recover_with_finished_job_replays_without_resubmit(sqlite_db, monkeypatch):
    issues, sessions = RagIssueService(), SessionService()
    user = "U20250000001"
    await UserService().create_users([user], ["viewer"], 200)
    session = await sessions.ensure_session(None, "评测", user)
    question = f"帮我评测 dns 插件：\n```c\n{PASTED_SOURCE}```"
    request_id = str(uuid4())
    task_id = issues.begin(request_id, user, session, question, "", "model")
    issues.store_task_inputs(task_id, question, source_text=PASTED_SOURCE,
                             source_origin="pasted")
    code_hash = hashlib.sha256(PASTED_SOURCE.encode()).hexdigest()
    job_id = f"{task_id}:dns:{code_hash[:16]}"
    issues.record_job(task_id, job_id, "grade_plugin", "dns", code_hash)
    _make_orphan(task_id)

    mcp = RoutingMcp(job_status="finished", job_result=HARNESS_OK)
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[grade_call()]),
        AIMessage(content="评测已完成：5/8 通过。"),
    ])
    _patch_agent_path(monkeypatch, model, mcp)

    assert await task_service.recover_orphans() == 1
    managed = task_service.TASKS[task_id]
    await asyncio.wait_for(managed.done.wait(), 5)
    assert managed.result is not None and managed.result.answer_status == "answered"

    # harness finished → 不打 grade_plugin；get_task_status 只探测一次、同 job_id
    assert [c for c in mcp.calls if c[0] == "grade_plugin"] == []
    assert [c for c in mcp.calls if c[0] == "get_task_status"] == [
        ("get_task_status", {"job_id": job_id})]
    # 成绩落库幂等闸：恰好一次
    assert _table_count("grading_submissions", "WHERE user_id=?", (user,)) == 1
    row = issues.get_task(task_id, user)
    assert row["status"] == "answered" and row["message_id"]
    assert issues.load_task_inputs(task_id) is None    # 终态即清


async def test_grade_save_failure_leaves_task_pending_then_recovers(sqlite_db, monkeypatch):
    """harness 已完成但成绩 DB 写失败：保留输入和结果，下一拍只补成绩。"""
    from app.services.grading_service import GradingService

    issues, sessions = RagIssueService(), SessionService()
    user = "U20250000009"
    await UserService().create_users([user], ["viewer"], 200)
    session = await sessions.ensure_session(None, "评测", user)
    question = f"帮我评测 dns 插件：\n```c\n{PASTED_SOURCE}```"
    task_id = issues.begin(str(uuid4()), user, session, question, "", "model")
    issues.store_task_inputs(task_id, question, source_text=PASTED_SOURCE, source_origin="pasted")
    code_hash = hashlib.sha256(PASTED_SOURCE.encode()).hexdigest()
    job_id = f"{task_id}:dns:{code_hash[:16]}"
    issues.record_job(task_id, job_id, "grade_plugin", "dns", code_hash)
    _make_orphan(task_id)
    mcp = RoutingMcp(job_status="finished", job_result=HARNESS_OK)
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[grade_call()]),
        AIMessage(content="", tool_calls=[grade_call()]),
        AIMessage(content="评测已完成。"),
    ])
    _patch_agent_path(monkeypatch, model, mcp)
    original = GradingService._insert_submission_in_conn

    def fail_insert(*args, **kwargs):
        raise RuntimeError("synthetic grade write failure")

    monkeypatch.setattr(GradingService, "_insert_submission_in_conn", fail_insert)
    assert await task_service.recover_orphans() == 1
    first = task_service.TASKS[task_id]
    await asyncio.wait_for(first.done.wait(), 5)
    assert issues.get_task(task_id, user)["status"] == "pending"
    assert issues.load_task_inputs(task_id) is not None
    assert issues.find_job(task_id, "dns", code_hash)["submission_done"] == 0
    assert _table_count("grading_submissions", "WHERE user_id=?", (user,)) == 0

    monkeypatch.setattr(GradingService, "_insert_submission_in_conn", original)
    _make_orphan(task_id)
    assert await task_service.recover_orphans() == 1
    second = task_service.TASKS[task_id]
    await asyncio.wait_for(second.done.wait(), 5)
    assert issues.get_task(task_id, user)["status"] == "answered"
    assert _table_count("grading_submissions", "WHERE task_job_id=?", (job_id,)) == 1
    assert [name for name, _ in mcp.calls if name == "grade_plugin"] == []


# ---------------------------------------------------------------------------
# 5. 恢复 + harness unknown：以同一 job_id 重驱动 grade_plugin
# ---------------------------------------------------------------------------


async def test_recover_with_unknown_job_resubmits_same_job_id(sqlite_db, monkeypatch):
    issues, sessions = RagIssueService(), SessionService()
    user = "U20250000002"
    await UserService().create_users([user], ["viewer"], 200)
    session = await sessions.ensure_session(None, "评测", user)
    question = f"帮我评测 dns 插件：\n```c\n{PASTED_SOURCE}```"
    task_id = issues.begin(str(uuid4()), user, session, question, "", "model")
    issues.store_task_inputs(task_id, question, source_text=PASTED_SOURCE,
                             source_origin="pasted")
    code_hash = hashlib.sha256(PASTED_SOURCE.encode()).hexdigest()
    job_id = f"{task_id}:dns:{code_hash[:16]}"
    issues.record_job(task_id, job_id, "grade_plugin", "dns", code_hash)
    _make_orphan(task_id)

    mcp = RoutingMcp(job_status="unknown")
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[grade_call()]),
        AIMessage(content="评测已完成：5/8 通过。"),
    ])
    _patch_agent_path(monkeypatch, model, mcp)

    assert await task_service.recover_orphans() == 1
    managed = task_service.TASKS[task_id]
    await asyncio.wait_for(managed.done.wait(), 5)
    assert managed.result is not None and managed.result.answer_status == "answered"

    grade_calls = [c for c in mcp.calls if c[0] == "grade_plugin"]
    assert len(grade_calls) == 1
    assert grade_calls[0][1]["job_id"] == job_id, "重投必须用账本里同一个 job_id"
    assert grade_calls[0][1]["plugin_source"] == PASTED_SOURCE
    assert _table_count("grading_submissions", "WHERE user_id=?", (user,)) == 1
    assert issues.get_task(task_id, user)["status"] == "answered"


# ---------------------------------------------------------------------------
# 6. model_args 执行源回填 inputs：恢复上下文按寄存字节重投
# ---------------------------------------------------------------------------


async def test_model_args_source_backfilled_and_reused_on_recovery(sqlite_db, monkeypatch):
    issues, sessions = RagIssueService(), SessionService()
    user = "U20250000003"
    await UserService().create_users([user], ["viewer"], 200)
    session = await sessions.ensure_session(None, "评测", user)
    question = "帮我评测这个 dns 插件"          # 无围栏块 → origin=model_args
    task_id = issues.begin(str(uuid4()), user, session, question, "", "model")
    issues.store_task_inputs(task_id, question)

    # 阶段一：任务内首次真实提交（工具级直驱），MCP 收到模型复述的源码
    mcp1 = RoutingMcp(job_status="unknown")
    orchestrator = make_orchestrator(
        ScriptedChatModel(responses=[AIMessage(content="占位")]), mcp1)
    _, context = orchestrator._build_run(session, user, ["viewer"], question, None,
                                         task_id=task_id)
    from app.agent.tools import build_tools
    grade = {tool.name: tool for tool in build_tools(context)}["grade_plugin"]
    result = await grade.ainvoke({"protocol": "dns", "plugin_source": MODEL_SOURCE})
    assert result["ok"] is True
    grade_calls = [c for c in mcp1.calls if c[0] == "grade_plugin"]
    assert grade_calls[0][1]["plugin_source"] == MODEL_SOURCE

    # 首次提交把执行源回填进 inputs：恢复要用同一份字节
    inputs = issues.load_task_inputs(task_id)
    assert inputs["source_text"] == MODEL_SOURCE and inputs["source_origin"] == "model_args"
    code_hash = hashlib.sha256(MODEL_SOURCE.encode()).hexdigest()
    job_id = f"{task_id}:dns:{code_hash[:16]}"
    assert grade_calls[0][1]["job_id"] == job_id

    # 模拟「提交后未收口」的崩溃现场：账本回到 submitted，成绩落库闸保持已认领
    with get_conn() as conn:
        conn.execute("UPDATE rag_task_jobs SET status='submitted',result_json=NULL "
                     "WHERE job_id=?", (job_id,))
    _make_orphan(task_id)

    # 阶段二：恢复重驱动——寄存源是权威执行源，模型复述漂移不影响字节
    mcp2 = RoutingMcp(job_status="unknown")
    model = ScriptedChatModel(responses=[
        AIMessage(content="", tool_calls=[grade_call()]),   # args 里复述的是别的字节
        AIMessage(content="评测已完成。"),
    ])
    _patch_agent_path(monkeypatch, model, mcp2)
    assert await task_service.recover_orphans() == 1
    managed = task_service.TASKS[task_id]
    await asyncio.wait_for(managed.done.wait(), 5)
    assert managed.result is not None

    resent = [c for c in mcp2.calls if c[0] == "grade_plugin"]
    assert len(resent) == 1
    assert resent[0][1]["job_id"] == job_id
    assert resent[0][1]["plugin_source"] == MODEL_SOURCE, \
        "恢复后执行源必须等于寄存字节，不是模型这次复述的内容"
    # 落库闸已认领：重投的结果不重复写成绩
    assert _table_count("grading_submissions", "WHERE user_id=?", (user,)) == 1


# ---------------------------------------------------------------------------
# 7. 越权访问：get_task / events / cancel 一律拒绝
# ---------------------------------------------------------------------------


async def test_foreign_task_access_is_rejected(sqlite_db, monkeypatch):
    product = _product()
    await product.prepare()
    task_id = product.task_id

    assert RagIssueService().get_task(task_id, "other") is None
    with pytest.raises(HTTPException) as exc:
        async for _ in task_service.task_events(task_id, "other"):
            pass
    assert exc.value.status_code == 404
    with pytest.raises(HTTPException) as exc:
        await task_service.cancel(task_id, "other")
    assert exc.value.status_code == 404

    from app.api import tasks as tasks_api
    other = Principal(subject="other", roles=["viewer"], daily_quota=200)
    with pytest.raises(HTTPException) as exc:
        await tasks_api.task_status(task_id, other)
    assert exc.value.status_code == 404
    with pytest.raises(HTTPException) as exc:
        await tasks_api.cancel_task(task_id, other)
    assert exc.value.status_code == 404
    with pytest.raises(HTTPException) as exc:
        await tasks_api.task_events_stream(task_id, other)
    assert exc.value.status_code == 404

    # 本人能读；/active 只认会话归属
    owner = Principal(subject="student", roles=["viewer"], daily_quota=200)
    status = await tasks_api.task_status(task_id, owner)
    assert status.task_id == task_id and status.status == "pending"   # 无注册表 → pending
    active = await tasks_api.active_task(product.session_id, owner)
    assert active["task"].task_id == task_id
    assert (await tasks_api.active_task(product.session_id, other))["task"] is None
    await task_service.cancel(task_id, "student")     # 收尾，别留给清扫夹具


# ---------------------------------------------------------------------------
# 8. 超时判死：system_error + inputs 清理 + 迟到订阅拿终态帧
# ---------------------------------------------------------------------------


async def test_task_timeout_marks_system_error(sqlite_db, monkeypatch):
    monkeypatch.setenv("RAG_TASK_MAX_SECONDS", "0.2")
    from app.config import get_settings
    get_settings.cache_clear()

    product = _product()
    await product.prepare()
    assert RagIssueService().load_task_inputs(product.task_id) is not None

    async def hanging(emit):
        await asyncio.sleep(30)
        return Verdict("正文", "answered", "supported"), [], "release"
    monkeypatch.setattr(product, "_candidate", hanging)
    managed = await task_service.start_task(product)
    await asyncio.wait_for(managed.done.wait(), 5)
    assert managed.terminal and managed.result is None

    row = RagIssueService().get_task(product.task_id, "student")
    assert row["status"] == "system_error" and row["reason"] == "timeout"
    assert RagIssueService().load_task_inputs(product.task_id) is None
    assert admission.active == 0

    frames = [event async for event in task_service.task_events(product.task_id, "student")]
    assert frames[-1]["event"] == "done"
    assert frames[-1]["data"]["terminal"] is True
    assert frames[-1]["data"]["status"] == "system_error"


# ---------------------------------------------------------------------------
# 9. 账号停用：该用户 pending 任务批量 cancelled（在跑协程一并收口）
# ---------------------------------------------------------------------------


async def test_account_disable_cancels_pending_tasks(sqlite_db, monkeypatch):
    issues, sessions = RagIssueService(), SessionService()
    user = "U20250000004"
    await UserService().create_users([user], ["viewer"], 200)

    # 一个在跑任务 + 一条未注册的 pending 行
    session = await sessions.ensure_session(None, "问题", user)
    product = RagProductService(
        Principal(subject=user, roles=["viewer"], daily_quota=200),
        ChatRequest(session_id=session, message="在跑问题", request_id=uuid4()))
    await product.prepare()

    async def hanging(emit):
        await asyncio.sleep(30)
        return Verdict("正文", "answered", "supported"), [], "release"
    monkeypatch.setattr(product, "_candidate", hanging)
    managed = await task_service.start_task(product)
    await asyncio.sleep(0)

    idle_task = issues.begin(str(uuid4()), user, session, "未起步问题", "", "model")
    issues.store_task_inputs(idle_task, "未起步问题")

    assert await UserService().set_enabled(user, False) is True

    for tid in (managed.task_id, idle_task):
        row = issues.get_task(tid, user)
        assert row["status"] == "cancelled" and row["reason"] == "account_disabled"
        assert issues.load_task_inputs(tid) is None
    assert managed.terminal and admission.active == 0
    # 停用不幂等于删除：重复停用仍是 True 之外的稳定行为（行已终态，不再变化）
    again = await task_service.cancel_pending_for_user(user, "account_disabled")
    assert again == 0


# ---------------------------------------------------------------------------
# 10. 属主心跳（S4-d 多副本防线）：在跑任务的 updated_at 由属主副本拨新，
#     其他副本的孤儿扫描/接续不再把它当孤儿重驱动（防跨副本双执行）。
# ---------------------------------------------------------------------------


def _begin_pending(user="student", session="sess_hb"):
    issues = RagIssueService()
    task_id = issues.begin(str(uuid4()), user, session, "心跳问题", "", "model")
    return task_id


def _updated_at(task_id):
    with get_conn() as conn:
        return conn.execute("SELECT updated_at FROM rag_requests WHERE id=?",
                            (task_id,)).fetchone()["updated_at"]


async def test_heartbeat_refreshes_owned_running_task(sqlite_db):
    """在跑句柄的 pending 行被拨新：即便超过 grace 也不再入选孤儿扫描。"""
    issues = RagIssueService()
    task_id = _begin_pending()
    _make_orphan(task_id)                                   # 模拟已跑很久
    assert [r["id"] for r in issues.orphaned_pending(30)] == [task_id]
    task_service.TASKS[task_id] = task_service.ManagedTask(task_id=task_id, service=None)
    assert task_service.heartbeat_owned() == 1
    assert issues.orphaned_pending(30) == []
    # 另一副本视角：注册表无条目但 updated_at 新鲜 → ensure_managed 不抢跑
    row = dict(issues.get_task(task_id, "student"))
    task_service.TASKS.pop(task_id)                         # B 视角：本地无此任务
    assert await task_service.ensure_managed(row) is None


async def test_heartbeat_skips_terminal_handles_and_non_pending_rows(sqlite_db):
    """terminal 句柄与已终态行都不拨新——真死亡语义照常成立。"""
    issues = RagIssueService()
    # terminal 句柄：注册表残留终态条目不续命
    t_terminal = _begin_pending()
    _make_orphan(t_terminal)
    managed = task_service.ManagedTask(task_id=t_terminal, service=None)
    managed.terminal = True
    task_service.TASKS[t_terminal] = managed
    # cancelled 行：句柄非 terminal 但行已终态 → SQL 条件排除
    t_cancelled = _begin_pending()
    issues.cancel_task(t_cancelled, "user_cancelled", "student")
    _make_orphan(t_cancelled)                              # 拨旧，等心跳误救
    stale = _updated_at(t_cancelled)
    task_service.TASKS[t_cancelled] = task_service.ManagedTask(
        task_id=t_cancelled, service=None)
    assert task_service.heartbeat_owned() == 0
    # t_terminal 仍是孤儿候选（句柄 terminal 不算活着）；t_cancelled 未被误拨
    assert [r["id"] for r in issues.orphaned_pending(30)] == [t_terminal]
    row = issues.get_task(t_cancelled, "student")
    assert row["status"] == "cancelled" and row["updated_at"] == stale


async def test_heartbeat_noop_when_registry_empty(sqlite_db):
    assert task_service.heartbeat_owned() == 0


async def test_claim_recovery_atomic_first_wins(sqlite_db):
    """认领闸真值表：太新/已认领/已终态一律拒绝，只有「pending 且陈旧」放行。

    多副本下两个恢复方并发相中同一孤儿行时，这条原子 UPDATE 是唯一仲裁。
    """
    issues = RagIssueService()
    task_id = _begin_pending()
    assert issues.claim_recovery(task_id) is False      # 太新（<grace）→ 拒
    _make_orphan(task_id)
    assert issues.claim_recovery(task_id) is True       # 先到先得
    assert issues.claim_recovery(task_id) is False      # 已拨新 → 后到者被拒
    issues.cancel_task(task_id, "user_cancelled", "student")
    _make_orphan(task_id)
    assert issues.claim_recovery(task_id) is False      # 非 pending → 拒
