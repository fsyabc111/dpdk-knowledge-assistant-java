"""M8: user hierarchy — users.created_by（组长 → 老师 → 学生树状管理）。

created_by 记录账号的创建者：CLI 引导的组长为 NULL（根节点）；API 创建的
账号为创建者 subject。老师（admin）只能管理 created_by=自己 的纯 viewer
学生，组长（leader）管理全部。SQLite 回退侧的同构 DDL 在
app/storage/sqlite.py 的 SQLITE_SCHEMA 与 init() 升级逻辑中。

Revision ID: 0005_user_hierarchy
Revises: 0004_grading_submissions
Create Date: 2026-08-27
"""
from __future__ import annotations

from alembic import op

revision = "0005_user_hierarchy"
down_revision = "0004_grading_submissions"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("ALTER TABLE users ADD COLUMN created_by TEXT REFERENCES users(id)")
    op.execute("CREATE INDEX users_created_by_idx ON users(created_by)")


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS users_created_by_idx")
    op.execute("ALTER TABLE users DROP COLUMN created_by")
