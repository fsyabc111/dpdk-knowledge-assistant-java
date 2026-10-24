"""Auth regression tests for account mode (M6).

Runs the ASGI app in-process with a throwaway SQLite database; account
fixtures mirror test_auth.py (monkeypatch.setenv + TestClient). The
module-level login rate limiter and the resolve_token 60s cache survive
across tests in one process, so an autouse fixture resets both around
each test.
"""
from __future__ import annotations

import asyncio
import hashlib
import json
from datetime import datetime, timedelta, timezone

import pytest

FALLBACK_TOKEN = "f" * 32
FALLBACK = {"subject": "emergency-admin", "roles": ["admin"], "daily_quota": 0}

ADMIN_ID = "U20240000001"
USER_A = "U20240000002"
USER_B = "U20240000003"

# 锁定用例专用学号：账号锁定器是进程内状态，各用例须用不同学号隔离。
LOCK_USER = "U20240000101"
LOCK_UNKNOWN = "U20249990101"
# 忘记密码流程专用学号。
RESET_USER = "U20240000201"
RESET_UNKNOWN = "U20249990201"

NEW_PASSWORD = "newpassword123"


@pytest.fixture(autouse=True)
def _reset_account_state():
    """Reset the login rate limiter and the token cache around each test.

    Both are module-level singletons keyed by client IP / token hash and
    would otherwise leak across tests (every TestClient request comes from
    the same "testclient" IP, so 10 logins in an earlier test would 429 a
    later one). The per-username login lockout and the password-reset rate
    limiter are reset here for the same reason.
    """
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
    """TestClient in account auth mode; no chat endpoint is exercised here."""
    monkeypatch.setenv("AUTH_MODE", "account")
    monkeypatch.setenv("API_TOKENS_JSON", json.dumps({FALLBACK_TOKEN: FALLBACK}))

    from app.config import get_settings

    get_settings.cache_clear()

    from fastapi.testclient import TestClient

    from app.main import app

    return TestClient(app)


def bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def _create_users(ids, roles=None, daily_quota=None):
    from app.services.user_service import UserService

    return asyncio.run(
        UserService().create_users(ids, roles=roles, daily_quota=daily_quota)
    )


def _login(client, user_id: str, password: str | None = None):
    return client.post(
        "/api/auth/login",
        json={"username": user_id, "password": password if password is not None else user_id},
    )


def _login_token(client, user_id: str, password: str | None = None) -> str:
    response = _login(client, user_id, password)
    assert response.status_code == 200
    return response.json()["token"]


def _settled_token(client, user_id: str, password: str | None = None) -> str:
    """登录并立即改密，返回使用非初始密码（NEW_PASSWORD）的 token。

    must_change_password 仅作信息标志，不再拦截受保护端点；此 helper 用于
    需要"已脱离初始密码"状态的用例。
    """
    old_password = password if password is not None else user_id
    token = _login_token(client, user_id, password)
    response = client.post(
        "/api/auth/change-password",
        json={"old_password": old_password, "new_password": NEW_PASSWORD},
        headers=bearer(token),
    )
    assert response.status_code == 204
    return token


def _insert_expired_token(token: str, user_id: str) -> None:
    from app.db import get_conn, get_database_provider

    ph = get_database_provider().placeholder
    past = (datetime.now(timezone.utc) - timedelta(days=1)).isoformat()
    digest = hashlib.sha256(token.encode("utf-8")).hexdigest()
    with get_conn() as conn:
        conn.execute(
            f"INSERT INTO api_tokens(token_hash, user_id, issued_at, expires_at) "
            f"VALUES ({ph}, {ph}, {ph}, {ph})",
            (digest, user_id, past, past),
        )


def _insert_session(session_id: str, user_id: str, updated_at: str) -> None:
    from app.db import get_conn, get_database_provider

    ph = get_database_provider().placeholder
    with get_conn() as conn:
        conn.execute(
            f"INSERT INTO assistant_sessions(id, user_id, title, created_at, updated_at) "
            f"VALUES ({ph}, {ph}, {ph}, {ph}, {ph})",
            (session_id, user_id, f"session {session_id}", updated_at, updated_at),
        )


# ---------------------------------------------------------------------------
# create_users
# ---------------------------------------------------------------------------


def test_create_users_valid_id_and_initial_password_login(client):
    result = _create_users([USER_A], roles=["viewer"], daily_quota=50)
    assert result == {"created": [USER_A], "skipped": []}

    response = _login(client, USER_A)  # 初始密码 = 学号本身
    assert response.status_code == 200
    body = response.json()
    assert body["subject"] == USER_A
    assert body["roles"] == ["viewer"]
    assert body["token"]
    assert body["expires_at"]
    assert body["must_change_password"] is True

    # must_change_password 不拦截访问：受保护端点直接可用。
    token = _settled_token(client, USER_A)
    me = client.get("/api/usage/me", headers=bearer(token))
    assert me.status_code == 200
    assert me.json()["subject"] == USER_A
    assert me.json()["daily_quota"] == 50


def test_create_users_skips_malformed_and_duplicate_ids(client):
    _create_users([USER_A])
    result = _create_users(["bad-id", "U123", USER_A, USER_B])
    assert result["created"] == [USER_B]
    assert result["skipped"] == ["bad-id", "U123", USER_A]


def test_create_users_rejects_invalid_roles(client):
    with pytest.raises(ValueError):
        _create_users([USER_A], roles=["superuser"])
    with pytest.raises(ValueError):
        _create_users([USER_A], roles=["viewer", "root"])


# ---------------------------------------------------------------------------
# login
# ---------------------------------------------------------------------------


def test_login_wrong_password_and_unknown_user_are_indistinguishable(client):
    _create_users([USER_A])
    wrong_password = _login(client, USER_A, password="wrong-password")
    unknown_user = _login(client, "U20249999999")
    assert wrong_password.status_code == 401
    assert unknown_user.status_code == 401
    assert wrong_password.json() == unknown_user.json()


def test_login_disabled_user_returns_401(client):
    _create_users([USER_A])

    from app.services.user_service import UserService

    assert asyncio.run(UserService().set_enabled(USER_A, False)) is True
    response = _login(client, USER_A)
    assert response.status_code == 401
    assert response.json() == {"detail": "invalid username or password"}


def test_login_rate_limit_returns_429(client):
    _create_users([USER_A])
    # 每次失败换不同学号，避免先触发按学号的账号锁定（锁定见下方专门用例）。
    for i in range(10):
        response = _login(client, f"U20240999{i:03d}", password="wrong-password")
        assert response.status_code == 401
    response = _login(client, USER_A, password="wrong-password")
    assert response.status_code == 429
    assert "Retry-After" in response.headers


# ---------------------------------------------------------------------------
# logout / token lifecycle
# ---------------------------------------------------------------------------


def test_logout_revokes_token_immediately(client):
    _create_users([USER_A])
    token = _settled_token(client, USER_A)
    assert client.get("/api/usage/me", headers=bearer(token)).status_code == 200

    response = client.post("/api/auth/logout", headers=bearer(token))
    assert response.status_code == 204
    # 60s 缓存也必须失效：旧 token 立即 401。
    assert client.get("/api/usage/me", headers=bearer(token)).status_code == 401


def test_expired_token_returns_401(client):
    _create_users([USER_A])
    token = "expired-token-value"
    _insert_expired_token(token, USER_A)
    response = client.get("/api/usage/me", headers=bearer(token))
    assert response.status_code == 401


# ---------------------------------------------------------------------------
# change-password
# ---------------------------------------------------------------------------


def test_change_password_wrong_old_returns_400(client):
    _create_users([USER_A])
    token = _login_token(client, USER_A)
    response = client.post(
        "/api/auth/change-password",
        json={"old_password": "not-the-password", "new_password": NEW_PASSWORD},
        headers=bearer(token),
    )
    assert response.status_code == 400


def test_change_password_too_short_new_returns_400(client):
    _create_users([USER_A])
    token = _login_token(client, USER_A)
    response = client.post(
        "/api/auth/change-password",
        json={"old_password": USER_A, "new_password": "short"},
        headers=bearer(token),
    )
    assert response.status_code == 400


def test_change_password_success_rotates_credentials_and_tokens(client):
    _create_users([USER_A])
    # 先脱离初始密码，再验证改密的 token 轮换语义。
    _settled_token(client, USER_A)
    token1 = _login_token(client, USER_A, password=NEW_PASSWORD)
    token2 = _login_token(client, USER_A, password=NEW_PASSWORD)
    # 让两个 token 都进入 resolve_token 缓存，验证改密会清缓存。
    assert client.get("/api/usage/me", headers=bearer(token1)).status_code == 200
    assert client.get("/api/usage/me", headers=bearer(token2)).status_code == 200

    second_password = "anotherpassword456"
    response = client.post(
        "/api/auth/change-password",
        json={"old_password": NEW_PASSWORD, "new_password": second_password},
        headers=bearer(token1),
    )
    assert response.status_code == 204

    # 当前 token 保留可用，该用户其他 token 被吊销。
    assert client.get("/api/usage/me", headers=bearer(token1)).status_code == 200
    assert client.get("/api/usage/me", headers=bearer(token2)).status_code == 401
    # 旧密失效、新密可登录。
    assert _login(client, USER_A, password=NEW_PASSWORD).status_code == 401
    assert _login(client, USER_A, password=second_password).status_code == 200


# ---------------------------------------------------------------------------
# 会话越权与列表隔离
# ---------------------------------------------------------------------------


def test_cross_subject_session_read_returns_404(client):
    _create_users([USER_A])
    _create_users([USER_B])
    _insert_session("sess_b1", USER_B, "2026-08-20T01:00:00+00:00")

    token_a = _settled_token(client, USER_A)
    response = client.get("/api/chat/sessions/sess_b1", headers=bearer(token_a))
    assert response.status_code == 404


def test_sessions_list_only_own_ordered_by_updated_at_desc(client):
    _create_users([USER_A])
    _create_users([USER_B])
    _insert_session("sess_a1", USER_A, "2026-08-20T01:00:00+00:00")
    _insert_session("sess_a2", USER_A, "2026-08-20T03:00:00+00:00")
    _insert_session("sess_a3", USER_A, "2026-08-20T02:00:00+00:00")
    _insert_session("sess_b1", USER_B, "2026-08-20T04:00:00+00:00")

    token_a = _settled_token(client, USER_A)
    response = client.get("/api/chat/sessions", headers=bearer(token_a))
    assert response.status_code == 200
    ids = [item["id"] for item in response.json()["sessions"]]
    assert ids == ["sess_a2", "sess_a3", "sess_a1"]


# ---------------------------------------------------------------------------
# admin 端点
# ---------------------------------------------------------------------------


def test_admin_endpoints_hidden_from_viewer(client):
    _create_users([USER_A], roles=["viewer"])
    token = _settled_token(client, USER_A)
    response = client.get("/api/admin/users", headers=bearer(token))
    assert response.status_code == 404


def test_admin_create_user_then_initial_password_login(client):
    _create_users([ADMIN_ID], roles=["admin"])
    admin_token = _settled_token(client, ADMIN_ID)

    response = client.post(
        "/api/admin/users",
        json={"ids": [USER_A, "bad-id"]},
        headers=bearer(admin_token),
    )
    assert response.status_code == 200
    assert response.json() == {"created": [USER_A], "skipped": ["bad-id"]}
    assert _login(client, USER_A).status_code == 200


def test_admin_reset_password_restores_initial_password(client):
    # M8 起全权管理是组长语义：admin（老师）只能管自己创建的学生。
    _create_users([ADMIN_ID], roles=["leader"])
    _create_users([USER_A])
    user_token = _login_token(client, USER_A)
    response = client.post(
        "/api/auth/change-password",
        json={"old_password": USER_A, "new_password": NEW_PASSWORD},
        headers=bearer(user_token),
    )
    assert response.status_code == 204

    admin_token = _settled_token(client, ADMIN_ID)
    response = client.post(
        f"/api/admin/users/{USER_A}/reset-password", headers=bearer(admin_token)
    )
    assert response.status_code == 204
    assert _login(client, USER_A, password=NEW_PASSWORD).status_code == 401
    response = _login(client, USER_A)  # 初始密码 = 学号恢复可用
    assert response.status_code == 200
    # 重置回初始密码后 must_change_password 重新置位（管理面板显示"待改密"）。
    assert response.json()["must_change_password"] is True


def test_admin_disable_user_revokes_tokens_immediately(client):
    _create_users([ADMIN_ID], roles=["leader"])
    _create_users([USER_A])
    user_token = _settled_token(client, USER_A)
    assert client.get("/api/usage/me", headers=bearer(user_token)).status_code == 200

    admin_token = _settled_token(client, ADMIN_ID)
    response = client.post(
        f"/api/admin/users/{USER_A}/status",
        json={"enabled": False},
        headers=bearer(admin_token),
    )
    assert response.status_code == 204
    assert client.get("/api/usage/me", headers=bearer(user_token)).status_code == 401
    assert _login(client, USER_A).status_code == 401


def test_admin_patch_daily_quota_takes_effect(client):
    _create_users([ADMIN_ID], roles=["leader"])
    _create_users([USER_A])
    admin_token = _settled_token(client, ADMIN_ID)

    response = client.patch(
        f"/api/admin/users/{USER_A}",
        json={"daily_quota": 42},
        headers=bearer(admin_token),
    )
    assert response.status_code == 200
    assert response.json()["daily_quota"] == 42

    listing = client.get("/api/admin/users", headers=bearer(admin_token))
    assert listing.status_code == 200
    users = {item["id"]: item for item in listing.json()["users"]}
    assert users[USER_A]["daily_quota"] == 42


# ---------------------------------------------------------------------------
# must_change_password 信息标志（内网部署不强制改密，仅透出状态）
# ---------------------------------------------------------------------------


def test_must_change_password_flag_is_informational(client):
    _create_users([USER_A])
    response = _login(client, USER_A)
    assert response.status_code == 200
    assert response.json()["must_change_password"] is True
    token = response.json()["token"]

    # 未改密也可正常访问受保护端点（内网部署不做强制改密拦截）。
    protected = client.get("/api/usage/me", headers=bearer(token))
    assert protected.status_code == 200

    change = client.post(
        "/api/auth/change-password",
        json={"old_password": USER_A, "new_password": NEW_PASSWORD},
        headers=bearer(token),
    )
    assert change.status_code == 204
    assert client.get("/api/usage/me", headers=bearer(token)).status_code == 200
    assert _login(client, USER_A, password=NEW_PASSWORD).json()["must_change_password"] is False


def test_change_password_equal_to_user_id_returns_400(client):
    _create_users([USER_A])
    token = _settled_token(client, USER_A)
    response = client.post(
        "/api/auth/change-password",
        json={"old_password": NEW_PASSWORD, "new_password": USER_A},
        headers=bearer(token),
    )
    assert response.status_code == 400


def test_change_password_equal_to_old_returns_400(client):
    _create_users([USER_A])
    token = _settled_token(client, USER_A)
    response = client.post(
        "/api/auth/change-password",
        json={"old_password": NEW_PASSWORD, "new_password": NEW_PASSWORD},
        headers=bearer(token),
    )
    assert response.status_code == 400


# ---------------------------------------------------------------------------
# 账号锁定（按学号，存在/不存在一视同仁）
# ---------------------------------------------------------------------------


def test_login_lockout_after_five_failures(client):
    _create_users([LOCK_USER])
    for _ in range(5):
        response = _login(client, LOCK_USER, password="wrong-password")
        assert response.status_code == 401
    # 第 6 次起 429 并带 Retry-After；锁定期间正确密码同样被拒。
    locked = _login(client, LOCK_USER, password="wrong-password")
    assert locked.status_code == 429
    assert "Retry-After" in locked.headers
    correct = _login(client, LOCK_USER)  # 初始密码 = 学号
    assert correct.status_code == 429
    assert correct.json() == locked.json()


def test_login_lockout_applies_to_unknown_users_identically(client):
    for _ in range(5):
        response = _login(client, LOCK_UNKNOWN, password="wrong-password")
        assert response.status_code == 401
    locked = _login(client, LOCK_UNKNOWN, password="wrong-password")
    assert locked.status_code == 429
    assert "Retry-After" in locked.headers


# ---------------------------------------------------------------------------
# 管理员 set-password
# ---------------------------------------------------------------------------


def test_admin_set_password_allows_login_and_revokes_tokens(client):
    _create_users([ADMIN_ID], roles=["leader"])
    _create_users([USER_A])
    old_token = _settled_token(client, USER_A)
    admin_token = _settled_token(client, ADMIN_ID)

    response = client.post(
        f"/api/admin/users/{USER_A}/set-password",
        json={"new_password": "adminsetpassword1"},
        headers=bearer(admin_token),
    )
    assert response.status_code == 204

    # 旧 token 全部失效；新密码可登录且仍强制改密。
    assert client.get("/api/usage/me", headers=bearer(old_token)).status_code == 401
    assert _login(client, USER_A, password=NEW_PASSWORD).status_code == 401
    login = _login(client, USER_A, password="adminsetpassword1")
    assert login.status_code == 200
    assert login.json()["must_change_password"] is True


def test_admin_set_password_equal_to_user_id_returns_400(client):
    _create_users([ADMIN_ID], roles=["leader"])
    _create_users([USER_A])
    admin_token = _settled_token(client, ADMIN_ID)
    response = client.post(
        f"/api/admin/users/{USER_A}/set-password",
        json={"new_password": USER_A},
        headers=bearer(admin_token),
    )
    assert response.status_code == 400


def test_admin_set_password_unknown_user_returns_404(client):
    _create_users([ADMIN_ID], roles=["leader"])
    admin_token = _settled_token(client, ADMIN_ID)
    response = client.post(
        "/api/admin/users/U20249999999/set-password",
        json={"new_password": "adminsetpassword1"},
        headers=bearer(admin_token),
    )
    assert response.status_code == 404


def test_admin_set_password_hidden_from_viewer(client):
    _create_users([USER_A], roles=["viewer"])
    _create_users([USER_B])
    viewer_token = _settled_token(client, USER_A)
    response = client.post(
        f"/api/admin/users/{USER_B}/set-password",
        json={"new_password": "adminsetpassword1"},
        headers=bearer(viewer_token),
    )
    assert response.status_code == 404


# ---------------------------------------------------------------------------
# 忘记密码：申请-审批-令牌改密
# ---------------------------------------------------------------------------


def test_password_reset_request_flow_end_to_end(client):
    _create_users([ADMIN_ID], roles=["leader"])
    _create_users([RESET_USER])
    old_token = _settled_token(client, RESET_USER)

    # 匿名提交：存在/不存在的学号统一 202；重复提交幂等；格式非法 400。
    assert client.post(
        "/api/auth/password-reset-requests", json={"username": RESET_USER}
    ).status_code == 202
    assert client.post(
        "/api/auth/password-reset-requests", json={"username": RESET_UNKNOWN}
    ).status_code == 202
    assert client.post(
        "/api/auth/password-reset-requests", json={"username": RESET_USER}
    ).status_code == 202
    assert client.post(
        "/api/auth/password-reset-requests", json={"username": "bad-id"}
    ).status_code == 400

    admin_token = _settled_token(client, ADMIN_ID)
    listing = client.get("/api/admin/password-reset-requests", headers=bearer(admin_token))
    assert listing.status_code == 200
    requests = listing.json()["requests"]
    # 幂等：同学号重复提交只占一行。
    assert {item["user_id"] for item in requests} == {RESET_USER, RESET_UNKNOWN}
    assert len(requests) == 2
    by_user = {item["user_id"]: item["id"] for item in requests}

    # 用户不存在的申请审批 → 404，不签发令牌。
    assert client.post(
        f"/api/admin/password-reset-requests/{by_user[RESET_UNKNOWN]}/approve",
        headers=bearer(admin_token),
    ).status_code == 404

    # 审批通过返回一次性令牌；非 pending 申请不能重复审批。
    approved = client.post(
        f"/api/admin/password-reset-requests/{by_user[RESET_USER]}/approve",
        headers=bearer(admin_token),
    )
    assert approved.status_code == 200
    reset_token = approved.json()["reset_token"]
    assert approved.json()["expires_at"]
    assert client.post(
        f"/api/admin/password-reset-requests/{by_user[RESET_USER]}/approve",
        headers=bearer(admin_token),
    ).status_code == 404

    # 学生凭令牌改密：旧 token 失效、must_change_password 清除、新密码可登录。
    confirm = client.post(
        "/api/auth/password-reset",
        json={"token": reset_token, "new_password": "resetpassword123"},
    )
    assert confirm.status_code == 204
    assert client.get("/api/usage/me", headers=bearer(old_token)).status_code == 401
    login = _login(client, RESET_USER, password="resetpassword123")
    assert login.status_code == 200
    assert login.json()["must_change_password"] is False

    # 令牌一次性：二次使用 400；无效令牌 400。
    assert client.post(
        "/api/auth/password-reset",
        json={"token": reset_token, "new_password": "resetpassword456"},
    ).status_code == 400
    assert client.post(
        "/api/auth/password-reset",
        json={"token": "no-such-token", "new_password": "resetpassword456"},
    ).status_code == 400


def test_password_reset_request_reject_flow(client):
    _create_users([ADMIN_ID], roles=["leader"])
    _create_users([RESET_USER])
    assert client.post(
        "/api/auth/password-reset-requests", json={"username": RESET_USER}
    ).status_code == 202

    admin_token = _settled_token(client, ADMIN_ID)
    request_id = client.get(
        "/api/admin/password-reset-requests", headers=bearer(admin_token)
    ).json()["requests"][0]["id"]

    assert client.post(
        f"/api/admin/password-reset-requests/{request_id}/reject", headers=bearer(admin_token)
    ).status_code == 204
    # 驳回后不再签发令牌；不存在的申请 404；pending 列表清空。
    assert client.post(
        f"/api/admin/password-reset-requests/{request_id}/approve", headers=bearer(admin_token)
    ).status_code == 404
    assert client.post(
        "/api/admin/password-reset-requests/no-such-id/reject", headers=bearer(admin_token)
    ).status_code == 404
    assert client.get(
        "/api/admin/password-reset-requests", headers=bearer(admin_token)
    ).json()["requests"] == []


def test_password_reset_admin_endpoints_hidden_from_viewer(client):
    _create_users([USER_A], roles=["viewer"])
    viewer_token = _settled_token(client, USER_A)
    assert client.get(
        "/api/admin/password-reset-requests", headers=bearer(viewer_token)
    ).status_code == 404
    assert client.post(
        "/api/admin/password-reset-requests/some-id/approve", headers=bearer(viewer_token)
    ).status_code == 404
    assert client.post(
        "/api/admin/password-reset-requests/some-id/reject", headers=bearer(viewer_token)
    ).status_code == 404


# ---------------------------------------------------------------------------
# 静态 token 兜底
# ---------------------------------------------------------------------------


def test_static_fallback_token_still_works_in_account_mode(client):
    response = client.get("/api/usage/me", headers=bearer(FALLBACK_TOKEN))
    assert response.status_code == 200
    assert response.json()["subject"] == "emergency-admin"
    assert response.json()["roles"] == ["admin"]


if __name__ == "__main__":
    import sys

    sys.exit(pytest.main([__file__, "-v"]))
