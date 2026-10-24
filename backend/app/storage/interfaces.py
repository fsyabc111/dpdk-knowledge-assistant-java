from __future__ import annotations

from contextlib import AbstractContextManager
from typing import Any, Protocol


class DatabaseProvider(Protocol):
    name: str
    # SQL placeholder token. SQLite uses "?" and PostgreSQL uses "%s".
    placeholder: str
    # True when the backend has a separately maintained FTS virtual table.
    # PostgreSQL uses a generated tsvector column instead.
    supports_manual_fts: bool
    # Column that stores the chunk embedding. SQLite stores JSON text in
    # `embedding_json`; PostgreSQL stores a vector in `embedding`.
    embedding_column: str
    # SQL cast appended to the metadata placeholder on insert. SQLite keeps
    # the column as TEXT; PostgreSQL needs "::jsonb" to bind into JSONB.
    metadata_cast: str
    # SQL cast appended to the embedding placeholder on insert. SQLite keeps
    # the text; PostgreSQL needs "::vector" to bind into VECTOR.
    embedding_cast: str

    def init(self) -> None:
        """Initialize schema and backend-specific connection settings."""

    def connect(self) -> AbstractContextManager[Any]:
        """Return a context manager yielding a DB-API style connection."""

    def close(self) -> None:
        """Release process-lifetime resources held by the provider."""
