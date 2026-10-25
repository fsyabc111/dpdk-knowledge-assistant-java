# J2-01 三模式端点契约矩阵（实现步骤 1 登记）

来源（只读冻结快照）：`backend/app/api/auth.py`（HEAD 80b2913 基线）、`backend/app/core/auth.py`、
`backend/app/services/user_service.py`、`backend/app/schemas/auth.py`；
HTTP golden：`migration/fixtures/p0-v2/python-golden.json`（/api/auth 前缀 19 条 observation）。

## 0. 执行顺序总纲（决定状态码优先级）

- FastAPI 求值顺序：**依赖（require_auth）→ 请求体 Pydantic 校验 → 端点函数体**。
  即 logout/change-password：**401 → 422 → 404**（golden change-password::unauthorized
  空/不完整体得 401 "missing or malformed bearer token" 而非 422 为准）。
- login/password-reset-requests/password-reset 无认证依赖：体校验 422 先于函数体。
- login 函数体首行 `_require_account_mode()` → **404 先于限流/锁定/业务**。
- login 函数内顺序：每 IP 限流(429) → 按学号锁定检查(429，密码验证之前) → 凭据校验(401)。
- password-reset-requests 顺序：限流(429) → 学号格式(400) → 落库。
- password-reset 顺序：限流(429) → 一次性消费(400)。

## 1. 三模式 Bearer 解析矩阵（core/auth.py require_auth）

| 模式 | 无 Authorization / 非 Bearer / 空 token | 静态 token（api_tokens_json） | 表 token（api_tokens） | 结果 Principal |
|---|---|---|---|---|
| local | 免凭证直接通过（不解析头） | 不解析 | 不解析 | `("local", ["admin"], 0, false)` 唯一主体 |
| token | 401 `missing or malformed bearer token` + `WWW-Authenticate: Bearer` | 命中 → 静态 Principal（subject/roles/daily_quota 或缺省 llm_daily_quota_default） | 不查 | — |
| token | 有效 Bearer 但两处都不命中 | 未命中 | 不查 | 401 `invalid token` + `WWW-Authenticate: Bearer` |
| account | 同 token 模式缺失分支 | **优先**命中 → 静态 Principal（应急兜底） | 静态未命中时查库：有效且未撤销且账号 enabled 且未过期 → 四字段 Principal；daily_quota 列 NULL → llm_daily_quota_default | — |
| account | Bearer 大小写：scheme 大小写不敏感（`bearer`/`Bearer` 均可）；token 取第一个空格后的余部并 strip | | | |

- 静态 token 比较用 `secrets.compare_digest`（常量时间）；Java 用 `MessageDigest.isEqual`。
- `must_change_password` 仅信息标志，任何模式都不拦截请求（验收 E）。
- 静态 token 配置校验（config.py）：token ≥32 字符、roles 非空 ⊆ {viewer,operator,admin,leader}、
  daily_quota 非负整数；`AUTH_MODE=token` 且无静态 token → 启动失败。

## 2. /api/auth/* 端点 × 模式矩阵

| 端点 | 方法 | account 模式 | local / token 模式 |
|---|---|---|---|
| /api/auth/login | POST | 完整行为（见 §3） | 请求体不合法 → 422；体合法 → 404 `{"detail":"not found"}` |
| /api/auth/logout | POST | 401（认证依赖：无/无效 token）→ 404（模式检查）→ 撤销 → 204 | 401 先于 404；local 模式免凭证主体通过认证后仍 404（见注） |
| /api/auth/change-password | POST | 401（认证依赖）→ 422（体校验）→ 404 → 旧密错/静态兜底主体无账号行 → 400 `old password is incorrect`；新密规则不满足 → 400 `str(ValueError)`；成功 → 204（保留当前 token，撤销其他） | 401（无 token/坏 token）→（不达）422/404 |
| /api/auth/password-reset-requests | POST | 422 → 404 → 429 → 格式非法 400 `invalid username format` → 幂等落库 → 202 空体 | 422 → 404 |
| /api/auth/password-reset | POST | 422 → 404 → 429 → 无效/过期/已用/新密不合规 → 400 `invalid or expired reset token`（不区分）→ 204 | 422 → 404 |

注（logout/local 模式）：local 模式下 `require_auth` 直接返回 local 主体（免 token），函数体随后
`_require_account_mode()` → 404。即 local 模式 logout 带/不带 Authorization 都得 404（体恒合法）。
Java 侧：模式非 account 时 logout/change-password 仍先做解析（local 免凭证通过），再做 404 隐藏。

## 3. login 明细（account 模式）

1. 请求体：`LoginRequest{username: 1..64, password: 1..256}`；Pydantic v2 422 数组按字段序。
2. 每 IP 限流：`login:{client_ip}` 10/分钟滑动窗（进程内存、独立桶）。超限 →
   429 `login rate limit exceeded (10/minute)` + `Retry-After: max(1, int(60-(now-first))+1)`。
3. 按学号锁定：`check` 在密码验证之前；锁定中（存在/不存在的学号一视同仁）→
   429 `too many failed login attempts, try again later` + `Retry-After: max(1, int(until-now)+1)`。
   15 分钟滑窗 5 次失败 → 锁 15 分钟；成功登录清零。
4. 凭据校验：用户不存在 / 密码错 / 停用 → 同一 401 `{"detail":"invalid username or password"}`
   （无 WWW-Authenticate）；不存在/停用也执行一次 dummy bcrypt 比对（等价工作，防时序侧信道）。
5. 成功 → 200 `{"token","subject","roles","expires_at","must_change_password"}`（Pydantic 字段序即 JSON 键序）。
   token=`secrets.token_urlsafe(32)`（43 字符 base64url，仅存 sha256 hex）；expires_at=签发+AUTH_TOKEN_TTL_DAYS(默认7) 天的 isoformat（`+00:00`，微秒非零时 6 位小数）。

## 4. 凭据/token/重置令牌存储契约

- users.password_hash：bcrypt `$2b$<cost>$...`（Python bcrypt 读取兼容；Java spring-security-crypto BCrypt 同算法）。
- api_tokens：token_hash=sha256 hex（不存明文）；issued_at/expires_at timestamptz；revoked_at 置时撤销；
  撤销不可逆（重新启用账号不复活 token）。resolve_token 正向缓存 60s（单调时钟），任何撤销/改密/停号/
  改配额/改角色清空整缓存（吊销立即生效）。
- password_reset_requests：id=uuid4 hex；status pending→approved/rejected/used；reset_token_hash=sha256；
  reset_expires_at 审批时=now+30min；user_id 无 FK（PG 基线确认），匿名申请不校验账号存在（防枚举），
  同 user_id 仅一行 pending（幂等）。消费：approved + 未过期 + 用户存在 + 新密合规 → 改密+清 must_change+
  撤销全部 token+置 used。任何失败统一 400 不区分原因。

## 5. golden observation 对齐说明（19 条）

- 8 条 login success + change-password/logout/password-reset-requests/password-reset success +
  4 条 422 invalid-input + 2 条 401 unauthorized。
- golden 中 `token`/`expires_at`/`<synthetic-credential>` 已按 J0-02 归一化（随机值/时间）；
  Java 断言按同规则归一化后逐字段比对（见 evidence/golden-diff.md）。
- golden 响应头 content-security-policy/referrer-policy/x-content-type-options/x-frame-options
  来自 Python secure-headers 中间件，属平台装配层（J1-01 未迁），J2-01 不实现、登记为平台差异。

## 6. 已登记差异（Java 侧决定）

1. 重置令牌并发一次性：Python `_consume_reset_token_sync` 的 `UPDATE ... WHERE id=?` 无 status 守卫，
   READ COMMITTED 并发下存在双消费竞态；Java 按 `WHERE id=? AND status='approved'` 守卫 + rowcount
   判定保证严格一次（任务卡验收 D 明确要求）；顺序流（首次 204/二次 400）与 Python 一致。
2. secure-headers 中间件响应头：平台层未迁移，见 §5。
3. 非 JSON 对象体 / 深度非法 JSON 的 422 文案：按 pydantic v2 模板实现，golden 未覆盖，
   逐条登记于 golden-diff.md。
