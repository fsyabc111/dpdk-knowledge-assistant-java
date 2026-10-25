# J2-05 integration_pending / 接口变更请求

## IP-J205-1：SessionPort.appendUser 受阻于冻结契约 MessageInput（阻塞中，等集成者）

`contracts/session/MessageInput.java` 当前仅有 `content` 字段（javadoc 注明
"字段由 J2-05 登记补全"），无法表达受理事务内用户消息写入所需的
sessionId/role/metadata。当前状态：

- `SessionPortAdapter.appendUser(DbTx, MessageInput)` 抛 IllegalStateException 指向本登记；
- `ensureOwned` / `touch` / `discardEmpty` 已完整实现并经测试；
- 全量能力经 `SessionService.appendMessage(DbTx, sessionId, role, content, metadata)`
  （同为参与 caller 事务，append + touch 同提交、异常同回滚，已测试）。

**请求集成者的最小变更**：`MessageInput` 补字段为
`(String sessionId, String role, String content, Map<String,Object> metadata)`
（或 SessionPort.appendUser 改签名）。变更后 `SessionPortAdapter.appendUser`
单点改为委托 appendMessage（契约版本按流程升级并通知 J3-01/J3-03/J3-04）。
过渡期消费方直接使用 `session.SessionService`（P1 包内公开类，非 contracts 冻结面）。

测试锚点：`SessionLifecycleTest.sessionPortAppendUserIsBlockedByFrozenContractWithRegistrationPointer`
（断言抛出与登记指向；**不得视为 appendUser 已通过**——以最终契约为准的通过态在
集成者变更后补验）。

## IP-J205-2：活动会话删除的执行中任务处理（P3 接线项，按卡登记）

冻结源 `delete_session` 只显式级联 citations→messages→grading 脱钩→session，
**不触碰 rag_requests/任务输入**：活动任务的取消、输入清理与"删除后任务完成落库
引用已删会话"的组合行为由任务体（J3-01 task.LifecyclePort/cancel 语义）在 P3
接线时组合验收。本卡已固定的相关语义：

- 删除不因存在 pending rag_request 而失败（与源一致）；
- `discardEmpty`（并发首问败方补偿）要求"无消息且无 rag_request"才清理
  （SessionLifecycleTest 验证 rag_request 存在时空会话保留）。

**不得写成已通过**：跨模块组合（删除↔任务取消/恢复/完成回写）登记为待 J3-01
接线后补验，责任卡 J3-01（消费 task.LifecyclePort）+ 集成者。

## 接口变更请求（加法/澄清，非破坏）

1. `usage.QuotaExceedException`（J2-05 包内新增 public 类型）：QuotaParticipant
   超额抛出类型，携带 kind/quota/retryAfterSeconds 与 detail 文案
   `daily {kind} quota exceeded ({quota}/day)`；J2-04/J3-01 映射 HTTP 429 +
   `Retry-After` 头。请求集成者在 interface-changes.md 登记该语义固化。
2. `usage.QuotaParticipant` 语义澄清（与 transaction-ports.json 一致，无签名变化）：
   - `countAndCheck` 的 `utcDay` 由调用方经 ClockPort 计算（实现不重复取时钟）；
   - 独立事务版 `checkAndCountDaily(subject, kind, quota)`（J2-04 grading 路径用）
     保留源现状：先递增后检查，**失败计数留存**（行为与 Python
     check_and_count_daily 一致；差异在于 Python 版抛 HTTPException、Java 版抛
     QuotaExceededException 由端点层转换——登记为有意差异）。
3. `session.MaintenanceSettings`（J2-05 包内新增）：maintenance_api_key（空=503）
   与 session_retention_days（默认 30）。P2 Spring 接线时由集成者绑定配置源
   （对应 Python Settings.maintenance_api_key/session_retention_days）。
4. `grading.query` 包为本卡独占路径；作业维度的成绩过滤（assignment_id）仓储侧
   已预留 `listSubmissionsForAssignment`，**未暴露 HTTP 参数**——J2-03 消费时
   需走自己的端点（验收 F：通用成绩与作业汇总不混用过滤逻辑）。
