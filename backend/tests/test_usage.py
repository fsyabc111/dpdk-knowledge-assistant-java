"""Usage dashboard endpoint tests (M5).

Token-mode suite mirrors test_auth.py: throwaway SQLite database, two
configured tokens (viewer/admin), counters seeded directly into
usage_counters so no chat/LLM path is involved.
"""
from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone

import pytest

VIEWER_TOKEN = "v" * 32
ADMIN_TOKEN = "a" * 32

VIEWER = {"subject": "alice", "roles": ["viewer"], "daily_quota": 50}
ADMIN = {"subject": "bob", "roles": ["admin"], "daily_quota": 0}


def _seed_counter(subject: str, day: str, count: int) -> None:
    from app.db import get_conn, get_database_provider

    ph = get_database_provider().placeholder
    with get_conn() as conn:
        conn.execute(
            f"INSERT INTO usage_counters(subject, day, chat_count) VALUES ({ph}, {ph}, {ph})",
            (subject, day, count),
        )


def _today() -> str:
    return datetime.now(timezone.utc).date().isoformat()


def _days_ago(n: int) -> str:
    return (datetime.now(timezone.utc).date() - timedelta(days=n)).isoformat()


@pytest.fixture
def client(sqlite_db, monkeypatch):
    """TestClient in token auth mode with a viewer and an admin token."""
    monkeypatch.setenv("AUTH_MODE", "token")
    monkeypatch.setenv(
        "API_TOKENS_JSON",
        json.dumps({VIEWER_TOKEN: VIEWER, ADMIN_TOKEN: ADMIN}),
    )

    from app.config import get_settings

    get_settings.cache_clear()

    from fastapi.testclient import TestClient

    from app.main import app

    return TestClient(app)


@pytest.fixture
def local_client(sqlite_db, monkeypatch):
    """TestClient in local auth mode (single implicit admin principal)."""
    monkeypatch.setenv("AUTH_MODE", "local")

    from app.config import get_settings

    get_settings.cache_clear()

    from fastapi.testclient import TestClient

    from app.main import app

    return TestClient(app)


def bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def test_usage_me_requires_token(client):
    response = client.get("/api/usage/me")
    assert response.status_code == 401


def test_usage_me_returns_quota_and_recent_days(client):
    _seed_counter("alice", _today(), 3)
    _seed_counter("alice", _days_ago(2), 5)
    _seed_counter("alice", _days_ago(9), 7)  # 超出最近 7 天窗口

    response = client.get("/api/usage/me", headers=bearer(VIEWER_TOKEN))
    assert response.status_code == 200
    body = response.json()
    assert body["subject"] == "alice"
    assert body["roles"] == ["viewer"]
    assert body["daily_quota"] == 50
    assert body["today"] == {"day": _today(), "chat_count": 3}

    recent = body["recent_days"]
    assert len(recent) == 7
    assert [item["day"] for item in recent] == sorted(item["day"] for item in recent)
    assert recent[0]["day"] == _days_ago(6)
    assert recent[-1] == {"day": _today(), "chat_count": 3}
    counts = {item["day"]: item["chat_count"] for item in recent}
    assert counts[_days_ago(2)] == 5
    # 无记录的日子补 0，9 天前的记录不进窗口
    assert counts[_days_ago(1)] == 0
    assert _days_ago(9) not in counts


def test_usage_me_zero_fill_without_any_records(client):
    response = client.get("/api/usage/me", headers=bearer(VIEWER_TOKEN))
    assert response.status_code == 200
    body = response.json()
    assert body["today"]["chat_count"] == 0
    assert all(item["chat_count"] == 0 for item in body["recent_days"])


def test_subjects_forbidden_for_non_admin_returns_404(client):
    response = client.get("/api/usage/subjects", headers=bearer(VIEWER_TOKEN))
    assert response.status_code == 404
    assert response.json()["detail"] == "not found"


def test_subjects_lists_configured_and_legacy_subjects(client):
    _seed_counter("alice", _today(), 3)
    _seed_counter("alice", _days_ago(4), 2)
    _seed_counter("carol", _days_ago(1), 9)  # 历史遗留 subject，不在 token 配置里

    response = client.get("/api/usage/subjects", headers=bearer(ADMIN_TOKEN))
    assert response.status_code == 200
    subjects = {item["subject"]: item for item in response.json()["subjects"]}
    assert set(subjects) == {"alice", "bob", "carol"}

    alice = subjects["alice"]
    assert alice["roles"] == ["viewer"]
    assert alice["daily_quota"] == 50
    assert alice["today_count"] == 3
    assert alice["total_count"] == 5
    assert alice["last_active_day"] == _today()

    bob = subjects["bob"]
    assert bob["today_count"] == 0
    assert bob["total_count"] == 0
    assert bob["last_active_day"] is None

    carol = subjects["carol"]
    assert carol["roles"] == []
    assert carol["daily_quota"] == 200  # llm_daily_quota_default
    assert carol["today_count"] == 0
    assert carol["total_count"] == 9
    assert carol["last_active_day"] == _days_ago(1)

    # 排序：today_count 降序，subject 升序
    ordered = [item["subject"] for item in response.json()["subjects"]]
    assert ordered == sorted(subjects, key=lambda s: (-subjects[s]["today_count"], s))


def test_subjects_merges_multiple_tokens_of_same_subject(sqlite_db, monkeypatch):
    extra_token = "e" * 32
    monkeypatch.setenv("AUTH_MODE", "token")
    monkeypatch.setenv(
        "API_TOKENS_JSON",
        json.dumps({
            VIEWER_TOKEN: VIEWER,
            extra_token: {"subject": "alice", "roles": ["operator"], "daily_quota": 0},
            ADMIN_TOKEN: ADMIN,
        }),
    )

    from app.config import get_settings

    get_settings.cache_clear()

    from fastapi.testclient import TestClient

    from app.main import app

    client = TestClient(app)
    response = client.get("/api/usage/subjects", headers=bearer(ADMIN_TOKEN))
    assert response.status_code == 200
    subjects = {item["subject"]: item for item in response.json()["subjects"]}
    assert set(subjects) == {"alice", "bob"}
    # roles 取并集；任一 token 配额为 0（不限额）则合并后为 0
    assert subjects["alice"]["roles"] == ["operator", "viewer"]
    assert subjects["alice"]["daily_quota"] == 0


def test_local_mode_me_returns_local_subject(local_client):
    _seed_counter("local", _today(), 2)
    response = local_client.get("/api/usage/me")
    assert response.status_code == 200
    body = response.json()
    assert body["subject"] == "local"
    assert body["roles"] == ["admin"]
    assert body["daily_quota"] == 0
    assert body["today"]["chat_count"] == 2


def test_local_mode_subjects_only_contains_local(local_client):
    _seed_counter("local", _today(), 2)
    _seed_counter("legacy", _days_ago(3), 4)
    response = local_client.get("/api/usage/subjects")
    assert response.status_code == 200
    subjects = response.json()["subjects"]
    assert len(subjects) == 1
    assert subjects[0]["subject"] == "local"
    assert subjects[0]["daily_quota"] == 0
    assert subjects[0]["today_count"] == 2
    # legacy subject 的计数不应并入 local
    assert subjects[0]["total_count"] == 2


@pytest.fixture
def account_client(sqlite_db, monkeypatch):
    """TestClient in account auth mode (M6): subjects come from the users table."""
    monkeypatch.setenv("AUTH_MODE", "account")

    from app.config import get_settings

    get_settings.cache_clear()

    from app.api.auth import _login_rate_limiter

    _login_rate_limiter._buckets.clear()

    from fastapi.testclient import TestClient

    from app.main import app

    return TestClient(app)


def test_account_mode_subjects_come_from_users_table(account_client):
    import asyncio

    from app.services.user_service import UserService

    # M8 起全量 subject 视图是组长语义（admin=老师只看自己创建的学生）。
    asyncio.run(UserService().create_users(["U20240000001"], roles=["leader"], daily_quota=0))
    asyncio.run(UserService().create_users(["U20240000002"], roles=["viewer"], daily_quota=33))
    _seed_counter("U20240000002", _today(), 4)
    _seed_counter("legacy", _days_ago(2), 6)  # 不在 users 表中的历史遗留 subject

    login = account_client.post(
        "/api/auth/login", json={"username": "U20240000001", "password": "U20240000001"}
    )
    assert login.status_code == 200
    token = login.json()["token"]

    # 新建账号带 must_change_password，先改密解除 403 拦截。
    change = account_client.post(
        "/api/auth/change-password",
        json={"old_password": "U20240000001", "new_password": "newpassword123"},
        headers=bearer(token),
    )
    assert change.status_code == 204

    response = account_client.get("/api/usage/subjects", headers=bearer(token))
    assert response.status_code == 200
    subjects = {item["subject"]: item for item in response.json()["subjects"]}
    assert set(subjects) == {"U20240000001", "U20240000002", "legacy"}

    admin = subjects["U20240000001"]
    assert admin["roles"] == ["leader"]
    assert admin["daily_quota"] == 0

    viewer = subjects["U20240000002"]
    assert viewer["roles"] == ["viewer"]
    assert viewer["daily_quota"] == 33
    assert viewer["today_count"] == 4
    assert viewer["total_count"] == 4
    assert viewer["last_active_day"] == _today()

    # 历史遗留 subject：roles 置空，配额取全局默认值
    assert subjects["legacy"]["roles"] == []
    assert subjects["legacy"]["daily_quota"] == 200
    assert subjects["legacy"]["total_count"] == 6


if __name__ == "__main__":
    import sys

    sys.exit(pytest.main([__file__, "-v"]))
