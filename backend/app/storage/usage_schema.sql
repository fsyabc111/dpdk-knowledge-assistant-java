-- 上游用量账本（P1 全链路用量与成本统计）。
-- 与 rag_schema.sql 同约定：只用 TEXT/INTEGER/JSON 之外的通用类型，两种数据库
-- 逐字执行同一份 DDL，业务 SQL 不做方言分支。
--
-- 与既有两张表的分工（不要合并、不要互相改语义）：
--   usage_counters  配额计数（rate_limit，kind=chat/grading，只数次数，无 token）
--   rag_requests    产品请求状态机（status/evidence），不是账本
--   upstream_usage_records  本表：各阶段上游花费
--
-- token 三列留 NULL 表示**未知**（上游没给 usage），绝不用 0 顶替：0 在账本里
-- 代表「确实没花」。usage_source 说明这份数字的来源：
--   upstream  上游返回了 usage
--   unknown   调了，但上游没给（含重试后仍失败）
--   cache_hit 命中缓存，零上游调用
--   replay    幂等重放，零模型调用
--   local     本地规则/启发式直接出结果，零上游调用
--   operation 非 token 操作（harness/MCP），token 列不适用
--
-- 本表**不含**提问正文、源码、prompt 或任何凭据列：账本是计量数据，
-- 「凭据、源码和完整 prompt 不进入指标标签或账单」是硬约束。
CREATE TABLE IF NOT EXISTS upstream_usage_records (
    id                TEXT PRIMARY KEY,   -- use_<12hex>
    request_id        TEXT,               -- trace_id / rag request_id；ingest 为 NULL
    subject           TEXT,               -- 主体（脱钩，无外键，同 grading_submissions 惯例）
    session_id        TEXT,
    kind              TEXT NOT NULL,      -- llm | embedding | harness | mcp
    stage             TEXT NOT NULL,      -- rewrite|followup|query_embed|planner|finalize|judge|...
    model             TEXT,
    prompt_tokens     INTEGER,
    completion_tokens INTEGER,
    total_tokens      INTEGER,
    usage_source      TEXT NOT NULL,
    call_count        INTEGER NOT NULL DEFAULT 1,
    duration_ms       INTEGER,
    cost_micros       INTEGER,            -- 整数微单位；NULL = 未定价
    currency          TEXT,
    price_version     TEXT,               -- 写入时的价格版本，调价不回溯改历史
    created_at        TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_usage_created ON upstream_usage_records(created_at);
CREATE INDEX IF NOT EXISTS idx_usage_subject_created ON upstream_usage_records(subject, created_at);
CREATE INDEX IF NOT EXISTS idx_usage_stage_created ON upstream_usage_records(stage, created_at);
