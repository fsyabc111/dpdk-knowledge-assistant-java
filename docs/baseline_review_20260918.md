# S0-01 可信基线核查报告

文件名沿用[统一路线](roadmap.md#4-首批执行单s0-01-可信基线核查)的计划命名；**实际执行日期 2026-09-21**。

本批只调查，不修业务。报告区分三种依据：**本批代码确认**（我在下列版本上读过实现并能说明分支）、**本批复现**（本轮在隔离环境跑出的行为）、**历史复现**（引用 [P0.1 独立验收](p01_independent_acceptance_20260918.md)，注明其版本与适用范围）。历史测试通过不写成本轮验证通过。

## 1. 基线与环境

### 1.1 代码版本

| 项目 | 值 |
| --- | --- |
| 助手仓库 HEAD | `7227c5c2d707819de86aaba77948069c9c33189a`（分支 `main`） |
| 助手仓库工作区 | 三份**既有**未提交文档改动：`docs/roadmap.md`、`docs/decisions.md`、`docs/knowledge_enrichment_plan.md`。本批未修改、未提交、未回退它们 |
| 独立 harness HEAD | `9ed5cfd7f03cd8b9c062a8d1a8d621403aaadcbc`（分支 `master`，工作区干净） |
| 本批新增文件 | 仅 `docs/baseline_review_20260918.md`（本文）+ `.logs/` 下证据（`.logs/` 在 `.gitignore:26`，不随 Git 交付） |

### 1.2 工具与平台

Darwin 25.6.0 / arm64；`backend/.venv` Python 3.11.15（系统 python3 为 3.9.6，未使用）；Node v24.18.1；npm 11.16.0；git 2.50.1；PostgreSQL 客户端 17.10；Docker 29.7.2。

### 1.3 脱敏后的本机行为配置

取自 `backend/.env`，**仅列影响行为的非机密项**，凭据一律不入本文：

`APP_ENV=dev`、`DATABASE_BACKEND=postgres`、`DATABASE_URL=postgresql:///assistant_dev`、`AUTH_MODE=local`、`RAG_PRODUCT_ENABLED=false`、`MODEL_NAME=qwen3.8-flash`、`EMBEDDING_MODEL_NAME=text-embedding-v4`、`HARNESS_MCP_URL=http://127.0.0.1:8100/mcp`。`AGENT_ENABLED` 未显式设置，取 `config.py` 默认（开启）。

本批**未**对上述开发库运行任何测试，**未**调用真实模型或真实 harness。

### 1.4 关键基线事实：P0.1 报告的代码版本与当前 HEAD 等价

P0.1 独立报告的 HEAD 是 `7048d8c`。本批比对 `7048d8c..7227c5c`：

```
backend/app/agent/__init__.py           | 2 +-
backend/app/agent/prompts.py            | 2 +-
backend/app/config.py                   | 6 +++---
backend/app/services/grading_service.py | 2 +-
backend/tests/agent/golden_support.py   | 2 +-
backend/tests/agent/live_eval.yml       | 2 +-
backend/tests/agent/live_eval_runner.py | 2 +-
backend/tests/agent/test_golden_scenarios.py | 2 +-
backend/alembic/versions/0009_li_hnsw_index.py | 2 +-
deploy/auto_ingest.sh                   | 2 +-
```

十处**全部**是注释/docstring 内 `docs/xxx.md` → `docs/archive/2026-09-18/xxx.md` 的路径字符串替换，逐行一对一，**没有任何逻辑变化，行号不变**。本批抽查复核了 P0.1 报告引用的 `context.py:180/184/213/289`、`graph.py:164/183/185`、`service.py:441/452/458`、`tools.py:546`，行号与内容均与报告一致。

**因此 F1–F6 的代码依据在当前 HEAD 逐字有效。** 这是本批据以「按版本继承」历史反例、而不重复执行它们的依据（见 §2 范围说明）。

### 1.5 测试基建隔离审查（执行单要求的前置检查）

**B0（本批代码确认，非业务缺陷，但影响此后每一批的取证可信度）**

`backend/tests/conftest.py` 的 autouse fixture `_isolate_env_and_caches`：

- 覆盖 `AUTH_MODE`、`BCRYPT_ROUNDS`，快照并复原 `os.environ`；
- **不**覆盖 `DATABASE_BACKEND` / `DATABASE_URL`；
- 注入 dummy 模型地址的分支带条件 `if not (ROOT / ".env").exists()`（`conftest.py:49`）。

本机 `backend/.env` 存在，且内含 `DATABASE_BACKEND=postgres`、`DATABASE_URL=postgresql:///assistant_dev` 与真实 dashscope 地址/密钥。结论：**在本机直接运行 `pytest -m "not live and not postgres"`，任何未显式使用 `sqlite_db` fixture（或自行 monkeypatch 数据库变量）的用例，都会落到真实开发 PG；模型地址也不会被替身接管。** `backend/tests/` 下 `DATABASE_BACKEND`/`DATABASE_URL` 共 42 处匹配，说明多数用例确实自行覆盖，但这是逐用例的约定而非 fixture 保证。

**本批处置**：不运行仓库自带测试集，改为自建完全隔离的复现脚本（临时 SQLite + 全部上游指向 `127.0.0.1:9` + 进程启动即断言的安全闸）。
**建议**：把数据库与上游地址的中和移入 autouse fixture，并去掉 `.env` 存在时的豁免分支。归属见 §5。

**修复状态（2026-09-21，S1-b）**：已按建议修复——中和移入 autouse fixture，`live`/`postgres` mark 成为显式豁免口，`.env` 豁免分支已删；契约由 `tests/test_conftest_isolation.py` 金丝雀锁死。验证记录见[统一路线第 9 节](roadmap.md#9-s1-b-批次记录b0-测试-fixture-隔离)。残留：`test_postgres_runtime.py` 的 ambient env 依赖与 ingest 数据前置登记为 W6。

## 2. 本批范围（经用户 2026-09-21 确认收窄）

执行单原列 7 个核查项。基于 §1.4 的等价性证据，用户确认把本批收窄为：

- **新写隔离复现**：核查项 1（成绩契约）、核查项 6（恢复与请求身份）中的请求身份/取消/release 部分；
- **按版本继承 + 本批代码确认**：核查项 2/3/4/5（对应 F1–F6），不重跑历史反例；
- 用户同时确认**不**对 P0.1 旧证据目录做完整性校验（不记哈希、不重跑旧脚本）。

7 个核查项**在本报告中全部有处置**，但证据等级不同，逐项在 §3 标注。

## 3. 问题清单

每项格式：现象 → 影响路径 → 代码依据 → 复现与输入 → 预期/实际 → 状态 → 建议批次。

### 核查项 1 — 前后端成绩契约

#### B1 成绩页面六处字段错位，教学闭环的成绩展示整体失效

**现象**：教师「成绩总览」表的协议列整体消失、班级列恒为 `—`、协议得分恒为 `—`、总分右半恒为 `—`；学生「作业提交」卡片恒显示「我的得分 0 / —」，详情表的「我的最高分 / 折算得分 / 提交次数」恒为 `—/—/0`。后端数据本身是对的。**显形前提**：仅在 `AUTH_MODE=account` 下出现——`config.py:115` 默认与本机 `.env` 均为 `local`，此时作业端点整组 404、前端入口静默隐藏（`main.jsx:229`、`:254`），即「切到 account 模式即不可用」。

**影响路径**：`GET /api/assignments/{id}/grades`（教师）、`GET /api/assignments`、`GET /api/assignments/{id}`（学生）→ `frontend/src/main.jsx`。

**代码依据**（本批代码确认）：

| # | 前端取值 | 后端实际字段 | 后果 |
| --- | --- | --- | --- |
| 1 | `grades?.items`（`main.jsx:883`） | `AssignmentGradesResponse.assignment.items`（`schemas/assignments.py:124-126`） | `gradeItems=[]` → 协议列**整列消失**（表头与单元格同时消失） |
| 2 | `row.class_owner`（`main.jsx:1018`） | `AssignmentGradeRow.class_teacher`（`:116`） | 班级列恒 `—` |
| 3 | `row.earned_by_protocol?.[p]`（`main.jsx:1020`） | `AssignmentGradeRow.scores`（`:118`） | 协议得分恒 `—` |
| 4 | `row.total_points`（`main.jsx:1022`） | 行模型无此字段（`:113-121`） | 总分右半恒 `—` |
| 5 | `assignment.my_grades?.total_earned / .total_points`（`main.jsx:1132`） | `StudentAssignment` 顶层 `total_earned` / `total_points`（`:75-85`） | 卡片恒「我的得分 0 / —」 |
| 6 | `myGrades?.items`（`main.jsx:1103-1104` → `:1169-1171`） | `StudentAssignment.items`（顶层，`:82`） | 最高分/折算/次数恒 `—/—/0` |

`frontend/src/api/client.js:397` 的注释把该接口写成「`{items, rows}`」——前端是照一份不存在的契约实现的，注释与实现自洽，所以静态审查容易漏掉。

**复现与输入**：用后端**真实响应模型**（`AssignmentGradesResponse` / `StudentAssignmentListResponse`）序列化一份合成响应（1 份作业、2 个协议项 dns 60 分 / http 40 分、1 名合成学生 `U20260000001`、班级 `T001`、得分 48/30、总分 78），再用 Node 逐字执行 `main.jsx` 中的取值表达式（含 `main.jsx:881-882` 的 `formatScore`）。脚本：`repro_baseline.py`（R1）+ `eval_frontend.mjs`。

**预期 / 实际**：

```
[:883]  gradeItems → []            (协议列数 = 0)；正确来源 grades.assignment.items = [dns 60, http 40]
[:1018] row.class_owner || "—" → "—"                (真实 class_teacher = "T001")
[:1020] dns → —  http → —                           (真实 scores.dns=48, scores.http=30)
[:1022] 78.0 / —                                    (API 行内无 total_points)
[:1132] "我的得分 0 / —"                             (真实应为 "我的得分 78 / 100")
[:1169-1171] dns: — / — / 0                         (真实 80 / 48 / 2)
             http: — / — / 1 → 实际 0                (真实 75 / 30 / 1)
```

**状态**：**确认**（取值层）。**界面未实测** —— 本批未起浏览器、未渲染 React，只对合成响应求值。真实浏览器下的最终呈现仍需场景验收。

**注**：`architecture_current.md` 的 C01 只记了 2 处错位，实际为 6 处，且最严重的一处（协议列整列消失）未被记录。该文档条目需在修复批次一并更正。

**建议批次**：**S1 第一批，单独成批**。纯前端取值修正，不动后端契约，零跨服务依赖，可独立验收。

### 核查项 2 — 引用身份

#### B2 工具片段编号与响应 citations 编号不同源，且四条上限会改变编号身份

**现象**：模型在答案里写的 `[n]` 按 `kb_search` 返回的片段序号，而响应 `citations` 按文件去重后截断到 4 条。同一文件多片段、跨轮累积检索时，`[2]` 指向的片段与 citations 第 2 条可以是不同来源。

**影响路径**：`agent/tools.py` → 模型答案 → `AgentOrchestrator._citations` → `ChatResponse.citations` / `assistant_citations` 落库 / 前端来源列表。

**代码依据**（本批代码确认）：

- 发号：`tools.py:126-141`，`ref` 按 `context.collected_chunks` 的**位置**分配，同片段跨轮复用原编号（去重发生在 append 之前，注释 `tools.py:116-125` 明确解释了为何不能事后删片段）。
- 收号：`agent/service.py:334-348`，`_citations` 对 `collected_chunks` **按 `source_path` 去重**，并在第 4 条 `break`。
- 两者对同一份 `collected_chunks` 做了**不同的投影**：一个保位置、一个按文件去重并截断。编号身份因此不守恒。
- 落库同样用这份截断后的列表：`service.py:465-469`（流式）、`:585-589`（非流式），`rank` 从 1 重排。
- 跨轮：`collected_chunks` 是请求级累积（`tools.py:58`），多轮 `kb_search` 会把编号推到 4 以上，而 citations 永远 ≤4。
- 产品路径是另一套第三种口径：`rag_product_service.py:308-310` 的 `evidence.chunks` 按 `enumerate(chunks, 1)` 发号、`:334-335` 的 citations **不去重不截断**，`validate_answer`（`:117`）按 `len(chunks)` 校验编号。即普通路径与产品路径的引用语义本身也不一致。

**复现与输入**：本批**未独立复现**。历史反例见 P0.1 报告 F4：模型收到 `a1(a.md), a2(a.md), b1(b.md)`，答案引用 `[2]`（= a2），响应第二条为 b1；同报告记录 live 首轮 `route_kb_engine_ops` 实测出现 `[9][10][17][18]` 而 sources 仅 4 项。

**预期 / 实际**：预期「答案里的 `[n]` 与响应 citations 第 n 条同指一段」；实际两者不同源。

**状态**：**代码确认（本批）+ 历史复现（P0.1，代码版本与当前 HEAD 等价，见 §1.4）**；本批未独立复现。

**建议批次**：S1，但**需先由用户定契约**（三选一：citations 不去重不截断以对齐编号 / 让模型只能引用去重后的编号 / 在响应里同时给出编号映射表）。这是产品边界，不由实现者自选。产品路径的第三套口径应在 S2 一并收敛。

### 核查项 3 — 已完成评测保留

#### B3 SSE 已输出文字后模型失败，会丢弃本轮已完成的评测结果

**现象**：一次成功的 `grade_plugin`（已扣配额、已真实执行、已落成绩）之后模型失败；若此前已有任何流式文字输出，最终正文只剩那句前置说明，不含分数与失败用例。非流式路径同条件下会保留。

**影响路径**：`POST /api/chat/stream` → `AgentOrchestrator.answer_stream` 异常分支。

**代码依据**（本批代码确认）：

- `agent/service.py:441` —— `if not answer_parts:` 才调用 `build_fallback_answer(..., grading_results=context.grading_results)`；
- `:452-455` —— 否则只发一个 `error` 事件（「模型生成中断，已保留部分内容。」），评测事实不进正文；
- `:458-464` —— 无论走哪一支，随后都以 `stream_status="completed"` 落库；
- 对照非流式 `:564-575`：`answer` 方法无条件走 `build_fallback_answer(..., grading_results=...)`，评测事实**总是**保留。

即 JSON 与 SSE 在同一故障下的降级语义不一致，且分歧点只由「此前是否已产生过文字」决定。

**复现与输入**：本批**未独立复现**。历史反例见 P0.1 报告 F2（把黄金 s33b 首个模型消息从空文本改为「我先检查这份插件。」，其余条件不变；事件序列 `status/start/status/delta/tool_start/tool_end/error/done`，正文无 62 分、5/8 与失败用例）。

**旁证（本批复现）**：R3-4 观察到「带 `tool_calls` 的 AIMessage 仍会产生 `delta` 事件」（实测事件序列 `['status','start','status','delta']`）。这正是 F2 分支被触发的前提条件——模型在调用工具的同一条消息里带文字，就足以让后续失败落入「已有输出」分支。该前提在当前 HEAD 成立。

**状态**：**代码确认（本批）+ 历史复现（P0.1，版本等价）+ 触发前提本批复现**；完整反例本批未独立复现。

**建议批次**：S1，与 B4/B5 同批（同在 agent 降级/finalize 路径，改完一起跑黄金集）。

**修复状态（2026-09-21，S1-c）**：已修——`grading_results` 非空时异常分支无条件补 `build_fallback_answer` delta；`error` 事件触发条件不变。验证记录见[统一路线第 10 节](roadmap.md#10-s1-c-批次记录b5b4b3-agent-降级路径合批)。

### 核查项 4 — 工具协议与预算

#### B4 轮数/token 达限转 finalize 时，未执行的 tool_call 原样进入 finalize

**现象**：`finalize` 收到的消息序列里存在没有对应 `ToolMessage` 的 `tool_call`。严格校验工具协议的 OpenAI-compatible 服务可能拒绝该请求。

**影响路径**：`agent/graph.py` 路由 → `finalize` → 上游模型。

**代码依据**（本批代码确认）：

- `graph.py:178-187` `route_after_planner`：`last` 有 `tool_calls` 时，若 `tokens_used >= token_budget`（`:183`）或 `tool_rounds >= max_rounds`（`:185`）→ 返回 `"finalize"`。这条 planner 消息的 `tool_calls` **从未被 `run_tools` 执行**。
- `graph.py:162-176` `finalize`：直接把 `state["messages"]` 交给 `prepare_messages`，不剔除未配对调用。
- `context.py:18-21` 的「配对不变式」只承诺**不新产生**孤儿/悬空（压缩按原子单元处理），不修复**入口就不完整**的序列；`_unit_slices`（`:87-110`）对无后继 ToolMessage 的 AIMessage 直接切成 `(index, index+1)` 单元，原样保留。
- 对照 `route_after_tools`（`:189-193`）：该路径下最后一条是 ToolMessage，`run_tools` 为每个 call 都产出了一条 ToolMessage（`:152-156`），因此**不**产生悬空。悬空只来自 `route_after_planner → finalize`。

**复现与输入**：本批**未独立复现**。历史反例见 P0.1 报告 F3（`max_rounds=1` 的独立图运行，finalize 输入含 `c_kb`、`c_kb2` 两个调用，仅 `c_kb` 有 ToolMessage）。

**预期 / 实际**：预期「进入 finalize 的序列中每个 `tool_call` 都有配对结果」；实际达限那一轮的调用悬空。P0.1 明确记录：「永远没有悬空 tool_call」的说明不成立，且**未**付费探测真实上游对此的拒绝行为。

**状态**：**代码确认（本批）+ 历史复现（P0.1，版本等价）**；本批未独立复现；**真实上游是否拒绝仍未验证**（需付费真实模型调用，见 §4）。

**建议批次**：S1，与 B3/B5 同批。

**修复状态（2026-09-21，S1-c）**：已修——finalize 进模型前对末尾悬空调用补 `not_executed` 合成 `ToolMessage`（只改发送副本）。验证记录见[统一路线第 10 节](roadmap.md#10-s1-c-批次记录b5b4b3-agent-降级路径合批)。真实上游对旧行为的拒绝行为仍属 §4 V2 未验证项。

#### B5 已完成评测的长源码参数无法收敛，造成虚假的「地板超限」

**现象**：一次已经执行完的 `grade_plugin`，其 `plugin_source` 参数在后续轮次既不能被压缩也不能被丢弃，导致预算判定为 `floor_exceeded` 并整体降级——而真实的「系统提示词 + 当前请求」其实放得下。

**影响路径**：`agent/context.py` 分级收缩 → `ContextBudgetExceeded` → service 层安全降级。

**代码依据**（本批代码确认）：

- 第 3 级 `_compact_historical_args`（`context.py:172-192`）：`newest_start = slices[-1][0]`（`:180`），`index >= newest_start` 的消息**无条件跳过**参数压缩（`:184`）。判据是「是不是最后一个单元」，而不是「这轮工具是否已经执行完」。
- 第 5 级 `_drop_units`（`:208-232`）：`protected = {len(slices) - 1}`（`:213`）保护最后一个单元，另保护含最后一条 human 消息的单元（`:215-223`）。于是「已完成评测」这个单元两级都动不了。
- 讽刺的是模块内**已有**正确的区分工具：`_last_answered_tool_index`（`:113-129`）专门区分「有结果」与「未执行」的工具轮，但只被第 1 级 `_compress_older`（`:145`）使用，第 3/5 级没有用它。
- 计量确实包含 tool_calls 参数（`message_chars`，`:43-54`），所以参数是真的占预算。

**复现与输入**：本批**未独立复现**。历史反例见 P0.1 报告 F1（系统提示 10,000 字符、用户消息 9,000、已完成评测源码参数 9,000、短评测结果，预算 24,000 → `over_budget=true`、`prepared_chars=28119`、`reason=floor_exceeded`，而「系统 + 当前请求」仅 19,000）。

**状态**：**代码确认（本批）+ 历史复现（P0.1，版本等价，行号 `:180/:184/:213/:289` 本批逐行复核一致）**；本批未独立复现。

**建议批次**：S1，与 B3/B4 同批。修法方向明确（第 3/5 级改用 `_last_answered_tool_index` 的口径），但仍应按批次规则单独验收。

**修复状态（2026-09-21，S1-c）**：已按建议方向修复——第 3 级改 per-call「已执行」判据（`_answered_call_ids`），第 5 级保护集改为「最后已执行轮 + 最后 human」所在单元；F1 反例形状已转绿（prep11）。验证记录见[统一路线第 10 节](roadmap.md#10-s1-c-批次记录b5b4b3-agent-降级路径合批)。

### 核查项 5 — 源码身份与重复执行

#### B6 落库 hash 的源码与实际送去执行的源码可以不是同一份

**现象**：上传路径下，`grading_submissions.code_hash` 按用户上传的**原件**计算，而 harness 实际编译运行的是**模型在 tool args 里复述的源码**。两者字节不一致时，成绩记录指向的代码并非被评测的代码。

**影响路径**：`POST /api/chat`（multipart）→ `AgentToolContext.uploaded_source` / 模型 tool args → `grade_plugin` → harness；另一侧 → `_record_submission` → `grading_submissions`。

**代码依据**（本批代码确认）：

- 送执行的是模型参数：`tools.py:253-261`，`call_mcp("grade_plugin", {"plugin_source": plugin_source, ...})`，`plugin_source` 是工具入参（`:229`）。
- 记 hash 的是原件：`tools.py:300-305`，`if context.uploaded_source is not None:` 用 `uploaded_source` 算 hash，`source_origin="upload"`；否则用 `plugin_source`，`source_origin="pasted"`。
- 注释本身承认了差异存在：`tools.py:296-298`「模型在 tool args 里复述的源码可能与原件字节不一致，live 冒烟实测确实如此」；`agent/service.py:302-304` 同义。
- 作业提交路径不同源、且是自洽的：`api/assignments.py:324-354`，`grade_code(plugin_source=code)` 与 `sha256(code)` 用的是同一个 `code`，不经模型。**该路径无此问题。**

**状态**：**代码确认（本批）**。字节差异的具体触发条件与影响面**本批未复现**（需真实模型复述行为，见 §4）。

**建议批次**：S2/S3。该问题的正解是 N07 的「源码带外化」——不让模型参数成为执行输入，与执行边界收敛同批，不宜单独打补丁。

#### B7 同一请求内可重复执行 `grade_plugin`，重复扣配额、重复记成绩

**现象**：模型在后续轮次用相同协议与相同源码再次调用 `grade_plugin`，工具会再次真实调用 MCP、再次扣学生日配额、再次写一条成绩。

**代码依据**（本批代码确认）：`tools.py:228-286` 的 `grade_plugin` 闭包内**没有任何同请求去重**——每次调用都走 `rate_limiter.check_and_count_daily`（`:242-252`）、`call_mcp`（`:254-261`）、`_record_submission`（`:276`）。压缩摘要的措辞还在鼓励重评：`tools.py:546`「需要完整逐用例结果可重新评测」。

**复现与输入**：本批**未独立复现**。历史反例见 P0.1 报告 F6（相同成功评测执行两次）。P0.1 同时澄清这不是 MCP 传输层重试，且「已完成评分后直接抛异常」的原黄金场景确实只执行一次，不能据此推广。

**状态**：**代码确认（本批）+ 历史复现（P0.1，版本等价）**；本批未独立复现。

**建议批次**：S2。与 N05「同一业务任务不重复扣配额、不重复记成绩」直接对应，属执行边界收敛。

#### B8 粘贴路径的学生源码会进会话正文，降级预览的消毒只覆盖反引号围栏

**现象**：裸粘贴的源码、或 `~~~c` 围栏内的源码，会完整出现在降级答案的问题预览里；且无附件时用户消息原文（含源码）直接落 `assistant_messages`。

**代码依据**（本批代码确认）：

- 消毒只认反引号：`services/agent_service.py:42`（`_FENCED_BLOCK_RE` = ```` ``` ````）、`:44`（`_UNCLOSED_FENCE_RE` 同样只认反引号）、`:56-68`（`_sanitize_question` 只对这两个正则做替换，再截到 120 字符）。`~~~` 围栏与裸粘贴都不匹配，120 字符上限挡不住短源码全文。
- 会话正文：`agent/service.py:167-171` `_persistable_question` —— **仅在有附件时**替换为 `[上传附件：filename]`；无附件时 `return question` 原样落库（`:394-396`、`:547-549`）。
- 对照：产品路径有更强的消毒（`rag_issue_service.py:13-27` 的 `redact` 会整行省略 `#include`/`typedef`/`struct` 等，并处理 `[上传附件：…]`），但默认 `RAG_PRODUCT_ENABLED=false`，普通 agent 路径不经过它。

**复现与输入**：本批**未独立复现**。历史反例见 P0.1 报告 F5。

**状态**：**代码确认（本批）+ 历史复现（P0.1，版本等价）**；本批未独立复现。

**建议批次**：S2/S3，与 B6 同属 N07 源码带外化；消毒统一（让普通路径复用 `redact` 口径）可作为其中的独立小步。

### 核查项 6 — 恢复与请求身份现状

> 本项是本批**新写隔离复现**的重点。以下 B9–B14 全部在本轮跑出行为，脚本与原始日志见 §6。

#### B9 幂等键把不同源码折叠成同一身份，导致重放旧答案

**现象**：同一个 `request_id` 携带**不同**的学生源码再次请求，产品路径判定为「同一问题的重放」，直接返回上一次的答案，不执行本次代码。

**影响路径**：`POST /api/chat*`（`RAG_PRODUCT_ENABLED=true`）→ `RagProductService.probe_replay` → `RagIssueService.replay`。

**代码依据**（本批代码确认）：`rag_issue_service.py:56` 用 `redact(question) != row["question"]` 做幂等键校验，而 `redact`（`:14`）把整个 ```` ``` ```` 围栏替换为字面量「[代码已省略]」。围栏内容在比较之前已被抹平。注释（`:55`）自称契约是「同一 request_id 复用必须携带同一问题」。

**复现与输入**（本批复现，R2-1 / R2-2）：合成两条只有围栏内源码不同的消息：

```
A: 帮我看看这个插件\n```c\nint dns_probe(void){ return 1; }\n```
B: 帮我看看这个插件\n```c\nint dns_probe(void){ return 999; /* 完全不同的实现 */ }\n```
```

`begin(rid, "stu_1", sess, A)` → `finish(rid, …, "这是针对源码 A 的答案。", status=answered)` → `replay(rid, "stu_1", B)`。

**预期 / 实际**：

- 预期：不同输入复用同一 `request_id` → 409 冲突。
- 实际：`redact(A) == redact(B) → True`，落库文本 `'帮我看看这个插件\n[代码已省略]'`；`replay` **命中**，返回 `'这是针对源码 A 的答案。'`。

**状态**：**确认（本批复现）**。

**建议批次**：证据归 S0；**修复不得先于决策**——`decisions.md` §3「恢复身份与并发：幂等键与输入身份」尚待用户冻结。身份定义定下来之前动这里，S3 的恢复会建在错误的身份上。

#### B10 附件完全不参与幂等键

**现象**：同一 `request_id` + 同一消息文本 + **不同的上传附件**，同样被判为重放，返回旧答案。

**代码依据**（本批代码确认）：`rag_product_service.py:224` 只把 `(self.request_id, self.principal.subject, self.request.message)` 传给 `replay`；`self.attachment`（`:206`）自始至终不进入幂等键。

**复现与输入**（本批复现，R2-3）：登记 `rid3 = "33333333-3333-3333-3333-333333333333"` + 消息 A 并 `finish` 出旧答案；再用同一 `rid3`、同一消息 A、附件 `UploadedCode(filename="U20260000001_张三_dns.c", source="int totally_other_impl(void){ return 42; }")` 构造 `RagProductService` 并 `await probe_replay()`。

> 取证过程记录：首轮脚本此处登记用的是无连字符 hex，而 `ChatRequest.request_id` 是 `UUID` 类型、`RagProductService` 取 `str(UUID)`（带连字符），两者精确匹配失败，首轮误报为「未复现」。修正键格式后复现成立。此处如实记录，以免把键格式问题与附件身份问题混淆。

**预期 / 实际**：预期附件不同即视为不同输入、不重放；实际 `replayed` 命中，返回 `'这是针对「源码 A + 附件 A」的旧答案。'`。

**状态**：**确认（本批复现）**。

**建议批次**：同 B9，决策前置。

#### B11 执行中的请求无法重连接续，只能拿到 409

**现象**：请求正在执行（`rag_requests.status='pending'`）时，客户端用同一 `request_id` 重连，得到 409「该请求已受理或不可用，请读取会话结果」。既拿不到进行中的结果，也不接管原执行。

**代码依据**（本批代码确认）：`rag_issue_service.py:53` —— `status == "pending"` 或 `message_id` 为空即抛 409。重复 `begin` 同 `request_id` 亦 409（`:40-45`，`ON CONFLICT DO NOTHING` + rowcount 校验）。

**复现与输入**（本批复现，R2-4 / R2-5）：`begin(rid2, …)` 后不 `finish`，立即 `replay(rid2, …)`；以及对同一 `rid2` 第二次 `begin`。

**预期 / 实际**：两者均返回 409（分别为「该请求已受理或不可用，请读取会话结果」「该请求已受理，请读取会话结果后重试」）。

**状态**：**确认（本批复现）**。这是「当前无恢复」的**正面证据**，不是恢复能力。已完成请求的重放（B9/B10 的机制）同样不是任务恢复——它只返回既有结果，不接续执行。

**建议批次**：S3（恢复专项），决策前置项见 `decisions.md` §3「任务结果未知」「展示与重连」。

#### B12 Agent 图无 checkpointer，进程重启不能接续执行

**代码依据 + 复现**（本批复现，R3-3）：`build_agent_graph(...).compile()` 的 `checkpointer` 实测为 `None`；`agent/graph.py:8` 亦明确声明无 checkpointer（D11）。

**状态**：**确认（本批复现）**。后端重启后只能重跑或读历史消息。

**建议批次**：S3。

#### B13 服务端取消与客户端断线共用同一路径，落库不区分来源，不登记可接续任务

**代码依据**（本批代码确认）：`agent/service.py:476-481` 是唯一**处理并落库**的 `CancelledError` 分支——有部分输出就以 `stream_status="cancelled"` 落一条助手消息，然后原样 re-raise；不记录取消来源，不登记任何可接续的任务标识。（`:437-438` 另有一处内层 `except asyncio.CancelledError: raise`，仅用于把取消与「图执行失败」区分开，不落库、不改变上述结论。）

**复现与输入**（本批复现，R3-4）：脚本化模型返回一条「带文字 + 带 `kb_search` 调用」的 AIMessage，检索替身 `await asyncio.sleep(30)` 模拟仍在执行的长任务；消费到第一个 `delta` 后取消外层 task。

**预期 / 实际**：事件序列 `['status','start','status','delta']`；落库助手消息 `{'content': '我先查一下资料。', 'stream_status': 'cancelled'}`。取消发生时那次仍在执行的检索没有任何登记，也无从查询或接管。

**状态**：**确认（本批复现，服务端取消场景）**。**真实浏览器断线未在本批验证**——本批未起前端。两者是否走同一代码路径为代码确认，非实测。

**建议批次**：S3；`decisions.md` §3「任务结果未知：取消语义」为前置决策。

#### B14 索引 release 的固定范围只在单个请求内，docstring 的「追问不跨版本」不成立

**现象**：`HybridDpdkRetriever` 的 docstring 写「请求第一次检索后固定版本，追问不跨版本」。实际固定范围是**单个 retriever 实例**，而编排器每请求新建、无进程级缓存，所以跨请求的追问会重新解析活动 release。若期间发生了新发布，同一会话的前后两问可以落在不同索引版本上。

**代码依据**（本批代码确认）：

- `llama_index_service.py:412` docstring；`:416` `self.release_id` 为实例属性；`:422-423` 仅在 `release_id is None` 时解析一次。
- `:446` `LlamaIndexRetrievalAdapter.__init__` 每次都 `HybridDpdkRetriever()`。
- 编排器每请求新建：`api/chat.py:116`（`return AgentOrchestrator()`）、`rag_product_service.py:256`（`service = AgentOrchestrator()`）。全仓 `AgentOrchestrator(` / `LlamaIndexRetrievalAdapter(` 的构造点共 5 处，**无任何进程级缓存或复用**。

**复现与输入**（本批复现，R3-1 / R3-2）：替身接管 `active_release` / `release_configuration`（后者恒返回与当前 Settings 匹配的指纹，避开 fail-closed），注入不触库的 legacy 检索替身。实例 A 检索一次 → 期间把活动 release 从 `rel_A` 切到 `rel_B` → A 再检索一次 → 新建实例 B 检索一次。

**预期 / 实际**：A 两次均 `rel_A`（单请求内确实固定）；B 为 `rel_B`（跨请求重新解析）。两个适配器的 `_retriever` 不是同一对象。

**状态**：**确认（本批复现）**。与 `architecture_current.md` §3 的谨慎表述（「不能仅凭这一点宣称整个会话的跨请求追问永久固定同一 release」）一致，本批把它从「待明确」变为**已确定**：跨请求不固定。

**建议批次**：S2（执行边界收敛时统一证据身份）。需用户决定产品行为：会话内锁版本，还是允许追问落到新版本并在响应中显式标注 release。**同时应修正 `llama_index_service.py:412` 的 docstring**——它当前是错的。

### 核查项 7 — 历史声明核对

#### 7.1 P0.1 独立报告 F1–F7 在当前 HEAD 的适用性

代码版本等价性见 §1.4。

| 原编号 | 本报告 | 当前 HEAD 适用性 | 本批证据等级 |
| --- | --- | --- | --- |
| F1 地板超限 | B5 | 适用，行号逐行复核一致 | 代码确认 + 历史复现 |
| F2 SSE 丢评测 | B3 | 适用 | 代码确认 + 历史复现 + 触发前提本批复现 |
| F3 finalize 悬空 tool_call | B4 | 适用 | 代码确认 + 历史复现 |
| F4 引用编号不一致 | B2 | 适用 | 代码确认 + 历史复现 |
| F5 源码回显/入库 | B8 | 适用 | 代码确认 + 历史复现 |
| F6 重复评测 | B7 | 适用 | 代码确认 + 历史复现 |
| F7 完成报告表述过强 | 见 7.3 | 属文档口径问题，已随 2026-09-18 归档处理 | 本批核对文档，未重算 |

#### 7.2 `architecture_current.md` C01–C10 的核对结果

| 编号 | 本批结论 |
| --- | --- |
| C01 | **需更正**：错位为 6 处不是 2 处，且漏记了最严重的「协议列整列消失」。见 B1 |
| C02 | 成立。补充：产品路径是第三套引用口径，原条目未记。见 B2 |
| C03 | 成立。见 B3 |
| C04 | 成立。补充：悬空只来自 `route_after_planner → finalize`，`route_after_tools` 路径不产生。见 B4 |
| C05 | 成立，拆为 B5（地板超限）/ B8（回显）/ B7（重复评测）三项 |
| C06 | 成立。补充：作业提交路径（`api/assignments.py`）不经模型，**无**此问题；仅聊天路径受影响。见 B6 |
| C07 | 成立，本批由代码确认升级为**已复现**（B11/B12/B13）。harness 侧核对：`mcp_server/server.py` 只暴露 `list_protocols` / `list_cases` / `grade_plugin` / `get_reference_answer` 四个工具，**确无**任务状态查询或结果回取接口 |
| C08 | 本批处置见 7.3 |
| C09 | 成立，本批新增两条具体分歧：引用口径（B2）、消毒口径（B8）。逐分支行为仍未全面验证 |
| C10 | 成立，本批由「未完整验证」升级为**已复现**：release 固定范围（B14）、幂等身份（B9）、附件身份（B10）三项均已确定 |

#### 7.3 各历史结论的适用版本与口径

| 声明 | 适用版本 | 口径与边界 | 本批处置 |
| --- | --- | --- | --- |
| P0.1「完成」 | 实现提交 `2467724` | 与独立验收冲突；独立验收结论为**不通过** | 维持「P0.1 验收未通过」。本批未重新验收 P0.1，不改变该结论 |
| P0.1 独立验收 | HEAD `7048d8c` | 反例在原始快照与当时 HEAD 双版本成立 | 因 §1.4 等价性，结论继承至 `7227c5c`；本批未重跑其脚本（用户确认） |
| P1 全链路用量 | `7048d8c` 时点 | 该轮实测 291,804 tokens / ¥0.290780，**另有 11 条 unknown**，`text-embedding-v4` 未配价，LangChain 内部重试未保证可见 ⇒ **不是完整账单** | 本批未重新采集用量。产品路径的收口（`rag_product_service.py:338-349`）为代码确认存在，运行时未验证 |
| P2「无答案」修复 | `224f5ad`（检索摘要）+ `7048d8c`（片段去重） | P2 的 `word-boundary-v1` 与更早的子串口径不同，两种口径不可合并计数 | 本批未重跑评测。注意 P0.1 的 live 结果已包含 P2 影响，**不能倒算为原始 P0.1 的成果** |
| P0.1 live 12/15 | `7048d8c`，模型 `qwen3.8-flash`，索引 release `ccbb92e558534cbcbd5e3d3b2ee4a30d`（3737 节点），harness `9ed5cfd` | 15 次请求、同一 `local` 身份且该身份处于**升级档**（近 10 次 DNS 评测全失败、阈值 3）⇒ `feedback_first_fail_no_code` 的 PASS **不能**作为默认档验收证据 | 本批未发起任何真实模型请求，该限制原样保留 |
| CI 口径 | `.workflow/*.yml` | 主 CI 排除 `live` 与 `postgres` 两个 mark | 本批未运行 CI，也未运行任何仓库测试（原因见 B0） |
| PG 专项验证 | — | `postgres` mark 用例存在（`test_*_postgres.py` 等），但存在 ≠ 已执行 | 本批未运行。真实 PG 验收列入 §4 |

## 4. 未验证项与外部验证前提

以下均**未**在本批验证，需另行授权，各自列明范围、前提与预算口径：

| # | 待验证 | 前提 | 预算/风险 |
| --- | --- | --- | --- |
| V1 | B1 的浏览器界面实测 | 起前端 + account 模式 + 至少 1 名教师/1 名学生账号 + 1 份作业与 1 次提交 | 需真实 harness 评测 1 次（或注入合成成绩行）；无模型费用 |
| V2 | B4 的真实上游拒绝行为 | 构造悬空 `tool_call` 打真实 OpenAI-compatible 端点 | 少量真实模型调用；P0.1 明确当时未付费探测 |
| V3 | B6 的字节差异触发条件与影响面 | 真实模型复述源码 | 真实模型调用；建议与 V2 合批 |
| V4 | B2/B3/B5/B7/B8 的本轮独立复现 | 本批按用户决定按版本继承，未重跑 | 若需本轮自有日志，可执行 `.logs/acceptance_p01_20260918/reproduce.py` 与 `test_independent_contracts.py` |
| V5 | account 模式端到端、PG 权限与事务、迁移与备份恢复 | 独立临时 PG 实例（**不得**用 `assistant_dev`） | 需先按 B0 修好 fixture 隔离，否则无法安全运行 `postgres` mark |
| V6 | 真实浏览器断线与 B13 的关系 | 前端 + 手动断网/关页 | 无模型费用 |
| V7 | 用量完整性（P1 口径重校准） | 真实模型 + 账本回读 | 真实模型调用；unknown 条目需如实保留 |

另有两个**不是缺陷、但本批无法判定**的点：B14 与 B9/B10 的正确产品行为取决于用户决策（见 §5 依赖说明）。

## 5. 建议的最小修复顺序

排序依据：**可独立验收 > 阻塞后续设计 > 同路径合批 > 需先决策**。

| 序 | 批次 | 内容 | 依赖与理由 |
| --- | --- | --- | --- |
| 1 | S1-a | **B1 成绩契约**（6 处取值修正）+ 更正 `architecture_current.md` C01 | 无任何依赖。纯前端取值，不动后端契约与数据库。切到 account 模式教学闭环的成绩展示即不可用，收益最直接，验收标准也最清晰（V1） |
| 2 | S1-b | **B0 测试 fixture 隔离**（DB/上游中和移入 autouse fixture，去掉 `.env` 豁免分支） | 不是业务缺陷，但**阻塞此后每一批的取证**：不修好它，S1 之后所有批次都得像本批一样自建隔离环境，且 V5 的 PG 专项无法安全执行。改动面小、可独立验收 |
| 3 | S1-c | **B5 + B4 + B3 合批**（context 第 3/5 级改用 `_last_answered_tool_index` 口径；finalize 前剔除或补齐未配对 `tool_call`；SSE 异常分支无条件保留评测事实） | 三者同在 agent 降级/finalize 路径，改任一处都要重跑同一套黄金集，分批只会重复验收成本。顺序上 B5 在前：预算收敛修好之后，B3/B4 的触发频率与反例形态才稳定 |
| 4 | — | **决策门禁**：冻结 `decisions.md` §3 的「恢复身份与并发（幂等键与输入身份）」；同时定 B2 引用契约与 B14 release 产品行为 | 不是代码批次。B9/B10/B14/B2 都要求先有产品边界结论，实现者不得自选。**放在这里是因为它阻塞第 5、6 两批** |
| 5 | S2 | **B9 + B10 请求身份** → 随后 **B2 引用口径统一**（普通/产品两路径）+ **B14 release 范围** + 修正 `llama_index_service.py:412` docstring | 全部以第 4 步的决策为输入。身份先于恢复：S3 的步骤级恢复必须建立在一个正确的请求/任务身份上，否则恢复会精确地复原一个错误的身份 |
| 6 | S2/S3 | **B6 + B8 + B7**（源码带外化：执行输入不再来自模型参数；消毒口径统一；同任务评测去重） | 三者共同指向 N07/N05，单独打补丁会被 S3 的源码保管设计推翻。B8 中「普通路径复用 `redact` 口径」可作为其中一个独立小步先行 |
| 7 | S3 | **B11 + B12 + B13**（任务持久化、跨重启接续、取消与断线分离） | 依赖第 5 步的身份定义与第 4 步的「任务结果未知 / 展示与重连」决策；涉及后端与独立 harness 两侧（harness 当前四个工具中无任务查询接口，需先加） |

## 6. 证据位置与重建方法

本批产物：

| 文件 | 内容 |
| --- | --- |
| `docs/baseline_review_20260918.md` | 本报告（随 Git 交付） |
| `.logs/baseline_review_20260921_1530/repro_baseline.py` | R1/R2/R3 复现脚本 |
| `.logs/baseline_review_20260921_1530/eval_frontend.mjs` | 按 `main.jsx` 取值表达式求值的前端脚本 |
| `.logs/baseline_review_20260921_1530/repro.log` | Python 侧原始输出 |
| `.logs/baseline_review_20260921_1530/r1_frontend.log` | Node 侧原始输出 |
| `.logs/baseline_review_20260921_1530/results.json` | 结构化结果（含本批实际生效的隔离配置） |
| `.logs/baseline_review_20260921_1530/synthetic_api.json` | 由后端真实响应模型序列化的合成响应 |

`.logs/` 在 `.gitignore:26`，**不随 Git 交付**，因此本机日志不是唯一依据。重建方法（在仓库根目录）：

```bash
EVID=.logs/baseline_review_20260921_1530
backend/.venv/bin/python $EVID/repro_baseline.py "$PWD/$EVID"
node $EVID/eval_frontend.mjs
```

脚本自带隔离与安全闸，不依赖本机日志：进程启动前把数据库指向 `tempfile.mkdtemp()` 下的临时 SQLite，把 LLM / embedding / harness MCP 全部指向 `127.0.0.1:9`（保留端口，必然拒连），随后用 5 条 `assert` 校验这些替身确实生效，任一不满足即中止。R3 另用 `BaseChatModel` 脚本化替身与 `_noop_mcp`，不存在网络路径。学生源码全为合成字符串。

§1.4 的版本等价性可用 `git diff 7048d8c..7227c5c -- backend/app` 独立重算。

## 7. 本批状态

| 状态栏 | 记录 |
| --- | --- |
| 实现/产物完成 | 是。产物为本报告 + §6 的六个证据文件。**未修改任何业务代码、既有测试、依赖或配置**；工作区内三份既有文档改动保持原样 |
| 自动验证 | 本批自建脚本两次执行（第一次 10 项中 9 确认 / 1 未复现；修正 R2-3 的幂等键格式后重跑，10 项全部确认）。环境：`backend/.venv` Python 3.11.15，临时 SQLite，全部上游替身化。证据见 §6。**未运行仓库自带测试集**（原因见 B0），未运行 ruff、前端 vitest/build、迁移、索引或真实模型 |
| 场景验收 | **未做**。本批未起浏览器、未起后端服务、未走 account 模式端到端。B1 的结论是取值层确认，不是界面实测 |
| 用户验收 | 待验收 |
| 下一批授权 | 未授权。本报告不构成 S1 开工许可 |

本批范围经用户 2026-09-21 确认收窄（见 §2）。七个核查项全部有处置，但证据等级按项区分，未复现与未验证项已在 §3、§4 逐条标明，不以「全部确认」掩盖未跑的部分。
