> 历史归档：2026-09-18。原路径：`docs/dev_notes.md`。
> 适用版本：正文各条记录注明的日期、提交与环境；归档所据助手仓库快照为 `7048d8c`，不代表整篇已在该版本复验。
> **不再作为当前实施指令。** 正文保留当时的决策、完成声明和测试数据；未注明版本或存在冲突的结论不能外推为当前验收结果。
> 当前入口：[文档导航](../../README.md) · [统一路线](../../roadmap.md) · [当前决策](../../decisions.md)。
> 正文中的纯文本旧路径与行号用于还原历史上下文；本次仅修正可点击的相对链接。

# Development Notes

## Boundaries

- 不修改现有 DPDK 项目运行模块行为
- 学生插件代码可上传/粘贴并评测（M1/M2 已放开，旧红线废止，见 `docs/agent_upgrade_plan.md` D8）：
  评测只在隔离的 harness 容器中编译运行（无外网、非 root、read_only + tmpfs、cgroup/rlimit 限额）；
  成绩落库只存代码 sha256 与结果 JSON，不存源码全文；
  用户消息落库时上传附件只留 `[上传附件：文件名]` 标记
- API Key 只放在 `.env` 或环境变量里

## Current Backend Shape

- 服务：`FastAPI` + LangGraph agent 层（planner → tools 循环，五工具含 `kb_search` / `grade_plugin`，`AGENT_ENABLED=false` 回退旧单轮 RAG 管线）
- 数据库：`PostgreSQL + pgvector`（默认）；SQLite 仅作显式本地回退
- 检索：LlamaIndex 节点存储 + 多路召回（FTS / 路径 / embedding）→ RRF 融合 → DPDK 代码信号重排 → MMR 选 Top-K
- 评测：独立的 `teaching_harness` 容器（MCP streamable HTTP，仅 compose 内网可达），编译运行学生插件并评分
- 模型客户端：长生命周期 `httpx.AsyncClient`
- 性能优化：连接池复用 + 本地缓存 + 并发同 key 合并

## Useful Commands

### Rebuild knowledge index

默认引擎（`RAG_ENGINE=llama_index`，幂等增量）：

```bash
cd ai_chat_assistant/backend
source .venv/bin/activate
python scripts/ingest_llama_index.py
```

legacy 数据面仅在 `RAG_ENGINE=legacy` 回滚时使用：`python scripts/ingest_repo.py`。

### Start backend

```bash
cd ai_chat_assistant/backend
source .venv/bin/activate
python -m uvicorn app.main:app --host 127.0.0.1 --port 8090
```

### Start frontend

```bash
cd ai_chat_assistant/frontend
npm run dev
```

## Test Commands

测试已全部迁移为 pytest（`backend/pyproject.toml` 注册了两个 mark：`live` = 需要真实
LLM/embedding 或已 ingest 的知识库，`postgres` = 需要运行中的 PostgreSQL）。
先安装开发依赖：`pip install -r requirements-dev.txt`。

```bash
# CI 同款：无网络子集（默认排除 live / postgres）
cd ai_chat_assistant/backend
.venv/bin/python -m pytest -m "not live and not postgres"

# 单个文件 / 单个用例
.venv/bin/python -m pytest tests/test_retrieval.py -m live
.venv/bin/python -m pytest tests/test_auth.py -q

# 覆盖率（门禁 60%，CI 中强制执行）
.venv/bin/python -m pytest -m "not live and not postgres" --cov=app --cov-report=term-missing --cov-fail-under=60
```

静态检查与依赖漏洞扫描：

```bash
cd ai_chat_assistant/backend
.venv/bin/python -m ruff check .
# pip-audit 需在 Linux/macOS 上跑（lock 内 uvloop 不支持 Windows）；CI 中自动执行
.venv/bin/python -m pip_audit -r requirements.lock
```

老习惯 `python tests/test_x.py` 仍可用（文件末尾走 `pytest.main`）；
四个运维工具脚本（`test_chat_e2e` / `test_answer_quality` / `load_chat_concurrency` /
`test_postgres_runtime`）的 argparse CLI 原样保留。

### Retrieval regression（需要已 ingest 的知识库，标记为 live）

```bash
cd ai_chat_assistant/backend
.venv/bin/python -m pytest tests/test_retrieval.py        # 等价于旧的直接运行
```

### Provider lifecycle regression

无需 PostgreSQL 服务；验证并发冷启动时只创建一个数据库 provider：

```bash
cd ai_chat_assistant/backend
.venv/bin/python tests/test_provider_lifecycle.py
```

### PostgreSQL + pgvector runtime check

验证当前库的 schema、向量维度、FTS、路径召回和语义召回：

```bash
cd ai_chat_assistant/backend
.venv/bin/python tests/test_postgres_runtime.py
.venv/bin/python tests/test_postgres_runtime.py --query engine_ftp
```

### End-to-end chat smoke test

```bash
cd ai_chat_assistant/backend
.venv/bin/python tests/test_chat_e2e.py
.venv/bin/python tests/test_chat_e2e.py --limit 1
.venv/bin/python tests/test_chat_e2e.py --question "engine_ops_t 是什么？"
```

### Multi-turn conversation memory

```bash
cd ai_chat_assistant/backend
.venv/bin/python tests/test_conversation_memory.py
```

The active page keeps one `session_id`; the backend reads its stored history,
keeps the newest six exchanges verbatim, and summarizes older exchanges. A
follow-up such as “它如何工作？” is resolved with that memory before a fresh
knowledge-base retrieval.

### LlamaIndex index and SSE streaming

```bash
cd ai_chat_assistant/backend
.venv/bin/python scripts/ingest_llama_index.py
.venv/bin/python tests/test_streaming.py
```

`/api/chat/stream` is an SSE endpoint. It sends retrieval status and sources
before `delta` events. The validated default is `RAG_ENGINE=llama_index`; set
`RAG_ENGINE=legacy` only for an explicit rollback while investigating a
production regression.

Index rebuild is intentionally CLI-only; no public FastAPI route can trigger
embedding or mutate either knowledge index. The LlamaIndex rebuild command is
idempotent and reports inserted/updated, unchanged, deleted, and deduplicated
node counts.

### Concurrency/load test

```bash
cd ai_chat_assistant/backend
.venv/bin/python tests/load_chat_concurrency.py --levels 1,10,30
.venv/bin/python tests/load_chat_concurrency.py --levels 50 --requests-per-level 50
```

热缓存（重复问题）与冷缓存（每个问题唯一）应分别测试：

```bash
# 热缓存：验证答案缓存和同请求合并
.venv/bin/python tests/load_chat_concurrency.py --levels 1,10,30 --requests-per-level 30 --debug-trace

# 冷缓存：验证真实检索 + 模型生成的并发能力
.venv/bin/python tests/load_chat_concurrency.py --levels 10 --requests-per-level 10 --cold-cache --debug-trace
```

## Operational Notes

- 默认 ASGI 测试模式不依赖本地端口绑定
- 如果运行环境禁止外网，`test_chat_e2e.py` 和 `load_chat_concurrency.py` 会明确显示模型连接失败
- 当前高并发瓶颈仍主要来自外部模型响应时间，不是前端

## Recent Measured Results

- `50 并发 / 50 请求`，优化后：
  - `50/50` 成功
  - `fallback = 0`
  - 平均约 `13.5s`
  - P95 约 `17.1s`

## Next Recommended Work

- M6（账号体系）已完成；agent 助教 M1/M2 已完成，M3 进行中（见 `docs/agent_upgrade_plan.md`）
- Gitee Go 开通后验证首次流水线；覆盖率从 60% 底线逐步提升
- 在有 Docker 的主机上演练 pg_restore 全量恢复（M4 遗留，见 `docs/runbook.md` 第 4 节）
- 维护 PostgreSQL schema 与检索质量
- 增加多轮会话压测
