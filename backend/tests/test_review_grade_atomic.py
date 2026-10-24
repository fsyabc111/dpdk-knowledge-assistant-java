"""总审查修复批次 3：成绩、账本和停号的事务边界。"""

from uuid import uuid4

import pytest
from scripts.audit_grading_jobs import audit

from app.config import get_settings
from app.db import get_conn
from app.services.grading_service import GradingService
from app.services.rag_issue_service import RagIssueService
from app.services.session_service import SessionService
from app.services.user_service import UserService


def _count(table, where="", params=()):
    with get_conn() as conn:
        return conn.execute(f"SELECT COUNT(*) FROM {table} {where}", params).fetchone()[0]


async def _job(user="student"):
    session = await SessionService().ensure_session(None, "评测", user)
    issues = RagIssueService()
    task = issues.begin(str(uuid4()), user, session, "评测问题", "", "model")
    issues.store_task_inputs(task, "评测问题", source_text="int x;", source_origin="pasted")
    job_id = f"{task}:dns:hash"
    issues.record_job(task, job_id, "grade_plugin", "dns", "hash")
    issues.finish_job(job_id, {"ok": True, "score": 90})
    return task, session, job_id


def _grade_args(task, session, job_id, user="student"):
    return dict(task_id=task, job_id=job_id, user_id=user, session_id=session,
                protocol="dns", code_hash="hash", score=90, passed=9, total=10,
                results=[{"name": "case1", "passed": True}], source_origin="pasted")


async def test_grade_insert_failure_rolls_back_job_flag_and_retry(sqlite_db, monkeypatch):
    task, session, job_id = await _job()
    service = GradingService()
    original = service._insert_submission_in_conn

    def fail_insert(*args, **kwargs):
        raise RuntimeError("synthetic insert failure")

    monkeypatch.setattr(service, "_insert_submission_in_conn", fail_insert)
    with pytest.raises(RuntimeError, match="synthetic insert"):
        await service.record_task_job_submission(**_grade_args(task, session, job_id))
    assert _count("grading_submissions") == 0
    assert RagIssueService().find_job(task, "dns", "hash")["submission_done"] == 0

    monkeypatch.setattr(service, "_insert_submission_in_conn", original)
    assert await service.record_task_job_submission(**_grade_args(task, session, job_id)) == "persisted"
    assert await service.record_task_job_submission(**_grade_args(task, session, job_id)) == "persisted"
    assert _count("grading_submissions", "WHERE task_job_id=?", (job_id,)) == 1
    assert RagIssueService().find_job(task, "dns", "hash")["submission_done"] == 1


async def test_disable_cancels_pending_chat_task_and_removes_its_grade(sqlite_db, monkeypatch):
    user = "U20250000011"
    await UserService().create_users([user], roles=["viewer"])
    monkeypatch.setenv("AUTH_MODE", "account")
    get_settings.cache_clear()
    task, session, job_id = await _job(user)
    service = GradingService()
    assert await service.record_task_job_submission(**_grade_args(task, session, job_id, user)) == "persisted"
    assert _count("grading_submissions", "WHERE task_job_id=?", (job_id,)) == 1

    assert await UserService().set_enabled(user, False)
    assert RagIssueService().get_task(task, user)["status"] == "cancelled"
    assert _count("grading_submissions", "WHERE task_job_id=?", (job_id,)) == 0
    assert _count("rag_task_inputs", "WHERE task_id=?", (task,)) == 0
    assert await service.record_task_job_submission(**_grade_args(task, session, job_id, user)) == "cancelled"
    assert _count("grading_submissions", "WHERE task_job_id=?", (job_id,)) == 0


async def test_formal_submission_refuses_disabled_account(sqlite_db, monkeypatch):
    user = "U20250000012"
    await UserService().create_users([user], roles=["viewer"])
    monkeypatch.setenv("AUTH_MODE", "account")
    get_settings.cache_clear()
    await UserService().set_enabled(user, False)
    with pytest.raises(PermissionError, match="已停用"):
        await GradingService().record_submission(
            user_id=user, session_id=None, protocol="dns", code_hash="hash",
            score=100, passed=1, total=1, results=[], assignment_id="asg_test",
            require_enabled=True,
        )
    assert _count("grading_submissions") == 0


async def test_legacy_job_audit_only_lists_unlinked_identity(sqlite_db):
    task, _, job_id = await _job()
    with get_conn() as conn:
        conn.execute("UPDATE rag_task_jobs SET submission_done=1 WHERE job_id=?", (job_id,))
    report = audit(10)
    assert report["legacy_unlinked_total"] == 1
    assert report["items"] == [{"task_id": task, "job_id": job_id}]
    assert _count("grading_submissions") == 0, "只读核查不得猜测补写"
