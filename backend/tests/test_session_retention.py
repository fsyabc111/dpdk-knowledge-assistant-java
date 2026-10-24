"""Session retention / purge regression tests (M4).

Throwaway SQLite database; no external calls. Covers the purge service logic
(dry-run vs apply, citation orphan removal) and the maintenance endpoint
contract (key enforcement, dry-run default).
"""
from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

MAINTENANCE_KEY = "m" * 32


@pytest.fixture
def seeded_db(sqlite_db, monkeypatch):
    """SQLite database in local auth mode, seeded with one old and one fresh session."""
    monkeypatch.setenv("AUTH_MODE", "local")
    monkeypatch.setenv("MAINTENANCE_API_KEY", MAINTENANCE_KEY)

    from app.config import get_settings

    get_settings.cache_clear()

    from app.db import get_conn

    old = _iso_days_ago(40)
    new = _iso_days_ago(1)
    with get_conn() as conn:
        conn.execute(
            "INSERT INTO assistant_sessions(id, user_id, title, created_at, updated_at) "
            "VALUES ('sess_old', 'alice', 'old', ?, ?)", (old, old))
        conn.execute(
            "INSERT INTO assistant_sessions(id, user_id, title, created_at, updated_at) "
            "VALUES ('sess_new', 'alice', 'new', ?, ?)", (new, new))
        conn.execute(
            "INSERT INTO assistant_messages(id, session_id, role, content, created_at) "
            "VALUES ('msg_old', 'sess_old', 'user', 'stale question', ?)", (old,))
        conn.execute(
            "INSERT INTO assistant_messages(id, session_id, role, content, created_at) "
            "VALUES ('msg_new', 'sess_new', 'user', 'fresh question', ?)", (new,))
        conn.execute(
            "INSERT INTO assistant_citations(id, message_id, chunk_id, score, rank) "
            "VALUES ('cit_old', 'msg_old', 'chk_x', 1.0, 1)")
        conn.execute(
            "INSERT INTO assistant_citations(id, message_id, chunk_id, score, rank) "
            "VALUES ('cit_new', 'msg_new', 'chk_x', 1.0, 1)")
    return sqlite_db


def _iso_days_ago(days: int) -> str:
    return (datetime.now(timezone.utc) - timedelta(days=days)).isoformat()


def _counts() -> dict:
    from app.db import get_conn

    with get_conn() as conn:
        return {
            "sessions": conn.execute("SELECT COUNT(*) n FROM assistant_sessions").fetchone()["n"],
            "messages": conn.execute("SELECT COUNT(*) n FROM assistant_messages").fetchone()["n"],
            "citations": conn.execute("SELECT COUNT(*) n FROM assistant_citations").fetchone()["n"],
        }


async def test_dry_run_counts_without_deleting(seeded_db) -> None:
    from app.services.session_service import SessionService

    result = await SessionService().purge_expired_sessions(30, dry_run=True)
    assert result["dry_run"] is True
    assert result["sessions"] == 1 and result["messages"] == 1 and result["citations"] == 1
    assert _counts() == {"sessions": 2, "messages": 2, "citations": 2}


async def test_apply_deletes_only_expired(seeded_db) -> None:
    from app.db import get_conn
    from app.services.session_service import SessionService

    result = await SessionService().purge_expired_sessions(30, dry_run=False)
    assert result["sessions"] == 1 and result["messages"] == 1 and result["citations"] == 1
    remaining = _counts()
    assert remaining == {"sessions": 1, "messages": 1, "citations": 1}
    with get_conn() as conn:
        assert conn.execute("SELECT id FROM assistant_sessions").fetchone()["id"] == "sess_new"
        assert conn.execute("SELECT id FROM assistant_messages").fetchone()["id"] == "msg_new"
        assert conn.execute("SELECT id FROM assistant_citations").fetchone()["id"] == "cit_new"


async def test_apply_is_idempotent(seeded_db) -> None:
    from app.services.session_service import SessionService

    await SessionService().purge_expired_sessions(30, dry_run=False)
    result = await SessionService().purge_expired_sessions(30, dry_run=False)
    assert result["sessions"] == 0 and result["messages"] == 0 and result["citations"] == 0
    assert _counts() == {"sessions": 1, "messages": 1, "citations": 1}


def test_endpoint(seeded_db) -> None:
    # Mirror the original script flow: expired rows are already purged by the
    # time the endpoint contract is exercised.
    import asyncio

    from fastapi.testclient import TestClient

    from app.main import app
    from app.services.session_service import SessionService

    asyncio.run(SessionService().purge_expired_sessions(30, dry_run=False))

    client = TestClient(app)
    response = client.post("/api/internal/purge-sessions")
    assert response.status_code == 403, response.text
    response = client.post(
        "/api/internal/purge-sessions",
        headers={"X-Maintenance-Key": MAINTENANCE_KEY},
    )
    assert response.status_code == 200, response.text
    # Default is dry-run: the surviving fresh session must still be there.
    assert response.json()["dry_run"] is True
    assert _counts()["sessions"] == 1
    response = client.post(
        "/api/internal/purge-sessions?retention_days=0",  # rejected: ge=1
        headers={"X-Maintenance-Key": MAINTENANCE_KEY},
    )
    assert response.status_code == 422, response.text


if __name__ == "__main__":
    import sys

    sys.exit(pytest.main([__file__, "-v"]))
