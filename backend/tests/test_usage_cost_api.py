"""只读成本 API（``GET /api/usage/cost``，P1 §7）的门禁、树隔离与响应口径。

与 ``/api/usage/subjects`` 同口径：非 admin/leader → **404**（不泄露存在性）；
account 模式下老师只看自己与自己创建的学生；harness/MCP 非 token 用量**分列**
且显式 ``priced=false``；token 未知如实回报，绝不当 0 加总。

账本行直接写入 ``upstream_usage_records``（不经过 chat/LLM 路径）：本文件测的是
读端，写入路径另有 tests/test_usage_wiring.py 与 tests/test_usage_records.py。
"""

from __future__ import annotations

import asyncio
import json
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, Optional

import pytest

from app.core.usage import (
    KIND_EMBEDDING,
    KIND_LLM,
    KIND_MCP,
    SOURCE_CACHE_HIT,
    SOURCE_UNKNOWN,
    SOURCE_UPSTREAM,
)
from app.services.session_service import now_iso

VIEWER_TOKEN = "v" * 32
ADMIN_TOKEN = "a" * 32
FALLBACK_TOKEN = "f" * 32

VIEWER = {"subject": "alice", "roles": ["viewer"], "daily_quota": 50}
ADMIN = {"subject": "bob", "roles": ["admin"], "daily_quota": 0}
FALLBACK = {"subject": "emergency-admin", "roles": ["admin"], "daily_quota": 0}

LEADER = "U20260000001"
TEACHER_A = "U20260000002"
TEACHER_B = "U20260000003"
STUDENT_A1 = "U20260000011"
STUDENT_B1 = "U20260000021"

NEW_PASSWORD = "newpassword123"

RESPONSE_KEYS = {
    "days", "since", "subject", "subjects", "stage", "request_id",
    "totals", "operations", "by_stage", "by_model", "by_day",
    "unpriced_models", "unknown_entries", "cost_currency_mixed",
    "truncated", "row_limit",
}


# ---------------------------------------------------------------------------
# 造账
# ---------------------------------------------------------------------------


def _row(
    subject: Optional[str],
    *,
    request_id: str = "trace_seed",
    stage: str = "planner",
    kind: str = KIND_LLM,
    model: Optional[str] = "qwen3.8-flash",
    prompt: Optional[int] = None,
    completion: Optional[int] = None,
    total: Optional[int] = None,
    source: str = SOURCE_UPSTREAM,
    call_count: int = 1,
    cost_micros: Optional[int] = None,
    currency: Optional[str] = None,
    created_at: Optional[str] = None,
) -> Dict[str, Any]:
    return {
        "id": f"use_{uuid.uuid4().hex[:12]}",
        "request_id": request_id,
        "subject": subject,
        "session_id": None,
        "kind": kind,
        "stage": stage,
        "model": model,
        "prompt_tokens": prompt,
        "completion_tokens": completion,
        "total_tokens": total,
        "usage_source": source,
        "call_count": call_count,
        "duration_ms": None,
        "cost_micros": cost_micros,
        "currency": currency,
        "price_version": None,
        "created_at": created_at or now_iso(),
    }


def _seed(*rows: Dict[str, Any]) -> None:
    from app.services.usage_service import UsageService

    assert UsageService().record_rows(list(rows)) == len(rows)


def _days_ago(n: int) -> str:
    return (datetime.now(timezone.utc) - timedelta(days=n)).isoformat()


def bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


# ---------------------------------------------------------------------------
# fixtures
# ---------------------------------------------------------------------------


@pytest.fixture
def token_client(sqlite_db, monkeypatch):
    """token 模式：一个 viewer、一个 admin。"""
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
def account_client(sqlite_db, monkeypatch):
    monkeypatch.setenv("AUTH_MODE", "account")
    monkeypatch.setenv("API_TOKENS_JSON", json.dumps({FALLBACK_TOKEN: FALLBACK}))

    from app.config import get_settings

    get_settings.cache_clear()

    from fastapi.testclient import TestClient

    from app.main import app

    return TestClient(app)


def _create_users(ids, roles=None, created_by=None):
    from app.services.user_service import UserService

    return asyncio.run(UserService().create_users(ids, roles=roles, created_by=created_by))


def _settled_token(client, user_id: str) -> str:
    login = client.post("/api/auth/login", json={"username": user_id, "password": user_id})
    assert login.status_code == 200
    token = login.json()["token"]
    change = client.post(
        "/api/auth/change-password",
        json={"old_password": user_id, "new_password": NEW_PASSWORD},
        headers=bearer(token),
    )
    assert change.status_code == 204
    return token


@pytest.fixture
def hierarchy(account_client):
    """组长 + 两名老师 + 各自学生；返回各角色 token。"""
    _create_users([LEADER], roles=["leader"])
    _create_users([TEACHER_A, TEACHER_B], roles=["admin"], created_by=LEADER)
    _create_users([STUDENT_A1], created_by=TEACHER_A)
    _create_users([STUDENT_B1], created_by=TEACHER_B)
    return {
        "leader": _settled_token(account_client, LEADER),
        "teacher_a": _settled_token(account_client, TEACHER_A),
        "teacher_b": _settled_token(account_client, TEACHER_B),
    }


# ---------------------------------------------------------------------------
# 门禁
# ---------------------------------------------------------------------------


def test_cost_hidden_from_non_admin(token_client):
    """非 admin/leader 拿 404 而不是 403：不泄露端点存在性（与 /subjects 同口径）。"""
    response = token_client.get("/api/usage/cost", headers=bearer(VIEWER_TOKEN))
    assert response.status_code == 404
    assert response.json()["detail"] == "not found"


def test_cost_requires_auth(token_client):
    assert token_client.get("/api/usage/cost").status_code == 401


def test_cost_rejects_out_of_range_days(token_client):
    response = token_client.get("/api/usage/cost?days=0", headers=bearer(ADMIN_TOKEN))
    assert response.status_code == 422
    response = token_client.get("/api/usage/cost?days=1000", headers=bearer(ADMIN_TOKEN))
    assert response.status_code == 422


# ---------------------------------------------------------------------------
# 口径
# ---------------------------------------------------------------------------


def test_cost_separates_tokens_from_non_token_operations(token_client):
    _seed(
        _row("alice", stage="planner", prompt=1000, completion=500, total=1500,
             cost_micros=1800, currency="CNY"),
        _row("alice", stage="query_embed", kind=KIND_EMBEDDING, model="text-embedding-v4",
             prompt=200, total=200, cost_micros=100, currency="CNY"),
        _row("alice", stage="judge", total=None, source=SOURCE_UNKNOWN),
        _row("alice", stage="query_embed", kind=KIND_EMBEDDING, model="text-embedding-v4",
             total=None, source=SOURCE_CACHE_HIT, call_count=0),
        _row("alice", stage="grade_plugin", kind=KIND_MCP, model=None),
    )

    body = token_client.get("/api/usage/cost", headers=bearer(ADMIN_TOKEN)).json()

    assert set(body) == RESPONSE_KEYS, "响应只增键；改键名等于破坏既有契约"
    assert body["totals"]["entries"] == 4, "harness/MCP 不进 token 合计"
    assert body["totals"]["total_tokens"] == 1700
    assert body["totals"]["known_entries"] == 2
    assert body["totals"]["cost_micros_by_currency"] == {"CNY": 1900}
    assert body["unknown_entries"] == 1, "judge 没拿到用量，就是一笔没算清的账"
    assert body["cost_currency_mixed"] is False

    operations = body["operations"]
    assert operations == {"entries": 1, "calls": 1, "duration_ms": None, "priced": False}
    assert "cost_micros_by_currency" not in operations, "非 token 用量永不出价"

    assert {item["key"] for item in body["by_stage"]} == {
        "planner", "query_embed", "judge", "grade_plugin",
    }
    assert {item["key"] for item in body["by_model"] if item["key"]} == {
        "qwen3.8-flash", "text-embedding-v4",
    }


def test_cost_reports_unpriced_models_without_inventing_a_price(token_client):
    _seed(_row("alice", model="mystery-model", prompt=10, completion=5, total=15))

    body = token_client.get("/api/usage/cost", headers=bearer(ADMIN_TOKEN)).json()

    assert body["unpriced_models"] == ["mystery-model"]
    assert body["totals"]["cost_micros_by_currency"] == {}, "没配价就不出价"
    assert body["totals"]["total_tokens"] == 15, "但用量照报"


def test_cost_keeps_unknown_as_unknown_not_zero(token_client):
    _seed(_row("alice", stage="judge", total=None, source=SOURCE_UNKNOWN))

    totals = token_client.get("/api/usage/cost", headers=bearer(ADMIN_TOKEN)).json()["totals"]

    assert totals["total_tokens"] is None
    assert totals["known_entries"] == 0
    assert totals["calls"] == 1, "调过一次是确定的"
    assert totals["unknown_entries"] == 1


def test_cost_filters_by_stage_and_subject(token_client):
    _seed(
        _row("alice", stage="planner", total=100),
        _row("bob", stage="judge", total=7),
    )

    by_stage = token_client.get("/api/usage/cost?stage=judge", headers=bearer(ADMIN_TOKEN)).json()
    assert by_stage["stage"] == "judge"
    assert by_stage["totals"]["total_tokens"] == 7

    by_subject = token_client.get("/api/usage/cost?subject=bob", headers=bearer(ADMIN_TOKEN)).json()
    assert by_subject["subject"] == "bob"
    assert by_subject["totals"]["total_tokens"] == 7


def test_cost_filters_by_request_id(token_client):
    """按 request_id 归因：live 评测逐题看花费靠的就是这个过滤。"""
    _seed(
        _row("alice", request_id="live-eval-run1-dns", total=100, stage="planner"),
        _row("alice", request_id="live-eval-run1-dns", total=50, stage="judge"),
        _row("alice", request_id="live-eval-run1-grade", total=7, stage="grade"),
    )

    body = token_client.get(
        "/api/usage/cost?request_id=live-eval-run1-dns", headers=bearer(ADMIN_TOKEN)
    ).json()
    assert body["request_id"] == "live-eval-run1-dns", "过滤条件必须回显"
    assert body["totals"]["total_tokens"] == 150, "只算这一次请求的两次调用"


def test_cost_unknown_request_id_is_an_empty_account(token_client):
    """查不到就是空账：tokens 为 None（未知）而不是 0，不能编出「花了 0 元」。"""
    _seed(_row("alice", request_id="live-eval-run1-dns", total=100))

    body = token_client.get(
        "/api/usage/cost?request_id=live-eval-nonexistent", headers=bearer(ADMIN_TOKEN)
    ).json()
    assert body["totals"]["entries"] == 0
    assert body["totals"]["total_tokens"] is None
    assert body["totals"]["cost_micros_by_currency"] == {}


def test_cost_window_is_rolling_days(token_client):
    _seed(
        _row("alice", stage="planner", total=100),
        _row("alice", stage="planner", total=999, created_at=_days_ago(10)),
    )

    recent = token_client.get("/api/usage/cost?days=7", headers=bearer(ADMIN_TOKEN)).json()
    assert recent["days"] == 7
    assert recent["totals"]["total_tokens"] == 100

    wider = token_client.get("/api/usage/cost?days=30", headers=bearer(ADMIN_TOKEN)).json()
    assert wider["totals"]["total_tokens"] == 1099


def test_cost_empty_ledger_reports_nothing_not_zero(token_client):
    body = token_client.get("/api/usage/cost", headers=bearer(ADMIN_TOKEN)).json()
    assert body["totals"]["entries"] == 0
    assert body["totals"]["total_tokens"] is None
    assert body["totals"]["cost_micros_by_currency"] == {}
    assert body["operations"] == {"entries": 0, "calls": 0, "duration_ms": None, "priced": False}
    assert body["by_stage"] == []
    assert body["unknown_entries"] == 0
    assert body["truncated"] is False


# ---------------------------------------------------------------------------
# account 模式：树隔离
# ---------------------------------------------------------------------------


def test_cost_scoped_to_teacher_tree(account_client, hierarchy):
    _seed(
        _row(TEACHER_A, stage="planner", total=100),
        _row(STUDENT_A1, stage="planner", total=20),
        _row(STUDENT_B1, stage="planner", total=888),
    )

    response = account_client.get("/api/usage/cost", headers=bearer(hierarchy["teacher_a"]))
    assert response.status_code == 200
    body = response.json()
    assert body["subjects"] == sorted({TEACHER_A, STUDENT_A1})
    assert body["totals"]["total_tokens"] == 120, "别班学生的花费不进我的视图"
    assert body["by_model"][0]["key"] == "qwen3.8-flash"


def test_cost_returns_404_for_subject_outside_the_tree(account_client, hierarchy):
    """指名别人班的学生 → 404（连「此人有没有花钱」都不透露）。"""
    _seed(_row(STUDENT_B1, stage="planner", total=888))

    response = account_client.get(
        f"/api/usage/cost?subject={STUDENT_B1}", headers=bearer(hierarchy["teacher_a"])
    )
    assert response.status_code == 404


def test_cost_of_own_subject_is_visible(account_client, hierarchy):
    _seed(_row(STUDENT_A1, stage="planner", total=20))

    response = account_client.get(
        f"/api/usage/cost?subject={STUDENT_A1}", headers=bearer(hierarchy["teacher_a"])
    )
    assert response.status_code == 200
    assert response.json()["totals"]["total_tokens"] == 20


def test_cost_request_id_cannot_escape_the_teacher_tree(account_client, hierarchy):
    """request_id 是**收窄**而不是后门：拿来别人班某次请求的 id 只会得到空账。"""
    _seed(
        _row(STUDENT_A1, request_id="trace_a", total=20),
        _row(STUDENT_B1, request_id="trace_b", total=888),
    )

    body = account_client.get(
        "/api/usage/cost?request_id=trace_b", headers=bearer(hierarchy["teacher_a"])
    ).json()
    assert body["totals"]["entries"] == 0
    assert body["totals"]["total_tokens"] is None

    own = account_client.get(
        "/api/usage/cost?request_id=trace_a", headers=bearer(hierarchy["teacher_a"])
    ).json()
    assert own["totals"]["total_tokens"] == 20


def test_cost_sees_everything_for_leader_and_static_token(account_client, hierarchy):
    _seed(_row(STUDENT_B1, stage="planner", total=888))

    leader = account_client.get("/api/usage/cost", headers=bearer(hierarchy["leader"])).json()
    assert leader["subjects"] is None, "不受树约束时不带主体过滤"
    assert leader["totals"]["total_tokens"] == 888

    # 静态应急 token（leader-like）同样不受树约束。
    emergency = account_client.get("/api/usage/cost", headers=bearer(FALLBACK_TOKEN)).json()
    assert emergency["totals"]["total_tokens"] == 888


if __name__ == "__main__":
    import sys

    sys.exit(pytest.main([__file__, "-v"]))
