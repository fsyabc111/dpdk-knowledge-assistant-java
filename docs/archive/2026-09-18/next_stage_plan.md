> 历史归档：2026-09-18。原路径：`docs/next_stage_plan.md`。
> 适用版本：正文各条记录注明的日期、提交与环境；归档所据助手仓库快照为 `7048d8c`，不代表整篇已在该版本复验。
> **不再作为当前实施指令。** 正文保留当时的决策、完成声明和测试数据；未注明版本或存在冲突的结论不能外推为当前验收结果。
> 当前入口：[文档导航](../../README.md) · [统一路线](../../roadmap.md) · [当前决策](../../decisions.md)。
> 正文中的纯文本旧路径与行号用于还原历史上下文；本次仅修正可点击的相对链接。

# 后续实施计划与本地开发准备

日期：2026-09-17。代码基线：`main / 495432d`。
本文安排后续工作的顺序与验收条件，不替代 `agent_upgrade_plan.md` 的 D1–D15
或 `agent_m4_decisions.md` 的既有决策；调整既有决策时仍须同步其事实来源。

## 1. 当前基线

- RAG A、B1–B3 已收口，C 的检索主路径与 ingest 幂等测试已落地，E 首批完成。
- Agent M1–M3、M4a 已完成；M4b/M4c 尚未整体完成。
- 产品化问答、问题闭环、共享知识、账号班级树和作业管理已有实现。
- 46 题检索回归全绿是既有部署实验记录，不代表独立答案验收已完成。
- 100 题候选集的人工标注、全链路费用、持续压测和首班灰度仍待完成。
- 本地环境与生产部署分开；本计划不自动开启产品灰度或触发批量模型调用。

## 2. 实施顺序

| 批次 | 范围 | 交付与验收 |
| --- | --- | --- |
| P0 本地环境 | Python 3.11、Node、PostgreSQL 17 + pgvector、前后端依赖 | 镜像下载、独立开发库、默认回归与构建、健康检查、启动说明 |
| P0.1 新模型适配（已完成 2026-09-17） | 上下文裁剪、预算降级、教学反馈 | 复现本地 live 失败；确定性回归与真实小样本复测；不关闭护栏或放宽断言掩盖问题 |
| P1 E 用量与成本（代码已收口 2026-09-17） | 模型 usage 解析与传播、分阶段记录、价格配置 | 缺失用量显式标记；阶段汇总可解释；不把缓存命中或重放重复计费 |
| P2 答案验收（离线工具已收口 2026-09-17） | 小样本校准后完成 100 题人工审核 | 事实与引用盲审、拒答/澄清分类、固定环境基线、失败归因 |
| P3 稳定性与灰度 | 持续负载、故障、备份恢复与回滚 | 20 路 30 分钟压测、429/超时演练、恢复验证、首班灰度结论 |
| P4 D 数据层收敛 | PG 测试替代 SQLite、删除 legacy 支持 | PG CI 门禁先通过，迁移与历史引用验证，再独立删除旧实现 |
| P5 条件性增强 | B4 rerank、M4b、K 教学资料 | 由真实失败和使用需求触发，独立实验、基线、回退与交付结论 |

P1 期间可以准备 PG 测试基础设施；SQLite 删除等待 P2/P3 验收收口。
每批单独提交、验证和记录结果，不把成本统计、状态持久化和数据库删除混成一次改造。

### 2026-09-17 更新：P0.1 已完成，当前执行 P1

P0 已完成本机安装与链路验证；3737 节点全量索引已发布并通过幂等复测。
生成模型现为 `qwen3.8-flash`，embedding 保持 `text-embedding-v4` / 1024 维。

P0.1 收口结果：正式 live 集 **10/11**（基线 6/11），基线 4 项
`agent_context_budget` 失败与 1 项围栏越界全部转 PASS，**零回归**；
默认门禁 475 passed、覆盖率 84.77%，黄金场景 36 passed，ruff 全绿。
实现与根因见 `docs/agent_m4_decisions.md` §6，本轮实测与残留风险见
[下一位 Agent 交接](next_agent_handoff.md) §4。要点：

- 上下文准备收敛为 `agent/context.py` 的分级管线（够用即停、配对不变式、
  地板溢出转 `ContextBudgetExceeded` 而不裸抛）；
- 降级答案保留已完成评测的评分事实、消毒提问中的代码围栏；
- 默认反馈档与索要参考答案档硬化禁围栏，升级档与骨架档不变。

**唯一仍失败项 `route_kb_engine_ops` 已定因为 P2 范畴**，不是 P0.1 残留：
证据在语料中存在（39+ 片段含 `can_handle`）、检索可达（模型本轮实际查询可召回
`can_handle` 位于第 938 字符的片段，在 1500 字符可见窗口内），但被 E5 的
「top-2 片段 × 300 字符」压缩丢弃——本轮 14 次 `agent_context_trimmed` 全部停在
level 2，连最近一轮也被压。该压缩是「先按名次取 top-2 再截断」，
**名次靠后或偏移 >300 的命中不可恢复**。此项作为 P2 的首个已知输入，
不得为跑绿放宽 rubric。

P0.1 不涉及 M4b、不删除 SQLite、不开产品灰度。可直接转交的 P1 开发提示词见
[下一位 Agent 交接](next_agent_handoff.md)。

## 3. P1：全链路用量与成本

1. 为 `llm_client` 的 chat、stream_chat、embed 保留上游 usage。流式接口按兼容性
   处理 usage 尾帧；缺失字段用未知态，不把 0 当实测，也不本地估算 token。
2. 覆盖 rewrite、embedding、planner/finalize、judge、会话摘要和 ingest；核查
   LlamaIndex 与 LangChain 调用路径，避免重复记账或遗漏。重试失败后上游未返回
   的用量保持未知，不能声称费用精确完整。
3. 在 Settings 中提供模型单价、币种及价格版本/生效信息；价格需有可追溯依据，
   未配置价格时仅展示用量，不编造费用。同步开发和部署环境模板。
4. 定义请求与阶段记录契约，新增 alembic revision，同步 PG/SQLite schema；
   本批仍遵守双业务存储支持。凭据、源码和完整 prompt 不进入指标标签或账单。
5. 默认回归覆盖 usage 缺失、流式尾帧、取消、重试、缓存、幂等重放与阶段汇总；
   用小规模真实调用校准后，才安排批量答案验收预算。

P0.1 结束时的真实用量观测（可作 P1 的输入与校准起点）：本轮 live 共 15 次
chat_trace（探针 5 + 全量 10 次生成请求；`guard_oversize_upload` 不调模型），
`tokens_used` 合计 283073，最小 6198 / 中位 16634 / 最大 43058。
最大值已达 `AGENT_REQUEST_TOKEN_BUDGET=48000` 的 90%——M4a 遗留的
「48K 阈值按真实分布校准」现已具备数据条件（运维动作，不改代码）。
注意 `tokens_used` 只记生成模型，尚未覆盖 embedding/harness/MCP，
这正是 P1 要补齐的部分，不要把未计量当作 0。

### 2026-09-17 更新：P1 代码与确定性测试已收口，唯一真实调用待执行

P1 分四步落地，前两步独立提交（`ff41625` 捕获层、`73fb1c6` 存储与价格），
后两步（`P1.3` 全链路接线、`P1.4` 只读 API 与文档）在同一个未提交批次里。
上述 1–5 条全部落地，逐条对照：

1. `llm_client` 的 chat/stream_chat/embed 已保留上游 usage（`usage`/`stage` 为
   **可选**参数，返回值与异常语义逐字不变）；流式按开关带
   `stream_options.include_usage` 并解析尾帧，**上游以 400 拒绝该字段时去掉它重试
   一次并记 unknown**——不允许因为要计量而让请求失败。
2. 已覆盖 rewrite、追问解析（**两个独立 stage**）、query embedding、planner/finalize、
   judge、会话摘要、answer（legacy 与 LlamaIndex 两条）与 ingest；取消/重试耗尽/
   未预期异常一律在 `finally` 补一条 unknown。**不声称费用精确完整**：
   `ChatOpenAI` 的重试在 langchain 内部，逐次失败的上游消耗不可见，重试过的调用
   实际支出可能高于账面。
3. `LLM_PRICES_JSON`（单价用字符串，Decimal 精确解析）、`LLM_PRICE_VERSION`、
   `LLM_USAGE_RECORDING_ENABLED`、`LLM_STREAM_USAGE_ENABLED` 已进 Settings；
   成本在**写入时**算成整数微单位并盖价格版本，调价不回溯改历史账；未配价只出用量。
   两处 `.env.example` 同步。
4. 新表 `upstream_usage_records`（`usage_schema.sql` 双后端同源 + alembic `0011`），
   **没有**给 `rag_requests` 加列——agent 路径与 ingest 根本没有 `rag_requests` 行。
   表内不含问题、prompt、源码或凭据。
5. 默认回归新增 `test_usage_wiring.py`（15 例）与 `test_usage_cost_api.py`（13 例），
   覆盖 usage 缺失、流式尾帧、400 退字段重试、取消、重试、缓存命中、本地规则、
   幂等重放与阶段汇总；PG 专项新增 `subjects IN` 过滤用例。

**唯一需要真实调用的一步已执行（2026-09-17）**：按 P0.1 的规矩先说明了范围——
1 次最小流式调用（一句短问，`max_tokens` 压到 96），不跑批量题库、不重复 embedding。
结果：上游**接受** `stream_options.include_usage` 并回 usage 尾帧，首帧即成功、
未走 400 退字段重试（实际只发出 1 个 payload，`call_count=1`）；尾帧
`prompt_tokens=72 / completion_tokens=79 / total_tokens=151`，`usage_source=upstream`，
是真实数值而非 unknown；经 `flush_usage_recorder` 落 PG（开发库，migration 0011）1 行，
`psql` 直查一致，`cost_micros` 为 **NULL**——本地未配价格，**账本没有编造金额**。
本次总花费 151 tokens。日志 `.logs/p1_stream_calibration.log`；校准脚本一次性，
故意不落进仓库。

该结论**只覆盖 `qwen3.8-flash` 与本次上游**：换模型或换 OpenAI 兼容实现
（自建 vLLM/SGLang 等）须重新校准，不能外推成「任何上游都回尾帧」。
`ChatOpenAI` 内部重试的逐次用量仍不可见——重试过的调用实际支出可能高于账面，
故仍**不声称费用精确完整**。

门禁（本机实测）：默认集 **581 passed / 96 deselected，覆盖率 86.26%**；
PG 专项（`RAG_TEST_DATABASE_URL=postgresql:///assistant_rag_test`）**4 passed**；
黄金场景 **36 passed**；`ruff check .` 全绿。本批**不碰前端**，前端 63 例未用于验收本批。

### 2026-09-17 更新：P2.0 离线工具已收口，真实小样本校准待执行

P2 分三步：**P2.0 离线（本批，零真实调用）**、P2.1 小样本校准（10–20 题，真实调用，
需先批预算）、P2.2 100 题（另批预算）。P2.0 已交付：

1. **引用级盲审可执行**：`product_acceptance_runner.py --evidence <run.json>` 逐条引用
   并排打印「论断句 + 证据正文」，证据经应用既有的历史引用取数路径
   （`li_` → `get_llama_index_source`，其余 → `RetrievalService.get_chunk`）取全文，
   **不新增取数路径、不加表、不改对外契约**。盲审语义明确为**归因核对**（这条引用是否
   支撑它所在的那句话），**不要求教师为 100 题写 gold answer**——这是让盲审真正可执行的
   关键取舍；`gold_answer`/`gold_citations` 只是题集可选键。
2. **评分 schema 只增不改**（老评分文件仍合法）：新增 `unsupported_citation_indexes`
   与 `missing_evidence`；越界编号**报错而不是静默丢弃**。
3. **门禁与退出码**：7 个阈值门禁默认不设线；人工评分覆盖率不足 100% ⇒ `incomplete`
   且拒绝判定（把免责声明变成可执行门禁）；无门禁参数恒退出 0（不破坏既有调用）；
   比率类指标无样本为 `null`，门禁按未满足处理，**不当 0 通过**。
4. **基线**：`--write-baseline/--baseline` 落 `backend/data/product_acceptance/baseline.json`
   （已 gitignore），存教师确认的标签、人工判定与指标。当前既无 run 也无基线 ⇒ P2.1 建立第一份。
5. **E5 压缩粒度修复**（`route_kb_engine_ops` 的已知输入）：kb_search 摘要改为**覆盖式**
   （每片段 head + tail + 高信号行，TOP_CHUNKS 2→3，补回 title，单片段 320 字符有界），
   把「前 2 名 × 前 300 字符」这个位置矩形之外不可恢复的缺陷修掉；**不动分级阶梯、
   配对不变式、L1 语义与 L5 保护**。压缩不能靠「干脆不压」通过测试（有有界断言）。
6. **live 口径收紧 + 逐题用量**：`required_terms` 改词边界匹配（`process` 不再被
   `packets_processed` 满足，P0.1 的 10/11 是**旧口径**下的数，两口径不可直接比）；
   live 报告按 `request_id` 回读 P1 账本（`/api/usage/cost` 新增可选 `request_id` 过滤，
   只收窄、仍受树隔离约束），读不到只记 `usage_error`、不影响 PASS/FAIL。

收尾验证另修两处（不改口径、不放宽断言）：`--evidence` 按文档调用时缺 `sys.path`
自举导致整篇清单取不到证据正文；`test_rag_versioned_postgres.py` 不自清产物导致
同一测试库跑第二遍必失败（看似发布事务缺陷）。均已在
[下一位 Agent 交接](next_agent_handoff.md) §6.4 与 `local_development.md` 记录。

**P2.1 的执行前提（都要先批）**：本地临时 `RAG_PRODUCT_ENABLED=true` 重启（跑完回退，
这不是开启产品灰度）；`--run --sample 15` 覆盖四类标签；按新 schema 评分 + `--evidence`
核对 + `--summarize` 出门禁结论；从账本读本轮真实花费。**跑之前先调大单题超时**
（`--timeout`/`RAG_EVAL_TIMEOUT_SEC`，现默认 150s，而单题实测 22–156s，不调会把最慢的题
记成假 `system_error`）。同轮可复跑 live 11 题，看 `route_kb_engine_ops` 是否转 PASS，
并单列口径收紧带来的数字变化。

### 2026-09-17 更新：P2.1 小样本已跑，结论是「先修两个阻断缺陷」

15 题真实跑批（`--sample 15 --timeout 300`）**0 题 answered**：10 unanswered
（9 题 `evidence_budget`）+ 5 system_error（3 judge 超时 + 2 生成失败）。
所以**盲审与门禁结论都不成立**（没有真实答案就没有引用可核对），`baseline.json` 未建。
这一轮交付的是**归因**，两个缺陷互相独立、都已实测：

- **R1 证据预算被打爆**：`chunks=72 / evidence_chars=69958 vs limit=24000`。根因是每轮
  `kb_search`（limit=8）全量追加进 `collected_chunks`、**不去重不设上限**（
  `app/agent/tools.py:116-117`）——累积无界，**不是「预算值定小了」**。60% 的题在
  检索已成功的前提下被判「资料不足」。附带缺陷：`citations` 取全部 chunks、evidence
  只装一部分，两者不同源。
- **R2 judge 超时**：`rag_validation_timeout_sec=20` 对最大 24,000 字符证据太紧，
  到达校验的请求几乎无一通过。

**不做的事（明确）**：不改预算值、不改检索/引用策略；不拿「调大预算」当默认
答案；不因跑绿放宽 rubric。临时开关已回退 `RAG_PRODUCT_ENABLED=false`。

**同日二轮：R1/R2 已修，且 R2 的超时上限按实测重定**（详见
[下一位 Agent 交接](next_agent_handoff.md) §6.6）：

- R1（去重复用原编号 + `bound_evidence` 只缩正文不丢片段）**在生产上证实生效**。
- R2 的结构性缺陷：judge 内层 `timeout=90 × 4` 被外层 20s 罩住（重试逻辑不可达），
  且 judge 拿独立 20s ⇒ 最坏 140s > 请求墙 120s，**两个墙同时到期、归因看竞态**。
  改为 `judge_budget = min(上限, 请求剩余)`；上限按实测上游分位 20 → 60
  （真实调用 p50 13.1s / p90 45.3s / max 46.8s，20s 落在 p90 之下 ⇒ 对 5/12 次必然误判）。
  新增 `answer_generation_ms` / `validation_ms` 埋点与失败日志的 `request_id`。
  **这不是「调大到不超时」：调到 60 秒后 failures 依然存在**（1 次撞满 60s、1 次撞 120s）。
- **answered 没有回来**（二轮 3 题仍 2 题 system_error）。超时只是症状；真因是 judge 要读
  **27–37 个片段**——`collected_chunks` 在**去重之后仍无界**，用户选定的**「名次上限」
  尚未实施**。降 judge 输入条数是检索质量取舍，**待拍板**。
- **重跑 15 题前先确认预算**：实测单题 ¥0.014–0.056 ⇒ 15 题 ≈ **¥0.42**，不是先前说的 ¥0.30。

**同时修好并实测了产品路径的用量收口（D1/D2）**：此前产品路径既不落账（账本 0 行、
`chat_trace` 0 条）也不复用 API 的 `request_id`（trace 自造随机 `trace_xxx`）⇒ P1 的
「全链路」在产品流量上不成立、逐题回读账本永远匹配不上。修复后 1 题真实调用可精确回读
（7 条 / 5 次调用 / 10,890 tokens / ¥0.0108）。本批真实花费：修复后 4 题 **¥0.0756**（精确）；
修复前 15 题无账本行，估算 ≈¥0.30，**是估算不是实测**。详见
[下一位 Agent 交接](next_agent_handoff.md) §6.5。

**下一步顺序**：R1/R2 已修（见上）。**先拍板 judge 输入条数**（名次上限定 N / 抬高请求墙 /
接受一定比例 system_error），因为它现在是唯一未动的瓶颈；然后按 ≈¥0.42 批预算重跑同一
`--sample 15`，再谈盲审与 100 题。

## 4. P2/P3：质量和上线验收

- 先抽取覆盖可回答、证据不足、澄清、对抗的少量题，人工校准标签与评分口径，
  再完成 100 题；自动分类与人工结论继续分列。
- 固定代码、模型、索引 release、题库和检索参数，保存可复现报告。
- 对失败分别判断召回遗漏、证据不足、生成错误、误拒答和系统错误。
- 优先修复 Agent live 集的反馈纪律问题，再根据失败扩充 M4c 回归，避免仅追题数。
- 按真实耗时分布设置 avg/P95 门禁；压测与批量调用先明确预算。
- 在独立环境验证迁移、索引发布/回滚、备份恢复、权限和历史引用，再做首班灰度。
- 验收未完成前保持 `RAG_PRODUCT_ENABLED=false`；单 worker admission 的边界不变。

## 5. M4 的取舍

### M4a：保留并校准

保留 token 预算、上下文修剪、画像、MCP 重试和注入防护。用实际用量分布校准
48K 阈值；token 护栏不能替代全链路费用统计。

### M4b：暂缓，保留路线

当真实场景需要高风险确认后继续，或跨进程恢复能够减少明显的重复工作时再启动。
启动前必须：

- 决定粘贴源码的持久化规则，上传和粘贴路径均满足 D12 的源码最小化要求。
- 先完成源码带外传递，再接 `AsyncPostgresSaver` 与 interrupt/resume。
- 明确附件过期/缺失后的恢复行为，恢复时重新核对用户、班级权限与配额。
- 明确评测幂等边界：结果未知的调用不得自动重放；checkpoint 不等于 exactly-once。
- 覆盖同会话并发、重复 resume、进程中断、清理与前端待确认态。

### M4c：按问题分批提前做

现在补反馈纪律、usage 缺失、预算与注入回归；interrupt/resume 用例随 M4b 实施。
扩充 live 题库与 LLM-judge 时保留人工终审，不以用例数量替代质量结论。

## 6. SQLite 退出条件与步骤

生产 LI 数据面已要求 PG。后续不增加 SQLite 专属能力，但正式删除前仍维护现有契约。
当前 CI 排除 postgres 测试，且业务测试依赖 SQLite fixture，不能直接删除 provider。

1. 准备可重复创建的 PostgreSQL 17 + pgvector 测试库，加入 CI；测试不得访问生产库。
2. 将权限、作业、请求幂等、知识发布、事务和 migration 覆盖迁到 PG；保留无数据库
   的纯逻辑单元测试。验证全新初始化、旧库升级以及事务失败后的行为。
3. 确认所有使用中的 SQLite 数据已迁移或明确归档，备份可恢复；保留可运行的旧版本
   作为迁移窗口回退手段。
4. P2/P3 验收通过后，独立移除 SQLite provider、配置分支和 legacy 检索入口，
   同步文档/CI/依赖。若接口抽象仍有价值，不为减少文件数而强行删除。
5. 核查历史引用后才通过新 migration 清理 `knowledge_chunks` 等旧表；新版索引
   仍复用 `ingestion_service.py` 的 reader/chunker，必须保留或先迁出这些组件。

完成标准：默认与 PG 门禁通过，初始化/迁移/历史引用/回退验证完整，无运行环境仍依赖 SQLite。

## 7. 本地环境约定

- 后端使用独立 Python 3.11 虚拟环境；前端使用满足 Vite 7 要求的现有 Node。
- Python 包优先阿里云 PyPI 镜像，npm 使用 npmmirror，Python 运行时使用可用镜像。
- 开发数据库与旧本地数据库隔离；应用仅监听 `127.0.0.1`。
- `.env`、虚拟环境、日志与本地数据库备份不提交；保留既有模型凭据。
- harness 为独立仓库并通过隔离容器运行，缺少仓库/容器运行时不在宿主机替代执行学生代码。
- 安装结果、版本、启动停止命令和未完成的外部依赖记录在 `docs/local_development.md`。
