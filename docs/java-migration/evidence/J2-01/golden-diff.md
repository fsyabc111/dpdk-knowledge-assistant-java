# J2-01 golden 差分与归一化映射

来源：`migration/fixtures/p0-v2/python-golden.json`（baseline p0-v2）按 route_id 过滤
`/api/auth*` 共 19 条；冻结副本 `backend-java/src/test/resources/migration/J2-01/golden-auth.json`
（提取脚本同源逐字段保留，见其 provenance 节）。
断言实现：`AuthContractTest`（goldenLoginSuccessObservations / goldenInvalidInputObservations /
goldenUnauthorizedObservations / goldenChangePasswordSuccess / goldenLogoutSuccess /
goldenPasswordResetRequestAndConfirm）。

## 逐 observation 结果

| case_id | Java 断言结果 | 归一化映射 |
|---|---|---|
| login::success-leader/teacher_a/teacher_b/student_a/student_b/free/delete/disabled（8 条） | 200；subject/roles/must_change_password 逐字段相等 | `token` `<identity:N>` → 断言 43 字符 base64url（token_urlsafe(32) 同构）；`expires_at` 的 oracle 顺序微秒 → 受控时钟等价严格递增微秒 + 格式 `isoformat+00:00`（零微秒无小数、非零 6 位）；`<synthetic-credential>` → 初始密码=学号（同一语义） |
| change-password::success | 204 空体 | golden 请求体口令已归一化；Java 用等价合成流（登录→改密）断言状态码/头/空体 |
| logout::success | 204 空体 | 同上 |
| password-reset-requests::success / success-second | 202 空体（sha256=空摘要等价断言 contentAsByteArray 为空） | 无 |
| password-reset::success | 204 空体（审批经 J2-01 审批端口签发真实一次性令牌后消费） | 无 |
| change-password::unauthorized / logout::unauthorized | 401 字节精确 `{"detail":"missing or malformed bearer token"}` + WWW-Authenticate: Bearer | 无 |
| login / change-password / password-reset / password-reset-requests::invalid-input（4 条） | 422；detail 数组与 golden **JSON 值级相等**（对象键序不敏感；golden 文件为 sort_keys 转储，wire 键序为 type/loc/msg/input[/ctx]，见 pydantic-probe.md） | 无 |

差异计数：**0 条状态码/字段差异**；键序差异 1 类（见上，已按 JSON 值级比较规则处理）。

## 头部差异（逐项登记）

- golden 响应头含 `content-security-policy/referrer-policy/x-content-type-options/x-frame-options`
  —— 来自 Python secure-headers 中间件，属平台装配层（J1-01 未迁移）。J2-01 不实现，
  集成者归入平台差异账本。
- Java 422/401/400 响应 `Content-Type: application/json`（与 FastAPI 一致）；202/204 空体
  无 Content-Type（与 Starlette Response(202)/204 一致）。

## 已知未覆盖/近似项

1. NaN/Infinity JSON 字面量：Python json 接受后走字段校验，Java Jackson 拒绝 → json_invalid。
   （探针证实 Python 行为；极端输入，非 golden 覆盖面。）
2. json_invalid 的 pos 对含空白尾随的 Extra data：按 Python 语义（值后首个非空白字符）
   实现，探针验证过 `'{"a":1} x'` 形；更复杂嵌套文档的 pos 未逐一冻结（J0-02 未覆盖）。
3. secure-headers 中间件头（见上）。
4. 静态 token 配置校验失败文案：与 config.py 同文案（AuthServerSettings.of），未在
   golden 覆盖内（启动期行为）。
