"""用户删除自己的会话（M9）。

语义：本人删除 204；他人/不存在的会话统一 404（不泄露存在性）；local 模式
沿用 load_session 的归属豁免。删除显式级联 messages/citations，
grading_submissions 行保留、session_id 置 NULL 脱钩。

复用 test_user_hierarchy.py 的 in-process ASGI + throwaway SQLite 模式。
"""
from __future__ import annotations

import asyncio

import pytest

USER = "U20250000011"
OTHER = "U20250000012"

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


def _make_client(monkeypatch, auth_mode: str):
    monkeypatch.setenv("AUTH_MODE", auth_mode)

    from app.config import get_settings

    get_settings.cache_clear()

    from fastapi.testclient import TestClient

    from app.main import app

    return TestClient(app)


@pytest.fixture
def client(sqlite_db, monkeypatch):
    return _make_client(monkeypatch, "account")


def bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def _create_users(ids):
    from app.services.user_service import UserService

    return asyncio.run(UserService().create_users(ids))


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


def _seed_session(user_id: str, with_grading: bool = False) -> str:
    """直接经 service 造一个带消息/引用（可选带成绩）的会话。"""
    from app.db import get_conn, get_database_provider
    from app.services.session_service import SessionService

    service = SessionService()
    session_id = asyncio.run(service.ensure_session(None, "seed", user_id))
    message_id = asyncio.run(service.add_message(session_id, "user", "hello"))
    ph = get_database_provider().placeholder
    with get_conn() as conn:
        conn.execute(
            f"INSERT INTO assistant_citations(id, message_id, chunk_id, score, rank) "
            f"VALUES ({ph}, {ph}, {ph}, 0.5, 1)",
            (f"cit_{session_id[-8:]}", message_id, "chunk-1"),
        )
        if with_grading:
            conn.execute(
                f"INSERT INTO grading_submissions(id, user_id, session_id, protocol, code_hash, "
                f"score, passed, total, results_json, created_at) "
                f"VALUES ({ph}, {ph}, {ph}, 'http', 'hash-x', 80, 8, 10, '{{}}', '2026-01-01')",
                (f"grade_{session_id[-8:]}", user_id, session_id),
            )
    return session_id


def _counts(session_id: str) -> dict:
    from app.db import get_conn, get_database_provider

    ph = get_database_provider().placeholder
    with get_conn() as conn:
        sessions = conn.execute(
            f"SELECT COUNT(*) AS n FROM assistant_sessions WHERE id = {ph}", (session_id,)
        ).fetchone()["n"]
        messages = conn.execute(
            f"SELECT COUNT(*) AS n FROM assistant_messages WHERE session_id = {ph}", (session_id,)
        ).fetchone()["n"]
        grading = conn.execute(
            f"SELECT session_id FROM grading_submissions WHERE id = {ph}",
            (f"grade_{session_id[-8:]}",),
        ).fetchone()
    return {"sessions": sessions, "messages": messages, "grading_session": grading}


def test_owner_deletes_own_session(client):
    _create_users([USER])
    token = _settled_token(client, USER)
    session_id = _seed_session(USER, with_grading=True)

    response = client.delete(f"/api/chat/sessions/{session_id}", headers=bearer(token))
    assert response.status_code == 204

    # 列表与加载都不可见。
    assert client.get(f"/api/chat/sessions/{session_id}", headers=bearer(token)).status_code == 404
    listing = client.get("/api/chat/sessions", headers=bearer(token)).json()["sessions"]
    assert session_id not in {item["id"] for item in listing}

    # 级联清空；成绩行保留且 session_id 脱钩为 NULL。
    counts = _counts(session_id)
    assert counts["sessions"] == 0
    assert counts["messages"] == 0
    assert counts["grading_session"] is not None
    assert counts["grading_session"]["session_id"] is None


def test_foreign_or_missing_session_404(client):
    _create_users([USER, OTHER])
    token = _settled_token(client, USER)
    foreign = _seed_session(OTHER)

    assert client.delete(f"/api/chat/sessions/{foreign}", headers=bearer(token)).status_code == 404
    assert client.delete("/api/chat/sessions/sess_nonexist", headers=bearer(token)).status_code == 404
    # 别人的会话不受影响。
    assert _counts(foreign)["sessions"] == 1


def test_local_mode_delete_skips_ownership(sqlite_db, monkeypatch):
    client = _make_client(monkeypatch, "local")
    session_id = _seed_session(OTHER)
    response = client.delete(f"/api/chat/sessions/{session_id}")
    assert response.status_code == 204
    assert _counts(session_id)["sessions"] == 0


if __name__ == "__main__":
    import sys

    sys.exit(pytest.main([__file__, "-v"]))
