"""Regression test for the protected, read-only session-storage endpoint."""
from __future__ import annotations

import pytest

MAINTENANCE_KEY = "test-maintenance-key"


@pytest.fixture
async def seeded_report(sqlite_db, monkeypatch):
    """SQLite DB with one session/message and the maintenance key configured.

    The endpoint function is called directly (it is a plain async function),
    so no TestClient or model initialisation is involved.
    """
    monkeypatch.setenv("MAINTENANCE_API_KEY", MAINTENANCE_KEY)

    from app.config import get_settings

    get_settings.cache_clear()

    from app.api.chat import session_storage
    from app.services.session_service import SessionService

    sessions = SessionService()
    session_id = await sessions.ensure_session(None, "storage test")
    await sessions.add_message(session_id, "user", "question")

    return await session_storage(MAINTENANCE_KEY)


def test_report_database_backend_is_sqlite(seeded_report):
    assert seeded_report["database_backend"] == "sqlite"


def test_report_session_count(seeded_report):
    assert seeded_report["session_count"] == 1


def test_report_storage_bytes_positive(seeded_report):
    assert seeded_report["storage_bytes"] > 0


async def test_invalid_maintenance_key_rejected(sqlite_db, monkeypatch):
    monkeypatch.setenv("MAINTENANCE_API_KEY", MAINTENANCE_KEY)

    from fastapi import HTTPException

    from app.api.chat import session_storage
    from app.config import get_settings

    get_settings.cache_clear()

    with pytest.raises(HTTPException) as exc_info:
        await session_storage("wrong-key")
    assert exc_info.value.status_code == 403


if __name__ == "__main__":
    import sys

    sys.exit(pytest.main([__file__, "-v"]))
