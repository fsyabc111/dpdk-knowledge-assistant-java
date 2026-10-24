"""作业管理（/api/assignments）的路由回归测试。

模式参照 test_grading_api.py / test_user_delete.py：进程内 ASGI + 一次性
SQLite 库 + account 模式。树隔离需要真实的 users 树（静态 token 主体按
grading.py 口径豁免隔离），因此老师/学生用 UserService.create_users 建号、
登录拿 token；viewer/operator 用静态 token 即可。

HarnessMcpClient.call_tool 一律 monkeypatch 掉，禁止真实网络；评测分数经
HARNESS_STATE 可变字典按用例调整。
"""
from __future__ import annotations

import asyncio
import csv
import io
import json
from datetime import datetime, timedelta, timezone

import pytest

TOKEN_VIEWER = "v" * 32
TOKEN_OPERATOR = "o" * 32

LEADER = "U20250000001"
TEACHER_A = "U20250000002"
TEACHER_B = "U20250000003"
STUDENT_A1 = "U20250000011"
STUDENT_B1 = "U20250000021"
STUDENT_FREE = "U20250000031"  # 未分配（created_by NULL）

# 评测结果（harness payload）的可变载体；各用例按需覆写 score。
HARNESS_STATE = {"score": 80}

FUTURE_DEADLINE = (datetime.now(timezone.utc) + timedelta(days=7)).isoformat()
PAST_DEADLINE = (datetime.now(timezone.utc) - timedelta(days=1)).isoformat()


@pytest.fixture(autouse=True)
def _reset_account_state():
    from app.api.auth import _login_lockout, _login_rate_limiter, _reset_rate_limiter
    from app.services.user_service import clear_token_cache

    _login_rate_limiter._buckets.clear()
    _reset_rate_limiter._buckets.clear()
    _login_lockout._failures.clear()
    _login_lockout._locked_until.clear()
    clear_token_cache()
    HARNESS_STATE["score"] = 80
    yield
    _login_rate_limiter._buckets.clear()
    _reset_rate_limiter._buckets.clear()
    _login_lockout._failures.clear()
    _login_lockout._locked_until.clear()
    clear_token_cache()


async def _fake_call_tool(self, tool_name, arguments):
    """Harness MCP 假实现：grade_plugin 返回构造结果，list_protocols 返回两协议。"""
    if tool_name == "grade_plugin":
        score = HARNESS_STATE["score"]
        return {
            "ok": True,
            "protocol": arguments.get("protocol"),
            "score": score,
            "passed": round(score / 20),
            "cases_total": 5,
            "results": [
                {"name": f"case{i}", "passed": i <= round(score / 20)} for i in range(1, 6)
            ],
        }
    if tool_name == "list_protocols":
        return {
            "ok": True,
            "protocols": [
                {"name": "dns", "cases": 5, "categories": {"basic": 5}},
                {"name": "http", "cases": 5, "categories": {"basic": 5}},
            ],
        }
    return {"ok": False, "error": "unknown_tool", "detail": tool_name}


@pytest.fixture
def client(sqlite_db, monkeypatch):
    monkeypatch.setenv("AUTH_MODE", "account")
    monkeypatch.setenv(
        "API_TOKENS_JSON",
        json.dumps(
            {
                TOKEN_VIEWER: {"subject": "t-viewer", "roles": ["viewer"], "daily_quota": 0},
                TOKEN_OPERATOR: {"subject": "t-operator", "roles": ["operator"], "daily_quota": 0},
            }
        ),
    )

    from app.agent.mcp_client import HarnessMcpClient

    monkeypatch.setattr(HarnessMcpClient, "call_tool", _fake_call_tool)

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


def _login(client, user_id: str) -> str:
    response = client.post("/api/auth/login", json={"username": user_id, "password": user_id})
    assert response.status_code == 200
    return response.json()["token"]


@pytest.fixture
def tokens(client):
    """账号树：leader → teacher A/B → 各自学生；外加一个未分配学生。"""
    _create_users([LEADER], roles=["leader"])
    _create_users([TEACHER_A, TEACHER_B], roles=["admin"], created_by=LEADER)
    _create_users([STUDENT_A1], created_by=TEACHER_A)
    _create_users([STUDENT_B1], created_by=TEACHER_B)
    _create_users([STUDENT_FREE])
    return {
        "leader": _login(client, LEADER),
        "teacher_a": _login(client, TEACHER_A),
        "teacher_b": _login(client, TEACHER_B),
        "student_a1": _login(client, STUDENT_A1),
        "student_b1": _login(client, STUDENT_B1),
        "student_free": _login(client, STUDENT_FREE),
    }


def _create_assignment(client, token, *, title="作业一", deadline=FUTURE_DEADLINE, items=None):
    response = client.post(
        "/api/assignments",
        json={
            "title": title,
            "description": "描述",
            "deadline": deadline,
            "items": items or [{"protocol": "dns", "points": 20}, {"protocol": "http", "points": 30}],
        },
        headers=bearer(token),
    )
    assert response.status_code == 201, response.text
    return response.json()


def _submit(client, token, assignment_id, protocol="dns", code="int main() { return 0; }"):
    return client.post(
        f"/api/assignments/{assignment_id}/submit",
        json={"protocol": protocol, "code": code},
        headers=bearer(token),
    )


# ----------------------------------------------------------------------
# 守卫：认证模式与角色
# ----------------------------------------------------------------------


def test_non_account_mode_gets_404(client, monkeypatch):
    monkeypatch.setenv("AUTH_MODE", "local")
    from app.config import get_settings

    get_settings.cache_clear()
    for method, path in [
        ("get", "/api/assignments"),
        ("post", "/api/assignments"),
        ("get", "/api/assignments/protocols"),
    ]:
        assert getattr(client, method)(path).status_code == 404


def test_viewer_role_cannot_manage_404(client, tokens):
    assignment = _create_assignment(client, tokens["teacher_a"])
    aid = assignment["id"]
    viewer = bearer(TOKEN_VIEWER)
    assert client.post("/api/assignments", json={}, headers=viewer).status_code == 404
    assert client.get("/api/assignments/protocols", headers=viewer).status_code == 404
    assert client.patch(f"/api/assignments/{aid}", json={}, headers=viewer).status_code == 404
    assert client.post(f"/api/assignments/{aid}/withdraw", headers=viewer).status_code == 404
    assert client.post(f"/api/assignments/{aid}/reactivate", headers=viewer).status_code == 404
    assert client.get(f"/api/assignments/{aid}/grades", headers=viewer).status_code == 404
    assert client.get(f"/api/assignments/{aid}/export", headers=viewer).status_code == 404


def test_operator_can_view_but_not_manage(client, tokens):
    assignment = _create_assignment(client, tokens["teacher_a"])
    aid = assignment["id"]
    operator = bearer(TOKEN_OPERATOR)
    # operator 可列表/详情/成绩/导出（作业管理页的成绩视角）。
    assert client.get("/api/assignments", headers=operator).status_code == 200
    assert client.get(f"/api/assignments/{aid}", headers=operator).status_code == 200
    assert client.get(f"/api/assignments/{aid}/grades", headers=operator).status_code == 200
    assert client.get(f"/api/assignments/{aid}/export", headers=operator).status_code == 200
    # 但不能发布/修改/撤回。
    assert client.post("/api/assignments", json={}, headers=operator).status_code == 404
    assert client.patch(f"/api/assignments/{aid}", json={}, headers=operator).status_code == 404
    assert client.post(f"/api/assignments/{aid}/withdraw", headers=operator).status_code == 404


# ----------------------------------------------------------------------
# CRUD 与可见性
# ----------------------------------------------------------------------


def test_create_scope_by_role(client, tokens):
    teacher_assignment = _create_assignment(client, tokens["teacher_a"])
    assert teacher_assignment["scope"] == "class"
    assert teacher_assignment["created_by"] == TEACHER_A
    assert teacher_assignment["status"] == "active"
    assert teacher_assignment["id"].startswith("asg_")
    assert teacher_assignment["items"] == [
        {"protocol": "dns", "points": 20},
        {"protocol": "http", "points": 30},
    ]
    leader_assignment = _create_assignment(client, tokens["leader"], title="组长作业")
    assert leader_assignment["scope"] == "all"


def test_tree_isolation_visibility(client, tokens):
    teacher_assignment = _create_assignment(client, tokens["teacher_a"])
    leader_assignment = _create_assignment(client, tokens["leader"], title="组长作业")

    def student_ids(token):
        response = client.get("/api/assignments", headers=bearer(token))
        assert response.status_code == 200
        return {a["id"] for a in response.json()["assignments"]}

    # 本班学生：老师作业 + 组长作业都可见。
    assert student_ids(tokens["student_a1"]) == {teacher_assignment["id"], leader_assignment["id"]}
    # 别班学生与未分配学生：只见组长作业。
    assert student_ids(tokens["student_b1"]) == {leader_assignment["id"]}
    assert student_ids(tokens["student_free"]) == {leader_assignment["id"]}

    # 别班学生取详情/提交老师 A 的作业 → 404（不泄露存在性）。
    assert (
        client.get(
            f"/api/assignments/{teacher_assignment['id']}",
            headers=bearer(tokens["student_b1"]),
        ).status_code
        == 404
    )
    assert _submit(client, tokens["student_b1"], teacher_assignment["id"]).status_code == 404


def test_manager_list_scoping(client, tokens):
    a1 = _create_assignment(client, tokens["teacher_a"], title="A 的作业")
    _create_assignment(client, tokens["teacher_b"], title="B 的作业")
    _create_assignment(client, tokens["leader"], title="组长作业")

    response = client.get("/api/assignments", headers=bearer(tokens["teacher_a"]))
    titles = {a["title"] for a in response.json()["assignments"]}
    assert titles == {"A 的作业"}

    response = client.get("/api/assignments", headers=bearer(tokens["leader"]))
    titles = {a["title"] for a in response.json()["assignments"]}
    assert titles == {"A 的作业", "B 的作业", "组长作业"}

    # 提交统计：A1 提交一次后，teacher A 的列表里该作业 submission_count=1。
    _submit(client, tokens["student_a1"], a1["id"])
    response = client.get("/api/assignments", headers=bearer(tokens["teacher_a"]))
    row = next(a for a in response.json()["assignments"] if a["id"] == a1["id"])
    assert row["submission_count"] == 1
    assert row["student_count"] == 1


def test_update_assignment_replaces_items_and_permission(client, tokens):
    assignment = _create_assignment(client, tokens["teacher_a"])
    aid = assignment["id"]

    # 非创建者（别的老师）→ 403；创建者与 leader → 200。
    response = client.patch(
        f"/api/assignments/{aid}", json={"title": "改名"}, headers=bearer(tokens["teacher_b"])
    )
    assert response.status_code == 403
    response = client.patch(
        f"/api/assignments/{aid}",
        json={"title": "改名", "items": [{"protocol": "dns", "points": 40}]},
        headers=bearer(tokens["teacher_a"]),
    )
    assert response.status_code == 200
    body = response.json()
    assert body["title"] == "改名"
    assert body["items"] == [{"protocol": "dns", "points": 40}]

    # leader 可改任意作业。
    response = client.patch(
        f"/api/assignments/{aid}", json={"description": "组长改的"}, headers=bearer(tokens["leader"])
    )
    assert response.status_code == 200
    assert response.json()["description"] == "组长改的"

    # 不存在的作业 → 404。
    assert (
        client.patch(
            "/api/assignments/asg_missing", json={"title": "x"}, headers=bearer(tokens["leader"])
        ).status_code
        == 404
    )


def test_withdraw_and_reactivate(client, tokens):
    assignment = _create_assignment(client, tokens["teacher_a"])
    aid = assignment["id"]

    response = client.post(f"/api/assignments/{aid}/withdraw", headers=bearer(tokens["teacher_a"]))
    assert response.status_code == 200
    assert response.json()["status"] == "withdrawn"

    # 撤回后：学生列表不可见、详情 404、提交 404。
    response = client.get("/api/assignments", headers=bearer(tokens["student_a1"]))
    assert aid not in {a["id"] for a in response.json()["assignments"]}
    assert client.get(f"/api/assignments/{aid}", headers=bearer(tokens["student_a1"])).status_code == 404
    assert _submit(client, tokens["student_a1"], aid).status_code == 404

    # 恢复后可提交。
    response = client.post(f"/api/assignments/{aid}/reactivate", headers=bearer(tokens["teacher_a"]))
    assert response.status_code == 200
    assert response.json()["status"] == "active"
    assert _submit(client, tokens["student_a1"], aid).status_code == 200

    # 非创建者不能撤回。
    assert (
        client.post(f"/api/assignments/{aid}/withdraw", headers=bearer(tokens["teacher_b"])).status_code
        == 403
    )


# ----------------------------------------------------------------------
# 提交与计分
# ----------------------------------------------------------------------


def test_submit_scoring_and_best_of_multiple_attempts(client, tokens):
    assignment = _create_assignment(client, tokens["teacher_a"])
    aid = assignment["id"]
    student = bearer(tokens["student_a1"])

    # score 80 × 20 分 = 16.0
    HARNESS_STATE["score"] = 80
    body = _submit(client, tokens["student_a1"], aid, protocol="dns").json()
    assert body["score"] == 80
    assert body["passed"] == 4
    assert body["total"] == 5
    assert len(body["results"]) == 5
    assert body["points"] == 20
    assert body["earned"] == 16.0
    assert body["best_earned"] == 16.0

    # 第二次更高分 → best 跟随上升（100 × 20% = 20）。
    HARNESS_STATE["score"] = 100
    body = _submit(client, tokens["student_a1"], aid, protocol="dns").json()
    assert body["earned"] == 20.0
    assert body["best_earned"] == 20.0

    # 第三次低分 → 本次折算低，但历史最高保持 20。
    HARNESS_STATE["score"] = 50
    body = _submit(client, tokens["student_a1"], aid, protocol="dns").json()
    assert body["earned"] == 10.0
    assert body["best_earned"] == 20.0

    # 学生视角的作业列表：dns 最高分 100、折算 20、提交 3 次。
    response = client.get("/api/assignments", headers=student)
    row = next(a for a in response.json()["assignments"] if a["id"] == aid)
    dns = next(item for item in row["items"] if item["protocol"] == "dns")
    assert dns["best_score"] == 100
    assert dns["earned"] == 20.0
    assert dns["submission_count"] == 3
    assert row["total_points"] == 50
    assert row["total_earned"] == 20.0


def test_submit_keeps_acceptance_points_when_assignment_changes_during_grading(client, tokens, monkeypatch):
    """提交响应按受理时 20 分，历史成绩视图仍按修改后的 40 分动态折算。"""
    from app.services.assignment_service import AssignmentService
    from app.services.grading_service import GradingService

    assignment = _create_assignment(client, tokens["teacher_a"])
    aid = assignment["id"]

    async def grade_after_edit(**kwargs):
        await AssignmentService().update_assignment(
            aid, actor_id=TEACHER_A, actor_roles=["admin"],
            items=[{"protocol": "dns", "points": 40}, {"protocol": "http", "points": 30}],
        )
        return {"ok": True, "protocol": "dns", "score": 80,
                "passed": 4, "cases_total": 5, "results": []}

    monkeypatch.setattr("app.api.assignments.harness_grading.grade_code", grade_after_edit)
    response = _submit(client, tokens["student_a1"], aid)
    assert response.status_code == 200, response.text
    body = response.json()
    assert body["points"] == 20 and body["earned"] == 16.0
    stored = asyncio.run(GradingService().get_submission(body["submission_id"]))
    assert stored["accepted_assignment"]["points"] == 20
    assert stored["accepted_assignment"]["scope"] == "class"
    grades = client.get(f"/api/assignments/{aid}/grades", headers=bearer(tokens["teacher_a"]))
    assert grades.status_code == 200
    assert grades.json()["rows"][0]["scores"]["dns"] == 32.0


def test_submit_rejects_past_deadline_and_unknown_protocol(client, tokens):
    expired = _create_assignment(client, tokens["teacher_a"], title="过期作业", deadline=PAST_DEADLINE)
    assert _submit(client, tokens["student_a1"], expired["id"]).status_code == 400

    active = _create_assignment(client, tokens["teacher_a"], title="有效作业")
    response = _submit(client, tokens["student_a1"], active["id"], protocol="ssh")
    assert response.status_code == 400
    assert "不属于该作业" in response.json()["detail"]


def test_submit_multipart_upload_and_size_limit(client, tokens, monkeypatch):
    assignment = _create_assignment(client, tokens["teacher_a"])
    aid = assignment["id"]
    student = bearer(tokens["student_a1"])

    # multipart 上传 .c 文件。
    response = client.post(
        f"/api/assignments/{aid}/submit",
        data={"protocol": "dns"},
        files={"file": ("plug_dns.c", b"int main() { return 0; }")},
        headers=student,
    )
    assert response.status_code == 200, response.text
    assert response.json()["earned"] == 16.0

    # 不支持的扩展名 → 400。
    response = client.post(
        f"/api/assignments/{aid}/submit",
        data={"protocol": "dns"},
        files={"file": ("plug.txt", b"x")},
        headers=student,
    )
    assert response.status_code == 400

    # 文件超限 → 413（缩小上限构造）。
    monkeypatch.setenv("GRADING_MAX_CODE_BYTES", "8")
    from app.config import get_settings

    get_settings.cache_clear()
    response = client.post(
        f"/api/assignments/{aid}/submit",
        data={"protocol": "dns"},
        files={"file": ("plug_dns.c", b"0123456789")},
        headers=student,
    )
    assert response.status_code == 413
    # JSON 粘贴路径超限同样 413。
    response = client.post(
        f"/api/assignments/{aid}/submit",
        json={"protocol": "dns", "code": "0123456789"},
        headers=student,
    )
    assert response.status_code == 413


def test_chat_path_submission_not_counted(client, tokens):
    """聊天路径（grade_plugin 工具）落的 submission assignment_id=NULL，不计入作业成绩。"""
    assignment = _create_assignment(client, tokens["teacher_a"])
    aid = assignment["id"]

    async def record_chat_submission():
        from app.services.grading_service import GradingService

        await GradingService().record_submission(
            user_id=STUDENT_A1,
            session_id=None,
            protocol="dns",
            code_hash="chat_hash",
            score=100,
            passed=5,
            total=5,
            results=[],
            source_origin="pasted",
        )

    asyncio.run(record_chat_submission())

    # 作业成绩为空（聊天提交不计入）。
    response = client.get(f"/api/assignments/{aid}/grades", headers=bearer(tokens["teacher_a"]))
    assert response.status_code == 200
    assert response.json()["rows"] == []

    # 正式提交一次后只算这一次。
    HARNESS_STATE["score"] = 80
    _submit(client, tokens["student_a1"], aid, protocol="dns")
    rows = client.get(
        f"/api/assignments/{aid}/grades", headers=bearer(tokens["teacher_a"])
    ).json()["rows"]
    assert len(rows) == 1
    assert rows[0]["submission_count"] == 1
    assert rows[0]["scores"] == {"dns": 16.0}


def test_record_submission_backward_compatible(sqlite_db):
    """record_submission 不传 assignment_id 行为不变；list_submissions 支持等值过滤。"""

    async def scenario():
        from app.services.grading_service import GradingService

        service = GradingService()
        await service.record_submission(
            user_id="U20250000011", session_id=None, protocol="dns", code_hash="h1",
            score=80, passed=4, total=5, results=[],
        )
        await service.record_submission(
            user_id="U20250000011", session_id=None, protocol="dns", code_hash="h2",
            score=90, passed=5, total=5, results=[], assignment_id="asg_x",
        )
        all_rows = await service.list_submissions(user_id="U20250000011")
        assert len(all_rows) == 2
        assert "assignment_id" not in all_rows[0]  # 明细最小字段集不变
        filtered = await service.list_submissions(assignment_id="asg_x")
        assert len(filtered) == 1
        assert filtered[0]["score"] == 90

    asyncio.run(scenario())


# ----------------------------------------------------------------------
# 成绩表与 CSV 导出
# ----------------------------------------------------------------------


@pytest.fixture
def graded_assignment(client, tokens):
    """组长作业：A1/B1/FREE 三个学生各提交一次（dns 80 分）。"""
    assignment = _create_assignment(client, tokens["leader"], title="组长作业")
    aid = assignment["id"]
    HARNESS_STATE["score"] = 80
    for key in ("student_a1", "student_b1", "student_free"):
        assert _submit(client, tokens[key], aid, protocol="dns").status_code == 200
    return assignment


def test_grades_tree_isolation(client, tokens, graded_assignment):
    aid = graded_assignment["id"]
    # 老师 A 只看自己班学生。
    rows = client.get(
        f"/api/assignments/{aid}/grades", headers=bearer(tokens["teacher_a"])
    ).json()["rows"]
    assert [row["user_id"] for row in rows] == [STUDENT_A1]
    assert rows[0]["class_teacher"] == TEACHER_A
    assert rows[0]["scores"] == {"dns": 16.0}
    assert rows[0]["total_earned"] == 16.0
    assert rows[0]["submission_count"] == 1
    assert rows[0]["last_submitted_at"]

    # leader 看全部。
    rows = client.get(
        f"/api/assignments/{aid}/grades", headers=bearer(tokens["leader"])
    ).json()["rows"]
    assert {row["user_id"] for row in rows} == {STUDENT_A1, STUDENT_B1, STUDENT_FREE}
    by_user = {row["user_id"]: row for row in rows}
    assert by_user[STUDENT_B1]["class_teacher"] == TEACHER_B
    assert by_user[STUDENT_FREE]["class_teacher"] is None  # 未分配


def _read_csv(response) -> list:
    assert response.status_code == 200
    assert response.headers["content-type"].startswith("text/csv")
    assert "attachment" in response.headers["content-disposition"]
    # UTF-8 BOM 兼容 Excel。
    assert response.content.startswith(b"\xef\xbb\xbf")
    text = response.content.decode("utf-8-sig")
    return list(csv.reader(io.StringIO(text)))


def test_export_csv_admin_own_class_only(client, tokens, graded_assignment):
    aid = graded_assignment["id"]
    response = client.get(f"/api/assignments/{aid}/export", headers=bearer(tokens["teacher_a"]))
    rows = _read_csv(response)
    assert rows[0] == ["班级(老师学号)", "学生学号", "dns(满分20)", "http(满分30)", "总分", "提交次数", "最近提交时间"]
    data = [row for row in rows[1:] if row]
    assert len(data) == 1
    assert data[0][0] == TEACHER_A
    assert data[0][1] == STUDENT_A1
    assert data[0][2] == "16.0"  # dns 折算
    assert data[0][3] == ""  # http 未提交
    assert data[0][4] == "16.0"  # 总分
    assert data[0][5] == "1"
    assert TEACHER_A in response.headers["content-disposition"]  # 文件名含老师学号


def test_export_csv_leader_grouped_by_class(client, tokens, graded_assignment):
    aid = graded_assignment["id"]
    response = client.get(f"/api/assignments/{aid}/export", headers=bearer(tokens["leader"]))
    rows = _read_csv(response)
    data = rows[1:]
    # 三个班级组（TEACHER_A / TEACHER_B / 未分配），组间空行分隔。
    blank_positions = [i for i, row in enumerate(data) if not row]
    assert len(blank_positions) == 2
    classes = [row[0] for row in data if row]
    students = [row[1] for row in data if row]
    assert classes == [TEACHER_A, TEACHER_B, "未分配"]
    assert students == [STUDENT_A1, STUDENT_B1, STUDENT_FREE]
    # 未分配组排最后（班级排序稳定）。
    assert classes[-1] == "未分配"


def test_export_csv_unassigned_after_account_deleted(client, tokens, graded_assignment):
    """学生账号被删后：成绩行保留（脱钩），学号仍在，班级列归“未分配”。"""
    from app.db import get_conn, get_database_provider

    ph = get_database_provider().placeholder
    with get_conn() as conn:
        conn.execute(f"DELETE FROM users WHERE id = {ph}", (STUDENT_A1,))
    aid = graded_assignment["id"]
    response = client.get(f"/api/assignments/{aid}/export", headers=bearer(tokens["leader"]))
    rows = [row for row in _read_csv(response)[1:] if row]
    deleted_row = next(row for row in rows if row[1] == STUDENT_A1)
    assert deleted_row[0] == "未分配"
    assert deleted_row[4] == "16.0"


def test_protocols_endpoint(client, tokens):
    response = client.get("/api/assignments/protocols", headers=bearer(tokens["teacher_a"]))
    assert response.status_code == 200
    body = response.json()
    assert body["error"] is None
    assert [p["name"] for p in body["protocols"]] == ["dns", "http"]
    assert body["protocols"][0]["cases"] == 5


def test_protocols_endpoint_degrades_when_harness_down(client, tokens, monkeypatch):
    async def failing_call_tool(self, tool_name, arguments):
        return {"ok": False, "error": "harness_unavailable", "detail": "down"}

    from app.agent.mcp_client import HarnessMcpClient

    monkeypatch.setattr(HarnessMcpClient, "call_tool", failing_call_tool)
    response = client.get("/api/assignments/protocols", headers=bearer(tokens["teacher_a"]))
    assert response.status_code == 200
    assert response.json() == {"protocols": [], "error": "harness_unavailable"}
