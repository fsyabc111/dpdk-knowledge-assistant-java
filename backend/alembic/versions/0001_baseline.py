"""Baseline: existing idempotent DDL schema.

The full schema lives in app/storage/postgres_schema.sql and is applied by
init_db(). This empty revision only marks that state as the alembic baseline:

    python -c "from app.db import init_db; init_db()"
    alembic stamp head

All later schema changes must be added as new revisions on top of this one.

Revision ID: 0001_baseline
Revises:
Create Date: 2026-08-20
"""
from __future__ import annotations

from alembic import op  # noqa: F401  (kept for symmetry with future revisions)

revision = "0001_baseline"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Intentionally empty: baseline databases are created by init_db() and
    # adopt this revision via `alembic stamp head`.
    pass


def downgrade() -> None:
    pass
