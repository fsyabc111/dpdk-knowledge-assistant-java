"""S3-c：任务恢复原语——执行输入临时保管与 harness 作业账本。

新增 ``rag_task_inputs`` 与 ``rag_task_jobs``（decisions §8）：

- ``rag_task_inputs`` 保管任务存活期的原始执行输入（原始问题全文、寄存源码），
  不脱敏、仅归属用户任务 scope；任务到达终态即清理，``expires_at`` 为 24h 兜底。
  独立成表便于备份排除。
- ``rag_task_jobs`` 是 harness 作业账本：``(task_id, job_id)`` 唯一，
  ``submission_done`` 作为成绩落库幂等闸，恢复重放与正常路径共用。

与 ``app/storage/rag_schema.sql`` 逐字同构：DDL 全部 ``IF NOT EXISTS``，对
已经由 init_db() 应用过 schema 文件的库重复执行是安全的（no-op）。
"""
from alembic import op

revision = "0013_task_recovery"
down_revision = "0012_request_identity"
branch_labels = None
depends_on = None

# 与 app/storage/rag_schema.sql 中的同名片段逐字一致（除注释）。
_UPGRADE_SQL = """
CREATE TABLE IF NOT EXISTS rag_task_inputs (
    task_id TEXT PRIMARY KEY,
    question TEXT NOT NULL,
    attachment_name TEXT,
    attachment_suffix TEXT,
    source_text TEXT,
    source_origin TEXT,
    created_at TEXT NOT NULL,
    expires_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rag_task_inputs_expiry ON rag_task_inputs(expires_at);
CREATE TABLE IF NOT EXISTS rag_task_jobs (
    id TEXT PRIMARY KEY,
    task_id TEXT NOT NULL,
    job_id TEXT NOT NULL,
    tool TEXT NOT NULL,
    protocol TEXT,
    code_hash TEXT,
    status TEXT NOT NULL,
    result_json TEXT,
    submission_done INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    UNIQUE(task_id, job_id)
);
CREATE INDEX IF NOT EXISTS idx_rag_task_jobs_task ON rag_task_jobs(task_id);
"""


def upgrade() -> None:
    op.execute(_UPGRADE_SQL)


def downgrade() -> None:
    # 回退保留数据，旧代码须关闭产品入口：临时输入与作业账本不回滚删除，
    # 要清理请显式 DROP TABLE（与 0008/0011 同一约定）。
    pass
