# 运维 Runbook（故障处置手册）

当前计划与授权范围见[统一路线](roadmap.md)，当前决策见[决策记录](decisions.md)。本手册保留现有实现的操作方法；历史实验与命令示例不代表本轮已验证或允许自动执行。在线 judge 默认关闭（`RAG_ONLINE_JUDGE_ENABLED=false`，decisions §10 定案维持离线口径）；在线只跑确定性四判据，离线验收用 `scripts/rag_judge_eval.py` + `tests/rag_eval/judge_cases.yml`（需真实 LLM 凭据）。

> 面向部署形态：Docker Compose + Nginx + HTTPS（见 README "Production Deployment" 章节）。
> 配套可观测性设施：单行 JSON 日志（`assistant` logger）、`/metrics` Prometheus 指标、
> `deploy/healthcheck.sh`（定时探测 + 告警 webhook）、`deploy/backup.sh`（每日备份）。
>
> 通用第一动作：先看日志。
> - 容器部署：`docker compose logs --tail=200 backend`
> - 日志为单行 JSON，直接按字段 grep，例如 `docker compose logs backend 2>&1 | grep '"event": "llm_retry"'`

---

## 1. LLM / Embedding 上游故障（429、超时、连接失败）

**现象**：用户提问返回降级回答（fallback）或明显变慢；监控看到 LLM 失败率上升。

**判据**：

```bash
# 重试与失败事件
docker compose logs backend 2>&1 | grep '"event": "llm_retry"' | tail -20
# 指标（应用容器内或宿主机直连后端端口，/metrics 不经 nginx 外露）
curl -s http://127.0.0.1:8090/metrics | grep assistant_llm
```

`assistant_llm_requests_total{status="error"}` 持续增长、`assistant_llm_request_duration_seconds` P95 飙升即为上游问题。

**处置**：

1. 确认上游状态：用 `backend/.env`（或 compose 环境变量）里的 `LLM_API_BASE` / `LLM_API_KEY` 直接 `curl` 一次最小补全请求，区分"上游挂了"与"我方配额/网络问题"。
2. 服务本身无需重启：客户端已有指数退避重试 + 降级回答，上游恢复后自动回到正常。
3. 若是配额耗尽（429 持续且带 quota 文案）：联系上游提额，或临时调大 `LLM_MAX_RETRIES` / `LLM_RETRY_BASE_SECONDS` 缓解抖动，重启 backend 生效。
4. 事后在日志中统计影响面：`grep '"event": "chat_trace"'` 看 `fallback` 字段出现频次。

## 2. 数据库连接打满 / `too many clients`

**现象**：请求大面积 500 或超时；日志出现 `too many clients` / 连接获取超时；高并发下 P95 异常升高（pgvector 检索连接排队）。

**判据**：

```bash
docker compose logs backend 2>&1 | grep -iE 'too many clients|pool|timeout' | tail -20
docker compose exec postgres psql -U postgres -d assistant -c \
  "SELECT count(*), state FROM pg_stat_activity GROUP BY state;"
```

**处置**：

1. 短期：重启 backend 释放连接（`docker compose restart backend`），连接池会重建。
2. 核对池配置与 PostgreSQL `max_connections` 的匹配：psycopg 池 min 4 / max 32，LlamaIndex 存储另有连接池；多实例部署时总连接数 = 实例数 × 各池上限，必须小于 `max_connections`。
3. 长期：高并发瓶颈在检索连接排队与上游生成（见 README "Measured Performance"），按数据库连接上限调整池参数，或横向扩展应用实例并同步放大 PG 连接数。

## 3. 索引漂移（知识源更新了但搜不到新内容）

**现象**：DPDK 仓库已提交新文档，提问仍召回不到新内容。

**判据**：

```bash
# 自动 ingest 日志（cron 重定向目标）
tail -50 /var/log/assistant-ingest.log
# 锁目录残留会导致后续任务跳过：auto_ingest.sh 用 mkdir 锁防并发
```

**处置**：

1. 确认 cron 是否在跑：`crontab -l` 应含 `deploy/auto_ingest.sh`；日志里每轮应有 "scanning all configured knowledge sources" / "ingest complete"（脚本已改为每次全扫、由 ingest 自身幂等跳过，不再有 HEAD 变化检测）。
2. 若上次任务失败残留锁目录，手工删除锁目录后重跑。
3. 手工兜底重建（幂等，未变节点不会重复 embedding）：

```bash
docker compose exec backend python scripts/ingest_llama_index.py
```

4. 重建后在 UI 提问验证，或 `pytest -m postgres`（需显式 `RAG_TEST_DATABASE_URL`）验证版本化发布/检索链路。

legacy 引擎已随 S4-b 删除，`knowledge_chunks` 仅作历史引用回读不再摄取。

## 4. 备份与恢复演练

**备份**：cron 每日执行 `deploy/backup.sh`（示例 `20 3 * * * .../deploy/backup.sh`），产物在 `backend/data/backups/`（保留 7 份，失败走 `ALERT_WEBHOOK_URL` 告警）。PG 归档保留 `rag_task_inputs` 的表结构，但用 `pg_dump --exclude-table-data=public.rag_task_inputs` 排除原始问题与源码；恢复后该表为空，未完成任务不能靠备份重驱动。备份目录中的旧归档不会因脚本升级自动净化，应逐份核查该表数据；有数据的归档须在隔离库清空该表后重新导出、验证并替换，不把原始内容写进核查日志。

**恢复（PostgreSQL，恢复到干净库）**：

```bash
docker compose up -d postgres
cat backend/data/backups/assistant-<ts>.dump | \
  docker compose exec -T postgres pg_restore -U postgres -d assistant --clean --if-exists
docker compose up -d
```

**演练要求**：每个季度至少在干净库上做一次全量恢复并完成一次问答验证（路线图 M4 遗留项：pg_restore 实操需在有 Docker 的主机完成）。恢复后跑 `pytest -m postgres`（显式 `RAG_TEST_DATABASE_URL`）验证 schema / FTS / 版本化检索链路。

**全新库初始化顺序（S4-c 实证陷阱）**：`init_db()` → `alembic upgrade head`，**不可** `alembic stamp head`——stamp 只写版本号不建表，users/api_tokens/grading_submissions 等迁移侧表会静默缺失，登录即 404。`stamp head` 仅适用于已含全量表的存量库。

**0014 成绩账本核查**：升级后可在 backend 目录运行 `.venv/bin/python scripts/audit_grading_jobs.py`，只读列出 `submission_done=1` 但无法凭 `task_job_id` 关联成绩的旧 job。旧成绩未存 job 身份，清单不代表一定漏记；不要按协议/hash 自动补写或删除。新 job 的成绩插入和 `submission_done` 在同一事务，失败时保留 pending 任务供重驱动。停号后，未落库的正式提交不再写成绩。

**0015 讲解身份兼容**：存量 `explain-{submission_id}` 会话只在派生 request_id 与成绩归属均吻合时标记为 `purpose=explain`，不改历史 `input_hash`；作业标题修改后由讲解端点按这份服务端身份接续。普通聊天仍对相同 request_id 的不同输入返回 409，历史 NULL hash 不放宽。

## 5. 限流风暴 / 配额耗尽

**现象**：用户反馈收到 429；日志大量 `rate_limit_hit`。

**判据**：

```bash
docker compose logs backend 2>&1 | grep '"event": "rate_limit_hit"' | tail -20
# 按 kind 区分：per_minute（每分钟滑动窗口）/ daily_quota（每日 LLM 配额）
curl -s http://127.0.0.1:8090/metrics | grep assistant_rate_limit_hits_total
```

**处置**：

1. `per_minute` 命中：单 subject 短时爆发。确认是否前端重试风暴或脚本误用；必要时调大 `RATE_LIMIT_CHAT_PER_MINUTE`（默认 20）重启生效。
2. `daily_quota` 命中：当天该 subject 的 LLM 调用次数达到配额（`usage_counters` 表持久化，UTC 零点重置）。在用量看板（侧边栏"用量"面板，admin 可见全 subject）确认分布；调整方式是修改 `API_TOKENS_JSON` 中该 subject 的 `daily_quota` 并重启 backend。
3. 429 响应带 `Retry-After`，客户端应按提示退避；若前端没有退避逻辑导致雪上加霜，按缺陷处理。

## 6. harness 评测服务运维

**角色**：`harness` 是独立的 `teaching_harness` 仓库容器（构建上下文 `${TEACHING_HARNESS_PATH:-../..}/teaching_harness`），编译并运行学生插件代码。对外接口是 MCP streamable HTTP（`:8100`），只挂在 `internal: true` 的 `harness_internal` 内网上，不发布到宿主机、无外网出口；backend 是唯一同时挂 `default` 与 `harness_internal` 的服务，经 `HARNESS_MCP_URL`（默认 `http://harness:8100/mcp`）调用。

**故障定位入口**：

```bash
# backend 侧 MCP 调用失败事件（重试耗尽后上报）
docker compose logs backend 2>&1 | grep '"event": "mcp_call_failed"' | tail -20
# harness 容器自身日志
docker compose logs --tail=200 harness
docker compose ps harness
```

agent 侧工具失败会降级为模型可读的 `{ok: false, error, detail}` 返回（如 `grading_timeout` / `grading_failed`），接口不会 500；学生看到降级话术时先按上面两个入口定位。

**评测超时处置**：`GRADING_TIMEOUT_SEC`（默认 120）是双保险——既作为 `grade_plugin` 的 `timeout_sec` 参数传给 harness，backend 侧又用 `asyncio.wait_for` 同值硬超时。学生代码死循环/编译卡住时表现为 `grading_timeout`；若频繁命中，先看 harness 日志确认是单个学生代码问题还是 harness 容量不足，再考虑调大该值（重启 backend 生效）。

**日配额处置**：评测配额独立于聊天配额，`usage_counters` 表 `kind='grading'`，默认 `GRADING_DAILY_QUOTA=20` 次/天（UTC 零点重置），`operator` / `admin` 角色豁免。学生反馈"今日评测配额已用完"时属预期行为；确需放宽就调大 `GRADING_DAILY_QUOTA` 或给教师账号加 `operator` 角色。单次评测代码大小上限 `GRADING_MAX_CODE_BYTES`（默认 256 KB），超限返回 `code_too_large`。

**容器安全配置清单**（compose `harness` 服务段；改动前核对[当前决策](decisions.md) N03 与继续有效的隔离约束，原 D9 仅供历史追溯）：

- `read_only: true`：根文件系统只读
- `tmpfs: /tmp:size=256m,exec`：read_only 下唯一可写处，gcc 临时目录。**`exec` 标志必须显式给出**——docker tmpfs 默认带 `noexec`，会导致 dlopen 编译产物报 `failed to map segment`（2026-08-25 实测踩过的坑）
- `user: "10001:10001"`：非 root 运行（Dockerfile 的 `USER` 之外的第二道保险）
- `cap_drop: ALL` + `security_opt: no-new-privileges:true`
- `mem_limit: 2g`、`pids_limit: 256`
- 只挂 `harness_internal`（`internal: true`，无网关）

## 7. TLS 证书到期

**现象**：浏览器提示证书过期；healthcheck 对 https URL 的探测开始失败并触发告警。

**处置**：

1. 确认到期时间：`openssl x509 -in deploy/certs/server.crt -noout -enddate`。
2. 更换证书：将新证书放到 `deploy/certs/server.crt` / `server.key`（演练环境用 `bash deploy/certs/gen_self_signed_cert.sh` 重新生成）。
3. 重载 nginx：`docker compose restart frontend`（证书经卷挂载，无需重建镜像）。
4. 验证：`curl -vI https://<主机>/api/health`；浏览器确认不再告警。

## 8. 评测安全信任模型与依赖 CVE 备忘

本章保留 M3 安全复核记录，不能代替当前环境复验；原信任模型见 `docs/archive/2026-09-18/agent_upgrade_plan.md` D9 与风险表，当前约束见[决策记录](decisions.md)。

**威胁模型**：学生插件代码（上传或粘贴的 C 源码）只在 harness 容器内编译、运行，backend 只经 MCP 传递源码与结果。harness 侧防线：

- 非 root 用户（uid/gid 10001，`user` 指令 + Dockerfile `USER` 双保险）
- 无外网：仅挂 `internal: true` 的 `harness_internal` 网络（无网关、无外部访问）
- `read_only: true` 根文件系统 + 唯一可写 tmpfs `/tmp`（显式 `exec`，见第 6 节）
- cgroup 限额：`mem_limit 2g`、`pids_limit 256`
- rlimit 护栏：CPU 60s/600s（soft/hard）、`CORE=0`、`NPROC=512`、`NOFILE=256`、`AS=8GB`
- `cap_drop ALL` + `no-new-privileges`

网络拓扑上 backend 是唯一横跨 `default` 与 `harness_internal` 的服务——**不要**给其他服务接 `harness_internal`，也不要给 harness 接外网。评测成绩落库只存代码 sha256 与结果 JSON，不存源码全文。

**依赖 CVE 备忘（pip-audit，决策：暂不升级）**：

- `mcp 1.12.4` 命中 3 个传输层 CVE，修复需 `mcp>=1.23.0`，但升级被 `pydantic==2.10.4` 的 pin 压住（`mcp>=1.13` 要求 `pydantic>=2.11`）
- `langchain-openai 1.1.9` 命中 1 个低危 CVE，升级被 `openai<2` 压住（该上限源自 `llama-index-llms-openai-like`）
- 缓解因素：MCP 端点仅 compose 内网可达（不发布到宿主机），harness 容器无外网出口，攻击面限于内网
- 将来升级需先解两个钉：`openai<2`（取决于 `llama-index-llms-openai-like` 兼容 openai 2.x）与 `pydantic==2.10.4`。CI 中 pip-audit 告警不阻塞，升级后重跑 `pytest -m "not live and not postgres"` 与黄金场景集回归

## 9. live 评测集与模型漂移检查
**用途**：agent 层的行为回归有两道卡口——黄金场景集（`backend/tests/agent/test_golden_scenarios.py`，mock 模型的确定性单测，进 CI 默认集）和 live 评测集（`backend/tests/agent/live_eval.yml`，11 题，走真实模型 + 真实 `/api/chat/stream`）。前者验证编排逻辑，后者观察**真实模型的工具选择漂移**（规划 §8/D14）。

**触发时机**（手工跑，不进 CI）：

- 更换/升级 LLM 模型（`LLM_MODEL` 或上游默认模型变化）；
- agent 提示词大改（`backend/app/agent/prompts.py`）；
- 工具面变化（新增/改名/参数变更）。

**跑法**（前置：后端以 agent 模式运行、知识库已 ingest、harness 容器可达；account 模式下用专用评测账号，注意每日 chat/grading 配额要够）：

```bash
cd backend
# 本机开发（AUTH_MODE=local 不需凭据）
.venv/bin/python tests/agent/live_eval_runner.py
# 生产/部署目标（account 模式换 token 跑）
.venv/bin/python tests/agent/live_eval_runner.py \
  --base-url https://<主机> --username <学号> --password '<密码>'
# 只跑单题排查
.venv/bin/python tests/agent/live_eval_runner.py --case route_kb_dns_qname
# pytest 形态（每题一个用例，全部 live mark）
.venv/bin/python -m pytest tests/agent/live_eval_runner.py -q -m live
```

**判定口径**：每题断言三层——HTTP 状态码、工具选择（`expect.tools` 为有序子序列，允许模型多调检索；`forbidden_tools` 一票否决）、答案 rubric（`required_terms`/`any_terms`/`forbidden_terms`，后者是反馈纪律与参考答案不泄露的红线，如 FAIL 首轮反馈不得出现代码围栏）。rubric 有意偏松：漂移靠基线对比暴露，不靠单题绝对通过线。

**基线与漂移**：

- 首次跑或确认本轮行为符合预期后，用 `--update-baseline` 固化基线到 `backend/data/live_eval_baseline.json`（**已 gitignore**：含模型输出摘要，属本地对照数据，不入库；换机器/换人需各自重建基线）。
- 之后每次跑都会与基线逐题对比，输出三类漂移项：`[退步]`（上次 PASS 本次 FAIL）、`[修复]`、`[工具漂移]`（通过状态没变但工具序列变了——模型行为漂移的早期信号）。
- 处置建议：出现 `[退步]` 或 `[工具漂移]` 先跑黄金场景集（`pytest tests/agent/test_golden_scenarios.py`）区分是提示词/编排回归还是模型本身漂移；确认新行为可接受再 `--update-baseline` 接纳为新基线。
- 完整机器可读结果用 `--output report.json` 留存（含逐题答案全文，注意妥善处置、勿提交）。

---

## 10. RAG 检索评估（recall@k / MRR / 基线漂移）

**用途**：检索质量的量化卡口，与 §9 的 agent 行为漂移检查互补。题库 `backend/tests/rag_eval/queries.yml`（协议/子系统/主题/结构体/边界/负例观察），runner 进程内直调检索服务（不启 HTTP），计算 recall@1/5/15、MRR、must_top 命中率并按分类汇总。`tests/test_retrieval.py` 从同一题库 parametrize 生成硬断言用例（唯一事实来源，改题库即改回归集）。

**触发时机**（手工跑，不进 CI，需真实 embedding/LLM 配置与已入库知识库）：

- 改检索逻辑（召回 / RRF / 重排 / MMR / 改写规则）前后各跑一次对比；
- 更换 embedding 模型、重建索引、调重排权重之前必跑，以指标为准入门槛；
- 怀疑检索质量退化时先按报告中的召回路径分层定位（改写 FTS / 原问 FTS / 路径主题 / embedding 各路命中在报告里分列）。S4-b 起检索数据面收敛为版本化 LlamaIndex 单栈，`--layer` 选项已随 legacy 引擎删除。

**跑法**：

```bash
cd backend
.venv/bin/python tests/rag_eval/rag_eval_runner.py                        # 默认引擎（生产同形）全量
.venv/bin/python tests/rag_eval/rag_eval_runner.py --case proto_dns       # 单例排查
.venv/bin/python tests/rag_eval/rag_eval_runner.py --update-baseline      # 确认本轮符合预期后落基线
.venv/bin/python tests/rag_eval/rag_eval_runner.py --fail-on-regression   # recall@15 任何回退即非零退出（默认阈值 0）
.venv/bin/python tests/rag_eval/rag_eval_runner.py --fail-on-regression --regression-threshold 0.05  # 显式放松回归容差
.venv/bin/python tests/rag_eval/rag_eval_runner.py --max-avg-latency 3 --max-p95-latency 10          # 性能门禁
.venv/bin/python tests/rag_eval/rag_eval_runner.py --output report.json   # 机器可读报告（含环境元信息与每例 top-15 路径）
```

**环境元信息（2026-09-13 起）**：报告与基线带 `metadata`——代码版本（git HEAD，容器内无 .git 时用 `RAG_EVAL_CODE_REV` 覆盖）、引擎/策略、生成与 embedding 模型名、FTS/HNSW 配置、活动索引 release 及配置指纹、23 个 `RETRIEVAL_*` 权重快照、题库文件名/题数/sha256（仅评估溯源）。白名单字段，不落 api_base/api_key。**退出码**：0=全绿；1=用例 FAIL / 性能超限 / recall@15 回归；2=基线与当前环境存在 blocking 差异（模型/引擎/权重/release/题库），`--fail-on-regression` 拒绝判定——不同环境的成绩不可直接比较，确认同源后重建基线，或显式 `--ignore-env-mismatch` 降级为警告。`code_revision` 差异只警告不阻断（跨提交对比是正当用法）。旧格式基线（无 metadata）仍可比指标，仅提示无法校验环境。

**判定口径**：`expected_sources` 是「相关组」列表——组内片段互为替代（任一命中即该组召回），组间取覆盖率；`must_top` 要求 top-1 命中；`observe` 用例（负例/模糊问句）只记录不进通过判定。漂移输出 `[退步]`/`[改善]`/`[排名下滑]`/`[新增]`；基线在 `backend/data/rag_eval_baseline.json`（已 gitignore，换机器需重建）。性能门禁 `--max-avg-latency`/`--max-p95-latency`（秒）默认不设线，可用环境变量 `RAG_EVAL_MAX_AVG_LATENCY_SEC`/`RAG_EVAL_MAX_P95_LATENCY_SEC` 兜底（CLI 优先）；报告始终打印 avg/p95。回归阈值默认值 2026-09-13 起为 0.0（对齐「recall@15 不回退」约定），需要容差时显式传 `--regression-threshold`。

**基线缺失/过期处置**：基线文件缺失时 runner 只打印提示并跳过漂移对比（`--fail-on-regression` 也随之不生效），不会报错——即门禁静默失效，不能当作"无回归"证据。过期基线（如架构升级前的旧基线）同理不可用：`backend/data/rag_eval_baseline.a-stage-20260904.json` 是 B1 单数据面收敛前的 A 阶段存档，仅作历史对照，不要拿它跑 `--fail-on-regression`。重建方式二选一：

1. 从部署副本拷回（首选）：部署副本生成基线在容器内 `/app/backend/data/rag_eval_baseline.json`，`docker compose cp backend:/app/backend/data/rag_eval_baseline.json backend/data/`（容器重建会丢，持久副本在部署副本仓库 `backend/data/`）。
2. 本机重新生成：需要运行中的 PG + pgvector、已完成版本化 ingest 的索引、有效 embedding key，然后 `.venv/bin/python tests/rag_eval/rag_eval_runner.py --update-baseline`（46 例真实 embedding 调用，注意费用）。

**处置建议**：出现 `[退步]` 先看报告里该例的 top5 路径判断是召回缺失还是排序下滑；确认新行为可接受再 `--update-baseline` 接纳。新增评估题时先实跑确认当前系统能召回，再决定是否设 `must_top`。

### B2 zhparser 中文 FTS 对照实验与切换（roadmap 三）

能力已代码就绪（`LLAMA_INDEX_FTS_CONFIG` + alembic 0010，默认 simple 不动）。实验前提：postgres 跑的是 `deploy/postgres` 自定义镜像（compose 默认构建）、已完成版本化 ingest、有效 embedding key。runner 是进程内直调检索服务，环境变量按 runner 进程生效，无需重启 backend 即可跑分；但线上服务切换需要重启 backend。

```bash
# A 组（现状 simple）
cd backend && .venv/bin/python tests/rag_eval/rag_eval_runner.py --output /tmp/fts_simple.json

# 迁移（建议维护窗口：换列持 ACCESS EXCLUSIVE 锁并全表重写，千级行秒级）
LLAMA_INDEX_FTS_CONFIG=zh .venv/bin/python -m alembic upgrade head

# B 组（zh）
LLAMA_INDEX_FTS_CONFIG=zh .venv/bin/python tests/rag_eval/rag_eval_runner.py --output /tmp/fts_zh.json
```

**判定**（决策规则见 roadmap「三、切换判定」）：整体 recall@15 不降且中文密集分类（协议检测/主题召回/边界）recall@15 提升 ≥ 0.03 才切换。达线 → `deploy/.env` 落 `LLAMA_INDEX_FTS_CONFIG=zh` + 重启 backend + `--update-baseline` 落新基线；不达标 → 回退：

```bash
# 回退：列换回 simple（zhparser 扩展/配置保留无害），数据无损、无需重 ingest
.venv/bin/python -m alembic downgrade -1   # 或 downgrade 0009_li_hnsw_index
# deploy/.env 移除/改回 simple，重启 backend
```

**排障**：日志出现 `rag_fts_config_mismatch` = env 与生成列 regconfig 不一致（切 env 未跑迁移，或反之），FTS 腿被跳过、其余三路正常——跑迁移或回 env 后重启即可。ingest 报「regconfig 不一致」同理，先 `alembic upgrade head`。stock PG 镜像开 zh 会在迁移的 `CREATE EXTENSION zhparser` 处报错，换自定义镜像。

### B3 权重参数扫描（roadmap 四）

23 个 `RETRIEVAL_*` 启发式权重已进 Settings（默认=线上现值，清单与默认值见 `backend/.env.example` 权重块）。runner 进程内读 Settings，**每组 env 直接跑 runner 即为一个采样点**，无需改代码、无需重启服务：

```bash
cd backend
# 0. 先落参照点（当前权重）
.venv/bin/python tests/rag_eval/rag_eval_runner.py --output /tmp/scan_base.json
# 1. 按组扫描示例（合并权重组）
for w in 0.30 0.34 0.42; do
  RETRIEVAL_W_SEMANTIC=$w .venv/bin/python tests/rag_eval/rag_eval_runner.py \
    --output "/tmp/scan_wsem_${w}.json"
done
# 2. 对比各报告 summary.overall 与 by_category（JSON 机器可读）
python3 -c "
import json,glob
for f in sorted(glob.glob('/tmp/scan_*.json')):
    s=json.load(open(f))['summary']['overall']
    print(f, ' '.join(f'recall@{k}={s[f\"recall@{k}\"]:.3f}' for k in (1,5,15)), f\"MRR={s['mrr']:.3f}\")
"
```

**扫描纪律**：按组扫（先合并权重、再 MMR、后 boosts），一轮只动一个旋钮，不正交全枚举；每轮必须含 `dedup_two_large` 哨兵（B1 唯一 FAIL，MMR 多样性观察点）与失败项所在分类的对比。

**判定规则**：整体 recall@15 不低于当前基线，且目标分类（去重多样性 + 本轮针对的分类）recall@15 改善才接纳；接纳后把权重写进 `deploy/.env`（不是改代码默认值，除非证据充分）+ 重启 backend + `--update-baseline` 落新基线。回退 = 还原 env + 重启。

**与 B2 的时序**：权重最优值依赖 FTS 腿口径（simple/zh 改变候选分布）。扫描在 B2 切换判定之后按生效配置执行；若 B2 迟迟未判定，先在 simple（线上现值）下扫描，zh 切换后复扫核心组。

---

## 11. 产品答案验收（100 题候选集）

**用途**：上线前的答案质量人工验收工作流。候选题集 `backend/tests/rag_eval/product_acceptance.yml`（20 协议 × 3 模板题 + 40 独立题 = 100 题，含拒答/澄清/对抗题），工具 `backend/tests/rag_eval/product_acceptance_runner.py`（五种模式：list / run / 合并评分 / 汇总 / **盲审 --evidence**）。**纪律：expected 是候选标签而非教师确认事实；auto 自动分类仅是机械初判；未完成人工评分前任何输出都不构成验收通过。**

**链路前提**：验收打的是 `POST /api/chat`，S4-b 起该端点恒走产品路径（证据校验、未解答/部分解答/澄清归类、citations——`RAG_PRODUCT_ENABLED` 开关已随收敛删除，无需临时置位）。`AUTH_MODE=local` 下 `quota=0` 不限量，**不需要** `RAG_EVAL_TOKEN`。

**工作流**（list / 盲审 / 合并 / 汇总离线可用；只有 run 调真实接口、消耗模型费用）：

```bash
cd backend
# 1. 选题（默认 list 不调模型）：--case 多选 / --expected 过滤 / --sample N 确定性分层抽样
.venv/bin/python tests/rag_eval/product_acceptance_runner.py --sample 20
# 2. 真实运行（需后端运行；结果落盘 backend/data/product_acceptance/，已 gitignore）
.venv/bin/python tests/rag_eval/product_acceptance_runner.py --run --sample 20 --limit 100
#   单题实测 22–156s：--timeout（或 RAG_EVAL_TIMEOUT_SEC）必须按实测分布调大，
#   否则最慢的题会被记成 system_error，成为假失败
# 3. 盲审核对：逐条引用并排打印「论断句 + 证据正文」，供教师判断引用是否支撑论断
#    证据正文经应用既有的历史引用取数路径取全文（需本机能连到索引库）；
#    backend/ 的 import 路径由工具自举，无需手工设 PYTHONPATH
.venv/bin/python tests/rag_eval/product_acceptance_runner.py --evidence run_xxx.json [--case <id>]
# 4. 教师人工评分：照盲审清单填写评分文件（格式见 runner 模块 docstring），再合并
.venv/bin/python tests/rag_eval/product_acceptance_runner.py --input run_xxx.json --scores scores.yml
# 5. 汇总：不带门禁参数只打印事实且恒退出 0；带门禁参数才判定并给退出码
.venv/bin/python tests/rag_eval/product_acceptance_runner.py --summarize run_xxx.json \
  --min-citation-ok-rate 0.9 --max-system-error-rate 0.05 \
  --write-baseline backend/data/product_acceptance/baseline.json
.venv/bin/python tests/rag_eval/product_acceptance_runner.py --summarize run_new.json \
  --baseline backend/data/product_acceptance/baseline.json    # 与基线对比，附回退清单
```

**盲审语义**：核对的是**归因是否忠实**（这条引用是否支撑它所在的那句话），不是拿答案对金标准。因此不要求教师为 100 题写 gold answer；`gold_answer`/`gold_citations` 是题集里的**可选**键。引用级字段 `unsupported_citation_indexes`（哪几条引用不支撑其论断）与 `missing_evidence`（应引而未引）写越界编号会**报错而不是静默丢弃**。

**门禁与退出码**：`--max-false-reject-rate` / `--min-correct-rate` / `--min-citation-ok-rate` / `--max-system-error-rate` / `--max-pending-review` / `--max-avg-latency-ms` / `--max-p95-latency-ms`。**不给门禁参数时恒退出 0**；给定时 `pass`→0、`fail`→1、`incomplete`→2。**人工评分覆盖率不足 100% 一概 `incomplete` 且拒绝判定**——这是把「未评分即未验收」从免责声明变成可执行门禁。比率类指标无样本时为 `null`（未知），门禁按「未满足」处理，**不当作 0 通过**。耗时门禁不预设数值：先用真实小样本跑出分布再定线。

**失败分类**（五类，人工确认以 `failure_category` 为准）：`retrieval_miss` 召回问题 / `insufficient_evidence` 证据不足 / `generation_error` 生成错误（含该拒未拒）/ `false_reject` 校验误拒 / `system_error` 系统错误。run 记录含 answer/answer_status/citations/elapsed_ms/http_error，供人工核对事实与引用。受限口径：`correct_rate` 与 `false_reject_rate` 的分子分母**都只取可答题**（拒答题问「答案是否正确」没有意义）。

## 12. 多副本部署（backend 横向扩展）

生产冻结形态是单副本（decisions §9）；若需临时扩容验证，机制与边界如下（实测证据 `.logs/s4d_scale_multirep_20260923/`）：

```bash
docker compose up -d --scale backend=2    # nginx 已用 resolver 127.0.0.11 每请求重解析，自动分流
```

- **防双执行**：属主副本对每条在跑任务每 10s 拨新 `rag_requests.updated_at`（`heartbeat_loop`，status='pending' 条件）；其他副本的孤儿扫描/接续只认「陈旧 pending 行」。属主真死（SIGKILL/崩溃）→ 心跳停 → 行转陈旧 → 任一活副本经 `claim_recovery` 原子认领后单份接管重驱动。
- **认领闸**：`_recover_row` 装配前先执行 `claim_recovery`（`UPDATE ... SET updated_at=now() WHERE status='pending' AND updated_at<cutoff`）——两副本并发相中同一孤儿只放行先到者。
- **残留边界**（如实）：非属主副本看该任务 status=`pending`（非 running）；跨副本 cancel 只终态化库行、属主执行体烧到自然收口（浪费算力无错误结果）；admission 队列是进程级，N 副本=N×20 活动配额；nginx 无粘性，续传靠客户端重试轮转到属主；PG 连接数按副本数放大（见 §2）。

---

## 附：常用定位入口速查

| 目的 | 命令 |
| --- | --- |
| 最近错误日志 | `docker compose logs backend 2>&1 | grep '"level": "ERROR"' | tail -50` |
| 认证失败 | `grep '"event": "auth_failed"'` |
| harness 调用失败 | `grep '"event": "mcp_call_failed"'`，再看 `docker compose logs harness` |
| 分阶段耗时 | `grep '"event": "chat_trace"'` 看 `stages_ms` |
| 会话存储占用 | `curl -H "X-Maintenance-Key: <key>" http://127.0.0.1:8090/api/internal/session-storage` |
| 手工清理过期会话 | `docker compose exec backend python scripts/purge_sessions.py --apply` |
| 健康探测手工验证 | `HEALTH_URL=http://127.0.0.1:9 deploy/healthcheck.sh --dry-run` |
