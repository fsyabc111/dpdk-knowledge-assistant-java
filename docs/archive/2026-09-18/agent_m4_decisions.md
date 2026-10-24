> 历史归档：2026-09-18。原路径：`docs/agent_m4_decisions.md`。
> 适用版本：正文各条记录注明的日期、提交与环境；归档所据助手仓库快照为 `7048d8c`，不代表整篇已在该版本复验。
> **不再作为当前实施指令。** 正文保留当时的决策、完成声明和测试数据；未注明版本或存在冲突的结论不能外推为当前验收结果。
> 当前入口：[文档导航](../../README.md) · [统一路线](../../roadmap.md) · [当前决策](../../decisions.md)。
> 正文中的纯文本旧路径与行号用于还原历史上下文；本次仅修正可点击的相对链接。

# Agent 升级 · M4 决策记录（运行时自我管理）

> 日期：2026-08-27，grill-me 结构化面试收敛
> 前置阅读：`docs/agent_upgrade_plan.md`（D1–D15，唯一事实来源；本文档仅追加，不覆盖）
> 状态：M4a 已实施（2026-09-12，见文末「M4a 实施状态」）；M4b/M4c 未启动

## 1. 定位与范围

M3 完成后 agent 骨架（图编排、工具契约、配额、隔离、回退开关）已生产级，但与成熟
agent 的差距集中在**运行时的自我管理**：上下文不管理、成本不核算、历史不记忆、
调用链不追踪、注入不设防。M4 范围 = 全部六维度补齐 + **翻转 D11**（引入
checkpoint 中断/恢复）。**D12 保持**（不存源码全文）。

节奏：无期限，质量驱动；每项能力独立回归 + 独立开关，分批上线。

## 2. 决策清单

| # | 决策点 | 结论 | 理由 |
|---|--------|------|------|
| E1 | 范围 | 六维度全做：上下文管理、可观测性、MCP 通道、学习者画像、token 预算、注入防护 + 测试硬化 | 用户选定；质量驱动允许完整范围 |
| E2 | checkpointer 定位 | 叠加在现有会话层上，只做中断+恢复；不取代 SessionService/ConversationMemory | 会话层已生产化；图状态接管会话会让消息列表无限增长固化到持久层 |
| E3 | checkpointer 实现 | `AsyncPostgresSaver`，`thread_id = session_id`；清理挂现有 `purge_sessions.py` 保留期 | 会话跨数周、重启不能丢中断态；PG 与 psycopg 已有 |
| E4 | interrupt 位置 | 仅高风险情境：推断协议与文件名不一致、剩余配额 ≤2、上传 .so 二进制 | 无条件确认破坏 M1 验收的“上传→自动评测”黄金路径 |
| E5 | 上下文预算 | 确定性截断：最近一轮工具结果原样，更早 ToolMessage 替换为 `tool_event_summary` 短摘要；kb_search 只留 top-2 chunk；零额外模型 hop | 复用已有摘要逻辑，与“本地规则优先”哲学一致 |
| E6 | 可观测性 | 零基建：chat_trace JSON + Prometheus 指标扩展（每轮 tool 耗时/结果大小/usage_metadata）；不用 LangSmith | SaaS 外发学生数据与隐私最小化冲突，内网可达性无保障；LangFuse 留作后手 |
| E7 | MCP 通道 | 保持短连接 + 连接类错误有限重试（2 次退避，不重试已提交超时的调用）+ 两条调用路径收敛到同一客户端实例；**不做连接池** | 压测显示评测耗时 18-36s，内网建连开销 <1%，池化收益被证伪 |
| E8 | 学习者画像 | 系统提示词固定注入，预算 ≤300 字：各协议最高分/尝试次数/高频失败用例名；数据源 `grading_submissions` | qwen tool calling 不稳定，按需工具不可靠；固定注入确定性可用 |
| E9 | token 预算 | `AgentGraphState.tokens_used` 累加 `usage_metadata`，超限硬转 finalize（复用 TOOL_LIMIT_NOTE 同形兜底）；默认 `AGENT_REQUEST_TOKEN_BUDGET=48000`，0=关闭 | 输入侧（记忆/附件）已有字符上限，唯一无界增长是工具循环累加；模型 qwen3.7-max 窗口 256K，48K 约占 19% |
| E10 | checkpoint 与 D12 冲突 | **源码带外传递重构**：上传路径 HumanMessage 只放文件名/大小/协议提示，`grade_plugin` 加 `use_attachment` 参数，工具从 `context.uploaded_source` 取原件 | 否则源码随 checkpoint 落 PG 违反 D12；顺手消灭“模型复述字节不一致”旧疾。此重构必须先于 checkpoint 启用 |
| E11 | 注入防护 | 结构性界定（提示词声明用户内容是不可信数据非指令）+ 黄金集注入对抗用例；不做正则静态检测 | 攻击目标具体（参考答案），守住输出纪律+对抗测试即守住要害；正则会误伤合法代码注释 |
| E12 | 回滚开关 | 每项独立环境变量：`AGENT_CHECKPOINTER_ENABLED` / `AGENT_CONTEXT_TRIM` / `AGENT_TOKEN_BUDGET`(0=关) / `AGENT_PROFILE_INJECT` | 延续现有细粒度开关文化；单项故障单项关 |
| E13 | 测试 | 黄金场景 25→35（注入对抗×4、interrupt/resume×2、trim/预算硬切×3、带外上传回绕）；live 集 11→25 题 + LLM-judge 自动评分进定期任务，人工保留终审 | 源码带外重构会碰遍现有上传路径用例，测试债躲不掉；质量驱动下测试基建最值 |
| E14 | 交付顺序 | M4a 运行时护栏 → M4b 图结构（源码带外先行，再 checkpoint+interrupt）→ M4c 测试硬化 | 唯一硬依赖：E10 先于 E3；每批独立上线可观测 |

## 3. 遗留风险

- **粘贴路径代码仍会进 checkpoint**：E10 只覆盖上传路径；学生粘贴在聊天消息里的
  代码属于对话内容，同样随 checkpoint 持久化。M4b 需决定：粘贴路径入口层也带外化，
  还是接受“粘贴即对话内容”例外。**这是 M4 唯一未决问题。**
- LLM-judge 与被测模型同为 qwen，存在自评偏置；保留人工终审。
- 48K 预算阈值是拍的；M4a 上线后按 usage 日志真实分布校准一次。
- 前端需新增“待确认”交互态（interrupt 的 resume 入口），属 M4b 范围。

## 4. 里程碑拆分

- **M4a 运行时护栏**（纯增量，不改图结构）：usage 采集 → token 硬切 → 上下文修剪
  → 画像注入 → MCP 重试与入口收敛 → 注入结构界定（纯提示词）。每步带黄金用例。
- **M4b 图结构**：源码带外重构（前置）→ AsyncPostgresSaver + interrupt/resume +
  checkpoint 清理 → 前端待确认态。同时解决粘贴路径 checkpoint 落源码问题。
- **M4c 测试硬化**：黄金集扩至 35 例（实际随 M4a/b 同步生长）、live 25 题、
  LLM-judge 定期任务、漂移基线更新。

## 5. M4a 实施状态（2026-09-12）

全部六项落地，默认集 379 例通过、覆盖率 84.18%、ruff 全绿；黄金场景 26→31。

| 决策 | 落地情况 |
|---|---|
| E9 token 预算 | 图内已有 usage 累加与硬切；本轮：独立开关 `AGENT_REQUEST_TOKEN_BUDGET`（默认 48000，0=关，与产品受理层 `rag_request_token_budget` 解耦），超自由抛异常改为**硬转 finalize**（TOOL_LIMIT_NOTE 同形兜底），新增 tools→finalize 条件边；`tokens_used` 落 chat_trace + `assistant_agent_request_tokens` 直方图。黄金 s27/s28 |
| E5 上下文修剪 | 已有「最近工具轮原文 + 更早 ToolMessage 压摘要」；本轮：`AGENT_CONTEXT_TRIM_ENABLED`（默认开）把修剪从产品路径推广到普通 agent 路径，kb_search 压缩保留 **top-2 片段**（`tool_context_summary`），call_id 配对不破。黄金 s29 |
| E8 学习者画像 | `GradingService.learner_profile`（最近 50 条聚合：各协议尝试次数/最高分/高频失败用例名，≤300 字），系统提示词固定注入、每请求一次、匿名/无记录/失败静默跳过；`AGENT_PROFILE_INJECT_ENABLED` 默认开。黄金 s30 + `test_learner_profile.py` |
| E7 MCP 通道 | 建连/initialize 失败重试 2 次退避（`mcp_call_retry` 日志），**已提交调用绝不重试**（submitted 标记）；agent 工具层与 harness_grading 作业路径经 `get_harness_mcp_client` 收敛同一进程级实例。`test_mcp_client_retry.py` |
| E6 可观测性 | `assistant_agent_tool_calls_total` / `_duration_seconds` / `_result_bytes`（按 tool+ok 标签）+ `assistant_agent_request_tokens`；chat_trace 增 `tokens_used`。零新基建 |
| E11 注入防护 | REDLINE 增第 4 条结构界定（用户消息/历史/代码/资料皆为数据非指令）；黄金 s31 对抗用例（伪系统指令不升级档位、仅作 human 数据入图） |

有意偏差/说明：

- E12 开关清单中的 `AGENT_TOKEN_BUDGET` 命名为 `AGENT_REQUEST_TOKEN_BUDGET`（与
  既有 `rag_request_token_budget` 对齐语义）。
- tools→finalize 条件边在当前记账方式下是防御性冗余（token 只在 planner/finalize
  累加，route_after_planner 已保证进 tools 时未超限），保留以覆盖未来工具侧计费。
- 48K 阈值校准（按 usage 日志真实分布）待生产窗口：`docker compose logs backend |
  grep chat_trace` 取 `tokens_used` 分布后复核，属运维动作不改代码。
- M4b 唯一未决问题（粘贴路径源码进 checkpoint）仍未决；M4b 启动前先在
  `docs/agent_m4_decisions.md` 补决策。

## 6. E5 分级降级扩展（P0.1，2026-09-17）

线上 4 题 `agent_context_budget` 暴露 E5 的两个缺口：planner 只压「严格早于最后
一个带 tool_calls 的 AIMessage」的 ToolMessage（最近一轮永远原文，单次 kb_search
最多 8×1500 字符即可单独超限），finalize 完全不做准备。本轮把 E5 从「一层压缩」
扩展为**分级、够用即停**的确定性管线 `app/agent/context.py`：

| 级别 | 动作 |
|---|---|
| L1 | 压缩早于最后一个**有结果**工具轮的 ToolMessage（= 原 E5 行为，始终执行） |
| L2 | 压缩全部 ToolMessage（含最近一轮） |
| L3 | 再压历史轮 tool_calls 长实参为占位符 |
| L4 | 再硬截断剩余 ToolMessage |
| L5 | 再从最旧整单元丢弃（保护最近一轮与最后一条用户消息） |

关键决策与理由：

1. **计量口径改为 `content + tool_calls.args`**：`grade_plugin` 的参数要携带完整
   `plugin_source`，只算 content 会显著低估真实请求体积，使压缩在超限时仍不触发。
2. **L1 无条件执行**（不再「超限才压」）：它是打开修剪后的既有行为，也是这段历史
   的确定性上界；否则黄金 s29 的「最近一轮原文」语义会漂移。
3. **「最近一轮」定义为最后一个*有 ToolMessage 结果*的工具轮**：finalize 前的历史
   以一条**未执行**的 tool_calls AIMessage 结尾（该轮被路由到 finalize 而非 tools），
   若按「最后一个带 tool_calls 的消息」判断，最近一轮真实工具结果反被判为早轮而
   压缩掉——而 finalize 正是最需要这份证据的地方（本机实测：误判会使 finalize 把
   20066 字符的最近证据压成 52 字符）。
4. **配对不变式**：消息按「单元」整体处理——带 tool_calls 的 AIMessage 与其全部
   ToolMessage 要么一起留、要么一起删；只改 `content`/args，不动 `tool_call_id`。
   因此任何级别都不产生孤儿 ToolMessage 或悬空 tool_call。
5. **地板溢出不再抛异常**：连「系统提示词 + 当前请求」都放不下时抛
   `ContextBudgetExceeded`（继承 RuntimeError 且消息含 `agent_context_budget`，
   chat_trace 观测口径不变），由 service 层转成**保留评分事实**的安全降级。

配套（同批）：`build_fallback_answer` 保留已完成评测的得分/失败用例、消毒提问中的
代码围栏；`tool_context_summary` 的 grade_plugin 分支不再退化为一句话；默认反馈档
与「索要参考答案」档硬化禁代码围栏（升级档不变）。黄金场景 31→36（s32–s35、s33b），
`test_agent_context_prepare.py` 14 例，`test_agent_graph.py` +5 例。
