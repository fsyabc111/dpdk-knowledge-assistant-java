# J2-02 golden 对照与归一化映射（36 条 observation）

来源：`migration/fixtures/p0-v2/python-golden.json` → 切片
`backend-java/src/test/resources/migration/J2-02/golden-admin.json`（36 条，route_id 以
`/api/admin` 开头全量）。断言位于 `AdminTreeContractTest`。

## 1. 逐类覆盖与结果

| golden 类别 | 条数 | 断言方式 | 结果 |
|---|---|---|---|
| GET /users::success | 1 | golden 子树（U2027 种子，created_at 微秒对齐 oracle）整体 JsonNode 相等（字节级字段序无关、值精确） | 零差异 |
| GET /audit-log::success、GET /password-reset-requests::success（空态） | 2 | JsonNode 相等 | 零差异 |
| POST /users::success、assign::success、delete::success（数组体） | 3 | JsonNode 相等 | 零差异 |
| PATCH::success | 1 | 字段逐项（见 §2 映射） | 零差异（归一化字段除外） |
| status/reset-password/set-password/approve/reject::success | 5 | 状态码 + 归一化字段 | 通过 |
| GET password-reset-requests::success-after-request / success-second | 2 | user_id 精确 + id/created_at 归一化 | 通过 |
| ::unauthorized（9 条） | 9 | 401 + WWW-Authenticate + `{"detail":"missing or malformed bearer token"}` 字节精确 | 零差异 |
| ::invalid-input（5 条：users/assign/delete/set-password/status + PATCH daily_quota） | 6 | 422 detail 数组 JsonNode 相等（含 greater_than_equal ctx/input） | 零差异 |
| ::invalid-resource（approve/reject/reset-password p0-missing） | 3 | 404 `{"detail":"not found"}` 字节精确 | 零差异 |
| ::viewer-hidden | 1 | 404 字节精确 | 零差异 |

合计 36/36 断言通过；其中 22 条为整体或字节级零差异，其余按下列映射归一化后断言。

## 2. 归一化映射（沿 J0-02/J2-01 规则）

| golden 字段 | 映射 | 说明 |
|---|---|---|
| `reset_token`（`<identity:16>`） | `^[A-Za-z0-9_-]{43}$` | token_urlsafe(32) 形态 |
| 申请 `id`（`<identity:15/17>`） | `^[0-9a-f]{32}$` | uuid4().hex |
| 动态 `created_at`（.000034/.000043/.000049 等） | `^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d{6})?\+00:00$` | Python `str(datetime)` 形态（空格分隔、零微秒无小数、+00:00）；oracle 微秒是顺序时钟产物，Java 侧为 TestClock 等价严格递增微秒 |
| approve `expires_at`（2026-10-24T04:30:00.000045+00:00） | `== pythonIso(clock.nowUtc()+30min)` | T 分隔（isoformat），30 分钟 TTL 精确 |
| 审计 `id`/`created_at` | `^aud_[0-9a-f]{12}$` / created_at 形态 | actor/action/targets 逐字段精确 |
| GET /users 全量列表 | golden 子树过滤（U2027 前缀） | 行为测试树（U2026）同库共存；golden 断言限定 oracle 子树，子树内字节可比 |
| `<synthetic-credential>` | 学号（初始密码）/固定测试密码 | 删除授权用 leader 初始密码 |

## 3. 已知差异与未逐字节断言分支（登记）

1. **json_invalid（语法错误）**：沿用 J2-01 已登记差异（Jackson 拒绝 Python 容忍的
   NaN/Infinity 字面量；AdminPydanticBody 无独立 Python 位置语义扫描器）。
   golden 无该类 observation。
2. **Pydantic 非 golden 分支**（int_parsing/int_from_float/int_type/bool_type/list_type/
   too_long/string_too_short 等）按 pydantic v2.10 惯例文案实现，测试覆盖
   （422 状态+结构），未与 oracle 逐字节比对——列入差分 suite J2-02 待跑项。
3. **时区渲染**：Python str(datetime) 输出依赖驱动会话时区（golden 为 UTC）；Java 统一
   以 UTC 渲染 `+00:00`，与 golden 一致；非 UTC 部署的 Python 行为不在本卡范围。
4. **端口时间格式转换**：PasswordResetApprovalPort 返回 isoformat（T 分隔，J2-01 语义），
   HTTP 列表渲染需 Python str(datetime)（空格分隔）；J2-02 控制器做了一次
   `OffsetDateTime→Instant→pythonStr` 转换。已在 handoff 提请 J2-01/集成者知悉
   （/api/auth 侧无该字段渲染，无既有影响）。

## 4. 与源一致、易误判为差异的行为（照源实现）

- `assign` 允许把**已归属**的纯 viewer 学生改派（docstring 说“未分配”，代码不限制）。
- 删除路径不触碰 rag_requests/rag_task_jobs（与停号路径清理范围不同）。
- 老师视角 users 列表把**本人行置于最前**（非 id 序）。
- 停号撤销的任务关联成绩按 `rag_task_jobs.job_id` 稳定身份关联（0014）。
