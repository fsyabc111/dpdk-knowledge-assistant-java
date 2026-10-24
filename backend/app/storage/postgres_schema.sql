-- PostgreSQL schema baseline.
-- RAG 0008 基线为同目录 rag_schema.sql，PostgresProvider.init 在同一事务中应用。
-- Applied by `init_db()` (idempotent DDL) and frozen as the alembic baseline
-- revision (see backend/alembic/). All later schema changes must be new
-- alembic revisions; edit this file only when rebaselining.

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS assistant_sessions (
    id TEXT PRIMARY KEY,
    user_id TEXT,
    title TEXT,
    memory_summary TEXT NOT NULL DEFAULT '',
    memory_summary_through_message_id TEXT,
    rag_release_id TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

-- Existing deployments predate conversation memory. These are safe to run on
-- every startup and keep the schema upgrade independent of a manual migration.
ALTER TABLE assistant_sessions ADD COLUMN IF NOT EXISTS rag_release_id TEXT;
ALTER TABLE assistant_sessions ADD COLUMN IF NOT EXISTS memory_summary TEXT NOT NULL DEFAULT '';
ALTER TABLE assistant_sessions ADD COLUMN IF NOT EXISTS memory_summary_through_message_id TEXT;

CREATE TABLE IF NOT EXISTS assistant_messages (
    id TEXT PRIMARY KEY,
    session_id TEXT NOT NULL REFERENCES assistant_sessions(id) ON DELETE CASCADE,
    role TEXT NOT NULL,
    content TEXT NOT NULL,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS knowledge_documents (
    id TEXT PRIMARY KEY,
    source_path TEXT NOT NULL UNIQUE,
    source_type TEXT NOT NULL,
    title TEXT,
    content_hash TEXT NOT NULL,
    git_commit TEXT,
    indexed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS knowledge_chunks (
    id TEXT PRIMARY KEY,
    document_id TEXT NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    source_path TEXT NOT NULL,
    section TEXT,
    content TEXT NOT NULL,
    metadata_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding VECTOR(1024),
    search_vector TSVECTOR GENERATED ALWAYS AS (
        to_tsvector(
            'simple',
            coalesce(source_path, '') || ' ' ||
            coalesce(section, '') || ' ' ||
            coalesce(content, '') || ' ' ||
            coalesce(metadata_json::text, '')
        )
    ) STORED
);

CREATE TABLE IF NOT EXISTS assistant_citations (
    id TEXT PRIMARY KEY,
    message_id TEXT NOT NULL REFERENCES assistant_messages(id) ON DELETE CASCADE,
    -- A citation can point either to the legacy knowledge_chunks namespace or
    -- to a LlamaIndex pgvector node.  Do not attach a legacy-only FK here.
    chunk_id TEXT NOT NULL,
    citation_engine TEXT NOT NULL DEFAULT 'legacy',
    score DOUBLE PRECISION NOT NULL,
    rank INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS teaching_faqs (
    id TEXT PRIMARY KEY,
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    tags TEXT,
    visibility TEXT NOT NULL DEFAULT 'student',
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS knowledge_indexes (
    id TEXT PRIMARY KEY,
    provider TEXT NOT NULL,
    embedding_model TEXT NOT NULL,
    chunker_version TEXT NOT NULL,
    source_config_hash TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS knowledge_source_files (
    source_path TEXT PRIMARY KEY,
    content_hash TEXT NOT NULL,
    document_id TEXT NOT NULL REFERENCES knowledge_documents(id) ON DELETE CASCADE,
    indexed_at TIMESTAMPTZ NOT NULL
);

-- Per-subject daily usage for quota checks (rate_limit.py). The day is stored
-- as an ISO date string in UTC. kind (alembic 0004) separates chat from
-- grading quotas; rows written before 0004 carry the 'chat' default.
CREATE TABLE IF NOT EXISTS usage_counters (
    subject TEXT NOT NULL,
    day TEXT NOT NULL,
    kind TEXT NOT NULL DEFAULT 'chat',
    chat_count INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY(subject, day, kind)
);

-- Idempotent upgrade for databases created before the kind dimension (0004):
-- add the column, then rebuild the primary key to include it.
ALTER TABLE usage_counters ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'chat';
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
END $$;

CREATE INDEX IF NOT EXISTS idx_assistant_messages_session_id ON assistant_messages(session_id);
CREATE INDEX IF NOT EXISTS idx_assistant_citations_message_id ON assistant_citations(message_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_documents_source_path ON knowledge_documents(source_path);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_document_id ON knowledge_chunks(document_id);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_source_path ON knowledge_chunks(source_path);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_section ON knowledge_chunks(section);
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_search_vector ON knowledge_chunks USING GIN(search_vector);
CREATE INDEX IF NOT EXISTS idx_knowledge_indexes_status ON knowledge_indexes(status);
CREATE INDEX IF NOT EXISTS idx_knowledge_source_files_document_id ON knowledge_source_files(document_id);

-- Vector index for semantic candidate recall. HNSW works well across a wide
-- range of table sizes and is cheap to maintain on insert. Building it at
-- init time (even on an empty table) is supported by pgvector.
CREATE INDEX IF NOT EXISTS idx_knowledge_chunks_embedding
    ON knowledge_chunks USING hnsw (embedding vector_cosine_ops);

-- Idempotent upgrade for databases created before LlamaIndex citations.
ALTER TABLE assistant_citations
    DROP CONSTRAINT IF EXISTS assistant_citations_chunk_id_fkey;
ALTER TABLE assistant_citations
    ADD COLUMN IF NOT EXISTS citation_engine TEXT NOT NULL DEFAULT 'legacy';
CREATE INDEX IF NOT EXISTS idx_assistant_citations_engine ON assistant_citations(citation_engine);

-- Grading records (alembic 0004, D12): minimal set per submission — no source
-- code, only its sha256. users comes from alembic 0002, so on a fresh database
-- where init_db() runs before `alembic upgrade head` the users table does not
-- exist yet; the DO guard keeps that startup order working (0004 itself is
-- idempotent and converges on the same DDL).
-- user_id intentionally carries no FK (alembic 0006): deleting an account keeps
-- its grading rows, with user_id degrading to a historical id string.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = current_schema() AND table_name = 'users')
       AND NOT EXISTS (SELECT 1 FROM information_schema.tables
                       WHERE table_schema = current_schema() AND table_name = 'grading_submissions') THEN
        CREATE TABLE grading_submissions (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL,
            session_id TEXT REFERENCES assistant_sessions(id),
            protocol TEXT NOT NULL,
            code_hash TEXT NOT NULL,
            score INTEGER NOT NULL,
            passed INTEGER NOT NULL,
            total INTEGER NOT NULL,
            results_json JSONB NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT now()
        );
    END IF;
    IF EXISTS (SELECT 1 FROM information_schema.tables
               WHERE table_schema = current_schema() AND table_name = 'grading_submissions') THEN
        CREATE INDEX IF NOT EXISTS idx_grading_user ON grading_submissions(user_id, created_at DESC);
        CREATE INDEX IF NOT EXISTS idx_grading_proto ON grading_submissions(protocol, created_at DESC);
        -- alembic 0006: pre-existing databases still carry the user_id FK; drop it
        -- here too so init_db() and `alembic upgrade head` converge either way.
        ALTER TABLE grading_submissions DROP CONSTRAINT IF EXISTS grading_submissions_user_id_fkey;
        -- alembic 0007: 作业提交落 assignment_id（无外键，脱钩）；聊天路径落 NULL。
        ALTER TABLE grading_submissions ADD COLUMN IF NOT EXISTS assignment_id TEXT;
        -- alembic 0014: 聊天评测作业关联，唯一键防同 job 重复记成绩。
        ALTER TABLE grading_submissions ADD COLUMN IF NOT EXISTS task_job_id TEXT;
        CREATE INDEX IF NOT EXISTS idx_grading_assignment
            ON grading_submissions(assignment_id, user_id);
        CREATE UNIQUE INDEX IF NOT EXISTS idx_grading_task_job_id
            ON grading_submissions(task_job_id) WHERE task_job_id IS NOT NULL;
    END IF;
END $$;

-- 作业管理（alembic 0007）：assignments 是教师/组长发布的作业，scope 服务端
-- 按角色写入（leader→'all'，admin→'class'）；created_by 只存学号文本，无外键
-- （脱钩原则，同 0006）。assignment_items 是协议项（协议 + 分值），得分按
-- 通过比例折算。两表都不依赖 users，无需 DO 守卫。
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

-- Admin audit log (alembic 0006): sensitive admin operations (currently batch
-- account deletion) are recorded for traceability — actor/action/target ids/
-- time only, never passwords or message content.
CREATE TABLE IF NOT EXISTS admin_audit_log (
    id TEXT PRIMARY KEY,
    actor TEXT NOT NULL,
    action TEXT NOT NULL,
    targets_json JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_admin_audit_log_created ON admin_audit_log(created_at DESC);
