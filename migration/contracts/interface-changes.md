# P1 接口变更登记（集成者账本）

本文件由 J1-01 集成者维护，登记各卡对 `transaction-ports.json@p0-v1` 拟议签名的语义固化与增补。只做加法/澄清，不做破坏性调整；破坏性调整须升级契约版本并通知消费者。

## J1-02（模型/embedding/harness）已接受

- `contracts.model.ChatInput(model, jsonPayload)`：`jsonPayload` 约定为
  `{"messages":[{ "role":..., "content":... }...], "model"?:...}` 的 JSON 字符串；
  富接口由 `model.ModelClients` 提供（`chat(List<ChatMessage>, ...)`、`streamChat(...)`、`embedTexts(...)`）。
- `contracts.model.HarnessReply(submissionPhase, payloadJson, error)`：`payloadJson` 恒为规范化 JSON
  （成功=工具结果 JSON；失败=`{"ok":false,"error":...,"detail":...}`）；`error` 为错误码或 null。
  错误码集合新增：`harness_bad_request`（参数不可序列化，NOT_SUBMITTED）、
  `harness_deadline_exceeded`（期限前到期，NOT_SUBMITTED）。
- 有意差异（源更保守方向）：参数序列化提前到建连前（畸形参数 NOT_SUBMITTED、零 HTTP）；
  取消为 shield 模型（不伪称远端已取消、账记 unknown）；异常日志不回显 payload/源码哨兵。
  全表见 `evidence/J1-02/unverified.md`。
- 工程注意：classpath 同时存在 Jackson 2/3，RestClient 优先 Jackson 3；Jackson 2 树作 RestClient
  body 会被按 POJO 展开——统一以 UTF-8 字节数组或 Jackson 3 树发送。

## J1-03（只读检索）已接受

- `contracts.retrieval.SearchInput(question, queryVector)`：POC/测试允许 queryVector=null
  （由替身决定）；富参数（rewrite/conversationContext/limit）经实现类重载
  `PgReleaseReader.searchPinned(snapshot, input, limit)` 提供，暂不扩展契约字段。
- `RetrievedChunk`/`SourceChunk` 的 source_path/section/title/metadata 富字段由
  `retrieval.RetrievalTrace.nodes` 交付，契约 record 暂不扩展（J3-03 接管时再评估）。
- `ReleaseSnapshot` 的 blockedReason/成员完整性由实现侧 fail-closed 异常表达，暂不扩展。
- 固定向量容差：仅 raw score 绝对误差 ≤1e-12（U09）；顺序/引用 round4/业务得分精确。
  J1-03 实测差值为 0。

## P1 规格审查备案（review-spec.md 建议 S1-S6，2026-10-25）

- S1（已登记为有意差异）：`ModelFailureClassifier` 对异常 cause 链**每层**消息做标记词匹配；
  Python `is_retriable_llm_error` 只查最深层 cause。实际影响几乎不可达（需浅层消息恰含标记词
  而深层不含），方向为放宽（多判可重试）。保留现状，J2-04/J3 消费方按"可能多一次重试"理解。
- S2：负值 Retry-After 头按 0 处理（Python httpx 同样忽略负等待）。
- S3：全角空格分词行为以 Java 实现为准（Python 分词器对 U+3000 的处理在冻结源同位）。
- S4：cause 链遍历深度无上限（Python 递归同样无上限）；S5：`explain-` 空尾后缀按非讲解处理（与源一致）。
- S6（待办）：后续 Spring 接线消费模型端口时补 cache_hit/replay 记账路径（P2 集成时关闭）。

## J2-01（认证）已接受（2026-10-25）

- `contracts.identity.AuthSettings` 维持占位 record；运行期设置经 auth 包 `AuthSettingsProvider`
  （环境同名变量 + config.py 同款校验）。后续若需要下沉为契约字段再走版本化变更。
- **有意差异（卡片验收 D 授权）**：重置令牌消费 Java 加 status 守卫保证并发严格一次
  （Python 顺序流一致、并发存在双消费窗口）；golden 19 条 auth observation 逐字段零差异。
- change-password 求值序以 golden 为准修正：401（认证）→422（体）→404（目标）。
- J2-02 消费端口（auth 包内）：`CredentialInvalidationPort`（管理员改密/停号撤销）、
  `PasswordResetApprovalPort`（审批签发/驳回/树隔离列表）。
- 平台差异登记：secure-headers 中间件头未实现（归平台层，J4-03 前评估）；JSON 极端输入
  NaN/Infinity 字面量行为差异（Java 拒绝，Python 容忍；已列 evidence/J2-01 差异表）。

## J2-02（管理员/班级树/审计）已接受（2026-10-25）

- 消费端口落地：`admin.AdminClassScopePort`（班级范围查询，供 J2-03/用量/问题工作台）、
  `admin.LocalTaskCancellationPort`（停号→任务取消冻结端口，NoOp 占位，**integration_pending
  IP-1**：J3-01 真实任务实现后补验，当前仅端口时序断言，不得写成已通过）。
- auth 接口请求（登记待后续消费者需要时再改，当前不阻塞）：PasswordRuleException 非 public、
  verifyCredentials 未端口化、PATCH 配额/角色后 60s Principal 缓存差异（golden 归一化映射登记）。
- 求值序门禁：401→422→404门禁→业务，与 J2-01 修正后的 golden 序一致。
- **IP-3**：J2-02 差分 suite 待集成者在 P2 集成回归统一补跑（golden 36 条已在单卡测试覆盖）。

## J2-03（作业/成绩/CSV）已接受（2026-10-25）

- 消费端口：`assignment.catalog.AssignmentCatalogPort`（submissionSnapshot/getAssignment/
  bestScore/isPastDeadline——J2-04 受理分值与汇总动态分值两口径分界；是否入
  transaction-ports.json 留 J2-04 集成时决定）。
- 求值序（源证实）：assignments 守卫为依赖形式 401→404门禁→422（与 admin.py 相反）。
- CSV/时间实现约束：`utf-8-sig` 非 JVM 字符集，Content-Type 需直接写头绕过 Spring
  MediaType；golden 时间戳为 Python str(datetime)（空格分隔）——均已按源实现并断言。
- 协议清单失败降级：`{"protocols":[],"error":…}`，error 必带，不伪称有效空清单。
- 布局登记：J2-03 测试类位于 test:assignment/ 根（跨 catalog/report 共享 harness），
  所有权不变，与 J2-04 的 assignment/submission/** 无冲突。

## J2-05（会话/配额/用量/来源/模板/成绩读取）已接受（2026-10-25）

- `contracts.session.MessageInput` 已按请求扩展为 (content, sessionId, role, metadata)
  四字段（兼容构造器保留单参形态）；SessionPort.appendUser 完整能力在
  `session.SessionService.appendMessage`（事务参与，已测试），J3-01 受理事务消费完整形态
  （原 IP-J205-1 关闭）。
- **IP-J205-2（integration_pending）**：活动会话删除×执行中任务组合按 PLAN 归 J3-01
  真实任务实现补验，当前仅冻结端口断言，未写成已通过。
- 语义固化：QuotaExceedException（429 语义载体）；checkAndCountDaily 独立事务先递增后检查
  的失败计数源现状保留；storage_bytes 物理字段归一化（session_count 精确）。
- 求值序按源固化：401→422→404；维护端点 503（未配置）/403（缺失/不符）、dry-run/apply 分离。

## J2-04（正式提交/MCP评测/成绩）已接受（2026-10-25）

- 消费端口落地：`assignment.submission` 实现 contracts.grading.FormalSubmissionPort，
  成绩只读经 `SubmissionRecordReader`（显式来源/assignment_id）；TaskGradeParticipant
  （聊天任务成绩）**归 J3-01 接线**，本卡不实现。
- **登记差异**：U+FFFD 计数 JVM/CPython 不一致（ED 代理区 1↔3）——自实现
  `PythonUtf8`（assignment.submission）以 CPython 冻结 hash 电池锁定（验收 F 路径）。
- 「已提交后挂起」：Python 504 / Java 502（均不重发、账本保留）；500 异常渲染缺口
  归平台层（P2 收口前由集成者补；未补前不宣称该分支兼容）。
- 求值序（源证实）：快照404→截止400→协议400→评测413/429/504/502→受理口径响应。

## P2 规格审查收口（2026-10-25，review-spec.md 建议 S1/S3/D2）

- S1 已关闭：J2-04 登记节随本提交入库（审查基线 c7cbab3 时未提交）。
- S3 已关闭：4 条"未分类"SKIP 为状态依赖用例，等价证据映射见
  evidence/P2-regression/golden-diff.md 分类附注。
- D2 已关闭：500 兜底渲染实现为 Starlette 平价（500 + 纯文本 "Internal Server Error"，
  不回显异常细节）。注意：p0-v2 未包含 500 观测，该形状未经差分钉死，按 FastAPI/Starlette
  默认实现，P5 全矩阵 oracle 复核后定稿。

## 消费指引（P1 内）

- J1-04/P2 消费模型端口：经 `io.dpdk.assistant.model.ModelClients` / `harness.HarnessClients` 入口。
- J1-04 消费检索：经 `retrieval.PgReleaseReader`（limit 重载）。
- Spring Bean/配置接线（settings 绑定与单例装配）由集成者在 P2 集成窗口统一完成。

## J3-02（Agent 五工具/受控循环）集成裁决（2026-10-25，集成者）

J3-02 已交付（worker/j3-02@3d426e9，自有测试 124/124；黄金场景 Python 可达子集对齐）。三项接口变更申请裁决如下：

1. **TaskGradePort**：不新增 contracts 接口。`agent/port/TaskGradePort`（`TaskGradeResult recordTaskGrade(TaskGradeInput)`，签名与冻结 record 一致）定为聊天任务成绩参与的消费面；J3-01 的 TaskGradeParticipant 真实实现经适配实现该端口（归属 task/**，指向 contracts.grading.TaskGradeParticipant 语义）。
2. **TaskJobLedgerPort**：不新增 contracts 接口。`agent/port/TaskJobLedgerPort`（findJob/recordJob/reserveGradingJob/finishJob/updateTaskSource，对位 rag_issue_service 同名方法）定为评测作业账本消费面；J3-01 的 task/** 仓储实现之（rag_task_jobs 建模、reserve 与配额同事务、find_job 幂等）。集成期以真实仓储替换 J3-02 的替身并回归 AgentScenario/TaskLedgerTest。
3. **ChatModelPort 富接口**：暂不改 contracts。Agent 侧以 `agent/AgentChatModel` 为模型替身边界（W9 口径已在其契约注释声明）；真实模型（OpenAiCompatibleChatClient）到 AgentChatModel 的适配器由 J3-03 集成窗口由集成者编写。tool-calling 富接口（多消息/tool_calls/usage）若 J3-03 集成发现缺口，另行登记变更。

行为差异备查（J3-02 申报，集成复核）：孤立代理项 getBytes 差异（实际不可达）、splitlines 全行界覆盖、get_reference_answer 固定 120s 期限、非流式 delta 粒度为整条消息（脚本化替身口径，SSE 细化归 J3-03）。

## J3-01（持久任务生命周期与恢复）集成裁决（2026-10-25，集成者）

J3-01 已交付（worker/j3-01@40cb46c，自有测试 8类50/50；真双JVM+合成PG认领实测；integration_pending 三项闭环）。六项申请受理如下：

1. **Probe/AdmissionResult/CompletedAnswer/TaskRow 字段补全**：受理，列入 P3 集成窗口（下一会话）执行——富形态（TaskProbeOutcome/TaskAdmitOutcome/TaskRecordRow）现公开于 io.dpdk.assistant.task 包，冻结端口按占位实现，不影响消费方编译；正式化到 contracts 后 J3-03/J3-04 消费切换。
2. **AdmissionInput 附件四字段（attachment_name/suffix/source_text/source_origin）**：受理，且为 **J3-03 集成前阻断项**——上传附件任务的恢复依赖 rag_task_inputs 寄存，缺字段会丢执行源。随第1项一并补契约字段与 TaskTemporaryInput 子结构。
3. **TaskGradeInput 来源说明**：登记（实现从任务行与 rag_task_inputs.source_origin 取同源值，已测试）；暂不改契约字段。
4. **SessionPortAdapter.appendUser 委托更新**：受理，P3 集成窗口由集成者改为委托 SessionService.appendMessage（J3-01 已有 TaskSessionPortBridge 可参照），Spring 装配单点选用。
5. **NoOp 装配替换**：受理，P3 集成窗口——admin.NoOpLocalTaskCancellation 让位于 task.TaskLocalCancellation（加 @Component 单 Bean），保持 IP-1 真实取消闭环。
6. **端口语义澄清（claim 先于占槽；finish 失败执行体 result 不成立）**：登记为冻结语义（测试钉死），无签名变化。

**P3 集成窗口待办汇总**（集成者，下一会话）：契约字段补全（1/2）→ SessionPortAdapter 委托（4）→ NoOp 替换（5）→ AgentChatModel 真实模型适配器（承 J3-02 裁决）→ J3-03 chat/SSE 集成。

## U12 续接集成窗口（2026-10-25）

- AdmissionInput保留无附件8参构造，新增TaskTemporaryInput(attachmentName,attachmentSuffix,sourceText,sourceOrigin)，同一受理事务仅寄存rag_task_inputs，终态/TTL清理不变。消费者J3-01/J3-03/J3-04；补真实PG寄存与重放断言。
- Probe/AdmissionResult补Optional<TaskRow>/Optional<TaskMessage>；TaskRow镜像完整任务行（主键taskId）；CompletedAnswer补messageId。task内部富接口保持兼容；冻结端口不依赖task仓储类型。
- SessionPortAdapter.appendUser真实委托；取消单Bean改为TaskLocalCancellation；TaskWiring管理设置和心跳生命周期。
- 模型适配受理：model.AgentModelAdapter(OpenAiCompatibleChatClient,String)实现AgentChatModel；client添加富工具调用出口，旧chat/stream兼容。只扩展模型协议，不改变产品SSE/权限/扣次；固定HTTP替身验证，不做真实验收。
- 核查发现TaskSettings默认8 active/32 waiting/30s排队与冻结config.py:77-79不符；本窗口纠正20/20/10s，纳入配置桥接验证，不改变Python基线和阈值。
- COMP-003实施：已有submitted作业running仅轮询，unknown/查询失败由HarnessResultUnknown穿透图层，在产品层保存system_error/answer_reason=harness_result_unknown，消息元数据及JSON/SSE附failure_code同值；保留submitted账本，终态清理输入。首次缺账本仍可首次受理。现有Python黄金不变，按p0-approved-differences/comp003.json单独验收。

## P3规格轴阻断修复窗口（2026-10-25）

- SearchInput增加conversationContext，保留question/queryVector两参构造兼容；QueryRewritePort新增默认上下文重载，HeuristicQueryRewriter覆写，PgReleaseReader正式消费会话上下文。J3-03传递真实会话检索语境，补端到端追问消解回归；不改变排序/向量/发布版本配置。
- 产品RAG_REQUEST_TOKEN_BUDGET必须在每次planner/finalize模型回复后、工具副作用前执行，不能仅在完整Agent结束后检查；AGENT独立预算不代替产品预算。补高AGENT预算+低RAG预算的真实账本零MCP/零grading扣次/零成绩回归。
- 规范轴成绩边界修复：TaskGradeInput.resultsJson明确为原始results数组JSON，TaskGradeLedger从临时输入取source_origin并只包一层。Agent侧不再嵌套来源对象；新增实际PG数组结构与失败用例画像回归。ChatAgentPorts成绩SQL迁入grading/query公开统计服务，chat仅委托；产品不存在的非task成绩写入路径明确拒绝，不绕过task参与者和账号锁。

## P4公共接口先行（2026-10-25，P3门禁已通过）

- PreparedIndex补releaseId/configuration/createdAt/desiredNodes/changedNodes，新增IndexNode(id,content,metadata,embedding)。复用节点可无向量，发布实现对changed节点严格验证1024维/finite/nonzero；PublishReceipt保持releaseId。消费者J4-01/J4-03，发布仍CAS+节点+release+成员一事务。
- 索引富批量消费面放index.IndexBatchEmbedding(List<String>,UsageCore.UsageRecorder)，集成装配适配已有OpenAiCompatibleEmbeddingClient.embedTexts和ModelUsageLedger；不修改公共单文本EmbeddingPort。IndexUsageSink finally刷账、失败告警降级，复用用量开关/价格版本。
- J4-02 OriginalQuestionVerifier分prepareAdmission（共享chat限流+日配额，先于issue读取）和verify（admission+当前release检索/生成/强制judge/总墙）；不创建普通chat任务、不复制task仓储。知识作者title/body保留Python字节语义，不新增脱敏安全差异；H的正文脱敏适用于运行输入/问题/反馈/note/事件。
- CLI增加dpdk.task.background-enabled开关，默认true兼容服务；CLI最高优先级false并关闭启动迁移，防dry-run触发孤儿恢复/DDL。Boot mainClass显式Application以免新增OperationsMain产生歧义。
- J4-02真实跨链发现TaskStore.insertIssue错误地随机id，而Python使用task/request主键且消息issue_id已为taskId。集成者窄修id=taskId；保持request_id唯一闸、不改DDL，不迁动旧库，新增问题反馈真实链回归验证。

## P4双轴阻断修复窗口（2026-10-25）

- ST-P4-02：已发布知识读取收回knowledge/workflow，新增contracts.knowledge.PublishedKnowledgeReader（有序id/title/body/revision快照）；IndexService显式构造注入并删除index内rag_knowledge SQL。不允许隐式空来源构造，纯测试需明确提供published fixtures，PG用真实知识仓储。
- ST-P4-01：CI真实jar验收显式传dpdk.acceptance.jar绝对路径；保持与本机成功命令相同，不用脚本exit0替代测试。
- SP-P4-01：工作流请求先严格验证完整JSON文档，尾随对象/垃圾返回冻结422且零知识/修订写入，补合法空白与Unicode位置反例。
- 真实CI跨平台补修：SystemClockAdapter.nowUtc在端口边界截断MICROS以匹配Python datetime；Linux纳秒墙钟曾令SessionService.purgeExpired的严格格式化失败。预算monotonicNanos与格式化器拒绝非法纳秒护栏不变；新增固定纳秒时钟纯契约与真实PG dry-run/apply红绿回归。

## P5明确配置与框架兼容（2026-10-25）

- DataSource增加dpdk.datasource.maximum-pool-size/DPDK_JAVA_DB_MAX_POOL_SIZE，默认8保留既有运行；必须为正数。冻结容量两端按baseline-environment.json:60的32显式配置，不修改阈值/配额/负载；不再通过测试私设pool绕过真实装配。
- P5严格collector发现源框架GET/HEAD文档8路缺失；Java将从只读Python黄金OpenAPI和冻结FastAPI0.115.6文档渲染器生成静态契约资源，实际Java端点返回相同JSON/HTML哈希，不更改黄金，不把文档路由缺失列新豁免。生产运行不依赖Python。
