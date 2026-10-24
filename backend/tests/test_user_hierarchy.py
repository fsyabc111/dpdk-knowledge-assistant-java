"""User hierarchy regression tests (M8): 组长(leader) → 老师(admin) → 学生(viewer).

树隔离语义：
- leader 管理全部用户；admin（老师）只能创建 viewer、且只能管理
  created_by=自己 的纯 viewer 学生；API 不能授予 leader 角色；
- 静态应急 token（API_TOKENS_JSON 主体）不受树约束；
- 任何人不能停用自己或改自己的角色。

复用 test_account_auth.py 的 in-process ASGI + throwaway SQLite 模式。
"""
from __future__ import annotations

import asyncio
import json

import pytest

FALLBACK_TOKEN = "f" * 32
FALLBACK = {"subject": "emergency-admin", "roles": ["admin"], "daily_quota": 0}

LEADER = "U20250000001"
TEACHER_A = "U20250000002"
TEACHER_B = "U20250000003"
STUDENT_A1 = "U20250000011"
STUDENT_A2 = "U20250000012"
STUDENT_B1 = "U20250000021"
ORPHAN = "U20250000031"  # created_by 为空的存量学生

NEW_PASSWORD = "newpassword123"


@pytest.fixture(autouse=True)
def _reset_account_state():
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


@pytest.fixture
def client(sqlite_db, monkeypatch):
    """TestClient in account auth mode with the LLM chain mocked out."""
    monkeypatch.setenv("AUTH_MODE", "account")
    monkeypatch.setenv("API_TOKENS_JSON", json.dumps({FALLBACK_TOKEN: FALLBACK}))

    from app.config import get_settings

    get_settings.cache_clear()

    from fastapi.testclient import TestClient

    from app.main import app

    return TestClient(app)


def bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def _create_users(ids, roles=None, created_by=None):
    from app.services.user_service import UserService

    return asyncio.run(UserService().create_users(ids, roles=roles, created_by=created_by))


def _login_token(client, user_id: str, password: str | None = None) -> str:
    response = client.post(
        "/api/auth/login",
        json={"username": user_id, "password": password if password is not None else user_id},
    )
    assert response.status_code == 200
    return response.json()["token"]


def _settled_token(client, user_id: str, password: str | None = None) -> str:
    old_password = password if password is not None else user_id
    token = _login_token(client, user_id, password)
    response = client.post(
        "/api/auth/change-password",
        json={"old_password": old_password, "new_password": NEW_PASSWORD},
        headers=bearer(token),
    )
    assert response.status_code == 204
    return token


@pytest.fixture
def hierarchy(client):
    """组长 + 两名老师 + 各自学生 + 一个无归属存量学生；返回各角色 token。"""
    _create_users([LEADER], roles=["leader"])
    _create_users([TEACHER_A, TEACHER_B], roles=["admin"], created_by=LEADER)
    _create_users([STUDENT_A1, STUDENT_A2], created_by=TEACHER_A)
    _create_users([STUDENT_B1], created_by=TEACHER_B)
    _create_users([ORPHAN])
    return {
        "leader": _settled_token(client, LEADER),
        "teacher_a": _settled_token(client, TEACHER_A),
        "teacher_b": _settled_token(client, TEACHER_B),
    }


# ---------------------------------------------------------------------------
# 创建权限
# ---------------------------------------------------------------------------


def test_teacher_creates_student_with_ownership(client, hierarchy):
    response = client.post(
        "/api/admin/users",
        json={"ids": ["U20250000099"]},
        headers=bearer(hierarchy["teacher_a"]),
    )
    assert response.status_code == 200
    assert response.json()["created"] == ["U20250000099"]
    users = client.get("/api/admin/users", headers=bearer(hierarchy["leader"])).json()["users"]
    created = {user["id"]: user for user in users}["U20250000099"]
    assert created["roles"] == ["viewer"]
    assert created["created_by"] == TEACHER_A


def test_teacher_cannot_create_teacher_or_operator(client, hierarchy):
    for roles in (["admin"], ["operator"], ["viewer", "admin"]):
        response = client.post(
            "/api/admin/users",
            json={"ids": ["U20250000098"], "roles": roles},
            headers=bearer(hierarchy["teacher_a"]),
        )
        assert response.status_code == 400


def test_api_cannot_grant_leader_role(client, hierarchy):
    response = client.post(
        "/api/admin/users",
        json={"ids": ["U20250000097"], "roles": ["leader"]},
        headers=bearer(hierarchy["leader"]),
    )
    assert response.status_code == 400
    # PATCH 提权同样禁止。
    response = client.patch(
        f"/api/admin/users/{STUDENT_A1}",
        json={"roles": ["leader"]},
        headers=bearer(hierarchy["leader"]),
    )
    assert response.status_code == 400


def test_leader_creates_teacher(client, hierarchy):
    response = client.post(
        "/api/admin/users",
        json={"ids": ["U20250000096"], "roles": ["admin"]},
        headers=bearer(hierarchy["leader"]),
    )
    assert response.status_code == 200
    users = client.get("/api/admin/users", headers=bearer(hierarchy["leader"])).json()["users"]
    created = {user["id"]: user for user in users}["U20250000096"]
    assert created["roles"] == ["admin"]
    assert created["created_by"] == LEADER


# ---------------------------------------------------------------------------
# 列表与树隔离
# ---------------------------------------------------------------------------


def test_leader_lists_all_users(client, hierarchy):
    users = client.get("/api/admin/users", headers=bearer(hierarchy["leader"])).json()["users"]
    assert {user["id"] for user in users} == {
        LEADER, TEACHER_A, TEACHER_B, STUDENT_A1, STUDENT_A2, STUDENT_B1, ORPHAN,
    }


def test_teacher_lists_only_self_and_own_students(client, hierarchy):
    users = client.get("/api/admin/users", headers=bearer(hierarchy["teacher_a"])).json()["users"]
    assert {user["id"] for user in users} == {TEACHER_A, STUDENT_A1, STUDENT_A2}


def test_teacher_cannot_manage_other_teachers_students(client, hierarchy):
    for method, url, payload in (
        ("post", f"/api/admin/users/{STUDENT_B1}/reset-password", None),
        ("post", f"/api/admin/users/{STUDENT_B1}/set-password", {"new_password": "whateverpass1"}),
        ("post", f"/api/admin/users/{STUDENT_B1}/status", {"enabled": False}),
        ("patch", f"/api/admin/users/{STUDENT_B1}", {"daily_quota": 5}),
    ):
        response = getattr(client, method)(
            url, headers=bearer(hierarchy["teacher_a"]), **({"json": payload} if payload else {})
        )
        assert response.status_code == 404, f"{method} {url}"


def test_teacher_cannot_manage_unowned_orphan_student(client, hierarchy):
    response = client.post(
        f"/api/admin/users/{ORPHAN}/reset-password", headers=bearer(hierarchy["teacher_a"])
    )
    assert response.status_code == 404


def test_teacher_cannot_manage_other_teacher(client, hierarchy):
    response = client.post(
        f"/api/admin/users/{TEACHER_B}/reset-password", headers=bearer(hierarchy["teacher_a"])
    )
    assert response.status_code == 404


def test_teacher_manages_own_student(client, hierarchy):
    response = client.post(
        f"/api/admin/users/{STUDENT_A1}/reset-password", headers=bearer(hierarchy["teacher_a"])
    )
    assert response.status_code == 204
    # 重置回初始密码后可登录。
    login = client.post(
        "/api/auth/login", json={"username": STUDENT_A1, "password": STUDENT_A1}
    )
    assert login.status_code == 200


def test_teacher_cannot_patch_roles_or_ownership(client, hierarchy):
    for payload in ({"roles": ["viewer"]}, {"created_by": TEACHER_A}):
        response = client.patch(
            f"/api/admin/users/{STUDENT_A1}",
            json=payload,
            headers=bearer(hierarchy["teacher_a"]),
        )
        assert response.status_code == 400


def test_teacher_can_patch_own_student_quota(client, hierarchy):
    response = client.patch(
        f"/api/admin/users/{STUDENT_A1}",
        json={"daily_quota": 7},
        headers=bearer(hierarchy["teacher_a"]),
    )
    assert response.status_code == 200
    assert response.json()["daily_quota"] == 7


# ---------------------------------------------------------------------------
# 组长特权与自保护
# ---------------------------------------------------------------------------


def test_leader_manages_any_user(client, hierarchy):
    assert client.post(
        f"/api/admin/users/{STUDENT_B1}/reset-password", headers=bearer(hierarchy["leader"])
    ).status_code == 204
    assert client.post(
        f"/api/admin/users/{TEACHER_B}/status",
        json={"enabled": False},
        headers=bearer(hierarchy["leader"]),
    ).status_code == 204


def test_leader_transfers_ownership(client, hierarchy):
    response = client.patch(
        f"/api/admin/users/{ORPHAN}",
        json={"created_by": TEACHER_B},
        headers=bearer(hierarchy["leader"]),
    )
    assert response.status_code == 200
    assert response.json()["created_by"] == TEACHER_B
    # 转归属后 TEACHER_B 可见可管，TEACHER_A 不可见。
    users_b = client.get("/api/admin/users", headers=bearer(hierarchy["teacher_b"])).json()["users"]
    assert ORPHAN in {user["id"] for user in users_b}
    users_a = client.get("/api/admin/users", headers=bearer(hierarchy["teacher_a"])).json()["users"]
    assert ORPHAN not in {user["id"] for user in users_a}


def test_leader_transfer_ownership_validates_target(client, hierarchy):
    assert client.patch(
        f"/api/admin/users/{ORPHAN}",
        json={"created_by": "U20259999999"},
        headers=bearer(hierarchy["leader"]),
    ).status_code == 400
    assert client.patch(
        f"/api/admin/users/{ORPHAN}",
        json={"created_by": ORPHAN},
        headers=bearer(hierarchy["leader"]),
    ).status_code == 400


def test_cannot_disable_self(client, hierarchy):
    response = client.post(
        f"/api/admin/users/{LEADER}/status",
        json={"enabled": False},
        headers=bearer(hierarchy["leader"]),
    )
    assert response.status_code == 400


def test_cannot_change_own_roles(client, hierarchy):
    response = client.patch(
        f"/api/admin/users/{LEADER}",
        json={"roles": ["leader", "admin"]},
        headers=bearer(hierarchy["leader"]),
    )
    assert response.status_code == 400


def test_static_token_bypasses_tree(client, hierarchy):
    # 静态应急 token 主体不在 users 表中，按组长语义放行。
    response = client.post(
        f"/api/admin/users/{ORPHAN}/reset-password", headers=bearer(FALLBACK_TOKEN)
    )
    assert response.status_code == 204


# ---------------------------------------------------------------------------
# 改密申请树隔离
# ---------------------------------------------------------------------------


def test_reset_requests_scoped_to_own_students(client, hierarchy):
    for user_id in (STUDENT_A1, STUDENT_B1, ORPHAN):
        assert client.post(
            "/api/auth/password-reset-requests", json={"username": user_id}
        ).status_code == 202

    listing = client.get(
        "/api/admin/password-reset-requests", headers=bearer(hierarchy["teacher_a"])
    ).json()["requests"]
    assert {item["user_id"] for item in listing} == {STUDENT_A1}

    listing = client.get(
        "/api/admin/password-reset-requests", headers=bearer(hierarchy["leader"])
    ).json()["requests"]
    assert {item["user_id"] for item in listing} == {STUDENT_A1, STUDENT_B1, ORPHAN}

    # 老师审批/驳回别人学生的申请 → 404（不泄露存在性）。
    leader_listing = {item["user_id"]: item["id"] for item in listing}
    other_id = leader_listing[STUDENT_B1]
    assert client.post(
        f"/api/admin/password-reset-requests/{other_id}/approve",
        headers=bearer(hierarchy["teacher_a"]),
    ).status_code == 404
    assert client.post(
        f"/api/admin/password-reset-requests/{other_id}/reject",
        headers=bearer(hierarchy["teacher_a"]),
    ).status_code == 404

    # 自己学生的申请可以审批。
    own_id = leader_listing[STUDENT_A1]
    approved = client.post(
        f"/api/admin/password-reset-requests/{own_id}/approve",
        headers=bearer(hierarchy["teacher_a"]),
    )
    assert approved.status_code == 200
    assert approved.json()["reset_token"]


# ---------------------------------------------------------------------------
# 用量看板与成绩视图树隔离
# ---------------------------------------------------------------------------


def _seed_chat_counter(subject: str, count: int) -> None:
    from datetime import datetime, timezone

    from app.db import get_conn, get_database_provider

    ph = get_database_provider().placeholder
    day = datetime.now(timezone.utc).date().isoformat()
    with get_conn() as conn:
        conn.execute(
            f"INSERT INTO usage_counters(subject, day, chat_count, kind) VALUES ({ph}, {ph}, {ph}, 'chat')",
            (subject, day, count),
        )


def test_usage_subjects_scoped_for_teacher(client, hierarchy):
    _seed_chat_counter(STUDENT_A1, 3)
    _seed_chat_counter(STUDENT_B1, 5)
    _seed_chat_counter(ORPHAN, 7)

    response = client.get("/api/usage/subjects", headers=bearer(hierarchy["teacher_a"]))
    assert response.status_code == 200
    subjects = {item["subject"] for item in response.json()["subjects"]}
    assert subjects == {TEACHER_A, STUDENT_A1, STUDENT_A2}

    response = client.get("/api/usage/subjects", headers=bearer(hierarchy["leader"]))
    subjects = {item["subject"] for item in response.json()["subjects"]}
    assert {STUDENT_A1, STUDENT_B1, ORPHAN} <= subjects


def test_grading_views_scoped_for_teacher(client, hierarchy):
    from app.services.grading_service import GradingService

    async def seed():
        service = GradingService()
        for user_id, score in ((STUDENT_A1, 80), (STUDENT_B1, 60), (ORPHAN, 100)):
            await service.record_submission(
                user_id=user_id, session_id=None, protocol="http",
                code_hash=f"hash-{user_id}", score=score, passed=score // 10, total=10,
                results=[],
            )

    asyncio.run(seed())

    response = client.get("/api/grading/submissions", headers=bearer(hierarchy["teacher_a"]))
    assert response.status_code == 200
    assert {row["user_id"] for row in response.json()["submissions"]} == {STUDENT_A1}

    summary = client.get("/api/grading/summary", headers=bearer(hierarchy["teacher_a"]))
    assert {row["user_id"] for row in summary.json()["by_user"]} == {STUDENT_A1}

    leader_summary = client.get("/api/grading/summary", headers=bearer(hierarchy["leader"]))
    assert {row["user_id"] for row in leader_summary.json()["by_user"]} == {
        STUDENT_A1, STUDENT_B1, ORPHAN,
    }


if __name__ == "__main__":
    import sys

    sys.exit(pytest.main([__file__, "-v"]))
