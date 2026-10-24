# DPDK Knowledge Assistant

面向 DPDK 流量检测平台的项目知识问答助手。它会索引 `DPDK` 主项目中的文档、代码和补充知识库，基于检索结果回答项目结构、协议检测、插件接口和实现细节问题。

当前处于个人开发验证阶段，目标为教学助教闭环。功能已有实现不代表整体验收通过。
文档入口：[导航](docs/README.md) · [当前架构](docs/architecture_current.md) · [当前决策](docs/decisions.md) · [统一路线](docs/roadmap.md)。
历史规划已归档，不作为自动续跑指令。

## Current Status

- 支持项目知识问答、引用来源、下一步建议
- 支持学习用插件模板生成
- 已接入真实 LLM / Embedding 接口
- 已加入端到端问答测试和并发压测脚本
- 默认数据库为 `PostgreSQL` + `pgvector`；SQLite 仅保留为显式本地回退和历史迁移源
- 已支持助教 agent 能力（M1/M2）：学生可上传/粘贴插件代码，agent 编排调用 harness 容器评测并给出引导式反馈
- 不支持线上 Collector 控制

## Knowledge Coverage

DPDK 知识源根目录由 `DPDK_REPO_PATH` 指定；未配置时默认使用本助手项目目录的父目录。助手自身路径由 `ASSISTANT_PROJECT_PATH` 自动发现，因此不依赖外层目录名的大小写。当前会按 [backend/data/knowledge_sources.yml](backend/data/knowledge_sources.yml) 索引这些内容：

- `docs/`
- `plugin/`
- `stats/`
- `session/`
- `capture/`
- `logger/`
- `api_server/`
- `Flask_network_new/core/`
- `Flask_network_new/services/`
- 根目录核心文件，如 `main.c`、`capture_dpdk.c`、`http_server.c`、`log.c`
- 助手项目内的 `knowledge/`

说明：
- `knowledge/` 已入库
- 助手项目的 `backend/` 和 `frontend/` 默认未入库

### Linux 路径与大小写

产品展示名称可以写作 `AI_CHAT_ASSISTANT`，但磁盘目录规范为小写 `ai_chat_assistant`。运行时代码不会再拼接该目录名：它通过 `ASSISTANT_PROJECT_PATH` 自动定位助手根目录。Linux 部署时，如项目不在默认嵌套位置，请在 `backend/.env` 中显式配置：

```bash
DPDK_REPO_PATH=/opt/dpdk
ASSISTANT_PROJECT_PATH=/opt/dpdk/ai_chat_assistant
```

知识源路径不存在时，索引任务会直接失败并报告缺失路径，避免 Linux 上因大小写错误而静默漏索引。

## Architecture

当前问答主链路：

```text
用户问题
  -> 本地规则改写 / LLM 改写
  -> Query Embedding
  -> 多路召回：改写 FTS + 原问题 FTS + 路径/主题召回 + 全库 embedding 粗排
  -> RRF 候选融合
  -> 语义 / 关键词 / 标识符 / 文件路径 / 主题 / 结构定义综合重排
  -> MMR 多样性选择 Top-K 片段
  -> LLM 生成最终回答
```

当前链路已经抽象成可替换组件，接口集中在 [backend/app/rag/interfaces.py](backend/app/rag/interfaces.py)，默认 provider 为 `RAG_PROVIDER=llama_index`：

- `ChatModel` / `EmbeddingModel`：默认使用 LlamaIndex 的 OpenAI-compatible adapter；如需回退旧实现，可设置 `RAG_PROVIDER=direct`。
- `QueryRewriter`：默认是本地规则 + LLM 改写，保留 DPDK 代码库特化逻辑。
- `KnowledgeRetriever`：默认是 `hybrid_fusion_v2`，使用多路召回、RRF 融合、DPDK 代码信号重排和 MMR 去冗余。
- `Chunker` / `KnowledgeSource`：默认是 YAML 配置知识源 + 正则代码切片，后续可替换为 tree-sitter 或远程文档源。
- `AnswerGenerator`：默认基于检索片段生成带 fallback 的回答。

说明：当前已将模型与 embedding provider 切到 LlamaIndex；检索策略也已升级为 `hybrid_fusion_v2`。它没有使用 LlamaIndex 默认向量检索替换全部逻辑，而是保留并增强 DPDK 特化代码信号，避免代码库问答效果下降。

**助教 agent 层（M1/M2）**：`/api/chat*` 默认由 LangGraph 编排（`backend/app/agent/`）：planner（LLM + bind_tools）与 tools 节点循环（上限 `AGENT_MAX_TOOL_ROUNDS` 轮，图按请求重建、无 checkpointer），五件工具——`kb_search`（封装上述检索）、`list_protocols` / `list_cases` / `grade_plugin`（经 MCP streamable HTTP 调 harness 容器）、`plugin_template`（本地骨架生成）。学生可在聊天中粘贴或上传（multipart，`.c`/`.so`）插件代码，`grade_plugin` 将其交给独立的 `teaching_harness` 容器编译运行并评分，agent 再按分级反馈提示词给出引导式反馈（不直接给修复代码）。评测有独立配额（`usage_counters` 表 `kind='grading'`，默认 `GRADING_DAILY_QUOTA=20`/天，`operator`/`admin` 豁免）与单次硬超时 `GRADING_TIMEOUT_SEC`（120s）；成绩落库只存代码 sha256 与结果 JSON。harness 容器隔离要点：无外网内网、非 root、read_only + tmpfs、cgroup/rlimit 限额（运维细节见 `docs/runbook.md` 第 6 章）。`AGENT_ENABLED=false` 时整体回退到上面的单轮 RAG 管线，SSE 事件契约同形。当前决策见 [docs/decisions.md](docs/decisions.md)，原设计仅作[历史追溯](docs/archive/2026-09-18/agent_upgrade_plan.md)。

后端存储层已经完成 provider 化迁移，`SQLite` 与 `PostgreSQL` 都是可用运行时：

- `DATABASE_BACKEND=postgres` 是默认运行时实现，目标 schema 位于 `backend/app/storage/postgres_schema.sql`（含 `search_vector` generated tsvector、`embedding VECTOR(1024)`、HNSW 向量索引、GIN 全文索引）。
- `DATABASE_BACKEND=sqlite` 是显式本地回退实现，schema 与连接配置位于 `backend/app/storage/sqlite.py`。
- `DatabaseProvider` 协议在 [backend/app/storage/interfaces.py](backend/app/storage/interfaces.py) 上声明方言属性（`placeholder` / `supports_manual_fts` / `embedding_column` / `metadata_cast` / `embedding_cast`），ingest 与 session 写入按方言生成 SQL，无需在业务层判断 backend。
- 检索候选读取抽到 [backend/app/storage/retrieval_store.py](backend/app/storage/retrieval_store.py)：`SQLiteRetrievalStore`（FTS5 + Python cosine）与 `PostgresRetrievalStore`（`websearch_to_tsquery` + pgvector `<=>`）按 `DATABASE_BACKEND` 自动选择，`RetrievalService` 的 RRF / rerank / MMR 完全 dialect 无关。
- ingest 会记录 `knowledge_indexes` 和 `knowledge_source_files` 元数据，为后续增量索引做准备。
- 一次性数据搬迁脚本 [backend/scripts/migrate_sqlite_to_postgres.py](backend/scripts/migrate_sqlite_to_postgres.py)：流式迁移，`embedding_json`→`VECTOR`、`metadata_json`→`JSONB`，跳过 `knowledge_chunks_fts`（PG 用 generated column），`--truncate` 幂等。

第一轮性能优化已完成：

- 复用 `httpx.AsyncClient` 连接池
- `query rewrite` / `query embedding` / 最终答案缓存
- 相同 key 的并发请求自动合并，避免同一问题同时重复打上游模型
- 已知协议/主题问题优先走本地规则改写，尽量减少一次模型 hop

## 认证与限流

`AUTH_MODE` 控制问答端点的访问方式：

- `local`（默认）：单机开发模式，不要求任何凭据，所有请求视为同一个管理员主体。只应在本机回环监听时使用。
- `token`：`/api/chat*`、`/api/sources/*`、`/api/plugin-template` 均要求 `Authorization: Bearer <token>`；缺失或不匹配返回 401（带 `WWW-Authenticate: Bearer`）。`/api/health` 保持匿名，`/api/internal/session-storage` 仍走 `MAINTENANCE_API_KEY`。
- `account`：账号体系模式（推荐生产使用）。用户在登录页以学号 + 密码登录，后端签发临时 token（落库只存哈希，默认 7 天过期，`AUTH_TOKEN_TTL_DAYS` 可配）；`API_TOKENS_JSON` 中的静态 token 仍作为应急兜底凭证并行有效。

account 模式的账号规则：

- 账号即学生学号，格式 `U2026xxxxxxx`（`U` + 4 位年份 + 7 位序号），**初始密码与学号相同**。新建账号和被管理员重置/设密的账号都带 `must_change_password` 标记：登录响应返回该标记、管理面板显示"待改密"，仅作信息提示——内网部署**不强制改密**，用户可随时在侧边栏"修改密码"凭旧密码自助更换（无需管理员介入）。
- 新密码规则：长度至少 `PASSWORD_MIN_LENGTH`（默认 8）、**不得等于学号**、不得等于旧密码。用户自助改密（`/api/auth/change-password`）、管理员设密、忘记密码令牌改密三处统一 enforce。
- 不提供自助注册。账号体系是**组长（leader）→ 老师（admin）→ 学生（viewer）三级树状管理**（M8，`users.created_by` 记录归属）：第一个组长通过 CLI 引导创建（根节点，`created_by` 为空）；组长在管理面板可创建老师与学生并管理全部账号；老师只能创建学生，且只能管理自己创建的学生（严格树隔离，跨老师一律 404）；`leader` 角色只能经 CLI 授予，API 不开放（防提权）；任何人不能停用自己或改自己的角色。`API_TOKENS_JSON` 中的静态应急 token 不受树约束。

  ```bash
  cd ai_chat_assistant/backend
  .venv/bin/python scripts/create_user.py --id U20260000001 --roles leader    # 引导第一个组长
  .venv/bin/python scripts/create_user.py --file students.csv --roles viewer  # 批量导入（每行一个学号）
  ```

- 管理员有两种改密方式，语义不同：
  - **重置密码**（`POST /api/admin/users/{id}/reset-password`）：把密码恢复为学号本身，用于用户彻底遗忘且走线下核身的场景；
  - **设置密码**（`POST /api/admin/users/{id}/set-password`）：管理员直接指定一个新密码。两者都会吊销该用户全部已签发 token，并置 `must_change_password` 标记（仅提示，不强制）。
- **忘记密码**走"申请-审批-令牌改密"流程：学生在登录页匿名提交申请（`POST /api/auth/password-reset-requests`，无论学号是否存在统一返回 202，不泄露账号存在性）→ 管理员在用户管理面板审批（`GET/POST /api/admin/password-reset-requests*`），通过时后端签发一次性重置令牌（30 分钟有效，库里只存 SHA-256 哈希，明文仅向管理员展示一次）→ 管理员线下把令牌交给学生 → 学生凭令牌自助设置新密码（`POST /api/auth/password-reset`，成功后旧 token 全部失效、令牌不可复用）。
- 登录防爆破两道闸：每 IP 每分钟限 10 次；任意学号 15 分钟内连续失败 5 次后锁定 15 分钟（对存在/不存在的学号一视同仁，锁定期内统一 429 + `Retry-After`，登录成功清零计数）。两者都是进程内存实现，重启清零。登出、改密、停用均立即吊销对应 token。
- token 默认 7 天过期（`AUTH_TOKEN_TTL_DAYS` 可配）；前端在 token 到期前 24 小时显示提示条，过期后自动跳回登录页。
- 存量 local 时期的 `user_id='anonymous'` 会话可用 `scripts/assign_anonymous_sessions.py --user-id <学号> --apply` 划归指定账号（默认 dry-run）。

token 模式下的令牌在 `API_TOKENS_JSON` 中配置，格式：

```json
{
  "<token（至少 32 字符）>": {
    "subject": "alice",
    "roles": ["viewer"],
    "daily_quota": 50
  }
}
```

- `roles` 为 `viewer` / `operator` / `admin` / `leader` 的非空子集；只有 `admin` / `leader` 角色能在请求里开启 `debug_trace`。
- `daily_quota` 省略时取 `LLM_DAILY_QUOTA_DEFAULT`（默认 200），`0` 表示不限额。
- 每个 subject 只能读写自己的会话：跨 subject 读取会话返回 404；拿着别人的 `session_id` 发起聊天会自动新建本人会话。

限流语义：

- `RATE_LIMIT_CHAT_PER_MINUTE`（默认 20）：每个 subject 的每分钟聊天请求上限，内存滑动窗口，`/api/chat` 与 `/api/chat/stream` 共用一个桶。
- 每日 LLM 配额按 subject 持久化在 `usage_counters` 表，超限后当天恢复不了。
- 评测配额独立于聊天配额：同表 `kind='grading'`，默认 `GRADING_DAILY_QUOTA=20` 次/天，`operator` / `admin` 角色豁免；超限由 `grade_plugin` 工具返回 `quota_exceeded`，不走 429。
- 超限均返回 429 并携带 `Retry-After` 响应头。

前端用法：account 模式下任一请求收到 401 会进入登录页（学号 + 密码，token 仅存于本浏览器 localStorage），登录成功或恢复后自动重试当前会话；侧边栏提供历史会话列表（点击即恢复本人会话，取代手动粘贴 session ID）、修改密码与退出登录入口。token 模式下仍保留粘贴静态 token 的入口。

**用量看板**：侧边栏"用量"面板展示当前 subject 的今日用量 / 配额与近 7 天趋势（`GET /api/usage/me`）；admin 角色还可看到全部 subject 的配额与累计用量（`GET /api/usage/subjects`，非 admin 返回 404）。数据与每日配额校验共用 `usage_counters` 表，面板数字即限流实际执行的口径。

## Quick Start

### Backend

```bash
cd ai_chat_assistant/backend
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
cp .env.example .env
```

编辑 `backend/.env`，至少配置：

```bash
LLM_API_BASE=...
LLM_API_KEY=...
LLM_MAX_TOKENS=2048
LLM_MAX_RETRIES=3
LLM_RETRY_BASE_SECONDS=0.5
CONVERSATION_RECENT_TURNS=6
CONVERSATION_SUMMARY_MAX_TOKENS=800
CONVERSATION_SUMMARY_MAX_CHARS=4800
CONVERSATION_MEMORY_MAX_CHARS=24000
DATABASE_BACKEND=postgres
DATABASE_URL=postgresql:///assistant
RAG_PROVIDER=llama_index
RAG_ENGINE=llama_index
LLAMA_INDEX_TABLE=li_knowledge_v1
RETRIEVAL_STRATEGY=hybrid_fusion_v2
ENABLE_DEBUG_TRACE=false
EMBEDDING_API_BASE=...
EMBEDDING_API_KEY=...
EMBEDDING_BATCH_SIZE=10
EMBEDDING_MAX_RETRIES=5
EMBEDDING_RETRY_BASE_SECONDS=1.0
```

然后重建索引并启动后端：

```bash
cd ai_chat_assistant/backend
source .venv/bin/activate
python scripts/ingest_llama_index.py
python -m uvicorn app.main:app --host 127.0.0.1 --port 8090
```

说明：
- `python -m uvicorn` 比直接执行 `uvicorn` 脚本更稳，能避免虚拟环境 shebang 失效问题
- 仓库默认使用本机 PostgreSQL 数据库 `assistant`；首次运行前须启用 `vector` 扩展
- 同一页面内的追问会由 `session_id` 关联：后端保留最近 6 轮原文并将更早内容压缩为摘要；每次追问仍会重新检索知识库并生成本轮引用。刷新页面不会恢复会话。
- 对话历史和摘要同时有服务端硬性字符上限；摘要服务异常时只保留受限的近期上下文，不会把完整历史发送给模型。
- 前端聊天使用 `POST /api/chat/stream` 的 SSE 流式接口；部署在 Nginx 等代理后须关闭响应缓冲（应用已发送 `X-Accel-Buffering: no`）。旧 `POST /api/chat` 保持兼容。
- 当前会话 ID 可在页面顶部点击复制。管理员或客户端可通过 `GET /api/chat/sessions/{session_id}?limit=100` 读取此前会话的消息和引用；该接口最多返回最近 200 条消息。
- 服务不会自动删除会话记录。维护人员可在 `backend/.env` 配置 `MAINTENANCE_API_KEY`，然后以 `X-Maintenance-Key` 请求未列入 OpenAPI/前端的 `GET /api/internal/session-storage`，读取会话表的物理占用字节数和会话数，再按运维流程手动清理。

### LlamaIndex 索引与回滚

默认 `RAG_ENGINE=llama_index`：LlamaIndex 负责 PostgreSQL/pgvector 节点存储、语义召回、QueryEngine 与流式回答；项目原有的 FTS、路径、符号、RRF 与 MMR 仍作为专用子检索器参与融合。若需紧急回滚，可显式设置 `RAG_ENGINE=legacy`：

```bash
cd ai_chat_assistant/backend
.venv/bin/python scripts/ingest_llama_index.py
```

该命令不会覆盖旧 `knowledge_chunks` 数据；索引表由 `LLAMA_INDEX_TABLE` 控制。它是幂等的：未变节点不会重复 embedding，过期节点会删除。索引重建没有公共 HTTP 接口，只能由管理员通过 CLI 或部署任务执行。

### PostgreSQL Backend（默认）

本地起 PostgreSQL + pgvector（macOS / Homebrew 示例）：

```bash
brew install postgresql@17 pgvector
brew services start postgresql@17
createdb assistant
psql -d assistant -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

初始化 PostgreSQL 运行时：

```bash
cd ai_chat_assistant/backend
# .env 里设 DATABASE_BACKEND=postgres / DATABASE_URL=postgresql:///assistant
# 1) 建 schema + HNSW 索引
.venv/bin/python -c "from app.db import init_db; init_db()"
# 2) 把现有 SQLite 知识库一次性搬过来（可选，幂等）
.venv/bin/python scripts/migrate_sqlite_to_postgres.py --sqlite ./data/assistant.db --truncate
# 或直接重新索引原始代码
.venv/bin/python scripts/ingest_llama_index.py
```

本地 socket 信任连接（Homebrew 默认）下 `DATABASE_URL=postgresql:///assistant` 即可，无需密码。

### One-Command Dev Start

如果你已经完成以下准备：

- `backend/.venv` 已创建并装好依赖
- `backend/.env` 已填写模型配置
- `frontend/node_modules` 已安装

可以直接在仓库根目录运行：

```bash
./start_dev.sh
```

脚本会自动：

- 启动后端 `uvicorn`
- 启动前端 `vite`
- 输出访问地址
- 把日志写到 `.logs/backend.log` 和 `.logs/frontend.log`
- 在你按 `Ctrl-C` 时一起关闭前后端

### Frontend

```bash
cd ai_chat_assistant/frontend
npm install
npm run dev
```

默认前端访问 `http://127.0.0.1:5174`，后端访问 `http://127.0.0.1:8090`。

## 数据生命周期（TTL / 备份 / Migration / 索引自动化）

**会话保留与清理**：会话按 `updated_at` 超过 `SESSION_RETENTION_DAYS`（默认 30 天）判定过期。三种触发方式：

```bash
# CLI（cron 入口；默认 dry-run 只打印统计，--apply 实删）
python scripts/purge_sessions.py --retention-days 30 --apply

# 维护端点（需 MAINTENANCE_API_KEY；dry_run 默认 true，显式传 false 实删）
curl -X POST -H "X-Maintenance-Key: <key>" \
  "http://127.0.0.1:8090/api/internal/purge-sessions?retention_days=30&dry_run=false"
```

删除按 citations → messages → sessions 顺序显式执行（不依赖数据库 cascade），实删写 `session_purge` 审计日志。cron 示例：`17 4 * * * cd <repo>/backend && .venv/bin/python scripts/purge_sessions.py --apply`。

**备份与恢复**：`deploy/backup.sh` 每日备份（cron：`20 3 * * * .../deploy/backup.sh`），postgres 模式经 `docker compose exec -T postgres pg_dump -Fc` 输出到 `backend/data/backups/`（保留 7 份，失败走 `ALERT_WEBHOOK_URL` 告警）；sqlite 回退模式直接复制 db 文件。恢复：

```bash
# postgres：恢复到干净库
docker compose up -d postgres
cat backups/assistant-<ts>.dump | docker compose exec -T postgres pg_restore -U postgres -d assistant --clean --if-exists
docker compose up -d
# sqlite：停后端后用备份文件覆盖 DATABASE_URL 指向的 db 文件即可
```

> 待演练项：pg_restore 全量恢复需在有 Docker 的主机上做一次实操验证。

**版本化 migration（alembic）**：schema 基线即 `postgres_schema.sql` 的幂等 DDL（由 `init_db()` 应用）。存量库一次性打基线：

```bash
cd backend
.venv/bin/python -c "from app.db import init_db; init_db()"
.venv/bin/alembic stamp head
```

此后所有 schema 变更必须新增 revision（`alembic revision -m "..."`，写入 `alembic/versions/`），部署时 `alembic upgrade head`。`alembic upgrade head --sql` 可离线生成 SQL 供评审。

**索引自动化**：`deploy/auto_ingest.sh` 检测 DPDK 仓库 `git rev-parse HEAD` 变化后触发增量重建（mkdir 锁防并发，失败告警）：

```bash
*/30 * * * * ALERT_WEBHOOK_URL=<webhook> /opt/dpdk/ai_chat_assistant/deploy/auto_ingest.sh >> /var/log/assistant-ingest.log 2>&1
```

默认在 compose 环境执行 `docker compose exec -T backend python scripts/ingest_llama_index.py`；非容器部署用 `INGEST_CMD` 覆盖。手工兜底仍是 `python scripts/ingest_llama_index.py`。

## 可观测性（日志 / Metrics / 告警）

- 应用日志统一走 `assistant` logger 的单行 JSON 格式（`backend/app/core/logging_config.py`）：stdout 始终输出；设置 `LOG_FILE` 时附加 `RotatingFileHandler`（默认 50MB × 5）。级别由 `LOG_LEVEL` 控制。结构化事件（`chat_trace`、`llm_retry`、`auth_failed`、`rate_limit_hit`）以顶层字段输出，可直接按字段 grep。
- `/metrics` 暴露 Prometheus 指标（`prometheus-fastapi-instrumentator` HTTP 指标 + `assistant_llm_requests_total` / `assistant_llm_request_duration_seconds` / `assistant_chat_retrieval_duration_seconds` / `assistant_chat_fallbacks_total` / `assistant_rate_limit_hits_total`）。该端点在应用层无认证：生产拓扑中 nginx 只反代 `/api/`，`/metrics` 不外露；**不要**把它加进反代配置。
- 最小告警：`deploy/healthcheck.sh` 检查 `HEALTH_URL` 与日志 ERROR 关键字，连续 `FAIL_THRESHOLD`（默认 3）次失败后向 `ALERT_WEBHOOK_URL` POST 告警。cron 示例：

  ```bash
  */5 * * * * HEALTH_URL=https://<主机>/api/health ALERT_WEBHOOK_URL=<webhook> /path/to/deploy/healthcheck.sh >> /var/log/assistant-healthcheck.log 2>&1
  ```

  本地验证：`HEALTH_URL=http://127.0.0.1:9 deploy/healthcheck.sh --dry-run`。

## Production Deployment（Docker Compose + Nginx + HTTPS）

一条命令在 Linux 主机拉起完整服务：前端为 nginx 托管的静态构建产物，对外仅暴露 443。

### 前置条件

- Docker Engine 24+ 与 compose 插件（WSL2 Ubuntu 可用 `deploy/wsl2/install_docker.sh` 一键安装）
- DPDK 主项目仓库在宿主机上（作为只读知识源挂载进 backend 容器）
- TLS 证书：演练用 `bash deploy/certs/gen_self_signed_cert.sh` 生成自签名证书；生产将正式证书放到 `deploy/certs/server.crt` / `server.key`

### 配置

```bash
cp deploy/.env.example deploy/.env
```

`deploy/.env` 关键变量（完整注释见模板）：

| 变量 | 说明 |
| --- | --- |
| `POSTGRES_PASSWORD` | postgres 容器密码，必须与 `DATABASE_URL` 中一致 |
| `DATABASE_URL` | 容器内固定指向 `postgresql://postgres:<密码>@postgres:5432/assistant` |
| `LLM_API_KEY` / `EMBEDDING_API_KEY` | 模型与 embedding 服务凭据 |
| `AUTH_MODE` | 生产必须为 `account`（账号体系）或 `token`（静态 token） |
| `API_TOKENS_JSON` | token → `{subject, roles, daily_quota}` 映射 |
| `DPDK_REPO_HOST_PATH` | 宿主机上 DPDK 仓库路径（默认 `..`，即本仓库的父目录） |

### 启动 / 停止 / 升级

```bash
# 构建并启动（首次）
docker compose build
docker compose up -d

# 首次或知识源更新后重建索引（幂等，增量）
docker compose exec backend python scripts/ingest_llama_index.py

# 查看日志 / 状态
docker compose logs -f backend
docker compose ps

# 停止 / 恢复（数据在命名卷 pgdata 中，down && up 不丢）
docker compose down
docker compose up -d

# 升级：拉取新代码后重建对应镜像并替换容器
docker compose build backend && docker compose up -d backend

# 回滚：git checkout 到上一个版本，重新 build + up -d；数据库 schema 为幂等 DDL，无需回退
```

访问 `https://<主机>/`（自签名证书浏览器会提示不受信任，属预期）。认证为 `AUTH_MODE=account` 时先执行 `docker compose exec backend python scripts/create_user.py --id U20260000001 --roles leader` 引导组长，再在登录页登录；`AUTH_MODE=token` 时在页面左侧粘贴 token 即可。

非容器备选：`deploy/systemd/assistant-backend.service` 提供了 systemd 单元模板（需自备本机 PostgreSQL 与反代）。

## Test Guide

后端测试已全部迁移为 pytest（开发依赖：`pip install -r backend/requirements-dev.txt`）。
两个自定义 mark：`live`（需要真实 LLM/embedding 或已 ingest 的知识库）、
`postgres`（需要运行中的 PostgreSQL）。CI 与本机快速回归使用无网络子集：

```bash
cd ai_chat_assistant/backend
.venv/bin/python -m pytest -m "not live and not postgres"          # CI 同款
.venv/bin/python -m pytest -m "not live and not postgres" --cov=app --cov-fail-under=60
```

下文保留各测试的用途说明与运维工具脚本的 CLI 用法；任何 `python tests/test_x.py`
也可以写成 `python -m pytest tests/test_x.py`，二者等价。

**agent 行为回归**：`backend/tests/agent/test_golden_scenarios.py` 是 agent 黄金场景集
（25 例，模拟真实用户场景断言路由、工具选择、反馈纪律与护栏降级；fake 模型 + 内存
fake MCP，确定性、无网络），归入默认集，改 agent 提示词/工具后必须跑：

```bash
cd ai_chat_assistant/backend
.venv/bin/python -m pytest tests/agent/test_golden_scenarios.py
```

前端测试：`cd ai_chat_assistant/frontend && npm test`（vitest，覆盖 `src/api/client.js`）。

CI：仓库根目录 `.workflow/`（Gitee Go 流水线即代码）：`MasterPipeline.yml`
（push 到 main：ruff → pytest+覆盖率门禁 → 前端测试与构建 → pip-audit 告警）
与 `PRPipeline.yml`（PR 合并前检查）。需在 Gitee 仓库页面开通 Gitee Go 后生效。

### 1. 检索回归测试

```bash
cd ai_chat_assistant/backend
.venv/bin/python tests/test_retrieval.py
.venv/bin/python tests/test_storage_provider.py
```

用途：
- 验证 query rewrite、topic boost、filename boost、去重和协议/子系统召回
- 用例由题库 `tests/rag_eval/queries.yml` parametrize 生成（唯一事实来源），SQLite 与 PostgreSQL backend 下均应全绿
- 量化指标（recall@1/5/15、MRR、基线漂移）用 `tests/rag_eval/rag_eval_runner.py`，流程见 `docs/runbook.md` 第 10 节

在 PostgreSQL backend 下跑：

```bash
DATABASE_BACKEND=postgres DATABASE_URL=postgresql:///assistant \
    .venv/bin/python tests/test_retrieval.py
```

### 1b. SQLite → PostgreSQL 迁移

```bash
cd ai_chat_assistant/backend
DATABASE_BACKEND=postgres DATABASE_URL=postgresql:///assistant \
    .venv/bin/python scripts/migrate_sqlite_to_postgres.py \
    --sqlite ./data/assistant.db --truncate
```

用途：
- 把现有 SQLite 知识库 + 对话历史一次性搬到 PostgreSQL
- `embedding_json` → `VECTOR`，`metadata_json` → `JSONB`，跳过 `knowledge_chunks_fts`（PG 用 generated `search_vector`）
- `--truncate` 幂等；自动过滤 SQLite 历史 FK 关闭残留的孤儿 citation

### 2. 端到端问答测试

```bash
cd ai_chat_assistant/backend
.venv/bin/python tests/test_chat_e2e.py
```

常用形式：

```bash
.venv/bin/python tests/test_chat_e2e.py --limit 1
.venv/bin/python tests/test_chat_e2e.py --limit 1 --debug-trace
.venv/bin/python tests/test_chat_e2e.py --question "engine_ops_t 是什么？"
.venv/bin/python tests/test_chat_e2e.py --base-url http://127.0.0.1:8090
```

用途：
- 探测 `LLM` / `Embedding` 是否真的可用
- 直接验证 `/api/chat` 是否返回真实生成答案，而不是 fallback

说明：
- 默认使用 ASGI in-process 调用，不依赖本机端口绑定
- 如果当前运行环境禁止外网，脚本会明确显示 `ConnectError`

### 3. 答案质量回归测试

```bash
cd ai_chat_assistant/backend
.venv/bin/python tests/test_answer_quality.py
.venv/bin/python tests/test_answer_quality.py --case engine_ops
.venv/bin/python tests/test_answer_quality.py --case engine_ops --debug-trace
.venv/bin/python tests/test_answer_quality.py --case engine_ops --show-retrieval
.venv/bin/python tests/test_answer_quality.py --question "engine_ops_t 是什么？" --expect engine_ops_t --expect can_handle --expect-citation engine.h
```

用途：
- 输出每个问题的完整回答和引用来源
- 检查答案是否 fallback、是否包含关键点、引用是否命中预期文件
- 可用 `--debug-trace` 输出 `rewrite / embedding / retrieval / answer` 等阶段耗时
- 可用 `--show-retrieval` 同时输出本地检索 top chunks，便于区分召回问题和生成问题
- 默认内置 20 个来自 `test_retrieval.py` 的高覆盖问题，用 deterministic rubric 发现检索或提示词退化

### 4. 并发压测

```bash
cd ai_chat_assistant/backend
.venv/bin/python tests/load_chat_concurrency.py
```

常用形式：

```bash
.venv/bin/python tests/load_chat_concurrency.py --levels 1,10,30
.venv/bin/python tests/load_chat_concurrency.py --levels 4,8 --requests-per-level 8 --debug-trace
.venv/bin/python tests/load_chat_concurrency.py --levels 80 --requests-per-level 80 --timeout 180 --debug-trace
.venv/bin/python tests/load_chat_concurrency.py --levels 10 --requests-per-level 30 --debug-trace
.venv/bin/python tests/load_chat_concurrency.py --base-url http://127.0.0.1:8090
```

用途：
- 测 `/api/chat` 在真实模型链路下的并发吞吐与延迟
- 加 `--debug-trace` 后会汇总 `avg_retrieval_ms`、`avg_answer_ms` 和 `p95_answer_ms`
- 每个请求默认创建独立匿名会话，因此该测试衡量的是“可同时服务多少新会话”，不是同一会话并发追问
- 对部署中的服务，请使用 `--base-url http://127.0.0.1:8090`；80 并发端到端测试建议设置 `--timeout 180`

### 5. 分阶段耗时指标

每次 `/api/chat` 都会写一条单行 JSON 日志，事件名为 `chat_trace`，包含：

- `total_ms`
- `stages_ms.rewrite_ms`
- `stages_ms.embedding_ms`
- `stages_ms.candidate_fetch_total_ms`
- `stages_ms.rerank_ms`
- `stages_ms.mmr_ms`
- `stages_ms.retrieval_total_ms`
- `stages_ms.answer_generation_ms`
- `stages_ms.citation_save_ms`

默认不把 trace 返回给客户端。开发调试时有两种方式：

- 设置 `ENABLE_DEBUG_TRACE=true`
- 或请求 `/api/chat` 时在 `context` 中传入 `"debug_trace": true`

## Measured Performance

以下数据来自 `RAG_ENGINE=llama_index`、PostgreSQL + pgvector、真实 embedding 与真实 LLM 的端到端测试，不是 mock。80 并发结果是在修复 LlamaIndex 连接池复用后取得：

| 并发会话 | 请求数 | 成功 | 吞吐 | 平均延迟 | P95 延迟 |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 4 | 8 | 8/8 | 0.271 req/s | 13.3s | 17.7s |
| 8 | 8 | 8/8 | 0.286 req/s | 19.0s | 25.9s |
| 80 | 80 | 80/80 | 0.829 req/s | 74.1s | 90.0s |

结论：

- 推荐将 `4` 个并发新会话作为交互体验较稳定的运行档位；`8` 并发仍能成功完成，但尾延迟明显升高。
- 当前服务能完成 `80` 个并发新会话，且不再出现 PostgreSQL `too many clients`；代价是排队导致 P95 接近 `90s`，不适合作为普通用户的交互目标。
- 高并发下主要瓶颈是 pgvector 检索连接排队与上游模型生成，而不是 FastAPI 请求处理。若要降低尾延迟，应扩大模型侧配额、横向扩展应用实例，并按数据库连接上限调整连接池。

## Current Limitations

- 默认数据库为 `PostgreSQL` + `pgvector`（`DATABASE_BACKEND=postgres`）；SQLite 仅作显式回退与迁移源
- legacy `ingest_repo.py` 与 LlamaIndex 索引是独立数据面；生产默认使用后者，只有需要 legacy 回滚时才需要同时维护前者
- `TOPIC_PATH_KEYWORDS` 仍需人工维护
- 同一 `session_id` 的追问会使用摘要和近期历史消解指代；同一会话的并发追问仍不建议启用
- `ai_chat_assistant/backend/` 和 `frontend/` 默认不在知识库索引范围内

## Important Files

- [backend/app/services/retrieval_service.py](backend/app/services/retrieval_service.py)
- [backend/app/services/agent_service.py](backend/app/services/agent_service.py)
- [backend/app/agent/graph.py](backend/app/agent/graph.py)（LangGraph 编排）、[backend/app/agent/tools.py](backend/app/agent/tools.py)（五件工具）、[backend/app/agent/mcp_client.py](backend/app/agent/mcp_client.py)（harness MCP 调用）
- [backend/app/core/llm_client.py](backend/app/core/llm_client.py)
- [backend/app/core/async_cache.py](backend/app/core/async_cache.py)
- [backend/app/db.py](backend/app/db.py)
- [backend/app/storage/interfaces.py](backend/app/storage/interfaces.py)
- [backend/app/storage/retrieval_store.py](backend/app/storage/retrieval_store.py)
- [backend/app/storage/postgres_schema.sql](backend/app/storage/postgres_schema.sql)
- [backend/scripts/migrate_sqlite_to_postgres.py](backend/scripts/migrate_sqlite_to_postgres.py)
- [backend/tests/test_chat_e2e.py](backend/tests/test_chat_e2e.py)
- [backend/tests/load_chat_concurrency.py](backend/tests/load_chat_concurrency.py)
