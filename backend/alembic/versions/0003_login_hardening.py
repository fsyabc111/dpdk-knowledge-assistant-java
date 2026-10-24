"""M7: login hardening — must_change_password + password_reset_requests.

users 加 must_change_password 列（存量账号默认 FALSE，不强制老用户改密；
新建账号由 create_users 显式写入 TRUE）。password_reset_requests 支撑忘记
密码的"申请-审批-一次性令牌改密"流程，令牌只存 sha256 哈希。SQLite 回退侧
的同构 DDL 在 app/storage/sqlite.py 的 SQLITE_SCHEMA 中（BOOLEAN -> INTEGER、
TIMESTAMPTZ -> TEXT）。

Revision ID: 0003_login_hardening
Revises: 0002_users_api_tokens
Create Date: 2026-08-22
"""
from __future__ import annotations

from alembic import op

revision = "0003_login_hardening"
down_revision = "0002_users_api_tokens"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        "ALTER TABLE users ADD COLUMN must_change_password BOOLEAN NOT NULL DEFAULT FALSE"
    )
    op.execute(
        """
        CREATE TABLE password_reset_requests (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            status TEXT NOT NULL DEFAULT 'pending',
            reset_token_hash TEXT,
            reset_expires_at TIMESTAMPTZ,
            created_at TIMESTAMPTZ NOT NULL,
            decided_at TIMESTAMPTZ,
            decided_by TEXT
        )
        """
    )
    op.execute(
        "CREATE INDEX password_reset_requests_status_idx ON password_reset_requests(status)"
    )


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS password_reset_requests")
    op.execute("ALTER TABLE users DROP COLUMN must_change_password")
