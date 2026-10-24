# Java 迁移业务任务卡（规划草案）

## 使用规则与验收命令约定

- 本文是可分派草案，不代表 Java 架构、工期或实现已获用户确认；本轮只读源代码并编写规划，未运行测试。
- 源仓库：`/Volumes/study/dpdk-runtime/workspace/dpdk/ai_chat_assistant`；本轮核对 HEAD 为 `a47a1bc`，工作区包含未提交改动。
- 实施前由 J0-01 固定复制快照、文件清单和差异摘要；契约以该快照为准，不能把 HEAD 当作全部工作区内容。
- 目标仓库为 `/Volumes/study/code/new_project/dpdk-knowledge-assistant-java`；下文路径均相对该仓库。
- 标注“源”的入口一律相对新仓库中的冻结Python快照；原绝对目录仅作来源身份，不随原工作区后续变化同步。差异调查须明确授权并由主Agent决定是否升级基线。简写 `api/`、`services/`、`core/`、`agent/`、`storage/`、`schemas/` 均从 `backend/app/` 起，`tests/` 从 `backend/` 起。
- 拟采用模块化单体；`MAIN=backend-java/src/main/java/io/dpdk/assistant`，`TEST=backend-java/src/test/java/io/dpdk/assistant`。
- `FIX=backend-java/src/test/resources`；每卡仅拥有明列路径、自己的 `docs/java-migration/evidence/<ID>/` 和 `docs/java-migration/handoffs/<ID>.md`。
- 根 pom、构建插件、全局配置、共享测试设施、schema/migration、公共端口和跨包装配由集成者独占；消费者提交接口需求后等待 owner 实现。
- 现有 React 和独立 Python harness 保留；这些业务卡不修改前端、harness、源仓库或生产部署。
- 顺序：先读本卡入口，再读触发分支所需测试；不要把整个源仓库文档复制进每张卡。
- J0-01 提供 API/错误码/schema 基线，J0-02 提供 Python oracle 和合成数据；Java 测试使用独立专用 PG，拒绝 ambient/生产库。
- `mvnw`、测试类、oracle runner 在本轮尚未存在；以下是命令模板，不能据此宣称命令已经可执行或验收通过。
- J1-01 必须提供 Maven wrapper、单卡测试命令、PG 安全预检和失败即停止行为；替换框架时由集成者一次性更新全部模板。
- 每卡 Java 命令模板：`./backend-java/mvnw -f backend-java/pom.xml -Dtest=<本卡测试类> test`。
- 每卡差分命令模板：`<J0-02_ORACLE_RUNNER> --suite <ID> --baseline <PINNED_ORACLE> --candidate <ISOLATED_JAVA_URL> --output <EVIDENCE_DIR>`。
- 占位值只从已验收 handoff 取值；完整实参、工作目录和前置条件写入证据后才执行；不在命令行打印密码、token 或完整连接串。
- PG/模型/MCP 均由测试 profile 显式绑定；默认用受控 HTTP/MCP stub，真实 harness 场景只在隔离环境运行合成源码。
- 比较状态码、响应头、字段/空值、分页排序、身份隔离和数据库副作用；时间/随机 ID 只按 J0-02 已定义规则归一化。
- 每卡完成报告分别记录：实现状态、自动验证、场景验收、用户验收；未执行、阻塞、跳过不计为通过。
- 证据必须含源快照指纹、目标 commit、真实命令/退出码、PG 身份的非秘密摘要、断言结果、差异清单和未验证项。
- 回退默认是撤销本卡目标仓库提交、保留诊断证据；数据库变更只能由集成者演练回退，不能借回退清空生产数据。
- 阶段推进遵守总计划的授权门禁；上游未验收可以提交接口草案，不能以 mock 全绿代替真实依赖集成验收。

## J2-01：身份认证与凭据生命周期

- **目标**：保持 local/token/account 三种认证及 `/api/auth/*` 的现有 HTTP 行为，提供稳定 Principal/账号检查端口。
- **依赖**：J0-01、J0-02、J1-01；管理员改密、停号通过本卡公开的凭据失效端口协作。
- **入口**：源 `backend/app/api/auth.py`、`backend/app/core/auth.py`、`backend/app/services/user_service.py`、`backend/app/schemas/auth.py`。
- **按需阅读**：密码与 token 序列化看 `user_service.py`；格式/锁定规则看 `tests/test_account_auth.py`、`tests/test_auth.py`。
- **独占可写**：`MAIN/auth/**`、`TEST/auth/**`、`FIX/J2-01/**`；不编辑 `admin/**` 或共享安全配置。
- **实现步骤 1**：登记现有响应、Bearer 解析、静态应急 token 优先级和非 account 下 404 的契约矩阵。
- **实现步骤 2**：实现密码/token/重置令牌存储与校验；使用合成 Python bcrypt/hash fixture 验证 Java 读取兼容。
- **实现步骤 3**：实现登录、退出、自己改密、匿名申请重置和一次性令牌重置；管理员审批留在 J2-02。
- **实现步骤 4**：给集成者提交 Principal、启用账号事务检查、撤销/改密失效通知端口，覆盖调用失败行为。
- **非目标**：OAuth/SSO、JWT 替换、强制修改初始密码、改变角色制度、将进程内登录锁定改为分布式产品语义。
- **验收 A**：不存在账号、错误密码、停用账号返回相同 401；锁定检查对不存在账号同样生效。
- **验收 B**：每 IP 登录限额、账号 5 次失败锁定、重置独立限额，使用可控时钟检查窗口边界与 Retry-After。
- **验收 C**：退出立即失效；过期 token 失败；改密保留当前 token 的原有规则及其他 token 撤销与 oracle 一致。
- **验收 D**：重置申请 202 不枚举账号；重置令牌有效期/一次性消费在并发 PG 事务中成立。
- **验收 E**：static fallback、Bearer 大小写、未知/缺失 token、must_change_password 仅信息标志均与现行行为一致。
- **验收 F**：源码已有 bcrypt 假比较路径，Java 对不存在/停用账号也执行等价工作；不以简单墙钟耗时测试证明无侧信道。
- **命令**：单卡模板的 `<本卡测试类>` 替换为 `AuthContractTest,CredentialLifecycleTest,LoginThrottleTest`；差分 suite=`J2-01`。
- **执行前门禁**：J1-01 的专用 PG 预检和 J0-02 的 token/时间归一化已验收；合成凭据不得复用真实账号。
- **证据**：三模式端点矩阵、Python→Java hash 兼容结果、撤销前后请求、并发一次性消费结果和敏感信息扫描摘要。
- **完成条件**：所有认证分支有差分断言；已知差异由集成者分类，未解决权限/凭据差异阻止合并。
- **回退**：撤销 `auth` 本卡提交；测试凭据只清理专用 fixture；源认证服务、账号和登录令牌保持原状。

## J2-02：管理员、班级树与审计

- **目标**：迁移 `/api/admin/*` 用户管理、重置审批、下层删除、未分配学生分配及审计查询。
- **依赖**：J2-01、J0-01、J0-02、J1-01；任务取消端口由 J3-01 接入后再关闭停号集成验收项。
- **入口**：源 `backend/app/api/admin.py`、`backend/app/services/user_service.py`；请求响应模型沿 admin.py 的实际 import 读取。
- **按需阅读**：树隔离看 `tests/test_user_hierarchy.py`；删除事务看 `tests/test_user_delete.py`；凭据审批看 `tests/test_account_auth.py`。
- **独占可写**：`MAIN/admin/**`、`TEST/admin/**`、`FIX/J2-02/**`。
- **实现步骤 1**：建立 actor×target×operation 矩阵，并区分 account 管理员与静态应急 token。
- **实现步骤 2**：迁移批量创建、配额/资料修改、改密审批、启停及分配；通过 auth 端口触发 token 立即失效。
- **实现步骤 3**：用单事务实现删除、相关数据清理和审计；保留成绩 hash/结果与被删除学号。
- **实现步骤 4**：定义班级范围查询端口供作业/用量/问题工作台消费，避免各模块复制权限 SQL。
- **非目标**：新增组织层级、角色继承、管理员互管规则、对静态应急 token 扩权、批量导入 UI。
- **验收 A**：leader 管全部，admin 只管理自己创建学生；他班和未分配学生不可被 admin 越权读写。
- **验收 B**：API 不能授予 leader；admin 不能创建老师/operator；不能停用自己或更改自己角色。
- **验收 C**：转班目标必须是启用中的 teacher/leader；批量输入的重复、无效 ID 和部分失败响应保持现有契约。
- **验收 D**：批量删除要求操作者自己的正确密码；静态 token 无账户时不能借同名标识通过删除授权。
- **验收 E**：删除老师后学生归未分配；删除用户清 token/会话/配额/重置请求；成绩保留且审计按树隔离。
- **验收 F**：停号与成绩落库/任务受理交错时按账号行事务顺序裁决；停号触发 J3-01 取消与临时输入清理。
- **验收 G**：事务中途失败时删除和审计一起回滚；通过真实 PG 故障注入验证，不能只 mock repository。
- **命令**：单卡测试类=`AdminTreeContractTest,AdminDeletionTransactionTest,PasswordResetApprovalTest`；差分 suite=`J2-02`。
- **执行前门禁**：auth 端口已验收；停号跨模块场景需 J3-01 真实实现，不能用空取消函数标记完成。
- **证据**：权限矩阵、删除前后各关联表摘要、审计 actor/scope、事务故障与并发时间线。
- **完成条件**：权限差分与 PG 原子性通过，停号集成项独立列明；任何越权/漏删 token 阻止合并。
- **回退**：撤销本卡代码；只恢复隔离测试 PG 的 fixture；不执行生产账号删除或恢复脚本。

## J2-03：作业定义、成绩汇总与 CSV

- **目标**：迁移作业协议清单、CRUD、撤回/恢复、可见性、成绩汇总和 CSV；与正式提交写入解耦。
- **依赖**：J2-01、J2-02、J1-02、J0-01、J0-02；用合成已落库成绩先验收读取，再与 J2-04 集成。
- **入口**：源 `backend/app/api/assignments.py` 的 protocols/CRUD/grades/export；`backend/app/services/assignment_service.py`。
- **按需阅读**：`backend/app/schemas/assignments.py`、`backend/tests/test_assignments_api.py`；CSV 消费形态再读现有 React 调用。
- **独占可写**：`MAIN/assignment/catalog/**`、`MAIN/assignment/report/**` 及 `TEST` 同名路径、`FIX/J2-03/**`。
- **实现步骤 1**：迁移作业/协议项更新事务及权限；scope 由服务端从角色产生，客户端输入不能覆盖。
- **实现步骤 2**：提供受理快照和当前作业项查询端口，明确 J2-04 的受理分值与汇总动态分值两种口径。
- **实现步骤 3**：实现最高分、按分值折算、提交次数、最近提交时间、当前班级归属及 CSV 字节输出。
- **实现步骤 4**：接入 J1-02 的 list_protocols；错误响应沿 oracle，不把 harness 故障展示为有效空协议列表。
- **非目标**：改变已有计分算法、按新方案补未提交学生、增加 XLSX、正式提交/讲解路由、harness 协议实现。
- **验收 A**：非 account 整组 404；operator 只读成绩/导出；仅作者或 leader 可修改与撤回。
- **验收 B**：leader 发布 all、admin 发布 class；学生列表和详情按所属班级、撤回状态保持现行可见性。
- **验收 C**：作业项替换事务原子；截止时刻和时区、重复协议、分值非法、缺失字段按 oracle 返回。
- **验收 D**：同协议多次取最高；聊天成绩 assignment_id=NULL 不计入；不同 assignment_id 不串分。
- **验收 E**：汇总按当前作业项动态折算；正式提交响应使用受理分值，两者改变作业后可有预期差异。
- **验收 F**：老师仅本班；leader/operator 按班分组；账号删除后成绩保留、学号保留并归未分配。
- **验收 G**：CSV BOM、UTF-8、表头/空行/排序、空分值、Content-Disposition 与 oracle 相符。
- **命令**：单卡测试类=`AssignmentCatalogContractTest,AssignmentReportContractTest,CsvExportContractTest`；差分 suite=`J2-03`。
- **执行前门禁**：J0-02 固定时间和 CSV 换行归一化规则，避免把真实内容差异全量忽略。
- **证据**：多班级角色结果、受理/汇总双口径样例、CSV 原始字节摘要及解析结果、PG 更新回滚记录。
- **完成条件**：各角色 CRUD/成绩/导出和协议故障覆盖；J2-04 集成前用 fixture 通过不能宣称正式提交链路通过。
- **回退**：撤销本卡实现；保留 schema 与合成成绩；不改线上作业定义或重新计分。

## J2-04：正式提交、MCP、评测配额与成绩

- **目标**：迁移 `/api/assignments/{id}/submit` 的 JSON/multipart 受理、MCP 评测、受理快照和成绩持久化。
- **依赖**：J2-03、J2-05、J2-01、J2-02、J1-02、J1-01、J0-02；grading 配额由 J2-05 提供，不依赖 J3-01。
- **入口**：源 `api/assignments.py` 的 `_parse_submit_request`/`submit_assignment`；`services/harness_grading.py`、`grading_service.py`。
- **按需阅读**：`core/rate_limit.py`、`agent/mcp_client.py`、`tests/test_assignments_api.py`、`tests/test_grading_api.py`。
- **独占可写**：`MAIN/assignment/submission/**`、`TEST/assignment/submission/**`、`FIX/J2-04/**`；不编辑 MCP/usage 基础包。
- **实现步骤 1**：保留请求校验和错误码顺序，读取作业可见性/协议/截止资格并构造受理快照。
- **实现步骤 2**：调用共享 quota 与 MCP 端口；共享 kind=grading 和角色豁免，MCP 已提交调用不盲重试。
- **实现步骤 3**：使用账号启用检查和单事务写成绩；只存 hash/结果及受理依据，返回 submission_id。
- **实现步骤 4**：暴露成绩查询端口供 J2-03/J3-04 和聊天成绩读取使用，成绩写入可共享但来源/assignment_id 必须显式。
- **非目标**：宿主机执行学生代码、重写 harness、自动重评、增加正式提交任务化、给现有接口承诺未实现的幂等键。
- **基线区别**：当前正式提交是同步 MCP→落库；`rag_task_jobs` 恢复能力属于产品问答路径，不能直接写成正式提交已有能力。
- **验收 A**：JSON 粘贴与 .c/.so 上传、大小/扩展名/编码边界、未知协议、过期作业保持 HTTP 状态和响应结构。
- **验收 B**：与聊天评测共用 grading 配额；豁免角色一致；超限、超时、结构化失败分别校验 429/504/502 等映射。
- **验收 C**：大小/资格拒绝不调用 MCP；已发起后响应丢失不得重复提交；调用计数与配额副作用按 oracle 固定。
- **验收 D**：评测中途修改/撤回/截止按受理快照完成；响应使用受理分值，汇总按当前分值；快照留存可追溯。
- **验收 E**：评测中停号，成绩写入与停号事务竞争按源码规则裁决；被停号用户不能在检查后绕过落库门禁。
- **验收 F**：成绩、日志、会话无源码；核对 Java/Python 对编码替换后文本的 SHA-256 一致，勿把上传字节 hash 偷换为成绩 hash。
- **验收 G**：合成低分/全通过/畸形 MCP payload；保持现行 score 舍入及异常语义，发现源缺陷登记差异不私改契约。
- **命令**：单卡测试类=`AssignmentSubmitContractTest,GradingQuotaIntegrationTest,GradingPersistenceRaceTest`；差分 suite=`J2-04`。
- **执行前门禁**：stub 可记录请求次数且不打印源码；真实 harness 另用隔离合成样本和明确网络边界。
- **证据**：每分支 MCP 调用数/配额变化/成绩行数、受理快照、PG 竞争顺序和敏感信息扫描结果。
- **完成条件**：提交→结果→成绩→汇总闭环通过；未知执行结果明确暴露，不伪造“恰好一次执行”。
- **回退**：撤销本卡适配；保留测试诊断和 hash/结果；不重发未知状态评测或删除生产成绩。

## J2-05：会话、用量、来源、模板与成绩读取

- **目标**：迁移会话生命周期/历史、release 绑定、配额与实际用量、来源回读、模板接口和通用成绩只读视图。
- **依赖**：J2-01、J2-02、J1-03、J1-04、J0-02；本卡先提供会话/配额/计量基础给 J2-04 和 J3-01。
- **入口**：源 `api/chat.py` 的 sessions/internal；`api/usage.py`、`sources.py`、`templates.py`、`grading.py`。
- **按需阅读**：`services/session_service.py`、`template_service.py`、`grading_service.py`；`tests/test_session_history.py`、`test_session_delete.py`。
- **统计/绑定分支阅读**：`core/rate_limit.py`、`tests/test_usage.py`、`test_usage_cost_api.py`、`test_usage_records_postgres.py`、`test_session_release.py`。
- **用量写入分支阅读**：`core/usage.py`、`services/usage_service.py`、`storage/usage_schema.sql`；该数据面与每日配额计数分别验收。
- **独占可写**：`MAIN/session/**`、`MAIN/usage/**`、`MAIN/source/**`、`MAIN/template/**`、`MAIN/grading/query/**`。
- **测试路径**：`TEST` 的同名子路径、`FIX/J2-05/**`；含会话生命周期、release 绑定、配额/用量仓储及事务参与实现。
- **实现步骤 1**：实现会话创建/删除/维护、release 首次绑定 CAS 及历史/分页/引用；公开参与调用方事务的端口。
- **实现步骤 2**：实现配额 counters 和实际调用 ledger 的写入/查询；J3-01 负责单事务受理编排，本卡负责其数据操作。
- **实现步骤 3**：来源 li_ 与历史 chunk 分支都可读；模板端口沿现有请求契约，通用成绩只读视图保持班级隔离。
- **非目标**：重新生成历史答案、重建索引、调整价格、补算 unknown 成本、改 UI、把模板生成变为自动评测。
- **验收 A**：会话自己可见，跨账号与不存在统一 404；排序/最近 N 条/truncated/取消消息及 metadata 字段一致。
- **验收 B**：删除会话保留成绩、session_id 置 NULL；首次 release 绑定并发取 CAS 胜者，跨请求/重启不漂移，删除重建重新绑定。
- **验收 C**：li_ 和历史非 li_ 引用都可回读，缺失来源 404；历史引用顺序和编号保持，不按当前检索结果重排。
- **验收 D**：usage/me、subjects、cost 的角色/班级范围、日期窗口、request_id 过滤、空账本与 unknown 一致。
- **验收 E**：未定价模型保持未定价；非 token 操作单列，未知用量不显示成零，不泄露他班主体。
- **验收 F**：模板字段、默认值、文件内容与非法输入契约；通用成绩与作业汇总不混用过滤逻辑。
- **验收 G**：internal session-storage/purge 用维护 key 且不进 OpenAPI；dry-run/apply 分离；配额原子增量、失败回滚、ledger unknown 可验证。
- **命令**：单卡测试类=`SessionLifecycleTest,SessionQueryContractTest,QuotaTransactionTest,UsageQueryContractTest,SourceTemplateContractTest,GradingQueryContractTest`；差分 suite=`J2-05`。
- **执行前门禁**：公共端口由 J0-01 设计、J1-01 集成者落地；J1-04 仅 synthetic task/session context，不代替本卡持久能力。
- **证据**：历史消息/引用 fixture、各角色查询差异、成本未知字段、维护请求鉴权结果及删除前后摘要。
- **完成条件**：端点权限/持久化/配额并发及事务参与通过，供 J2-04 使用；活动任务取消组合验收在 J3-01 记录，不阻塞基础交付。
- **回退**：撤销本卡代码，由集成者协调依赖卡回退；保留合成 fixture 与报告，不清理源历史数据或更改生产保留策略。

## J3-04：已有成绩的按需讲解

- **目标**：迁移 `/api/assignments/{aid}/submissions/{sid}/explain`，把已落库成绩转为可恢复的产品问答任务。
- **依赖**：J2-03、J2-04、J2-05、J3-01、J3-02、J3-03；任务身份、配额及结果重放端口必须先验收。
- **入口**：源 `api/assignments.py` 的 explain 路由、`services/rag_product_service.py` 的 explain 身份/context 相关函数。
- **按需阅读**：`tests/test_s3d_explain.py`、`agent/reference.py`、任务 purpose 验证与恢复逻辑；不把 prompt 当权限证明。
- **独占可写**：`MAIN/assignment/explanation/**`、`TEST/assignment/explanation/**`、`FIX/J3-04/**`。
- **实现步骤 1**：校验本人提交、assignment 关联及当前可见性；截止后仍允许讲解，隐藏成绩身份存在性。
- **实现步骤 2**：使用确定性的 session_id/request_id 和 purpose=explain；预建会话的顺序按 oracle 固定。
- **实现步骤 3**：通过 task/chat 公开端口受理，返回 started/attached/replayed；成绩 context 来自数据库可信记录。
- **实现步骤 4**：恢复时重建 context，接入参考答案披露策略；调用全通过分支与失败分支的既有教学行为。
- **非目标**：重新调用 grade_plugin、修改成绩、将讲解视为新正式提交、改变参考答案策略、复制任务引擎。
- **验收 A**：不存在/非本人/作业不匹配/不可见返回同口径 404；非 account 404，装配失败 503。
- **验收 B**：截止后可讲解；标题修改后的重复请求仍回放同一任务，不因展示文本变更新建任务。
- **验收 C**：并发首次调用只创建一个任务且计费配额一次；pending 附着、终态重放不重复模型执行。
- **验收 D**：harness grade_plugin 调用次数为零；讲解前后成绩表内容与行数一致。
- **验收 E**：后端重启由可信成绩重建 context；伪造请求/会话前缀不能冒充 explain purpose 绕过身份检查。
- **验收 F**：全通过不注入参考答案；失败结果按现行规则注入；上下文与日志没有学生源码。
- **验收 G**：断线继续、查询/续传/取消与普通产品任务契约同形；停号后不能继续回放结果。
- **命令**：单卡测试类=`SubmissionExplainContractTest,ExplainReplayIntegrationTest,ExplainRecoveryIntegrationTest`；差分 suite=`J3-04`。
- **执行前门禁**：J3-01 的并发/恢复实测和 J3-03 的任务入口装配已通过；模型 stub 返回可追溯确定性结果。
- **证据**：submission→session/request/task/purpose 映射、重复调用计数、配额差、成绩不变摘要、重启与重放事件轨迹。
- **完成条件**：已有成绩→启动→重连→终态回放闭环通过，且重评零调用断言与权限反例全部成立。
- **回退**：撤销 explain 适配，保留已经生成的隔离任务证据；不回滚或覆盖原成绩。

## J4-02：问题闭环与共享知识

- **目标**：迁移反馈、问题工作台、认领/编辑/合并、原题验证、共享知识发布/修订/撤回及历史修订查询。
- **依赖**：J2-01、J2-02、J3-03、J0-02；J4-01索引接口已冻结时可用fixture并行实现，知识发布实际生效验收须等J4-01完成；使用请求证据、当前 release、检索/生成/judge 和留存维护端口。
- **入口**：源 `backend/app/api/rag.py`、`services/rag_issue_service.py` 的 feedback/event/mark_verified/maintain。
- **按需阅读**：`storage/rag_schema.sql`、`tests/test_rag_workflow.py`、`test_rag_product.py`；UI 契约看 `frontend/src/RagWorkspace.jsx`。
- **独占可写**：`MAIN/knowledge/workflow/**`、`TEST/knowledge/workflow/**`、`FIX/J4-02/**`；索引和任务请求存储由其 owner 修改。
- **实现步骤 1**：实现本人消息反馈、teacher 树隔离问题查询和状态操作，事件与状态在同一事务中记录。
- **实现步骤 2**：实现合并约束与基于 updated_at/revision 的条件更新，不把并发覆盖写变为最后写入胜出。
- **实现步骤 3**：实现共享确认、作者/leader 修改权限、修订历史和 pending 索引状态；通过 J4-01 后续发布生效。
- **实现步骤 4**：原题验证使用当前 release，受限流/配额/admission/总超时控制；显式 judge 不受在线默认关闭影响。
- **非目标**：自动发布未审核内容、扩充教学材料、修改题库、即时重建索引、跨班问题可见性放宽、改变 180 天保留规则。
- **验收 A**：反馈仅本人消息可写；重复反馈复用问题并重开状态/清旧验证；跨班问题不存在式 404。
- **验收 B**：仅 account admin/leader 进入工作台；认领 owner、note 脱敏、事件顺序和分页与 oracle 一致。
- **验收 C**：自合并、合并到已合并记录、存在合并来源再转合并均按当前 400/409 规则拒绝。
- **验收 D**：resolved 仅在当前 release 已通过原题验证后允许；新 release 后旧验证不能自动解锁 resolved。
- **验收 E**：验证期间新反馈/编辑到达，旧验证条件更新失败且新状态不丢失；PG 双连接交错验证此不变式。
- **验收 F**：published 必须 shared_confirmed；非作者/非 leader 不可修订；revision 冲突 409，历史修订完整。
- **验收 G**：共享知识写入返回 pending，查询可见与进入 active release 是两件事；发布/撤回经 J4-01 验证检索可见性。
- **验收 H**：正文/反馈/事件脱敏和 180 天留存清理范围正确；请求/任务临时源码的 24h 清理由原 owner 负责。
- **命令**：单卡测试类=`KnowledgeWorkflowContractTest,IssueConcurrencyIntegrationTest,KnowledgePublicationIntegrationTest`；差分 suite=`J4-02`。
- **执行前门禁**：必须有两个独立 PG 连接的并发 fixture 和可切换 release；原题验证 stub 仍走真实业务事务。
- **证据**：角色矩阵、冲突响应与最终行状态、revision/event 历史、发布前后 release/检索差异、脱敏/留存检查。
- **完成条件**：反馈→认领→人工发布→索引生效→原题验证→解决闭环通过；并发反馈和权限反例无回退。
- **回退**：撤销本卡 API/工作流提交；知识撤回/索引回退由 J4-01 独立执行，保留修订历史和问题证据。
