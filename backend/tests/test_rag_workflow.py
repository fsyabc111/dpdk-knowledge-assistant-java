"""问题维护与人工发布的业务回归。"""
import sqlite3
from datetime import datetime, timedelta, timezone

import pytest
from fastapi import HTTPException

from app.api.rag import KnowledgeWrite, _write_knowledge
from app.core.auth import Principal
from app.db import get_conn
from app.services.rag_issue_service import RagIssueService
from app.services.session_service import SessionService


def test_knowledge_publish_requires_confirmation_and_owner(sqlite_db):
    teacher = Principal(subject="teacher", roles=["admin"], daily_quota=200)
    other = Principal(subject="other", roles=["admin"], daily_quota=200)
    with pytest.raises(HTTPException):
        _write_knowledge(KnowledgeWrite(title="DNS", body="知识正文"), teacher)
    first = _write_knowledge(KnowledgeWrite(title="DNS", body="知识正文", shared_confirmed=True), teacher)
    update = KnowledgeWrite(title="DNS", body="修订正文", revision=1, shared_confirmed=True)
    with pytest.raises(HTTPException) as exc:
        _write_knowledge(update, other, first["id"])
    assert exc.value.status_code == 404
    assert _write_knowledge(update, teacher, first["id"])["revision"] == 2
    with pytest.raises(HTTPException) as exc:
        _write_knowledge(update, teacher, first["id"])
    assert exc.value.status_code == 409
    with get_conn() as conn:
        assert conn.execute("SELECT COUNT(*) FROM rag_knowledge_revisions").fetchone()[0] == 2


async def test_retention_recovers_interrupted_and_expires_rows(sqlite_db):
    service = RagIssueService()
    session = await SessionService().ensure_session(None, "问题", "deleted-student")
    expired = service.begin("expired", "deleted-student", session, "问题", "", "model")
    service.finish(expired, session, "未解答", [], "unanswered", "no_evidence", "", 1)
    interrupted = service.begin("interrupted", "deleted-student", session, "其他问题", "", "model")
    old = (datetime.now(timezone.utc) - timedelta(days=181)).isoformat()
    stale = (datetime.now(timezone.utc) - timedelta(minutes=11)).isoformat()
    with get_conn() as conn:
        conn.execute("UPDATE rag_requests SET created_at=? WHERE id=?", (old, expired))
        conn.execute("UPDATE rag_requests SET updated_at=? WHERE id=?", (stale, interrupted))
    result = service.maintain(180)
    assert result == {"interrupted": 1, "expired": 1}
    with get_conn() as conn:
        row = conn.execute("SELECT * FROM rag_requests WHERE id=?", (interrupted,)).fetchone()
        assert row["user_id"] is None and row["reason"] == "interrupted"
        assert conn.execute("SELECT COUNT(*) FROM rag_issues").fetchone()[0] == 1


async def test_failed_message_write_rolls_back_request_and_issue(sqlite_db):
    service = RagIssueService()
    pending = service.begin("pending", "student", "missing-session", "问题", "", "model")
    with get_conn() as conn:
        conn.execute("CREATE TRIGGER fail_message BEFORE INSERT ON assistant_messages BEGIN SELECT RAISE(ABORT, 'blocked'); END")
    with pytest.raises(sqlite3.IntegrityError):
        service.finish(pending, "missing-session", "未解答", [], "unanswered", "no_evidence", "", 0)
    with get_conn() as conn:
        assert conn.execute("SELECT status FROM rag_requests").fetchone()[0] == "pending"
        assert conn.execute("SELECT COUNT(*) FROM rag_issues").fetchone()[0] == 0
