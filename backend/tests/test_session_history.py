"""Network-free regression test for loading a persisted session by id."""
from __future__ import annotations

import sys
from pathlib import Path

import pytest
from fastapi import HTTPException

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.api.chat import load_session
from app.db import get_conn
from app.services.session_service import SessionService


@pytest.fixture
async def seeded_session(sqlite_db):
    """Session with one user/assistant message pair and one citation."""
    sessions = SessionService()
    session_id = await sessions.ensure_session(None, "history test")
    await sessions.add_message(session_id, "user", "历史问题")
    assistant_id = await sessions.add_message(session_id, "assistant", "历史回答")
    with get_conn() as conn:
        conn.execute(
            "INSERT INTO knowledge_documents(id, source_path, source_type, title, content_hash, git_commit, indexed_at) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            ("doc_history", "history.md", "doc", "history", "hash", "", "now"),
        )
        conn.execute(
            "INSERT INTO knowledge_chunks(id, document_id, source_path, section, content, metadata_json, embedding_json) "
            "VALUES (?, ?, ?, ?, ?, ?, ?)",
            ("chk_history", "doc_history", "history.md", "section", "source text", "{}", "[]"),
        )
    await sessions.add_citation(assistant_id, "chk_history", 0.9, 1)
    return session_id


async def test_load_session_returns_same_session_id(seeded_session):
    loaded = await load_session(seeded_session, limit=100)
    assert loaded.session_id == seeded_session


async def test_load_session_preserves_message_content(seeded_session):
    loaded = await load_session(seeded_session, limit=100)
    assert [item.content for item in loaded.messages] == ["历史问题", "历史回答"]


async def test_load_session_resolves_citation_source_path(seeded_session):
    loaded = await load_session(seeded_session, limit=100)
    assert loaded.messages[1].citations[0].source_path == "history.md"


async def test_load_session_missing_returns_404(seeded_session):
    with pytest.raises(HTTPException) as exc_info:
        await load_session("sess_missing", limit=100)
    assert exc_info.value.status_code == 404


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v"]))
