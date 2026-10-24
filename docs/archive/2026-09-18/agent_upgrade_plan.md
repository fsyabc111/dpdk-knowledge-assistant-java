> 历史归档：2026-09-18。原路径：`docs/agent_upgrade_plan.md`。
> 适用版本：正文各条记录注明的日期、提交与环境；归档所据助手仓库快照为 `7048d8c`，不代表整篇已在该版本复验。
> **不再作为当前实施指令。** 正文保留当时的决策、完成声明和测试数据；未注明版本或存在冲突的结论不能外推为当前验收结果。
> 当前入口：[文档导航](../../README.md) · [统一路线](../../roadmap.md) · [当前决策](../../decisions.md)。
> 正文中的纯文本旧路径与行号用于还原历史上下文；本次仅修正可点击的相对链接。

# FlowSense 教学 Agent 升级规划

> 版本:v1(2026-08-23,grill-me 三轮决策收敛)
> 状态:已确认,待实施
> 前置阅读:`docs/archive/production_roadmap.md`(M1–M6 已完成,已存档)、`docs/dev_notes.md`

本文档记录将 ai_chat_assistant 从单轮 RAG 问答系统升级为**学生助教 Agent** 的完整决策与实施规划。所有关键决策已经过三轮设计树收敛,本文档是唯一事实来源。

---

## 1. 定位与目标

**学生助教 Agent**:学生在 Web 聊天界面提问、粘贴或上传插件代码,Agent 自主决策调用工具——知识库答疑、协议用例查询、插件模板生成、调用 teaching_harness 检测——并给出分级教学反馈。

典型闭环场景:

```
学生:我要做 DNS 作业,有哪些测试场景?        → list_cases("dns")
学生:给我一个插件骨架                        → plugin_template
学生:[上传 2024001_张三_dns.c] 帮我看看      → grade_plugin(dns, 源码)
Agent:dns_basic_3 挂了,期望 X 实际 Y,想想…  → 分级引导反馈
```

**成功标准(两阶段)**:

- 阶段 A(原型):核心链路端到端可演示——提问 → Agent 决策 → 调 harness 检测 → 分级反馈
- 阶段 B(课程可用):一个班学生真实使用一学期——账号、配额、稳定性、防滥用、成绩历史

**非目标**:

- 不做教师批改批处理入口(M3 仅提供成绩查询视图)
- 不做通用 DPDK 开发助手
- 不重写认证、存储、检索、注入等已生产化的模块

---

## 2. 关键决策汇总(收敛记录)

| # | 决策点 | 结论 | 理由 |
|---|--------|------|------|
| D1 | Agent 职责 | 学生助教(非教师批改/全流程/通用助手) | teaching_harness 已备检测能力,助教形态最自然 |
| D2 | harness 关系 | 独立仓库、独立容器,经 MCP 被调用 | harness 已内置 MCP server(v2 即为此设计);执行学生代码需要隔离边界 |
| D3 | 演进方式 | **半重写**:保留认证/存储/检索/注入/前端壳,仅将 `AgentService` 聊天管线替换为 LangGraph 编排层 | 已有模块与 Agent 化正交,重写只产生回归风险 |
| D4 | 模型与资源 | 继续 DashScope OpenAI 兼容 API(qwen 支持 tool calling),模型可换;无 GPU;部署仍 WSL2 + docker-compose | 现状可满足,不构成瓶颈 |
| D5 | 工具集(MVP→全量) | 五件:`kb_search`、`grade_plugin`、`list_protocols`、`list_cases`、`plugin_template` | 均为现成能力的封装,构成"模板→编写→检测→反馈"教学闭环 |
| D6 | 代码提交入口 | 文件上传 `.c`(主)+ 聊天粘贴(兜底) | 上传天然携带 `学号_姓名_协议.c` 命名信息;粘贴是讨论时的自然动作 |
| D7 | 反馈教学法 | 分级:默认给用例差异+思路引导;多次失败或学生明确要求后可给完整解答(教师可配阈值);Agent 可读 `answer/` 理解错因但**禁止输出答案代码** | 一刀切苏格拉底或一刀切给答案都不可取 |
| D8 | 旧红线翻转 | 废止"不上传、不运行、不评测学生插件代码"边界(`docs/dev_notes.md` 及 `agent_service.py` 系统提示词),M3 同步更新文档 | 助教 Agent 的核心场景即评测学生代码 |
| D9 | 安全隔离 | harness 跑在独立容器:无外部网络、低权限用户、ulimit + 超时;M1 不做一次性容器级隔离 | 从原型到课程可用的平滑路径 |
| D10 | MCP 传输 | FastMCP 从 stdio 改为 streamable HTTP,harness 容器监听 compose 内网端口 | stdio 跨不了容器;改动仅几行;复用已测试的 MCP 契约 |
| D11 | LangGraph 状态 | **暂不启用 checkpointer**;会话连续性继续走现有 `session_service` + `conversation_memory`;出现长任务恢复需求再加 | 检测任务秒级完成,原型期无中断恢复需求 |
| D12 | 检测数据 | 新表存最小集:学生/时间/协议/分数/用例结果 JSON/代码 hash;不存源码全文 | 支撑 B 阶段成绩历史;hash 足够追溯,防抄袭比对将来另议 |
| D13 | 评测配额 | 所有登录学生可用,独立日配额(远紧于聊天)+ 代码大小上限 + 单协议限并发;教师(`operator` 角色)无配额 | 评测真实编译运行代码,成本高且危险 |
| D14 | 测试策略 | 黄金场景集(20–30 例,mock LLM 确定性单测)从 M1 与提示词同步演进 + 小规模 live 评测感知模型漂移 | 现有 CI 门禁文化延续到 Agent 层 |
| D15 | 双副本治理 | `ai_chat_assistant/teaching_harness/` 快照删除并以 `E:\teaching_harness` 为唯一事实来源(通过 compose 挂载或构建上下文引用) | 消除双副本漂移 |

---

## 3. 目标架构

```
┌───────────────────────── docker-compose ─────────────────────────┐
│                                                                  │
│  frontend(nginx, 443)──▶ backend(FastAPI, 内网)                 │
│                            │                                     │
│                            ├─ /api/chat/stream(SSE,契约不变)    │
│                            │     └─ LangGraph Agent 编排层(新)  │
│                            │           ├─ kb_search      → 现有混合检索 │
│                            │           ├─ plugin_template→ 现有 TemplateService │
│                            │           └─ MCP Client(HTTP)────┐ │
│                            ├─ 认证/配额/会话/记忆(保留)        │ │
│                            │                                  ▼ │
│  postgres(pgvector,内网)◀─┤                    harness 容器(新) │
│                                                            │    │
│                                            FastMCP streamable HTTP│
│                                                            │    │
│                                            gcc + traffic_harness  │
│                                            (低权限用户/ulimit/    │
│                                             无外部网络)           │
└──────────────────────────────────────────────────────────────────┘
```

### 3.1 保留不动的部分

- `backend/app/core/`(auth、rate_limit、llm_client、缓存、tracing、metrics)
- `backend/app/rag/`(检索与生成的 Protocol 接口及两个 provider 家族)
- `backend/app/services/` 中的 retrieval / llama_index / ingestion / session / conversation_memory / template / user 服务
- `backend/app/storage/`(psycopg 方言层)
- 前端整体结构(登录、会话、管理页、用量页),仅扩展聊天视图
- 部署骨架:nginx、backup、healthcheck、auto_ingest、CI 流水线

### 3.2 替换的部分

- `backend/app/services/agent_service.py` 的单轮 RAG 编排内部实现 → LangGraph 图。**`/api/chat` 与 `/api/chat/stream` 的请求/响应契约保持不变**,前端无感切换。

### 3.3 新增的部分

| 组件 | 位置 | 说明 |
|------|------|------|
| Agent 图编排 | `backend/app/agent/`(新包) | LangGraph 状态图:路由(是否需工具)→ 工具执行 → 反馈生成 |
| 工具层 | `backend/app/agent/tools/` | 五个工具的封装,统一错误模型与超时 |
| MCP 客户端 | `backend/app/agent/mcp_client.py` | streamable HTTP 连接 harness 容器,连接池 + 重试 |
| 检测记录 | 新表 `grading_submissions` | 见 §6 |
| harness 服务 | `docker-compose.yml` 新 service | 见 §5 |
| 前端扩展 | `frontend/src/main.jsx` + 新组件 | 文件上传按钮、工具调用过程展示(SSE 新事件) |
| 黄金场景测试 | `backend/tests/agent/` | 见 §8 |

---

## 4. Agent 设计

### 4.1 LangGraph 图结构(M1 最小版)

```
                 ┌──────────┐
   用户消息 ───▶ │  planner  │  LLM + 系统提示词 + 工具 schema
                 └────┬─────┘
                      │ tool_calls?
              ┌───────┴────────┐
              ▼                ▼
        ┌──────────┐     ┌──────────┐
        │ tool 执行 │     │ 直接生成  │
        └────┬─────┘     │ (kb 答疑) │
             │ 结果回填    └──────────┘
             ▼
        ┌──────────┐
        │  planner  │  循环,直至无 tool_call(硬上限,见下)
        └────┬─────┘
             ▼
        ┌──────────┐
        │ 反馈生成  │  若涉及 grade_plugin 结果,走分级反馈提示词(§4.3)
        └──────────┘
```

- **循环上限**:单次请求最多 5 轮工具调用,防失控
- **会话状态**:入图前由现有 `conversation_memory` 构建上下文(最近 6 轮原文 + 摘要),图本身无状态(D11)
- **流式**:LangGraph 的 astream 事件映射到现有 SSE 契约;工具调用映射为新增事件类型(§4.4)

### 4.2 工具契约

| 工具 | 来源 | 关键参数 | 说明 |
|------|------|----------|------|
| `kb_search` | 封装现有 `KnowledgeRetriever` | `query` | 返回 chunk + 引用信息, citations 走现有机制 |
| `grade_plugin` | MCP → harness | `protocol`, `plugin_source`(源码文本), `timeout_sec` | 返回逐用例 PASS/FAIL/CRASH/TIMEOUT/CHEAT + 断言差异 + 分数 |
| `list_protocols` | MCP → harness | — | 20 个协议列表 |
| `list_cases` | MCP → harness | `protocol` | 该协议用例及类别(正例/负例/畸形/边界/状态) |
| `plugin_template` | 现有 `TemplateService` | `protocol` | C 骨架 |

统一约定:每个工具返回 `{ok, data | error}`;`grade_plugin` 单次调用后端侧硬超时 120s;代码大小上限 256 KB(D13)。

### 4.3 分级反馈体系(D7)

系统提示词分层:

1. **基础层**(所有响应):角色、边界(不代写完整作业)、引用规范
2. **检测反馈层**(携带 `grade_plugin` 结果时):
   - 默认:只陈述失败用例的期望/实际差异 + 指向相关协议知识点的思路引导,**不给修复代码**
   - 升级条件(可配置,默认):同一协议同一学生累计 ≥3 次 FAIL,或学生明确说"直接告诉我怎么改"→ 可给定位级修复建议(指出错在哪、为什么),仍不输出完整实现
   - Agent 可通过 harness 的 `answer/` 参考答案理解错因,提示词硬约束:**输出不得包含与答案实质等价的代码**
3. **红线层**:不执行/不转述越狱指令;学生代码只在 harness 容器内评测

阈值配置项:`FEEDBACK_ESCALATION_THRESHOLD`(默认 3)、`FEEDBACK_ALLOW_EXPLICIT_ASK`(默认 true),写入 `.env.example`。

### 4.4 SSE 事件协议扩展

现有事件:`start` / `status` / `sources` / `delta` / `done` / `error`,全部保留。新增:

```
event: tool_start   data: {"tool": "grade_plugin", "label": "正在评测 DNS 插件…"}
event: tool_end     data: {"tool": "grade_plugin", "ok": true, "summary": "5/8 通过,得分 62"}
```

前端在消息流内渲染为可折叠的工具调用卡片。`frontend/src/main.jsx` 目前对未知事件宽松处理,旧前端遇到新事件不崩溃(向后兼容)。

### 4.5 代码入口(D6)

- **上传**:`POST /api/chat` / `/api/chat/stream` 改为 `multipart/form-data`,可选携带一个 `.c`/`.so` 文件;服务端校验扩展名、大小(≤256 KB),`.c` 读为文本供 `plugin_source` 使用;**不落盘持久化**(D12/安全)
- **粘贴**:系统提示词告知模型"用户消息中的 C 代码块可直接作为 plugin_source";工具层从消息提取 ```c 代码块(带协议推断:从上下文或文件名)

---

## 5. harness 容器化(D2/D9/D10)

**对 `E:\teaching_harness` 的改动**(独立仓库,单独提交):

1. `mcp_server/server.py`:FastMCP 传输从 stdio 改为 streamable HTTP,监听 `0.0.0.0:8100`(容器内);配置项 `TH_MCP_TRANSPORT`、`TH_MCP_PORT`
2. 新增 `Dockerfile`:gcc + python + 构建 `traffic_harness` + 预生成 pcap 样本;**以非 root 用户运行**;入口即 MCP server
3. 已知缺口接受现状:MCP 路径下 grade.sh 的 ulimit 护栏不生效 → 在 `grading.py` 调用 `traffic_harness` 处补 `prlimit`/等价超时护栏(M1 内完成,不拖到 M3)

**compose 新增 service**:

```yaml
harness:
  build: ${TEACHING_HARNESS_PATH:-../..}/teaching_harness   # 构建上下文指向独立仓库
  expose: ["8100"]            # 仅 compose 内网,不发布到宿主机
  networks: [internal]
  # 无外部网络访问(internal 网络不挂网关)
  read_only: true
  tmpfs: ["/tmp:size=256m"]   # gcc 临时目录
  mem_limit: 2g
  pids_limit: 256
  environment:
    TH_HARNESS_HOME: /opt/teaching_harness
```

backend 侧新增配置:`HARNESS_MCP_URL=http://harness:8100/mcp`。

---

## 6. 数据模型(D12)

新表(alembic 新 revision,遵循现有迁移惯例):

```sql
CREATE TABLE grading_submissions (
    id            TEXT PRIMARY KEY,            -- grade_<ulid>
    user_id       TEXT NOT NULL REFERENCES users(id),
    session_id    TEXT REFERENCES assistant_sessions(id),
    protocol      TEXT NOT NULL,
    code_hash     TEXT NOT NULL,               -- sha256(源码)
    score         INTEGER NOT NULL,            -- 0-100
    passed        INTEGER NOT NULL,
    total         INTEGER NOT NULL,
    results_json  JSONB NOT NULL,              -- 逐用例结果与断言差异
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_grading_user ON grading_submissions(user_id, created_at DESC);
CREATE INDEX idx_grading_proto ON grading_submissions(protocol, created_at DESC);
```

- 不存源码全文;对话消息表照旧承载讨论内容
- 升级条件统计(§4.3)查询同 `user_id + protocol` 的近期 FAIL 次数
- M3 教师视图基于 `idx_grading_user` 聚合

配额:`usage_counters` 增加 `kind` 维度(chat / grading),grading 默认日配额 20(`GRADING_DAILY_QUOTA`),`operator` 角色豁免(D13)。

---

## 7. 配置项汇总(写入 `.env.example`)

| 变量 | 默认 | 说明 |
|------|------|------|
| `AGENT_ENABLED` | `true` | 总开关,false 回退到原单轮 RAG 管线(灰度/兜底) |
| `AGENT_MAX_TOOL_ROUNDS` | `5` | 工具循环上限 |
| `HARNESS_MCP_URL` | `http://harness:8100/mcp` | harness MCP 端点 |
| `GRADING_TIMEOUT_SEC` | `120` | 单次评测后端硬超时 |
| `GRADING_MAX_CODE_BYTES` | `262144` | 256 KB |
| `GRADING_DAILY_QUOTA` | `20` | 学生日评测配额 |
| `FEEDBACK_ESCALATION_THRESHOLD` | `3` | 分级反馈升级阈值 |
| `FEEDBACK_ALLOW_EXPLICIT_ASK` | `true` | 学生明确要求时是否可升级反馈 |

---

## 8. 测试策略(D14)

**黄金场景集**(`backend/tests/agent/`,约 25 例起步,mock LLM 的 tool_calls 响应做确定性单测):

- 路由正确性:"DPDK 是什么" → 仅 `kb_search`;"贴了段 DNS 插件代码" → 必须 `grade_plugin`;"DNS 作业测什么" → `list_cases`
- 入口解析:粘贴代码块提取、上传文件协议推断(文件名 `学号_姓名_协议.c`)、协议缺失时的澄清行为
- 反馈纪律:构造 FAIL 结果,断言首轮响应不含修复代码;构造第 4 次 FAIL,断言允许给定位建议
- 护栏:工具循环上限、配额 429、超大代码拒绝、harness 超时/不可用的降级话术(不 500,沿用现有 fallback 哲学)
- MCP 契约:复用 harness 侧 pytest 思路,对 `grade_plugin` 做容器级集成测试(标记 `live`,不进 CI 默认集)

**live 评测**:10 题小集,模型升级后手工触发跑分,观察工具选择漂移。

**沿用**:现有 pytest 门禁(`--cov-fail-under=60`)、vitest、CI 流水线不变;新增 agent 测试并入 `not live and not postgres` 默认集。

---

## 9. 里程碑

### M1 — 原型纵切(端到端可演示)

1. harness 仓库:MCP streamable HTTP 化 + `grading.py` 补资源护栏 + Dockerfile
2. compose 新增 harness 服务;backend 配置 `HARNESS_MCP_URL`
3. `backend/app/agent/`:LangGraph 图 + MCP client + `kb_search`、`grade_plugin` 两件工具
4. `POST /api/chat/stream` 支持 multipart 上传;SSE 新增 `tool_start`/`tool_end`
5. 前端:上传按钮 + 工具调用卡片
6. `grading_submissions` 表 + 评测配额(独立计数)
7. 黄金场景集首批 ~15 例(路由 + 入口 + 护栏)
8. 删除仓库内 `teaching_harness/` 快照(D15)

**验收**:学生登录 → 上传 DNS 插件 → Agent 自动调检测 → 流式展示评测过程 → 给出引导式反馈 → 成绩落库可查;`AGENT_ENABLED=false` 时行为与现状完全一致。

### M2 — 教学闭环(2026-08-24 已完成)

1. ✅ 补齐 `list_protocols` / `list_cases` / `plugin_template` 三工具
2. ✅ 分级反馈提示词体系 + 升级阈值统计查询(§4.3)
3. ✅ 粘贴代码块提取与协议推断
4. ✅ 黄金场景集补满 ~25 例(含反馈纪律)
5. ✅ 参考答案访问通道(harness MCP 新增只读工具 `get_reference_answer`,仅供反馈生成层使用)

### M3 — 课程可用（2026-08-26 已完成，见 docs/agent_m3_progress.md）

1. ✅ 安全加固收尾:复核 ulimit/cgroup/超时链路与容器逃逸面(harness compose 补 user/cap_drop/no-new-privileges,信任模型与 CVE 备忘入 runbook §8)
2. ✅ 教师成绩视图(`operator` 角色,按学生/协议聚合,前端管理页扩展;`GET /api/grading/submissions|summary`)
3. ✅ 多轮并发压测(`load_chat_concurrency.py --mix grading` 混合负载)
4. ✅ 文档:`docs/dev_notes.md` 边界声明更新(D8)、runbook 增加 harness 服务运维(§6)与 live 评测流程(§9)、README 更新
5. ✅ live 评测集 + 模型漂移检查流程(`tests/agent/live_eval.yml` 11 题 + `live_eval_runner.py` + 基线对比)

---

## 10. 风险与对策

| 风险 | 等级 | 对策 |
|------|------|------|
| 学生恶意代码逃逸 harness 容器 | 高 | M1:低权限用户 + 无网络 + tmpfs + pids/mem 限额 + fork 隔离已有;M3 专项复核;现状信任模型文档化告知使用者 |
| qwen tool calling 不稳定(漏调/乱调) | 中 | 黄金场景集卡口;提示词中给路由示例;`AGENT_ENABLED` 开关可整体回退 |
| LangGraph 学习/调试成本 | 中 | 图保持扁平(§4.1);不引入 checkpointer/subgraph 直至有真实需求(D11) |
| Agent 泄露参考答案 | 中 | 提示词硬约束 + 黄金场景"反馈纪律"断言;answer/ 不进入知识库索引 |
| 双仓库(teaching_harness / ai_chat_assistant)版本漂移 | 低 | compose 构建上下文锁路径;harness 侧改动走其自身 git 里程碑 |
| 评测资源耗尽(死循环插件) | 低 | 配额 + 超时 + 单协议限并发(D13) |

---

## 11. 明确不做

- 不重写认证/存储/检索/注入(D3)
- 不启用 LangGraph checkpointer(D11,需要时再议) — **2026-08-27 已被 M4 翻转**：引入 `AsyncPostgresSaver` 仅做中断/恢复（不取代会话层），条件与约束见 `docs/agent_m4_decisions.md` E2/E3/E10
- 不存学生源码全文、不做防抄袭比对(D12,将来另议)
- 不做教师批处理批改入口(M3 仅查询视图)
- 不动 harness 的 152 个用例集与协议矩阵本体
