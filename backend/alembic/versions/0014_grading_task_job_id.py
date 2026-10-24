"""聊天评测成绩与任务作业账本建立唯一关联，供同事务落库及停号清理。"""

from alembic import op

revision = "0014_grading_task_job_id"
down_revision = "0013_task_recovery"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("ALTER TABLE grading_submissions ADD COLUMN IF NOT EXISTS task_job_id TEXT")
    op.execute(
        "CREATE UNIQUE INDEX IF NOT EXISTS idx_grading_task_job_id "
        "ON grading_submissions(task_job_id) WHERE task_job_id IS NOT NULL"
    )


def downgrade() -> None:
    # 保留成绩与账本关联；回退旧二进制不会写此列，但不应丢失现有身份数据。
    pass
