"""S4-b W4：学生详情表「未提交」显示口径的契约回归。

冻结口径（docs/decisions.md §9）：学生视图里无提交记录的协议统一显示
「未提交」，取代原先「最高分 —、折算 0」的不齐两列。落到 API 契约上：
``StudentItemGrade.earned`` 对无提交协议为 ``None``（0.0 留给「提交了但
0 分」），``total_earned`` 聚合时 None 按 0 计。

两层断言：
- service 层 ``_student_view`` 的行装配（纯函数，不起库）；
- API 层 ``GET /api/assignments``（学生视角）序列化出 ``earned: null``，
  锁 JSON 契约，与 test_assignments_api.py 同一套假 harness 机制。
"""
from __future__ import annotations

import asyncio
import json

import pytest

TEACHER = "U20250000002"
STUDENT = "U20250000011"


def _assignment(items=None) -> dict:
    """最小作业字典（_student_view 只读 id/items 与其余透传字段）。"""
    return {
        "id": "asg_w4",
        "title": "作业",
        "description": "",
        "created_by": TEACHER,
        "scope": "all",
        "deadline": "2026-10-01T00:00:00+00:00",
        "status": "active",
        "created_at": "2026-09-20T00:00:00+00:00",
        "updated_at": "2026-09-20T00:00:00+00:00",
        "items": items
        or [{"protocol": "dns", "points": 20}, {"protocol": "http", "points": 30}],
    }


def _view(mine: dict) -> dict:
    from app.services.assignment_service import AssignmentService

    return AssignmentService._student_view(_assignment(), mine)


def test_unsubmitted_protocol_earned_is_none():
    """完全无提交：每个协议 earned=None、best_score=None、提交数 0。"""
    view = _view({})
    for item in view["items"]:
        assert item["best_score"] is None
        assert item["earned"] is None
        assert item["submission_count"] == 0
    assert view["total_earned"] == 0.0
    assert view["total_points"] == 50
    assert view["submission_count"] == 0


def test_partial_submission_earned_and_total():
    """dns 已提交（80 分×20 分=16.0）数值不变；http 未提交 earned=None；
    total_earned 聚合把 None 按 0 计，仍为 16.0。"""
    mine = {"asg_w4": {"dns": {"best_score": 80, "submission_count": 2}}}
    view = _view(mine)
    dns = next(item for item in view["items"] if item["protocol"] == "dns")
    http = next(item for item in view["items"] if item["protocol"] == "http")
    assert dns["best_score"] == 80
    assert dns["earned"] == 16.0
    assert dns["submission_count"] == 2
    assert http["earned"] is None
    assert view["total_earned"] == 16.0
    assert view["submission_count"] == 2


def test_zero_score_submission_earned_is_zero_not_none():
    """提交了但最高 0 分：earned=0.0（不是 None）——与「未提交」语义区分。"""
    mine = {"asg_w4": {"dns": {"best_score": 0, "submission_count": 1}}}
    view = _view(mine)
    dns = next(item for item in view["items"] if item["protocol"] == "dns")
    assert dns["best_score"] == 0
    assert dns["earned"] == 0.0
    assert view["total_earned"] == 0.0


# ----------------------------------------------------------------------
# API 层：序列化出的 JSON 里 earned 确实是 null（schema Optional[float]）
# ----------------------------------------------------------------------


@pytest.fixture(autouse=True)
def _reset_account_state():
    """清登录限流/锁定与 token 缓存（同 test_assignments_api.py 口径）。"""
    from app.api.auth import _login_lockout, _login_rate_limiter, _reset_rate_limiter
    from app.services.user_service import clear_token_cache

    _login_rate_limiter._buckets.clear()
    _reset_rate_limiter._buckets.clear()
    _login_lockout._failures.clear()
    _login_lockout._locked_until.clear()
    clear_token_cache()
    yield
    _login_rate_limiter._buckets.clear()
    _reset_rate_limiter._buckets.clear()
    _login_lockout._failures.clear()
    _login_lockout._locked_until.clear()
    clear_token_cache()


async def _fake_call_tool(self, tool_name, arguments):
    """Harness MCP 假实现：只喂 grade_plugin 固定 80 分。"""
    if tool_name == "grade_plugin":
        return {
            "ok": True,
            "protocol": arguments.get("protocol"),
            "score": 80,
            "passed": 4,
            "cases_total": 5,
            "results": [{"name": f"case{i}", "passed": i <= 4} for i in range(1, 6)],
        }
    return {"ok": False, "error": "unknown_tool", "detail": tool_name}


@pytest.fixture
def client(sqlite_db, monkeypatch):
    """account 模式 + 假 harness（同 test_assignments_api.py 的机制）。"""
    monkeypatch.setenv("AUTH_MODE", "account")
    monkeypatch.setenv("API_TOKENS_JSON", json.dumps({}))

    from app.agent.mcp_client import HarnessMcpClient

    monkeypatch.setattr(HarnessMcpClient, "call_tool", _fake_call_tool)

    from app.config import get_settings

    get_settings.cache_clear()

    from fastapi.testclient import TestClient

    from app.main import app

    return TestClient(app)


def _login(client, user_id: str) -> dict:
    response = client.post("/api/auth/login", json={"username": user_id, "password": user_id})
    assert response.status_code == 200
    return {"Authorization": f"Bearer {response.json()['token']}"}


def test_api_serializes_earned_null_for_unsubmitted(client):
    from app.services.user_service import UserService

    asyncio.run(UserService().create_users([TEACHER], roles=["admin"]))
    asyncio.run(UserService().create_users([STUDENT], created_by=TEACHER))
    teacher = _login(client, TEACHER)
    student = _login(client, STUDENT)

    response = client.post(
        "/api/assignments",
        json={
            "title": "作业一",
            "description": "",
            "deadline": "2099-01-01T00:00:00+00:00",
            "items": [{"protocol": "dns", "points": 20}, {"protocol": "http", "points": 30}],
        },
        headers=teacher,
    )
    assert response.status_code == 201, response.text
    aid = response.json()["id"]

    # 只提交 dns；http 保持无提交。
    response = client.post(
        f"/api/assignments/{aid}/submit",
        json={"protocol": "dns", "code": "int main() { return 0; }"},
        headers=student,
    )
    assert response.status_code == 200, response.text

    response = client.get("/api/assignments", headers=student)
    assert response.status_code == 200
    row = next(a for a in response.json()["assignments"] if a["id"] == aid)
    dns = next(item for item in row["items"] if item["protocol"] == "dns")
    http = next(item for item in row["items"] if item["protocol"] == "http")
    # 契约：未提交协议 JSON 序列化为 earned: null / best_score: null。
    assert http["best_score"] is None
    assert http["earned"] is None
    assert http["submission_count"] == 0
    assert dns["earned"] == 16.0
    assert row["total_earned"] == 16.0
