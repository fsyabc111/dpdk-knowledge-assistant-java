"""RAG 版本发布、请求留存、问题闭环和共享知识修订。"""
from alembic import op

revision = "0008_rag_product"
down_revision = "0007_assignments"
branch_labels = None
depends_on = None

_SCHEMA = """
-- RAG 业务表两种数据库共用；JSON 和 UTC 时间使用 TEXT，避免业务 SQL 方言分支。
CREATE TABLE IF NOT EXISTS rag_releases (
    id TEXT PRIMARY KEY,
    configuration TEXT NOT NULL,
    created_at TEXT NOT NULL,
    node_count INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS rag_index_state (
    name TEXT PRIMARY KEY,
    release_id TEXT NOT NULL
);
INSERT INTO rag_index_state(name, release_id) VALUES ('active', '') ON CONFLICT(name) DO NOTHING;
CREATE TABLE IF NOT EXISTS rag_release_nodes (
    release_id TEXT NOT NULL,
    node_id TEXT NOT NULL,
    PRIMARY KEY (release_id, node_id)
);
CREATE INDEX IF NOT EXISTS idx_rag_release_node ON rag_release_nodes(node_id);
CREATE TABLE IF NOT EXISTS rag_requests (
    id TEXT PRIMARY KEY,
    user_id TEXT,
    session_id TEXT,
    message_id TEXT,
    question TEXT NOT NULL,
    context_text TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL DEFAULT 'pending',
    reason TEXT NOT NULL DEFAULT '',
    release_id TEXT NOT NULL DEFAULT '',
    evidence_json TEXT NOT NULL DEFAULT '[]',
    model_version TEXT NOT NULL DEFAULT '',
    prompt_version TEXT NOT NULL DEFAULT 'grounded-v1',
    elapsed_ms INTEGER NOT NULL DEFAULT 0,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rag_request_user ON rag_requests(user_id, created_at);
CREATE INDEX IF NOT EXISTS idx_rag_request_status ON rag_requests(status, updated_at);
CREATE TABLE IF NOT EXISTS rag_issues (
    id TEXT PRIMARY KEY,
    request_id TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL DEFAULT 'open',
    owner_id TEXT,
    note TEXT NOT NULL DEFAULT '',
    feedback TEXT NOT NULL DEFAULT '',
    merged_into TEXT,
    knowledge_id TEXT,
    verified_release_id TEXT,
    verified_at TEXT,
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS rag_issue_events (
    id TEXT PRIMARY KEY,
    issue_id TEXT NOT NULL,
    actor_id TEXT,
    action TEXT NOT NULL,
    detail TEXT NOT NULL,
    created_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS rag_knowledge (
    id TEXT PRIMARY KEY,
    created_by TEXT,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    revision INTEGER NOT NULL DEFAULT 1,
    status TEXT NOT NULL DEFAULT 'published',
    updated_at TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS rag_knowledge_revisions (
    id TEXT PRIMARY KEY,
    knowledge_id TEXT NOT NULL,
    revision INTEGER NOT NULL,
    actor_id TEXT,
    title TEXT NOT NULL,
    body TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE(knowledge_id, revision)
);
"""


def upgrade() -> None:
    op.execute(_SCHEMA)


def downgrade() -> None:
    # 保留业务数据，旧应用忽略新增表；禁止回滚时自动丢弃问题记录。
    pass
