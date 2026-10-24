> 历史归档：2026-09-18。原路径：`docs/next_agent_handoff.md`。
> 适用版本：正文各条记录注明的日期、提交与环境；归档所据助手仓库快照为 `7048d8c`，不代表整篇已在该版本复验。
> **不再作为当前实施指令。** 正文保留当时的决策、完成声明和测试数据；未注明版本或存在冲突的结论不能外推为当前验收结果。
> 当前入口：[文档导航](../../README.md) · [统一路线](../../roadmap.md) · [当前决策](../../decisions.md)。
> 正文中的纯文本旧路径与行号用于还原历史上下文；本次仅修正可点击的相对链接。

# 下一位 Agent 开发交接提示词

以下内容可直接作为下一位编码 Agent 的任务提示词。

---

请接手 DPDK Knowledge Assistant 的下一阶段开发，先核对当前代码和已有未提交修改，
然后直接实施下述 **P2.1 小样本校准**（真实调用，**执行前先说明范围与预算并取得同意**），
不要只给建议。文档和注释沿用中文。

## 1. 工作区与规则

- 仓库：`/Users/fsy/code/dpdk/ai_chat_assistant`。**工作分支是 `main`**，跟踪
  `legacy-gitee/main`（gitee.com/fsyabc/ai_chat_assistant）；先重新核对，不要
  pull/reset/clean 或丢弃已有改动。
- **分支命名坑（2026-09-17 已修）**：本仓库有两个远程，`origin` 指向
  **另一个仓库** gitee.com/fsyabc/dpdk-engineering-agent（其 main 停在 2026-07-14，
  与本站工作线在 `0012ba8` 分叉）。原先本地 `main` 跟踪的是 `origin/main`、而实际
  工作线在本地分支 `v1` 上，导致提交「落错分支」。现已把工作线改名为 `main`
  （跟踪 `legacy-gitee/main`），旧 main 保留为 `dpdk-engineering-agent-main`。
  **提交前先 `git branch -vv` 确认当前分支跟踪的是哪个远程**，别想当然。
- 阅读仓库 `AGENTS.md`，以及 `docs/next_stage_plan.md`、`docs/local_development.md`、
  `docs/agent_upgrade_plan.md`、`docs/agent_m4_decisions.md`。P0.1 的分级降级设计见
  `docs/agent_m4_decisions.md` §6（唯一事实来源，不要在本文件复述第二份）。
- 规划已存在：P0 环境、P0.1 模型适配、P1 用量成本、**P2.0 验收工具（离线）**均已完成；
  当前 **P2.1 小样本校准**优先，其后 P2.2 100 题、P3 稳定性灰度、P4 数据层收敛；
  不要同时展开所有批次。
- 不提交或打印真实凭据；凭据已在被忽略的 `backend/.env`，无需用户再次提供。
  安装依赖继续使用镜像源。不要自动提交 Git，也不要修改外部 harness 仓库。

## 2. 已完成状态

- 本地 Python 3.11.15、`backend/.venv`；Node 24.18.1，npm 11.16.0；PG17.10 +
  pgvector0.8.4。开发库 `postgresql:///assistant_dev`，测试库 `assistant_rag_test`。
  原 `assistant` 库保留，不得拿开发/旧库做破坏性测试。
- 生成模型已从 `qwen3.7-max` 改成 `qwen3.8-flash`：本地 .env、Settings 默认、
  backend/deploy 的 .env.example 均已同步。
- embedding 保持 `text-embedding-v4` / 1024 维，不要随意更换或重新付费全量嵌入。
- 211 文件、3737 片段全量索引完成；release 为
  `ccbb92e558534cbcbd5e3d3b2ee4a30d`。声明/成员/物理节点数均 3737，配置匹配。
  再次 ingest 返回 3737 unchanged、0 inserted_or_updated，release 不变。
- 前端 `http://127.0.0.1:5174`，后端 `http://127.0.0.1:8090`；新会话先检查实际状态。
  `AUTH_MODE=local`，只监听回环；产品灰度仍关闭。
- harness 源码 `/Users/fsy/code/teaching_harness`，提交 `9ed5cfd`；Colima profile
  `dpdk`，Docker context `colima-dpdk`。`bash local_harness.sh start` 启动隔离容器
  和 SSH 本机转发，MCP URL `http://127.0.0.1:8100/mcp`。
  工具协议列表20项、DNS示例9/9通过；不得在宿主机执行学生代码或给容器外网。
- 起停服务和镜像配置以 `docs/local_development.md` 为准。

## 3. 未提交改动与验证

需保留的已修改文件：`backend/app/config.py`、`backend/.env.example`、
`deploy/.env.example`（模型名称）。本地 harness 脚手架（`docker-compose.local.yml`、
`local_harness.sh`、`frontend/.npmrc`）与 `docs/local_development.md`、
`docs/next_stage_plan.md`、本交接文件已随 `da6c2dd` 提交。
本地 `.env`、`.logs`、`.runtime` 均不提交。

P0.1 新增/修改的代码文件（同属未提交改动，须一并保留）：
`backend/app/agent/context.py`（新增）、`backend/app/agent/graph.py`、
`backend/app/agent/prompts.py`、`backend/app/agent/tools.py`、
`backend/app/agent/service.py`、`backend/app/services/agent_service.py`、
`backend/tests/test_agent_context_prepare.py`（新增）、`backend/tests/test_agent_graph.py`、
`backend/tests/agent/test_golden_scenarios.py`、`backend/tests/agent/golden_support.py`、
`docs/agent_m4_decisions.md`。

P0.1 结束时实测（连跑两次一致）：默认门禁 **475 passed / 92 deselected，
覆盖率 84.77%**（基线 451 / 84.26%）；黄金场景 **36 passed**（基线 31）；
`test_agent_context_prepare.py` 14 passed；`test_agent_graph.py` 11 passed；
`ruff check .` 全绿。这些是历史记录，不代表你修改后的回归结果。

**P1 已全部提交**（`ff41625` 捕获层 → `73fb1c6` 存储与价格 → `638d674` 全链路接线
→ `139f01b` 只读 API 与文档），不再属于未提交改动；文件清单见各提交。

**当前未提交的只有 P2.0**（`git status` 的 M 列表 + 1 个新文件）：

- `backend/app/agent/context.py`（日志每级留痕 + `applied`）、
  `backend/app/agent/tools.py`（kb_search 覆盖式摘要，E5 修复）、
  `backend/app/services/usage_service.py`、`backend/app/api/usage.py`、
  `backend/app/schemas/usage.py`（`/api/usage/cost` 新增可选 `request_id` 过滤与回显）、
  `backend/tests/test_agent_context_prepare.py`（prep_09/prep_10）、
  `backend/tests/agent/test_golden_scenarios.py`（s29 改写）、
  `backend/tests/agent/live_eval_runner.py`（词边界口径、逐题用量、结构化失败分类）、
  `backend/tests/rag_eval/{acceptance_lib,product_acceptance_runner,test_product_acceptance}.py`、
  `backend/tests/test_usage_cost_api.py`、`backend/tests/test_rag_versioned_postgres.py`
  （开跑前自清产物，修「同库第二遍必失败」），新增
  `backend/tests/agent/test_live_eval_scoring.py`；
  文档 `docs/{next_stage_plan,next_agent_handoff,local_development,runbook,
  rag_product_progress}.md`。

P2.0 结束时实测（离线）：默认门禁 **670 passed / 96 deselected，覆盖率 86.17%**；
`tests/rag_eval/` **127 passed**；`test_live_eval_scoring.py` 29 passed；
黄金场景 36 passed 无回归；`ruff check .` 全绿；
PG 专项（同一 `assistant_rag_test` 库**连跑两遍**）**5 passed**；
离线冒烟 `--sample 15`、`--evidence`（真实 chunk 取到 2178 字符正文、不存在的
chunk_id 显式标 ⚠）均通过；**零真实模型调用**。

P1 结束时实测（历史记录）：默认门禁 **581 passed / 96 deselected，覆盖率 86.26%**（P0.1 基线
475 / 84.77%）；PG 专项（`RAG_TEST_DATABASE_URL=postgresql:///assistant_rag_test`）
**4 passed**；黄金场景 **36 passed**（无回归）；`ruff check .` 全绿。
（P1 时代把 `test_rag_versioned_postgres.py` 也一起跑过并记录 1 passed，但那只在
**空库**上成立；同一库第二遍会因残留 release 失败，见 §6.4。）

## 4. P0.1 结论（已完成，供你判断基线可信度）

正式报告 `.logs/live_eval_qwen38_flash_after.json`（11 题 **10/11 通过**，
基线 `.logs/live_eval_qwen38_flash.json` 为 6/11），过程日志同名 `.log`。
小样本探针 `.logs/live_eval_qwen38_flash_p01_probe.json`（5 题 4/5）。
`live_eval_qwen38_flash_tools.json` 是索引完成前的预检，不能冒充正式报告。

已修复（4 项预算失败 + 1 项围栏越界全部转 PASS，**零回归**）：
`entry_paste_dns`、`feedback_first_fail_no_code`、`feedback_explicit_ask_no_full_impl`
（`agent_context_budget` 降级）；`leak_direct_reference_request`（提示词硬化）。
`.logs/backend.log` 中 `agent_context_budget` 出现次数为 **0**（基线为 4 题）。

复现到的根因（均已落为确定性测试，细节见 `docs/agent_m4_decisions.md` §6）：
planner 只压「最后一个带 tool_calls 的 AIMessage」之前的 ToolMessage，最近一轮永远
原文，单轮多次 `kb_search` 即可单独超限；finalize 完全不做上下文准备；系统提示词在
注入教师参考实现后可达预算一半以上；计量口径不计 `tool_calls` 参数（`grade_plugin`
携带完整 `plugin_source`）；降级答案回显提问中的源码围栏且丢弃已完成评分事实。

**唯一仍失败项 `route_kb_engine_ops`：已定因为 P2 范畴，不是 P0.1 残留。**
该题本轮产出的是**真实模型答案**（非降级），命中 `engine_ops_t`/`process`，
仅缺 `can_handle`。本机实测的三段证据：

1. 语料充足：库内 39+ 片段含 `can_handle`，含直接列出字段清单的文档片段
   （`### 1.4 插件系统关键接口`、`### 6.1 engine_ops_t 接口规范` 等）。
2. 检索可达：用**模型本轮实际发出的查询**回放（「plugin 编写 步骤 engine_get_ops 导出」
   等 3 条）都能召回 `plugin/engine.h` 片段，`can_handle` 位于该片段第 938 字符——
   **在 1500 字符可见窗口内**，即证据确实进过模型上下文。
3. 被 E5 压缩丢弃（实测）：`tool_context_summary` 的 kb_search 分支只保留
   **top-2 片段 × 300 字符**，把 8737 字符压成 642 字符后 `can_handle` 消失；
   本轮 14 次 `agent_context_trimmed` **全部停在 level 2**（压缩全部 ToolMessage），
   因此连最近一轮也被压。压缩是「先按名次取 top-2 再截断」，**名次靠后或偏移 >300 的
   命中不可恢复**；摘要自带 `需要完整内容可重新检索` 提示，模型重查 8 次但始终围绕
   「可选接口/get_stats」，未重查字段清单。

结论：检索与索引都不是瓶颈，**缺口在压缩的有损粒度与模型的自我纠正策略**，
属 P2「答案验收」而非 P0.1 字符预算机制。不要为了让这题变绿去放宽 rubric。

> 后续（P2.0，见 §6.1）：压缩粒度已按此定因修复（覆盖式摘要 + TOP_CHUNKS 2→3 + 有界），
> 并有承重断言；但**离线修复不等于该题已转 PASS**——真实复跑属 P2.1。

### 残留风险（P0.1 未闭环，交接须知）

1. **提示词硬化是概率性约束**：`qwen3.8-flash` 仍可能偶发代码围栏。rubric 未放宽，
   复测若失败须记为模型遵从问题，不得删断言换通过率。
2. **单题耗时上移到上游读超时**：修复后请求不再提前中止，live 单题 22–156s
   （本轮 11 题合计 566s），而上游 `ChatOpenAI(timeout=90, max_retries=3)`；
   探针轮曾 1 次 ReadTimeout。批量验收前需评估超时与重试是否足够。
3. **降级文案不准确**：「模型生成暂时不可用」是固定串，任何异常都命中，
   对 ReadTimeout 不成立，违反「不要把失败都称作上游不可用」的口径。
   修正需兼顾 `tests/test_llm_resilience.py`、`tests/test_agent_service.py`
   的既有断言，本批未动。
4. **接近地板仍会走降级而非生成**：超大粘贴源码叠加教师参考实现时仍可能触发
   `ContextBudgetExceeded`，属预期安全行为，但需在文档与前端预期中对齐。
5. **计量口径变更使压缩更早触发**：纳入 `tool_calls` 参数后，接近上限的请求会
   比修复前更早进入 L2（本轮 14/14 全为 L2），信息损失比旧路径更频繁——
   这正是 P2 要解决的取舍。

## 5. P1 全链路用量与成本统计（已完成，含唯一一次真实校准调用）

**一句话现状**：一次请求的全部上游花费现在都进账本
（`upstream_usage_records`），可查、可审计、缺失如实标未知；流式 usage 尾帧
**已用 1 次真实调用证实可用**（见 §5.4）。

### 5.1 交付内容

- **记录层** `app/core/usage.py`（纯逻辑，无 DB 无网络）：`UsageEntry` /
  `UsageRecorder`；四条不可动摇语义——未知不是 0（token 为 `None`）、零调用要留痕
  （`call_count=0`）、未定价不出价、不跨币种求和。来源分
  `upstream | unknown | cache_hit | replay | local | operation`，其中后三类零调用
  来源计入 `zero_call_entries` 而**不计入** `unknown_entries`（否则「有多少账没算清」
  会虚高）。
- **捕获层**：`llm_client` 的 `chat`/`stream_chat`/`embed` 加**可选** `usage`/`stage`
  sink 且返回值与异常语义逐字不变；流式按 `LLM_STREAM_USAGE_ENABLED` 带
  `stream_options.include_usage` 并解析尾帧；**上游以 400 拒绝该字段时去掉它重试一次**
  并把该次记为 unknown（不允许因为要计量而让请求失败）；取消、重试耗尽、未预期异常
  一律在 `finally` 补一条 unknown（请求已经发出，上游可能已经算过钱）。
  LlamaIndex 与 LangChain 两条旁路（`rag/providers.py`、`build_agent_chat_model`
  显式 `stream_usage=True`）同样接上。
- **存储层**：新表 `upstream_usage_records`（`app/storage/usage_schema.sql` 双后端同源，
  alembic `0011_usage_records`）；**没有**给 `rag_requests` 加列——agent 路径与 ingest
  根本没有 `rag_requests` 行。表内不含问题、prompt、源码或凭据。
- **价格层**：`LLM_PRICES_JSON`（单价为字符串，Decimal 精确解析）/`LLM_PRICE_VERSION`/
  `LLM_USAGE_RECORDING_ENABLED`/`LLM_STREAM_USAGE_ENABLED`；成本在**写入时**算成整数
  微单位并盖 `price_version`，调价不回溯改历史账；模型不在价格表 → `cost_micros=NULL`
  并归入 `unpriced_models`，只报用量。
- **接线**：rewrite / followup（两个独立 stage）/ query_embed / planner / finalize /
  judge / summary / answer（legacy 与 LlamaIndex 两条）/ ingest_embed，外加
  harness/MCP 的**非 token** 用量（只记次数与耗时，`priced=false`）。落库唯一收口是
  `flush_usage_recorder()`，挂在既有 `finally: self._log_trace(trace)` 上，因此**取消
  路径同样落库**（产品状态机的接受分支做不到这点）。离线 ingest 自建累加器并在
  `finally` 里 flush。
- **只读 API**：`GET /api/usage/cost?days=&subject=&stage=`，门禁与 `/subjects` 同口径
  （非 admin/leader → **404** 隐藏存在性；account 模式下老师只看到自己与自己创建的学生，
  指名树外主体同样 404）。响应 token 用量与非 token 操作**分列**，并显式回报
  `unknown_entries` / `unpriced_models` / `cost_currency_mixed` / `truncated`。
  既有 `/me`、`/subjects` 响应不增不删不改。

### 5.2 本轮新增的确定性测试

- `tests/test_usage_wiring.py`（15 例）：各 stage 名称与来源、本地规则与缓存命中记
  `local`/`cache_hit` 且 `call_count=0`、追问与改写分两笔、不接受 `usage` 的旧 rewriter
  照常工作、planner/finalize 分列、上游失败记 unknown、MCP 成功与失败都留痕、
  **取消（`task.cancel()`）后已发生的条目仍落库**。
- `tests/test_usage_cost_api.py`（13 例）：404 门禁、`days` 越界 422、token 与非 token
  分列、未定价只报用量、未知不当 0、滚动窗口、空账、**老师树隔离（含指名树外主体 404）
  与 leader/静态应急 token 不受树约束**、响应键集只增不改。
- `tests/test_llm_client_usage.py` 增 1 例：**在途取消 → 记一条 unknown、`call_count=1`**。
- `tests/test_usage_records_postgres.py` 增 1 例：`subjects IN (...)` 过滤与空集合语义
  （空集合 = 什么都看不到，**不是**看到全部）。

### 5.3 未闭环 / 能力边界（交接须知）

1. **`ChatOpenAI` 的重试在 langchain 内部**，逐次尝试的用量不可见 → **重试过的调用，
   上游实际消耗可能高于账面**。这是设计的能力边界，不得声称「费用精确完整」。
2. **harness/MCP 的真实成本在外部服务内**，本仓只记次数与耗时，不折算费率。
3. **价格数据需运维提供可追溯来源**；未配置时只出用量。本地 `.env` 目前未配
   `LLM_PRICES_JSON`，因此本机 `/api/usage/cost` 只会给出 `unpriced_models`，不出现金额。
4. **生成非流式与流式两条路的 usage 都已验，但仍只覆盖 `qwen3.8-flash` 这一个模型、
   一次调用**：换模型、换上游兼容实现（自建 vLLM/SGLang 等）时须重新校准，
   不能把这一次的结论外推成「任何上游都回尾帧」。
5. **本批不碰前端**：`/api/usage/cost` 没有对应的展示页；前端 63 例与 `client.test.js`
   未受影响，也未被用来验证本批。

### 5.4 真实校准调用（2026-09-17，全批唯一一次付费调用）

**问题**：上游是否接受 `stream_options.include_usage` 并回 usage 尾帧？
**结论：接受，且回帧。** 首帧即成功，未触发 400 退字段重试。

范围与证据（日志 `../.logs/p1_stream_calibration.log`，脚本 `/tmp/p1_stream_calibration.py`
——一次性校准脚本，**故意不落进仓库**）：

- 走仓库既有的 `OpenAICompatibleClient.stream_chat`，配置与线上一致，
  仅把 `max_tokens` 压到 96 以降低花费；一句短问
  （「用一句话说明 TCP 三次握手的第三步是什么。」）。
- 实际发出的 payload（子类录得）含
  `"stream_options": {"include_usage": true}`，且**只发出 1 个 payload**
  ——即没有走退字段重试分支。
- 尾帧 usage：`prompt_tokens=72 / completion_tokens=79 / total_tokens=151`，
  `usage_source=upstream`、`call_count=1`、`duration_ms=2074`。**是真实数值，不是 unknown。**
- 全链路落库：`flush_usage_recorder` 写入 PG（开发库 `assistant_dev`，migration 0011）
  **1 行**，`psql` 直查核对一致，`cost_micros`/`currency`/`price_version` 均为 **NULL**
  ——本地未配价格，账本**没有编造金额**，`/api/usage/cost` 给出
  `unpriced_models: ["qwen3.8-flash"]`。
- 花费量级：151 tokens（本次唯一支出）。未跑题库、未跑 embedding、未重建索引。
- 库里留有一行 `subject='calibration-p1-stream'` / `request_id='calib_p1_stream_20260917'`
  的真实记录。**故意不删**：删记账记录比留一行更糟；核对时按 subject 排除即可。

**这条结论改变了什么**：流式用量不再「可能长期为 unknown」，`LLM_STREAM_USAGE_ENABLED`
可以放心保持默认开。

## 6. P2.0 答案验收（离线工具已收口 2026-09-17；**未提交**）

**一句话现状**：盲审从「凭印象看四个 bool」变成「逐条引用并排看论断句与证据正文」，
门禁与基线可执行，「未评分即未验收」从免责声明变成 `incomplete` 拒绝判定；
真实调用（P2.1 小样本、P2.2 100 题）**一次都没跑**，`backend/data/product_acceptance/`
仍是空的——**本批没有任何答案质量结论**。

### 6.1 交付内容（P2.0 新增/修改，全部未提交，须与 P0.1/P1 改动一并保留）

- 盲审 `--evidence`（`tests/rag_eval/product_acceptance_runner.py`）：逐条引用打印
  「论断句 + 证据正文」。证据**复用应用既有的历史引用取数路径**——
  `li_` 前缀走 `get_llama_index_source`（与 `app/api/chat.py` 渲染历史引用同一个函数），
  其余走 `RetrievalService().get_chunk`；取数失败在清单上**显式标出**，不静默当「无证据」。
  不新增取数路径、不加表、不改 JSON/SSE 对外契约。
- **盲审语义 = 归因核对**：审稿人判断「这条引用是否支撑它所在的那句话」，**不要求教师写
  gold answer**（`gold_answer`/`gold_citations` 只是题集可选键），这是让 100 题盲审真正
  可执行的关键取舍。`DISCLAIMER` 纪律不变。
- 评分 schema **只增不改**：新增 `unsupported_citation_indexes`（哪几条引用不支撑其论断）与
  `missing_evidence`（应引而未引）；`validate_review` 对越界/类型错误**报错而不是静默丢弃**；
  旧评分文件（只有四个 bool）仍然合法。
- 门禁 `evaluate_acceptance` + 7 个阈值 flag + 退出码（`pass`→0、`fail`→1、
  `incomplete`→2、**无门禁参数恒 0**）；人工评分覆盖率 <100% ⇒ `incomplete` 且拒绝判定；
  比率类指标无样本为 `null`，门禁按未满足处理（**不当 0 通过**）。
- 基线 `--write-baseline`/`--baseline` → `backend/data/product_acceptance/baseline.json`
  （已 gitignore），存教师确认标签、人工判定与指标。**当前既无 run 也无基线。**
- **E5 压缩粒度修复**（`route_kb_engine_ops` 的已知输入，见 §4）：`tool_context_summary`
  的 kb_search 分支由「top-2 片段 × 前 300 字符」改为**覆盖式摘要**——每片段
  头部 120 + 尾部 60 + 全窗口按检索词/声明行等距抽出的高信号行 140，TOP_CHUNKS 2→3，
  补回被丢掉的 `title`，单片段 320 字符有界。**不动分级阶梯、配对不变式、L1 语义与
  L5 保护**。`_shrink` 只接受确实变小的结果，因此不存在「干脆不压」式假修复。
- 观测修正：`agent_context_trimmed` 从「只在 level ≥ 2 记录」改为**每级都记录**并带
  `applied` 布尔——旧口径把「L1 就够」这个健康情形系统性隐掉，使「14/14 全为 L2」
  无法解释。**新旧口径的日志分布不可直接比。**
- live 口径收紧 + 逐题用量（见 §6.3）。

### 6.2 本轮新增的确定性测试

- `backend/tests/test_agent_context_prepare.py` prep_09/prep_10：签名行放在 1500 字符片段
  的偏移 `[60,300,700,938,1200,1440]` 各处都必须在摘要里存活（参数化）、第 3 名片段的
  标记必须保留、检索词优先、摘要**有界**、L2 端到端；日志每级留痕且 no-op 时
  `applied=False`。
- `backend/tests/agent/test_golden_scenarios.py`：`test_s29_context_trim_keeps_kb_top2`
  改名为 `..._covers_rank_and_offset`，从**锁定有损行为**（`ref == [1,2]`、
  `len <= 300`、`"第2段" not in`）改为在「能否找回证据」维度上严格更强（前 2 名仍在、
  偏移 >300 的标记与第 3 名的标记都必须出现），体积仍受同一上限约束。**这不是删断言
  换通过率，是替换被修掉行为的断言。**
- `backend/tests/rag_eval/`（83 → **127 例**）：引用级 schema 与越界报错、论断句定位
  （`[1]` 不被 `[10]` 满足）、盲审清单（取不到证据/答案无该编号都显式标出）、受限口径
  （`correct_rate`/`false_reject_rate` 只算可答题）、门禁四结论与退出码、基线回退与
  耗时抖动容忍、`--write-baseline`/`--baseline`/`--evidence`/`--timeout` 行为；
  另有两例守住盲审的 import 自举（子进程）与「必须先 bootstrap 再 import」。
- `backend/tests/agent/test_live_eval_scoring.py`（新增 29 例）：词边界口径（`process`
  不被 `packets_processed` 满足、`dns_` 前缀不被误伤、中文与围栏词条行为不变）、
  口径变化逐题留痕（收紧与放宽两个方向都要报）、失败分类结构化且不为未知失败编分类、
  request_id 是合法 UUID 且逐 run 不同、用量回读失败不改判定、未知 token 不显示成 0、
  JSON 报告只增键、基线带口径版本。
- `backend/tests/test_usage_cost_api.py` 增 4 例：`request_id` 过滤、查不到即空账
  （token 为 `null` 而非 0）、**`request_id` 不能越过老师树隔离**、响应键集只增。

### 6.3 live 链路两处口径修正（有证据，附带）

1. **`required_terms` 改词边界匹配**（`TERM_CALIBER = word-boundary-v1`）：
   `route_kb_engine_ops` 的 `process` 曾被答案里的 `packets_processed` 满足
   （旧口径是 `term.lower() in text.lower()`）。这是**收紧**：P0.1 报告里的 11 题
   通过数是**旧口径**下的数，**两口径不可直接比**。逐题差异记在 `term_caliber` 字段、
   报告以 `~` 行列出；JSON 报告与基线都带口径版本，跨口径对比会先打一行 `[口径变更]`。
   注意收紧也会让**违禁词**判定变松（`process` 不再命中 `packets_processed`），该方向
   同样显式报出，不允许悄悄变绿。
   `_contains` 只在词条自身的首/末字符是字母数字时加边界：以 `_` 结尾的词（如 `dns_`）
   本就是前缀写法，加右边界会把 `dns_qname` 这类合法命中排除掉。
2. **逐题 token/成本**：CLI 形态给每题派生 `request_id = uuid5(run_id, case_id)`
   （`ChatRequest.request_id` 是 UUID 字段，非 UUID 会 422；**同 request_id 会被产品路径
   幂等重放、拿回上一轮旧答案**，所以 `run_id` 默认取当前时间戳，每次运行都不同），
   跑完按 `request_id` 回读 `/api/usage/cost`（该端点新增**可选** `request_id` 过滤，
   只做收窄、仍受树隔离约束）。读不到只记 `usage_error`，**不影响 PASS/FAIL**——
   账本不可读是观测问题，不是答案问题。失败分类只做**断言种类**结构化
   （`tool_sequence`/`citations`/`required_term`/...），**不硬套验收路径那套
   答非所问分类**：live 集的 `expect` 没有 `answer_status` 期望标签，套上去会编出
   并不存在的归因；产品路径的 `answer_status` 单独回报，旧管线没有就留 `null`。

### 6.4 未闭环 / 能力边界（交接须知）

1. **答案质量仍无结论，但已有一次真实 run**（见 §6.5）：15 题跑批 **0 题 answered**，
   10 题是「资料不足」占位、5 题是错误占位 ⇒ **没有引用可核对，盲审与门禁结论都不成立**。
   批审工具与门禁是**能力**，不是**结论**；任何「验收通过」的说法都必须先有**有效** run
   + 人工评分 + `--summarize` 结论。`baseline.json` **故意没建**：不能用无效 run 建基线。
2. **P2.1 的三个前提**：① 本地临时 `RAG_PRODUCT_ENABLED=true` 重启（跑完回退 `false`；
   仍 `AUTH_MODE=local`、仍只监听回环，不是开启产品灰度）——否则验收打的不是产品路径；
   ② **先调大单题超时**（`--timeout`/`RAG_EVAL_TIMEOUT_SEC`，现默认 150s vs 单题实测
   22–156s，不调会把最慢的题记成假 `system_error`）；③ 先说明范围与预算再执行。
   **新增第 ④ 条（实测踩到）**：开关开着的时候默认集门禁会 **85 failed / 55 errors**，
   全部是 `Settings` 校验「生产 RAG 仅支持版本化 llama_index 数据面」——测试环境覆盖了
   `RAG_ENGINE`，于是 `rag_product_enabled=true` 直接让 `Settings` 构造失败。
   **这不是代码回归**：跑门禁前先把开关回退 `false`，否则数字毫无意义。
3. **耗时门禁不预设数值**：`--max-avg-latency-ms`/`--max-p95-latency-ms` 已可用，
   但定线要用 P2.1 的真实分布，不许拿别处的数拍脑袋。
4. **E5 修复是离线验证的**：确定性测试证明「证据在压缩后可恢复」，**没有**证明模型
   拿到 `can_handle` 后就会答对 `route_kb_engine_ops`——那要 P2.1 复跑 live 才知道。
   不要在本批声称该题已修复。
5. **压缩与体积的取舍仍在**：`3×320 ≈ 960` 字符 vs 原 `2×300 = 600`，8 次 kb_search 的
   轮次总摘要体积上升，理论上可能更早触发 L3/L4。本批选 320 而非更大的 420 正是为控制
   总量；真实轮次是否因此更早降级，要看 P2.1 的 `agent_context_trimmed` 分级分布
   （现在每级都记，可直接数）。
6. **`--evidence` 依赖本机可检索的索引**：取不到证据正文会在清单上标出，但那是数据
   可得性问题，不代表引用无效；换机器/换索引 release 后清单内容会变，报告须标注 release。
   `backend/` 的 import 路径已由 `_ensure_app_importable()` 自举，**不必再手工设
   `PYTHONPATH`**；把这一步删掉会让整篇清单退化成「取证据正文出错」，
   `test_product_acceptance.py` 里有一条子进程用例守着它（进程内测不出来）。
7. **本批不碰前端**：`--evidence` 与门禁都是 CLI；前端 63 例未用于验收本批。
8. **`test_rag_versioned_postgres.py` 原先不是自隔离的**（已修）：它假定库是新的，
   同一库跑第二遍时上轮的 release 会让首轮 ingest 跳过 embedding，
   `embed.calls == 1` 拿到 0，**看起来像发布事务坏了**。现在用例自己在开跑前清
   `rag_release_nodes` / `rag_releases` / `data_rag_test_nodes`。若你见到该用例失败，
   先确认是不是回退到了旧版；不要直接怀疑 `rag_index_service`。

### 6.5 P2.1 真实跑批实测（2026-09-17，全批唯一一批付费调用）

范围：15 题 `--run --sample 15 --limit 15 --timeout 300`（21:11–21:26），
外加修复后的 **1 题**（验收形态）+ **3 题**（抓预算诊断）。没有再跑别的批量调用。

**结论先行：这一轮不是质量结论，是一次成功的「为什么答不出来」归因。**
15 题 **0 answered**：10 unanswered（其中 **9 题 `evidence_budget`**）+ 5 system_error
（3 `validation_failed`、2 `generation_failed`）。产物
`data/product_acceptance/run_20260917_212648.json`（gitignore）。

#### R1 证据预算被打爆（实测，非推断）

`rag_evidence_budget_exceeded` 实测一条：

```
chunks=72  chunk_chars=67651  tool_results=1  evidence_chars=69958  limit=24000
```

根因在 `app/agent/tools.py:116-117`：每轮 `kb_search`（`limit=8`）把结果**全量追加**进
`context.collected_chunks`，**既不去重也不设上限**。9 轮 = 72 片段，每片段进上下文时已被
`_KB_CHUNK_CONTENT_LIMIT=1500` 头部截断，于是证据 7.0 万字符 ≈ 上限 2.9 倍。
`tool_results` 只有 1 条、约 2.3k 字符，**排除**「单个工具结果过大」这一假设。

要点：这**不是「预算值定小了」**，而是**累积无界**。`evidence_budget` 是硬 UNANSWERED，
所以 60% 的题在**检索本身已经成功**（确实召回了 72 个片段）的前提下被判成「资料不足」。
另注意 `_candidate` 里 `citations` 是由**全部** `chunks` 生成的，而 evidence 只装得下一部分：
模型可能引用 evidence 里不存在的编号，引用列表也会长到用户无法阅读（实测出现过 24 条）。

#### R2 到达校验的请求几乎无一通过

`rag_validation_timeout_sec=20`，而 judge 要读最大 24,000 字符证据。15 题里 3 题
`validation_failed`（`TimeoutError`）；修复后 3 题小样本里到达校验的 **2/2** 都超时。
账本里这两次 judge 记 `usage_source=unknown`（超时拿不到 usage），与「不知道花了多少」一致。
**R1 与 R2 互相独立**：修好预算不会顺带修好超时。

#### D1/D2 已修并实测（产品路径的用量收口）

修复前：产品路径 `chat_trace` **0 条**、`upstream_usage_records` **0 行**（
`flush_trace_usage` 只挂在两条 legacy 的 `_log_trace` 上），且 `RequestTrace.request_id`
是随机 `trace_xxx`、与 API 的 `request_id` 无关 ⇒ P1 声称的「全链路」在产品流量上不成立，
P2 §4 的「逐题回读账本」在真实后端**永远匹配不上**。

修复后（1 题真实调用）：`chat_trace` 1 条、账本 **7 条 / 5 次调用 / 10,890 tokens /
CNY 10813 微元 = ¥0.0108**（价格版本 `2026-09-17-user-provided-no-bill-source`），
`UsageService().query(request_id=…)` 可精确回读。修复内容：

- `RagProductService.__init__` 用 `RequestTrace(request_id=self.request_id)`；
- 新增 `_log_trace()`（`flush_trace_usage` + `chat_trace` 日志 + `record_chat_trace`），
  在 `run()` 的 **`finally`** 里调用——异常/超时/取消三条路径都覆盖；
- 归属只填空缺（`usage.bind`），重放场景已有归属不被改写；
- **顺带修的第四处**：`events()` 的 `start` 帧原先在 `try` 之外，客户端恰好在该帧断开时
  `run()` 任务无人 `cancel()`/`await`，成了孤儿（账目与持久化都不收口）。已把 `try` 提前。

**legacy 路径不是缺陷，不要「顺手修」**：它 SSE `start` 回显 `trace.request_id`、
账本同键，本来就自洽（`live_eval_runner.py` 的 `observed_request_id` 正是为此写的）。

#### 本轮真实花费

- 修复后 4 题：**¥0.0756**（账本精确，48 条 / 33 次调用 / 76,325 tokens，含 P1 校准那行）。
- 修复前 15 题：**无账本行**（D1 的直接后果），只能按同批 prompt:completion ≈ 8.6:1
  估算 ≈ **¥0.30**（区间 ¥0.24–0.80）。**不要把估算写成实测。**
- `query_embed` 全部 `cost=None`：`text-embedding-v4` 单价仍未提供，账本**只出用量不出价**。

#### 本批没做的事（及理由）

- **没做盲审、没出 `--summarize` 门禁结论**：15 题 0 题产出真实答案，没有引用可核对；
  让教师花 30–75 分钟核对一叠「资料不足」占位是浪费。
- **没建基线**：`--write-baseline` 只能用有效 run。
- **没改预算值、没改检索/引用策略、没改 judge 超时**：这三项都会改变产品行为与单请求
  成本（实测单请求已达 4 万+ tokens），属于要用户拍板的取舍，不在本批擅自决定。
- 临时开关已回退 `RAG_PRODUCT_ENABLED=false` 并重启（`/api/health`、`/api/ready` 均 200）。

#### 新增的可观测性（否则 R1 无法归因）

`evidence_budget` 原先只留一个原因串，运维既不知道是片段攒多了还是单个工具结果太大，
也就不知道该调预算还是该收敛检索。现在超预算时打一条
`rag_evidence_budget_exceeded`（`chunks` / `distinct_chunks` / `chunk_chars` /
`tool_results` / `evidence_chars` / `limit`）。`distinct_chunks` 是**下一次**要读的数：
它决定「去重」能不能单独救回预算，还是必须同时设上限。
**注意**：`JsonFormatter` 对 `extra` 有**字段白名单**，白名单外的键会被**静默丢弃**
（`tool_rounds` 曾因此形同虚设）——要么走 message-JSON 形态，要么把键加进白名单。

### 6.6 R1/R2 修复与二轮实测（2026-09-17，两轮各 3 题付费调用）

#### R1 已修，并在**生产上**证实生效（不是只过测试）

改法（`app/agent/tools.py` + `app/services/rag_product_service.py`）：

- `kb_search` **按 chunk.id 去重**，重复命中复用**原编号**（编号是位置量，重发编号会让
  `[n]` 指向另一条片段）；`count` 仍是本次返回条数。
- 新增 `bound_evidence(evidence, limit)`：超预算时**片段一个不少、只缩短正文**，
  等额 + 按实测比例收敛，纯函数、无随机、无时间依赖。替代原先的硬 `evidence_budget`
  （那是把「装不进预算」这个**表示**问题当成「资料不足」这个**结论**）。

实测（`rag_evidence_bounded`，两轮 3 题）：

| request | chunks | distinct_chunks | chunk_chars | evidence_chars | kept_chars |
|---|---|---|---|---|---|
| 98d074c7 | 37 | 37 | 46639 | 43979 | 23309 |
| 741d24a8 | 27 | 27 | 31887 | 27916 | 19403 |
| 26d34ad7 | 34 | 34 | 34969 | 31881 | 21137 |

`distinct == chunks`（去重干净）、`kept_chars <= 24000`（收窄到位）。
对照修前：`chunks=72`（去重后本应约 30+）、`evidence_chars=69958`。

**R1 没有顺带修好 R2**（实测，非推断）：收窄后 judge 仍然超时，`dns_1` 那题
**根本没触发收窄**（证据未超预算）却照样超时。

#### R2 的结构性缺陷已修；超时上限按上游分位重定

缺陷的精确表述：judge 的**内层**单次客户端超时是 `timeout=90.0` × (`llm_max_retries+1`=4) 次
尝试 = 360 秒，而**外层** `rag_validation_timeout_sec=20` 罩在它上面 ⇒ 20 < 90，judge 阶段
的重试与错误分类逻辑**永远不可达**（第一次尝试还没超时，外层就到期了）。同时 judge 拿的是
**独立的** 20 秒而不是请求剩余预算 ⇒ 最坏 20+120=140 秒 > 请求墙 120 秒，**两个墙同时到期，
同一个「慢」报成哪种 reason 取决于毫秒级竞态**。

改法：

- 新增纯函数 `judge_budget(request_budget_sec, elapsed_sec, cap_sec, *, floor_sec=1.0)`
  = `max(min(cap, remaining), floor)`；judge 死线**必然早于**请求墙，归因不再由竞态决定。
  `/api/rag` 的 `verify` 端点同一口径。
- `rag_validation_timeout_sec` 语义改为**上限**，20 → **60**。这是**按实测定的**，不是
  「调大到不超时」：上游**真实调用**（`rewrite_ms`，n=12，已剔除 ≈0ms 的未命中）
  **p50 13.1s / p90 45.3s / max 46.8s**，20 秒墙落在 **p90 之下**，对 5/12 次真实调用必然误判。
  60 秒覆盖实测上界并留余量。**注意：调大到 60 秒并没有让 failures 消失**（见下），
  所以不构成「用放宽阈值掩盖失败」。
- 新增阶段埋点 `answer_generation_ms` / `validation_ms`（`timed_stage` / `_timed_generation`）。
  此前 `stages_ms` **只有检索阶段**，judge 花了多久根本无从判断——这是 R2 此前只能靠猜的
  直接原因。失败路径也记（`finally`），因为「失败那次花了多久」正是要看的数。
- `rag_validation_failed` / `rag_request_failed` 补 `request_id`：此前 7 条 `TimeoutError`
  只能靠时间顺序猜是哪次请求。该键在 `JsonFormatter` 白名单内。

变异检验（4 个，全部被捕获、逐字还原、哈希核对）：judge_budget 退回固定值、
调用点退回固定超时、去掉 `request_id`、去掉两处阶段埋点。

#### 二轮实测：answered **没有**回来（这是本节的结论，不是待办）

| 轮次 | 用例 | 总耗时 | 生成 | 校验 | 结果 |
|---|---|---|---|---|---|
| R1 后 | dns_1 | 39.4s | — | 20s 撞墙 | system_error |
| R1 后 | ftp_2 | 116.4s | — | 20s 撞墙 | system_error |
| R1 后 | https_1 | 24.5s | — | 刚好挤过 | unanswered |
| R2 后 | dns_1 | 76.1s | 15.3s | **60.0s 撞满新上限** | system_error |
| R2 后 | ftp_2 | 101.2s | 46.4s | **54.7s 跑完** | unanswered |
| R2 后 | https_1 | — | 24.8s | 16.8s | system_error（**撞 120s 请求墙**） |

结论：**超时只是症状。**判据是这两条：

1. **judge 要读的东西太大**：34 个片段 / 24k 字符送进 judge。而片段数之所以是 34，
   是因为 `collected_chunks` **在去重之后仍然无界**——R1 只做了「去重 + 按需缩短正文」，
   **没有做用户选定的「名次上限」**。收窄正文不减少 judge 要读的**条数**。
2. **上游本身慢且方差大**：真实调用 p90 45.3s。任何在 120 秒里要串 2 次以上上游调用的
   请求都很脆弱（生成 46.4s + 校验 54.7s = 101s，已经贴着请求墙）。

**因此 R2 之后请求墙成了慢尾的新瓶颈**（`https_1` 就是死在 120s 请求墙而非 judge 墙）——
这正是 `judge_budget` 把两个墙组合起来要达到的效果：归因确定，而不是随机。

#### 尚未决定的事（需要拍板，不要替用户决定）

judge 要读的**条数**必须降下来，可选：

- **(a) 名次上限**（用户先前已选「去重 + 名次上限」，但未定数）：在 `kb_search` **收集时**
  按名次设上限。必须在**收集时**而不是构证据时截断——截断已发出的编号会让 `[n]` 指向
  另一条片段（静默错配，比拒答更糟）。给出 N（6–8 时 judge 输入降到约 9–12k 字符）是
  **检索质量取舍**，要用户拍板。
- **(b) 抬高请求墙**（120 → 180s）：让「慢生成 + 慢校验」能共存；代价是用户最长等 3 分钟。
- **(c) 接受一定比例的 system_error**，把 judge 延迟列为产品风险。

(a) 与 (b) 相互作用，建议一起定。

#### 本轮真实花费（账本精确，逐请求可回读）

| request | 用例 | 条数 | tokens | 金额 |
|---|---|---|---|---|
| 85d6e27e | R1后 dns_1 | — | — | ≈¥0.012 |
| 98d074c7 | R1后 ftp_2 | 27 | 48597 | ¥0.0474 |
| 741d24a8 | R1后 https_1 | 14 | 25220 | ¥0.0225 |
| f56b7110 | R2后 dns_1 | 10 | 14486 | ¥0.0134 |
| 26d34ad7 | R2后 ftp_2 | 28 | 55954 | ¥0.0562 |
| 2d7ced87 | R2后 https_1 | 9 | 14963 | ¥0.0148 |

两轮 6 题合计 ≈ **¥0.166**，**单题 ¥0.014–0.056**。此前交接文档里「3 题 ≈ ¥0.03」
**低估了约 2.8 倍**：验收形态单题要 3–5 个工具轮，25k–56k tokens，不是一次问答。
**照此推算 15 题 ≈ ¥0.42，不是先前说的 ¥0.30** —— 重跑前要按这个数字重新批预算。
账本累计（全部会话）99 次调用 / 249,781 tokens / **¥0.2435**。

#### 本轮没做的事（及理由）

- **没重跑 15 题**：R2 之后 judge 墙与请求墙仍然拦人，重跑只会再买一批 system_error；
  且实际单价（≈¥0.028/题）高于先前给用户的估算，扩量前应先确认预算。
- **没做 (a)/(b)/(c) 中的任何一项**：都是产品行为/成本取舍，属用户拍板范围。
- **没做盲审、没建 baseline**：仍然没有可核对的答案。

#### 一处测量污染（必须知道，否则会误读时间）

R2 轮 `https_1`（`2d7ced87`）的日志墙钟跨 **363 秒**，而 `chat_trace.total_ms` 只有 **41.7 秒**。
原因是**宿主休眠**（`pmset` 显示开机以来 4039 次 sleep/wake）：Darwin 上 `time.perf_counter()`
（即 `now_ms()`）与事件循环单调钟**在系统休眠期间不走**。所以：

- 该题的**计时数据作废**（状态数据仍有效：它撞的是 120s 请求墙）。
- 一般规则：**本机跑批时先 `caffeinate`**，并把「墙钟 ≫ trace.total_ms」当作休眠信号。
- 这也解释了为什么单次真实调用延迟的 p90 高达 45s 却又不总是如此——分布本身有真实方差，
  但读数里可能混入休眠。

## 7. 下一批任务（R1/R2 已修，见 §6.6）

0. **先拍板 judge 输入条数**（§6.6 末：(a) 名次上限定 N / (b) 抬高请求墙 / (c) 接受）。
   这是**唯一**还没动的瓶颈；不解决它，任何规模的真实跑批都只会产出 system_error。
   若选 (a)：改在 `kb_search` **收集时**截断（不是构证据时），先补确定性测试
   （`tests/test_agent_tools.py` 已有去重用例可扩展），并保留「编号与名次一一对应」的断言。
1. **重跑 15 题**（同一 `--sample` 种子）看 answered 是否回来，再谈盲审。**按 ≈¥0.42 批预算**
   （不是 ¥0.30）；跑前 `caffeinate`，跑后核对账本。
2. 抽取覆盖可回答、证据不足、澄清、对抗的**10–20 题**，人工校准标签与评分口径；
   P2.2 100 题另批预算。自动分类与人工结论继续分列。原有 46 题只用于检索回归，不能替代该门禁。
3. **`route_kb_engine_ops` 仍是待判项**（见 §4 与 §6.4 第 4 条）：E5 压缩粒度已在离线修好
   并有承重断言，但**要用真实复跑判定它是否真转 PASS**；本轮 live 未复跑（需另批预算），
   验收侧该题这轮落在 `evidence_budget` 里，**没有**回答质量信息。同时单列词边界口径的
   数字变化。不要为跑绿放宽 rubric。
4. 用 P1 的账本给出每轮的**真实花费口径**（按 stage/model 的 token 与金额）：
   产品路径自 §6.5 起才真正落账，**修复前的 run 无法回读**，不要为历史 run 编数字。
   `answer` 阶段已由真实调用验证过尾帧可用（§5.4）；planner/rewrite/query_embed/judge
   已在真实批量里看到账（planner 每个工具轮一条，是成本主项），judge 超时那几次记
   `unknown`，符合预期。
5. **`text-embedding-v4` 单价仍未提供** ⇒ `query_embed` 账本行 `cost=None`，账本只出用量不出价。

## 8. 验收与边界

```bash
cd backend
.venv/bin/python -m pytest tests/agent/test_golden_scenarios.py -q
.venv/bin/python -m pytest -m 'not live and not postgres' --cov=app --cov-fail-under=60
.venv/bin/python -m ruff check .
# 账本专项（离线；PG 侧只在独立测试库跑）
.venv/bin/python -m pytest tests/test_usage_wiring.py tests/test_usage_cost_api.py -q
RAG_TEST_DATABASE_URL=postgresql:///assistant_rag_test \
  .venv/bin/python -m pytest tests/test_usage_records_postgres.py \
  tests/test_rag_versioned_postgres.py -q     # 可重复跑，用例自清产物
# P2 验收工具（离线；不调模型、不花钱）
.venv/bin/python -m pytest tests/rag_eval/ -q          # 约 2 分钟，建议后台跑
.venv/bin/python tests/rag_eval/product_acceptance_runner.py --sample 15
.venv/bin/python tests/rag_eval/product_acceptance_runner.py --evidence <run.json> --case <id>
# live：按需先 --case 指定失败题；输出使用新文件名，保留原始报告
.venv/bin/python tests/agent/live_eval_runner.py --output ../.logs/live_eval_qwen38_flash_p2.json
```

P2.1 本机实测（2026-09-17，**开关已回退 `RAG_PRODUCT_ENABLED=false` 后跑**）：
默认集 **674 passed / 96 deselected，覆盖率 86.31%**（P2.0 基线 670 / 86.17%，
+4 = 本批新增 3 例账本/request_id + 1 例预算诊断）；`tests/rag_eval/` **127 passed**；
黄金场景 **36 passed**（一次在 15 题跑批并行的负载下出现 `test_s06` 假失败——judge 20s
超时；单独复跑两遍均通过，**是负载抖动不是回归**，但这条恰好又一次说明 R2 的 20s 很紧）；
PG 专项 **5 passed**（同库可重复）；`ruff check .` 全绿。
4 个变异（`RequestTrace()` 不接 id / `finally` 不落账 / `start` 帧移回 try 外 /
删掉预算诊断）**全部被捕获**，逐字还原后哈希一致。
变异测试纪律沿用：改坏源码 → 目标断言必须失败 → 逐字还原（`diff`/哈希核对）。
**注意**：变异测试要用 `PYTHONDONTWRITEBYTECODE=1` 或先清 `__pycache__`——同一秒内
改回源码时，`mtime`+`size` 可能命中旧 `.pyc`，让**已经被捕获的变异显示成「未捕获」**
（本轮 10 个变异里就误报过 1 个）。

**P2.0 收尾验证时又抓到两处问题，都已修并留下回归**（细节见 §6.4）：

1. `--evidence` 的**文档调用式原本取不到证据**：`cd backend && python
   tests/rag_eval/product_acceptance_runner.py --evidence run.json` 时，Python 只把
   脚本目录 `tests/rag_eval` 放进 `sys.path`（不含 cwd），`import app` 抛
   `ModuleNotFoundError`，整篇清单变成「取证据正文出错」——看上去像证据库坏了。
   已加 `_ensure_app_importable()`，并用**子进程**测试锁死（进程内测不出来：
   pytest 早已 import 过 `app`，`find_spec` 走 `sys.modules` 短路）；2 个变异均被捕获。
2. PG 专项 `test_rag_versioned_postgres.py` **同库跑第二遍必失败**（不是发布逻辑坏了）：
   该用例不清自己的产物，上一轮留下的同内容 release 会被复用，首轮 ingest 不再
   embedding，`embed.calls == 1` 拿到 0。已改为开跑前清 `rag_release_nodes` /
   `rag_releases` / `data_rag_test_nodes`；在**已被污染**的库上连跑两遍均 5 passed。
   （我第一次跑就撞上了，按「发布事务坏了」排查了一轮才定位到测试隔离。）

若改前端，另跑 npm test/build；PG 测试只能用独立测试库。
最终记录修改点、根因、实测结果、残留风险和下一批任务，更新交接与进度文档。
不能仅凭 mock 全绿或 HTTP200 宣称线上质量通过。

M4a 保留并校准，M4c 按本次失败补回归；M4b checkpoint/interrupt 暂缓。
SQLite 当前不删除：先建设 PG CI 并迁移业务测试，完成迁移/历史引用/恢复验证和
P2/P3验收后独立删除。生产 LI 数据面已 PG-only，但业务测试仍依赖 SQLite。
不启动 B4 rerank、不启用产品灰度、不调整架构来绕过这批明确问题。

P1 的代码与确定性测试已收口，回到 `docs/next_stage_plan.md` 的 P2 答案验收；
`route_kb_engine_ops` 的压缩粒度问题（见 §4）是 P2 的首个已知输入。
