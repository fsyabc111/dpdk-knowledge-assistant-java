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
    request_id TEXT,
    input_hash TEXT,
    initial_request INTEGER NOT NULL DEFAULT 0,
    purpose TEXT NOT NULL DEFAULT 'chat',
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

CREATE UNIQUE INDEX IF NOT EXISTS idx_rag_request_session_key ON rag_requests(session_id, request_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_rag_request_initial_key ON rag_requests(user_id, request_id) WHERE initial_request=1;

-- S3-c 任务恢复（alembic 0013 同构，decisions §8）。
-- rag_task_inputs：任务执行输入（原始问题 + 寄存执行源）的临时保管，不脱敏、
-- 仅归属用户任务 scope、不出现在任何对外响应；独立成表便于备份排除。
-- 任务到达终态即清理（finish/cancel/interrupted），expires_at 只是 24h 兜底。
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
-- rag_task_jobs：harness 作业账本。一个任务可有多个评测作业（不同协议/源码）；
-- job_id 是发给 harness 的确定性作业标识，submission_done 是成绩落库幂等闸。
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
