# 代码通读学习路线

> 用途：个人通读代码、准备面试的学习路线。依据 2026-09-25 的代码静态阅读整理，行号会随代码演进漂移，以函数名为准。
> 本文件不是项目事实或决策来源：实现现状以 [architecture_current.md](../architecture_current.md) 为准，决策以 [decisions.md](../decisions.md) 为准。
> 语法预备知识见 [pre/README.md](pre/README.md)。

## 0. 全局鸟瞰

**规模**：后端 `app/` + `scripts/` 约 1.78 万行 Python，前端约 4.5k 行（`main.jsx` 2363 行），70+ 测试文件，135 次提交。最大模块：`user_service`(926)、`agent/tools`(900)、`retrieval_service`(844)、`rag_product_service`(726)、`agent/service`(700)。

**一次问答请求的主链路**（后面每个阶段都在拆这条链）：

```
POST /api/chat/stream (api/chat.py)
 └─ RagProductService.probe_replay()    幂等探测：重放 / 接续 / 新请求
 └─ admission.acquire()                 进程级有界队列（20 活动 / 20 等待）
 └─ RagProductService.prepare()         建会话 → 会话记忆 → 同一事务登记任务、扣配额、寄存输入
 └─ task_service.start_reserved_task()  注册表托管，断线不等于取消
     └─ RagProductService.run() → _candidate()
         └─ AgentOrchestrator._build_run() → LangGraph: planner ⇄ tools → finalize
              ├─ kb_search → RetrievalService（改写 → 4 路召回 → RRF → 重排 → MMR）
              └─ grade_plugin → MCP → 隔离 harness 容器
         └─ validate_answer()           确定性证据检查（在线 judge 默认关）
         └─ 答案 + 引用 + 请求终态同一事务落库 → 之后才下发 SSE
```

**阅读方法**：先纵向把一个请求从头跟到尾，再横向逐模块精读。每个阶段读代码时同时看 `docs/decisions.md` 里对应的决策：代码告诉你"怎么做"，决策文档告诉你"为什么"，面试时后者更值钱。

---

## 阶段 1：跑起来 + 基础设施层

**读**：`config.py` → `db.py` → `storage/interfaces.py` → `storage/postgres.py` / `sqlite.py` → `core/logging_config.py` → `core/auth.py` → `main.py`

**重点**：
- [main.py](../../backend/app/main.py) 启动钩子：生产环境先校验 alembic 版本等于代码 head → `init_db()` → 预热 LlamaIndex → 跑一轮孤儿任务恢复 → 起维护循环与心跳循环。
- `/api/ready` 为什么不只查 `SELECT 1`，还要核对"声明节点数 = 成员行数 = 物理节点数"及 embedding 配置指纹。
- `DatabaseProvider` 用 Protocol 把方言差异参数化（`placeholder` 等属性），业务代码不判断 `DATABASE_BACKEND`。

**自测**：为什么生产只用 PG、SQLite 只留测试？为什么是 `init_db()` → `alembic upgrade head` 而不是 `stamp head`？

**动手**：按 AGENTS.md 起本地环境，跑 `pytest -m "not live and not postgres"`；读懂 `tests/conftest.py` 如何强制每个用例用临时 SQLite + 不可达上游地址。

---

## 阶段 2：知识入库与版本化索引

**读**：`data/knowledge_sources.yml` → `services/knowledge_sources.py`（切块）→ `services/rag_index_service.py` → `services/llama_index_service.py` → `scripts/ingest_llama_index.py`

**重点**：
- 切块策略：Markdown 按标题、C 代码按函数/结构体，超长块二次拆分（`chunk_markdown` / `chunk_code` / `split_long_chunk`）。
- **增量计划** `plan_nodes`：以 `(source_key, ordinal)` 定位，正文与元数据逐项比较（不用 hash），配置不变时复用旧 id，否则新建 `li_<hex32>`。
- **先嵌入后发布** `ingest_release` / `_publish`：对 `rag_index_state` 做 CAS 原子切换；旧节点只读保留；`rollback_release` 回滚前强制校验配置指纹与节点完整性。见 [rag_index_service.py](../../backend/app/services/rag_index_service.py)。

**自测**：两个摄取任务并发会怎样？embedding 跑一半失败，库处于什么状态？为什么换了 embedding 模型不能直接回滚到旧版本？

---

## 阶段 3：检索管线（简历上的技术核心）

**读**：`rag/interfaces.py` → `storage/retrieval_store.py` → `storage/li_retrieval_store.py` → `services/retrieval_service.py`（从 `search()` 往下读）

**重点**（均在 [retrieval_service.py](../../backend/app/services/retrieval_service.py)）：
1. **查询改写** `HeuristicLLMQueryRewriter`：本地规则 + LLM 改写，带缓存。embedding 用"原问题 + 改写"拼接，注释里有中文文档召回与代码召回的取舍数据。
2. **4 路召回 + RRF 融合** `_fetch_candidate_rows`：改写 FTS、原问题 FTS、路径片段、向量语义；每路失败都降级而不是抛异常。
3. **综合重排** `_retrieve_sync`：语义 / 词法 / 标识符 / 文件名与主题加分 / RRF 先验 / 定义结构加分加权求和；无向量时切换另一组权重；23 个权重已参数化。
4. **MMR 多样性** `_select_diverse_chunks`：Jaccard 冗余惩罚 + 单文件上限，token 集合预计算（注释说明了性能优化原因）。
5. 同步的 DB 查询与 CPU 打分整体用 `asyncio.to_thread` 移出事件循环。
6. release 绑定：`li_retrieval_store` 固定版本时 JOIN `rag_release_nodes`；会话级锁版本（`session_service.resolve_release`）。

**自测**：RRF 的 k=60 是什么意思？为什么不直接按向量相似度排序？MMR 解决什么问题？embedding 上游挂了检索会怎样？

**动手**：读 `tests/rag_eval/queries.yml` 与 `rag_eval_runner.py`，弄清 recall@k、MRR 怎么算；看 `docs/runbook.md` §10 的权重扫描流程。

---

## 阶段 4：LLM 调用基础设施

**读**：`core/llm_client.py` → `rag/providers.py` → `core/async_cache.py` → `core/upstream_gate.py` → `core/usage.py` → `core/metrics.py` / `tracing.py`

**重点**：httpx 连接池复用、可重试错误判定与退避；`AsyncTTLCache.get_or_create` 把相同 key 的并发请求合并为一次（singleflight）；`upstream_slot` 信号量限制上游并发；`UsageRecorder` 记账是旁路，不改变返回值与异常语义，用量 unknown 不等于 0。

**自测**：并发合并与普通缓存有何区别？为什么用量要区分 unknown 与 0？

---

## 阶段 5：Agent 编排层

**读顺序**：`agent/prompts.py` → `agent/graph.py` → `agent/context.py` → `agent/tools.py` → `agent/mcp_client.py` → `agent/reference.py` → `agent/service.py`

**重点**：
- **图结构** [graph.py](../../backend/app/agent/graph.py) `build_agent_graph`：planner → tools 循环 → finalize。转 finalize 的四种原因：轮数上限、token 预算、软截止、planner 直接终答。达限时给未执行的 tool_call 补合成 ToolMessage 配对（`_pair_unexecuted_tool_calls`）；只读工具轮并发，含 `grade_plugin` 的轮串行。
- **上下文修剪** [context.py](../../backend/app/agent/context.py) `prepare_messages`：保留最近工具轮原文，更早的压成摘要，超预算按单元丢弃。
- **动态 system prompt** [service.py](../../backend/app/agent/service.py) `_build_prompt_builder`：失败次数达阈值升级反馈档位；评测失败时带外注入教师参考实现并重述红线；讲解模式块；学习者画像。
- **`grade_plugin` 是全仓最复杂的函数**（[tools.py](../../backend/app/agent/tools.py)）：执行源优先级"上传原件 > 服务端抽取粘贴块 > 恢复寄存源 > 模型参数复述"；同请求去重；作业账本接续；配额与账本同事务；harness `job_id` 幂等；基础设施错误退配额；编译错误不把原文回给模型（防源码泄露）。

**自测**：为什么不信任模型复述的源码？为什么没有 checkpointer，重启靠什么恢复？MCP 重试为什么"已提交的调用绝不重试"？

**动手**：跑 `tests/agent/test_golden_scenarios.py`，挑 2 个场景从 fake model 输出一路跟到最终答案。

---

## 阶段 6：产品化问答编排（工程深度所在）

**读**：`api/chat.py` → `services/rag_product_service.py` → `services/rag_issue_service.py`（`input_hash` / `probe` / `begin_admitted` / `finish`）→ `services/conversation_memory.py` → `services/session_service.py` → `services/citation_service.py`

**重点**：
- **幂等身份分层**：客户端 `request_id` 与服务端 `task_id` 分离；`input_hash` = sha256(归一化问题 + 附件字节)。`probe` 三态：replay 直接返回、attach 接续在跑任务、miss 新建；身份不匹配 409。
- **原子受理** `RagProductService.prepare`：唯一键、临时输入、用户消息、日配额同事务写入；并发首发的败方转为接续赢家。
- **Admission 有界队列** `Admission`：`asyncio.Condition` 实现，满员 429 + `Retry-After`；恢复扫描用 `try_acquire`，不挤占实时请求。
- **证据收窄而非拒答** `_candidate` 中的 `bound_evidence`：注释记录了旧行为把 9/15 可答题误判为拒答。
- 先落库再展示；SSE 不是 token 级流式，保存后一次性下发 `sources` + `delta`（原因见 decisions）。

**自测**：用户双击发送、网络重试、刷新页面分别走哪条分支？为什么要"先存后发"？

---

## 阶段 7：任务托管与故障恢复

**读**：`services/task_service.py`（全文）→ `api/tasks.py` → `rag_issue_service` 的 `orphaned_pending` / `claim_recovery` / `mark_interrupted`

**重点**（[task_service.py](../../backend/app/services/task_service.py)）：
- 进程级注册表 `TASKS`；事件扇出到"回放日志 + 每订阅者一个无界队列"，订阅者慢或断开都不反压执行体。
- `_run_with_deadline` 的 `finally` 收尾：只有库行已终态才删执行输入；terminal / done / 哨兵之间无 await，订阅者看到的状态变化是原子的。
- 孤儿恢复 `recover_orphans`：过宽限期的 pending 行从 `rag_task_inputs` 取回输入重驱动，harness 作业账本复用已完成评测。
- 多副本防双执行：属主每 10s 刷新 `updated_at` 心跳（`heartbeat_loop`），`claim_recovery` 条件更新原子认领。

**自测**：断线和取消在代码上怎么区分？为什么心跳周期要远小于宽限期？两副本同时扫到同一孤儿，谁赢？

---

## 阶段 8：业务模块

**读**：`services/user_service.py`（组长 → 老师 → 学生树状权限）→ `api/auth.py` / `admin.py` → `core/rate_limit.py`（登录防爆破、不泄露账号存在性）→ `services/assignment_service.py` → `services/harness_grading.py` → `services/assignment_grading_attempts.py` → `api/assignments.py`（含讲解入口）→ `services/usage_service.py`

**重点**：权限 scope 由服务端写入；成绩只存 hash 不存源码；按通过比例折算、截止前取最高分；讲解任务用 uuid5 派生确定性身份（`explain_request_identity`），重复点"讲解"天然命中阶段 6 的幂等逻辑。

---

## 阶段 9：前端

**读**：`frontend/src/api/client.js`（SSE 解析、续传）→ `taskRecovery.js` / `grades.js` → `main.jsx` 的 `App`、`makeStreamHandlers`、`HomeworkPage` → `RagWorkspace.jsx`

**重点**：SSE 帧解析对未知事件宽松；无 `done` 的 EOF 保留 task_id 续传；Markdown 经 dompurify 消毒。对照 `client.test.js` 一起读。

---

## 阶段 10：工程化与部署

**读**：`backend/alembic/versions/`（按编号速览，每个迁移对应一个功能批次）→ `docker-compose.yml` → `deploy/nginx.conf` → `deploy/*.sh` → `.workflow/*.yml`

**重点**：harness 挂在 `internal: true` 网络无外网、只读根文件系统、tmpfs 必须显式 `exec`（注释记录了踩坑）；backend 是唯一跨两个网络的服务；`/metrics` 不对外反代；SSE 需 `proxy_buffering off`。

---

## 面试准备：能讲透的 6 个故事

1. **混合检索**：4 路召回 + RRF + 代码信号重排 + MMR；为什么 embedding 用"原问题 + 改写"拼接。
2. **版本化索引**：增量计划、先嵌入后 CAS 发布、会话锁版本、回滚前校验。
3. **可靠任务模型**：幂等身份分层、原子受理、断线不等于取消、孤儿恢复、多副本心跳认领。
4. **Agent 护栏**：轮数上限 / token 预算 / 软截止 / 上下文修剪 / 合成 ToolMessage 配对 / 执行源不信任模型复述。
5. **安全隔离**：学生代码在无外网只读容器中运行，成绩只存 hash，源码统一脱敏口径。
6. **性能问题与迭代**：`architecture_current.md` §5 记录 L4 彩排 No-Go（60 次问答 35 次系统错误，p95 122.13s），之后 L5 做了并发工具轮、软截止等优化——具体改进数字以 `docs/roadmap.md` §15 为准（整理本文时未核对）。

**简历数字要谨慎**：文档明确标注"未验证"的项（live 质量题集、>2 副本、生产主机验证）不要写成已达成；引用的数字以文档原始证据为准。
