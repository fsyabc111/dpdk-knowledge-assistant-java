"""批量删除下层账号（M9）：密码确认 + 树隔离 + 级联清理 + 审计留痕。

语义：
- 每次删除操作都必须附操作者自己的密码，校验失败 403；
- 自己/不存在/不可管的 id 进 skipped，不打断批量；
- 删除连带清理 token/会话/消息/引用/配额/改密申请，成绩行保留脱钩；
- 被删老师名下学生 created_by 置 NULL（未分配组）；
- 每次成功删除写一行 admin_audit_log，组长看全部、老师只看自己。

复用 test_user_hierarchy.py 的 in-process ASGI + throwaway SQLite 模式。
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

NEW_PASSWORD = "newpassword123"
WRONG_PASSWORD = "wrongpassword1"


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


def _settled_token(client, user_id: str) -> str:
    response = client.post("/api/auth/login", json={"username": user_id, "password": user_id})
    assert response.status_code == 200
    token = response.json()["token"]
    response = client.post(
        "/api/auth/change-password",
        json={"old_password": user_id, "new_password": NEW_PASSWORD},
        headers=bearer(token),
    )
    assert response.status_code == 204
    return token


@pytest.fixture
def hierarchy(client):
    _create_users([LEADER], roles=["leader"])
    _create_users([TEACHER_A, TEACHER_B], roles=["admin"], created_by=LEADER)
    _create_users([STUDENT_A1, STUDENT_A2], created_by=TEACHER_A)
    _create_users([STUDENT_B1], created_by=TEACHER_B)
    return {
        "leader": _settled_token(client, LEADER),
        "teacher_a": _settled_token(client, TEACHER_A),
    }


def _user_exists(user_id: str) -> bool:
    from app.db import get_conn, get_database_provider

    ph = get_database_provider().placeholder
    with get_conn() as conn:
        return conn.execute(f"SELECT 1 FROM users WHERE id = {ph}", (user_id,)).fetchone() is not None


def _audit_rows() -> list:
    from app.db import get_conn

    with get_conn() as conn:
        return conn.execute(
            "SELECT actor, action, targets_json FROM admin_audit_log"
        ).fetchall()


# ---------------------------------------------------------------------------
# 密码确认
# ---------------------------------------------------------------------------


def test_delete_requires_correct_password(client, hierarchy):
    response = client.post(
        "/api/admin/users/delete",
        json={"ids": [STUDENT_A1], "password": WRONG_PASSWORD},
        headers=bearer(hierarchy["teacher_a"]),
    )
    assert response.status_code == 403
    assert _user_exists(STUDENT_A1)
    assert _audit_rows() == []


def test_static_token_cannot_delete(client, hierarchy):
    # 静态应急 token 主体不在 users 表中，无法通过密码校验。
    response = client.post(
        "/api/admin/users/delete",
        json={"ids": [STUDENT_A1], "password": "whatever"},
        headers=bearer(FALLBACK_TOKEN),
    )
    assert response.status_code == 403


# ---------------------------------------------------------------------------
# 树隔离与自保护
# ---------------------------------------------------------------------------


def test_cannot_delete_self(client, hierarchy):
    response = client.post(
        "/api/admin/users/delete",
        json={"ids": [TEACHER_A], "password": NEW_PASSWORD},
        headers=bearer(hierarchy["teacher_a"]),
    )
    assert response.status_code == 200
    assert response.json() == {"deleted": [], "skipped": [TEACHER_A]}
    assert _user_exists(TEACHER_A)


def test_teacher_cannot_delete_others_students(client, hierarchy):
    response = client.post(
        "/api/admin/users/delete",
        json={"ids": [STUDENT_B1, "U20259999999", STUDENT_A1], "password": NEW_PASSWORD},
        headers=bearer(hierarchy["teacher_a"]),
    )
    assert response.status_code == 200
    assert response.json()["deleted"] == [STUDENT_A1]
    assert set(response.json()["skipped"]) == {STUDENT_B1, "U20259999999"}
    assert _user_exists(STUDENT_B1)
    assert not _user_exists(STUDENT_A1)


def test_viewer_cannot_access_delete_endpoint(client, hierarchy):
    token = _settled_token(client, STUDENT_A2)
    response = client.post(
        "/api/admin/users/delete",
        json={"ids": [STUDENT_A1], "password": NEW_PASSWORD},
        headers=bearer(token),
    )
    assert response.status_code == 404


# ---------------------------------------------------------------------------
# 级联清理与审计
# ---------------------------------------------------------------------------


def test_delete_cascades_data_and_writes_audit(client, hierarchy):
    from app.db import get_conn, get_database_provider
    from app.services.session_service import SessionService

    # 造数据：学生的一个会话（带消息）+ 一条配额计数 + 一个改密申请 + 一个有效 token。
    service = SessionService()
    session_id = asyncio.run(service.ensure_session(None, "t", STUDENT_A1))
    asyncio.run(service.add_message(session_id, "user", "hello"))
    student_token = _settled_token(client, STUDENT_A1)
    ph = get_database_provider().placeholder
    with get_conn() as conn:
        conn.execute(
            f"INSERT INTO usage_counters(subject, day, chat_count, kind) "
            f"VALUES ({ph}, '2026-01-01', 3, 'chat')",
            (STUDENT_A1,),
        )
        conn.execute(
            f"INSERT INTO password_reset_requests(id, user_id, status, created_at) "
            f"VALUES ({ph}, {ph}, 'pending', '2026-01-01')",
            ("req_x1", STUDENT_A1),
        )

    response = client.post(
        "/api/admin/users/delete",
        json={"ids": [STUDENT_A1], "password": NEW_PASSWORD},
        headers=bearer(hierarchy["teacher_a"]),
    )
    assert response.status_code == 200
    assert response.json()["deleted"] == [STUDENT_A1]

    with get_conn() as conn:
        assert conn.execute(f"SELECT 1 FROM users WHERE id = {ph}", (STUDENT_A1,)).fetchone() is None
        assert conn.execute(f"SELECT 1 FROM api_tokens WHERE user_id = {ph}", (STUDENT_A1,)).fetchone() is None
        assert conn.execute(f"SELECT 1 FROM assistant_sessions WHERE id = {ph}", (session_id,)).fetchone() is None
        assert conn.execute(
            f"SELECT 1 FROM assistant_messages WHERE session_id = {ph}", (session_id,)
        ).fetchone() is None
        assert conn.execute(
            f"SELECT 1 FROM usage_counters WHERE subject = {ph}", (STUDENT_A1,)
        ).fetchone() is None
        assert conn.execute(
            f"SELECT 1 FROM password_reset_requests WHERE user_id = {ph}", (STUDENT_A1,)
        ).fetchone() is None

    # 被删学生的 token 立即失效。
    assert client.get("/api/chat/sessions", headers=bearer(student_token)).status_code == 401

    # 审计行：一次删除一行，记录操作者与目标。
    rows = _audit_rows()
    assert len(rows) == 1
    assert rows[0]["actor"] == TEACHER_A
    assert rows[0]["action"] == "delete_users"
    assert json.loads(rows[0]["targets_json"]) == [STUDENT_A1]


def test_delete_teacher_unassigns_students(client, hierarchy):
    response = client.post(
        "/api/admin/users/delete",
        json={"ids": [TEACHER_A], "password": NEW_PASSWORD},
        headers=bearer(hierarchy["leader"]),
    )
    assert response.status_code == 200
    assert response.json()["deleted"] == [TEACHER_A]
    assert not _user_exists(TEACHER_A)
    # 名下学生保留，created_by 置 NULL（未分配组）。
    users = client.get("/api/admin/users", headers=bearer(hierarchy["leader"])).json()["users"]
    by_id = {user["id"]: user for user in users}
    assert by_id[STUDENT_A1]["created_by"] is None
    assert by_id[STUDENT_A2]["created_by"] is None


def test_audit_log_scoped(client, hierarchy):
    client.post(
        "/api/admin/users/delete",
        json={"ids": [STUDENT_A1], "password": NEW_PASSWORD},
        headers=bearer(hierarchy["teacher_a"]),
    )
    client.post(
        "/api/admin/users/delete",
        json={"ids": [STUDENT_B1], "password": NEW_PASSWORD},
        headers=bearer(hierarchy["leader"]),
    )

    entries = client.get("/api/admin/audit-log", headers=bearer(hierarchy["leader"])).json()["entries"]
    assert len(entries) == 2
    assert {entry["actor"] for entry in entries} == {TEACHER_A, LEADER}

    entries = client.get("/api/admin/audit-log", headers=bearer(hierarchy["teacher_a"])).json()["entries"]
    assert len(entries) == 1
    assert entries[0]["actor"] == TEACHER_A
    assert entries[0]["targets"] == [STUDENT_A1]

    viewer_token = _settled_token(client, STUDENT_A2)
    assert client.get("/api/admin/audit-log", headers=bearer(viewer_token)).status_code == 404


if __name__ == "__main__":
    import sys

    sys.exit(pytest.main([__file__, "-v"]))
