"""上游用量账本表（P1 全链路用量与成本统计）。

新增 ``upstream_usage_records``：逐条记录各阶段的上游花费（生成 token、
embedding token、harness/MCP 调用次数与耗时），供会话/用户/功能维度聚合。

与 alembic 之外的关系：``app/storage/usage_schema.sql`` 是同一份 DDL 的
运行期副本（新建库由 provider.init() 直接建表，覆盖 SQLite 与 PG 两种后端）。
两处必须保持一致——本 revision 负责升级**存量**库，schema 文件负责**新建**库。

DDL 全部 ``IF NOT EXISTS`` 且逐字对齐 schema 文件，因此在已经把 schema 文件
跑过的库上重复执行是安全的（no-op）。

downgrade **不删表**：账本是财务口径的审计数据，回退代码不等于可以把历史花费
删掉。要清理请显式执行 DROP TABLE。
"""
from alembic import op

revision = "0011_usage_records"
down_revision = "0010_li_fts_zhparser"
branch_labels = None
depends_on = None

# 与 app/storage/usage_schema.sql 逐字一致（除注释）。token 三列可空表示未知。
_UPGRADE_SQL = """
CREATE TABLE IF NOT EXISTS upstream_usage_records (
    id                TEXT PRIMARY KEY,
    request_id        TEXT,
    subject           TEXT,
    session_id        TEXT,
    kind              TEXT NOT NULL,
    stage             TEXT NOT NULL,
    model             TEXT,
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    total_tokens      INTEGER,
    usage_source      TEXT NOT NULL,
    call_count        INTEGER NOT NULL DEFAULT 1,
    duration_ms       INTEGER,
    cost_micros       INTEGER,
    currency          TEXT,
    price_version     TEXT,
    created_at        TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_usage_created ON upstream_usage_records(created_at);
CREATE INDEX IF NOT EXISTS idx_usage_subject_created ON upstream_usage_records(subject, created_at);
CREATE INDEX IF NOT EXISTS idx_usage_stage_created ON upstream_usage_records(stage, created_at);
"""


def upgrade() -> None:
    op.execute(_UPGRADE_SQL)


def downgrade() -> None:
    # 有意为空：不删账本数据。理由见模块文档字符串。
    pass
