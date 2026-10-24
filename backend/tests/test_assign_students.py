"""组长分配未分配学生（M9）：POST /api/admin/users/assign。

语义：仅组长（或静态应急 token）可操作；目标必须是启用中的老师/组长账号；
非纯 viewer 或不存在的 id 进 skipped；分配后学生在目标老师的管理范围可见。

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
STUDENT_A1 = "U20250000011"
ORPHAN_1 = "U20250000031"
ORPHAN_2 = "U20250000032"

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
def tokens(client):
    _create_users([LEADER], roles=["leader"])
    _create_users([TEACHER_A], roles=["admin"], created_by=LEADER)
    _create_users([STUDENT_A1], created_by=TEACHER_A)
    _create_users([ORPHAN_1, ORPHAN_2])
    return {
        "leader": _settled_token(client, LEADER),
        "teacher_a": _settled_token(client, TEACHER_A),
    }


def test_leader_assigns_orphans_to_teacher(client, tokens):
    response = client.post(
        "/api/admin/users/assign",
        json={"ids": [ORPHAN_1, ORPHAN_2], "created_by": TEACHER_A},
        headers=bearer(tokens["leader"]),
    )
    assert response.status_code == 200
    assert response.json() == {"assigned": [ORPHAN_1, ORPHAN_2], "skipped": []}
    # 分配后在老师视角可见。
    users = client.get("/api/admin/users", headers=bearer(tokens["teacher_a"])).json()["users"]
    assert {ORPHAN_1, ORPHAN_2} <= {user["id"] for user in users}


def test_teacher_cannot_assign(client, tokens):
    response = client.post(
        "/api/admin/users/assign",
        json={"ids": [ORPHAN_1], "created_by": TEACHER_A},
        headers=bearer(tokens["teacher_a"]),
    )
    assert response.status_code == 400


def test_assign_validates_target(client, tokens):
    # 目标不存在 / 目标是学生：400。
    for target in ("U20259999999", STUDENT_A1):
        response = client.post(
            "/api/admin/users/assign",
            json={"ids": [ORPHAN_1], "created_by": target},
            headers=bearer(tokens["leader"]),
        )
        assert response.status_code == 400, target


def test_assign_skips_non_viewers_and_missing(client, tokens):
    response = client.post(
        "/api/admin/users/assign",
        json={"ids": [TEACHER_A, "U20259999998", ORPHAN_1], "created_by": TEACHER_A},
        headers=bearer(tokens["leader"]),
    )
    assert response.status_code == 200
    assert response.json()["assigned"] == [ORPHAN_1]
    assert set(response.json()["skipped"]) == {TEACHER_A, "U20259999998"}


if __name__ == "__main__":
    import sys

    sys.exit(pytest.main([__file__, "-v"]))
