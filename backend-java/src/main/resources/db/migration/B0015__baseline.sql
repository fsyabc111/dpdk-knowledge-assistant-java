-- B0015：完整 schema baseline（15 态，等于 Python 冻结副本当前 schema）。
-- 来源与验证：由 P0 合成 oracle 库（pg_dump，版本表已剥离）变换生成；
-- 迁移后以 expected-catalog-b15.json 做完整 catalog 差分（J1-01 三路径验收）。
-- 仅空库路径执行本文件；合成0013/等价0015 起点显式 baseline 后只走 V0014/V0015。
CREATE EXTENSION IF NOT EXISTS vector;

-- Name: admin_audit_log; Type: TABLE
--

CREATE TABLE public.admin_audit_log (
    id text NOT NULL,
    actor text NOT NULL,
    action text NOT NULL,
    targets_json jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);


--

-- Name: api_tokens; Type: TABLE
--

CREATE TABLE public.api_tokens (
    token_hash text NOT NULL,
    user_id text NOT NULL,
    issued_at timestamp with time zone NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone
);


--

-- Name: assignment_items; Type: TABLE
--

CREATE TABLE public.assignment_items (
    id text NOT NULL,
    assignment_id text NOT NULL,
    protocol text NOT NULL,
    points integer NOT NULL
);


--

-- Name: assignments; Type: TABLE
--

CREATE TABLE public.assignments (
    id text NOT NULL,
    title text NOT NULL,
    description text DEFAULT ''::text NOT NULL,
    created_by text NOT NULL,
    scope text NOT NULL,
    deadline timestamp with time zone NOT NULL,
    status text DEFAULT 'active'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);


--

-- Name: assistant_citations; Type: TABLE
--

CREATE TABLE public.assistant_citations (
    id text NOT NULL,
    message_id text NOT NULL,
    chunk_id text NOT NULL,
    citation_engine text DEFAULT 'legacy'::text NOT NULL,
    score double precision NOT NULL,
    rank integer NOT NULL
);


--

-- Name: assistant_messages; Type: TABLE
--

CREATE TABLE public.assistant_messages (
    id text NOT NULL,
    session_id text NOT NULL,
    role text NOT NULL,
    content text NOT NULL,
    metadata_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--

-- Name: assistant_sessions; Type: TABLE
--

CREATE TABLE public.assistant_sessions (
    id text NOT NULL,
    user_id text,
    title text,
    memory_summary text DEFAULT ''::text NOT NULL,
    memory_summary_through_message_id text,
    rag_release_id text,
    created_at timestamp with time zone NOT NULL,
    updated_at timestamp with time zone NOT NULL
);


--

-- Name: data_li_knowledge_v1; Type: TABLE
--

CREATE TABLE public.data_li_knowledge_v1 (
    id bigint NOT NULL,
    text character varying NOT NULL,
    metadata_ jsonb,
    node_id character varying,
    embedding public.vector(1024),
    text_search_tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple'::regconfig, (text)::text)) STORED
);


--

-- Name: data_li_knowledge_v1_id_seq; Type: SEQUENCE
--

CREATE SEQUENCE public.data_li_knowledge_v1_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--

-- Name: data_li_knowledge_v1_id_seq; Type: SEQUENCE OWNED BY
--

ALTER SEQUENCE public.data_li_knowledge_v1_id_seq OWNED BY public.data_li_knowledge_v1.id;


--

-- Name: grading_submissions; Type: TABLE
--

CREATE TABLE public.grading_submissions (
    id text NOT NULL,
    user_id text NOT NULL,
    session_id text,
    protocol text NOT NULL,
    code_hash text NOT NULL,
    score integer NOT NULL,
    passed integer NOT NULL,
    total integer NOT NULL,
    results_json jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    assignment_id text,
    task_job_id text
);


--

-- Name: knowledge_chunks; Type: TABLE
--

CREATE TABLE public.knowledge_chunks (
    id text NOT NULL,
    document_id text NOT NULL,
    source_path text NOT NULL,
    section text,
    content text NOT NULL,
    metadata_json jsonb DEFAULT '{}'::jsonb NOT NULL,
    embedding public.vector(1024),
    search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple'::regconfig, ((((((COALESCE(source_path, ''::text) || ' '::text) || COALESCE(section, ''::text)) || ' '::text) || COALESCE(content, ''::text)) || ' '::text) || COALESCE((metadata_json)::text, ''::text)))) STORED
);


--

-- Name: knowledge_documents; Type: TABLE
--

CREATE TABLE public.knowledge_documents (
    id text NOT NULL,
    source_path text NOT NULL,
    source_type text NOT NULL,
    title text,
    content_hash text NOT NULL,
    git_commit text,
    indexed_at timestamp with time zone NOT NULL
);


--

-- Name: knowledge_indexes; Type: TABLE
--

CREATE TABLE public.knowledge_indexes (
    id text NOT NULL,
    provider text NOT NULL,
    embedding_model text NOT NULL,
    chunker_version text NOT NULL,
    source_config_hash text NOT NULL,
    status text NOT NULL,
    created_at timestamp with time zone NOT NULL,
    completed_at timestamp with time zone
);


--

-- Name: knowledge_source_files; Type: TABLE
--

CREATE TABLE public.knowledge_source_files (
    source_path text NOT NULL,
    content_hash text NOT NULL,
    document_id text NOT NULL,
    indexed_at timestamp with time zone NOT NULL
);


--

-- Name: password_reset_requests; Type: TABLE
--

CREATE TABLE public.password_reset_requests (
    id text NOT NULL,
    user_id text NOT NULL,
    status text DEFAULT 'pending'::text NOT NULL,
    reset_token_hash text,
    reset_expires_at timestamp with time zone,
    created_at timestamp with time zone NOT NULL,
    decided_at timestamp with time zone,
    decided_by text
);


--

-- Name: rag_index_state; Type: TABLE
--

CREATE TABLE public.rag_index_state (
    name text NOT NULL,
    release_id text NOT NULL
);


--

-- Name: rag_issue_events; Type: TABLE
--

CREATE TABLE public.rag_issue_events (
    id text NOT NULL,
    issue_id text NOT NULL,
    actor_id text,
    action text NOT NULL,
    detail text NOT NULL,
    created_at text NOT NULL
);


--

-- Name: rag_issues; Type: TABLE
--

CREATE TABLE public.rag_issues (
    id text NOT NULL,
    request_id text NOT NULL,
    status text DEFAULT 'open'::text NOT NULL,
    owner_id text,
    note text DEFAULT ''::text NOT NULL,
    feedback text DEFAULT ''::text NOT NULL,
    merged_into text,
    knowledge_id text,
    verified_release_id text,
    verified_at text,
    updated_at text NOT NULL
);


--

-- Name: rag_knowledge; Type: TABLE
--

CREATE TABLE public.rag_knowledge (
    id text NOT NULL,
    created_by text,
    title text NOT NULL,
    body text NOT NULL,
    revision integer DEFAULT 1 NOT NULL,
    status text DEFAULT 'published'::text NOT NULL,
    updated_at text NOT NULL
);


--

-- Name: rag_knowledge_revisions; Type: TABLE
--

CREATE TABLE public.rag_knowledge_revisions (
    id text NOT NULL,
    knowledge_id text NOT NULL,
    revision integer NOT NULL,
    actor_id text,
    title text NOT NULL,
    body text NOT NULL,
    status text NOT NULL,
    created_at text NOT NULL
);


--

-- Name: rag_release_nodes; Type: TABLE
--

CREATE TABLE public.rag_release_nodes (
    release_id text NOT NULL,
    node_id text NOT NULL
);


--

-- Name: rag_releases; Type: TABLE
--

CREATE TABLE public.rag_releases (
    id text NOT NULL,
    configuration text NOT NULL,
    created_at text NOT NULL,
    node_count integer NOT NULL
);


--

-- Name: rag_requests; Type: TABLE
--

CREATE TABLE public.rag_requests (
    id text NOT NULL,
    request_id text,
    input_hash text,
    initial_request integer DEFAULT 0 NOT NULL,
    purpose text DEFAULT 'chat'::text NOT NULL,
    user_id text,
    session_id text,
    message_id text,
    question text NOT NULL,
    context_text text DEFAULT ''::text NOT NULL,
    status text DEFAULT 'pending'::text NOT NULL,
    reason text DEFAULT ''::text NOT NULL,
    release_id text DEFAULT ''::text NOT NULL,
    evidence_json text DEFAULT '[]'::text NOT NULL,
    model_version text DEFAULT ''::text NOT NULL,
    prompt_version text DEFAULT 'grounded-v1'::text NOT NULL,
    elapsed_ms integer DEFAULT 0 NOT NULL,
    created_at text NOT NULL,
    updated_at text NOT NULL
);


--

-- Name: rag_task_inputs; Type: TABLE
--

CREATE TABLE public.rag_task_inputs (
    task_id text NOT NULL,
    question text NOT NULL,
    attachment_name text,
    attachment_suffix text,
    source_text text,
    source_origin text,
    created_at text NOT NULL,
    expires_at text NOT NULL
);


--

-- Name: rag_task_jobs; Type: TABLE
--

CREATE TABLE public.rag_task_jobs (
    id text NOT NULL,
    task_id text NOT NULL,
    job_id text NOT NULL,
    tool text NOT NULL,
    protocol text,
    code_hash text,
    status text NOT NULL,
    result_json text,
    submission_done integer DEFAULT 0 NOT NULL,
    created_at text NOT NULL,
    updated_at text NOT NULL
);


--

-- Name: teaching_faqs; Type: TABLE
--

CREATE TABLE public.teaching_faqs (
    id text NOT NULL,
    question text NOT NULL,
    answer text NOT NULL,
    tags text,
    visibility text DEFAULT 'student'::text NOT NULL,
    created_at timestamp with time zone NOT NULL
);


--

-- Name: upstream_usage_records; Type: TABLE
--

CREATE TABLE public.upstream_usage_records (
    id text NOT NULL,
    request_id text,
    subject text,
    session_id text,
    kind text NOT NULL,
    stage text NOT NULL,
    model text,
    prompt_tokens integer,
    completion_tokens integer,
    total_tokens integer,
    usage_source text NOT NULL,
    call_count integer DEFAULT 1 NOT NULL,
    duration_ms integer,
    cost_micros integer,
    currency text,
    price_version text,
    created_at text NOT NULL
);


--

-- Name: usage_counters; Type: TABLE
--

CREATE TABLE public.usage_counters (
    subject text NOT NULL,
    day text NOT NULL,
    kind text DEFAULT 'chat'::text NOT NULL,
    chat_count integer DEFAULT 0 NOT NULL
);


--

-- Name: users; Type: TABLE
--

CREATE TABLE public.users (
    id text NOT NULL,
    password_hash text NOT NULL,
    roles text NOT NULL,
    daily_quota integer,
    enabled boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone NOT NULL,
    must_change_password boolean DEFAULT false NOT NULL,
    created_by text
);


--

-- Name: data_li_knowledge_v1 id; Type: DEFAULT
--

ALTER TABLE ONLY public.data_li_knowledge_v1 ALTER COLUMN id SET DEFAULT nextval('public.data_li_knowledge_v1_id_seq'::regclass);


--

-- Name: admin_audit_log admin_audit_log_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.admin_audit_log
    ADD CONSTRAINT admin_audit_log_pkey PRIMARY KEY (id);


--

-- Name: api_tokens api_tokens_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.api_tokens
    ADD CONSTRAINT api_tokens_pkey PRIMARY KEY (token_hash);


--

-- Name: assignment_items assignment_items_assignment_id_protocol_key; Type: CONSTRAINT
--

ALTER TABLE ONLY public.assignment_items
    ADD CONSTRAINT assignment_items_assignment_id_protocol_key UNIQUE (assignment_id, protocol);


--

-- Name: assignment_items assignment_items_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.assignment_items
    ADD CONSTRAINT assignment_items_pkey PRIMARY KEY (id);


--

-- Name: assignments assignments_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.assignments
    ADD CONSTRAINT assignments_pkey PRIMARY KEY (id);


--

-- Name: assistant_citations assistant_citations_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.assistant_citations
    ADD CONSTRAINT assistant_citations_pkey PRIMARY KEY (id);


--

-- Name: assistant_messages assistant_messages_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.assistant_messages
    ADD CONSTRAINT assistant_messages_pkey PRIMARY KEY (id);


--

-- Name: assistant_sessions assistant_sessions_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.assistant_sessions
    ADD CONSTRAINT assistant_sessions_pkey PRIMARY KEY (id);


--

-- Name: data_li_knowledge_v1 data_li_knowledge_v1_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.data_li_knowledge_v1
    ADD CONSTRAINT data_li_knowledge_v1_pkey PRIMARY KEY (id);


--

-- Name: grading_submissions grading_submissions_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.grading_submissions
    ADD CONSTRAINT grading_submissions_pkey PRIMARY KEY (id);


--

-- Name: knowledge_chunks knowledge_chunks_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.knowledge_chunks
    ADD CONSTRAINT knowledge_chunks_pkey PRIMARY KEY (id);


--

-- Name: knowledge_documents knowledge_documents_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.knowledge_documents
    ADD CONSTRAINT knowledge_documents_pkey PRIMARY KEY (id);


--

-- Name: knowledge_documents knowledge_documents_source_path_key; Type: CONSTRAINT
--

ALTER TABLE ONLY public.knowledge_documents
    ADD CONSTRAINT knowledge_documents_source_path_key UNIQUE (source_path);


--

-- Name: knowledge_indexes knowledge_indexes_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.knowledge_indexes
    ADD CONSTRAINT knowledge_indexes_pkey PRIMARY KEY (id);


--

-- Name: knowledge_source_files knowledge_source_files_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.knowledge_source_files
    ADD CONSTRAINT knowledge_source_files_pkey PRIMARY KEY (source_path);


--

-- Name: password_reset_requests password_reset_requests_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.password_reset_requests
    ADD CONSTRAINT password_reset_requests_pkey PRIMARY KEY (id);


--

-- Name: rag_index_state rag_index_state_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.rag_index_state
    ADD CONSTRAINT rag_index_state_pkey PRIMARY KEY (name);


--

-- Name: rag_issue_events rag_issue_events_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.rag_issue_events
    ADD CONSTRAINT rag_issue_events_pkey PRIMARY KEY (id);


--

-- Name: rag_issues rag_issues_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.rag_issues
    ADD CONSTRAINT rag_issues_pkey PRIMARY KEY (id);


--

-- Name: rag_issues rag_issues_request_id_key; Type: CONSTRAINT
--

ALTER TABLE ONLY public.rag_issues
    ADD CONSTRAINT rag_issues_request_id_key UNIQUE (request_id);


--

-- Name: rag_knowledge rag_knowledge_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.rag_knowledge
    ADD CONSTRAINT rag_knowledge_pkey PRIMARY KEY (id);


--

-- Name: rag_knowledge_revisions rag_knowledge_revisions_knowledge_id_revision_key; Type: CONSTRAINT
--

ALTER TABLE ONLY public.rag_knowledge_revisions
    ADD CONSTRAINT rag_knowledge_revisions_knowledge_id_revision_key UNIQUE (knowledge_id, revision);


--

-- Name: rag_knowledge_revisions rag_knowledge_revisions_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.rag_knowledge_revisions
    ADD CONSTRAINT rag_knowledge_revisions_pkey PRIMARY KEY (id);


--

-- Name: rag_release_nodes rag_release_nodes_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.rag_release_nodes
    ADD CONSTRAINT rag_release_nodes_pkey PRIMARY KEY (release_id, node_id);


--

-- Name: rag_releases rag_releases_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.rag_releases
    ADD CONSTRAINT rag_releases_pkey PRIMARY KEY (id);


--

-- Name: rag_requests rag_requests_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.rag_requests
    ADD CONSTRAINT rag_requests_pkey PRIMARY KEY (id);


--

-- Name: rag_task_inputs rag_task_inputs_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.rag_task_inputs
    ADD CONSTRAINT rag_task_inputs_pkey PRIMARY KEY (task_id);


--

-- Name: rag_task_jobs rag_task_jobs_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.rag_task_jobs
    ADD CONSTRAINT rag_task_jobs_pkey PRIMARY KEY (id);


--

-- Name: rag_task_jobs rag_task_jobs_task_id_job_id_key; Type: CONSTRAINT
--

ALTER TABLE ONLY public.rag_task_jobs
    ADD CONSTRAINT rag_task_jobs_task_id_job_id_key UNIQUE (task_id, job_id);


--

-- Name: teaching_faqs teaching_faqs_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.teaching_faqs
    ADD CONSTRAINT teaching_faqs_pkey PRIMARY KEY (id);


--

-- Name: upstream_usage_records upstream_usage_records_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.upstream_usage_records
    ADD CONSTRAINT upstream_usage_records_pkey PRIMARY KEY (id);


--

-- Name: usage_counters usage_counters_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.usage_counters
    ADD CONSTRAINT usage_counters_pkey PRIMARY KEY (subject, day, kind);


--

-- Name: users users_pkey; Type: CONSTRAINT
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);


--

-- Name: api_tokens_user_id_idx; Type: INDEX
--

CREATE INDEX api_tokens_user_id_idx ON public.api_tokens USING btree (user_id);


--

-- Name: idx_admin_audit_log_created; Type: INDEX
--

CREATE INDEX idx_admin_audit_log_created ON public.admin_audit_log USING btree (created_at DESC);


--

-- Name: idx_assignments_created_by; Type: INDEX
--

CREATE INDEX idx_assignments_created_by ON public.assignments USING btree (created_by);


--

-- Name: idx_assignments_status; Type: INDEX
--

CREATE INDEX idx_assignments_status ON public.assignments USING btree (status);


--

-- Name: idx_assistant_citations_engine; Type: INDEX
--

CREATE INDEX idx_assistant_citations_engine ON public.assistant_citations USING btree (citation_engine);


--

-- Name: idx_assistant_citations_message_id; Type: INDEX
--

CREATE INDEX idx_assistant_citations_message_id ON public.assistant_citations USING btree (message_id);


--

-- Name: idx_assistant_messages_session_id; Type: INDEX
--

CREATE INDEX idx_assistant_messages_session_id ON public.assistant_messages USING btree (session_id);


--

-- Name: idx_grading_assignment; Type: INDEX
--

CREATE INDEX idx_grading_assignment ON public.grading_submissions USING btree (assignment_id, user_id);


--

-- Name: idx_grading_proto; Type: INDEX
--

CREATE INDEX idx_grading_proto ON public.grading_submissions USING btree (protocol, created_at DESC);


--

-- Name: idx_grading_task_job_id; Type: INDEX
--

CREATE UNIQUE INDEX idx_grading_task_job_id ON public.grading_submissions USING btree (task_job_id) WHERE (task_job_id IS NOT NULL);


--

-- Name: idx_grading_user; Type: INDEX
--

CREATE INDEX idx_grading_user ON public.grading_submissions USING btree (user_id, created_at DESC);


--

-- Name: idx_knowledge_chunks_document_id; Type: INDEX
--

CREATE INDEX idx_knowledge_chunks_document_id ON public.knowledge_chunks USING btree (document_id);


--

-- Name: idx_knowledge_chunks_embedding; Type: INDEX
--

CREATE INDEX idx_knowledge_chunks_embedding ON public.knowledge_chunks USING hnsw (embedding public.vector_cosine_ops);


--

-- Name: idx_knowledge_chunks_search_vector; Type: INDEX
--

CREATE INDEX idx_knowledge_chunks_search_vector ON public.knowledge_chunks USING gin (search_vector);


--

-- Name: idx_knowledge_chunks_section; Type: INDEX
--

CREATE INDEX idx_knowledge_chunks_section ON public.knowledge_chunks USING btree (section);


--

-- Name: idx_knowledge_chunks_source_path; Type: INDEX
--

CREATE INDEX idx_knowledge_chunks_source_path ON public.knowledge_chunks USING btree (source_path);


--

-- Name: idx_knowledge_documents_source_path; Type: INDEX
--

CREATE INDEX idx_knowledge_documents_source_path ON public.knowledge_documents USING btree (source_path);


--

-- Name: idx_knowledge_indexes_status; Type: INDEX
--

CREATE INDEX idx_knowledge_indexes_status ON public.knowledge_indexes USING btree (status);


--

-- Name: idx_knowledge_source_files_document_id; Type: INDEX
--

CREATE INDEX idx_knowledge_source_files_document_id ON public.knowledge_source_files USING btree (document_id);


--

-- Name: idx_rag_release_node; Type: INDEX
--

CREATE INDEX idx_rag_release_node ON public.rag_release_nodes USING btree (node_id);


--

-- Name: idx_rag_request_initial_key; Type: INDEX
--

CREATE UNIQUE INDEX idx_rag_request_initial_key ON public.rag_requests USING btree (user_id, request_id) WHERE (initial_request = 1);


--

-- Name: idx_rag_request_session_key; Type: INDEX
--

CREATE UNIQUE INDEX idx_rag_request_session_key ON public.rag_requests USING btree (session_id, request_id);


--

-- Name: idx_rag_request_status; Type: INDEX
--

CREATE INDEX idx_rag_request_status ON public.rag_requests USING btree (status, updated_at);


--

-- Name: idx_rag_request_user; Type: INDEX
--

CREATE INDEX idx_rag_request_user ON public.rag_requests USING btree (user_id, created_at);


--

-- Name: idx_rag_task_inputs_expiry; Type: INDEX
--

CREATE INDEX idx_rag_task_inputs_expiry ON public.rag_task_inputs USING btree (expires_at);


--

-- Name: idx_rag_task_jobs_task; Type: INDEX
--

CREATE INDEX idx_rag_task_jobs_task ON public.rag_task_jobs USING btree (task_id);


--

-- Name: idx_usage_created; Type: INDEX
--

CREATE INDEX idx_usage_created ON public.upstream_usage_records USING btree (created_at);


--

-- Name: idx_usage_stage_created; Type: INDEX
--

CREATE INDEX idx_usage_stage_created ON public.upstream_usage_records USING btree (stage, created_at);


--

-- Name: idx_usage_subject_created; Type: INDEX
--

CREATE INDEX idx_usage_subject_created ON public.upstream_usage_records USING btree (subject, created_at);


--

-- Name: li_knowledge_v1_idx; Type: INDEX
--

CREATE INDEX li_knowledge_v1_idx ON public.data_li_knowledge_v1 USING gin (text_search_tsv);


--

-- Name: password_reset_requests_status_idx; Type: INDEX
--

CREATE INDEX password_reset_requests_status_idx ON public.password_reset_requests USING btree (status);


--

-- Name: users_created_by_idx; Type: INDEX
--

CREATE INDEX users_created_by_idx ON public.users USING btree (created_by);


--

-- Name: api_tokens api_tokens_user_id_fkey; Type: FK CONSTRAINT
--

ALTER TABLE ONLY public.api_tokens
    ADD CONSTRAINT api_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id);


--

-- Name: assignment_items assignment_items_assignment_id_fkey; Type: FK CONSTRAINT
--

ALTER TABLE ONLY public.assignment_items
    ADD CONSTRAINT assignment_items_assignment_id_fkey FOREIGN KEY (assignment_id) REFERENCES public.assignments(id) ON DELETE CASCADE;


--

-- Name: assistant_citations assistant_citations_message_id_fkey; Type: FK CONSTRAINT
--

ALTER TABLE ONLY public.assistant_citations
    ADD CONSTRAINT assistant_citations_message_id_fkey FOREIGN KEY (message_id) REFERENCES public.assistant_messages(id) ON DELETE CASCADE;


--

-- Name: assistant_messages assistant_messages_session_id_fkey; Type: FK CONSTRAINT
--

ALTER TABLE ONLY public.assistant_messages
    ADD CONSTRAINT assistant_messages_session_id_fkey FOREIGN KEY (session_id) REFERENCES public.assistant_sessions(id) ON DELETE CASCADE;


--

-- Name: grading_submissions grading_submissions_session_id_fkey; Type: FK CONSTRAINT
--

ALTER TABLE ONLY public.grading_submissions
    ADD CONSTRAINT grading_submissions_session_id_fkey FOREIGN KEY (session_id) REFERENCES public.assistant_sessions(id);


--

-- Name: knowledge_chunks knowledge_chunks_document_id_fkey; Type: FK CONSTRAINT
--

ALTER TABLE ONLY public.knowledge_chunks
    ADD CONSTRAINT knowledge_chunks_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.knowledge_documents(id) ON DELETE CASCADE;


--

-- Name: knowledge_source_files knowledge_source_files_document_id_fkey; Type: FK CONSTRAINT
--

ALTER TABLE ONLY public.knowledge_source_files
    ADD CONSTRAINT knowledge_source_files_document_id_fkey FOREIGN KEY (document_id) REFERENCES public.knowledge_documents(id) ON DELETE CASCADE;


--

-- Name: users users_created_by_fkey; Type: FK CONSTRAINT
--

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_created_by_fkey FOREIGN KEY (created_by) REFERENCES public.users(id);


--
-- PostgreSQL database dump complete
--


-- 种子：活动发布指针（rag_schema.sql 原样）。
INSERT INTO rag_index_state(name, release_id) VALUES ('active', '') ON CONFLICT (name) DO NOTHING;
