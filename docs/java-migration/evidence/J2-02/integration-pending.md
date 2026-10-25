# J2-02 integration_pending 登记（未完成集成项，不得计为通过）

## IP-1 停号触发任务取消与临时输入清理的真实执行体

- **状态**：integration_pending（未完成；默认装配 NoOpLocalTaskCancellation 占位）。
- **冻结语义**（对齐 user_service.set_enabled 的提交后分支）：
  停号 DB 事务（enabled=false + 撤 token + rag_requests→cancelled + rag_task_inputs 删除 +
  任务关联成绩删除）提交**之后**，调用 `LocalTaskCancellationPort.cancelLocalForUser(userId)`
  一次；实现必须自吞异常（最多留日志），失败不影响已提交停号终态。
- **已验证部分**：端口调用时序与吞错语义（AdminDeletionTransactionTest.
  disableCascadesTokensTasksAndCallsCancellationPort：记录桩断言恰好一次调用；启用不触发）。
- **未完成部分**：真实“取消本进程执行中的 RAG/评测协程 + 源码限时寄存输入清理”执行体，
  归 J3-01（任务执行器）接线；接线前不得把本项写入完成/通过记录。
- **验收归属**：任务卡验收 F 的“停号触发 J3-01 取消”子项在本卡内**不通过、仅登记**；
  J2-02 侧的“按账号行事务顺序裁决”（evidence §2）已真实 PG 验证。

## IP-2 auth 包消费缺口（接口变更请求，见 handoff/J2-02.md）

1. `PasswordRuleException` 非 public：AdminController 按“类名 == PasswordRuleException”
   识别并转 400 detail 透传（当前行为等价；请求 auth owner 将其 public 化或提供端口化校验）。
2. `UserAuthRepository.verifyCredentials`（删除授权的操作者密码校验，含 dummy 等价工作）
   未端口化：admin 依赖具体类；请求提升为 CredentialInvalidationPort 或新端口方法。
3. PATCH roles/daily_quota 后无法清 token→Principal 60s 正向缓存（Python clear_token_cache）
   ：auth 未暴露缓存清理方法；影响 = 改配额/角色后最长 60s 内旧 Principal 仍被 resolve
   （token 仍有效，与源一致；差异仅在缓存时效）。请求暴露
   `clearPrincipalCache()`/等价端口。

## IP-3 差分 suite J2-02 未跑

`<J0-02_ORACLE_RUNNER> --suite J2-02 ...` 尚未执行（P1 差分 runner 未在本 worktree 授权运行）；
HTTP 兼容以 golden 36 条 observation（切片 fixture）+ Python 测试语义对齐覆盖，
差分结论待集成者在主 worktree 统一补跑。
