# AGENTS.md

> 本文件面向 AI 编码代理，描述本仓库的架构、命令与约定。README 面向人类用户，内容更偏使用说明；二者不一致时以代码与本文件为准。项目文档与代码注释以中文为主（技术术语保留英文），新写注释/文档请沿用中文。

## 当前文档与批次约束

- 阅读入口：[docs/README.md](docs/README.md)；实现现状与证据等级：[docs/architecture_current.md](docs/architecture_current.md)。
- 当前目标及决策以 [docs/decisions.md](docs/decisions.md) 为准；[docs/roadmap.md](docs/roadmap.md) 是唯一阶段状态入口。`docs/archive/` 只供历史追溯，其中的交接指令、完成声明与旧排期不得直接执行。
- 每批限定目标、范围、非目标、验收和回退；批次内自主完成授权工作。用户验收并单独授权后才开启下一批，不自动追加目标。
- 代码实现、自动验证、场景验收、用户验收分别记录。历史测试结果不得当作本轮已验证，未知项明确写未验证。
- 产品路径已有任务恢复机制（S3-c）：进程级任务注册表托管执行（断线≠取消）、`rag_task_inputs` 临时寄存执行输入（终态即清、24h 兜底）、`rag_task_jobs` harness 作业账本、`GET /api/tasks/*` 查询/续传/取消、重启孤儿重驱动。S4-d 补：属主副本每 10s 拨新在跑行 `updated_at`（`heartbeat_loop`）+ `claim_recovery` 原子认领闸——多副本共享库时防跨副本双执行（实测：在跑任务不被抢、属主死亡后他副本单份接管）。S4 起产品路径是唯一问答路径（普通 agent/legacy 管线已删）；离线 judge 与 PG-only 均已落地——`RAG_ONLINE_JUDGE_ENABLED` 默认 false 的存留决策已定案（decisions §10），生产无 SQLite 回退。

## 项目概述

**DPDK Knowledge Assistant**：面向 DPDK 流量检测平台（父目录中的 `DPDK` 主项目仓库）的项目知识问答助手。它索引主项目的文档、代码和手工知识库（`knowledge/`），基于 RAG 检索回答项目结构、协议检测、插件接口和实现细节问题，并支持学习用插件模板生成与学生插件评测（agent 层，见下文）。

技术栈：

- **后端**：Python 3.11、FastAPI 0.115、uvicorn、pydantic 2.10、pydantic-settings；LLM/embedding 走 OpenAI-compatible HTTP 接口（httpx 连接池复用）；LlamaIndex（pgvector 节点存储与语义召回）+ 自有多路检索融合；LangGraph + langchain-openai + MCP client 构成 agent 编排层。
- **存储**：生产仅 PostgreSQL 17 + pgvector（`DATABASE_BACKEND=postgres`）；SQLite 只作单元测试与本地开发后端（S4 起不再是生产回退口径）。schema 版本化用 alembic。
- **前端**：React 19 + Vite 7（`frontend/`，单文件主界面 `frontend/src/main.jsx`），vitest 测试，marked + dompurify 渲染 Markdown。
- **部署**：Docker Compose（postgres / backend / harness / frontend-nginx 四服务），生产仅暴露 443（nginx 反代 `/api/`，HTTPS）。
- **CI**：Gitee Go 流水线即代码，`.workflow/MasterPipeline.yml`（push 到 main）与 `PRPipeline.yml`。

问答主链路：本地规则/LLM 改写 query → embedding → 多路召回（改写 FTS + 原问题 FTS + 路径/主题召回 + 全库 embedding 粗排）→ RRF 融合 → DPDK 代码信号综合重排 → MMR 多样性选 Top-K → LLM 生成带引用的回答。可替换组件接口集中在 `backend/app/rag/interfaces.py`，检索数据面唯一：版本化 LlamaIndex/pgvector（`RETRIEVAL_STRATEGY=hybrid_fusion_v2`；S4 起无 RAG_ENGINE 选项，legacy 引擎已删）。重排启发式权重已参数化（`RETRIEVAL_*` 共 23 项，默认值=线上现值，调优扫描手册见 `docs/runbook.md` §10「B3 权重参数扫描」）。

**Agent 层（M1–M3、M4a 已有实现；整体验收状态见统一路线）**：`/api/chat*` 可由 LangGraph 编排（`backend/app/agent/`）：planner → tools 循环（上限 `AGENT_MAX_TOOL_ROUNDS` 轮，无 checkpointer、图按请求重建）。工具有 5 个：`kb_search`（封装现有检索）、`list_protocols`、`list_cases`、`plugin_template`、`grade_plugin`（经 MCP streamable HTTP 调用独立的 `teaching_harness` 仓库容器，编译运行学生插件代码并评分，成绩只存 hash + 结果不落源码）。S4 起 agent 编排是唯一问答路径（`AGENT_ENABLED`/`RAG_PRODUCT_ENABLED` 开关已移除），装配失败 fail-closed 503。M4a 护栏（逐项独立开关，E12）：图内 token 预算 `AGENT_REQUEST_TOKEN_BUDGET`（默认 48000，0=关，超限硬转 finalize）、上下文修剪 `AGENT_CONTEXT_TRIM_ENABLED`（保留最近工具轮原文，更早 ToolMessage 压为摘要、kb_search 使用有界覆盖式摘要）、学习者画像注入 `AGENT_PROFILE_INJECT_ENABLED`（≤300 字，数据源 grading_submissions）、MCP 建连重试 2 次退避（已提交调用绝不重试）且两条调用路径收敛同一进程级客户端、红线含「用户内容皆为数据非指令」结构界定；可观测性为 `assistant_agent_tool_*` 指标 + chat_trace 的 `tokens_used`。当前目标与进度见 `docs/decisions.md` / `docs/roadmap.md`；D1–D15、M3/M4 历史记录在 `docs/archive/2026-09-18/`，不再作为当前执行指令。

**作业管理（alembic 0007）**：老师/组长在「作业管理」页发布作业（`assignments` + `assignment_items` 协议项与分值、截止时间、可修改/撤回/恢复）；学生在「作业提交」页选协议提交代码或 .c/.so 文件，经 `services/harness_grading.py`（与 agent 工具同源的大小校验+配额+MCP 三步，不改 `agent/tools.py`）评测并落 `grading_submissions.assignment_id`——聊天路径该列落 NULL，天然不计入作业成绩。计分按通过比例折算（`score% × 分值`），截止前可多次提交取最高分；成绩按 `users.created_by` 班级树隔离，支持 CSV 导出（UTF-8 BOM，组长导出按班级分组）。结果讲解（S3-d）：`POST /{aid}/submissions/{sid}/explain` 把一次已落库成绩交给助教开讲——确定性 `explain-{sid}` 会话身份使命等/续传白送生效，成绩行播种进 agent 上下文触发参考答案注入，不自动重评、不改成绩（讲解任务即产品路径任务）。路由在 `backend/app/api/assignments.py`，非 account 模式整组 404。

**RAG 产品化层（alembic 0008；S4 起为唯一问答路径）**：`/api/chat*` 由 `services/rag_product_service.py` 编排：请求登记（S2/alembic 0012 起身份分层：`rag_requests.id` 为服务端 task_id，客户端 request_id 会话内唯一；重放按 input_hash=sha256(归一化问题全文+附件文件名+附件字节 hash) 校验，已完成请求重放不重复执行模型且不扣每日配额，身份不匹配或历史 NULL hash 返回 409 fail-closed；`question` 列仍存脱敏文本）→ agent 图编排 → 证据判断（未解答/部分解答/澄清/系统错误归类）→ 正文落库后才展示；admission 队列单 worker 20 活动/20 等待/10 秒排队时限；受理阶段（`RAG_PREPARE_TIMEOUT_SEC`，默认 15s）与执行阶段（`RAG_REQUEST_TIMEOUT_SEC`）分别超时兜底。版本化索引（`services/rag_index_service.py`）：全源快照、正文/元数据/配置逐项比较（无 hash）、节点 id 为 `li_<hex32>`、先嵌入后 CAS 原子发布、旧节点只读保留；`scripts/rag_maintenance.py --rollback-release ID` 回滚前强制校验目标版本与当前 embedding 配置指纹匹配且物理节点完整。检索器按发布版本检索（`storage/li_retrieval_store.py` 在固定版本时 JOIN `rag_release_nodes`；S2 起会话级锁版本——`assistant_sessions.rag_release_id` 首次有可用发布时持久绑定，跨请求/重启不漂移，会话删除重建重新解析），固定版本时校验配置指纹，不匹配 fail-closed 并记 `rag_release_config_mismatch`；`/api/ready` 核对物理节点计数与配置指纹（失败 503，原因见 `ready_check_failed` 日志）。问题闭环与共享知识：`services/rag_issue_service.py` + `api/rag.py`（反馈、班级隔离、认领/合并/原题验证——验证落库为条件更新，与并发反馈不互丢、知识发布与修订乐观锁，未解答问题脱敏存 180 天）；前端工作台 `frontend/src/RagWorkspace.jsx`（仅 admin/leader 可见）。业务表 DDL 在 `storage/rag_schema.sql`（0008 双库同构，downgrade 有意 no-op 保留数据；LI 向量数据面仍 PG-only）。当前状态见 `docs/roadmap.md`，历史交接、进度和实验见 `docs/archive/2026-09-18/`。证据校验（S3-e/N08）：默认在线只跑确定性检查（空答案/引用越界/无证据/缺引用），`RAG_ONLINE_JUDGE_ENABLED=true` 恢复逐请求模型核验；显式注入 judge 模型不受开关约束（`api/rag.py` 原题验证不受影响）；离线验收走 `scripts/rag_judge_eval.py` + `tests/rag_eval/judge_cases.yml`（需真实 LLM 凭据，不进默认 CI）。

## 目录结构

```
backend/            FastAPI 后端（Python 包根即 backend/，import 名为 app）
  app/
    main.py         FastAPI 入口：CORS、安全头中间件、/metrics、/api/health、/api/ready、启动/关闭钩子
    config.py       Settings（pydantic-settings，全部经 backend/.env 注入；路径自动发现）
    db.py           init_db / 连接获取 / DatabaseProvider 生命周期
    api/            路由：chat / auth / admin / sources / templates / usage / grading / assignments / rag / tasks
    core/           llm_client（连接池+重试）、async_cache、auth、rate_limit、metrics、tracing、logging_config
    rag/            RAG 抽象接口（interfaces.py）与 provider 装配（providers.py）
    services/       retrieval_service（li 数据面共用检索管线）、agent_service（GroundedAnswerGenerator 等共享件）、
                    llama_index_service、knowledge_sources（知识源加载/切块）、
                    session_service、conversation_memory、grading_service、user_service、template_service、
                    assignment_service（作业 CRUD/计分）、harness_grading（作业提交的评测封装）、
                    rag_index_service（版本化索引发布/回滚）、rag_product_service（问答编排）、
                    rag_issue_service（问题闭环/脱敏/留存）、citation_service（引用编号单一口径，S2）、
                    task_service（进程级任务注册表：订阅/取消/孤儿恢复/属主心跳防跨副本双执行，S3-c/S4-d）
    agent/          LangGraph agent 层：graph / tools / prompts / service / mcp_client / reference.py
    storage/        存储 provider：interfaces（方言声明）、postgres.py、sqlite.py、
                    postgres_schema.sql（幂等 DDL 基线）、rag_schema.sql（RAG 产品业务表，0008 同源）、
                    retrieval_store.py（RetrievalStore Protocol + 历史引用回读）、li_retrieval_store.py（唯一数据面，PG-only）
    schemas/        pydantic 请求/响应模型
  alembic/          版本化 migration（0001 基线 → 0015；0015 讲解任务身份标记，0014 聊天评测 job 身份，0013 任务恢复（rag_task_inputs/rag_task_jobs），0012 请求身份分层+会话 release 绑定，
                    0011 用量账本，0008 RAG 产品业务表、0009 li 表 HNSW 条件索引、
                    0010 li 表 zhparser FTS 条件换列，后两者默认 no-op，由 LLAMA_INDEX_HNSW_ENABLED /
                    LLAMA_INDEX_FTS_CONFIG 门控）
  data/             knowledge_sources.yml（知识源清单，include 只支持精确路径，无 glob）
  scripts/          CLI：ingest_llama_index.py（唯一摄取入口，版本化幂等）、rag_judge_eval.py（离线 judge 验收）、
                    create_user.py、purge_sessions.py、migrate_sqlite_to_postgres.py、
                    assign_anonymous_sessions.py、rag_maintenance.py（只读统计/留存清理/发布回滚）
  tests/            pytest 测试（tests/agent/ 为 agent 黄金场景集；tests/rag_eval/ 为检索评估基建）
  pyproject.toml    pytest / coverage / ruff 配置（仅此一个 toml，无打包配置）
frontend/           React + Vite 前端（src/main.jsx、src/RagWorkspace.jsx、src/api/client.js、src/styles/app.css）
deploy/             nginx.conf、.env.example、backup.sh、auto_ingest.sh、healthcheck.sh、
                    postgres/（自定义 PG 镜像：pgvector + zhparser，roadmap B2）、
                    certs/（自签名证书脚本）、systemd/ 单元模板、wsl2/ Docker 安装脚本
knowledge/          手工知识库：faq.md、lab_tasks.yml、protocol_notes.md（已入库）
docs/               当前架构、决策、统一路线、专项与操作手册；入口 README.md，历史在 archive/
docker-compose.yml  生产编排（postgres + backend + harness + frontend）
start_dev.sh / stop_dev.sh（.bat 同名）  本地一键起停前后端
```

## 常用命令

所有后端命令默认工作目录 `backend/`，使用虚拟环境 `.venv`（Linux 路径写法 `.venv/bin/python`；Windows 本地开发可用对应 Scripts 路径或 Git Bash）。

### 环境准备

```bash
cd backend
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt            # 运行依赖（版本全部 pin 死）
pip install -r requirements-dev.txt        # 开发/CI：pytest、pytest-cov、ruff、pip-audit
cp .env.example .env                        # 填 LLM_API_BASE/KEY、EMBEDDING_API_BASE/KEY 等
cd ../frontend && npm install
```

生产镜像用 `backend/requirements.lock`（锁定传递依赖，含 Linux-only 包如 uvloop，**不要在 Windows 上从 lock 安装**）。

### 运行

```bash
./start_dev.sh                              # 仓库根目录：一键起 uvicorn(8090) + vite(5174)，日志在 .logs/
cd backend && .venv/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8090
cd frontend && npm run dev
```

知识库索引（改动知识源后必须重建，幂等增量）：

```bash
cd backend && .venv/bin/python scripts/ingest_llama_index.py   # 唯一摄取入口（版本化索引）
```

首次初始化 PostgreSQL：`psql -d assistant -c "CREATE EXTENSION IF NOT EXISTS vector;"`，然后 `.venv/bin/python -c "from app.db import init_db; init_db()"`。

### 测试与静态检查

```bash
cd backend
.venv/bin/python -m pytest -m "not live and not postgres"      # CI 同款无网络子集
.venv/bin/python -m pytest -m "not live and not postgres" --cov=app --cov-fail-under=60
.venv/bin/python -m ruff check .
cd ../frontend && npm test                                     # vitest（覆盖 src/api/client.js）
```

- pytest 自定义 mark（`--strict-markers`）：`live`（需真实 LLM/embedding 或已 ingest 的知识库）、`postgres`（需运行中的 PostgreSQL + pgvector）。默认集必须全绿。
- 历史上的独立测试脚本（`tests/test_chat_e2e.py`、`tests/test_answer_quality.py`、`tests/load_chat_concurrency.py` 等）保留 CLI 形态，`python tests/test_x.py` 与 `python -m pytest tests/test_x.py` 等价；这些多带 `--debug-trace`、`--base-url`、`--case` 等参数，用途见 README「Test Guide」。
- 前端构建：`cd frontend && npm run build`（CI 会跑 vitest + build）。

### 数据库 migration

schema 基线是 `backend/app/storage/postgres_schema.sql` 的幂等 DDL（`init_db()` 应用）。**任何 schema 变更必须新增 alembic revision**（`alembic revision -m "..."` → `alembic/versions/`），且 `postgres_schema.sql` 与 `sqlite.py` 中的 DDL 要同步更新。**全新库的正确顺序是 `init_db()` → `alembic upgrade head`**（迁移全部幂等，会补齐 users/api_tokens/grading_submissions 等只由迁移建的表）；`init_db() + alembic stamp head` 只适用于已有全量表的存量库——在全新库上 stamp head 会静默漏建迁移侧表，登录即 404（S4-c 演练实证）。

## 代码风格约定

- **Python 3.11**，ruff 检查：启用 `E/W/F/I/B`（bugbear 的 `extend-immutable-calls` 含 FastAPI 的 `Depends/Query/Path`），`E501`、`E402` 显式豁免；`line-length = 120`；import 排序 isort 规则、`app` 为 first-party。存量 UP（pyupgrade）问题约 390 处是有意留待后续处理，**不要顺手做全仓现代化改写**。
- 后端包根是 `backend/`（`sys.path` 含它），代码中 import 写 `from app.xxx import ...`，不是 `backend.app.xxx`。
- 异步优先：FastAPI 路由与 service 层用 async；LLM/embedding 调用复用 `httpx.AsyncClient` 连接池（`core/llm_client.py`）；相同 key 的并发请求要合并（`core/async_cache.py`），避免重复打上游模型。
- **存储层 dialect 无关**：业务代码不得直接判断 `DATABASE_BACKEND`。SQL 方言差异通过 `DatabaseProvider` 的属性（`placeholder` / `supports_manual_fts` / `embedding_column` / `metadata_cast` / `embedding_cast`）参数化；检索候选读取走 `storage/li_retrieval_store.py`（PG-only——检索向量数据面不支持 SQLite，故 sqlite 后端只剩测试语义）；历史消息的非 `li_` 引用回读走 `retrieval_store.py` 的 `get_historical_chunk`（`knowledge_chunks` 表保留）。新增业务表/查询时 SQLite 与 PostgreSQL 两个实现都要改。
- 配置一律进 `config.py` 的 `Settings`（环境变量大写蛇形），新增配置要同步 `backend/.env.example`（和部署时 `deploy/.env.example`）。
- 结构化日志统一 `logging.getLogger("assistant")`，单行 JSON（`core/logging_config.py`）；结构化事件（`chat_trace`、`llm_retry`、`auth_failed`、`rate_limit_hit`）以顶层字段输出。
- 外部接口契约（`/api/chat*` 的 JSON 与 SSE 事件序列）是稳定契约：`/api/chat/stream` 与 `/api/tasks/{id}/events` 帧同形（契约明细见 `docs/architecture_current.md` §3）；新增 SSE 事件类型时前端解析要对未知事件保持宽松（见 `frontend/src/api/client.js`）。
- fallback 哲学：检索/生成/工具调用的任何异常都不能让接口 500——流式路径退回 fallback 文本，非流式返回低置信度草稿。

## 测试策略

- 默认门禁：`pytest -m "not live and not postgres"` 全绿 + `--cov-fail-under=60` + `ruff check .` + 前端 `npm test`。
- `tests/conftest.py` 提供环境变量/设置缓存/数据库 provider 的隔离 fixture；新测试应复用，不要手工 mutate `os.environ` 后不复位。autouse fixture 对无 `live`/`postgres` mark 的用例强制临时 SQLite + 死上游地址，与 `backend/.env` 是否含真实配置无关——默认集在本机可直接裸跑；要真实 PG/模型必须打对应 mark（`tests/test_conftest_isolation.py` 是这条契约的金丝雀）。postgres mark 文件一律要求显式 `RAG_TEST_DATABASE_URL` 专用测试库，未设置即 skip（S4-b/W6 已对齐，不吃 ambient 配置）。
- 网络调用一律 mock（构造真实的 Service，只 mock 网络层）；需要真实链路的用例打 `live` mark，需要 PG 的打 `postgres` mark。
- agent 层行为回归用 `tests/agent/test_golden_scenarios.py` 黄金场景集（路由、入口解析、反馈纪律、护栏），改 agent 提示词/工具后必须跑。
- live 评测集 `tests/agent/live_eval.yml` + `live_eval_runner.py`（真实模型/真实接口，live mark 不进默认集）：模型升级或提示词大改后手工跑分、与本地基线 `backend/data/live_eval_baseline.json`（gitignore）对比漂移，流程见 `docs/runbook.md` 第 9 节。
- 检索质量回归：题库 `tests/rag_eval/queries.yml` 是唯一事实来源——`tests/test_retrieval.py` 从题库 parametrize 生成硬断言用例（live，需已发布 li 版本的 PG 库）；`tests/rag_eval/rag_eval_runner.py`（CLI/pytest 双形态，live mark）计算 recall@1/5/15、MRR、must_top 命中率并与本地基线 `backend/data/rag_eval_baseline.json`（gitignore）对比漂移，改检索逻辑/换 embedding 模型/调权重前后必跑，流程见 `docs/runbook.md` 第 10 节；`tests/test_answer_quality.py`（deterministic rubric + `--show-retrieval` 区分召回/生成问题）。

## 安全注意事项

- **凭据只进 `.env`**（`backend/.env` 开发、`deploy/.env` 生产，均已 gitignore）；绝不提交真实 API key、token、密码。
- `AUTH_MODE` 三档：`local`（默认，无认证，**仅限本机回环**）、`token`（静态 Bearer token，`API_TOKENS_JSON`）、`account`（生产推荐，学号+密码登录，库中只存哈希，token 默认 7 天过期）。生产必须 `account` 或 `token`。
- 账号角色为 `{viewer, operator, admin, leader}`，用户管理是**组长（leader）→ 老师（admin）→ 学生（viewer）树状结构**（`users.created_by` 归属列，alembic 0005）：组长管全部、老师只管自己创建的学生；`leader` 只能由 `scripts/create_user.py` CLI 授予（API 防提权）；任何人不能停用自己/改自己角色；静态应急 token 不受树约束。
- 删除相关（alembic 0006）：用户可删自己的会话（`DELETE /api/chat/sessions/{id}`，他人/不存在统一 404；成绩行保留、`session_id` 置 NULL 脱钩）；管理员可批量删除下层账号（`POST /api/admin/users/delete`，**每次必须附自己的密码**，静态应急 token 因不在 users 表天然不可用），删除连带清理 token/会话/配额/改密申请、被删老师的学生 `created_by` 置 NULL 归入未分配组，且每次删除写一行 `admin_audit_log`（`GET /api/admin/audit-log`，组长看全部、老师只看自己）；组长可批量分配未分配学生（`POST /api/admin/users/assign`，目标必须是启用中的老师/组长）。`grading_submissions.user_id` 自 0006 起**无外键**（成绩与账号脱钩）。
- 作业管理权限（alembic 0007）：作业的 `scope` 由服务端按发布者角色写入（leader→`all` 全体可见、admin→`class` 仅自己名下学生可见），客户端不可指定；修改/撤回权限=创建者本人或 leader；operator 只读成绩与导出，不能发布/修改/撤回；成绩与导出复用树隔离（老师只看自己班），CSV 导出时学生班级归属实时查 `users.created_by`（账号已删归「未分配」，学号保留）。作业提交与聊天评测共用 `kind="grading"` 每日配额与豁免角色（`GRADING_QUOTA_EXEMPT_ROLES`，agent/tools.py）。
- 登录防爆破（每 IP 每分钟 10 次、学号 15 分钟 5 连败锁定）与限流（`RATE_LIMIT_CHAT_PER_MINUTE`、每日配额 `usage_counters`）是进程内存/DB 实现，修改时保持「对存在/不存在的学号一视同仁」的语义，不泄露账号存在性。
- `/metrics` 在应用层**无认证**，靠网络拓扑隔离：nginx 只反代 `/api/`，**不要把 `/metrics` 加进反代配置**。`/api/internal/*` 维护端点走 `MAINTENANCE_API_KEY`（`X-Maintenance-Key`），不进 OpenAPI/前端。
- 应用级安全头（CSP、nosniff、DENY 等）由后端中间件设置，nginx 不要重复加。
- 前端渲染 Markdown 必须经 dompurify 消毒（`main.jsx`）。
- 学生代码评测在独立 `harness` 容器中执行：`harness_internal` 网络 `internal: true` **无外网**、read_only 根文件系统 + tmpfs、mem/pids 限额、harness 侧还有 rlimit 护栏。backend 是唯一横跨 default 与 harness_internal 的服务——不要给其他服务接这个网，也不要给 harness 接外网。
- 评测成绩表只存代码 sha256 与结果 JSON，**不存源码全文**；上传附件以 `[上传附件：文件名]` 标记替代正文，粘贴源码经统一 `redact` 口径（含 `~~~` 围栏，S3-b）。任务存活期的执行源寄存 `rag_task_inputs`（受理时写入、终态即清、24h 兜底，S3-c/N07 已交付）；不能把源码写入成绩、长期会话或日志。
- CI 有 pip-audit 依赖扫描（告警不阻塞）；升级依赖注意 `requirements.txt` 中被既有 pin 压住的版本约束（`openai<2`、`pydantic==2.10.4` 卡住 `langchain-openai` 与 `mcp` 的版本上限）。

## 部署流程

生产一条命令：`docker compose build && docker compose up -d`（根目录 `docker-compose.yml`，配置在 `deploy/.env`，模板 `deploy/.env.example`）。要点：

- 前端镜像 = nginx 托管静态构建产物 + `deploy/nginx.conf` 反代 `/api/`（SSE 必须 `proxy_buffering off`，已配）；对外仅 443/80→443。证书放 `deploy/certs/server.crt|key`（演练用 `gen_self_signed_cert.sh` 自签）。
- DPDK 主仓库以只读卷挂进 backend 容器（`DPDK_REPO_HOST_PATH`，默认 `..`）；知识源缺失时索引任务会直接失败报错（不会静默漏索引）。
- 升级：`docker compose build backend && docker compose up -d backend`；schema 是幂等 DDL + alembic revision，回滚即 checkout 旧版本重建，数据无需回退。
- 索引自动化：`deploy/auto_ingest.sh`（cron，建议每 5 分钟）每次触发即扫描全部知识源，幂等增量由版本化 ingest 自身保证（正文/元数据/配置逐项比较，不再依赖 `git rev-parse HEAD`）；mkdir 锁防并发，失败告警到 `ALERT_WEBHOOK_URL`。
- 运维脚本：`deploy/backup.sh`（每日 pg_dump，保留 7 份）、`deploy/healthcheck.sh`（连续失败告警到 `ALERT_WEBHOOK_URL`）、会话清理 `scripts/purge_sessions.py`（默认 dry-run，`--apply` 实删，`SESSION_RETENTION_DAYS` 默认 30 天）。
- 非容器备选：`deploy/systemd/assistant-backend.service` 单元模板。WSL2/Windows 部署细节见 `docs/deployment_windows_wsl2.md`，运维手册见 `docs/runbook.md`。

## 关键文件速查

- RAG 接口与装配：`backend/app/rag/interfaces.py`、`backend/app/rag/providers.py`
- 检索核心：`backend/app/services/retrieval_service.py`（管线）、`backend/app/storage/li_retrieval_store.py`（唯一数据面，PG-only）、`backend/app/storage/retrieval_store.py`（Protocol + 历史引用回读）
- RAG 产品化：`backend/app/services/rag_product_service.py`、`rag_index_service.py`、`rag_issue_service.py`、`backend/app/api/rag.py`、`backend/app/storage/rag_schema.sql`、`backend/scripts/rag_maintenance.py`、`frontend/src/RagWorkspace.jsx`
- Agent 编排：`backend/app/agent/graph.py`、`service.py`、`tools.py`、`prompts.py`
- LLM 客户端/缓存：`backend/app/core/llm_client.py`、`async_cache.py`
- 存储基线：`backend/app/storage/postgres_schema.sql`、`backend/app/db.py`
- 知识源清单：`backend/data/knowledge_sources.yml`
- 作业管理：`backend/app/api/assignments.py`、`backend/app/services/assignment_service.py`、`backend/app/services/harness_grading.py`
- 当前文档：`docs/README.md`（导航）、`docs/architecture_current.md`（事实）、`docs/decisions.md`（决策）、`docs/roadmap.md`（阶段状态与首批执行单）
