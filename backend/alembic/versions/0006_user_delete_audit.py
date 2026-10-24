"""M9: admin_audit_log 审计表 + grading_submissions 与 users 脱钩。

admin_audit_log 记录敏感管理操作（当前为批量删除账号）：操作者、动作、
目标学号列表与时间，不存密码等敏感内容。删除账号属于不可恢复操作，
仅日志留痕不便回溯，故落表。

grading_submissions.user_id 去掉对 users(id) 的外键：删除账号后成绩保留，
user_id 成为历史学号文本（脱钩），不再阻塞 users 行的删除。SQLite 回退侧
本就未声明该外键，只需在 app/storage/sqlite.py 的 SQLITE_SCHEMA 中补
admin_audit_log 的同构 DDL（JSONB -> TEXT、TIMESTAMPTZ -> TEXT）。

与 0004 相同的交错约束：postgres_schema.sql 的幂等 DDL 也携带同一变更，
本 revision 全部写成幂等形式（IF NOT EXISTS / DROP CONSTRAINT IF EXISTS）。

Revision ID: 0006_user_delete_audit
Revises: 0005_user_hierarchy
Create Date: 2026-08-27
"""
from __future__ import annotations

from alembic import op

revision = "0006_user_delete_audit"
down_revision = "0005_user_hierarchy"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS admin_audit_log (
            id TEXT PRIMARY KEY,
            actor TEXT NOT NULL,
            action TEXT NOT NULL,
            targets_json JSONB NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """
    )
    op.execute(
        "CREATE INDEX IF NOT EXISTS idx_admin_audit_log_created "
        "ON admin_audit_log(created_at DESC)"
    )
    # 成绩与账号脱钩：删除用户后成绩行保留，user_id 退化为历史学号文本。
    op.execute(
        "ALTER TABLE grading_submissions "
        "DROP CONSTRAINT IF EXISTS grading_submissions_user_id_fkey"
    )


def downgrade() -> None:
    op.execute(
        "ALTER TABLE grading_submissions "
        "ADD CONSTRAINT grading_submissions_user_id_fkey "
        "FOREIGN KEY (user_id) REFERENCES users(id)"
    )
    op.execute("DROP TABLE IF EXISTS admin_audit_log")
