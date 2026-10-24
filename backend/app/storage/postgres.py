from __future__ import annotations

from contextlib import contextmanager
from pathlib import Path
from threading import Lock
from typing import Iterator, Optional

from ..config import Settings


class PostgresProvider:
    name = "postgres"
    placeholder = "%s"
    supports_manual_fts = False
    embedding_column = "embedding"
    metadata_cast = "::jsonb"
    embedding_cast = "::vector"

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        # Lazily-created, process-lifetime connection pool. Reusing connections
        # is critical under concurrency: opening a fresh PostgreSQL connection
        # per get_conn() call costs ~10-50ms of setup/auth and exhausts
        # max_connections under load. The pool keeps min_size warm connections
        # and checks them out cheaply.
        self._pool: Optional[object] = None
        self._pool_lock = Lock()

    def _load_psycopg(self):
        try:
            import psycopg  # noqa: F401
            from psycopg.rows import dict_row
        except ImportError as exc:
            raise RuntimeError(
                "PostgreSQL backend requires psycopg. Install project dependencies "
                "after adding psycopg[binary], or set DATABASE_BACKEND=sqlite."
            ) from exc
        return dict_row

    def _get_pool(self):
        # asyncio.to_thread workers can arrive together during cold start.
        # Pool construction is not idempotent, so it must be serialized.
        with self._pool_lock:
            if self._pool is None:
                dict_row = self._load_psycopg()
                try:
                    from psycopg_pool import ConnectionPool
                except ImportError as exc:
                    raise RuntimeError(
                        "PostgreSQL backend requires psycopg-pool. Install "
                        "psycopg-pool, or set DATABASE_BACKEND=sqlite."
                    ) from exc
                # This pool shares PostgreSQL with LlamaIndex's async pgvector
                # pool. Keep a bounded headroom below the server default so a
                # burst cannot starve the vector-store queries or admin work.
                self._pool = ConnectionPool(
                    conninfo=self.settings.database_url,
                    kwargs={"row_factory": dict_row},
                    min_size=4,
                    max_size=self.settings.database_pool_max_size,
                    timeout=30.0,
                )
            return self._pool

    def close(self) -> None:
        """Close the process-lifetime pool before interpreter shutdown."""
        with self._pool_lock:
            pool = self._pool
            self._pool = None
        if pool is not None:
            pool.close()

    @property
    def schema_path(self) -> Path:
        return Path(__file__).with_name("postgres_schema.sql")

    def init(self) -> None:
        # Use the pool so init_db() benefits from warm connections on repeat
        # calls. The pool opens min_size connections eagerly in a background
        # worker; schema creation runs on the first checked-out connection.
        pool = self._get_pool()
        with pool.connection() as conn:
            with conn.cursor() as cur:
                cur.execute(self.schema_path.read_text(encoding="utf-8"))
                cur.execute("SELECT to_regclass('rag_requests') AS name")
                if cur.fetchone()["name"]:
                    for name, ddl in (("request_id", "TEXT"), ("input_hash", "TEXT"),
                                      ("initial_request", "INTEGER NOT NULL DEFAULT 0"),
                                      ("purpose", "TEXT NOT NULL DEFAULT 'chat'")):
                        cur.execute(f"ALTER TABLE rag_requests ADD COLUMN IF NOT EXISTS {name} {ddl}")
                    cur.execute("UPDATE rag_requests SET request_id=id, initial_request=1 WHERE request_id IS NULL")
                cur.execute(self.schema_path.with_name("rag_schema.sql").read_text(encoding="utf-8"))
                cur.execute(self.schema_path.with_name("usage_schema.sql").read_text(encoding="utf-8"))
            conn.commit()

    @contextmanager
    def connect(self) -> Iterator[object]:
        pool = self._get_pool()
        # pool.connection() checks out a connection, yields it, and returns it
        # to the pool on exit (rolling back on exception). The explicit commit
        # before return persists writes for the request.
        with pool.connection() as conn:
            yield conn
            conn.commit()
