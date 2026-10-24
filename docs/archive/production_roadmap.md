# RAG 项目生产化路线文档

> 版本：v1（分支 `v1` @ `0012ba8`）
> 编写日期：2026-08-17
> 最近更新：2026-08-21 — M6（账号体系：学号登录 + 管理员用户管理 + 每账号历史会话）已完成；M5（工程化：pytest 迁移 + Gitee Go CI + vitest + 用量看板 + runbook + pip-audit）已完成
> 目标：将当前"单机内网演示就绪"的 RAG 助手推进为可在生产环境运行的成熟产品。

---

## 1. 现状评估

### 1.1 已经扎实的部分（无需重做）

- 检索/生成链路：LlamaIndex + pgvector 混合检索（语义 + FTS + 路径 + 符号融合），CLI 幂等重建索引（`backend/scripts/ingest_llama_index.py`，未变节点跳过、过期节点清理）。
- LLM/embedding 客户端：指数退避重试、超时控制、`Retry-After` 尊重（`backend/app/core/llm_client.py`）。
- 数据库：psycopg 连接池（min 4 / max 32）、LlamaIndex 存储池（`pool_pre_ping`），阻塞调用全部 `asyncio.to_thread` 不堵事件循环。
- 同 key 请求合并 + TTL 缓存（`backend/app/core/async_cache.py`）。
- SQL 全部参数化，动态表名有白名单校验；前端 Markdown 渲染经 DOMPurify 消毒。
- 测试资产：检索回归 42 例、答案质量 20 题、并发压测、流式/会话/维护 key 等脚本（`backend/tests/`）。
- SSE 已发送 `X-Accel-Buffering: no`，具备反代适配基础。

### 1.2 缺口总览（按优先级）

| 优先级 | 维度 | 关键缺口 |
|--------|------|----------|
| P0 | 安全 | ~~全部业务端点零认证；会话凭 ID 即可越权读取；无限流（付费 LLM 无成本上限）；`debug_trace` 任意客户端可开；无安全响应头~~（M1 已解决）；无 HTTPS（M2） |
| P1 | 部署 | ~~无 Dockerfile / compose / systemd；前端只能 dev server；依赖未完全 pin；无部署文档~~（M2 已解决） |
| P2 | 可观测性与数据运维 | ~~无日志轮转/metrics/告警~~（M3 已解决；tracing 导出未做）；~~无自动备份；会话永不删除；无版本化 DB migration；索引重建靠手工~~（M4 已解决） |
| P3 | 产品质量与工程化 | ~~llama_index 生成路径无重试（绕过既有退避逻辑）~~（M3 已解决）；无 CI；前端零测试；无多用户/配额体系（M6 账号体系） |

### 1.3 缺口明细与代码证据

**安全**

- `/api/chat`、`/api/chat/stream`、`/api/chat/sessions/{id}`、`/api/sources/{chunk_id}` 等端点无认证（`backend/app/api/chat.py:18-81`、`backend/app/main.py:13-47` 无 Auth dependency）。
- 会话 ID 为 `sess_<12 hex>`（48 bit），知道 ID 即可读全部历史；前端提供"粘贴 ID 恢复会话"入口（`frontend/src/main.jsx:198-208`）。
- schema 有 `user_id` 列但写入恒为 `"anonymous"`，读取不做 owner 校验（`backend/app/services/session_service.py:44`、`backend/app/storage/postgres_schema.sql:10`）。
- 无任何 rate limiting；任何人可无限触发付费 LLM 调用。
- `debug_trace` 可由请求方通过 `context.debug_trace:true` 打开，内部异常文本回传客户端（`backend/app/services/agent_service.py:298,379`）。
- CORS 配了 `allow_credentials=True` 但没有任何凭证机制（`backend/app/main.py:14-20`）。
- 无 CSP / X-Frame-Options 等安全响应头。
- `.env.example` 含开发机绝对路径（提交前应移除）。

**部署**

- 唯一启动方式 `start_dev.sh`（uvicorn + `npm run dev`，绑 `127.0.0.1`）。
- `frontend/package.json` 有 `build` 脚本但 `dist/` 被 gitignore，无静态托管说明。
- `requirements.txt` 中 `psycopg[binary]>=3.2.4` / `psycopg-pool>=3.2.4` 未 pin，无 lock 文件。
- 无 HTTPS/TLS 任何配置或文档。

**可观测性与数据运维**

- 日志仅每请求一条 JSON `chat_trace`（`agent_service.py:233-234`），无 logging 级别/格式/轮转配置；`start_dev.sh` 仅 `>` 重定向。
- 无 metrics、无外部 tracing 导出、无错误监控。
- 备份仅 `backend/data/backups/` 一个手工 sqlite 文件。
- 会话永不自动删除（`session_service.py:194` 注明 "Retention is deliberately manual"）。
- 无 alembic；schema 演进全靠 `postgres_schema.sql` 幂等 DDL，无版本表、无回滚。
- 索引重建无 HTTP 接口、无定时机制（README 明确为管理员 CLI）。

**质量与工程化**

- 默认 llama_index 引擎的生成路径使用裸 `OpenAILike(max_retries=0)` 给 ResponseSynthesizer，绕过了 `generate_with_attempts` 的退避逻辑（`backend/app/services/llama_index_service.py:181-243` vs `backend/app/rag/providers.py:66-87`）——上游抖动直接 500。
- 非流式 `answer()` 无异常捕获，生成异常冒泡为 500（`agent_service.py:311-383`）。
- 测试为手工脚本，无 pytest 配置、无 CI、无覆盖率统计；前端无测试。
- README 链接为本机绝对路径，不可移植；`postgres_schema.sql` 头注释（"intentionally not used"）与实际用途矛盾。

---

## 2. 六步实施计划

### 第 1 步：认证 + 限流（堵住成本与越权风险） ✅ 已完成 2026-08-17

**目标**：任何业务端点必须持有有效凭证才可访问；单个凭证/IP 的调用频率和每日 LLM 调用量有硬上限。

**任务清单**

1. **API token 认证**
   - 在 `Settings` 增加 `api_tokens_json`（JSON：`token -> {subject, roles, daily_quota}`），启动时校验格式（token 长度 ≥ 32）。
   - 新增 FastAPI dependency `require_auth`：从 `Authorization: Bearer` 解析 token，`secrets.compare_digest` 比对，注入 `Request.state.subject`。
   - 挂载到 `/api/chat*`、`/api/sources/*` 全部业务路由；`/api/health` 保持匿名（供监控探测）。
   - 前端在登录页或设置页输入 token，存 `localStorage`，`frontend/src/api/client.js` 统一带 header；SSE 流式请求注意 `EventSource` 不支持自定义 header，需改用 `fetch` + ReadableStream 或 query 参数签名（推荐前者）。
2. **会话归属校验**
   - `chat_sessions` 写入真实 `user_id`（即 token 的 subject），读取会话/历史时校验 `session.user_id == request.subject`，否则 404（不泄露存在性）。
   - 保留匿名兼容开关 `auth_mode=local`（本机开发跳过认证），生产强制 `token`。
3. **限流**
   - 引入 `slowapi`（或自研滑动窗口，基于现有 `async_cache.py` 思路）：每 subject 每分钟 N 次 chat 请求、每日 LLM 调用配额；超限返回 429 + `Retry-After`。
   - 配额计数落库（新表 `usage_counters`），重启不丢，供后续计费/审计。
4. **杂项收口**
   - `debug_trace` 仅当 subject 具有 admin 角色时生效。
   - 添加安全响应头中间件：`X-Content-Type-Options: nosniff`、`X-Frame-Options: DENY`、`Referrer-Policy: no-referrer`、宽松 CSP（前端为自托管 SPA）。
   - CORS 的 `allow_credentials` 与 `allow_origins` 收敛为精确白名单。
   - `.env.example` 移除本机绝对路径。

**验收标准**

- 无 token 请求业务端点返回 401；错误 token 返回 401；A 用户的 token 读 B 用户会话返回 404。
- 连续超限请求返回 429；配额计数在重启后保留。
- 既有测试脚本（`backend/tests/test_session_storage.py` 等）在 `auth_mode=local` 下全部通过；新增认证/限流/越权测试用例。

---

### 第 2 步：部署形态（容器化 + 反代 + HTTPS） ✅ 已完成 2026-08-19

**目标**：一条命令在任何 Linux 主机拉起完整服务；前端为静态构建产物；对外仅暴露 443。

**任务清单**

1. **依赖锁定**
   - `pip-compile`（pip-tools）生成 `requirements.lock`，pin 全部传递依赖；`psycopg` 改为精确版本。
2. **Docker 化**
   - `backend/Dockerfile`：`python:3.11-slim`（顺带升级 3.9→3.11，注意验证 LlamaIndex 兼容性）、多阶段构建、非 root 用户、`HEALTHCHECK` 调 `/api/health`。
   - `frontend/Dockerfile`：node 构建 `dist` → nginx serve 阶段。
   - 根级 `docker-compose.yml`：`backend` + `frontend(nginx)` + `postgres(pgvector/pgvector:pg17)`；`.env` 通过 `env_file` 注入，key 不进镜像。
   - 数据卷：pg 数据卷；知识源（外层 dpdk 仓库）以只读卷挂载进 backend。
3. **Nginx 反代**
   - `deploy/nginx.conf`：443 TLS（证书路径外挂）、`/api/` 反代到 backend（`proxy_buffering off`，SSE 必需）、其余路径 serve 前端 `dist`、安全响应头、请求体大小限制。
4. **进程与启动**
   - 提供 `docker compose up -d` 一条命令；另附非容器备选：`deploy/systemd/assistant-backend.service` 单元文件。
5. **文档**
   - README 新增"生产部署"章节：前置条件（Docker、证书）、环境变量清单、启动/停止/升级流程、回滚步骤；修正本机绝对路径链接。

**验收标准**

- 干净 Linux 主机上 clone 仓库 + 填 `.env` + `docker compose up -d` 后，https 访问可完成一次完整流式问答。
- `docker compose down && up` 后会话数据与知识索引不丢。
- 升级流程（拉新镜像重启）有文档且演练过一次。

---

### 第 3 步：可观测性 + 修复生成路径重试缺口 ✅ 已完成 2026-08-20

**目标**：故障可发现、可定位、可追溯；上游抖动不再直接打穿到 500。

**任务清单**

1. **修复 llama_index 生成路径无重试**（真实 bug，优先做）
   - 让 `LlamaIndexAnswerGenerator` 复用 `generate_with_attempts` 的退避策略（`backend/app/rag/providers.py:66-87`），或给 `OpenAILike` 包装一层带重试的 LLM 适配器；保持流式接口语义。
   - 非流式 `answer()` 增加异常捕获，返回与流式路径一致的降级回答（复用 `_fallback_answer`）。
   - 补回归测试：mock 上游 429/500/超时，断言最终成功或降级而非冒泡。
2. **日志**
   - 统一 `logging.config`：JSON 格式、级别由 `LOG_LEVEL` 控制、`RotatingFileHandler`（如 50MB × 5）+ stdout。
   - 关键路径补日志：检索耗时、LLM 调用结果（不含 prompt 内容）、认证失败、限流命中。
3. **Metrics**
   - 引入 `prometheus-fastapi-instrumentator` 暴露 `/metrics`（仅内网/带认证）；自定义指标：LLM 调用次数/耗时/失败率、检索耗时、限流命中数、活跃会话数。
4. **告警（最小可用）**
   - 起步阶段：health check 定时探测（cron + curl）+ 日志关键字告警（ERROR 邮件/webhook）；有 Prometheus 后再上 Alertmanager。
5. **链路追踪（可选增强）**
   - 现有 `RequestTrace` 增加 OTLP 导出开关（v2 分支已有 OpenTelemetry 配置可参考回填）。

**验收标准**

- 人为制造 DashScope 429/超时，服务返回降级回答且日志/指标可见，无未捕获 500。
- 日志文件按大小轮转；`/metrics` 可拉到 LLM 调用计数与 P95 耗时。
- 连续 3 次 health check 失败能触达告警接收人。

---

### 第 4 步：数据生命周期（TTL + 备份 + 索引自动化） ✅ 已完成 2026-08-20

**目标**：数据有保留策略、可恢复、知识库跟随外层仓库自动更新。

**任务清单**

1. **会话 TTL**
   - `Settings` 增加 `session_retention_days`（默认 30）；定时任务（APScheduler 或外部 cron 调 CLI）软删/硬删过期会话及摘要；删除动作写审计日志。
   - 维护端点扩展：`POST /api/internal/purge-sessions`（带维护 key，支持 dry-run）。
2. **备份与恢复**
   - 每日 `pg_dump` 到 `backend/data/backups/`（或对象存储），保留 N 份；写恢复 runbook 并演练一次全量恢复。
3. **版本化 migration**
   - 引入 alembic，将现有 `postgres_schema.sql` 固化为 baseline revision；后续 schema 变更全部走 revision；`init_db()` 保留为首次初始化入口。
4. **索引自动化**
   - 外层仓库更新后自动重建：cron（如每 30 分钟检测 `git rev-parse HEAD` 变化则触发 `ingest_llama_index.py`）或外层仓库 push webhook；任务加锁防并发重建，失败告警。
   - 保留 CLI 手工触发能力作为兜底。

**验收标准**

- 过期会话被自动清理且审计日志可查；维护端点 dry-run 输出与实删一致。
- 用备份文件能在干净数据库上完整恢复并正常问答。
- 外层仓库提交新文档后，30 分钟内（可配）新内容可被检索到，全程无人工干预。
- alembic 版本表存在，`alembic upgrade head` / `downgrade` 均演练通过。

---

### 第 5 步：工程化与长期演进 ✅ 已完成 2026-08-21

**目标**：变更有保障、协作可持续、功能可扩展。

**任务清单**

1. **CI**（Gitee Go）✅
   - 流水线：lint（ruff）→ 后端测试（18 个手工脚本已全部改造为 pytest，`pytest -m "not live and not postgres"` 为 CI 子集）→ 前端 `vitest` + `npm run build`。
   - 配置即代码：`.workflow/MasterPipeline.yml`（push 到 main）与 `.workflow/PRPipeline.yml`（PR 门禁）。
   - 测试覆盖率统计（pytest-cov），底线 `--cov-fail-under=60`（实测 63%，逐步提升）。
   - Docker 镜像构建验证不在 Gitee Go 内（build@docker 需镜像仓库凭据），发布前本机 `docker compose build` 演练。
2. **测试体系补强** ✅
   - 18 个手工脚本全部迁移为 pytest 套件（72 个无网络用例全绿）；`live` / `postgres` 两个 mark 标记外部依赖用例；4 个运维工具脚本保留 argparse CLI。
   - 前端引入 vitest 覆盖 api client（token、401、SSE 解析、会话恢复）。
3. **多用户配额与用量看板** ✅
   - `GET /api/usage/me`（本人今日用量/配额/近 7 天）与 `GET /api/usage/subjects`（admin 全 subject 视图，非 admin 404）；前端侧边栏"用量"面板。
4. **文档收口** ✅
   - 运维 runbook：`docs/runbook.md`，覆盖 6 个故障场景（LLM 上游、DB 连接、索引漂移、备份恢复、限流风暴、证书到期）。
   - README 测试段落 pytest 化并补 CI 说明；修正 `postgres_schema.sql` 头注释；`.env.example` 与部署文档对齐（M1/M2 已完成）。
   - v1/v2 分支说明：本仓库实际仅有 main 分支（v1/v2 为路线图编写时的历史语境，现仓库不存在这两条分支），演进策略为单 main 分支 + PR 门禁，故原"归档两分支差异说明"条目以此说明收口。
5. **依赖治理** ✅
   - CI 内接入 pip-audit（首版告警不阻塞）；依赖全部 pin 在 `backend/requirements.lock`，季度升级窗口按 runbook 演练节奏执行。

**验收标准**

- 每次 push 自动跑完整 CI，红灯阻塞合并（需先在 Gitee 仓库页面开通 Gitee Go）。
- 覆盖率报告可见且达标（60% 门禁）。
- runbook 覆盖至少 5 个已发生或高概率故障场景，并经一次桌面演练。

---

### 第 6 步：账号体系（登录 + 每账号历史会话） ✅ 已完成 2026-08-21

**目标**：用户以用户名/密码登录；每个账号拥有独立、可持续保存的历史会话；替代静态 token 配置。

**前置说明**：M1 已把下游链路（会话归属、限流、配额、角色）全部收敛到 `require_auth` 返回的 `Principal(subject, roles, daily_quota)` 上，且 `assistant_sessions.user_id` 已按身份隔离写入。因此本步只改变凭证的**签发与校验方式**，下游路由和会话逻辑基本不动，无数据迁移负担。

**实施说明（与下方原始清单的差异）**：实际落地时账号即学生学号（`U2026xxxxxxx`，管理员创建时指定，初始密码 = 学号），不提供自助注册；管理员通过前端"用户管理"面板创建/批量导入、重置密码、启用/停用、改配额（`backend/app/api/admin.py`），第一个管理员由 `scripts/create_user.py` 引导；新增 `POST /api/auth/change-password` 供用户自助改密；登录端点有每 IP 每分钟 10 次限流；静态 `API_TOKENS_JSON` 在 account 模式下保留为应急兜底。实现入口：`AUTH_MODE=account`、`backend/app/services/user_service.py`、`backend/app/api/auth.py`、alembic `0002_users_api_tokens`。

**任务清单**

1. **账号与凭证存储**
   - 新表 `users`（`username` 唯一、密码 bcrypt 哈希、角色、日配额、创建时间）和 `api_tokens`（token 哈希、所属 user、签发/过期时间、吊销标记）。
   - 首批账号通过 CLI 脚本创建（`scripts/create_user.py`），暂不做开放注册（需要时再开邀请制）。
2. **登录/登出端点**
   - `POST /api/auth/login`：验密 → 生成 token（≥32 字符随机）落库 → 返回给前端；`POST /api/auth/logout`：吊销当前 token。
   - `require_auth` 增加第三种来源：`auth_mode=account`（或 token 模式下自动回落查 `api_tokens` 表），静态 `API_TOKENS_JSON` 保留作为管理员兜底。
   - token 过期时间可配（默认 7 天），过期即 401，前端重新登录。
3. **前端登录页**
   - 把 M1 的"粘贴 token"表单替换为登录页（用户名/密码），登录后存 token 到 localStorage（沿用现有 `assistant_api_token` 机制与 401 自动弹出逻辑）。
4. **每账号历史会话**
   - 新增 `GET /api/chat/sessions`：按 `user_id` 列出本人会话（标题、更新时间，倒序分页）。
   - 前端侧边栏渲染历史会话列表，点击即恢复，取代"手动粘贴 session ID"。
5. **存量数据过渡**
   - 提供一次性 SQL/脚本把 `user_id='anonymous'`（local 模式时期）的会话划归指定账号，可选执行。

**验收标准**

- 新用户可通过 CLI 创建；登录页登录后正常问答，token 落库可查。
- 账号 A 的会话列表不包含账号 B 的会话；A 的 token 读 B 的会话返回 404。
- 登出后旧 token 立即失效（401）；过期 token 同样失效。
- 存量 anonymous 会话执行过渡脚本后归属目标账号且可在其列表中看到。
- 认证/限流既有测试全绿，新增登录/登出/会话列表用例。

---

## 3. 里程碑与排期建议

| 里程碑 | 内容 | 状态 | 建议周期 |
|--------|------|------|----------|
| M1 | 第 1 步（认证 + 限流） | ✅ 已完成 2026-08-17 | 1 周 |
| M2 | 第 2 步（部署形态：Docker Compose + Nginx + HTTPS） | ✅ 已完成 2026-08-19 | 1 周 |
| M3 | 第 3 步（可观测性 + 重试修复） | ✅ 已完成 2026-08-20 | 1 周 |
| M4 | 第 4 步（数据生命周期） | ✅ 已完成 2026-08-20（pg 恢复演练待有 Docker 的主机） | 1 周 |
| M5 | 第 5 步（工程化：CI 用 Gitee Go，持续） | ✅ 已完成 2026-08-21（Gitee Go 需在仓库页面开通后验证首次流水线） | 长期，先行落地 CI |
| M6 | 第 6 步（账号体系：登录 + 每账号历史会话） | ✅ 已完成 2026-08-21 | 1~2 天 |

> M1 完成前**不要**将服务暴露到本机回环以外的任何网络；M2 完成后才具备公网部署的最低条件。

## 4. 风险与注意事项

- ~~**前端 SSE 认证改造**是第 1 步最大的工作量点~~（已排除：前端流式本来就是 `fetch` + ReadableStream，M1 直接加 `Authorization` header 即可，无需重构）。
- **Python 3.9 → 3.11 升级**需回归验证 LlamaIndex / pgvector 依赖兼容性，建议放在第 2 步容器化时一并做（容器内版本与宿主解耦）。
- 第 1 步的会话归属校验会改变"粘贴 session_id 恢复会话"的语义（只能恢复自己的会话），属于有意的行为变更，需在前端文案中说明。
- 第 6 步账号化时，local 模式存量的 `user_id='anonymous'` 会话不属于任何账号，届时执行过渡脚本划归指定账号或放弃。
- 双数据面（legacy `knowledge_chunks` 与 `li_knowledge_v1`）在第 4 步自动化前需确认生产只用 llama_index 一条链路，legacy 通道冻结不再维护。
