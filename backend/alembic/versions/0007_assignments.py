"""作业管理：assignments / assignment_items 表 + grading_submissions 挂作业。

assignments 是教师/组长发布的作业（标题/描述/截止时间/状态），scope 由服务端
按发布者角色写入（leader→'all' 全体可见，admin→'class' 仅自己名下学生可见）；
created_by 只存发布者学号文本，无外键（脱钩原则，同 0006）。assignment_items
是作业的协议项（协议 + 分值），按通过比例折算得分。grading_submissions 加
assignment_id 列（无外键）：作业提交路径落作业 id，聊天路径（grade_plugin
工具）落 NULL，天然不计入作业成绩。

SQLite 回退侧的同构 DDL 在 app/storage/sqlite.py 的 SQLITE_SCHEMA 中
（JSONB -> TEXT、TIMESTAMPTZ -> TEXT），存量库的幂等 ALTER 升级在其 init() 中。

与 0004/0006 相同的交错约束：postgres_schema.sql 的幂等 DDL 也携带同一变更，
本 revision 全部写成幂等形式（IF NOT EXISTS / ADD COLUMN IF NOT EXISTS）。

Revision ID: 0007_assignments
Revises: 0006_user_delete_audit
Create Date: 2026-08-27
"""
from __future__ import annotations

from alembic import op

revision = "0007_assignments"
down_revision = "0006_user_delete_audit"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS assignments (
            id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            description TEXT NOT NULL DEFAULT '',
            created_by TEXT NOT NULL,
            scope TEXT NOT NULL,
            deadline TIMESTAMPTZ NOT NULL,
            status TEXT NOT NULL DEFAULT 'active',
            created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
            updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """
    )
    op.execute(
        "CREATE INDEX IF NOT EXISTS idx_assignments_status ON assignments(status)"
    )
    op.execute(
        "CREATE INDEX IF NOT EXISTS idx_assignments_created_by ON assignments(created_by)"
    )
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS assignment_items (
            id TEXT PRIMARY KEY,
            assignment_id TEXT NOT NULL REFERENCES assignments(id) ON DELETE CASCADE,
            protocol TEXT NOT NULL,
            points INTEGER NOT NULL,
            UNIQUE(assignment_id, protocol)
        )
        """
    )
    # 作业提交落 assignment_id（无外键，脱钩）；聊天路径落 NULL 不计入作业成绩。
    op.execute(
        "ALTER TABLE grading_submissions ADD COLUMN IF NOT EXISTS assignment_id TEXT"
    )
    op.execute(
        "CREATE INDEX IF NOT EXISTS idx_grading_assignment "
        "ON grading_submissions(assignment_id, user_id)"
    )


def downgrade() -> None:
    op.execute("DROP INDEX IF EXISTS idx_grading_assignment")
    op.execute("ALTER TABLE grading_submissions DROP COLUMN IF EXISTS assignment_id")
    op.execute("DROP TABLE IF EXISTS assignment_items")
    op.execute("DROP TABLE IF EXISTS assignments")
