# 身份、序列化与数据保真

本契约只冻结 Python 已有行为，不引入产品变更。字段的完整 Python 类型、缺省值、Field 长度/范围约束见 `static-inventory.json.models`；继承类先解析 `bases`。JSON 中省略字段与显式 `null` 按源代码保留：作业 PATCH 两者均传为 None，服务层只更新非 None 的字段，不能用 Java PATCH 默认行为改成“null 清空”（`backend/app/api/assignments.py:214`、`backend/app/services/assignment_service.py:173`）。

## 字节身份

| 身份 | 精确算法 | 源位置 |
|---|---|---|
| 请求输入摘要 | 对 question 和附件 filename 分别 Unicode NFC 后 Python `str.strip()`；数组 `[question, filename或null, raw-content-hash或null]`；`json.dumps(ensure_ascii=False,separators=(',',':'))`；UTF-8；SHA-256 lowercase hex。附件存在但缺 raw hash 必须 ValueError | `backend/app/services/rag_issue_service.py:36` |
| 附件原件摘要 | 对 upload 原始 bytes 做 SHA-256；发生在 UTF-8 replacement 解码之前；filename 独立进入请求 hash | `backend/app/api/chat.py:100` |
| 执行源码摘要 | 权威执行源 UTF-8 `errors='replace'` 编码后 SHA-256；上传原件解码文本 > 粘贴围栏代码 > 恢复寄存源 > 模型参数。此摘要不等同附件原件 hash | `backend/app/agent/tools.py:385`、`backend/app/api/assignments.py:363` |
| chat 请求键 | 有 UUID 时 `str(UUID)` 规范小写连字符；无时 `uuid4().hex`。task_id 独立 `uuid4().hex`。首次请求唯一 `(user_id,request_id) WHERE initial_request=1`，会话内唯一 `(session_id,request_id)` | `backend/app/services/rag_product_service.py:322`、`backend/app/storage/rag_schema.sql:84` |
| 讲解键 | session=`explain-` + submission_id；request=`UUIDv5(NAMESPACE_URL,'assignment-explain:'+submission_id)`；purpose=`explain`。迁移回填需同时核实固定键和成绩归属，不得凭 session 前缀猜测 | `backend/app/services/rag_product_service.py:287`、`backend/alembic/versions/0015_explain_purpose.py:14` |
| 登录/重置 token | token 为 `secrets.token_urlsafe(32)`，库只存 UTF-8 SHA-256 lowercase hex；普通 token 不是 JWT，令牌原文只在签发响应给出 | `backend/app/services/user_service.py:94`、`:718`、`:835` |
| 密码 | UTF-8 bytes → bcrypt，ASCII 保存完整 hash，cost=`bcrypt_rounds`。校验必须兼容复制基线 bcrypt 4.3.0 的 bytes 语义；不能擅自加入预 hash 或 Unicode 归一化 | `backend/app/services/user_service.py:105`、`:576` |
| 索引配置串 | **并非摘要 hash**：`json.dumps({'model':embedding_model_name,'api_base':embedding_api_base,'dimensions':1024,'chunker':'bounded-lines-v2'},sort_keys=True)`。默认 ensure_ascii=true、默认 `', '`/`': '` 空格；键序 api_base/chunker/dimensions/model。含中文或 URL 的字符串按 Python JSON 转义规则逐字保持；禁止 URL 规范化 | `backend/app/services/rag_index_service.py:35` |

Java `String.trim/strip` 与 Python strip 集合不同。J0-02 应固定 Python 3.11 `chr(cp).isspace()` 的 Unicode 空白集合，并包含 `U+001C..001F`、NBSP、窄不换行空格、组合重音、emoji、引号/反斜杠/换行、嵌入 NUL、空附件与无附件的差异。NFC 只用于输入身份；寄存和真正评测的源码不按此归一化。

P0 可消费辅助向量见 `migration/contracts/identity-vectors.json`，由独立的纯标准库参考计算形成，不能冒充已运行 Python 应用 oracle；最终以 J0-02 冻结结果作差分对照。

## 时间、空值与数字

- PostgreSQL 的 users/api_tokens/password_reset_requests、assistant_sessions/messages、legacy knowledge、assignments、grading_submissions/admin_audit 等时间列采用 TIMESTAMPTZ；rag_schema 和 upstream_usage_records 时间列为 TEXT，usage_counters.day 为 TEXT 日期。`now()/now_iso()` 生成 Python `datetime.now(timezone.utc).isoformat()`（有小数时最多6位，零微秒不强补零，`+00:00`）；session 的 now_iso 还有进程内微秒递增保护。但 PG 读回 TIMESTAMPTZ 后，部分响应调用 `str(datetime)`，可能出现空格日期分隔而非 `T`。持久化输入与每路由 wire 时间需分开按 fixture 固定，不能统一改成 `Z` 或数据库 local time。源：`storage/postgres_schema.sql`、`storage/rag_schema.sql`、`storage/usage_schema.sql`、`rag_index_service.py:20`、`session_service.py:24`、`api/chat.py:281`。
- 作业 deadline 输入接受 datetime；naive 输入按 UTC 处理，aware 转 UTC；截止判断按冻结时钟，不能用测试的真实时间碰运气。源：`assignment_service.py:31`—`:57`、`:525`。
- `score=int(round(float(score)))` 与 `earned=round(score*points/100,1)` 使用 Python round（ties-to-even 及二进制浮点现状），不能默认换 Java HALF_UP；得分、未交 `best_score/earned=null` 和交了零分 `0/0.0` 分开。源：`api/assignments.py:350`、`assignment_service.py:53`、`schemas/assignments.py:91`。
- upstream usage token 三列 `null` 是未知，0 是确实零调用；`known_entries/unknown_entries` 与合计同出。价格存整数微单位、币种分列，未配置价格留 null；不同币种不能合并。源：`storage/usage_schema.sql:1`、`schemas/usage.py:37`、`core/usage.py`。
- JSON 的 TEXT 与 JSONB 列不要统一改型；返回字段、nullable、`default_factory`、`exclude` 与路由手工字典在 fixture 中逐项比较。`ChatResponse` 默认可空字段照样出现，SSE done 仅排除 answer/citations；结果不同路径的附加 status/terminal 字段按现状保留。

## 认证和可见范围

- local：隐式 `subject=local,roles=[admin],daily_quota=0`，不要求 bearer；token：静态 JSON 使用 constant-time 比较；account：静态应急 token 优先，再查数据库 token/user。`must_change_password` 仅信息标志。源：`backend/app/core/auth.py:39`、`:76`。
- `require_auth` 的 401 必须带 `WWW-Authenticate: Bearer`；bearer scheme 大小写不敏感，首个空格后 token 做 Python strip。无权限管理面大量返回 404（隐藏存在性），不能一律映射 403。
- 管理账号：admin/leader；admin 仅自己的 students，leader/配置静态应急主体在对应入口不限；leader 不能经普通 API 授权创建。成绩读取还允许 operator；普通 viewer 不可见。assignment manager/grades/account 依赖须先于 body 校验生效；不能因 Spring 默认 body 验证返回 422/400 而泄露 404 隐藏端点。
- session/history 外部认证只允许自己的会话，缺失/越权同 404；local 对历史读取/删除保留既有宽口径。task 一律 owner 校验，缺失/他人同 404。source 回读只要求 require_auth，**没有会话归属检查**；知识来源全局可读，LI 与 legacy 引擎分支不能混淆（`api/sources.py:11`）。
- API 各模块“静态主体不限树”的判断并不完全共用，不能统一修饰成更宽权限。`rag.py:scope` 仅 leader 不限，admin 以 users.created_by 收窄；以该源事实和 fixtures 为准。
