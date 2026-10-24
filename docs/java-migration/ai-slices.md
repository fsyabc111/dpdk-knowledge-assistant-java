# AI、任务与索引迁移任务卡（规划稿）

本稿只用于分派与评审，不授权实施、部署、切换或真实模型调用。
技术建议：Java 21 候选、Spring Boot 模块化单体、Spring AI 适配模型/MCP、自写 SQL 兼容现有 LI 表；选型仍待总方案确认。
目标目录：`/Volumes/study/code/new_project/dpdk-knowledge-assistant-java`。
来源身份：`/Volumes/study/dpdk-runtime/workspace/dpdk/ai_chat_assistant`；仅作复制溯源。以下所有Python源码/测试入口均相对新仓库中的冻结副本，以baseline-manifest.json核对，不继续从可能变化的原目录取契约。原目录仅在明确授权的差异调查时读取。
Java 主/测试包前缀分别为 `backend-java/src/main/java/io/dpdk/assistant/` 和 `backend-java/src/test/java/io/dpdk/assistant/`。
卡片的可写路径均相对目标目录；包路径写作 `main:<包>`、`test:<包>`，按上述前缀展开。
每卡证据独占 `docs/java-migration/evidence/<卡号>/`；只能保存脱敏输入、命令、版本、断言与结果，不能保存凭据或真实学生源码。
根 POM、公共配置、schema/migration、应用装配入口由集成者管理；依赖需求写进本卡 evidence，不越界修改。
执行前只加载本卡、直接依赖的交付契约与所列源入口；结果回报：修改文件、运行命令、通过/失败/未验证、接口变化、阻塞。
所有 P0 oracle 使用固定合成数据；未知行为先记录差异，不把文档意图或历史通过数当作本轮实现事实。
阶段放行仍由主计划控制；可写归属不等于已经获准开启下一阶段。

## 端口归属与批次协作

- J2-05 独占 `session/**` 与 `usage/**`，包含会话创建/删除/release 绑定、配额/用量仓储及参加既有事务的接口。
- J3-01 只编排跨表原子受理，调用 session/usage 端口，不复制它们的实现；相同事务须共享同一连接/事务上下文。
- J3-02 的成绩落库调用 P2 业务端口，任务/job 仓储归 J3-01；两者同事务约束通过集成者管理的接口表达。
- 公共 DTO、公共端口和装配入口由集成者持有；发现接口缺项先提交最小签名/语义草案，不改共享文件。
- J1-04 使用 synthetic 上下文；它不依赖 P2，也不提供持久会话、持久任务或生产 API 兼容承诺。
- 正式作业提交与聊天评测分开：当前正式提交无业务幂等键，本稿不把聊天恢复能力扩展成作业已具备的能力。
- 每次并行分派先记录独占包锁；需要改别卡文件时回交所属 Agent 或集成者，不能静默越界。
- 测试资源也按卡号隔离；公共 oracle 只读，由 J0-02 维护，Agent 不可修改期望值来消除差异。
- 真实 PG/模型/harness 测试是否可执行，以当批授权及隔离环境检查为准；测试存在与测试通过分开报告。
- 根依赖新增建议要写明用途、版本与必要范围；Maven 构建接线由集成者一次完成。
- 所有时间比较以明确 UTC 表示和可注入时钟测试；旧表 ISO 文本排序语义须核对后再转换数据库类型。
- 源代码里的异常 payload 日志与模型提示纪律先进入 oracle 风险清单；未查证的安全属性标为待验证。

## J1-02 模型与 MCP 适配

**目标**：提供与供应商无关的 Java 模型/embedding 端口、harness MCP 端口及失败分类，供后续模块调用。
**依赖**：J0-01 契约/schema 清单、J0-02 oracle、J1-01 Java 脚手架和隔离配置；集成者确认 Spring AI/Boot BOM。
**源读取入口**：
- `backend/app/core/llm_client.py`、`core/usage.py`、`core/upstream_gate.py`、`core/async_cache.py`。
- `backend/app/agent/mcp_client.py`、`agent/service.py:build_agent_chat_model`、`rag/providers.py`。
- `backend/tests/test_mcp_client.py`、`test_mcp_client_retry.py`、`test_llm_client_usage.py`、`test_usage_wiring.py`。
**独占可写**：`main:model/**`、`main:harness/**`；对应 `test:model/**`、`test:harness/**`；`src/test/resources/migration/J1-02/**` 置于 `backend-java/` 下。
**实现范围**：
- 定义模型消息、工具声明/结果、embedding、usage 与错误端口；业务模块不直接依赖供应商 SDK 对象。
- 保持聊天与 embedding 分离配置；base URL 路径拼接、模型名、超时、pool 和重试均显式映射。
- MCP 支持当前 harness 的 Streamable HTTP 与工具返回规范化；连接初始化失败最多重试 2 次。
- 已进入 `call_tool` 提交阶段的失败返回明确不确定结果；禁止由 SDK/框架层自动重发评测。
- 同 URL 复用客户端服务实例；源实现每次调用仍建立 transport/session，不能宣称已有长连接池语义。
- 用量 unknown 与 0 分开；上游并发门与相同 key 合并请求分别按原接口建模。
**非目标**：Agent 循环、任务账本、评测计分、生产凭据迁移、升级或重写独立 harness。
**具体验收**：
1. Mock HTTP 覆盖 JSON/流式、错误状态、超时、畸形工具参数、空响应及 usage 缺失；与 oracle 字段对齐。
2. MCP fake server 覆盖 initialize 失败后重连、提交后断连；后者断言 `grade_plugin` 只提交一次。
3. structuredContent、文本 JSON、isError、非 JSON 按旧契约分别验证；异常日志不得回显合成源码哨兵。
4. 请求并发上限、取消后资源释放和同 key 合并有确定性测试；已发送请求的取消不伪称远端已取消。
5. 经后续 live 批次授权才对真实供应商/隔离 harness 做兼容冒烟；当前通过仅指替身契约。
**证据**：锁定依赖版本、请求/响应脱敏样本、重试次数、usage 矩阵、未验证供应商能力清单。
**交付接口**：模型/embedding/harness 端口签名及错误分类；模型调用副作用、取消与计费边界各附一个样例。
**完成门槛**：替身验证通过且集成者签收端口；若真实兼容冒烟未执行，交付明确保留该限制。
**回退**：撤销本卡新增包/配置接线，保留 Python 入口；不改变数据库和 harness 状态。

## J1-03 只读检索与 LI 数据面适配

**目标**：在固定 release 的合成 PG 快照上重现候选召回、融合、重排和最终片段排序。
**依赖**：J0-01、J0-02、J1-01；J1-02 embedding/改写端口，初期可用其冻结 mock。
**源读取入口**：
- `backend/app/services/retrieval_service.py`、`services/llama_index_service.py`、`storage/li_retrieval_store.py`。
- `backend/app/storage/retrieval_store.py`、`rag/interfaces.py`、`services/rag_index_service.py:index_configuration`。
- `backend/tests/test_li_retrieval_store.py`、`test_retrieval_weights.py`、`test_retrieval_degradation.py`、`test_retrieval.py`。
- `backend/tests/rag_eval/queries.yml`；真实题库指标与合成对照分开记录。
**独占可写**：`main:retrieval/**`、`test:retrieval/**`；`backend-java/src/test/resources/migration/J1-03/**`。
**实现范围**：
- JDBC/SQL 读取既有 `node_id/text/metadata_/embedding`，通过 `rag_release_nodes` 限定版本成员。
- 保留改写 FTS、原问题 FTS、路径/主题、语义召回；按原 RRF、代码信号、23 项权重和 MMR 处理。
- 校验动态表名，参数化 SQL；保留 LIKE 转义、FTS 配置不一致降级、HNSW 事务级参数与排序 tie-break。
- 区分固定 release、未发布索引、配置失配及历史引用读取；旧非 `li_` 引用走历史表适配。
- 只消费冻结的配置指纹接口；跨语言配置字符串规范由 J0-01 定义，J4-01 复用。
**非目标**：写索引、换 embedding、换切块器、调权重、启用默认 VectorStore 自动建表、迁业务会话写入。
**具体验收**：
1. 固定候选和向量输入，逐阶段比较候选 ID、rank、score、去重、Top-K；数值容差在运行前冻结。
2. 两个 release 含重叠节点时不能串版本；无活动 release 返回无证据；配置不符按原行为失败关闭。
3. 中文、组合/分解 Unicode、路径大小写、`%`/`_`、空查询、重复分值及零证据有对照样本。
4. FTS 不匹配只关闭对应召回腿，不能任意补齐候选；合成 PG 测试验证 JOIN 与 SQL 行为。
5. 历史引用可读且不受新 release 覆盖；真实 recall/MRR 需另行执行 live 评测后才能称等价。
**证据**：PG schema/fixture 版本、阶段排序差异表、浮点容差、SQL release 约束断言和未验证 live 指标。
**交付接口**：查询输入、明确 release、候选片段、检索 trace、历史引用读取；不暴露数据库连接给 Agent。
**完成门槛**：固定输入的每个检索阶段均可解释差异；任何未处理排序漂移阻止宣称算法等价。
**回退**：撤销 Java 检索接线；源索引只读，因此无需数据回滚，保留 Python 检索对照入口。

## J1-04 最小问答纵向切片

**目标**：证明 Java 的请求→固定版本检索→模型→结构化答案/引用链路可接通，为后续完整产品路径提供证据。
**依赖**：J1-01、J1-02、J1-03；使用 J0-02 的合成请求、固定模型响应和预期引用。
**源读取入口**：
- `backend/app/rag/interfaces.py`、`services/agent_service.py`、`services/citation_service.py`、`schemas/chat.py`。
- `backend/app/services/rag_product_service.py:_precheck_verdict/_postcheck_verdict`、`backend/tests/test_citation_contract.py`。
**独占可写**：`main:chat/poc/**`、`test:chat/poc/**`；`backend-java/src/test/resources/migration/J1-04/**`。
**实现范围**：
- 以隔离 profile 的内部测试入口或测试 harness 装配模型与检索端口；不占用正式 `/api/chat*` 路由。
- 固定 release 和身份 fixture，输出答案、来源编号、answer_status 及失败分类。
- 为 J3-03 提供结果 DTO/候选生成契约草案，正式契约由集成者签收；POC 目录后续可整体退出。
- 模型调用以 mock 为默认；真实模型仅在本卡另获明确授权和隔离配置后验证。
**非目标**：账号体系、持久任务、恢复、Agent 五工具、生产 SSE、完整历史兼容或切换流量。
**具体验收**：
1. 一条有证据问题返回预期片段顺序和引用；无证据、引用越界、空答案按确定性规则分类。
2. 模型和检索异常均得到受控响应；既有数据库、正式路由和运行中 Python 服务不受影响。
3. 测试能从公开契约入口调用完整链路，只 mock 上游网络，不直接伪造最终业务结果。
4. 构建产物在隔离 profile 可运行；移除 profile 后 POC 路由不可达。
5. 交付明确标注“纵向可行性已验证/未验证”，不写成完整问答迁移完成。
**证据**：固定输入/检索中间值/模型响应/最终引用四联样本、启动配置边界及失败样本。
**交付接口**：只输出纵向可行性记录与候选结果结构；正式聊天 DTO 交由集成者纳入 J0-01 契约。
**完成门槛**：成功/无证据/失败三类流程可复现，且没有借用未完成 P2/P3 的隐式能力。
**回退**：移除 POC 装配和 `chat/poc`；不触及 J1-02/J1-03 已稳定端口，也不切换生产。

## J3-01 持久任务生命周期与恢复

**目标**：迁移任务受理、进程托管、订阅、取消、重驱动与 SQL 认领，独立于 HTTP 连接生命周期。
**依赖**：P0、J1-01；P2 已交付身份/会话/配额端口；Agent 尚未完成时使用冻结的执行体接口 fake。
**源读取入口**：
- `backend/app/services/task_service.py`、`services/rag_product_service.py:Admission/prepare/prepare_for_recovery`。
- `backend/app/services/rag_issue_service.py:begin_admitted/probe/cancel_task/claim_recovery/finish`、`api/tasks.py`。
- `backend/tests/test_s3c_task_recovery.py`、`test_s3c_task_ledger.py`、`test_rag_identity.py`、`test_session_release.py`。
**独占可写**：`main:task/**`、`test:task/**`；`backend-java/src/test/resources/migration/J3-01/**`。
**实现范围**：
- 分层：内存运行句柄/订阅日志与数据库 pending/终态分开；任务状态 API 由本包承担。
- 受理事务包含启用账号锁、唯一任务、临时输入、用户消息、会话更新时间、日配额；冲突接续不重扣。
- session/usage 接口由 J2-05 提供；受理中共享事务，不能让嵌套独立事务先提交配额或消息。
- task 包持有请求/临时输入/job 仓储及终态提交协调；finish 保持消息/引用/请求状态同事务。
- 评测 job 登记+配额、成绩+submission_done 同事务，通过冻结业务端口协作；Agent 不自行拼写业务 SQL。
- input_hash 对齐 NFC、Python strip 边界、紧凑 JSON 数组、UTF-8 与附件原字节 hash；历史 NULL hash 拒绝。
- 明确锁/原子段保护注册与启动、回放快照与订阅注册、完成与退订；不能仅用 ConcurrentHashMap 替代。
- admission 保留进程级活动/等待界限和超时；恢复用非排队占槽；受理/执行 deadline 分开。
- 当前 10s 心跳、30s 孤儿宽限、updated_at 条件 UPDATE 认领；不是持久 owner_id/fencing 协议。
- 保留 500 条进度日志、300s 终态内存 TTL；终态可从 DB 重建；SSE 断开只移除订阅者。
- 进程重启重新执行 Agent，再用工具账本复用；不宣称恢复 LangGraph/Java 内存断点。
- 终态与取消原子清临时输入，异常仍 pending 则保留恢复输入；24h 清理由运维卡接线。
**非目标**：分布式全局 admission、跨副本立即停止算力、持久事件日志、exactly-once 外部评测、改成工作流平台。
**具体验收**：
1. 同 request 并发受理只产生一任务/一用户消息/一次扣额；注入任一步 DB 失败，全事务回滚。
2. 合成 Unicode/附件 hash oracle 完全一致；同 ID 异内容、越权主体及历史 NULL hash 分别拒绝。
3. 多线程并发启动/快照订阅/终态取消无双执行、无遗漏终帧、无负槽；时钟可控，避免真实 sleep。
4. 断开所有 SSE 订阅仍完成；重连先回放再实时；显式取消不能被迟到完成覆盖。
5. 双 Java 进程合成 PG：活任务不抢、属主停止后一方认领；非属主返回 pending 保持非终态语义。
6. 停用账号、输入缺失、占槽满及重复恢复均有分支证据；跨副本取消不停算力等既有边界独立记录。
**证据**：线程交错/故障注入矩阵、数据库前后快照、计数与状态断言、双进程时间线及未覆盖组合。
**交付接口**：admit/probe/attach/cancel/recover、执行体与事件订阅、job 接续和终态提交事务端口。
**完成门槛**：并发与崩溃测试证明声明范围；只证明 mock 单线程路径时不可放行 J3-03 生产契约集成。
**回退**：停止测试 Java worker、等待/取消隔离任务后撤接线；不让 Python/Java 同时恢复同一生产任务池。

## J3-02 Agent 五工具与受控循环

**目标**：迁移 planner→tools→finalize、上下文预算及五工具行为，并接入 J3-01 任务/作业账本端口。
**依赖**：J1-02、J1-03、P2 的成绩/配额/资料读取端口及 J0-02 黄金场景输入；J3-01 的冻结接口允许先用替身并行开发，最终集成验收必须等待其真实实现。
**源读取入口**：
- `backend/app/agent/graph.py`、`agent/context.py`、`agent/service.py`、`agent/prompts.py`、`agent/reference.py`。
- `backend/app/agent/tools.py`、`services/grading_service.py`、`services/rag_issue_service.py:reserve_grading_job`。
- `backend/tests/agent/test_golden_scenarios.py`、`test_agent_graph.py`、`test_agent_context_prepare.py`、`test_s3b_source_identity.py`。
**独占可写**：`main:agent/**`、`test:agent/**`；`backend-java/src/test/resources/migration/J3-02/**`。
**实现范围**：
- 显式循环状态保存消息、工具轮次和 token 使用；框架自动工具循环须关闭或受同一控制器约束。
- 迁五工具：kb_search、list_protocols、list_cases、plugin_template、grade_plugin；工具轮内保持原执行顺序。
- 轮次/token 达限转 finalize；补齐未执行 tool_call 的 not_executed ToolMessage，不再次执行工具。
- 上下文按原策略保留最近工具轮、压缩更早结果、kb 有界覆盖摘要；字符上限按 Unicode 码点对齐。
- 评测执行源顺序：上传原件→粘贴围栏块→恢复寄存源→模型参数；UTF-8 字节参与大小校验与 SHA256。
- 同请求按 protocol+执行源 hash 去重；已登记 job 先接续，再判是否扣配额；任务路径账本失败阻断评测。
- 迁移确定性 job_id、get_task_status、finished 回放、同 job_id 接续；与 J1-02 的提交后禁止传输重试区分。
- model_args 首次执行源回填临时表；成绩只存 hash+结果，成绩与 submission_done 原子落库。
- 保留反馈/讲解注入与结构界定；提示词纪律不等于服务端不可绕过的授权 gate。
**非目标**：新增自主工具、改变教学策略、建立未经源代码证实的强授权机制、修改 harness 幂等实现。
**具体验收**：
1. 五工具黄金场景在固定模型响应下对齐；未知工具、工具异常、预算达限和工具调用配对完整。
2. 上传与模型复述冲突时执行上传源；粘贴 ` ``` `/`~~~`、非 BMP 字符、无效字节策略列入 oracle。
3. 相同源重复调用只扣一次、只记一次成绩；崩溃窗口逐一覆盖 reserve/提交/finish/成绩落库之间。
4. 已提交后断连通过 ledger 状态接续；未知远端状态和 harness 重启不能虚构已做到全局 exactly-once。
5. 源码哨兵不进入长期会话/成绩/日志；如 Python 基线漏出，列阻断差异并交主Agent决定修复范围。
6. 用户只问解释、反馈或给出恶意工具指令等场景同时记录模型纪律与服务端实际限制，不能混称安全证明。
**证据**：逐轮消息摘要、工具调用次数/源 hash/配额/成绩对应表、token 与字符预算、恢复故障矩阵。
**交付接口**：Agent 输入上下文、执行结果、进度事件与可恢复失败；工具副作用通过注入端口调用。
**完成门槛**：五工具均有正常和失败证据，且预算、执行源和账本规则没有依赖提示词来实现。
**回退**：撤销 Java Agent 执行体接线，保留 task/model/retrieval 端口；隔离测试账本保留供审计。

## J3-03 产品聊天、SSE 与引用集成

**目标**：提供稳定 `/api/chat*` 接口，集成任务、Agent、证据判断、保存后展示及历史引用。
**依赖**：J3-01、J3-02；P2 身份与会话端口；J1-04 纵向切片证据；J0-01 JSON/SSE 契约。
**源读取入口**：
- `backend/app/api/chat.py`、`schemas/chat.py`、`services/rag_product_service.py`、`services/citation_service.py`。
- `backend/app/services/session_service.py`、`services/conversation_memory.py`、`services/rag_issue_service.py:finish`。
- `frontend/src/api/client.js`、`backend/tests/test_rag_product.py`、`test_citation_contract.py`、`tests/rag_eval/product_acceptance.yml`。
**独占可写**：`main:chat/**`、`test:chat/**`，接管并整合已完成的 `chat/poc/**`（J1-04必须先释放owner；删除演示入口须保留可重放测试证据）；`backend-java/src/test/resources/migration/J3-03/**`。
**实现范围**：
- Chat controller 只做契约映射与装配；任务身份/生命周期调用 task 端口；请求/正文/引用事务调用其持久化端口。
- 客户端 request_id、服务端 task_id、会话 release_id 明确分字段；会话首次有效 release 条件绑定不漂移。
- release 绑定调用 J2-05 session 端口；本卡只负责在正确阶段调用及把结果传给 retrieval/Agent。
- 确定性检查保留空答案、引用越界、无证据、缺引用；在线 judge 默认关，显式 judge 注入分支保留。
- 正文/引用/终态一次提交后才发送答案；检索/工具进度可先发，不能直接透传供应商 token 到正文。
- SSE start/status/tool_start/tool_end/sources/delta/done 字段与顺序对齐；sources 为累计全量列表。
- 正常终态和 pending terminal:false 区分；取消历史占位可见但不纳入对话记忆；故障受控返回。
- 固定引用编号按片段而非文件合并；历史 `li_` 与非 `li_` 回读走 retrieval 端口。
**非目标**：作业讲解入口（J3-04）、知识工作台（J4-02）、前端重写、修改公开 SSE 契约或生产切换。
**具体验收**：
1. 同一请求非流式/流式/任务续传/已完成重放的答案、引用、task_id 一致；重放不调用模型、不扣额。
2. 在保存前注入异常，客户端不得出现未落库正文；完成和取消竞态不产生双终态或覆盖取消。
3. SSE 字节流按 oracle 验证帧名/字段/顺序、累计 sources、pending 非终态与终态重建。
4. 会话首次 release 并发绑定同一赢家；切换活动索引后旧会话仍读旧版本，失配关闭并输出可辨原因。
5. 现有前端 API 客户端契约测试对 Java 服务通过；浏览器断线/刷新验收留到 P5，不能用单测替代。
6. 所有 answer_status、引用越界与模型/工具失败分支有对照；装配失败保持 503，不伪装成功。
**证据**：API/SSE 对照表、事务失败注入、重放计数、release 绑定与引用回读证据、未执行页面清单。
**交付接口**：公开 JSON/SSE 契约差异清单、Agent/Task 装配要求；J3-04 讲解通过公共入口复用本链路。
**完成门槛**：接口契约全部有对照结果；如需调整前端容错，单独交集成者决定，不修改前端掩盖不兼容。
**回退**：撤销 Java 正式路由接线；保留既有 Python 路由与前端；测试数据库回滚按 J0-01 快照方案。

## J4-01 索引摄取、原子发布与回滚

**目标**：在独立测试库重现全源扫描、切块、增量判定、embedding、CAS 发布与受校验回滚。
**依赖**：J1-02、J1-03、J3-03 release/健康契约；J4-02 知识读取接口可先使用固定 published fixtures。
**源读取入口**：
- `backend/app/services/rag_index_service.py`、`services/knowledge_sources.py`、`services/llama_index_service.py`。
- `backend/scripts/ingest_llama_index.py`、`scripts/rag_maintenance.py`、`backend/data/knowledge_sources.yml`。
- `backend/tests/test_rag_versioned_postgres.py`、`test_ingest_idempotency.py`、`test_session_release.py`。
**独占可写**：`main:index/**`、`test:index/**`；`backend-java/src/test/resources/migration/J4-01/**`。
**实现范围**：
- 接收隔离知识源根路径；精确 include、缺源报错、排序/去重、最长匹配 root 与相对 source_key 对齐。
- 本地文件与已发布知识按原 bounded-lines-v2 切块；逐项比较正文/metadata/config，不改成 hash 增量算法。
- 配置当前是 `json.dumps(sort_keys=True)` 字符串：字段顺序、默认空格、ASCII 转义与原字符串精确兼容。
- 保留 model/api_base/dimensions=1024/chunker；修改表示法须走另批迁移，不能偷偷重嵌入全库。
- 新节点 `li_<hex32>`、原节点复用、先嵌入后事务发布；CAS active 指针、节点、release/成员写入同事务。
- embedding 数量/1024 维/有限/非零检查；用量成功失败均记；旧节点保留供会话和历史引用。
- 回滚前检查版本存在、配置一致、声明/成员/物理计数一致；健康探针复用同一校验服务。
**非目标**：重建生产索引、清理旧节点、换向量维度/模型/算法、引入默认 VectorStore schema、实现运维调度脚本。
**具体验收**：
1. 相同快照重复摄取不新增 release/节点/embedding；单文件、metadata、已发布知识修订逐项差异符合 oracle。
2. 中文、emoji、组合字符、CRLF/末尾换行、路径排序、切块边界、JSON 字符串都有跨语言黄金样本。
3. 失败 embedding、空源、缺源、NaN/Infinity/零向量/维度错均不改变 active；已产生用量仍可见。
4. 两发布者扫描同旧版，最多一个 CAS 成功；失败事务不留半个 release；旧引用继续回读。
5. 配置失配或节点缺失拒绝回滚；合法回滚只改变 active；旧会话保持已有 release 绑定。
6. 冻结合成 PG 通过后，P5 才评估隔离真实快照/真实 embedding 与质量回归；不把单测等价当质量验收。
**证据**：源快照清单、切块与 metadata diff、配置原字符串样本、embedding 次数、CAS/回滚事务记录。
**交付接口**：scan/plan/ingest/health/rollback 服务接口，供 J4-03 运维入口调用；脚本不另写一套校验。
**完成门槛**：增量、失败与竞争发布均通过合成 PG 测试；旧 release/引用保持可读才允许进入 P5。
**回退**：停止 Java ingest 写入者并撤接线；合成测试库按快照恢复；生产索引在本卡未被修改。

## 给集成者的交接摘要

这七卡按端口先行组织：模型/MCP和只读检索→隔离最小问答→持久任务→显式 Agent 循环→产品聊天→索引写入。
SQL/schema、根依赖和应用装配集中管理，卡内 Agent 只改独占 feature package、测试和证据目录。
重点风险是 Python 单事件循环原子段、input_hash Unicode/JSON 字节、配置指纹原字符串、任务账本与外部评测的不确定窗口。
现有心跳认领不是 fencing；跨副本取消不保证立即停止算力；模型提示纪律不是服务端强授权；这些边界必须按基线记录。
每卡完成只表示其测试范围通过；真实模型、PG 场景、浏览器、生产切换和用户验收分别由后续批次放行。
