# J2-02 actor×target×operation 权限矩阵（实现步骤 1 登记）

来源（只读冻结快照）：`backend/app/api/admin.py`、`backend/app/services/user_service.py`；
测试佐证 `backend/tests/test_user_hierarchy.py`、`backend/tests/test_user_delete.py`。
HTTP golden：`migration/fixtures/p0-v2/python-golden.json`（/api/admin 前缀 36 条 observation）。

## 0. 主体分类

| 主体 | 判定 | 说明 |
|---|---|---|
| leader（组长） | roles 含 `leader` | 管全部；唯一可改归属/角色/建老师 |
| 老师（admin） | roles 含 `admin` 且非 leader | 只管 `created_by=自己` 的**纯 viewer** 学生 |
| 静态应急 token 主体 | subject ∈ API_TOKENS_JSON 主体集（**不论 roles**） | 按 leader 语义放行（树不受限）；但主体不在 users 表 → 删除密码校验天然失败、改自己角色/停自己不可能（无行） |
| 其余（viewer/operator、local 主体） | — | 整组端点 404（不泄露存在性） |

`_is_leader_like` = leader 角色 **或** 静态 token 主体；`MANAGER_ROLES={admin,leader}` 是进组门禁（404）。
非 `AUTH_MODE=account`（local/token）整组 404。

## 1. 端点 × 操作矩阵

行=端点；列=检查顺序（自左向右求值，先命中先返回）。

| 端点 | 求值顺序（401 认证 → 422 体 → 之后） | 业务规则 |
|---|---|---|
| GET /users | 401 → 404 门禁 | leader-like：全部用户（id ASC）；老师：**本人行在前**+自建学生；静态 token：全部 |
| POST /users | 401 → 422 → 404 门禁 → 400 角色 | leader-like 请求 roles 含 leader → 400 CLI bootstrap；老师 roles 非 ⊆{viewer} → 400 teachers can only create viewer；随后服务端 ValueError（roles 子集/daily_quota≥0）→ 400；逐 id：格式 `^U\d{11}$` 不符或已存在 → skipped；初始密码=学号、must_change=true、created_by=actor |
| POST /users/{id}/reset-password | 401 → 404 门禁 → 404 树隔离 → 404 行 | 经 CredentialInvalidationPort.resetPasswordToInitial（must_change 置位+全撤 token） |
| POST /users/{id}/set-password | 401 → 422 → 404 门禁 → 404 树隔离 → 400 规则 → 404 行 | 经 CredentialInvalidationPort.setPasswordByAdmin（规则文案逐字） |
| GET /password-reset-requests?status= | 401 → 404 门禁 | leader-like 全部；老师 JOIN users 只看自建学生（孤儿申请对老师不可见） |
| POST /password-reset-requests/{rid}/approve | 401 → 404 门禁 → 404 | 经 PasswordResetApprovalPort.approve：pending+用户存在+scope 内 → 签发一次性令牌（明文仅此一次）；否则 404 |
| POST /password-reset-requests/{rid}/reject | 401 → 404 门禁 → 404 | pending → rejected；scope 越界/非 pending/不存在 → 404 |
| POST /users/{id}/status | 401 → 422 → 404 门禁 → **400 自停**（id==subject 且 enabled=false，先于树检查）→ 404 树隔离 → 404 行 | 停用=单事务：UPDATE users + 撤销全部 token + pending rag_requests → cancelled/account_disabled + 删 rag_task_inputs + 删任务关联 grading_submissions；提交后 best-effort 取消本进程任务（J3-01 端口） |
| PATCH /users/{id} | 401 → 422 → 404 门禁 → 400（老师改 roles/created_by）→ 400（roles 含 leader）→ 400（改自己 roles）→ 404 树隔离 → 400 服务规则 → 404 行 | created_by 校验序：先“不得指向自己”后“创建者须存在”；更新后返回整行 UserInfo |
| POST /users/delete | 401 → 422 → 404 门禁 → **403 密码**（actor.subject 自己的密码，含 dummy 比对；静态 token 主体无行必 403）→ 逐 id | 跳过：自己/不存在/不可管（teacher 树；此处只认 roles 含 leader，**不认静态 token**）；可管即单事务级联删除；deleted 非空写一行审计 |
| POST /users/assign | 401 → 422 → 404 门禁 → 400（非 leader-like）→ 400 目标 | 目标 created_by 须为存在+启用+roles∩{admin,leader}（“assign target must be an enabled teacher account”）；逐 id：不存在或 roles 非 ⊆{viewer} → skipped；否则改归属（源允许改已归属 viewer，不强制未分配） |
| GET /audit-log | 401 → 404 门禁 | leader-like：全部（created_at DESC,id DESC,LIMIT 100）；老师只看 actor=自己 |

树隔离 `_get_manageable_target`：target 不存在 → 404；非 leader-like 且 can_manage=false → 404（不区分存在性）。
`can_manage`：actor 含 leader → true；actor 无 admin → false；target.created_by≠actor.subject → false；target.roles 空 or ⊄{viewer} → false。

## 2. 自保护与防提权

- API 不能授 leader：POST /users（leader-like 也拒）与 PATCH（roles 含 leader）均 400，leader 仅 CLI 引导。
- 老师不能建老师/operator；不能改 roles/created_by（只能调配额）。
- 不能停用自己（400，先于树检查）；不能改自己角色（400）。
- 静态 token 主体：树隔离按 leader 放行（无 users 行），但删除需密码 → 403；`cannot disable your own account` 的 subject 比较对同名 users 行同样生效（400 先于 404）。

## 3. 级联删除范围（单事务，顺序受 FK 约束）

api_tokens（先删）→ assistant_citations（按 message）→ assistant_messages → grading_submissions.session_id 置 NULL（成绩保留脱钩）→ assistant_sessions → password_reset_requests → usage_counters → users.created_by 置 NULL（学生归未分配，先于删 users 行）→ users 行。
**不**触碰 rag_requests/rag_task_jobs（源如此：删除路径与停号路径清理范围不同）。
deleted 非空 → 单行 admin_audit_log（actor/action=delete_users/targets_json/created_at），与删除同事务。

## 4. Java 落点

- 门禁/树/leader-like：`admin/AdminTreeRules`；HTTP 层 `admin/AdminController`；
  SQL/事务 `admin/AdminUserRepository`；范围端口 `admin/AdminClassScopePort`；
  停号进程内取消 `admin/LocalTaskCancellationPort`（integration_pending，见 integration-pending.md）。
- 密码确认复用 auth 包 `UserAuthRepository.verifyCredentials`（含 dummy 等价工作）；
  改密/停号经 `CredentialInvalidationPort`；审批经 `PasswordResetApprovalPort`。
