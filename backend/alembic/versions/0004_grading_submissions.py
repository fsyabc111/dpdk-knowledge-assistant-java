"""M1 agent 层:grading_submissions 表 + usage_counters 增加 kind 维度。

grading_submissions 存评测最小集（学生/会话/协议/分数/逐用例结果 JSON/代码
hash),不存源码全文（D12)。usage_counters 加 kind 列区分 chat/grading 配额，
主键从 (subject, day) 扩为 (subject, day, kind)，存量行 kind 落默认 'chat'。
SQLite 回退侧的同构 DDL 在 app/storage/sqlite.py 的 SQLITE_SCHEMA 中
(JSONB -> TEXT,TIMESTAMPTZ -> TEXT)。

注意：postgres_schema.sql 的幂等 DDL 也携带同一变更（init_db() 每次启动都会
应用），与部署期 `alembic upgrade head` 的先后顺序不确定，因此本
revision 全部写成幂等形式（IF NOT EXISTS / 守卫 DO 块）,两条路径可任意交错。

Revision ID: 0004_grading_submissions
Revises: 0003_login_hardening
Create Date: 2026-08-23
"""
from __future__ import annotations

from alembic import op

revision = "0004_grading_submissions"
down_revision = "0003_login_hardening"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS grading_submissions (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id),
            session_id TEXT REFERENCES assistant_sessions(id),
            protocol TEXT NOT NULL,
            code_hash TEXT NOT NULL,
            score INTEGER NOT NULL,
            passed INTEGER NOT NULL,
            total INTEGER NOT NULL,
            results_json JSONB NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        )
        """
    )
    op.execute(
        "CREATE INDEX IF NOT EXISTS idx_grading_user "
        "ON grading_submissions(user_id, created_at DESC)"
    )
    op.execute(
        "CREATE INDEX IF NOT EXISTS idx_grading_proto "
        "ON grading_submissions(protocol, created_at DESC)"
    )
    # chat/grading 配额共表：加 kind 列（存量行默认 'chat'），主键相应扩展。
    op.execute(
        "ALTER TABLE usage_counters ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'chat'"
    )
    op.execute(
        """
        DO $$
        BEGIN
            IF NOT EXISTS (
                SELECT 1
                FROM pg_constraint c
                JOIN pg_attribute a
                  ON a.attrelid = c.conrelid AND a.attnum = ANY(c.conkey)
                WHERE c.conrelid = 'usage_counters'::regclass
                  AND c.contype = 'p'
                  AND a.attname = 'kind'
            ) THEN
                ALTER TABLE usage_counters DROP CONSTRAINT usage_counters_pkey;
                ALTER TABLE usage_counters ADD PRIMARY KEY (subject, day, kind);
            END IF;
        END $$
        """
    )


def downgrade() -> None:
    op.execute("ALTER TABLE usage_counters DROP CONSTRAINT usage_counters_pkey")
    op.execute("ALTER TABLE usage_counters ADD PRIMARY KEY (subject, day)")
    op.execute("ALTER TABLE usage_counters DROP COLUMN kind")
    op.execute("DROP TABLE IF EXISTS grading_submissions")
