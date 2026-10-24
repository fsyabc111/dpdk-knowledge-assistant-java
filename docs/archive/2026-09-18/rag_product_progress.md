> 历史归档：2026-09-18。原路径：`docs/rag_product_progress.md`。
> 适用版本：正文各条记录注明的日期、提交与环境；归档所据助手仓库快照为 `7048d8c`，不代表整篇已在该版本复验。
> **不再作为当前实施指令。** 正文保留当时的决策、完成声明和测试数据；未注明版本或存在冲突的结论不能外推为当前验收结果。
> 当前入口：[文档导航](../../README.md) · [统一路线](../../roadmap.md) · [当前决策](../../decisions.md)。
> 正文中的纯文本旧路径与行号用于还原历史上下文；本次仅修正可点击的相对链接。

# RAG 产品化实施与验收记录

接手入口与已知风险优先级见 [RAG 开发交接](rag_handoff.md)。

## 已实现的代码

- migration 0008：共享业务 DDL，PG / SQLite 请求、问题、发布记录同构。
- LlamaIndex 单数据面四路召回；版本固定、UUIDv4 节点、实际正文及元数据比较、先嵌入后原子发布、CAS 冲突保护、历史节点保留。
- `auto_ingest.sh` 不再依赖 Git HEAD，扫描全部知识来源；共享知识也由同一索引入口处理。
- `RAG_PRODUCT_ENABLED`：灰度接入统一聊天服务，先保存请求，正文校验后展示，状态与回答事务保存，SSE 仅提前发进度。
- 未解答/部分解答/澄清/系统错误归类；用户反馈；老师本班问题、组长全部问题；认领、备注、合并、知识关联、原题验证、解决/忽略。
- 老师直接发布共享知识，显式全体可见确认、作者权限、修订冲突检查、撤回和历史修订。
- 单 worker 20 活动请求、20 等待请求、10 秒排队时限；请求与校验超时、工具循环 token/上下文护栏、上游并发限制。
- 180 天留存清理、异常中断归类、账号/会话脱钩；`/api/ready` 检查数据库与活动索引；结果和耗时指标。
- migration 0011 + `upstream_usage_records`：全链路上游用量账本（生成/embedding 计费，
  harness/MCP 只记次数与耗时）；`GET /api/usage/cost` 只读聚合，token 与非 token 分列。

## 部署顺序

1. 保持 `RAG_PRODUCT_ENABLED=false`，备份数据库，构建新 backend/frontend。
2. `alembic upgrade head`，首次安装先 `init_db()`；0008 新增业务表，不删除历史索引。
3. `python scripts/ingest_llama_index.py`。首次 UUID 版本需要重新 embedding，不自动调用真实模型；先估算费用。重复运行逐项比较正文/配置，不重复 embedding。
4. 用独立验收题和人工审核验证答案，再开启 `RAG_PRODUCT_ENABLED=true`。保留单 uvicorn worker。
5. 启动全源扫描 cron（推荐每 5 分钟）；知识条目显示已提交后，要等待索引成功才对问答生效。数据库写入成功不等于索引已生效。
6. 内网监控 `/metrics` 和 `/api/ready`。`python scripts/rag_maintenance.py` 默认只读；`--apply-retention` 清理，`--rollback-release ID` 切换旧发布版本。

## 验证记录与边界

- 2026-09-17 P2.1 真实小样本跑批（**本批唯一一批付费调用**：15 题 + 修复后 1 题 + 3 题抓诊断）：
  - **15 题 0 题 answered**：10 unanswered（9 题 `evidence_budget`）+ 5 system_error
    （3 judge 超时、2 生成失败）。**这一轮是归因，不是质量结论**——没有真实答案就没有
    引用可核对，盲审与门禁都不成立，`baseline.json` 故意没建。
  - **R1 证据预算被打爆（实测）**：`chunks=72 / chunk_chars=67651 / tool_results=1 /
    evidence_chars=69958 vs limit=24000`。根因是每轮 `kb_search`（limit=8）把结果**全量
    追加**进 `collected_chunks`（`app/agent/tools.py:116-117`），**不去重不设上限** ⇒
    累积无界，不是「预算值定小了」。`tool_results` 只有 1 条，排除「单个工具结果过大」。
    后果：60% 的题在**检索已成功**的前提下被判成「资料不足」（`evidence_budget` 是硬
    UNANSWERED）。另：`citations` 由**全部** chunks 生成、evidence 只装得下一部分，
    两者不同源（实测出现过 24 条引用），修 R1 时必须一并钉死。
  - **R2 judge 超时**：`rag_validation_timeout_sec=20` 对最大 24,000 字符证据太紧。
    到达校验的请求几乎无一通过（15 题里 3 次；修复后 3 题里 2/2）。与 R1 互相独立。
  - **R1/R2 已修 + 二轮 3 题实测（同日）**：R1（`kb_search` 去重复用原编号 + `bound_evidence`
    只缩正文不丢片段）**在生产上证实生效**——三条 `rag_evidence_bounded` 均为
    `distinct == chunks`（37/37、27/27、34/34）且 `kept_chars <= 24000`。
    R2 修的是**结构性缺陷**：judge 内层 `timeout=90×4` 被外层 20s 罩住（重试逻辑不可达），
    且 judge 拿独立 20s ⇒ 最坏 140s > 请求墙 120s，**两个墙同时到期、归因看竞态**。
    改为 `judge_budget = min(上限, 请求剩余)`，上限按**实测上游分位** 20 → 60
    （真实调用 `rewrite_ms` n=12：p50 13.1s / p90 **45.3s** / max 46.8s，20s 落在 p90 之下）。
    新增 `answer_generation_ms` / `validation_ms` 埋点与失败日志的 `request_id`。
    **但 answered 没有回来**（2/3 仍 system_error：1 次撞满 60s、1 次撞 120s 请求墙）——
    超时只是症状，真因是 judge 要读 **27–37 个片段**：`collected_chunks` 在**去重之后
    仍然无界**，而用户选定的**「名次上限」尚未实施**（R1 只做了去重 + 缩正文）。
    降 judge 输入**条数**是检索质量取舍，待拍板（交接文档 §6.6）。**重跑 15 题前先确认预算**：
    实测单题 ¥0.014–0.056，15 题 ≈ **¥0.42**，不是先前说的 ¥0.30。
  - **D1/D2 已修并实测**（产品路径的用量收口）：修复前产品路径 `chat_trace` **0 条**、
    账本 **0 行**（`flush_trace_usage` 只挂在两条 legacy `_log_trace` 上），且
    `RequestTrace.request_id` 是随机 `trace_xxx`、与 API 的 `request_id` 无关 ⇒
    P1 的「全链路」在产品流量上不成立、P2 §4 的逐题回读**永远匹配不上**。
    修复后 1 题真实调用：`chat_trace` 1 条、账本 **7 条 / 5 次调用 / 10,890 tokens /
    ¥0.0108**（价格版本 `2026-09-17-user-provided-no-bill-source`），按 `request_id`
    精确回读。顺带修 `events()` 的 `start` 帧在 `try` 之外导致客户端断连时 `run()`
    成为孤儿任务。**legacy 路径本来就自洽**（SSE 回显 `trace.request_id`、账本同键）。
  - **本轮花费**：修复后 4 题 **¥0.0756**（账本精确）；修复前 15 题无账本行，
    估算 ≈¥0.30（区间 ¥0.24–0.80），**是估算不是实测**。
  - 新增可观测性：超预算时打 `rag_evidence_budget_exceeded`（`chunks`/`distinct_chunks`/
    `chunk_chars`/`tool_results`/`evidence_chars`/`limit`）。`distinct_chunks` 是下一步
    要读的数：它决定「去重」能否单独救回预算。
  - 临时开关已回退 `RAG_PRODUCT_ENABLED=false` 并重启。**注意**：开关开着时默认集门禁
    会出现 85 failed / 55 errors，全部是 `Settings` 的 llama_index 数据面校验，不是回归。
- 2026-09-17 P2.0 答案验收工具（**离线完成，未发起任何真实模型调用，未产出任何答案质量结论**）：
  - **盲审可执行**：`--evidence <run.json>` 逐条引用并排打印「论断句 + 证据正文」，
    证据经应用既有的历史引用取数路径取全文（`li_` → `get_llama_index_source`，
    其余 → `RetrievalService().get_chunk`），取不到时在清单上显式标出。**盲审语义是
    归因核对**（这条引用是否支撑其所在的那句话），**不要求教师写 gold answer**。
  - **评分 schema 只增不改**（旧评分文件仍合法）：新增 `unsupported_citation_indexes` 与
    `missing_evidence`，越界编号**报错而不是静默丢弃**。
  - **门禁与基线**：7 个阈值 flag，人工评分覆盖率 <100% ⇒ `incomplete` 且拒绝判定，
    无门禁参数恒退出 0；比率类指标无样本为 `null`（未知不当 0）。
    `--write-baseline/--baseline` 存教师确认标签与判定，供后续 run diff。
  - **E5 压缩粒度修复**（`route_kb_engine_ops` 的已知输入）：kb_search 摘要由
    「top-2 片段 × 前 300 字符」改为覆盖式（head + tail + 高信号行，TOP_CHUNKS 2→3，
    补回 title，单片段 320 字符有界），**不动分级阶梯、配对不变式与 L5 保护**；
    `test_s29` 的旧断言锁定的是被修掉的有损行为，已改写为在「能否找回证据」维度上
    更强的断言（不是删断言换通过率）。
  - **观测修正**：`agent_context_trimmed` 改为每级都记（含 L1）并带 `applied`——
    旧口径把「L1 就够」系统性隐掉，使「14/14 全为 L2」无法解释；新旧日志分布不可直接比。
  - **口径收紧**：live 的 `required_terms` 改词边界匹配（`process` 不再被
    `packets_processed` 满足），P0.1 的 10/11 是旧口径下的数；报告与基线都带口径版本，
    跨口径对比先打 `[口径变更]`。live 报告新增逐题 token/成本（按 `request_id` 回读 P1
    账本）与结构化失败分类，**只增字段**；读账失败只记 `usage_error`，不影响 PASS/FAIL。
  - **门禁**：默认集 **670 passed / 96 deselected，覆盖率 86.17%**；`tests/rag_eval/`
    **127 passed**（原 83）；新增 `tests/agent/test_live_eval_scoring.py` 29 例；
    黄金场景 36 passed 无回归；ruff 全绿；PG 专项在同一测试库连跑两遍 5 passed。
    另修一处真缺陷：`false_reject_rate` 分子原先
    取全局人工分类计数而分母只取可答题数 ⇒ 比率可**超过 100%**，已改为分子分母同源并加断言。
  - **收尾验证又修两处**（均留承重回归，2 个变异全部被捕获）：① `--evidence` 按文档
    调用时 Python 只把脚本目录进 `sys.path`（不含 cwd），`import app` 报
    `ModuleNotFoundError`，整篇盲审清单退化成「取证据正文出错」——已加自举的
    `_ensure_app_importable()`（用子进程用例守，进程内测不出来）；②
    `test_rag_versioned_postgres.py` 原先假定库是新的，**同一库跑第二遍必失败**，
    看着像发布事务坏了，实为测试未自清产物——已改，并在已污染的库上连跑两遍验证。
  - **仍未闭环**：`--run` 一次没跑，`backend/data/product_acceptance/` 仍不存在 ⇒ 本批
    交付的是**能力**，不是**结论**；耗时门禁不预设数值，要用真实小样本分布定线；
    E5 修复只是离线可证，`route_kb_engine_ops` 是否转 PASS 要 P2.1 真实复跑才知道。
  - 本批不碰 `frontend/`；`RAG_PRODUCT_ENABLED` 保持 `false`。

- 2026-09-17 P1 全链路用量与成本（**离线完成，未发起任何真实模型调用**）：
  - **实现**：`app/core/usage.py`（记录层，纯逻辑）、`llm_client` 三方法 + LlamaIndex/
    LangChain 两条旁路的 usage 保留、`upstream_usage_records`（`usage_schema.sql` 双后端
    同源 + alembic 0011）、价格配置与写入时定价、`app/services/usage_service.py`
    （纯函数聚合 + 落库收口 `flush_usage_recorder`）、全链路接线、`GET /api/usage/cost`。
  - **四条语义**（测试锁死）：未知不是 0（token 为 NULL）；零调用要留痕
    （`cache_hit`/`replay`/`local` 记 `call_count=0`，且**不计入**「账没算清」）；
    未定价不出价（`cost_micros=NULL` + `unpriced_models`）；不跨币种求和。
    成本在写入时算成整数微单位并盖 `LLM_PRICE_VERSION`，调价不回溯改历史。
  - **取消覆盖**：落库挂在既有 `finally: _log_trace`，因此客户端断开 / judge 超时时
    已发生的花费仍然落库；在途取消记一条 unknown（请求已发出，上游可能已经算过钱）。
  - **门禁**：默认集 **581 passed / 96 deselected，覆盖率 86.26%**（基线 475 / 84.77%）；
    PG 专项 **4 passed**（专用库 `assistant_rag_test`）；黄金场景 **36 passed** 无回归；
    ruff 全绿。新增 `test_usage_wiring.py` 15 例、`test_usage_cost_api.py` 13 例。
  - **真实校准调用（已执行，全批唯一一次付费调用，2026-09-17）**：上游**接受**
    `stream_options.include_usage` 并回 usage 尾帧——1 次最小流式调用（一句短问，
    `max_tokens` 压到 96）即成功，未触发 400 退字段重试；实际发出 1 个 payload、
    `call_count=1`；尾帧 `72/79/151` tokens，`usage_source=upstream`（真实数值，
    非 unknown）；`flush_usage_recorder` 落 PG（开发库，migration 0011）1 行，
    `psql` 直查一致，`cost_micros` 为 NULL——本地未配价格，**账本没有编造金额**。
    本次总花费 151 tokens，未跑题库、未跑 embedding、未重建索引。
    日志 `.logs/p1_stream_calibration.log`；校准脚本故意不落进仓库。
  - **仍未闭环**：`ChatOpenAI` 内部重试的逐次用量不可见（重试过的调用实际支出可能
    高于账面，故不声称费用精确完整）；价格表尚未由运维给出可追溯来源；该结论只覆盖
    `qwen3.8-flash` + 本次上游，换模型/换兼容实现须重新校准。
  - **能力边界**：`ChatOpenAI` 内部重试的逐次用量不可见，重试过的调用实际支出可能高于
    账面，因此**不声称费用精确完整**；harness/MCP 的成本在外部服务内，只记次数与耗时、
    不折算费率；价格须由运维提供可追溯来源，未配置时只出用量。
  - 本批不碰 `frontend/`，前端 63 例与 `client.test.js` 未用于验收本批。

- 2026-09-13 E 阶段首批：可复现评估 + 答案验收工具 + 耗时观测切片（离线完成，无真实模型调用）。
  - **实现完成**：
    - 检索评估 runner（`tests/rag_eval/rag_eval_runner.py`）补环境元信息：代码版本（git HEAD，`RAG_EVAL_CODE_REV` 兜底）、引擎/策略、生成与 embedding 模型名、FTS/HNSW 配置、活动索引 release 及配置指纹、23 个 `RETRIEVAL_*` 权重快照、题库文件名/题数/sha256（仅评估溯源）；白名单字段，不落 api_base/api_key。元信息随 JSON 报告与基线落盘；旧格式基线（无 metadata）兼容——警告后照常比指标。
    - 门禁收紧与补齐：`--fail-on-regression` 遇环境 blocking 差异（模型/引擎/权重/release/题库）退出码 2 拒绝判定（`--ignore-env-mismatch` 降级为警告；code_revision 差异只警告）；新增性能门禁 `--max-avg-latency`/`--max-p95-latency`（env `RAG_EVAL_MAX_AVG_LATENCY_SEC`/`RAG_EVAL_MAX_P95_LATENCY_SEC` 兜底，默认不设线）；`--regression-threshold` 默认 0.05→**0.0**，对齐「recall@15 不回退」书面约定（放松需显式传参）。门禁判定抽为纯函数 `evaluate_gates`。
    - 产品答案验收工具（`tests/rag_eval/acceptance_lib.py` + 重写 `product_acceptance_runner.py`）：100 题候选集展开、`--case/--expected/--sample`（确定性分层抽样，先保标签覆盖再按协议轮转）、run 落盘 `backend/data/product_acceptance/`（已 gitignore）、人工评分 yaml/json 合并（schema 校验、未知 id 不静默）、跨 run 汇总。五类失败分类（retrieval_miss/insufficient_evidence/generation_error/false_reject/system_error），auto 初判与人工确认**分列**，输出恒带「expected 为候选标签、不构成人工验收通过」声明；令牌只从 `RAG_EVAL_TOKEN` 进请求头，不落盘。旧用法 `--run --limit N` 不变。跑法见 runbook §11。
    - 耗时观测切片：`assistant_chat_stage_duration_seconds{stage=rewrite|embedding}`（`record_chat_trace` 消费既有 stages_ms）+ `assistant_rag_validation_duration_seconds{result}`（`validate_answer` 全路径计时，含 cancelled）。
  - **离线验证**：默认集 **451 过**（379 基线 + 新增 `test_rag_eval_gates.py` 23 例、`test_product_acceptance.py` 46 例、validate_answer 计时 3 例）、覆盖率门禁通过、ruff 全绿、前端 63 过；runner `--help` 与验收工具 list/抽样/合并/汇总 CLI 冒烟通过。
  - **真实验收（未做）**：真实跑分与基线重建（需有效 embedding key 与已入库索引）、100 题教师标注与事实/引用盲审、性能阈值定值（先观察真实耗时分布再设线）——均需授权后在部署窗口执行。
  - **下一批（E 剩余，成本/用量）**：`llm_client` 三处（chat/stream_chat/embed）解析上游 usage（流式需 `stream_options.include_usage`）→ 分阶段向上传播；上游不返回 usage 时显式标记缺失（不把 0 当实测、不做本地估算）；可追溯价格配置（模型→单价进 Settings，只存配置不编造价格）；`rag_requests` per-stage 耗时/token 列（需新 alembic revision + sqlite 同步）；ingest 批量 embedding 用量可见性；`conversation_memory._summarize` 观测。现状排查记录（2026-09-13）：usage 在 `llm_client.py` chat/stream/embed 三处被丢弃；agent 图只累计 total_tokens 且上游缺失时静默为 0；rewrite/embedding 耗时此前只在 chat_trace 日志。

- 2026-09-12 部署窗口实验（轨 1，本机部署副本，3583 节点）：**B2 对照实验判定打平不切换**——A 组 simple 与 B 组 zh 全量 46 题逐项指标完全一致（recall@1 0.7939 / recall@5 0.9693 / recall@15 1.0 / MRR 0.8991 / must_top 100%），唯一 FAIL `dedup_two_large` 两组同状；中文密集分类 recall@15 已封顶，「提升 ≥0.03」不可能。已回退 simple（列已核实），zhparser 镜像/扩展保留备查。**B3 参数扫描 6 组单旋钮 + 1 组组合验证**：`w_semantic` 两值打平、`mmr_redundancy_penalty=0.30` 回归、`filename_boost_exact=0.70` 混合均弃；**接纳 `RETRIEVAL_MMR_PER_FILE_CAP=1` + `RETRIEVAL_TOPIC_BOOST_SPECIFIC=1.15`**——组合验证收益完全可加：recall@1 0.8202 / recall@5 0.9868 / recall@15 1.0 / MRR 0.9342 / **46 例首次 0 FAIL**（哨兵 `dedup_two_large` 12→7 翻转）。两值已写 `deploy/.env` 并重启生效，检索基线已落新值；代码默认值未动（单轮单库证据先落 env）。**live_eval 基线补齐**：11 题真实链路 9 过 2 挂，基线落 `backend/data/live_eval_baseline.json`（gitignore）；两挂均属反馈纪律分类（`feedback_first_fail_no_code` 模型输出代码围栏触违禁词、`feedback_explicit_ask_no_full_impl` 引导词全缺失），记为 M4b 提示词调优候选，非阻塞。
- 2026-09-12 C 测试硬化两项 + E answer cache 补齐：`tests/test_search_pipeline_fixture.py`（search() 主路径离线 fixture——预置 embedding 小语料 + 自洽 fake store，排序/文件名支配/MMR 多样性/语义腿降级/零证据空回进默认集，B3 调参的离线回归网）；`tests/test_ingest_idempotency.py`（同快照零嵌入零发布/逐项比较换新 id/配置指纹漂移全量重嵌/纯删除重发）；`LlamaIndexAnswerGenerator` 补与旧管线同形同键的 answer cache（`RAG_ANSWER_CACHE_ENABLED` 默认关，命中落 `answer_cache_hit` 观测字段），`tests/test_li_answer_cache.py` 4 例。「检索性能基准进 CI」调整为 runner 耗时统计 + 阈值告警（CI 无 PG 服务）；E 的 fallback 治理并入 D 阶段。默认集 366 例通过、覆盖率 84.04%、ruff 全绿。
- 2026-09-12 B3 启发式权重进 Settings（roadmap 四，行为不变）：23 个 `RETRIEVAL_*` 权重（合并权重/RRF 先验/MMR/boosts 四组）从 `retrieval_service.py` 裸常量参数化，默认值逐项锁定=线上现值；启动校验（非负/下限）；`tests/test_retrieval_weights.py` 12 例覆盖校验、公式分差、rrf_k/prior 接线与 MMR 上限。默认集 352 例通过、覆盖率 83.28%、ruff 全绿。参数扫描属部署副本窗口任务，手册与判定规则见 runbook §10「B3 权重参数扫描」，时序上排在 B2 切换判定之后。
- 2026-09-12 B2 zhparser 中文 FTS 代码就绪（roadmap 三，默认 simple 行为不变）：`LLAMA_INDEX_FTS_CONFIG` 白名单开关（建表 DDL 与 FTS 召回腿同源）、alembic 0010 存量换列迁移（幂等 DO 块、downgrade 无条件回 simple）、ingest 错配硬失败 + 检索侧 `rag_fts_config_mismatch` 告警降级。镜像已实证构建（发现并修复 initdb 词性映射两处 bug：as 保留字须引用、映射限 zhparser 26 类词法表）并内置 scws 简体词典（词级分词）；scratch 库实测 0010 换列/降级往返幂等、中文单词命中（simple 对照零命中）、`test_li_fts_zhparser_postgres.py` 与 `test_rag_versioned_postgres.py` 均通过。默认集 340 例通过、覆盖率 82.89%、ruff 全绿；切换判定用的 live 对照实验（46 题 simple/zh 两组 runner）待在有部署副本的窗口执行，决策规则见 `rag_roadmap.md` 三、runbook §10。
- 2026-09-12 上线阻断修复（详见 rag_handoff.md 接手优先级 1–3 的状态更新）：`/api/ready` 物理节点与配置指纹核对、检索/回滚配置校验、重放免配额、同 request_id 异消息 409、prepare 整体超时（`RAG_PREPARE_TIMEOUT_SEC`）、反馈×验证竞态条件更新。修复后默认集 321 例通过、ruff 全绿（本机经 uv 重建 Python 3.11 环境后原生运行）；PG 专项 `test_rag_versioned_postgres.py` 的回滚调用兼容新校验，需在下次有专用测试库时复跑确认。
- 后端默认集 295 例通过，交接提交前复测覆盖率 77.77%；新测试覆盖证据校验、正文展示前落库、内容比较、反馈/请求去重、实际图编排、班级隔离、发布权限、清理与事务回滚。
- 独立临时 PostgreSQL + pgvector 测试验证四路 SQL、索引复用、CAS 冲突、历史引用与回滚。没有更改现有部署库。
- 前端原有 63 例通过，构建通过；Playwright 使用模拟接口分别检查 1440×1000 / 390×844 的工作台，无脚本错误和横向溢出。测试使用现有生产依赖镜像与独立测试工具目录；测试工具版本与仓库开发 pin 尚有差异。
- 灰度开关默认关闭，不能把代码合入等同于正式上线验收完成。

## 尚待上线验收及后续工作

- `tests/rag_eval/product_acceptance.yml` 已提供 100 个独立候选题（70 个可回答候选、30 个拒答/澄清/对抗），`product_acceptance_runner.py` 默认只列题，`--run --limit 1` 才调用真实接口。**验收工具链已收口**（2026-09-17 P2.0：引用级盲审 `--evidence`、评分 schema 只增不改、7 个门禁与四结论退出码、基线对比），并已于 2026-09-17 跑过一轮 15 题真实 run——但那一轮 **0 题 answered**（见上：R1 证据预算被打爆、R2 judge 超时），**没有产出答案质量结论**，`baseline.json` 仍未建。**先修 R1/R2、重跑 15 题，再谈 100 题人工标注**；原有 46 题只用于检索回归，不能替代该门禁。
- 成本计量的**代码、离线回归与真实校准调用均已完成**（见 2026-09-17 记录：流式
  usage 尾帧已证实可用），但价格表尚未由运维给出可追溯来源，金额列目前为空；
  重试过程的逐次用量不可见属已知边界；20 路持续 30 分钟压测、429/超时演练、
  生产备份恢复演练、首班灰度均待执行；未授权预算前不发起大规模调用。
  P2.0 已把**逐题**花费接进 live 报告（按 `request_id` 回读账本，读不到只记
  `usage_error` 不改判定），使「这一轮小样本花了多少」可在跑完后直接读出。
- HNSW、rerank 仍属于对照实验，不默认启用；现阶段使用精确向量检索。HNSW 能力已落地为 Settings 开关（`LLAMA_INDEX_HNSW_ENABLED` 默认关）+ 条件迁移 0009（开关关闭时 no-op），实验操作与回退路径见 `rag_roadmap.md` B1。中文分词对照实验已完成并判定**不切换**（2026-09-12 打平，结论与打平原因见 `rag_roadmap.md` 三）；zhparser 能力（`LLAMA_INDEX_FTS_CONFIG` + 条件迁移 0010 + 自定义 PG 镜像）保留，语料结构变化时可低成本复验。
- 旧索引节点目前保守保留；应补充引用保留期驱动的节点垃圾回收及磁盘容量告警。
- 对问题的脱敏是保守规则，不能保证识别所有自然语言隐私；共享发布始终需要人工检查。
- 请求支持 UUID 幂等键，前端同问题重试复用该键；已完成请求重放结果，处理中/中断态拒绝重复执行，不能自动重放评测。
- 工具上下文保留最近工具轮原文，更早结果压为工具摘要；预算仍超限时安全终止。
  kb_search 摘要的**压缩粒度已修**（2026-09-17 P2.0）：原「前 2 名 × 前 300 字符」
  是**位置函数**，名次 ≥3 或偏移 >300 的命中一律不可恢复（`route_kb_engine_ops`
  即因此丢 `can_handle`）；现改为覆盖式（head + tail + 高信号行、TOP_CHUNKS 3、
  补回 title、单片段 320 字符有界），不再有位置矩形之外的盲区，但**仍有界**：
  第 4 名之后与超出片段额度的内容依旧不可恢复——这是压缩的代价，不是缺陷。
  邻接片段预算选择和更细致切分待补充；画像/checkpoint 仍属 Agent M4 后续。
- 模型校验采用单次有界判断，不提供正确性保证；需要人工盲审校准并根据模型实际 JSON 遵从率决定上线参数。
