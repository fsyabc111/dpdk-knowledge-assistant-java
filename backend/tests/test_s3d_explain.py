"""S3-d「正式作业结果按需讲解」（N02）回归测试。

端点：POST /api/assignments/{assignment_id}/submissions/{submission_id}/explain

- 确定性身份：session_id=f"explain-{sid}"，request_id 为 uuid5 派生——重复调用
  命中同一 rag_requests 行，pending 接续（attached）、终态回放（replayed），
  都不重复扣每日配额、不重复执行模型；
- 成绩行播种进 agent 上下文：_failed_graded_protocol 照常触发参考答案注入，
  讲解块声明「该成绩已落库，不主动重评」；
- 讲解不产生新的 grading_submissions 行。

驱动方式：HTTP（TestClient）只用于 submit 响应契约与 account 模式门控；
讲解调用直驱 ``assignments_api.explain_submission``（与 test_s3c 直驱
tasks_api 同口径），便于在同一事件循环里控制任务门闩与等待收口。
"""
from __future__ import annotations

import asyncio
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

from test_agent_service import ScriptedChatModel, make_orchestrator

from app.api import assignments as assignments_api
from app.api import tasks as tasks_api
from app.core.auth import Principal
from app.core.rate_limit import rate_limiter
from app.db import get_conn
from app.schemas.chat import ChatRequest
from app.services import task_service
from app.services.assignment_service import AssignmentService
from app.services.grading_service import GradingService
from app.services.rag_product_service import (
    RagProductService,
    Verdict,
    admission,
    build_explain_context,
    explain_request_identity,
)
from app.services.user_service import UserService

LEADER = "U20250000001"
TEACHER = "U20250000002"
STUDENT_A = "U20250000011"
STUDENT_B = "U20250000031"   # 未分配（created_by NULL）：只见 scope='all' 的作业

FUTURE_DEADLINE = datetime.now(timezone.utc) + timedelta(days=7)
PAST_DEADLINE = datetime.now(timezone.utc) - timedelta(days=1)

# harness 形态的逐用例结果（result ∈ PASS/FAIL）：2 通过 + 2 失败。
GRADE_RESULTS = [
    {"name": "dns_ok_1", "category": "[POSITIVE]", "result": "PASS"},
    {"name": "dns_ok_2", "category": "[POSITIVE]", "result": "PASS"},
    {"name": "dns_basic_3", "category": "[POSITIVE]", "result": "FAIL",
     "failures": [{"desc": "qname", "expected": "example.com", "actual": "www.example.com"}]},
    {"name": "dns_basic_4", "category": "[NEGATIVE]", "result": "FAIL"},
]
ALL_PASS_RESULTS = [
    {"name": "dns_ok_1", "result": "PASS"},
    {"name": "dns_ok_2", "result": "PASS"},
]
REFERENCE_SOURCE = "/* 教师参考实现（仅供模型理解错因） */"


@pytest.fixture(autouse=True)
async def _clean_task_registry():
    """任务注册表/占槽/限流桶是进程级全局状态：用例间清干净，残留协程取消收口。"""
    task_service.TASKS.clear()
    rate_limiter._buckets.clear()
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
    rate_limiter._buckets.clear()


@pytest.fixture
def product_on(sqlite_db, monkeypatch):
    """讲解走产品化管线（S4 起唯一路径，无开关）；保留 fixture 只刷新 Settings 缓存。"""
    from app.config import get_settings

    get_settings.cache_clear()


def _principal(user: str, quota: int = 200) -> Principal:
    return Principal(subject=user, roles=["viewer"], daily_quota=quota)


async def _make_users() -> None:
    users = UserService()
    await users.create_users([LEADER], roles=["leader"])
    await users.create_users([TEACHER], roles=["admin"], created_by=LEADER)
    await users.create_users([STUDENT_A], created_by=TEACHER)
    await users.create_users([STUDENT_B])  # 未分配


async def _make_assignment(*, creator="leader", deadline=None, title="作业一") -> dict:
    """creator="leader" → scope='all'（全体学生可见）；="teacher" → TEACHER 的 class 作业。"""
    actor, roles = (TEACHER, ["admin"]) if creator == "teacher" else (LEADER, ["leader"])
    return await AssignmentService().create_assignment(
        actor_id=actor,
        actor_roles=roles,
        title=title,
        description="",
        deadline=deadline or FUTURE_DEADLINE,
        items=[{"protocol": "dns", "points": 20}],
    )


async def _submission(assignment_id: str, user: str = STUDENT_A, results=None) -> str:
    """直接落一条正式成绩行（assignment_id 非空），返回 grade_* 提交 id。"""
    return await GradingService().record_submission(
        user_id=user,
        session_id=None,
        protocol="dns",
        code_hash="hash_" + uuid4().hex[:8],
        score=60,
        passed=2,
        total=4,
        results=GRADE_RESULTS if results is None else results,
        source_origin="pasted",
        assignment_id=assignment_id,
    )


async def _explain(assignment_id: str, submission_id: str, user: str = STUDENT_A):
    """直驱端点函数（依赖注入按显式 principal 传入）。"""
    return await assignments_api.explain_submission(
        assignment_id, submission_id, _principal(user))


def _table_count(name: str, where: str = "", params=()) -> int:
    with get_conn() as conn:
        return conn.execute(f"SELECT COUNT(*) FROM {name} {where}", params).fetchone()[0]


async def _fake_validate(question, answer, evidence, model, timeout, usage=None, request_id=""):
    return Verdict(answer or "正文", "answered", "supported")


class ExplainMcp:
    """MCP 替身：记录调用；get_reference_answer 回参考实现，其余回 ok。"""

    def __init__(self):
        self.calls = []

    async def __call__(self, tool_name, arguments):
        self.calls.append((tool_name, dict(arguments or {})))
        if tool_name == "get_reference_answer":
            return {"ok": True, "protocol": arguments.get("protocol"), "source": REFERENCE_SOURCE}
        if tool_name == "grade_plugin":
            return {"ok": True, "protocol": arguments.get("protocol"), "score": 100,
                    "passed": 4, "cases_total": 4, "results": ALL_PASS_RESULTS}
        return {"ok": True}


def _spy_model_prompts(model: ScriptedChatModel) -> list:
    """在实例上包一层 _generate：记录每次调用发给模型的消息列表（取系统提示词用）。"""
    prompts = []
    orig_generate = model._generate

    def spy(messages, stop=None, run_manager=None, **kwargs):
        prompts.append(list(messages))
        return orig_generate(messages, stop=stop, run_manager=run_manager, **kwargs)

    object.__setattr__(model, "_generate", spy)
    return prompts


def _all_prompt_text(prompts: list) -> str:
    return "\n".join(str(message.content) for messages in prompts for message in messages)


def _patch_agent_path(monkeypatch, model, mcp, captured=None):
    """产品路径的 agent 装配点换成进程内替身；captured 收集 _build_run 的入参与上下文。"""
    orchestrator = make_orchestrator(model, mcp)
    if captured is not None:
        orig_build_run = orchestrator._build_run

        def spy_build_run(*args, **kwargs):
            graph, context = orig_build_run(*args, **kwargs)
            captured["explain"] = kwargs.get("explain")
            captured["context"] = context
            return graph, context

        orchestrator._build_run = spy_build_run
    monkeypatch.setattr("app.agent.service.AgentOrchestrator", lambda: orchestrator)
    monkeypatch.setattr("app.services.rag_product_service.validate_answer", _fake_validate)
    return orchestrator


def _daily_chat_count(user: str = STUDENT_A) -> int:
    """直接读取日账本，避免 mock 被测的原子受理配额路径。"""
    with get_conn() as conn:
        row = conn.execute(
            "SELECT chat_count FROM usage_counters WHERE subject=? AND kind='chat'", (user,)
        ).fetchone()
    return int(row[0]) if row else 0


# ----------------------------------------------------------------------
# HTTP 层：submit 响应带 submission_id；非 account 模式整组 404
# ----------------------------------------------------------------------


async def _fake_harness_call(self, tool_name, arguments):
    if tool_name == "grade_plugin":
        return {
            "ok": True,
            "protocol": arguments.get("protocol"),
            "score": 60,
            "passed": 2,
            "cases_total": 4,
            "results": GRADE_RESULTS,
        }
    return {"ok": True, "protocols": [{"name": "dns", "cases": 4}]}


@pytest.fixture
def client(sqlite_db, monkeypatch):
    monkeypatch.setenv("AUTH_MODE", "account")
    from app.agent.mcp_client import HarnessMcpClient

    monkeypatch.setattr(HarnessMcpClient, "call_tool", _fake_harness_call)
    from app.config import get_settings

    get_settings.cache_clear()
    from fastapi.testclient import TestClient

    from app.main import app

    return TestClient(app)


def _bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def _login(client, user_id: str) -> str:
    response = client.post("/api/auth/login", json={"username": user_id, "password": user_id})
    assert response.status_code == 200, response.text
    return response.json()["token"]


def test_submit_response_contains_submission_id(client):
    asyncio.run(UserService().create_users([LEADER], roles=["leader"]))
    asyncio.run(UserService().create_users([STUDENT_A]))
    leader_token = _login(client, LEADER)
    student_token = _login(client, STUDENT_A)

    created = client.post(
        "/api/assignments",
        json={
            "title": "作业一",
            "description": "",
            "deadline": FUTURE_DEADLINE.isoformat(),
            "items": [{"protocol": "dns", "points": 20}],
        },
        headers=_bearer(leader_token),
    )
    assert created.status_code == 201, created.text
    assignment_id = created.json()["id"]

    response = client.post(
        f"/api/assignments/{assignment_id}/submit",
        json={"protocol": "dns", "code": "int main(){return 0;}"},
        headers=_bearer(student_token),
    )
    assert response.status_code == 200, response.text
    body = response.json()
    # 既有字段全部保留；新增 submission_id 供讲解入口引用。
    for field in ("score", "passed", "total", "results", "points", "earned", "best_earned"):
        assert field in body
    assert body["submission_id"].startswith("grade_")
    row = asyncio.run(GradingService().get_submission(body["submission_id"]))
    assert row is not None and row["assignment_id"] == assignment_id


def test_explain_route_gated_by_account_mode(sqlite_db, monkeypatch):
    """非 account 模式不泄露端点存在性（与作业路由同口径）。"""
    monkeypatch.setenv("AUTH_MODE", "local")
    from app.config import get_settings

    get_settings.cache_clear()
    from fastapi.testclient import TestClient

    from app.main import app

    client = TestClient(app)
    response = client.post("/api/assignments/asg_x/submissions/grade_x/explain")
    assert response.status_code == 404


# ----------------------------------------------------------------------
# 正常讲解：started + 任务可查 + 上下文播种 + 提示词 + 参考答案 + 不落新成绩
# ----------------------------------------------------------------------


async def test_explain_starts_task_and_seeds_context(product_on, monkeypatch):
    await _make_users()
    assignment = await _make_assignment()
    sid = await _submission(assignment["id"])

    model = ScriptedChatModel(responses=[AIMessage(content="dns_basic_3 挂在 qname 编码。")])
    mcp = ExplainMcp()
    captured = {}
    prompts = _spy_model_prompts(model)
    _patch_agent_path(monkeypatch, model, mcp, captured)

    response = await _explain(assignment["id"], sid)
    assert response.status == "started"
    assert response.session_id == f"explain-{sid}"
    assert response.task_id

    # 任务归本人所有：/api/tasks 只读视图可查。
    status = await tasks_api.task_status(response.task_id, _principal(STUDENT_A))
    assert status.task_id == response.task_id
    with pytest.raises(HTTPException) as exc:
        await tasks_api.task_status(response.task_id, _principal(STUDENT_B))
    assert exc.value.status_code == 404

    managed = task_service.TASKS[response.task_id]
    await asyncio.wait_for(managed.done.wait(), 5)
    assert managed.result is not None

    # 上下文播种：第一条 grading_results 是被讲解的那次提交，形状对齐 harness 载荷。
    context = captured["context"]
    assert context.explain_context["submission_id"] == sid
    assert context.explain_context["assignment_title"] == "作业一"
    seeded = context.grading_results[0]
    assert seeded["explained_submission"] == sid
    assert seeded["protocol"] == "dns" and seeded["cases_total"] == 4
    assert [case["result"] for case in seeded["results"]].count("FAIL") == 2

    # 系统提示词带讲解块：已落库成绩，不主动重评。
    prompt_text = _all_prompt_text(prompts)
    assert "不要主动调用 grade_plugin" in prompt_text
    assert "作业一" in prompt_text and "60" in prompt_text

    # 有非 PASS 用例 → 参考答案注入照常触发；讲解不主动打 grade_plugin。
    assert ("get_reference_answer", {"protocol": "dns"}) in mcp.calls
    assert [c for c in mcp.calls if c[0] == "grade_plugin"] == []

    # 讲解不产生新的正式成绩行：仍然只有提交那一次。
    assert _table_count("grading_submissions") == 1


async def test_explain_after_deadline_is_allowed(product_on, monkeypatch):
    """讲解不做截止期检查：过期的作业同样允许讲解已落库的成绩。"""
    await _make_users()
    assignment = await _make_assignment(deadline=PAST_DEADLINE)
    sid = await _submission(assignment["id"])

    model = ScriptedChatModel(responses=[AIMessage(content="讲解")])
    _patch_agent_path(monkeypatch, model, ExplainMcp())
    response = await _explain(assignment["id"], sid)
    assert response.status == "started"
    await asyncio.wait_for(task_service.TASKS[response.task_id].done.wait(), 5)


async def test_explain_all_pass_skips_reference_answer(product_on, monkeypatch):
    """全 PASS 的成绩行：讲解块照常，但不触发参考答案注入。"""
    await _make_users()
    assignment = await _make_assignment()
    sid = await _submission(assignment["id"], results=ALL_PASS_RESULTS)

    mcp = ExplainMcp()
    model = ScriptedChatModel(responses=[AIMessage(content="讲解")])
    _patch_agent_path(monkeypatch, model, mcp)
    response = await _explain(assignment["id"], sid)
    await asyncio.wait_for(task_service.TASKS[response.task_id].done.wait(), 5)
    assert [c for c in mcp.calls if c[0] == "get_reference_answer"] == []


# ----------------------------------------------------------------------
# 幂等：pending 接续（attached）与终态回放（replayed）
# ----------------------------------------------------------------------


async def test_duplicate_explain_attaches_to_pending_task(product_on, monkeypatch):
    await _make_users()
    assignment = await _make_assignment()
    sid = await _submission(assignment["id"])

    gate = asyncio.Event()
    candidate_calls = []

    async def gated(self, emit):
        candidate_calls.append(1)
        await gate.wait()
        return Verdict("讲解正文", "answered", "supported"), [], "release"

    monkeypatch.setattr(RagProductService, "_candidate", gated)

    first = await _explain(assignment["id"], sid)
    assert first.status == "started"
    await asyncio.sleep(0)   # 让执行协程进入 _candidate

    # 同 submission 重复讲解：接续同一任务，不新建、不重复扣每日配额。
    second = await _explain(assignment["id"], sid)
    assert second.status == "attached"
    assert second.task_id == first.task_id
    assert second.session_id == first.session_id
    assert len(task_service.TASKS) == 1
    assert _daily_chat_count() == 1

    gate.set()
    managed = task_service.TASKS[first.task_id]
    await asyncio.wait_for(managed.done.wait(), 5)
    assert len(candidate_calls) == 1, "同一任务只执行一次 _candidate"


async def test_explain_after_completion_replays_without_rerun(product_on, monkeypatch):
    await _make_users()
    assignment = await _make_assignment()
    sid = await _submission(assignment["id"])

    candidate_calls = []

    async def quick(self, emit):
        candidate_calls.append(1)
        return Verdict("讲解正文", "answered", "supported"), [], "release"

    monkeypatch.setattr(RagProductService, "_candidate", quick)

    first = await _explain(assignment["id"], sid)
    assert first.status == "started"
    await asyncio.wait_for(task_service.TASKS[first.task_id].done.wait(), 5)

    second = await _explain(assignment["id"], sid)
    assert second.status == "replayed"
    assert second.task_id == first.task_id
    assert second.session_id == first.session_id
    assert len(candidate_calls) == 1, "终态回放不重复执行模型"
    assert _daily_chat_count() == 1, "幂等重放不重复扣每日配额"


async def test_explain_replays_after_assignment_title_change(product_on, monkeypatch):
    """标题是可变展示数据，不应改变成绩行派生的讲解任务身份。"""
    await _make_users()
    assignment = await _make_assignment(creator="teacher", title="初始标题")
    sid = await _submission(assignment["id"])

    async def quick(self, emit):
        return Verdict("讲解正文", "answered", "supported"), [], "release"

    monkeypatch.setattr(RagProductService, "_candidate", quick)
    first = await _explain(assignment["id"], sid)
    await asyncio.wait_for(task_service.TASKS[first.task_id].done.wait(), 5)
    await AssignmentService().update_assignment(
        assignment["id"], actor_id=TEACHER, actor_roles=["admin"], title="修改后的标题",
    )
    second = await _explain(assignment["id"], sid)
    assert second.status == "replayed" and second.task_id == first.task_id
    assert _daily_chat_count() == 1
    # 同一个确定性 request_id 在普通聊天入口仍执行 S2 的 input_hash 冲突判定。
    session_id, request_id = explain_request_identity(sid)
    ordinary = RagProductService(_principal(STUDENT_A), ChatRequest(
        session_id=session_id, request_id=request_id, message="不同的普通聊天输入",
    ))
    with pytest.raises(HTTPException) as exc:
        await ordinary.probe_replay()
    assert exc.value.status_code == 409


# ----------------------------------------------------------------------
# 归属与可见性：同口径 404，不泄露成绩行存在性
# ----------------------------------------------------------------------


async def test_explain_foreign_submission_404(product_on):
    """别人的成绩行：作业可见但行不属于调用者 → 404。"""
    await _make_users()
    assignment = await _make_assignment()          # scope='all'：B 也可见
    sid = await _submission(assignment["id"], user=STUDENT_A)
    with pytest.raises(HTTPException) as exc:
        await _explain(assignment["id"], sid, user=STUDENT_B)
    assert exc.value.status_code == 404


async def test_explain_mismatched_assignment_404(product_on):
    """成绩行存在但属于另一个作业 → 404。"""
    await _make_users()
    assignment_a = await _make_assignment(title="作业甲")
    assignment_b = await _make_assignment(title="作业乙")
    sid = await _submission(assignment_b["id"], user=STUDENT_A)
    with pytest.raises(HTTPException) as exc:
        await _explain(assignment_a["id"], sid)
    assert exc.value.status_code == 404


async def test_explain_missing_submission_404(product_on):
    await _make_users()
    assignment = await _make_assignment()
    with pytest.raises(HTTPException) as exc:
        await _explain(assignment["id"], "grade_not_exist")
    assert exc.value.status_code == 404


async def test_explain_invisible_assignment_404(product_on):
    """class 作业对别班/未分配学生不可见 → 404（先于成绩行检查）。"""
    await _make_users()
    assignment = await _make_assignment(creator="teacher")   # scope='class'
    sid = await _submission(assignment["id"], user=STUDENT_A)
    with pytest.raises(HTTPException) as exc:
        await _explain(assignment["id"], sid, user=STUDENT_B)
    assert exc.value.status_code == 404


# ----------------------------------------------------------------------
# 装配失败（fail-closed）与配额
# ----------------------------------------------------------------------


async def test_explain_orchestrator_assembly_failure_returns_503(sqlite_db, monkeypatch):
    """编排层装配失败 → 受理返回 503（fail-closed，不再退回旧管线）。"""
    await _make_users()
    assignment = await _make_assignment()
    sid = await _submission(assignment["id"])

    def explode():
        raise RuntimeError("orchestrator assembly broken")

    monkeypatch.setattr("app.agent.service.AgentOrchestrator", explode)
    with pytest.raises(HTTPException) as exc:
        await _explain(assignment["id"], sid)
    assert exc.value.status_code == 503


async def test_explain_counts_daily_quota_once(product_on, monkeypatch):
    """新讲解计每日 chat 配额一次；attached 不计第二次。"""
    await _make_users()
    assignment = await _make_assignment()
    sid = await _submission(assignment["id"])

    gate = asyncio.Event()

    async def gated(self, emit):
        await gate.wait()
        return Verdict("讲解正文", "answered", "supported"), [], "release"

    monkeypatch.setattr(RagProductService, "_candidate", gated)

    first = await _explain(assignment["id"], sid)
    await _explain(assignment["id"], sid)      # attached
    assert _daily_chat_count() == 1
    gate.set()
    await asyncio.wait_for(task_service.TASKS[first.task_id].done.wait(), 5)


# ----------------------------------------------------------------------
# 恢复路径：_resolve_explain 按确定性身份从库行重建讲解上下文
# ----------------------------------------------------------------------


async def test_resolve_explain_rebuilds_context_from_identity(product_on):
    """构造参数不含 explain 的实例（恢复形态）：按 session/request 身份重建上下文。"""
    await _make_users()
    assignment = await _make_assignment()
    sid = await _submission(assignment["id"])

    session_id, request_id = explain_request_identity(sid)
    from app.schemas.chat import ChatRequest

    product = RagProductService(
        _principal(STUDENT_A),
        ChatRequest(session_id=session_id, request_id=request_id, message="讲解"),
    )
    context = await product._resolve_explain()
    assert context is not None
    assert context["submission_id"] == sid
    assert context["assignment_title"] == "作业一"
    assert context["score"] == 60 and context["total"] == 4

    # 同一 session_id 形态但 request_id 不是确定性派生值 → 不重建（普通会话）。
    other = RagProductService(
        _principal(STUDENT_A),
        ChatRequest(session_id=session_id, request_id=uuid4(), message="普通问题"),
    )
    assert await other._resolve_explain() is None

    # 成绩行不属于调用者 → 不重建。
    stranger = RagProductService(
        _principal(STUDENT_B),
        ChatRequest(session_id=session_id, request_id=request_id, message="讲解"),
    )
    assert await stranger._resolve_explain() is None


def test_explain_request_identity_is_deterministic():
    session_id, request_id = explain_request_identity("grade_abc")
    assert session_id == "explain-grade_abc"
    assert (session_id, request_id) == explain_request_identity("grade_abc")
    _, other = explain_request_identity("grade_xyz")
    assert request_id != other
    # ChatRequest 契约：request_id 必须是可解析的 UUID（uuid5 派生值满足）。
    from app.schemas.chat import ChatRequest

    req = ChatRequest(session_id=session_id, request_id=request_id, message="讲解")
    assert str(req.request_id) == request_id


def test_build_explain_context_shape():
    context = build_explain_context(
        {
            "id": "grade_x", "assignment_id": "asg_1", "protocol": "dns",
            "score": 60, "passed": 2, "total": 4, "results": GRADE_RESULTS,
            "source_origin": "pasted", "created_at": "2026-01-01T00:00:00+00:00",
        },
        {"title": "作业一"},
    )
    assert context["submission_id"] == "grade_x"
    assert context["assignment_title"] == "作业一"
    assert context["results"] == GRADE_RESULTS
    # 作业行缺失（如已删除）时标题退化空串，不抛错。
    degraded = build_explain_context({"id": "g", "protocol": "dns", "score": 0,
                                      "passed": 0, "total": 0}, None)
    assert degraded["assignment_title"] == ""


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
