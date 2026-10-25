# J2-02 删除级联与审计证据（验收 E）

测试：`AdminDeletionTransactionTest.deleteCascadesDataAndWritesAudit` /
`deleteTeacherUnassignsStudents` / `auditLogScopedByActor`（PG 实例 j202delete 库，B0015+V0014）。

## 1. 删除前后各关联表摘要（学生 U20260000051：会话 sess-d1[消息 m1,m2+引用 c1]、
usage_counters 1 行、pending 重置申请 req-d1、登录 token、会话关联成绩 grade-session-d1、
任务关联成绩 grade-task-d1）

| 表 | 删除前 | 删除后 | 说明 |
|---|---|---|---|
| users | 含 51 | 无 | 行删除 |
| api_tokens | 51 的登录 token ≥1 | 0 | 先删（FK），token 立即 401 |
| assistant_sessions | sess-d1 | 0 | 显式级联 |
| assistant_messages | m1,m2 | 0 | 显式级联 |
| assistant_citations | c1 | 0 | 显式级联 |
| grading_submissions | 2 行 | 2 行（保留） | grade-session-d1 的 session_id 置 NULL 脱钩；grade-task-d1 原样保留（删除路径不触碰任务关联成绩） |
| password_reset_requests | req-d1(pending) | 0 | 清 |
| usage_counters | 1 | 0 | 清 |
| rag_requests/rag_task_inputs | task-d1 | 原样保留 | 照源：删除路径不清理（与停号路径不同） |
| admin_audit_log | — | +1 行 | actor=操作者、action=delete_users、targets_json=["U20260000051"]（jsonb 落库原文）、created_at=受控时钟 |

被删用户的登录 token 在删除后即刻 401（api_tokens 行删除 + resolve 缓存经
CredentialInvalidationPort.revokeUserTokens 清空）。

## 2. 删除老师 → 学生归未分配

删除 TEACHER_A 后：users 中 STUDENT_A2 仍存在、created_by=NULL（未分配组）；
leader 列表可见这些学生。

## 3. 审计 actor/scope

- 一次批量删除（无论目标数）写**一行**审计，targets_json 为本次全部 deleted（同事务）。
- leader/静态 token：读全部审计（created_at DESC, id DESC, LIMIT 100）；
  老师：`WHERE actor=自己`，只见自己的操作行。
- viewer 主体 GET /api/admin/audit-log → 404（端点隐藏，不进组）。
- 审计行 id=`aud_`+12 位 hex（uuid4 截断，照源）。

## 4. 停号级联（同事务，_set_enabled_sync 库内部分）

停用 U20260000081（有 pending rag task + 任务关联成绩 + 登录 token）后：

| 项 | 结果 |
|---|---|
| users.enabled | false |
| api_tokens | 全部 revoked（同事务 UPDATE） |
| rag_requests(task-e1) | status=cancelled、reason=account_disabled |
| rag_task_inputs | 删除 |
| grading_submissions(任务关联) | 删除（经 rag_task_jobs.job_id） |
| resolve 缓存 | 提交后经端口清空，旧 token 立即 401 |
| 重新启用 | 不复活已撤销 token；不触发取消端口 |
