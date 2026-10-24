# 本地开发环境

配置日期：2026-09-17。适用代码：`main / 495432d`，当前主机 macOS arm64。
后续实施顺序见 [统一路线](roadmap.md)。
本文的已配置组件、版本及第 6 节是带日期的环境记录，不是本轮运行状态；执行前先核对。

## 1. 已配置组件

| 组件 | 本机状态 |
| --- | --- |
| Python | uv 管理的 CPython 3.11.15；项目虚拟环境 `backend/.venv` |
| Python 依赖 | `requirements.txt` + `requirements-dev.txt`，安装后 `pip check` 通过 |
| Node / npm | 复用 Node 24.18.1 / npm 11.16.0 |
| 前端依赖 | 使用 lockfile 执行 `npm ci`；项目 `.npmrc` 指向 npmmirror |
| PostgreSQL | 复用 Homebrew PostgreSQL 17.10，pgvector 0.8.4 |
| 开发库 | `assistant_dev`，Unix socket 本机连接；迁移至 `0010_li_fts_zhparser` |
| 专项测试库 | `assistant_rag_test`，与开发库和旧库隔离 |
| harness 源码 | `/Users/fsy/code/teaching_harness`，Gitee 提交 `9ed5cfd` |
| 容器工具 | Colima 0.10.3、Docker CLI 29.7.2、Compose 5.5.0、Buildx 0.36.1 |

原 `assistant` 数据库保留。原 Python 3.9 虚拟环境移至
`.runtime/backups/backend-venv-py39`；原配置备份为
`.runtime/backups/backend.env.before-local-setup`。这些本地文件均被 Git 忽略。
迁移后的旧虚拟环境仅作备份，恢复时需移回原位置才能依赖其原有脚本路径。

## 2. 镜像源

- Python 运行时：`https://registry.npmmirror.com/-/binary/python-build-standalone`。
- Python 包：`https://mirrors.aliyun.com/pypi/simple/`；虚拟环境 `pip.conf` 已配置，
  uv 安装仍需显式传 `--index-url`。
- npm：`https://registry.npmmirror.com`，作用范围仅本项目。
- Homebrew：本次使用中科大 bottle/API 镜像；个别 bottle manifest 镜像缺失时
  Homebrew 自动回退上游元数据，没有修改用户全局 brew 配置。
- Colima ARM64 镜像：通过 GitHub 下载镜像获取，Colima 按内置 SHA-512 验证。
- harness 构建：DaoCloud Python 基础镜像、阿里云 Debian 和 PyPI 镜像。

重新安装后端依赖：

```bash
uv python install 3.11 --mirror https://registry.npmmirror.com/-/binary/python-build-standalone
uv venv --python 3.11 backend/.venv
uv pip install --python backend/.venv/bin/python \
  --index-url https://mirrors.aliyun.com/pypi/simple/ \
  -r backend/requirements.txt -r backend/requirements-dev.txt pip
```

已有环境无需重复创建虚拟环境。新主机需自行配置 `.env`，不要复制或提交凭据备份。

## 3. 启动与停止

从仓库根目录启动前后端：

```bash
./start_dev.sh
```

- 前端：<http://127.0.0.1:5174>
- 后端：<http://127.0.0.1:8090>
- API 文档：<http://127.0.0.1:8090/docs>
- 健康/就绪：`/api/health`、`/api/ready`
- 日志：`.logs/backend.log`、`.logs/frontend.log`

前台启动按 Ctrl-C 停止；已有后台服务可运行 `bash stop_dev.sh`。
不要重复启动占用相同端口的服务。

PostgreSQL 当前由 Homebrew 服务管理，需要时运行：

```bash
brew services start postgresql@17
```

## 4. 本地评测容器

`docker-compose.local.yml` 只启动 harness，前后端和 PG 继续在宿主机运行。
默认构建上下文 `../../teaching_harness` 与本机仓库位置匹配；其他位置通过
`TEACHING_HARNESS_PATH` 指定完整仓库路径（与生产 compose 的父目录语义不同）。

```bash
bash local_harness.sh start
docker --context colima-dpdk compose -f docker-compose.local.yml logs --tail=30 harness
```

后端 `.env` 已设置 `HARNESS_MCP_URL=http://127.0.0.1:8100/mcp`。
容器使用 internal 网络、非 root 用户、只读根目录、tmpfs、cap_drop、
no-new-privileges、内存和进程数限制。不在宿主机直接运行学生代码。
Docker internal 网络不发布端口；脚本通过 Colima 的 SSH 将容器端口转发到
宿主机 `127.0.0.1:8100`，不为 harness 添加外网。脚本支持重复启动并更新转发，
Colima 重启后也应重新运行脚本。

harness 按独立仓库的 `mcp>=1.2,<2` 约束安装，本次解析为 MCP 1.30.0；后端
保持 1.12.4，MCP 协议联调通过。不能直接把后端 pin 套到服务端：1.12.4 的
FastMCP 与 harness 的字符串类型注解存在工具注册兼容问题。

停止评测容器与虚拟机：

```bash
bash local_harness.sh stop
colima stop dpdk
```

## 5. 功能边界

- 本机使用 `AUTH_MODE=local`，仅回环开发。账号、作业管理与教师工作台联调需
  切到 account 模式并在开发库创建测试账号，不能把 local 模式暴露到局域网。
- 模型凭据仅存本地 `.env`。生成模型已切换为 `qwen3.8-flash`，真实调用
  返回 HTTP 200，后端已重启；代码默认值和开发/部署配置模板同步更新。
  embedding 保持 `text-embedding-v4`，输出 1024 维。
- 知识源离线扫描通过：211 个文件、3737 个片段，约 282.6 万字符。
  已按用户要求完成全量 ingest：3737 个片段全部发布到
  `ccbb92e558534cbcbd5e3d3b2ee4a30d`；首次入库调用 embedding 服务。
  后续知识源更新使用同一命令，未变化的片段会复用：

```bash
cd backend
.venv/bin/python scripts/ingest_llama_index.py
```

- 当时记录为 `RAG_PRODUCT_ENABLED=false`。保持实际配置由实施批次管理，不因整理文档自动开启；新目标中的离线 judge 不能靠直接打开旧产品开关实现。
- FTS 保持 `simple`，本机 PG 不要求安装 zhparser；HNSW 保持代码默认关闭。
- 检索权重保持代码默认，不把另一部署环境的实验配置冒充本机验证结果。

### 5.1 用量账本（P1）

每次请求的全部上游花费落在 `upstream_usage_records`（migration 0011；SQLite 与 PG
同一份 `usage_schema.sql`）。本地无需额外配置即可记录用量，但要**看到金额**必须
在 `backend/.env` 里给出价格，否则只会返回 `unpriced_models`：

```bash
# 单价用字符串（Decimal 精确解析）；embedding 只需 input_per_1k。
# 下面数字**只是格式占位，不是官方价**，不要照抄：真实单价须由运维给出可追溯来源
# （账单页/合同/报价单），连同来源标识一起写进 LLM_PRICE_VERSION。
# 本机 .env 目前留空 ⇒ 只出用量、不出金额。
LLM_PRICES_JSON={"qwen3.8-flash":{"input_per_1k":"<按来源填>","output_per_1k":"<按来源填>","currency":"CNY"},"text-embedding-v4":{"input_per_1k":"<按来源填>","currency":"CNY"}}
LLM_PRICE_VERSION=<价格来源与生效月份>   # 随每条记录落库，供审计追溯；调价不改历史账
LLM_USAGE_RECORDING_ENABLED=true       # 总开关
LLM_STREAM_USAGE_ENABLED=true          # 单独控制流式 stream_options
```

查询（local 模式下 `local` 主体即 admin；token/account 模式非 admin/leader 返回 404）：

```bash
curl -s 'http://127.0.0.1:8090/api/usage/cost?days=7' | python -m json.tool
curl -s 'http://127.0.0.1:8090/api/usage/cost?days=7&stage=query_embed'
```

读账本时的三条口径：token 为 `null` 表示**未知**（不是 0，也不做本地估算）；
`cache_hit`/`replay`/`local` 是**已知的零调用**（`call_count=0`，不计费）；
`operations`（harness/MCP）与 token 用量分列且 `priced=false`。

## 6. 验证记录

> 本节保留 2026-09-17 等既有实测记录，不是 2026-09-18 文档整理的复验结论。
> P0.1 的“全部转 PASS / 零回归”等表述已受到[独立验收报告](p01_independent_acceptance_20260918.md)质疑，旧词条口径也已变更；不得依据原声明放行下一阶段。当前处置见[统一路线](roadmap.md)。
> 本节中的“已修”“本机价格”“下一步”等均限于当时环境，不覆盖更新后的配置或目标。

本次在新 Python 环境实际执行：

- 后端默认集：451 passed，92 deselected，覆盖率 84.26%。
- `ruff check .`、`pip check`：通过。
- 前端：63 tests passed，Vite production build 通过。
- 独立 PG 测试库：`test_rag_versioned_postgres.py` 1 passed，覆盖发布、复用、
  版本固定召回、冲突与回滚；embedding 使用测试替身，不访问模型。
- 开发库初始化及 alembic upgrade head：通过。
- 全量索引：374 批 embedding 请求均成功；版本声明、成员、物理节点数均为
  3737，配置指纹匹配。重复 ingest 返回 3737 unchanged、0 inserted_or_updated，
  发布版本不变。日志：`.logs/ingest-full.log`、`.logs/ingest-idempotent.log`。
- 模型切换后 `test_golden_scenarios.py`：31 passed；配置文件 ruff 检查通过。
- 完整索引发布后的真实模型评测（P0.1 前基线）：`qwen3.8-flash` 为 6/11 passed，
  未写入基线。报告：`.logs/live_eval_qwen38_flash.json`。4 项失败对应日志中的
  `agent_context_budget`，降级答案缺少业务内容，其中一项回显学生代码围栏；
  另 1 项返回学习骨架，触发不泄露用例的禁 C 围栏断言，不能据此直接认定
  参考实现泄露。
- P0.1 修复后的实测（2026-09-17）：
  - 默认集 475 passed、92 deselected，覆盖率 84.77%（基线 451 / 84.26%）。
  - `test_golden_scenarios.py`：36 passed（基线 31，新增 s32–s35、s33b）。
  - `ruff check .`：全绿。
  - 小样本探针（5 题，含全部失败题）：4/5。报告
    `.logs/live_eval_qwen38_flash_p01_probe.json`。
  - 全量 11 题：**10/11 passed**，基线 4 项预算失败 + 1 项围栏越界全部转 PASS，
    零回归。报告 `.logs/live_eval_qwen38_flash_after.json`。
  - `.logs/backend.log` 中 `agent_context_budget` 出现 0 次（基线 4 题）；
    14 次 `agent_context_trimmed` 全部停在 level 2（压缩全部工具结果），
    未升到 L3/L4/L5，无地板溢出。
  - 唯一仍失败 `route_kb_engine_ops`：产出真实模型答案（非降级），命中
    `engine_ops_t`/`process`，缺 `can_handle`。本机实测定因为 E5 压缩粒度：
    证据在语料中存在且检索可达（`can_handle` 在 `plugin/engine.h` 片段第 938
    字符，处于 1500 字符可见窗口内），但被 kb_search 分支的
    「top-2 片段 × 300 字符」「先按名次取 top-2 再截断」丢弃。属 P2 答案验收
    范畴，不得为跑绿放宽 rubric。
- 修复后模型仍可调用，但**未通过教学场景全量验收**（10/11）：剩余项属内容命中，
  不是可用性故障。提示词硬化是概率性约束，复测仍可能偶发围栏，
  rubric 不放宽；复测失败须如实记为模型遵从问题。
- 运行时长观测（P0.1 副作用，需运维知晓）：修复后请求不再提前中止，自然会跑满，
  单题 live 耗时 22–156s（合计 566s / 9.4min，11 题），而
  `ChatOpenAI(timeout=90, max_retries=3)`。探针轮 `route_kb_engine_ops` 曾出现
  1 次 ReadTimeout（72.87s / 11327 tokens / 3 轮 kb_search）。
  **降级文案「模型生成暂时不可用」是固定串，任何异常都命中，对 ReadTimeout
  并不准确**——这是已知文案缺陷，修它需同时兼顾
  `tests/test_llm_resilience.py`、`tests/test_agent_service.py` 的既有断言，
  未在本批改动。
- P1 用量账本实测（2026-09-17，**离线，未发起任何真实模型调用**）：
  - 默认集 **581 passed、96 deselected，覆盖率 86.26%**（P0.1 基线 475 / 84.77%）；
    `ruff check .` 全绿；黄金场景 36 passed 无回归。
  - 新增 `tests/test_usage_wiring.py` 15 例（各 stage 接线、缓存命中/本地规则记零调用、
    取消后仍落库、MCP 成功与失败都留痕）、`tests/test_usage_cost_api.py` 13 例
    （404 门禁、token 与非 token 分列、滚动窗口、老师树隔离与 leader 不受约束）。
  - PG 专项 4 passed：`RAG_TEST_DATABASE_URL=postgresql:///assistant_rag_test
    .venv/bin/python -m pytest tests/test_usage_records_postgres.py -q`。
  - **真实校准调用（全批唯一一次付费调用）**：上游接受 `stream_options.include_usage`
    并回 usage 尾帧。1 次最小流式调用（一句短问、`max_tokens=96`）首帧即成功，
    未触发 400 退字段重试；尾帧 72/79/151 tokens、`usage_source=upstream`；
    落 PG 1 行，`psql` 直查一致；`cost_micros` 为 NULL（本机未配价格，不编造金额）。
    共花费 151 tokens。日志 `.logs/p1_stream_calibration.log`。
    注意：该结论只覆盖 `qwen3.8-flash` 与本次上游，换模型/换兼容实现须重新校准。
- P2.0 答案验收（离线部分）实测（2026-09-17，**离线，未发起任何真实模型调用**）：
  - 默认集 **670 passed、96 deselected，覆盖率 86.17%**（P1 基线 581 / 86.26%）；
    `ruff check .` 全绿；黄金场景 36 passed 无回归。
  - 新增 `tests/agent/test_live_eval_scoring.py` 29 例：词边界口径（`process` 不再被
    `packets_processed` 满足、`dns_` 前缀写法不被误伤、中文/围栏词条行为不变）、
    口径变化逐题留痕（收紧与放宽两个方向）、失败分类结构化且不编造、
    request_id 是合法 UUID 且每次 run 都不同、用量回读失败不改判定、
    token 未知不显示成 0、JSON 报告只增键、基线带口径版本。
  - `tests/rag_eval/` 扩到 127 例（原 83 例）：引用级评分 schema（越界编号报错、旧评分文件仍合法）、
    论断句定位（`[1]` 不被 `[10]` 满足）、盲审清单取不到证据时显式标出、
    受限口径（`correct_rate`/`false_reject_rate` 只算可答题）、门禁四结论与退出码、
    基线回退对比与耗时抖动容忍。
  - 修掉一个真缺陷：`false_reject_rate` 的分子原先取全局人工分类计数，而分母只取
    可答题数 ⇒ 一条被标成误拒的拒答题会让比率**超过 100%**。改为分子分母同源
    （可答题桶），并加断言锁死。
  - **live 口径收紧**（`TERM_CALIBER = word-boundary-v1`，此前是子串口径）：
    `route_kb_engine_ops` 的 `required_terms` 里 `process` 曾被 `packets_processed`
    满足——P0.1 的通过数是**旧口径**下的数，两口径不可直接比；报告与基线都带
    口径版本，跨口径对比会先打一行 `[口径变更]`。
  - 逐题用量：CLI 形态给每题派生 `request_id`（`uuid5(run_id, case_id)`），跑完按
    `request_id` 回读 `/api/usage/cost`（该端点新增可选的 `request_id` 过滤，只做收窄、
    仍受树隔离约束）。读不到只记 `usage_error`，不影响 PASS/FAIL。
  - 离线冒烟：`--sample 15` 分层抽样正常；`--evidence` 对真实 chunk 打出 2178 字符
    正文（有界截断并注明），对不存在的 `chunk_id` 标出「⚠ 取不到证据正文」。
  - 收尾验证修的两处（都留了承重回归，2 个变异均被捕获）：
    ① `--evidence` 按文档调用（`cd backend && python tests/rag_eval/…`）时 Python 只把
    脚本目录进 `sys.path`、不含 cwd，`import app` 抛 `ModuleNotFoundError`，
    整篇清单退化成「取证据正文出错」；已加 `_ensure_app_importable()` 自举，用
    **子进程**用例锁死（pytest 进程内 `find_spec` 走 `sys.modules` 短路，测不出来）。
    ② `test_rag_versioned_postgres.py` 原先假定库是新的 ⇒ **同一库跑第二遍必失败**
    （上轮 release 被复用 → 首轮 ingest 不 embedding → `embed.calls == 1` 拿到 0），
    看着像发布事务坏了；已改为开跑前清自己的三张表，在**已污染的库**上连跑两遍 5 passed。
- P2.1 真实跑批与 D1/D2 修复实测（2026-09-17，**本批唯一一批付费调用**：
  15 题验收 + 修复后 1 题 + 3 题抓诊断）：
  - 默认集 **674 passed、96 deselected，覆盖率 86.31%**（P2.0 基线 670 / 86.17%）；
    `tests/rag_eval/` 127 passed；黄金场景 36 passed；PG 专项 5 passed；`ruff check .` 全绿。
  - **15 题 0 题 answered**：10 unanswered（9 题 `evidence_budget`）+ 5 system_error
    （3 judge 超时、2 生成失败）。**这一轮是归因，不是质量结论**：没有真实答案就
    没有引用可核对，盲审与门禁都不成立，`baseline.json` 故意没建。
  - **R1 实测**：`chunks=72 / chunk_chars=67651 / tool_results=1 / evidence_chars=69958
    vs limit=24000`。根因是 `collected_chunks` 每轮 `kb_search`（limit=8）全量追加、
    不去重不设上限（`app/agent/tools.py:116-117`）——**累积无界**，不是预算值偏小。
    60% 的题在检索成功的前提下被判「资料不足」。
  - **R2**：`rag_validation_timeout_sec=20` 对最大 24,000 字符证据太紧；到达校验的
    请求几乎无一通过（15 题里 3 次，修复后 3 题里 2/2）。
  - **D1/D2 已修并实测**：修复前产品路径 `chat_trace` 0 条、账本 0 行、`trace.request_id`
    是随机 `trace_xxx`。修复后 1 题真实调用 → `chat_trace` 1 条、账本 7 条 / 5 次调用 /
    10,890 tokens / **¥0.0108**，`UsageService().query(request_id=…)` 精确回读。
  - **本轮花费**：修复后 4 题 **¥0.0756**（账本精确）；修复前 15 题**无账本行**，
    估算 ≈¥0.30（区间 ¥0.24–0.80）——**是估算不是实测**。产品路径不落账时，
    历史 run 的花费再也补不回来，这是 D1 最贵的后果。
  - `query_embed` 全部 `cost=None`：`text-embedding-v4` 单价仍未提供，账本只出用量不出价。
  - **跑门禁前务必把 `RAG_PRODUCT_ENABLED` 回退 `false`**：开关开着时默认集会出现
    85 failed / 55 errors，全部是 `Settings` 的「生产 RAG 仅支持版本化 llama_index
    数据面」校验（测试环境覆盖了 `RAG_ENGINE`）——**不是代码回归**。
  - 另一处坑：`JsonFormatter` 对 `extra` 有字段**白名单**，白名单外的键静默丢弃。
    结构化观测要么走 message-JSON，要么把键加进白名单。
  - **测试里抓日志也有同一个坑**：`extra=` 形态的日志（如 `rag_validation_failed`）
    用 `json.loads(record.getMessage())` **抓不到**（message 只是中文串，JSON 在
    `record.__dict__` 里）。抓 `extra` 形态要按白名单键读 `record.__dict__`；
    抓 message-JSON 形态才用 `getMessage()`。两种形态在同一模块里并存。
- R1/R2 修复与二轮实测（同日，再 6 次付费调用，**共 ¥0.166**）：
  - 默认集 **678 passed、96 deselected，覆盖率 86.30%**（+2 新测试）；`ruff` 全绿；
    `tests/rag_eval/` 127 passed。
  - **R1 在生产上证实生效**：三条 `rag_evidence_bounded` 均 `distinct == chunks`
    （37/37、27/27、34/34）、`kept_chars <= 24000`。修前是 `chunks=72 / 69958 字符`。
  - **R2 是结构性缺陷，不只是「20 秒太小」**：judge 内层 `timeout=90.0 × (1+3 次重试)`
    被外层 `rag_validation_timeout_sec=20` 罩住 ⇒ **重试与错误分类逻辑永远不可达**；
    且 judge 拿的是**独立** 20 秒而非请求剩余 ⇒ 最坏 140s > 请求墙 120s，
    **两个墙同时到期，同一件「慢」随机报成 `validation_failed` 或 `generation_failed`**。
    改为 `judge_budget() = max(min(上限, 请求剩余), 1.0)`，judge 死线必然早于请求墙。
  - **上限 20 → 60 是按实测定的**：上游**真实调用**延迟（`rewrite_ms`，n=12，
    已剔除 ≈0ms 的未命中）p50 13.1s / p90 **45.3s** / max 46.8s ⇒ 20 秒落在 p90 之下，
    对 5/12 次真实调用**必然**误判。**调到 60 秒并没让 failures 消失**（1 次撞满 60s、
    1 次撞 120s 请求墙）——所以这不是「用放宽阈值掩盖失败」。
  - **answered 没有回来**（二轮 3 题仍 2 题 system_error）。超时只是症状：judge 要读
    **27–37 个片段**，因为 `collected_chunks` **在去重之后仍然无界**——R1 只做了
    「去重 + 按需缩短正文」，**没做用户选定的「名次上限」**。收窄正文不减少**条数**。
  - **单题成本实测 ¥0.014–0.056**（25k–56k tokens，3–5 个工具轮）。
    「3 题 ≈ ¥0.03」低估约 2.8 倍 ⇒ **15 题 ≈ ¥0.42，不是 ¥0.30**。扩量前重新批预算。
    账本累计（全部会话）99 次调用 / 249,781 tokens / ¥0.2435。
  - **宿主休眠会污染计时（本机特有，务必知道）**：`https_1` 的日志墙钟跨 **363 秒**，
    而 `chat_trace.total_ms` 只有 **41.7 秒**。原因是 macOS **休眠期间
    `time.perf_counter()`（即 `now_ms()`）与事件循环单调钟都不走**。
    ⇒ 跑批前先 `caffeinate`；看到「墙钟 ≫ `trace.total_ms`」就当休眠信号，
    该题**计时数据作废**（状态数据仍有效）。
  - 观测缺口已补：`stages_ms` **原先只有检索阶段**，judge 花了多久无从判断——
    这正是 R2 此前只能靠猜的直接原因。现加 `answer_generation_ms` / `validation_ms`
    （失败路径也记），失败日志补 `request_id`（此前 7 条 `TimeoutError` 只能按时间顺序猜）。
- `/api/health`、`/api/ready` 和前端 HTTP 访问：通过。
- 独立 Chrome 页面检查：1440×1000、390×844 均正常渲染且无横向溢出；
  截图保存在 `.logs/desktop-smoke.png`、`.logs/mobile-smoke.png`。
- 使用后端 MCP 客户端读取 harness 协议列表：20 个协议；容器内编译 DNS
  示例并评测：9/9 通过、100 分，没有调用外部模型。
- 容器隔离实测：uid 10001、只读根目录、全部 capability 删除、2 GiB / 256
  进程上限；无默认路由，外网连接返回 network unreachable。

复测命令：

```bash
cd backend
.venv/bin/python -m pytest -m 'not live and not postgres' --cov=app --cov-fail-under=60
.venv/bin/python -m ruff check .
RAG_TEST_DATABASE_URL=postgresql:///assistant_rag_test \
  .venv/bin/python -m pytest tests/test_rag_versioned_postgres.py tests/test_usage_records_postgres.py -q
cd ../frontend
npm test
npm run build
```

PG 专项应使用新建的空测试库；不要用开发库或旧 `assistant` 库代替。
（`test_rag_versioned_postgres.py` 自 2026-09-17 起会先清自己的产物，因此同一库可
重复跑；此前它只在空库上成立，第二遍会因残留 release 失败。）

## 7. 专项检查命令（从旧开发笔记迁入）

以下命令是操作参考，2026-09-18 文档整理未执行这些检查。工作目录为 `backend/`；先审查测试 fixture 与实际目标环境。默认回归、安装及启动命令见上文与仓库 README，不重复维护。

### 无真实模型的定向回归

```bash
.venv/bin/python -m pytest tests/test_provider_lifecycle.py tests/test_conversation_memory.py tests/test_streaming.py -m 'not live and not postgres'
.venv/bin/python -m pytest tests/test_auth.py -q
.venv/bin/python -m ruff check .
```

provider 生命周期测试检查并发冷启动的实例复用；会话记忆测试检查摘要与近期消息；流式测试不等于真实上游或浏览器端到端验收。默认 ASGI 测试可以不绑定本地端口。

依赖审计在准备好的开发环境运行 `.venv/bin/python -m pip_audit -r requirements.lock`；它可能访问外部漏洞服务。Linux-only lock 不用于 Windows 安装。

### 真实链路与旧数据面检查

下面命令可能访问模型或数据库，必须先明确范围、预算及隔离环境；不会因文档列出而自动运行。

```bash
.venv/bin/python tests/test_chat_e2e.py --limit 1
.venv/bin/python tests/test_chat_e2e.py --question "engine_ops_t 是什么？"
.venv/bin/python -m pytest tests/test_retrieval.py -m live
```

S4-b 起 legacy `knowledge_chunks` 数据面与 `test_postgres_runtime.py` 已一并删除；LI 发布/回滚专项用隔离测试库，命令见第 6 节。

索引发布唯一入口是 `scripts/ingest_llama_index.py`（legacy `ingest_repo.py` 已删）。索引命令会写数据且可能调用 embedding，不是普通诊断命令。

### 冷热缓存与多轮负载

```bash
# 重复问题：观察缓存与同请求合并；只有相关开关开启时才代表缓存路径
.venv/bin/python tests/load_chat_concurrency.py --levels 1,10,30 --requests-per-level 30 --debug-trace
# 不同问题：观察真实检索及生成负载
.venv/bin/python tests/load_chat_concurrency.py --levels 10 --requests-per-level 10 --cold-cache --debug-trace
```

以上是原工具用法，不是当前已批准负载或容量承诺。聊天、评测和混合场景分别验收；参考[运维手册](runbook.md)第 9–11 节。旧开发笔记中的 50 并发性能数字保留在归档，不作为当前基线。
