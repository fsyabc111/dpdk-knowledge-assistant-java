"""讲解任务身份标记；仅核实旧确定性身份后回填，保留原 input_hash。"""

import uuid

import sqlalchemy as sa
from alembic import op

revision = "0015_explain_purpose"
down_revision = "0014_grading_task_job_id"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("ALTER TABLE rag_requests ADD COLUMN IF NOT EXISTS purpose TEXT NOT NULL DEFAULT 'chat'")
    conn = op.get_bind()
    rows = conn.execute(sa.text(
        "SELECT id,session_id,request_id,user_id FROM rag_requests "
        "WHERE session_id LIKE 'explain-%' AND purpose='chat'"
    )).mappings().all()
    for row in rows:
        submission_id = row["session_id"][len("explain-"):]
        expected = str(uuid.uuid5(uuid.NAMESPACE_URL, f"assignment-explain:{submission_id}"))
        if row["request_id"] != expected:
            continue
        submission = conn.execute(sa.text(
            "SELECT user_id FROM grading_submissions WHERE id=:sid"
        ), {"sid": submission_id}).mappings().first()
        if submission and submission["user_id"] == row["user_id"]:
            conn.execute(sa.text("UPDATE rag_requests SET purpose='explain' WHERE id=:id"),
                         {"id": row["id"]})


def downgrade() -> None:
    # 业务身份不可丢；旧二进制回退只能停新讲解写入，优先前滚。
    pass
