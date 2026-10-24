"""Storage provider selection tests for the SQLite / PostgreSQL dual backend.

All assertions are pure selection/initialization checks: the PostgreSQL
provider and retrieval store are constructed lazily, so no running
PostgreSQL service is required and no marker is needed.
"""
from __future__ import annotations

import sqlite3
from concurrent.futures import ThreadPoolExecutor

import pytest


@pytest.fixture
def sqlite_backend(tmp_path, monkeypatch):
    """Point the app at a throwaway SQLite database file (schema not yet initialised)."""
    db_path = tmp_path / "assistant.db"
    monkeypatch.setenv("DATABASE_BACKEND", "sqlite")
    monkeypatch.setenv("DATABASE_URL", f"sqlite:///{db_path}")
    _clear_caches()
    return db_path


@pytest.fixture
def postgres_backend(monkeypatch):
    """Select the PostgreSQL backend with a dummy DSN (never connected to)."""
    monkeypatch.setenv("DATABASE_BACKEND", "postgres")
    monkeypatch.setenv("DATABASE_URL", "postgresql://user:pass@localhost:5432/assistant")
    _clear_caches()


def _clear_caches() -> None:
    from app.config import get_settings
    from app.db import get_database_provider

    get_settings.cache_clear()
    get_database_provider.cache_clear()


def test_sqlite_provider_initializes_metadata_tables(sqlite_backend):
    from app.db import get_database_provider
    from app.storage.sqlite import SQLiteProvider

    db_path = sqlite_backend
    provider = get_database_provider()
    assert isinstance(provider, SQLiteProvider)
    provider.init()

    with sqlite3.connect(db_path) as conn:
        tables = {
            row[0]
            for row in conn.execute(
                "SELECT name FROM sqlite_master WHERE type = 'table'"
            ).fetchall()
        }
    assert "knowledge_indexes" in tables
    assert "knowledge_source_files" in tables
    columns = {row[1] for row in conn.execute("PRAGMA table_info(assistant_sessions)")}
    assert {"memory_summary", "memory_summary_through_message_id"} <= columns


def test_postgres_provider_can_be_selected(postgres_backend):
    from app.db import get_database_provider
    from app.storage.postgres import PostgresProvider

    provider = get_database_provider()
    assert isinstance(provider, PostgresProvider)


def test_concurrent_provider_lookup_returns_one_instance(postgres_backend):
    from app.db import get_database_provider

    with ThreadPoolExecutor(max_workers=16) as executor:
        providers = list(executor.map(lambda _: get_database_provider(), range(64)))
    assert all(provider is providers[0] for provider in providers)


def test_historical_chunk_lookup_reads_knowledge_chunks(sqlite_db):
    """S4 收敛后 knowledge_chunks 仅存历史引用回读：直查函数按方言占位符读行。"""
    from app.db import get_conn, get_database_provider
    from app.storage.retrieval_store import get_historical_chunk

    ph = get_database_provider().placeholder
    with get_conn() as conn:
        conn.execute(
            f"INSERT INTO knowledge_documents(id, source_path, source_type, title, content_hash, indexed_at) "
            f"VALUES ({ph}, {ph}, {ph}, {ph}, {ph}, {ph})",
            ("doc1", "docs/dns.md", "doc", "DNS 文档", "h1", "2026-01-01"),
        )
        conn.execute(
            f"INSERT INTO knowledge_chunks(id, document_id, source_path, section, content, metadata_json) "
            f"VALUES ({ph}, {ph}, {ph}, {ph}, {ph}, {ph})",
            ("chk1", "doc1", "docs/dns.md", "报文格式", "qname 标签编码", '{"k": 1}'),
        )

    chunk = get_historical_chunk("chk1")
    assert chunk["chunk_id"] == "chk1"
    assert chunk["source_path"] == "docs/dns.md"
    assert chunk["title"] == "DNS 文档"
    assert chunk["section"] == "报文格式"
    assert chunk["metadata"] == {"k": 1}
    assert get_historical_chunk("missing") is None


if __name__ == "__main__":
    import sys

    sys.exit(pytest.main([__file__, "-v"]))
