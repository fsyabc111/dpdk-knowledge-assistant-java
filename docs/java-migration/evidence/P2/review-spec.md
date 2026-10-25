# P2 规格独立审查（实现忠实度抽查）

- 审查员：P2 规格独立审查 Agent（独立上下文，不参与实现；只读，本文件为唯一交付）。
- 基线：commit `c7cbab3`（分支 codex/p1-p2-longrun）。日期：2026-10-25。
- 方法：每审查点抽样对照 Python 冻结副本与 Java 实现，逐条给 `文件:行` 证据；只判定实现与冻结契约的一致性与登记完整性，不修复问题。工作树中 `migration/contracts/interface-changes.md` 有一处未提交改动，已计入审查（见差异清单 S1）。

## 1. 认证求值序与锁定

| 契约点 | Python 证据 | Java 证据 | 结论 |
|---|---|---|---|
| 登录序：体422→模式404→每IP 429→锁定429→密码验证→401 | `backend/app/api/auth.py:127-142`（`_require_account_mode`:129 → `_login_rate_limiter.check`:131 → `_login_lockout.check`:133 → verify 失败 401:135-142） | `backend-java/.../auth/AuthController.java:71-94`（parseStringObject:74 → requireAccountMode:75 → limiter:76 → lockout:78 → verifyCredentials:79-82） | 一致 |
| 401→422→404（带 body 认证端点） | `api/auth.py:157-183`（require_auth 依赖先于体校验，FastAPI 依赖序） | `AuthController.java:116-137`（resolve:121 → parse:122 → requireAccountMode:123，注释明示）；golden `change-password::unauthorized` 空体 401 PASS（evidence/P2-regression/logs/golden-diff.json） | 一致（golden 实证） |
| 常量：每IP登录10/分、重置5/分、5次失败/15分钟窗锁定15分钟 | `api/auth.py:34-42` | `auth/AuthServerSettings.java:35-39` | 一致 |
| 锁定语义：滑窗计数、≥5 锁定并清桶、成功清零、Retry-After=max(1,int(until-now)+1) | `api/auth.py:97-111`（record_failure:97-106、record_success:108-111、check:81-95） | `auth/LoginLockout.java:31-64`（同一公式:38；清桶:55-56；清零:61-64） | 一致 |
| 每 IP 滑窗限流：60s 窗、429 detail=`{kind} rate limit exceeded ({limit}/minute)`、Retry-After=max(1,int(60-(now-first))+1) | `api/auth.py:45-66` | `auth/SlidingWindowRateLimiter.java:32-47`（同一公式:40-43） | 一致 |
| 静态 token 优先、常量时间比较；未命中 account 模式查 api_tokens | `backend/app/core/auth.py:77-91`（compare_digest:78；account 分支:80-89） | `auth/AuthPrincipalResolver.java:42-57`（constantTimeEquals:47；accountMode 分支:51-56） | 一致 |
| 401 detail 区分 missing/invalid + WWW-Authenticate: Bearer | `core/auth.py:50-55,74-76,90-91` | `AuthPrincipalResolver.java:43-44,57-63` | 一致 |
| bcrypt 假比较等价工作（不存在/停用仍一次 checkpw，dummy 缓存一次） | `services/user_service.py:79-85,560-572`（种子 `kimi-assistant-dummy`:82） | `auth/PasswordHasher.java:16,37-53`（同种子:16；惰性缓存:41-53）、`auth/UserAuthRepository.java:117-131`（dummyCheck:131） | 一致 |
| local 模式隐式 admin | `core/auth.py:70-71` | `AuthPrincipalResolver.java:39-41` | 一致 |

## 2. 树规则与删除事务

| 契约点 | Python 证据 | Java 证据 | 结论 |
|---|---|---|---|
| can_manage：leader 全管；admin 仅 created_by=自己且纯 viewer 非空 | `services/user_service.py:166-180` | `admin/AdminTreeRules.java:56-68` | 一致 |
| leader-like：leader 角色或静态 token 主体；scope_creator | `api/admin.py:44-54` | `AdminTreeRules.java:38-50` | 一致 |
| 不可自停（先于可管判定）、不可自改角色 | `api/admin.py:184-192`（status:185-189 先判 self）、`api/admin.py:212-215`（PATCH self roles） | `admin/AdminController.java:266-269`（self 判定:269 先于 getManageableTarget）、`AdminController.java:305-311` | 一致（含顺序） |
| 提权防线：leader 仅 CLI 引导、老师只能建 viewer | `api/admin.py:89-101` | `AdminController.java:127-131` | 一致 |
| 删除：密码校验失败→403；自己/不存在/不可管进 skipped；审计同事务；删除 leader 判定只认 actor.roles | `user_service.py:385-428`（verify:388→PermissionError；skipped:394-410；audit INSERT:413-419 同 conn；leader 判定:408） | `admin/AdminUserRepository.java:395-452`（verifyCredentials:396→ActorPasswordException；skipped:403-424；audit:429-442 在 inTx 内；`AdminTreeRules.canDelete`:70-74）；403 渲染 `AdminController.java:341` 附近（ActorPasswordException→403） | 一致 |
| 级联顺序（FK 约束）：api_tokens→会话三表显式级联→grading_submissions.session_id=NULL（成绩保留脱钩）→password_reset_requests→usage_counters→名下学生 created_by=NULL→users 行 | `user_service.py:430-475` | `AdminUserRepository.java:459-541`（逐条同序，session_id=NULL:506-513） | 一致 |
| 提交后清 token 缓存（事务外幂等） | `user_service.py:420-421`（clear_token_cache） | `AdminUserRepository.java:445-450`（invalidation 端口） | 一致 |

## 3. 作业与 CSV

| 契约点 | Python 证据 | Java 证据 | 结论 |
|---|---|---|---|
| 门禁：非 account 模式整组 404；管理端点 404 先于体校验（依赖形式） | `api/assignments.py:73-105`（manager_principal/grades_principal/account_principal；注释:91-92） | `assignment/catalog/AssignmentCatalogController.java:38-46`（注释与 requireManager）、`assignment/submission/AssignmentSubmissionController.java:54-63,81-91` | 一致 |
| scope 服务端产生（leader→all，admin→class），客户端不可指定 | `services/assignment_service.py:130-131` | `AssignmentCatalogController.java:43`（注释口径；落库在 catalog service/repo） | 一致 |
| 受理快照：单条 SQL（LEFT JOIN items）、status=active、scope!='all' 需 created_by=学生归属、points 可空 | `assignment_service.py:352-380` | `assignment/catalog/AssignmentCatalogRepository.java:299-333`（同 SQL 形状:302-307；active:325；scope:328） | 一致 |
| 受理序：快照404→截止400→协议400（points None） | `api/assignments.py:326-333` | `assignment/submission/AssignmentSubmissionService.java:71-81` | 一致 |
| CSV 字节规则：UTF-8 BOM、CRLF、QUOTE_MINIMAL（含 `\r`/`\n` 触发）、组间空行=单 CRLF、未分配组排最后、老师单组文件名含 `_csv_safe(subject)` | `api/assignments.py:519-579`（BOM:575、空行:567-569、未分配最后:564-566、文件名:557,572、`_csv_safe`:519-520） | `assignment/report/PythonCsv.java:18-57`（CRLF:25、空行:29-31、escape:41-57、BOM:34-38）、`GradeReportService.java:88-136`（分组/排序/文件名:104-134、csvSafe:84-86）；行为证据 BOM+CRLF 实测（evidence/P2-regression/behavior-evidence.md 场景5） | 一致（字节级） |
| 树隔离 `_scope_user_ids`：leader/operator/静态 token 不限；admin 仅自班 | `api/assignments.py:108-116` | `GradeReportService.java:68-77` | 一致 |

## 4. 正式提交

| 契约点 | Python 证据 | Java 证据 | 结论 |
|---|---|---|---|
| 解析序：multipart 422/400/413 先于快照；认证 401→模式404 先于解析 | `api/assignments.py:271-307`（JSON 422:276-284、protocol 422:289-290、缺文件 422:292-293、后缀 400:295-299、超限 413:301-306）；依赖序:321 | `assignment/submission/AssignmentSubmissionController.java:54-63`（authenticate:57→mode:58→parse:59）+`SubmitRequestParser` | 一致 |
| 错误码顺序：快照404→截止400→协议400→评测 413/429/504/默认502→落库403（停号） | `api/assignments.py:326-348,375-376`；`_GRADING_ERROR_STATUS`:310-314 | `AssignmentSubmissionService.java:71-89`（404:72-73、截止:74-75、协议:77-81、映射:42-45,86-89）；403 经 `SubmissionGradeWriter`（AccountWriteGuard+INSERT 单事务，`SubmissionGradeWriter.java:17-23,41-53`） | 一致 |
| grade_code 三步：大小校验→豁免角色{operator,admin,leader}配额（kind=grading）→超时 504；统一 {"ok":...} 不抛穿 | `services/harness_grading.py:24-73`（豁免常量:17） | `assignment/submission/GradingInvoker.java:35,61-101` | 一致 |
| UNKNOWN 不重发（已提交后失败绝不重发，不伪称未提交） | `harness_grading.py:51-59`（wait_for 单次，无重试） | `GradingInvoker.java:85-94`（注释:91-93）、`harness/StreamableHttpHarnessClient.java:41-43,143`（进入提交阶段后一律 UNKNOWN，绝不重发） | 一致（不重发；状态码差异见 D1） |
| PythonUtf8 与 CPython 解码一致性 | `api/assignments.py:307,361`（decode(errors="replace") 后 sha256） | `assignment/submission/PythonUtf8.java`（CPython utf8_decode 字节机复刻：ED 代理区逐字节拒绝:16-24）；登记差异 U+FFFD ED 代理区 1↔3 + 冻结 hash 电池（migration/contracts/interface-changes.md J2-04 节〔工作树未提交〕；evidence/J2-04/utf8-parity.md） | 一致（登记差异已锁） |
| payload 数值化畸形按 Python 语义→500 | `api/assignments.py:350-353`（int(round(float()))） | `AssignmentSubmissionService.java:155-303`（HALF_EVEN:155-159、int 截断:162-187、list(dict) 键序:193-215） | 一致（500 渲染缺口见 D2） |

## 5. 会话/配额/用量

| 契约点 | Python 证据 | Java 证据 | 结论 |
|---|---|---|---|
| quota=0 不记日计数（三入口一致） | `core/rate_limit.py:56-58`（check_and_count_daily）、`rate_limit.py:92-95`（count_kind_in_conn `if quota:`） | `usage/UsageCounterStore.java:49-52,62-65,80-83`（三入口 quota==0 早退） | 一致 |
| check_and_count_daily 先递增后检查、失败计数保留（独立事务提交） | `rate_limit.py:56-71`（increment:59 → enforce:60；独立 get_conn） | `UsageCounterStore.java:79-93`（注释明示「源现状：失败计数保留」）；行为实证：第 21 次 429、counter=22（behavior-evidence.md 场景4） | 一致 |
| 未知≠0：token 缺失一律 null；zero_call（cache_hit/replay/local）不算 unknown；totals 带 `*_entries` 分母 | `core/usage.py:12-16,39,59-89,250,296-333` | `usage/UsageCore.java:19-21,37-38,60-105,245-253,277-293` | 一致 |
| 成本写入时定价、未配价不出价、盖 price_version | `core/usage.py:162-181,353-395` | `UsageCore.java:169-188,313-333` | 一致 |
| release 首次绑定 CAS（条件 UPDATE ... IS NULL 后重读） | `services/session_service.py:72-99`（UPDATE...WHERE rag_release_id IS NULL:91-92） | `session/SessionService.java:320-357`（同条件 UPDATE:350-352） | 一致 |
| 维护端点：未配置 503 / 缺失或不符 403（恒时比较）；不进 schema | `api/chat.py:283-293`（compare_digest:291） | `session/SessionController.java:216-226`（MessageDigest.isEqual:223-224；503:220-222） | 一致 |
| usage 面板：非管理 404、只统计 kind='chat'、account 树隔离、sort(-today, subject) | `api/usage.py:31-48,98-150`（404:101-102；隔离:144-149；排序:149） | `usage/UsageQueryController.java:37-41,163,174,211-215,250-261`、`UsageCounterStore.java:116-139`（kind='chat':120-122） | 一致 |

## 6. P2-regression golden-diff 复核

来源：`docs/java-migration/evidence/P2-regression/logs/golden-diff.json`（186 cases）与 `golden-diff.md`。

**STATE-DIFF 全部 6 条复核**（golden-diff.json 逐条）：admin/users（8→7 行）、password-reset-requests（0→4）、assignments（0→4）、grading/submissions（0→23）、grading/summary（0 组→1/3 组）、usage/subjects（8→7）。均为列表/聚合端点，键集/类型/格式类一致，仅行数与账号集随环境状态不同（golden 当时空表/8 账号，候选库为回归后状态/7 账号）。归为 STATE-DIFF（非契约差异）合理，登记充分。

**PASS 抽 6 条复核**：
1. `POST /api/auth/change-password::unauthorized`：401 体逐字节 + WWW-Authenticate=Bearer，空体得 401 而非 422——实证本审查点 1 的依赖序；
2. `POST /api/auth/login::invalid-input`：422 detail 数组值级相等（pydantic 等价）；
3. `POST /api/auth/password-reset-requests::success`：202 零字节 sha256 对 golden 摘要；
4. `GET /api/assignments/protocols::success`：经 fake harness 与 golden 值级相等；
5. `POST /api/plugin-template::success`：确定性模板文本结构一致；
6. `GET /api/assignments/{assignment_id}::success`：等价流 shape 相等（id/时间戳/学号归一为格式类后逐字段相等）。
归一化映射（identity 双射/`<TS>`/token→`<ID>`/学号→`<USER_ID>`/日期→`<DATE>`）承 J2-01 golden-diff.md 已登记口径（evidence/J2-01/golden-diff.md:13-15），映射合理、无过度归一（状态码、detail 文案、字节摘要均未归一化掉）。

**SKIP 87 条**：理由分布实测为 RAG(J3) 29、SSE/模型 live/任务/维护路由 22、状态面成功流（场景2留证）10、golden 引导账号登录等价流（场景1留证）8、作业创建/CSV（场景3/5留证）6、chat 主路由未装配实测 404 共 6、知识库种子 1、评测成功流（场景4留证）1，另有 **4 条登记为「未分类案例后缀」占位理由**（见 S3）。整体 SKIP 理由成立（均有场景证据指针或范围归属），但 4 条占位理由未落到实质。

**平台/环境差异登记**（secure-headers 缺失、探针路由、FastAPI 运行时路由、CORS 403 vs 400、OPTIONS 405 vs 404、chat/rag 未装配）：逐条如实登记，与 golden-diff.md 一致，不阻塞。

## 差异清单

未发现 [阻断]（偏离冻结契约且未登记）项。以下为登记/收口类发现：

- **D1〔已登记差异，核对通过〕** 「已提交后挂起」Python 504 / Java 502：Python `asyncio.wait_for` 覆盖整个 call_tool（`harness_grading.py:52-59`），提交后挂起超时→504；Java 提交后失败/超时→UNKNOWN→502（`StreamableHttpHarnessClient.java:41-43`）。两者均不重发、配额账面一致。登记于 interface-changes.md J2-04 节。
- **D2〔建议·收口项〕** 500 渲染缺口：`platform/errors/ApiExceptionHandler.java:11-12,19-26` 仅映射 ApiDetailException；payload 畸形→`IllegalStateException` 走 Boot 默认 500 错误体，Python 为 FastAPI 默认 500 `Internal Server Error`。登记写明「P2 收口前由集成者补；未补前不宣称该分支兼容」——本审查时（基线 c7cbab3）仍未补，P2 阶段报告须核实该项关闭，未关闭前不得在验收材料宣称畸形 payload 分支兼容。
- **S1〔建议·流程〕** interface-changes.md 的 J2-04 登记节（D1/D2 所在）在基线 c7cbab3 **未提交**（仅存在于工作树，`git show c7cbab3:...` 无该节）。「先登记接口变更，再让依赖任务继续」要求登记先落库；请集成者在 P2 收口提交内补上并说明。
- **S2〔建议·收口项〕** `GradingInvoker.java:126-132` 自登记「pythonStr 容器值按 Jackson 渲染为近似」：error/detail 为容器时与 Python `str(dict)` 文案不同。golden 未覆盖该输入域；保持账面即可，勿宣称该分支逐字节兼容。
- **S3〔建议·证据质量〕** golden-diff.json 中 4 条 SKIP 的 detail 为「未分类案例后缀：'success-after-request'/'success-second'/'success-nonempty-manager'/'success-nonempty-student'」，而 golden-diff.md 称「SKIP 逐条登记，理由分五类」。这 4 条实质属状态面成功流（与已登记 STATE-DIFF 同族、场景 2/3 已留等价证据），建议在 P2 阶段报告中补一句实质归类，消除报告与机器可读明细的口径差。

## 总结论

**通过（0 阻断）。** 六个抽查点共 30 余项契约语义逐条对照，Java 实现与 Python 冻结基线一致；求值序、锁定/限流常量与公式、删除级联顺序与同事务审计、CSV 字节规则、错误码顺序、UNKNOWN 不重发、quota=0 不记/失败计数保留/未知≠0、CAS release 绑定、维护端点 503/403 均有双侧 file:line 证据。登记差异（D1、PythonUtf8 U+FFFD、S2）与平台差异均已在账。遗留三件收口事项（D2 500 渲染、S1 登记未提交、S3 四条 SKIP 理由占位）不构成放行阻塞，但 P2 阶段报告与用户验收前应逐项核销；D2 未关闭前不得宣称畸形 payload 500 分支兼容。
