"""教师成绩视图（M3）/api/grading 的路由回归测试。

进程内跑 ASGI 应用 + 一次性 SQLite 库（复用 conftest 的 sqlite_db /
环境隔离 fixture）。account 模式下静态 API_TOKENS_JSON 令牌优先命中
（core/auth.py 的应急回退顺序），因此用三个不同角色的静态令牌即可覆盖
viewer/operator/admin，无需走登录流程。
"""
from __future__ import annotations

import json

import pytest

TOKEN_VIEWER = "v" * 32
TOKEN_OPERATOR = "o" * 32
TOKEN_ADMIN = "a" * 32

# 成绩明细的种子数据：两个学生、两个协议、四个递增量级的时间戳。
# 时间戳格式与 session_service.now_iso 一致（ISO + "+00:00"），字符串序即时间序。
SEED_ROWS = [
    {"id": "grade_t1", "user_id": "student1", "protocol": "http", "score": 80, "passed": 4, "total": 5,
     "created_at": "2026-08-20T10:00:00+00:00"},
    {"id": "grade_t2", "user_id": "student1", "protocol": "dns", "score": 60, "passed": 3, "total": 5,
     "created_at": "2026-08-20T11:00:00+00:00"},
    {"id": "grade_t3", "user_id": "student1", "protocol": "http", "score": 90, "passed": 5, "total": 5,
     "created_at": "2026-08-20T12:00:00+00:00"},
    {"id": "grade_t4", "user_id": "student2", "protocol": "http", "score": 100, "passed": 5, "total": 5,
     "created_at": "2026-08-20T13:00:00+00:00"},
]

# ORDER BY created_at DESC, id DESC 的全量期望顺序。
EXPECTED_ORDER_DESC = ["grade_t4", "grade_t3", "grade_t2", "grade_t1"]


@pytest.fixture
def client(sqlite_db, monkeypatch):
    """account 模式的 TestClient，三个静态令牌分别对应 viewer/operator/admin。"""
    monkeypatch.setenv("AUTH_MODE", "account")
    monkeypatch.setenv(
        "API_TOKENS_JSON",
        json.dumps(
            {
                TOKEN_VIEWER: {"subject": "t-viewer", "roles": ["viewer"], "daily_quota": 0},
                TOKEN_OPERATOR: {"subject": "t-operator", "roles": ["operator"], "daily_quota": 0},
                TOKEN_ADMIN: {"subject": "t-admin", "roles": ["admin"], "daily_quota": 0},
            }
        ),
    )

    from app.config import get_settings

    get_settings.cache_clear()

    from fastapi.testclient import TestClient

    from app.main import app

    return TestClient(app)


def bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


def _seed_submissions(rows=SEED_ROWS) -> None:
    """直接写 grading_submissions：显式 created_at 保证排序断言确定。"""
    from app.db import get_conn, get_database_provider

    ph = get_database_provider().placeholder
    with get_conn() as conn:
        for row in rows:
            conn.execute(
                f"INSERT INTO grading_submissions"
                f"(id, user_id, session_id, protocol, code_hash, score, passed, total, results_json, created_at)"
                f" VALUES ({ph}, {ph}, {ph}, {ph}, {ph}, {ph}, {ph}, {ph}, {ph}, {ph})",
                (
                    row["id"], row["user_id"], None, row["protocol"], "hash_" + row["id"],
                    row["score"], row["passed"], row["total"], "{}", row["created_at"],
                ),
            )


def test_viewer_role_gets_404(client):
    _seed_submissions()
    for path in ("/api/grading/submissions", "/api/grading/summary"):
        assert client.get(path, headers=bearer(TOKEN_VIEWER)).status_code == 404


def test_operator_and_admin_get_200(client):
    _seed_submissions()
    for token in (TOKEN_OPERATOR, TOKEN_ADMIN):
        response = client.get("/api/grading/submissions", headers=bearer(token))
        assert response.status_code == 200
        assert [s["id"] for s in response.json()["submissions"]] == EXPECTED_ORDER_DESC
        assert client.get("/api/grading/summary", headers=bearer(token)).status_code == 200


def test_non_account_mode_gets_404(client, monkeypatch):
    """local 模式下即使隐式 admin 主体也 404（端点只在 account 模式存在）。"""
    _seed_submissions()
    monkeypatch.setenv("AUTH_MODE", "local")
    from app.config import get_settings

    get_settings.cache_clear()
    for path in ("/api/grading/submissions", "/api/grading/summary"):
        assert client.get(path).status_code == 404


def test_submissions_filter_by_user_id(client):
    _seed_submissions()
    response = client.get(
        "/api/grading/submissions", params={"user_id": "student1"}, headers=bearer(TOKEN_OPERATOR)
    )
    assert response.status_code == 200
    submissions = response.json()["submissions"]
    assert [s["id"] for s in submissions] == ["grade_t3", "grade_t2", "grade_t1"]
    assert all(s["user_id"] == "student1" for s in submissions)
    # 明细不含评测细节与代码哈希（最小暴露原则）。
    assert "results_json" not in submissions[0]
    assert "code_hash" not in submissions[0]


def test_submissions_filter_by_protocol_and_combined(client):
    _seed_submissions()
    response = client.get(
        "/api/grading/submissions", params={"protocol": "dns"}, headers=bearer(TOKEN_OPERATOR)
    )
    assert [s["id"] for s in response.json()["submissions"]] == ["grade_t2"]

    response = client.get(
        "/api/grading/submissions",
        params={"user_id": "student1", "protocol": "http"},
        headers=bearer(TOKEN_OPERATOR),
    )
    assert [s["id"] for s in response.json()["submissions"]] == ["grade_t3", "grade_t1"]


def test_submissions_pagination(client):
    _seed_submissions()
    page1 = client.get(
        "/api/grading/submissions", params={"limit": 2, "offset": 0}, headers=bearer(TOKEN_OPERATOR)
    ).json()["submissions"]
    page2 = client.get(
        "/api/grading/submissions", params={"limit": 2, "offset": 2}, headers=bearer(TOKEN_OPERATOR)
    ).json()["submissions"]
    assert [s["id"] for s in page1] == EXPECTED_ORDER_DESC[:2]
    assert [s["id"] for s in page2] == EXPECTED_ORDER_DESC[2:]
    # limit 超上限钳制到 500，不报错且返回全部。
    response = client.get(
        "/api/grading/submissions", params={"limit": 1000}, headers=bearer(TOKEN_OPERATOR)
    )
    assert response.status_code == 200
    assert len(response.json()["submissions"]) == len(SEED_ROWS)


def test_summary_aggregations(client):
    _seed_submissions()
    response = client.get("/api/grading/summary", headers=bearer(TOKEN_ADMIN))
    assert response.status_code == 200
    body = response.json()

    by_user = {row["user_id"]: row for row in body["by_user"]}
    assert set(by_user) == {"student1", "student2"}
    s1 = by_user["student1"]
    assert s1["submission_count"] == 3
    assert s1["avg_score"] == pytest.approx((80 + 60 + 90) / 3)
    assert s1["best_score"] == 90
    assert s1["last_submitted_at"] == "2026-08-20T12:00:00+00:00"
    s2 = by_user["student2"]
    assert s2["submission_count"] == 1
    assert s2["avg_score"] == pytest.approx(100.0)
    assert s2["best_score"] == 100

    by_protocol = {row["protocol"]: row for row in body["by_protocol"]}
    assert set(by_protocol) == {"http", "dns"}
    http = by_protocol["http"]
    assert http["submission_count"] == 3
    assert http["avg_score"] == pytest.approx((80 + 90 + 100) / 3)
    assert http["passed_total"] == 14
    assert http["total_total"] == 15
    assert http["student_count"] == 2
    dns = by_protocol["dns"]
    assert dns["submission_count"] == 1
    assert dns["passed_total"] == 3
    assert dns["total_total"] == 5
    assert dns["student_count"] == 1


def test_summary_empty_when_no_submissions(client):
    response = client.get("/api/grading/summary", headers=bearer(TOKEN_OPERATOR))
    assert response.status_code == 200
    assert response.json() == {"by_user": [], "by_protocol": []}
