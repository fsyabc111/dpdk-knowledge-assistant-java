# Agent 升级 · M1+M2 进度存档

> 日期:2026-08-24
> 总规划:`docs/agent_upgrade_plan.md`(决策 D1–D15 的唯一事实来源)
> 当前状态:**M1、M2 已完成**(M1:S1 地基 / S2 agent 编排层 / S3 前端 / S4a 黄金场景与收尾,live 冒烟全链路通过,三个冒烟问题已修复;M2:五工具齐备 + 参考答案带外注入 + 协议推断 + 黄金场景 25 例)

---

## 已完成

### S1a — teaching_harness 容器化(仓库 `E:\teaching_harness`)

- `mcp_server/config.py` + `server.py`:新增 `TH_MCP_TRANSPORT`(stdio|http,默认 stdio)、`TH_MCP_HOST`、`TH_MCP_PORT`(8100);http 模式走 FastMCP streamable HTTP,端点 `/mcp`;三个工具契约零改动
- `mcp_server/grading.py`:gcc 编译与 harness 运行两处补 `preexec_fn` rlimit 护栏(CORE=0/NPROC=512/NOFILE=256/AS=8GB,CPU 60s/600s),非 Linux 跳过
- 新增 `Dockerfile`(python:3.12-slim,非 root 用户 teaching/10001)+ `.dockerignore`
- 验证:pytest 13 全绿;smoke 全过;**Docker 实测构建成功,容器内 grade_plugin 跑通(dns 参考插件 100 分,9/9)**;镜像 `teaching-harness-mcp` 留在本机 docker

### S1b — backend 地基

- 依赖:`langgraph==1.2.11`、`langchain-openai==1.1.9`、`mcp==1.12.4`。**注意**:后两个是被既有 pin 压住的次新版本(llama-index 要 `openai<2`;显式 pin `pydantic==2.10.4` 卡住 `mcp<1.13`),将来升级需先解这两个钉
- DB:alembic `0004_grading_submissions`;`grading_submissions` 表(postgres_schema.sql + sqlite.py 同步);`usage_counters` 加 `kind` 维度,主键变 `(subject, day, kind)`,存量回填 'chat'
- `rate_limit.check_and_count_daily(subject, quota, kind=...)` 支持评测配额
- `backend/app/config.py` 新增 8 配置(agent_enabled、agent_max_tool_rounds、harness_mcp_url、grading_timeout_sec、grading_max_code_bytes、grading_daily_quota、feedback_escalation_threshold、feedback_allow_explicit_ask),`.env.example` 已同步
- compose 新增 `harness` service:构建上下文 `${TEACHING_HARNESS_PATH:-../..}/teaching_harness`,独立 `harness_internal` 网络(`internal: true` 无外网),read_only + tmpfs 256m + mem 2g + pids 256
- 验证:默认集 112 passed;真实 Postgres 两种迁移顺序均收敛;`docker compose config` 通过

### S2 — agent 编排层

- **新包 `backend/app/agent/`**:
  - `graph.py`:LangGraph 三节点(planner → 条件边 → run_tools/finalize),循环上限 `agent_max_tool_rounds`,无 checkpointer,图按请求重建
  - `tools.py`:`kb_search`(封装现有检索,citations 沿用旧机制)+ `grade_plugin`(大小校验 → 配额(operator/admin 豁免)→ 硬超时 → 落库),统一 `{ok, data|error}`
  - `mcp_client.py`:streamable HTTP 短连接,所有异常收敛为结构化错误,不抛穿
  - `prompts.py`:三层提示词(基础/检测反馈两档/红线),显式请求关键词识别
  - `service.py`:`AgentOrchestrator`,与旧 AgentService 同形接口;反馈升级判定(显式请求开关 或 同协议近期 FAIL ≥ 阈值)
- **新 `backend/app/services/grading_service.py`**:`record_submission()`(不存源码,只存 hash+结果)+ `recent_fail_count()`
- `api/chat.py`:两端点支持 multipart 可选附件(.c/.so,400/413 校验,不落盘,文件名第三段作 protocol_hint);JSON 契约不变;`agent_enabled=false` 走旧管线(灰度开关)
- `agent_service.py`:D8 旧红线已改写(不再声明"不评测学生代码")
- 新增 38 个测试;**pytest 默认集 145 passed**;ruff 全过
- SSE 新契约(前端 S3 以此为准):

```
event: tool_start  data: {"tool": "grade_plugin", "label": "正在评测 DNS 插件…"}
event: tool_end    data: {"tool": "grade_plugin", "ok": true, "summary": "5/8 通过,得分 62"}
```

kb_search 轮另产生 `sources` 事件(结构同旧契约)。用户消息落库不含源码,只留 `[上传附件:文件名]` 标记。

### S3 — 前端(2026-08-24 完成)

- `frontend/src/main.jsx`:聊天输入区加附件上传按钮(.c/.so),有附件时改走 multipart,无附件保持 JSON;`tool_start`/`tool_end` 渲染为可折叠工具调用卡片(含 ok/失败摘要)
- `frontend/src/api/client.js`:SSE 解析支持 `tool_start`/`tool_end`/`status` 新事件类型,对未知事件保持宽松
- 验证:**vitest 41 passed**(`client.test.js` 覆盖新事件解析与上传分支)

### S4a — 黄金场景与收尾(2026-08-24 完成)

- `backend/tests/agent/` 黄金场景集 15 例(§8):路由正确性(1–5)、入口解析(6–7)、反馈纪律(8–11)、护栏(12–15),全绿
- `teaching_harness/` 快照已删除(D15),`.gitignore` 增加 `teaching_harness/` 防回归

### live 冒烟(2026-08-24,compose + 真 DashScope key)

- 全链路打通:上传 DNS 插件 → agent 自动评测(grade_plugin 经 harness MCP)→ 流式工具卡片 → 引导反馈 → 成绩落库可查;`AGENT_ENABLED=false` 灰度回退行为与旧版一致。证据日志:`backend/data/m1_sse_*.log`
- 冒烟发现并已修复三个问题:
  1. **agent 模式 LLM max_tokens 截断工具参数(阻断)**:agent 的 ChatOpenAI 沿用了旧 RAG 管线的 `LLM_MAX_TOKENS=2048`,grade_plugin 单次响应要输出完整 plugin_source(3-4K+ tokens),参数被截断成残码导致编译失败。修复:新增独立配置 `AGENT_LLM_MAX_TOKENS`(默认 8192,校验 ≥2048,勿低于 4096),不影响旧管线
  2. **grading_submissions.code_hash 与原件不一致**:落库 hash 取自模型在 tool args 里复述的源码,与上传原件字节不一致(实测确不一致)。修复:上传原件经 `AgentToolContext.uploaded_source` 传入,有原件用原件算 hash、粘贴路径才用 tool args 源码;`results_json` 增加 `source_origin: "upload"|"pasted"`
  3. **非 UTF-8 body 500**:`_parse_chat_request` 只 catch JSONDecodeError,补 catch UnicodeDecodeError 返回 422(与非法 JSON 同语义)
- 验证:pytest 默认集 **167 passed**(覆盖率 73.18%),ruff 过改动文件

### M2 — 教学闭环(2026-08-24 完成)

- **五件工具齐备**:`tools.py` 补齐 `list_protocols` / `list_cases` / `plugin_template`,与 `kb_search` / `grade_plugin` 合计五工具;统一 `{ok, data|error}` 契约
- **参考答案带外注入**:harness MCP 新增只读工具 `get_reference_answer`(仓库 `E:\teaching_harness`,同步推送);backend 侧新增 `agent/reference.py`,评测 FAIL 时由服务层带外取参考答案注入反馈上下文(**不经 tool args、不落库、提示词硬约束禁止向学生输出等价代码**),`source` 契约 `{ok, protocol, source}`
- **粘贴代码块提取与协议推断**:消息内 ```c 代码块自动识别为待评测代码;协议名从文件名/代码特征推断,协议别名表保守(VPN/quic 等无中文别名,推断不出则要求用户指明)
- **黄金场景集补满 25 例**(15 → 25):新增反馈纪律/协议推断/参考答案不泄露等场景,全绿
- 验证:pytest 默认集 **199 passed**(覆盖率 74.14%),ruff 全过

## 提交状态

- **2026-08-23 已推送**:**ai_chat_assistant** `ab98d4a`(Gitee main;CI 等价检查全过:ruff、pytest 149 passed / 覆盖率 72.9%、vitest 33 passed、build 成功);**teaching_harness** `c9c2f46`(Gitee master,强推;远程分叉处理:另一台电脑的 `b33c590` assert.c/harness.c 越界读修复已 cherry-pick,其文档提交 `712b503` 被本地 `7aa5751` 完整覆盖未保留;推送前 C 全量测试 + 13 MCP pytest + smoke 全绿)
- **2026-08-24 M1 收尾**:S3 前端 + S4a 黄金场景 + live 冒烟三修复,已提交并推送 main(commit 见 git log)
- **2026-08-24 M2 收尾**:五工具齐备 + 参考答案带外注入 + 协议推断 + 黄金场景 25 例,已提交并推送 main;harness 侧 `get_reference_answer` 同步推送 master(commit 见 git log)
- pip-audit 备忘(CI 非阻塞):`mcp 1.12.4` 命中 3 个传输层 CVE(修复版 1.23.0+)、`langchain-openai 1.1.9` 命中 1 个低危;当前 pin 被 `openai<2`/`pydantic==2.10.4` 压住,记入 M3 安全加固评估

## 已知事项(不阻塞,记录在案)

- sqlite 后端与 llama_index 检索引擎不兼容(生产用 postgres + pgvector,sqlite 仅供隔离开发/测试)
- harness `get_reference_answer` 的 dns 参考答案走 `examples/` 兜底(answer/ 目录无 dns 参考实现,契约已含此查找顺序)
- 协议别名表保守:VPN/quic 等协议暂无中文别名,消息含中文俗称时推断不出,agent 会要求用户指明协议
- WSL 空闲时可能杀 docker 容器,live 冒烟/演示前需先确认容器存活
- qwen 若 tool_calls 同帧输出正文,delta 会提前流出(可接受)
- 显式请求识别是关键词启发式(M1 简化,规划允许)
- 答案泄露审计(提示词对抗/越权取答案的系统性测试)属 M3,未动
- `docs/dev_notes.md` 边界声明更新属 M3,未动
