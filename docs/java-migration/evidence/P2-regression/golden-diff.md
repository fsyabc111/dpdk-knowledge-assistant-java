# P2 golden 重放差分报告（IP-3）

来源：`migration/fixtures/p0-v2/python-golden.json`（baseline p0-v2，186 observations）。
重放对象：真实运行的 Java 应用（Spring Boot 8090，AUTH_MODE=account，候选库 p1_java_candidate，
fake MCP harness 等价替身提供 grade_plugin/list_protocols）。环境对齐：`LLM_DAILY_QUOTA_DEFAULT=10000`
（与 golden 环境一致）；种子账号 daily_quota=10000、admin.created_by=leader（对位 golden 引导树）。
重放脚本：`scripts/run_08_golden.py`；逐 case 明细：`logs/08-golden-replay.log`；机器可读：`logs/golden-diff.json`。

## 比对口径

- **PASS**：状态码相等 + 响应体 JSON 值级相等（键序不敏感）；401 另比 `WWW-Authenticate: Bearer`；
  202 空体以 golden 摘要 {length:0, sha256} 对真实字节做 sha256 比对。
- **PASS（等价流）**：golden 成功面在候选库合成账号上重放等价流（如 change-password 204 空体、
  新建等参作业后学生视角详情、删除自有一次性会话 204、leader PATCH daily_quota=5），
  断言状态码 + 体 shape（id/时间戳/用户号归一为格式类后逐字段相等）。
- **STATE-DIFF（登记，非契约差异）**：行的格式类（键集/类型/格式）一致，仅行数或账号集取值不同
  （golden 场景当时为空表/8 账号，本环境为回归后状态/7 账号）。
- **SKIP（逐条登记）**：场景 1-6 已留等价证据的成功流、SSE/模型 live/任务路由、RAG（J3 未实施）、
  chat 主路由未装配、Python 平台自身路由。
- 归一化承 J2-01 golden-diff.md：identity 双射（`<identity:N>`、asg_/uuid/hex id → `<ID>`）、
  顺序微秒时间戳 → `<TS>`（不比具体值）、token → `<ID>`、学号 → `<USER_ID>`、日期 → `<DATE>`。

## 结果汇总（SUMMARY）

| verdict | 数量 | 说明 |
|---|---|---|
| PASS | 78 | 状态无关面（401/404/422/202 空体）逐字节/值级一致；等价成功流 shape 一致 |
| STATE-DIFF | 6 | 格式类一致、行数/账号集不同（登记）：admin/users、password-reset-requests、assignments、grading/submissions、grading/summary、usage/subjects |
| REPLAYED | 15 | Python 平台面（/api/health、/docs、/openapi.json、/redoc、/metrics 及 HEAD 变体）与 CORS 预检形态，见下"平台差异" |
| SKIP | 87 | 逐条登记，理由分五类（见 log） |
| **契约 DIFF** | **0** | 未发现状态码或响应体契约差异 |

## 分类附注（P2 收口，集成者补；规格审查 S3 关闭）

原脚本未映射的 4 条 SKIP 属**状态依赖用例**（golden 用例顺序前置），等价成功流已在场景 1-6 留证：

| route::case | 分类 | 等价证据 |
|---|---|---|
| GET /api/admin/password-reset-requests::success-after-request | 依赖前置申请 POST（golden 顺序） | 场景2 改密审批流（申请→审批→列表断言） |
| GET /api/admin/password-reset-requests::success-second | 同会话第二次查询（状态序） | 场景2 审批列表两次断言 |
| GET /api/assignments::success-nonempty-manager | 需预置作业行的角色列表 | 场景3 admin 视角列表可见性 |
| GET /api/assignments::success-nonempty-student | 同上（学生视角） | 场景3 学生视角可见性/撤回断言 |

## 登记的平台/环境差异（不阻塞）

1. **secure-headers 中间件头缺失**（J2-01 已登记，P2 live 确认）：golden 401/422 响应含
   `content-security-policy/referrer-policy/x-content-type-options/x-frame-options`，Java 无。
   `WWW-Authenticate: Bearer` 行为一致（401 面）。
2. **探针路由**：Python `GET/HEAD /api/health`、`GET /api/ready` → Java 侧 404；Java 探针为
   `/actuator/health`（200 UP，body 形态不同：groups+status）。
3. **FastAPI 运行时路由**：`/docs`、`/docs/oauth2-redirect`、`/openapi.json`、`/redoc`、`/metrics`
   及 HEAD 变体 → Java 404（范围外，未迁移）。
4. **CORS 拒绝语义**：非允许来源预检，Python 400 `Invalid CORS request`；Spring CorsFilter 403
   （同为拒绝，状态码不同）。
5. **普通 OPTIONS**（无 Origin）：Python 对 `/api/health` 405 `{"detail":"Method Not Allowed"}`；
   Java 404（路由未映射时由 Boot 默认错误体承载）。
6. **chat 主路由未装配**：`POST /api/chat` 各 case 实测 404（chat 编排属 J3+/J5 范围）。
7. **RAG 路由未实施**：`/api/rag/**` 属 J3。

## 覆盖亮点（状态无关契约面全部一致）

- 全部 in-scope 路由的 401（body 逐字节 + WWW-Authenticate 头）：auth/admin/assignments/
  submissions/grades/export/usage/grading/sessions/sources/plugin-template/withdraw/reactivate。
- 全部 422 invalid-input 的 pydantic 等价 detail 数组值级相等（含 query 参数 days=0 的
  greater_than_equal ctx、嵌套 items loc）。
- 404 invalid-resource / foreign-tree / viewer-hidden 语义（含 `session not found` 与 `not found`
  的分 Detail 文案区分）。
- `GET /api/assignments/protocols` 经 fake harness list_protocols 与 golden 值级相等
  （{"ok":true,"protocols":[{"name":"dns","cases":5,"categories":{"basic":5}}]}）。
- `POST /api/plugin-template` 结构与确定性文案一致（等价流 PASS）。
- `POST /api/auth/password-reset-requests` 202 零字节体（sha256 与 golden 摘要一致）。
