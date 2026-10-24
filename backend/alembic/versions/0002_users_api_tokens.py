"""M6: users / api_tokens tables for account auth mode.

users.id 即学号（^U\\d{11}$），password_hash 为 bcrypt；api_tokens 只存
sha256(token)，明文 token 仅在登录响应中返回一次。SQLite 回退侧的同构 DDL
在 app/storage/sqlite.py 的 SQLITE_SCHEMA 中（BOOLEAN -> INTEGER）。

Revision ID: 0002_users_api_tokens
Revises: 0001_baseline
Create Date: 2026-08-21
"""
from __future__ import annotations

from alembic import op

revision = "0002_users_api_tokens"
down_revision = "0001_baseline"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        CREATE TABLE users (
            id TEXT PRIMARY KEY,
            password_hash TEXT NOT NULL,
            roles TEXT NOT NULL,
            daily_quota INTEGER,
            enabled BOOLEAN NOT NULL DEFAULT TRUE,
            created_at TIMESTAMPTZ NOT NULL
        )
        """
    )
    op.execute(
        """
        CREATE TABLE api_tokens (
            token_hash TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id),
            issued_at TIMESTAMPTZ NOT NULL,
            expires_at TIMESTAMPTZ NOT NULL,
            revoked_at TIMESTAMPTZ
        )
        """
    )
    op.execute("CREATE INDEX api_tokens_user_id_idx ON api_tokens(user_id)")


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS api_tokens")
    op.execute("DROP TABLE IF EXISTS users")
