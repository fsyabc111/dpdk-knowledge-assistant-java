继续开发 DPDK Knowledge Assistant（DPDK 项目知识问答助手）。

## 项目位置

`/Users/fsy/code/dpdk/ai_chat_assistant/`（后端在 `backend/`，前端在 `frontend/`，OS: macOS arm64，Python 3.9，venv 在 `backend/.venv`）

## 当前真实状态

- 已支持真实 LLM / Embedding 链路（DeepSeek + DashScope `text-embedding-v4` 1024 维）
- 已支持端到端问答测试、答案质量回归、并发压测
- 高并发优化已完成（连接池 + 线程卸载 + MMR token 预算）
- **默认数据库已是 `PostgreSQL` + pgvector**（`.env` 设 `DATABASE_BACKEND=postgres`）
- `SQLite` 仍是可用 backend，按需切换
- 存储层 provider 化完成：SQLite / PostgreSQL 双 backend，方言差异封装在 provider 层
- 检索候选读取抽到 `RetrievalStore`，按 `DATABASE_BACKEND` 自动选择 SQLite / PostgreSQL 实现
- 增量索引已完成：基于 `knowledge_source_files.content_hash` 跳过未变文件，并清理已从配置移除的文件

## 当前链路

```text
用户问题
  -> 本地规则改写 / LLM 改写
  -> Query Embedding
  -> 多路召回：改写 FTS + 原问题 FTS + 路径/主题召回 + 语义粗排
     （SQLite: FTS5 + Python cosine；PostgreSQL: websearch_to_tsquery + pgvector <=> HNSW）
  -> RRF 候选融合
  -> 语义 / 关键词 / 标识符 / 文件路径 / 主题 / 结构定义综合重排
  -> MMR 多样性选择 Top-K 片段
  -> LLM 回答
```

## 最近完成的核心改造

### -2. 增量索引

文件：
- `backend/app/services/ingestion_service.py`
- `backend/scripts/ingest_repo.py`

能力：
- ingest 入口先拉取 `{source_path -> content_hash}` 快照
- content_hash 未变 -> 跳过分片和 embedding（不调 API）
- 配置里已移除的文件 -> 删除其 chunks/fts/documents/source_files
- stats 含 `skipped_unchanged` / `skipped_empty` / `deleted`
- `ingest_repo.py` 打印跳过/删除计数
- 验证：已索引库上重跑 `Indexed 0 files / 0 chunks (skipped 210 unchanged)`，秒级完成，0 次 embedding API 调用

### -1. 后端存储 provider 化 + 并发优化

文件：
- `backend/app/db.py`
- `backend/app/storage/interfaces.py`
- `backend/app/storage/sqlite.py`
- `backend/app/storage/postgres.py`
- `backend/app/storage/postgres_schema.sql`
- `backend/app/storage/retrieval_store.py`
- `backend/scripts/migrate_sqlite_to_postgres.py`

能力：
- `DATABASE_BACKEND=postgres` 是默认运行时 backend（本地 PostgreSQL 17 + pgvector，数据库 `assistant`，OS 用户 socket 信任连接）
- `DATABASE_BACKEND=sqlite` 仍是可用 backend
- `db.py` 只保留兼容入口：`init_db()` / `get_conn()` / `get_database_provider()`
- `DatabaseProvider` 协议声明方言属性：`placeholder` / `supports_manual_fts` / `embedding_column` / `metadata_cast` / `embedding_cast`
- `RetrievalStore` 协议 + `get_retrieval_store()` 工厂按 backend 路由
- `PostgresRetrievalStore`：`websearch_to_tsquery` FTS、`ILIKE` 路径召回、pgvector `<=>` 余弦距离语义召回
- `postgres_schema.sql` 含 `search_vector` generated tsvector、`embedding VECTOR(1024)`、HNSW 向量索引、GIN 全文索引
- ingest 与 session 写入按方言生成 SQL（`?` vs `%s`、`::jsonb` / `::vector`），PostgreSQL 跳过手动 FTS 写入
- `PostgresProvider` 用 `psycopg_pool.ConnectionPool`（min 4 / max 80）
- `SessionService` 方法改 async + `asyncio.to_thread`；`RetrievalService._retrieve_sync` 卸载到线程
- `migrate_sqlite_to_postgres.py` 一次性数据搬迁，幂等（`--truncate`），过滤孤儿 citation
- ingest 记录 `knowledge_indexes` / `knowledge_source_files` 元数据

### 0. 分阶段耗时指标

文件：
- `backend/app/core/tracing.py`
- `backend/app/services/agent_service.py`
- `backend/app/services/retrieval_service.py`

能力：
- `/api/chat` 每次请求输出 `chat_trace` 单行 JSON 日志
- 记录 `rewrite / embedding / candidate fetch / rerank / MMR / retrieval / answer / citation save` 等阶段耗时
- 默认不向客户端返回 trace；可设置 `ENABLE_DEBUG_TRACE=true`，或请求 context 传 `"debug_trace": true`
- 测试脚本支持 `--debug-trace`

### 1. 检索层

文件：
- `backend/app/services/retrieval_service.py`

能力：
- `TOPIC_PATH_KEYWORDS` 文件/主题加权
- `SPECIFIC_TOPIC_KEYWORDS`（证书/sni/san/ja3/威胁情报/clickhouse/状态机/流表/…）具体词加权高于泛协议词
- `topic_boost` 扫描全部匹配关键词取最优（不再首中即返回）
- 多路候选召回（FTS + 路径 + 语义）
- Python 重排 + MMR（token 集合预算，单请求 MMR 1663ms -> 79ms）
- 本地规则改写优先
- `query rewrite` / `query embedding` 缓存

### 2. 回答层

文件：
- `backend/app/services/agent_service.py`

能力：
- 基于检索上下文生成最终回答
- 最终答案缓存
- 相同 key 并发合并，减少重复调用上游模型

### 3. 模型客户端

文件：
- `backend/app/core/llm_client.py`
- `backend/app/core/async_cache.py`

能力：
- 复用长生命周期 `httpx.AsyncClient`
- 连接池复用
- 应用关闭时释放客户端

### 4. 数据库层

文件：
- `backend/app/db.py`
- `backend/app/services/ingestion_service.py`

能力：
- SQLite WAL（可选 backend）
- FTS 虚表 `knowledge_chunks_fts`（仅 SQLite，PostgreSQL 用 generated column）
- 知识入库时同步维护 FTS 数据（SQLite 路径）
- PostgreSQL 路径：embedding 写 `VECTOR`，`search_vector` 自动生成，HNSW 索引随 `init_db` 创建

## 当前知识覆盖

已索引（`backend/data/knowledge_sources.yml`）：
- `docs/`
- `plugin/`
- `stats/`
- `session/`
- `capture/`
- `logger/`
- `api_server/`
- `Flask_network_new/core/`
- `Flask_network_new/services/`
- 根级核心文件
- `ai_chat_assistant/knowledge/`

共 210 文档 / 3556 chunks，全量带 1024 维 embedding。

未默认索引：
- `ai_chat_assistant/backend/`
- `ai_chat_assistant/frontend/`

## 最近 commit（HEAD = 369ab33）

```
369ab33 feat: incremental indexing and specific-keyword topic boost
87a856f perf: connection pool, thread offload, and MMR token caching
b184259 docs: reflect postgres runtime completion and latest test results
3943fdd feat: enable hnsw vector index in postgres schema
39dbfb6 feat: sqlite->postgres migration script and websearch tsquery fix
c14355a feat: dialect-aware ingestion and session writes
c9043a7 feat: provider-based retrieval store with pg vector backend
488d678 feat: add postgres provider skeleton and sqlite retrieval store
c8de1c9 feat: add chat tracing and storage provider base
```

## 当前测试脚本

注意：`.env` 默认 PG，不带环境变量跑测试就是 PostgreSQL backend；要测 SQLite 需显式 `DATABASE_BACKEND=sqlite`。

### 检索回归

```bash
cd ai_chat_assistant/backend
.venv/bin/python tests/test_retrieval.py                                              # 默认 PG
DATABASE_BACKEND=sqlite DATABASE_URL=sqlite:///./data/assistant.db .venv/bin/python tests/test_retrieval.py  # SQLite 回归
.venv/bin/python tests/test_storage_provider.py
.venv/bin/python tests/test_rag_interfaces.py
```

### SQLite -> PostgreSQL 迁移

```bash
cd ai_chat_assistant/backend
DATABASE_BACKEND=postgres DATABASE_URL=postgresql:///assistant \
    .venv/bin/python scripts/migrate_sqlite_to_postgres.py \
    --sqlite ./data/assistant.db --truncate
```

### 索引重建（增量）

```bash
cd ai_chat_assistant/backend
.venv/bin/python scripts/ingest_repo.py
# 已索引库重跑：Indexed 0 files / 0 chunks (skipped 210 unchanged)
```

### 端到端问答测试

```bash
cd ai_chat_assistant/backend
.venv/bin/python tests/test_chat_e2e.py
.venv/bin/python tests/test_chat_e2e.py --limit 1
.venv/bin/python tests/test_chat_e2e.py --limit 1 --debug-trace
.venv/bin/python tests/test_chat_e2e.py --question "engine_ops_t 是什么？"
```

### 答案质量回归

```bash
cd ai_chat_assistant/backend
.venv/bin/python tests/test_answer_quality.py
.venv/bin/python tests/test_answer_quality.py --case engine_ops --debug-trace
.venv/bin/python tests/test_answer_quality.py --case engine_ops --show-retrieval
.venv/bin/python tests/test_answer_quality.py --question "engine_ops_t 是什么？" --expect engine_ops_t --expect can_handle --expect-citation engine.h
```

### 并发压测

```bash
cd ai_chat_assistant/backend
.venv/bin/python tests/load_chat_concurrency.py --levels 30 --requests-per-level 30
.venv/bin/python tests/load_chat_concurrency.py --levels 50 --requests-per-level 50 --debug-trace
```

## 最近验证结果

### 检索回归

- SQLite backend：`42/42 passed`
- PostgreSQL backend：`42/42 passed`

### 端到端问答

- 真实链路可返回生成答案
- `/api/chat` 和 `/api/sources/{chunk_id}` 都正常
- SQLite 与 PostgreSQL backend 下均验证通过

### 答案质量（PostgreSQL backend，20 用例）

- 通过 `18/20`（`90%`）
- `subsys_cert` 已修（`SPECIFIC_TOPIC_KEYWORDS` 让 `cert_extractor.c` 进 top-4）
- 剩余失败：`proto_dns` / `proto_openvpn`（第 4 条 chunk 差异 + LLM 非确定性 temp=0.2，非检索召回问题）
- 无 fallback、无 error，全部走真实 LLM 路径

### 并发压测（PostgreSQL backend，50 并发 / 50 请求）

- 成功 `50/50`
- `fallback = 0` / `error = 0`
- 平均 `19.2s` / P50 `19.6s` / P95 `21.6s` / Max `23.0s`
- `avg_retrieval` `6.8s` / `avg_answer` `12.1s`
- 对比 SQLite baseline（13.5s）已接近

## 当前主要限制

1. query rewrite 还不感知多轮上下文
2. `TOPIC_PATH_KEYWORDS` 仍需人工维护
3. PG retrieval 6.8s vs SQLite 4s（`asyncio.to_thread` + 同步 psycopg，可走 `AsyncConnectionPool` 进一步压低）
4. `proto_dns` / `proto_openvpn` 答案质量受 LLM 非确定性影响
5. 高并发主要瓶颈仍是外部模型响应时间

## 下一步建议优先级

1. 多轮上下文参与 query rewrite（把最近 1-2 轮对话塞进 rewrite prompt）
2. 更真实压测（50 用户 × 2-3 轮追问，`load_chat_concurrency.py` 支持多轮会话）
3. `TOPIC_PATH_KEYWORDS` 自动化（从 `engine_*.c` 文件名 + `engine_get_ops` 注册推导）
4. async DB（`AsyncConnectionPool`，retrieval 再降 2-3s）
5. `proto_dns` / `proto_openvpn` 调 temp 或扩 citation 数 4->6
6. 视需要把 `ai_chat_assistant/backend/` 和 `frontend/` 纳入知识库
