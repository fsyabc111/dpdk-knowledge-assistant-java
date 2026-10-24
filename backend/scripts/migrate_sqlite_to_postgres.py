"""One-shot migration: copy knowledge base + chat history from the SQLite
database into the configured PostgreSQL backend.

Usage:
    DATABASE_BACKEND=postgres DATABASE_URL=postgresql:///assistant \
        .venv/bin/python scripts/migrate_sqlite_to_postgres.py \
        --sqlite ./data/assistant.db --truncate

The script:
- reads from the SQLite file directly (sqlite3, read-only),
- writes through the project's PostgresProvider / get_conn(),
- converts embedding_json (TEXT) -> embedding (VECTOR),
- converts metadata_json (TEXT) -> metadata_json (JSONB),
- skips knowledge_chunks_fts (PostgreSQL uses the generated search_vector).

Re-running with --truncate first wipes the target tables, so the script is
idempotent. Without --truncate it uses ON CONFLICT ... DO NOTHING so existing
rows are kept.
"""
from __future__ import annotations

import argparse
import json
import sqlite3
import sys
from pathlib import Path
from typing import Any, Iterable, List, Sequence, Tuple

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.db import get_conn, get_database_provider  # noqa: E402

# (sqlite_table, pg_table, columns, needs_embedding_cast, needs_metadata_cast, where_clause)
# Insert order respects FK dependencies. The where_clause drops orphan rows
# that SQLite kept because foreign-key enforcement was off historically.
TABLE_PLAN: List[Tuple[str, str, List[str], bool, bool, str]] = [
    ("assistant_sessions", "assistant_sessions",
     ["id", "user_id", "title", "created_at", "updated_at"], False, False, ""),
    ("assistant_messages", "assistant_messages",
     ["id", "session_id", "role", "content", "metadata_json", "created_at"], False, True, ""),
    ("knowledge_documents", "knowledge_documents",
     ["id", "source_path", "source_type", "title", "content_hash", "git_commit", "indexed_at"],
     False, False, ""),
    ("knowledge_chunks", "knowledge_chunks",
     ["id", "document_id", "source_path", "section", "content", "metadata_json", "embedding_json"],
     True, True, ""),
    ("knowledge_source_files", "knowledge_source_files",
     ["source_path", "content_hash", "document_id", "indexed_at"], False, False, ""),
    ("knowledge_indexes", "knowledge_indexes",
     ["id", "provider", "embedding_model", "chunker_version", "source_config_hash",
      "status", "created_at", "completed_at"], False, False, ""),
    ("assistant_citations", "assistant_citations",
     ["id", "message_id", "chunk_id", "score", "rank"], False, False,
     "WHERE chunk_id IN (SELECT id FROM knowledge_chunks) "
     "AND message_id IN (SELECT id FROM assistant_messages)"),
    ("teaching_faqs", "teaching_faqs",
     ["id", "question", "answer", "tags", "visibility", "created_at"], False, False, ""),
]


def _open_sqlite(path: Path) -> sqlite3.Connection:
    if not path.exists():
        raise FileNotFoundError(f"SQLite database not found: {path}")
    conn = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
    conn.row_factory = sqlite3.Row
    return conn


def _truncate_target_tables() -> None:
    tables = ", ".join(pg_table for _, pg_table, _, _, _, _ in TABLE_PLAN)
    with get_conn() as conn:
        with conn.cursor() as cur:
            cur.execute(f"TRUNCATE {tables} RESTART IDENTITY CASCADE;")


def _build_insert_sql(
    pg_table: str,
    columns: Sequence[str],
    ph: str,
    embedding_cast: str,
    metadata_cast: str,
) -> str:
    # On the PG side the embedding column is named `embedding`, not `embedding_json`.
    pg_cols = [c if c != "embedding_json" else "embedding" for c in columns]
    pg_col_list = ", ".join(pg_cols)
    values: List[str] = []
    for col in columns:
        if col == "embedding_json":
            values.append(f"{ph}{embedding_cast}")
        elif col == "metadata_json":
            values.append(f"{ph}{metadata_cast}")
        else:
            values.append(ph)
    return (
        f"INSERT INTO {pg_table} ({pg_col_list}) VALUES ({', '.join(values)}) "
        f"ON CONFLICT DO NOTHING"
    )


def _normalize_embedding(value: Any) -> Any:
    if value is None:
        return None
    if isinstance(value, str):
        text = value.strip()
        if not text or text == "[]":
            return None
        try:
            vec = json.loads(text)
        except json.JSONDecodeError:
            return None
        if not isinstance(vec, list) or not vec:
            return None
        return "[" + ",".join(f"{float(x):.7f}" for x in vec) + "]"
    return None


def _normalize_metadata(value: Any) -> Any:
    if value is None:
        return "{}"
    if isinstance(value, (dict, list)):
        return json.dumps(value, ensure_ascii=False)
    text = value.strip() if isinstance(value, str) else ""
    if not text:
        return "{}"
    # Validate JSON; if invalid, fall back to empty object so insert does not fail.
    try:
        json.loads(text)
        return text
    except json.JSONDecodeError:
        return "{}"


def _stream_rows(
    sqlite_conn: sqlite3.Connection,
    sqlite_table: str,
    columns: Sequence[str],
    batch_size: int,
    where_clause: str = "",
) -> Iterable[List[Any]]:
    col_list = ", ".join(columns)
    cursor = sqlite_conn.execute(f"SELECT {col_list} FROM {sqlite_table} {where_clause}")
    while True:
        batch = cursor.fetchmany(batch_size)
        if not batch:
            cursor.close()
            return
        yield [tuple(row) for row in batch]


def _migrate_table(
    sqlite_conn: sqlite3.Connection,
    sqlite_table: str,
    pg_table: str,
    columns: Sequence[str],
    embedding_cast: bool,
    metadata_cast: bool,
    ph: str,
    emb_cast: str,
    meta_cast: str,
    batch_size: int,
    where_clause: str = "",
) -> int:
    needs_emb = "embedding_json" in columns
    needs_meta = "metadata_json" in columns
    insert_sql = _build_insert_sql(pg_table, columns, ph, emb_cast, meta_cast)
    total = 0
    for batch in _stream_rows(sqlite_conn, sqlite_table, columns, batch_size, where_clause):
        normalized: List[Tuple[Any, ...]] = []
        for row in batch:
            row_list = list(row)
            for index, col in enumerate(columns):
                if col == "embedding_json" and needs_emb:
                    row_list[index] = _normalize_embedding(row_list[index])
                elif col == "metadata_json" and needs_meta:
                    row_list[index] = _normalize_metadata(row_list[index])
            normalized.append(tuple(row_list))
        with get_conn() as conn:
            with conn.cursor() as cur:
                cur.executemany(insert_sql, normalized)
        total += len(normalized)
        print(f"  {pg_table}: {total} rows", flush=True)
    return total


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sqlite", default="./data/assistant.db",
                        help="Path to the SQLite database file")
    parser.add_argument("--batch", type=int, default=500,
                        help="Rows per batch (default 500)")
    parser.add_argument("--truncate", action="store_true",
                        help="Truncate target PG tables before migration")
    args = parser.parse_args()

    provider = get_database_provider()
    if provider.name != "postgres":
        raise SystemExit(
            f"This script requires DATABASE_BACKEND=postgres (got {provider.name!r}). "
            "Set DATABASE_BACKEND=postgres and DATABASE_URL=postgresql:///assistant "
            "before running."
        )
    ph = provider.placeholder
    emb_cast = provider.embedding_cast
    meta_cast = provider.metadata_cast

    sqlite_path = (ROOT / args.sqlite).resolve() if not Path(args.sqlite).is_absolute() else Path(args.sqlite)
    print(f"Source SQLite: {sqlite_path}")
    print(f"Target PostgreSQL: provider={provider.name}")

    sqlite_conn = _open_sqlite(sqlite_path)
    if args.truncate:
        print("Truncating target tables ...")
        _truncate_target_tables()

    grand_total = 0
    for sqlite_table, pg_table, columns, needs_emb, needs_meta, where_clause in TABLE_PLAN:
        print(f"\nMigrating {sqlite_table} -> {pg_table}")
        if where_clause:
            print(f"  filter: {where_clause.strip()}")
        count = _migrate_table(
            sqlite_conn,
            sqlite_table,
            pg_table,
            columns,
            needs_emb,
            needs_meta,
            ph,
            emb_cast,
            meta_cast,
            args.batch,
            where_clause,
        )
        grand_total += count
        print(f"  done: {count} rows")

    sqlite_conn.close()
    print(f"\nMigration complete. Total rows migrated: {grand_total}")


if __name__ == "__main__":
    main()
