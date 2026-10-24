"""S3-c 存储与服务层原语：probe 三态 / 任务读取与取消 / 临时输入保管 / 作业账本幂等。

新表由临时 SQLite 的 init_db() 直接落（rag_schema.sql 基线同构）；alembic 0013
只做静态校验（revision 链），不跑 upgrade——存量库升级验证留给部署侧。
"""
from __future__ import annotations

import importlib.util
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from fastapi import HTTPException

from app.db import get_conn
from app.services.rag_issue_service import RagIssueService


def _begin(issues, request_id="req", user="student", session="sess", question="问题"):
    return issues.begin(request_id, user, session, question, "", "model")


def _table_count(name, where="", params=()):
    with get_conn() as conn:
        return conn.execute(f"SELECT COUNT(*) FROM {name} {where}", params).fetchone()[0]


def _now_iso():
    return datetime.now(timezone.utc).isoformat()


def _future_iso():
    return (datetime.now(timezone.utc) + timedelta(hours=1)).isoformat()


def test_new_tables_present(sqlite_db):
    with get_conn() as conn:
        names = {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert {"rag_task_inputs", "rag_task_jobs"} <= names


# ---------------------------------------------------------------------------
# probe 三态 + replay 旧契约保持
# ---------------------------------------------------------------------------


def test_probe_miss_in_progress_replay(sqlite_db):
    issues = RagIssueService()
    assert issues.probe("no-such", "student", "问题", session_id="sess") == ("miss", None, None)

    task = _begin(issues, "req-a", "student", "sess-a")
    state, row, message = issues.probe("req-a", "student", "问题", session_id="sess-a")
    assert state == "in_progress" and row["id"] == task and message is None

    message_id, _ = issues.finish(task, "sess-a", "答案", [], "answered", "supported", "", 1)
    state, row, message = issues.probe("req-a", "student", "问题", session_id="sess-a")
    assert state == "replay" and row["id"] == task and row["message_id"] == message_id
    assert message["content"] == "答案"


def test_probe_terminal_without_message(sqlite_db):
    """取消/错误终态且没有正文的行 → terminal，而不是 replay。"""
    issues = RagIssueService()
    task = _begin(issues, "req-b", "student", "sess-b")
    assert issues.mark_interrupted(task) is True
    state, row, message = issues.probe("req-b", "student", "问题", session_id="sess-b")
    assert state == "terminal" and row["status"] == "system_error" and message is None


def test_probe_conflicts_and_replay_keeps_old_contract(sqlite_db):
    issues = RagIssueService()
    task = _begin(issues, "req", "student", "sess")
    # user 不匹配 / input_hash 不匹配：照旧 409
    with pytest.raises(HTTPException) as exc:
        issues.probe("req", "other", "问题", session_id="sess")
    assert exc.value.status_code == 409
    with pytest.raises(HTTPException) as exc:
        issues.probe("req", "student", "别的问题", session_id="sess")
    assert exc.value.status_code == 409
    # replay() 是旧两态接口：pending 与 terminal 仍按原契约抛 409
    with pytest.raises(HTTPException) as exc:
        issues.replay("req", "student", "问题", session_id="sess")
    assert exc.value.status_code == 409
    issues.finish(task, "sess", "答案", [], "answered", "supported", "", 1)
    row, message = issues.replay("req", "student", "问题", session_id="sess")
    assert row["id"] == task and message["content"] == "答案"


# ---------------------------------------------------------------------------
# get_task / active_task_for_session / cancel_task / orphaned_pending
# ---------------------------------------------------------------------------


def test_get_task_scoped_to_owner(sqlite_db):
    issues = RagIssueService()
    task = _begin(issues, "req", "student", "sess")
    assert issues.get_task(task, "student")["id"] == task
    assert issues.get_task(task, "other") is None      # 他人任务不区分是否存在
    assert issues.get_task("missing", "student") is None


def test_active_task_for_session_returns_latest_pending(sqlite_db):
    issues = RagIssueService()
    older = _begin(issues, "req-old", "student", "sess", "旧问题")
    newer = _begin(issues, "req-new", "student", "sess", "新问题")
    with get_conn() as conn:
        conn.execute("UPDATE rag_requests SET updated_at=? WHERE id=?",
                     ("2020-01-01T00:00:00+00:00", older))
    assert issues.active_task_for_session("sess", "student")["id"] == newer
    # 终态不算活动任务；他人/其他会话不可见
    assert issues.cancel_task(newer, "user_cancelled", "student") is True
    assert issues.active_task_for_session("sess", "student")["id"] == older
    assert issues.active_task_for_session("sess", "other") is None
    assert issues.active_task_for_session("no-such", "student") is None


def test_cancel_task_writes_placeholder_and_cleans_inputs(sqlite_db):
    issues = RagIssueService()
    task = _begin(issues, "req", "student", "sess")
    issues.store_task_inputs(task, "原始问题", source_text="源码", source_origin="pasted")

    assert issues.cancel_task(task, "user_cancelled", "student") is True
    row = issues.get_task(task, "student")
    assert row["status"] == "cancelled" and row["reason"] == "user_cancelled"
    assert row["message_id"], "取消必须回填占位消息 id"
    assert issues.load_task_inputs(task) is None     # 临时输入即清

    with get_conn() as conn:
        message = conn.execute("SELECT * FROM assistant_messages WHERE id=?",
                               (row["message_id"],)).fetchone()
    assert message["role"] == "assistant" and message["content"] == "（请求已取消）"
    metadata = json.loads(message["metadata_json"])
    assert metadata["stream_status"] == "cancelled"
    assert metadata["task_id"] == task and metadata["request_id"] == "req"
    # 占位消息让 probe 按 replay 有行可读
    state, _, replayed = issues.probe("req", "student", "问题", session_id="sess")
    assert state == "replay" and replayed["content"] == "（请求已取消）"

    # 重复取消 False；终态行不可再取消
    assert issues.cancel_task(task, "user_cancelled", "student") is False
    done = _begin(issues, "req-done", "student", "sess2")
    issues.finish(done, "sess2", "答案", [], "answered", "supported", "", 1)
    assert issues.cancel_task(done, "user_cancelled", "student") is False


def test_orphaned_pending_respects_grace_window(sqlite_db):
    issues = RagIssueService()
    fresh = _begin(issues, "req-fresh", "student", "sess")
    assert issues.orphaned_pending(30) == []          # grace 窗口内不入列

    stale = (datetime.now(timezone.utc) - timedelta(seconds=60)).isoformat()
    with get_conn() as conn:
        conn.execute("UPDATE rag_requests SET updated_at=? WHERE id=?", (stale, fresh))
    assert [row["id"] for row in issues.orphaned_pending(30)] == [fresh]

    issues.cancel_task(fresh, "user_cancelled", "student")
    assert issues.orphaned_pending(30) == []          # 非 pending 不入列


def test_mark_interrupted_records_issue_and_cleans_inputs(sqlite_db):
    issues = RagIssueService()
    task = _begin(issues, "req", "student", "sess")
    issues.store_task_inputs(task, "原始问题")
    assert issues.mark_interrupted(task, "account_disabled") is True
    assert issues.mark_interrupted(task) is False     # 已非 pending，条件更新失败
    row = issues.get_task(task, "student")
    assert row["status"] == "system_error" and row["reason"] == "account_disabled"
    assert _table_count("rag_issues", "WHERE request_id=?", (task,)) == 1
    assert _table_count("rag_task_inputs") == 0


# ---------------------------------------------------------------------------
# rag_task_inputs
# ---------------------------------------------------------------------------


def test_task_inputs_roundtrip_expiry_and_drop(sqlite_db):
    issues = RagIssueService()
    task = _begin(issues, "req", "student", "sess")
    issues.store_task_inputs(task, "原始问题", attachment_name="p.c",
                             attachment_suffix=".c", source_text="源码",
                             source_origin="upload")
    row = issues.load_task_inputs(task)
    assert row["question"] == "原始问题" and row["source_text"] == "源码"
    assert row["attachment_name"] == "p.c" and row["attachment_suffix"] == ".c"
    assert row["source_origin"] == "upload"
    assert row["expires_at"] > row["created_at"]      # 默认取 settings 的 24h

    # 过期视为不存在，并顺手删除
    issues.store_task_inputs(task, "原始问题", ttl_hours=0)
    assert issues.load_task_inputs(task) is None
    assert _table_count("rag_task_inputs", "WHERE task_id=?", (task,)) == 0

    # drop 幂等；同 task 重复寄存是覆盖而非第二行
    issues.store_task_inputs(task, "原始问题")
    issues.store_task_inputs(task, "原始问题 v2")
    assert _table_count("rag_task_inputs", "WHERE task_id=?", (task,)) == 1
    assert issues.load_task_inputs(task)["question"] == "原始问题 v2"
    issues.drop_task_inputs(task)
    assert issues.load_task_inputs(task) is None
    issues.drop_task_inputs(task)


def test_finish_clears_task_inputs(sqlite_db):
    """任务到达终态即清理临时输入（§8），不等 24h 兜底。"""
    issues = RagIssueService()
    task = _begin(issues, "req", "student", "sess")
    issues.store_task_inputs(task, "原始问题")
    issues.finish(task, "sess", "答案", [], "answered", "supported", "", 1)
    assert _table_count("rag_task_inputs") == 0


# ---------------------------------------------------------------------------
# rag_task_jobs
# ---------------------------------------------------------------------------


async def test_task_jobs_lifecycle_and_submission_gate(sqlite_db):
    from app.services.grading_service import GradingService

    issues = RagIssueService()
    task = _begin(issues, "req", "student", "sess")
    issues.record_job(task, "job-1", "grade_plugin", "dns", "hash-a")
    issues.record_job(task, "job-1", "grade_plugin", "dns", "hash-a")   # 幂等登记
    assert _table_count("rag_task_jobs") == 1

    job = issues.find_job(task, "dns", "hash-a")
    assert job["job_id"] == "job-1" and job["status"] == "submitted"
    assert job["submission_done"] == 0
    assert issues.find_job(task, "dns", "hash-b") is None
    assert issues.find_job(task, "http", "hash-a") is None

    issues.finish_job("job-1", {"score": 90, "ok": True})
    job = issues.find_job(task, "dns", "hash-a")
    assert job["status"] == "finished"
    assert json.loads(job["result_json"])["score"] == 90

    # 成绩和账本标志在同一事务，重复调用不会产生第二条成绩。
    kwargs = dict(task_id=task, job_id="job-1", user_id="student", session_id="sess",
                  protocol="dns", code_hash="hash-a", score=90, passed=9, total=10,
                  results=[], source_origin="pasted")
    assert await GradingService().record_task_job_submission(**kwargs) == "persisted"
    assert await GradingService().record_task_job_submission(**kwargs) == "persisted"
    assert _table_count("grading_submissions", "WHERE task_job_id=?", ("job-1",)) == 1
    assert issues.find_job(task, "dns", "hash-a")["submission_done"] == 1

    # 同任务不同协议/源码各自登记
    issues.record_job(task, "job-2", "grade_plugin", "http", "hash-a")
    assert issues.find_job(task, "http", "hash-a")["job_id"] == "job-2"
    assert _table_count("rag_task_jobs") == 2


def test_job_reservation_and_grading_quota_commit_together(sqlite_db):
    issues = RagIssueService()
    task = _begin(issues, "reserve", "student", "sess")
    assert issues.reserve_grading_job(task, "job-a", "dns", "hash-a", "student", 1)
    assert not issues.reserve_grading_job(task, "job-a", "dns", "hash-a", "student", 1)
    with pytest.raises(HTTPException) as exc:
        issues.reserve_grading_job(task, "job-b", "http", "hash-b", "student", 1)
    assert exc.value.status_code == 429
    with get_conn() as conn:
        count = conn.execute("SELECT chat_count FROM usage_counters WHERE subject=? AND kind='grading'",
                             ("student",)).fetchone()[0]
    assert count == 1
    assert _table_count("rag_task_jobs", "WHERE task_id=?", (task,)) == 1


# ---------------------------------------------------------------------------
# maintain 扩展
# ---------------------------------------------------------------------------


def test_maintain_cleans_expired_and_orphaned_inputs(sqlite_db):
    issues = RagIssueService()
    pending = _begin(issues, "req-p", "student", "sess-p")
    issues.store_task_inputs(pending, "原始", ttl_hours=0)              # 已过期
    done = _begin(issues, "req-d", "student", "sess-d")
    issues.finish(done, "sess-d", "答案", [], "answered", "supported", "", 1)
    # 终态行残留的孤儿 inputs：finish 已即时清理，手工补一行模拟漏网
    with get_conn() as conn:
        conn.execute(
            "INSERT INTO rag_task_inputs(task_id,question,created_at,expires_at) VALUES (?,?,?,?)",
            (done, "孤儿", _now_iso(), _future_iso()))
    issues.maintain(180)
    assert _table_count("rag_task_inputs") == 0
    # pending 任务本体不受影响（maintain 会把无对应 users 行的 user_id 置 NULL，
    # 是既有 dangling 清理行为，故这里直接读行而不是走 get_task 的归属口径）
    with get_conn() as conn:
        assert conn.execute("SELECT status FROM rag_requests WHERE id=?",
                            (pending,)).fetchone()["status"] == "pending"


def test_maintain_deletes_children_before_parent(sqlite_db):
    """180d 留存删除 rag_requests 前先删 rag_task_inputs / rag_task_jobs 子行。"""
    issues = RagIssueService()
    task = _begin(issues, "req-old", "student", "sess-old")
    issues.store_task_inputs(task, "原始问题")
    issues.record_job(task, "job-x", "grade_plugin", "dns", "hash-x")
    old = (datetime.now(timezone.utc) - timedelta(days=181)).isoformat()
    with get_conn() as conn:
        conn.execute("UPDATE rag_requests SET created_at=? WHERE id=?", (old, task))
    result = issues.maintain(180)
    assert result["expired"] == 1
    assert _table_count("rag_requests") == 0
    assert _table_count("rag_task_inputs") == 0
    assert _table_count("rag_task_jobs") == 0


def test_maintain_skips_pending_with_live_inputs(sqlite_db):
    """有有效 inputs 的超龄 pending 留给恢复扫描接续，不判 interrupted。"""
    issues = RagIssueService()
    live = _begin(issues, "req-live", "student", "sess-1")
    issues.store_task_inputs(live, "原始问题")                          # 24h 有效
    dead = _begin(issues, "req-dead", "student", "sess-2")              # 无 inputs
    stale = (datetime.now(timezone.utc) - timedelta(minutes=11)).isoformat()
    with get_conn() as conn:
        conn.execute("UPDATE rag_requests SET updated_at=? WHERE id IN (?,?)",
                     (stale, live, dead))
    result = issues.maintain(180)
    assert result["interrupted"] == 1
    with get_conn() as conn:
        live_row = conn.execute("SELECT status FROM rag_requests WHERE id=?", (live,)).fetchone()
        dead_row = conn.execute("SELECT status,reason FROM rag_requests WHERE id=?", (dead,)).fetchone()
    assert live_row["status"] == "pending"          # 有效输入在行不判死
    assert dead_row["status"] == "system_error" and dead_row["reason"] == "interrupted"
    assert issues.load_task_inputs(live) is not None  # 有效输入保留给接续


# ---------------------------------------------------------------------------
# 配置校验与 alembic 静态检查
# ---------------------------------------------------------------------------


def test_task_settings_validation(monkeypatch):
    from app.config import Settings
    assert Settings().rag_task_max_seconds == 600.0
    assert Settings().rag_task_input_ttl_hours == 24
    monkeypatch.setenv("RAG_TASK_MAX_SECONDS", "0")
    with pytest.raises(ValueError, match="RAG_TASK_MAX_SECONDS"):
        Settings()
    monkeypatch.setenv("RAG_TASK_MAX_SECONDS", "600")
    monkeypatch.setenv("RAG_TASK_INPUT_TTL_HOURS", "0")
    with pytest.raises(ValueError, match="RAG_TASK_INPUT_TTL_HOURS"):
        Settings()


def test_alembic_0013_revision_chain():
    """静态校验：import 模块并核对 revision 链，不跑 upgrade。"""
    path = Path(__file__).resolve().parents[1] / "alembic/versions/0013_task_recovery.py"
    spec = importlib.util.spec_from_file_location("s3c_migration", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    assert module.revision == "0013_task_recovery"
    assert module.down_revision == "0012_request_identity"
    assert "CREATE TABLE IF NOT EXISTS rag_task_inputs" in module._UPGRADE_SQL
    assert "CREATE TABLE IF NOT EXISTS rag_task_jobs" in module._UPGRADE_SQL
