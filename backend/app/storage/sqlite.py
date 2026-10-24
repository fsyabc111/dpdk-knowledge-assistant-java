from __future__ import annotations

import sqlite3
import uuid
from contextlib import contextmanager
from pathlib import Path
from typing import Iterator

from ..config import Settings

RAG_SCHEMA = Path(__file__).with_name("rag_schema.sql").read_text(encoding="utf-8")
USAGE_SCHEMA = Path(__file__).with_name("usage_schema.sql").read_text(encoding="utf-8")

SQLITE_SCHEMA = """
CREATE TABLE IF NOT EXISTS assistant_sessions (
    id TEXT PRIMARY KEY,
    user_id TEXT,
    title TEXT,
    memory_summary TEXT NOT NULL DEFAULT '',
    memory_summary_through_message_id TEXT,
    rag_release_id TEXT,
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS assistant_messages (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS knowledge_documents (
    id TEXT PRIMARY KEY,
    source_path TEXT NOT NULL UNIQUE,
    source_type TEXT NOT NULL,
    title TEXT,
    content_hash TEXT NOT NULL,
    git_commit TEXT,
    indexed_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL,
    source_path TEXT NOT NULL,
    section TEXT,
    content TEXT NOT NULL,
    metadata_json TEXT NOT NULL DEFAULT '{}',
    embedding_json TEXT NOT NULL DEFAULT '[]'
);

CREATE TABLE IF NOT EXISTS assistant_citations (
    id TEXT PRIMARY KEY,
    message_id TEXT NOT NULL,
    chunk_id TEXT NOT NULL,
    citation_engine TEXT NOT NULL DEFAULT 'legacy',
    score REAL NOT NULL,
    rank INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS teaching_faqs (
    id TEXT PRIMARY KEY,
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    tags TEXT,
    visibility TEXT NOT NULL DEFAULT 'student',
    created_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS knowledge_indexes (
    id TEXT PRIMARY KEY,
    provider TEXT NOT NULL,
    embedding_model TEXT NOT NULL,
    chunker_version TEXT NOT NULL,
    source_config_hash TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TEXT NOT NULL,
    completed_at TEXT
);

CREATE TABLE IF NOT EXISTS knowledge_source_files (
    source_path TEXT PRIMARY KEY,
    content_hash TEXT NOT NULL,
    document_id TEXT NOT NULL,
    indexed_at TEXT NOT NULL
);

-- Per-subject daily usage for quota checks (rate_limit.py). kind (alembic
-- 0004 同构)区分 chat / grading 配额；存量库由 init() 重建表升级。
CREATE TABLE IF NOT EXISTS usage_counters (
    subject TEXT NOT NULL,
    day TEXT NOT NULL,
    kind TEXT NOT NULL DEFAULT 'chat',
    chat_count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(subject, day, kind)
);

-- 评测记录（对应 alembic 0004，PostgreSQL 侧为 JSONB/TIMESTAMPTZ，此处沿用
-- metadata_json 的 TEXT 约定；不存源码全文，只存 sha256）。
-- assignment_id（alembic 0007）：作业提交落作业 id，聊天路径落 NULL（无外键，
-- 脱钩）；存量库由 init() 中的 ALTER TABLE 升级。
CREATE TABLE IF NOT EXISTS grading_submissions (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    session_id TEXT,
    protocol TEXT NOT NULL,
    code_hash TEXT NOT NULL,
    score INTEGER NOT NULL,
    passed INTEGER NOT NULL,
    total INTEGER NOT NULL,
    results_json TEXT NOT NULL DEFAULT '{}',
    assignment_id TEXT,
    task_job_id TEXT,
    created_at TEXT NOT NULL
);

-- 作业管理（alembic 0007，PostgreSQL 侧为 TIMESTAMPTZ）：assignments 的 scope
-- 服务端按角色写入（leader→'all'，admin→'class'），created_by 只存学号文本
-- （无外键，脱钩原则）；assignment_items 是协议项（协议 + 分值）。
CREATE TABLE IF NOT EXISTS assignments (
    id TEXT PRIMARY KEY,
    title TEXT NOT NULL,
    description TEXT NOT NULL DEFAULT '',
    created_by TEXT NOT NULL,
    scope TEXT NOT NULL,
    deadline TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'active',
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_assignments_status ON assignments(status);
CREATE INDEX IF NOT EXISTS idx_assignments_created_by ON assignments(created_by);

CREATE TABLE IF NOT EXISTS assignment_items (
    id TEXT PRIMARY KEY,
    assignment_id TEXT NOT NULL REFERENCES assignments(id) ON DELETE CASCADE,
    protocol TEXT NOT NULL,
    points INTEGER NOT NULL,
    UNIQUE(assignment_id, protocol)
);

-- M6 账号体系（对应 alembic 0002_users_api_tokens，PostgreSQL 侧为 BOOLEAN/TIMESTAMPTZ）。
-- must_change_password（alembic 0003_login_hardening）：新库默认 1，因为新库里的账号
-- 全是初始密码账号；存量库由 init() 中的 ALTER TABLE 以 DEFAULT 0 升级。
-- created_by（alembic 0005_user_hierarchy）：账号归属，组长→老师→学生树状管理；
-- CLI 引导的根账号为 NULL。存量库由 init() 中的 ALTER TABLE 升级。
CREATE TABLE IF NOT EXISTS users (
    id TEXT PRIMARY KEY,
    password_hash TEXT NOT NULL,
    roles TEXT NOT NULL,
    daily_quota INTEGER,
    enabled INTEGER NOT NULL DEFAULT 1,
    must_change_password INTEGER NOT NULL DEFAULT 1,
    created_at TEXT NOT NULL,
    created_by TEXT REFERENCES users(id)
);

CREATE INDEX IF NOT EXISTS idx_users_created_by ON users(created_by);

CREATE TABLE IF NOT EXISTS api_tokens (
    token_hash TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    issued_at TEXT NOT NULL,
    expires_at TEXT NOT NULL,
    revoked_at TEXT
);

CREATE INDEX IF NOT EXISTS idx_api_tokens_user_id ON api_tokens(user_id);

-- 忘记密码"申请-审批-一次性令牌改密"流程（alembic 0003_login_hardening）。
-- 令牌明文仅向管理员展示一次，库里只存 sha256 哈希。
CREATE TABLE IF NOT EXISTS password_reset_requests (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    reset_token_hash TEXT,
    reset_expires_at TEXT,
    created_at TEXT NOT NULL,
    decided_at TEXT,
    decided_by TEXT
);

CREATE INDEX IF NOT EXISTS idx_password_reset_requests_status ON password_reset_requests(status);

-- 管理操作审计（alembic 0006_user_delete_audit）：批量删除账号等敏感操作留痕，
-- 只记操作者/动作/目标学号/时间，不存密码等敏感内容。
CREATE TABLE IF NOT EXISTS admin_audit_log (
    id TEXT PRIMARY KEY,
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    targets_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_log_created ON admin_audit_log(created_at DESC);

CREATE INDEX IF NOT EXISTS idx_assistant_messages_session_id ON assistant_messages(session_id);
CREATE INDEX IF NOT EXISTS idx_assistant_citations_message_id ON assistant_citations(message_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_documents_source_path ON knowledge_documents(source_path);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_document_id ON knowledge_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_source_path ON knowledge_chunks(source_path);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_section ON knowledge_chunks(section);
CREATE INDEX IF NOT EXISTS idx_knowledge_indexes_status ON knowledge_indexes(status);
CREATE INDEX IF NOT EXISTS idx_knowledge_source_files_document_id ON knowledge_source_files(document_id);
CREATE INDEX IF NOT EXISTS idx_grading_user ON grading_submissions(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_grading_proto ON grading_submissions(protocol, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_grading_assignment ON grading_submissions(assignment_id, user_id);

CREATE VIRTUAL TABLE IF NOT EXISTS knowledge_chunks_fts USING fts5(
    chunk_id UNINDEXED,
    source_path,
    section,
    content,
    metadata_text,
    tokenize = 'unicode61'
);
"""


class SQLiteProvider:
    name = "sqlite"
    placeholder = "?"
    supports_manual_fts = True
    embedding_column = "embedding_json"
    metadata_cast = ""
    embedding_cast = ""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings

    def _configure(self, conn: sqlite3.Connection) -> None:
        # WAL and a short busy timeout reduce lock contention under concurrent chat traffic.
        conn.execute("PRAGMA journal_mode=WAL;")
        conn.execute("PRAGMA synchronous=NORMAL;")
        conn.execute("PRAGMA busy_timeout=5000;")
        conn.execute("PRAGMA temp_store=MEMORY;")
        conn.execute("PRAGMA foreign_keys=ON;")

    def init(self) -> None:
        db_path = self.settings.db_path
        db_path.parent.mkdir(parents=True, exist_ok=True)
        with sqlite3.connect(db_path) as conn:
            self._configure(conn)
            conn.executescript(SQLITE_SCHEMA)
            # 旧业务表先补列，再执行含新索引的共同基线。
            request_columns = {row[1] for row in conn.execute("PRAGMA table_info(rag_requests)")}
            if request_columns:
                for name, ddl in (("request_id", "TEXT"), ("input_hash", "TEXT"),
                                  ("initial_request", "INTEGER NOT NULL DEFAULT 0"),
                                  ("purpose", "TEXT NOT NULL DEFAULT 'chat'")):
                    if name not in request_columns:
                        conn.execute(f"ALTER TABLE rag_requests ADD COLUMN {name} {ddl}")
                if "purpose" not in request_columns:
                    rows = conn.execute(
                        "SELECT id,session_id,request_id,user_id FROM rag_requests "
                        "WHERE session_id LIKE 'explain-%'"
                    ).fetchall()
                    for row in rows:
                        sid = row[1][len("explain-"):]
                        expected = str(uuid.uuid5(uuid.NAMESPACE_URL, f"assignment-explain:{sid}"))
                        if row[2] != expected:
                            continue
                        submission = conn.execute(
                            "SELECT user_id FROM grading_submissions WHERE id=?", (sid,)
                        ).fetchone()
                        if submission and submission[0] == row[3]:
                            conn.execute("UPDATE rag_requests SET purpose='explain' WHERE id=?", (row[0],))
                conn.execute("UPDATE rag_requests SET request_id=id, initial_request=1 WHERE request_id IS NULL")
            conn.executescript(RAG_SCHEMA)
            conn.executescript(USAGE_SCHEMA)
            # SQLite does not support ADD COLUMN IF NOT EXISTS. Keep the
            # explicit fallback backend upgradeable without recreating data.
            columns = {row[1] for row in conn.execute("PRAGMA table_info(assistant_sessions)")}
            if "rag_release_id" not in columns:
                conn.execute("ALTER TABLE assistant_sessions ADD COLUMN rag_release_id TEXT")
            if "memory_summary" not in columns:
                conn.execute("ALTER TABLE assistant_sessions ADD COLUMN memory_summary TEXT NOT NULL DEFAULT ''")
            if "memory_summary_through_message_id" not in columns:
                conn.execute("ALTER TABLE assistant_sessions ADD COLUMN memory_summary_through_message_id TEXT")
            citation_columns = {row[1] for row in conn.execute("PRAGMA table_info(assistant_citations)")}
            if "citation_engine" not in citation_columns:
                conn.execute(
                    "ALTER TABLE assistant_citations ADD COLUMN citation_engine TEXT NOT NULL DEFAULT 'legacy'"
                )
            user_columns = {row[1] for row in conn.execute("PRAGMA table_info(users)")}
            if "must_change_password" not in user_columns:
                # 存量账号默认 0，不强制老用户改密（新库 DDL 默认 1，差异是有意的）。
                conn.execute(
                    "ALTER TABLE users ADD COLUMN must_change_password INTEGER NOT NULL DEFAULT 0"
                )
            if "created_by" not in user_columns:
                # 树状管理（alembic 0005）：存量账号归属置 NULL，仅组长可见/可管。
                conn.execute("ALTER TABLE users ADD COLUMN created_by TEXT REFERENCES users(id)")
                conn.execute("CREATE INDEX IF NOT EXISTS idx_users_created_by ON users(created_by)")
            usage_columns = {row[1] for row in conn.execute("PRAGMA table_info(usage_counters)")}
            if "kind" not in usage_columns:
                # SQLite 不能改主键，重建表升级 kind 维度（alembic 0004 同构）。
                # 计数器是按日累计的易失数据，重建代价可忽略。
                conn.execute("ALTER TABLE usage_counters RENAME TO usage_counters_pre_kind")
                conn.execute(
                    """
                    CREATE TABLE usage_counters (
                        subject TEXT NOT NULL,
                        day TEXT NOT NULL,
                        kind TEXT NOT NULL DEFAULT 'chat',
                        chat_count INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(subject, day, kind)
                    )
                    """
                )
                conn.execute(
                    "INSERT INTO usage_counters(subject, day, kind, chat_count) "
                    "SELECT subject, day, 'chat', chat_count FROM usage_counters_pre_kind"
                )
                conn.execute("DROP TABLE usage_counters_pre_kind")
            grading_columns = {row[1] for row in conn.execute("PRAGMA table_info(grading_submissions)")}
            if "assignment_id" not in grading_columns:
                # 作业管理（alembic 0007）：存量库的 grading_submissions 补
                # assignment_id 列；聊天路径的历史行保持 NULL，不计入作业成绩。
                conn.execute("ALTER TABLE grading_submissions ADD COLUMN assignment_id TEXT")
                conn.execute(
                    "CREATE INDEX IF NOT EXISTS idx_grading_assignment "
                    "ON grading_submissions(assignment_id, user_id)"
                )
            if "task_job_id" not in grading_columns:
                conn.execute("ALTER TABLE grading_submissions ADD COLUMN task_job_id TEXT")
            conn.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_grading_task_job_id "
                "ON grading_submissions(task_job_id) WHERE task_job_id IS NOT NULL"
            )
            conn.commit()

    @contextmanager
    def connect(self) -> Iterator[sqlite3.Connection]:
        self.init()
        conn = sqlite3.connect(self.settings.db_path)
        conn.row_factory = sqlite3.Row
        try:
            self._configure(conn)
            yield conn
            conn.commit()
        finally:
            conn.close()

    def close(self) -> None:
        """SQLite connections are request-scoped, so there is nothing to release."""
