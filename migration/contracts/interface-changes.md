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

## 消费指引（P1 内）

- J1-04/P2 消费模型端口：经 `io.dpdk.assistant.model.ModelClients` / `harness.HarnessClients` 入口。
- J1-04 消费检索：经 `retrieval.PgReleaseReader`（limit 重载）。
- Spring Bean/配置接线（settings 绑定与单例装配）由集成者在 P2 集成窗口统一完成。
