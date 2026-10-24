"""S2：请求身份分层、输入摘要与会话 release 绑定。

历史 id 保留为 task_id，不重写问题关联；无法还原原文的 input_hash 保持 NULL。
回退保留新增列及唯一约束，避免丢失已受理任务身份和会话绑定。
"""
import sqlalchemy as sa
from alembic import op

revision = "0012_request_identity"
down_revision = "0011_usage_records"
branch_labels = None
depends_on = None


def upgrade():
    bind = op.get_bind()
    columns = {column["name"] for column in sa.inspect(bind).get_columns("rag_requests")}
    for name, kind, default in (("request_id", sa.Text(), None), ("input_hash", sa.Text(), None),
                                ("initial_request", sa.Integer(), "0")):
        if name not in columns:
            op.add_column("rag_requests", sa.Column(name, kind, nullable=default is None, server_default=default))
    op.execute("UPDATE rag_requests SET request_id=id, initial_request=1 WHERE request_id IS NULL")
    op.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_rag_request_session_key ON rag_requests(session_id, request_id)")
    op.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_rag_request_initial_key ON rag_requests(user_id, request_id) WHERE initial_request=1")
    session_columns = {column["name"] for column in sa.inspect(bind).get_columns("assistant_sessions")}
    if "rag_release_id" not in session_columns:
        op.add_column("assistant_sessions", sa.Column("rag_release_id", sa.Text(), nullable=True))


def downgrade():
    # 只回退版本标记并保留数据；旧代码不兼容新身份契约，回退须关闭产品入口。
    pass
