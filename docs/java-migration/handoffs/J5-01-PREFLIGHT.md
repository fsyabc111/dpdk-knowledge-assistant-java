# J5-01 只读预检交接（U12；P5 未放行）

- owner/工作树：主 Agent 的 acceptance_matrix 子 Agent；共享集成工作区，只读分析；未建立 worker 树。
- 源基线：Python `80b29135faddd16d5e39fdbf0633291ea6a20489`；预检 HEAD `5a6ea656c2bc0e5b0dc8e30f94236dcaa898e259`（运行中集成可能继续推进）。
- 当前授权：只读 J5-01 准备；P4 最终 verify/门禁由主 Agent 完成后才进入 P5。唯一写入为本文件；未改业务、共享状态、脚本、黄金；未提交/推送。
- fixture：p0-v2，黄金 SHA256 `8d0658661fedb31c6a0690ea97cb3167534504049c65861b3c67e6691337de14`（本轮读取后重新计算一致）；COMP-003 单独批准安全目标见 `migration/fixtures/p0-approved-differences/comp003.json`。
- 四层状态：只读预检完成；P5 实现/自动验证/浏览器场景/用户验收均未执行。本文件的历史结果不能作为当前通过。

## 1. 结论与最小依赖

1. R 段 `PASS 72 / ALLOWED-DIFF 25 / SKIP 89` 是 d9364ed 附近的历史 HTTP 薄重放，不能直接升级为 P5 全量严格差分。当前 `run_08_golden.py` 未消费冻结业务初态/逐 case delta/终态、11 PG 专例、14上游脚本或 semantic_vectors；也未调用 `migration.oracle.compare`。
2. P3/P4 现已存在真实 chat/tasks/explain/RAG/operations 与 HTTP 测试入口；必须待 P4 门禁绑定最终 SHA 后重新采集候选。原 SKIP 理由中的“依赖 live 模型”已不适用本批次：可在外部模型/embedding/MCP边界使用固定替身，业务必须走真实 Java。
3. 首要工作是新建 `acceptance/contract/` 候选适配器，复用冻结 `migration.oracle.verify`，建立与 p0-v2 同形的 Java 原始 trace；不要把当前 `golden-diff.json`（只含 verdict/detail）当候选 trace，也不要把 Python 输出填入 Java 未采集字段。
4. 最小依赖：P4 工程门禁及最终源码/产物 SHA；DB owner 提供可验证身份的独立空库/迁移入口；集成者单写共享装配与前端/README；本机固定模型+1024维 embedding+MCP脚本；确定性时钟/实体和合成seed注入方案。没有上述条件只能记录阻塞，不以删除字段、宽泛 ALLOW 或旧日志替代。
5. `acceptance/` 当前不存在；本轮不建立目录或执行脚本。P5放行后再由主 Agent 建独立 worker 树，J5-01可写仅 `acceptance/contract/**`、`acceptance/ui/**`、`docs/java-migration/evidence/J5-01/**` 及本卡交接；实现缺陷交回原卡 owner。

## 2. 已有入口与实际能力

| 入口 | 静态确认能力 | P5 使用限制 |
|---|---|---|
| `docs/java-migration/evidence/P2-regression/scripts/run_00_setup.sh` | 显式PG环境；维护连接先只读核验 user/data_directory/库占用，再 CREATE/COMMENT；记录600身份文件 | 会写库；必须在P5统一窗口且复核真实目标后运行。data-dir检查是字符串前缀，不能由旧报告替代现场核验 |
| 同目录 `seed_accounts.sh` / `collect_state.py` | seed U900…01..05/07，运行时bcrypt；API建U6/U7，收角色token；节拍防登录限流 | 非完整P0种子；teacher_b=teacher_a，viewer=free；学生A/B同属一个admin，U7树外而非真实第二班；无operator；不能证明两班权限 |
| 同目录 `run_01_auth.sh` … `run_06_usage.sh` | 认证/管理/作业/正式提交/CSV/用量场景 | 是R段等价流；不是186 case逐值/副作用完整对照；run_07文件不存在 |
| 同目录 `run_08_golden.py` | 读186 observation；部分HTTP请求/等价流；显式DECLARATIONS；状态/JSON类型/键集/列表序列；`--selftest`17个合成检查 | 仅JSON请求（非dict input直接无body），不支持完整multipart/SSE/字节输出采集；只对unauthorized额外查2头；不采业务delta。会重建等价session、改密码/配额再SQL复位，并非只读命令 |
| `migration/oracle/verify.py` / `compare.py` | manifest全部摘要；容器schema/时间/业务delta链/SSE状态机；字段类型+全trace实体命名双射；精确值比较 | 真正全量差分入口；需要完整Java trace。默认不处理R脚本的宽泛ALLOW；COMP-003需独立目标断言/独立差分记录，不能改黄金 |
| `migration/oracle/run.py` / `runtime.py` / `route_cases.py` | Python本地服务调用与PG业务编排、确定时钟/种子；每请求settled checkpoint | `--database p0_candidate_oracle`仍运行Python，不是Java候选！run会TRUNCATE合成库、写旧证据路径；不得在P5直接运行冒充Java采集 |
| `migration/oracle/business_state.py` / `fixtures/p0-v2/business-projection.json` | 25表白名单字段投影、顺序及removed/added多重集、最终状态 | 复用语义和投影，不导入Python服务去读错库；Java collector需绑定自己的专属连接，清晰处理实际schema映射 |
| `migration/oracle/test_review_fixes.py` | 纯内存/CLI业务值、关联、源码hash、SSE等反例 | 可作为比较器独立无库验证入口；本轮未运行 |
| `migration/oracle/verify_v2_regressions.py` | 真实CLI40类既有变异/边界线索，冻结覆盖保护 | 当前硬读`.migration-artifacts/p0/candidate-trace-p0-v2.json`历史候选并写p0产物；P5新入口必须显式指向本轮Java候选，不得依赖历史文件 |
| Java `platform/wiring/ProductApplicationHttpTest` | 真应用HTTP、无上游503、固定本地chat、相同request重放/SSE、Node原client、清输入 | 源码存在；local模式/no_evidence分支，不代替account两班/全引用/全导航；本轮未运行 |
| Java `platform/wiring/P4PlatformHttpTest` | 环境变量真实装配；health/ready、缺release成员/物理节点/指纹、404/405/500安全头 | 源码存在；可使旧平台ALLOW进入重新严格验证；本轮未运行，不预判P4最终结果 |
| `frontend/src/api/client.test.js` / `taskRecovery.test.js` / `grades.test.js` | mock fetch的客户端SSE分片/身份/上传/恢复、成绩格式逻辑 | `frontend/package.json`仅vitest；当前无浏览器E2E入口，不能代替DOM、下载文件、CORS和控制台 |

## 3. 66 路由完整登记与 R 历史状态

下表66行直接来自冻结 `route-case-map.json`；R列读取版本化 `logs/golden-diff.json`，不是本轮执行。P=PASS，A=ALLOWED-DIFF，S=SKIP；数字是该route的历史case数。每行的成功/无权/无效输入case ID与预期原样在冻结map及golden，不复制修改。`重试`列是P0 map的历史状态，不能当Java当前通过。

画像决定该行适用角色/模式、输入、SSE与副作用（下一节）；每行都必须有当前构建的实际结果、差分、DB断言、允许依据或明确不适用理由。

| 方法与路径 | 画像 | R历史P/A/S | P0重试/并发 |
|---|---|---|---|
| `DELETE /api/chat/sessions/{session_id}` | SESSION | 3/0/0 | 未执行 |
| `GET /api/admin/audit-log` | ADMIN | 2/0/0 | 明确不适用 |
| `GET /api/admin/password-reset-requests` | ADMIN | 1/0/3 | 明确不适用 |
| `GET /api/admin/users` | ADMIN | 2/1/0 | 明确不适用 |
| `GET /api/assignments` | ASSIGN | 1/3/0 | 明确不适用 |
| `GET /api/assignments/protocols` | ASSIGN | 2/0/0 | 明确不适用 |
| `GET /api/assignments/{assignment_id}` | ASSIGN | 4/0/0 | 明确不适用 |
| `GET /api/assignments/{assignment_id}/export` | ASSIGN | 2/0/1 | 明确不适用 |
| `GET /api/assignments/{assignment_id}/grades` | ASSIGN | 2/0/1 | 明确不适用 |
| `GET /api/chat/sessions` | SESSION | 2/1/0 | 明确不适用 |
| `GET /api/chat/sessions/{session_id}` | SESSION | 3/1/0 | 明确不适用 |
| `GET /api/grading/submissions` | GRADE | 2/1/0 | 明确不适用 |
| `GET /api/grading/summary` | GRADE | 1/1/0 | 明确不适用 |
| `GET /api/health` | PLATFORM | 0/1/0 | 明确不适用 |
| `GET /api/internal/session-storage` | MAINT | 0/0/2 | 明确不适用 |
| `GET /api/rag/issues` | RAG | 0/0/3 | 明确不适用 |
| `GET /api/rag/issues/{issue_id}` | RAG | 0/0/4 | 明确不适用 |
| `GET /api/rag/knowledge` | RAG | 0/0/2 | 明确不适用 |
| `GET /api/rag/knowledge/{knowledge_id}/revisions` | RAG | 0/0/3 | 明确不适用 |
| `GET /api/ready` | PLATFORM | 0/1/0 | 明确不适用 |
| `GET /api/sources/{chunk_id}` | SOURCE | 2/0/1 | 明确不适用 |
| `GET /api/tasks/active` | TASK | 0/0/3 | 明确不适用 |
| `GET /api/tasks/{task_id}` | TASK | 0/0/4 | 明确不适用 |
| `GET /api/tasks/{task_id}/events` | TASK | 0/0/3 | 通过 |
| `GET /api/usage/cost` | USAGE | 3/0/0 | 明确不适用 |
| `GET /api/usage/me` | USAGE | 1/1/0 | 明确不适用 |
| `GET /api/usage/subjects` | USAGE | 1/1/0 | 明确不适用 |
| `GET /docs` | PLATFORM | 0/1/0 | 明确不适用 |
| `GET /docs/oauth2-redirect` | PLATFORM | 0/1/0 | 明确不适用 |
| `GET /metrics` | PLATFORM | 0/1/0 | 明确不适用 |
| `GET /openapi.json` | PLATFORM | 0/1/0 | 明确不适用 |
| `GET /redoc` | PLATFORM | 0/1/0 | 明确不适用 |
| `HEAD /docs` | PLATFORM | 0/1/0 | 明确不适用 |
| `HEAD /docs/oauth2-redirect` | PLATFORM | 0/1/0 | 明确不适用 |
| `HEAD /openapi.json` | PLATFORM | 0/1/0 | 明确不适用 |
| `HEAD /redoc` | PLATFORM | 0/1/0 | 明确不适用 |
| `PATCH /api/admin/users/{user_id}` | ADMIN | 3/0/0 | 未执行 |
| `PATCH /api/assignments/{assignment_id}` | ASSIGN | 2/0/1 | 未执行 |
| `PATCH /api/rag/issues/{issue_id}` | RAG | 0/0/3 | 未执行 |
| `PATCH /api/rag/knowledge/{knowledge_id}` | RAG | 0/0/3 | 未执行 |
| `POST /api/admin/password-reset-requests/{request_id}/approve` | ADMIN | 2/0/1 | 未执行 |
| `POST /api/admin/password-reset-requests/{request_id}/reject` | ADMIN | 2/0/1 | 未执行 |
| `POST /api/admin/users` | ADMIN | 2/0/1 | 未执行 |
| `POST /api/admin/users/assign` | ADMIN | 2/0/1 | 未执行 |
| `POST /api/admin/users/delete` | ADMIN | 2/0/1 | 未执行 |
| `POST /api/admin/users/{user_id}/reset-password` | ADMIN | 2/0/1 | 未执行 |
| `POST /api/admin/users/{user_id}/set-password` | ADMIN | 2/0/1 | 未执行 |
| `POST /api/admin/users/{user_id}/status` | ADMIN | 2/0/1 | 未执行 |
| `POST /api/assignments` | ASSIGN | 2/0/1 | 未执行 |
| `POST /api/assignments/{assignment_id}/reactivate` | ASSIGN | 2/0/1 | 未执行 |
| `POST /api/assignments/{assignment_id}/submissions/{submission_id}/explain` | EXPLAIN | 0/0/3 | 未执行 |
| `POST /api/assignments/{assignment_id}/submit` | SUBMIT | 2/0/1 | 未执行 |
| `POST /api/assignments/{assignment_id}/withdraw` | ASSIGN | 2/0/1 | 未执行 |
| `POST /api/auth/change-password` | AUTH | 2/0/1 | 未执行 |
| `POST /api/auth/login` | AUTH | 1/0/8 | 未执行 |
| `POST /api/auth/logout` | AUTH | 1/0/1 | 未执行 |
| `POST /api/auth/password-reset` | AUTH | 1/0/1 | 未执行 |
| `POST /api/auth/password-reset-requests` | AUTH | 1/0/2 | 未执行 |
| `POST /api/chat` | CHAT | 0/0/6 | 通过 |
| `POST /api/chat/stream` | CHAT | 0/0/3 | 通过 |
| `POST /api/internal/purge-sessions` | MAINT | 0/0/3 | 未执行 |
| `POST /api/plugin-template` | TEMPLATE | 3/0/0 | 未执行 |
| `POST /api/rag/feedback` | RAG | 0/0/3 | 未执行 |
| `POST /api/rag/issues/{issue_id}/verify` | RAG | 0/0/3 | 未执行 |
| `POST /api/rag/knowledge` | RAG | 0/0/3 | 未执行 |
| `POST /api/tasks/{task_id}/cancel` | TASK | 0/0/3 | 通过 |

另有3个不在66清单中的协议route（4 observations），必须保留：`HEAD /api/health`（R:A1），`OPTIONS /api/health`（R:A1），`OPTIONS /api/chat`（R:A2：cors-200/cors-400）。总计69 route_id、186 observations，不能漏4条或误称69业务路由。

## 4. 角色/模式、输入、SSE、跨表语义扩展矩阵

通用每路由维度：成功；无/坏/过期/撤销token；有效但无权角色；未知对象与树外对象同隐匿语义；必填/类型/null/缺省/空白/越界/未知query；固定身份跨HTTP/SSE/DB关联；适用并发重试。不适用必须引用冻结行为，不能按全笛卡尔积要求本不存在的成功。

| 画像 | 模式×主体及输入 | 必要状态/副作用与补充分支 |
|---|---|---|
| AUTH | local/token/account分别启动；匿名与各角色；账号密码JSON、改密/申请/一次性令牌；同IP节拍/限流 | users、api_tokens、reset_requests；等价匿名申请；失效/停号/过期、已用重置token；管理改密全撤token。local/token下account专用功能按冻结门控断言 |
| ADMIN | account的leader、adminA/B、viewer、operator；静态应急token在account模式另测；批量ids/角色/归属/配额/密码 | users/token/reset/audit及账号级联；老师只管自班纯viewer；leader全树；静态subject特殊leader-like与删除密码失败；自停/自改角色/授leader拒绝；停号pending取消+清输入+不落未完成成绩；删除归未分配/历史成绩脱钩；双连接重复审批/停号竞态 |
| ASSIGN | account：leader/admin管理；viewer本人作业；operator只读成绩导出；local/token模式门控；JSON作业items/deadline/points，query与CSV | assignments/items、grades动态最高分/分组/三态；撤回/恢复/改分/改名/截止；scope服务端决定；树外404；导出BOM/CRLF/中文/排序/空值；发放后修改与受理快照；两班空态+非空态 |
| SUBMIT | account本人；JSON粘贴与multipart .c/.so，UTF8坏字节/Unicode名、恰边界与+1；空/垃圾/多JSON/合法非对象 | 真实SubmissionService→fake MCP；扣次/成绩事务、code_hash/结果false/0/unknown；重复网络submit保持“无业务幂等键”边界，不宣传全提交去重；失败零成绩与已扣次按原语义；截止/配额/协议 |
| EXPLAIN | account学生本人该作业grade；树外/他人成绩/未知id；改名/截止后仍复用受理身份 | chat task purpose=explain、固定explain session、来源、一次扣chat；不重MCP、不变原grade/正式配额；刷新/终态重放、停用/显式取消 |
| SESSION | local/token/account绑定subject；query limit、未知与他人session；删除活动session | sessions/messages/citations/release；取消占位可回看不进模型记忆；首次release并发赢家；删除后404、不残活动任务/输入；终态回读保持first/replay批准边界 |
| CHAT | local/token/account各适用角色；JSON/multipart，附件原字节、NFC、执行源上传>粘贴>恢复>参数；request/session身份 | request/message/session/task/usage/job/grade/citation同事务与一次扣次；same身份attached/replayed、不同input409；真实Agent五工具；503零受理；超过4来源/同文件多片段/跨工具轮索引、旧release；正文必须提交终态后才发 |
| TASK | local/token/account各subject；active query、task path、cancel；owner与树外/未知 | start/status/tool_start/tool_end/sources/delta/done；原LF协议、UTF8任意分块、EOF不flush；pending/terminal:false不是完成；error合法终止、done后无事件；断订阅不取消；终态events重放上游/扣次为0；显式cancel幂等且迟到结果不覆盖 |
| RAG | feedback本人message；工作台leader/adminA/B，viewer/operator无权；JSON status/claim/merge/knowledge/revision/shared_confirmed | rag_issues/events/knowledge/revisions及原task关联；班级隔离/分页/反馈累积；发布与索引生效分开；revision CAS、并发新反馈/修订不能被旧验证覆盖；verify使用本地模型/evidence judge、重验证后resolve |
| SOURCE | local/token/account可见历史/当前source；legacy chunk与li node、未知/无权 | response内容与source_path/section；旧release保留、同文件多片段6条有序不去重；来源1..N跨轮身份与DB citation rank完全一致 |
| GRADE | account本人、admin自班、leader全树、operator只读；query协议/limit | grading_submissions与作业独立；正式作业与聊天练习分开；NULL/缺省、已删学生归属、highest评分，不能用行数掩盖score错写 |
| USAGE | 本人/主体/cost权限，local/token/account；days边界/定额 | usage_counters + upstream_usage_records，业务次数与上游次数独立；unknown费用/tokens不变0；重放不重复计；禁止用BODY豁免整表 |
| TEMPLATE | 各认证模式/角色、protocol缺失/不存在/大小写 | 无评测或扣次；学习骨架与完整参考边界；无效输入文本/类型精确 |
| MAINT | maintenance key正确/缺失/错误；Bearer不可替代；query dry_run/retention_days | 存储报告与purge；留存/终态/TTL、回读不泄源码；真实业务连接由DB owner绑定；dry-run零写，清理只受控库 |
| PLATFORM | 公开health/ready/metrics/docs/HEAD/OPTIONS；Origin允许5174/恶意origin | status/body/media/Allow/CSP/安全头/CORS；ready检验有效release完整性；缺上游/缺索引失败；404/405/500保密；metrics不经nginx暴露属于部署验收 |

冻结P0 `route-case-map`是account基本面，request.actor集中leader/student_a/匿名；不含operator完整模式角色矩阵。P5不能用“186已映射”推导三模式四角色覆盖。额外并发/恢复HTTPcase放新场景文件；若要扩展黄金，向J0-02 owner申请新fixture版本，当前p0-v2保持只读。

25表投影必须完整：`users/api_tokens/password_reset_requests/admin_audit_log`；`assistant_sessions/messages/citations`；`usage_counters/upstream_usage_records`；`assignments/assignment_items/grading_submissions`；`rag_requests/rag_task_inputs/rag_task_jobs`；`rag_issues/rag_issue_events/rag_knowledge/rag_knowledge_revisions`；`rag_releases/rag_index_state/rag_release_nodes/knowledge_documents/knowledge_chunks/li_nodes`。表名以frozen projection为准，29 schema表不等于29业务投影。

每case采集实际初态→before计数→各表removed/added有序多重集→after计数；settled checkpoint须等待本次已受理任务收口而保持原始受理回执。198步链=186HTTP+1 auth_state_step+11专例；最后终态必须可由全部delta重建。白名单指纹不替代score/roles/tree/input_hash/job_id/submission_done等实体值；不保留源码或token/hash。

11专例必须映射真实Java入口：`pg-admission-race`、`pg-admission-rollback`、`pg-finish-rollback`、`pg-finish-once`、`pg-cancel-idempotent`、`pg-grade-job-dedup`、`pg-recovery-claim`、`pg-input-ttl`、`pg-vector-rrf-mmr`、`citation-six-same-file`、`pg-cleanup-terminal`。PG并发需多连接，恢复双进程归J5-02统一窗口；不能以mock或单连接替代。14上游脚本：LLM multi_chunk/bad_json/disconnect/429/timeout/usage_missing/usage_duplicate；MCP connect_failure/initialize_failure/submitted_response_lost/finished/running/unknown/restart_clears_ledger。COMP-003使Java unknown/查询失败不重投，与Python历史黄金的行为差异必须逐case隔离说明，不能重写Python。

## 5. 72/25/89 的升级清单与比较器可信性

- **72 PASS**：只可作为已知候选请求线索；当前构建全部重测，增加安全头/media/raw字节、关联/时间和25表delta。R比较器把user/id/date/ts/token全部折叠为类，未证明全trace双射。
- **25 ALLOWED-DIFF**：10条状态列表BODY（admin users、assignments×3、chat sessions×2、grading×2、usage×2）应以等价前置和逐case投影替换整BODY豁免；11条平台路由（GET health/ready/docs/oauth/openapi/redoc/metrics及HEAD docs/oauth/openapi/redoc）必须按P4最终能力逐条复核；剩余HEAD health、OPTIONS health与OPTIONS chat×2也严格重测。P4现有404/405/500安全头源码测试并不证明docs/OpenAPI/metrics全兼容；文档迁移和CORS若需差异，须有明确批准，登记缺口不是批准。
- **89 SKIP**：P3 chat/SSE/tasks/explain、P4 RAG/internal、历史source成功已具实现线索，应补固定上游/旧release种子后真实采集；P2 auth/admin/assignment/submit成功仍依赖原等价流，需复制相同语义的受控初态及操作序列，纳入同一trace。无法重放的case必须报阻塞/未执行，不能沿用“J3未实施”“依赖live”的理由。

R脚本的“实体双射”实为每个对象数组的原始对象去重数量比较；它不记录跨字段/跨case映射。静态反例（本轮未执行）：`{task_id:A, message_id:A}` 对 `{task_id:B, message_id:C}` 若A/B/C均匹配RE_ID可被归一化为同类；两个同分不同人的user行调换，distinct数不变且USER全被折叠，也可相等；错误deadline/created_at也被折叠。`body_json`解析失败返回None，与真实null未区分。ALLOW(BODY)可吞掉字段缺失/任意score；ALLOW(STATUS,BODY)可吞掉平台500，现有selftest甚至要求该结果ALLOWED。故17/17只证明那17个样例符合R声明，不证明P5严格性。

P5比较器反例必须对**本轮Java原始候选副本**执行，先正例rc0，再变异rc1，坏schema/非法日历/placeholder/NaN/深度rc2；每项留CLI参数、候选摘要、改动字段、预期/实测退出码与脱敏stdout。至少覆盖：200→500同体；80→99同HTTP/计数；数组反序/同分身份交换/同一身份重复；task→session跨类型/跨表task或job关联漂移；null→0/缺字段/类型变化；重复扣次/grade/job submission_done；源码hash全关联一致替换；引用rank/顺序/第5条及同文件第6条；SSE CRLF、截断done、pending伪终态、done后事件、sources非累计前缀、身份漂移；只变业务delta/最终状态与delta不一致。raw检索score仅指定transaction路径容差≤1e-12；citation.round4与业务得分exact。

## 6. 合成账号与数据种子（待P5建立，当前不写库）

| 种子 | 最小用途/要求 |
|---|---|
| leader + adminA + adminB | 真正两班树；teacher_B必须独立subject/token，不允许别名teacher_A |
| viewerA + viewerB + freeViewer | 分属A/B及created_by=NULL；另备A班第二人用于同分排序/三态成绩 |
| operator | 独立subject，验证仅成绩/导出，管理/发布/知识工作台隐藏且服务端拒绝 |
| disabledViewer + deleteViewer + quotaViewer | 停号/删除/零或1配额各用独立账号，不污染导航账号 |
| resetApplicant + expire/revoke凭据 | pending/approved/rejected/used/expired；must_change true/false与改密失效；口令/token运行时生成，600且gitignore |
| static emergency token主体×4角色 + local主体 | `API_TOKENS_JSON`格式由AuthServerSettings解析；至少32字符、roles非空；token模式与account静态应急语义分别核对；local不假装有account作业功能 |
| A/B公开/班级作业 + 空作业 +过期/撤回作业 | .c/.so/粘贴；未提交/0分/非零/最高分，NULL/缺省不同；改名/改分受理快照；CSV含中文/逗号/引号/换行与排序 |
| 当前release R1/R2 + legacy p0-history | 固定1024维；同文件至少6片段、超过4来源、多工具轮；历史session绑定旧release；切active后旧节点可回读；缺release/mismatch单独隔离 |
| 问题A/B +共享知识revision1/2 | unanswered/failed/feedback、认领/合并/验证；出版未索引与索引后区别；并发新反馈/CAS；工作台列表空/非空/分页 |
| chat slow/pending/terminal/error/cancel + explain grade | 固定延迟外部fake使浏览器能断网/刷新，不需真实模型；重放ledger和扣次计数可观察 |
| Markdown与源码哨兵 | 正常标题/表格/代码/链接；`script`、onerror、javascript:、svg危险属性等合成字符串；网络出站只允许本地；源码用运行时随机标记，不入长期表/历史/日志/导出/备份 |

需要两套隔离种子：①严格p0-v2等价初态/操作顺序（含冻结CLOCK与ID格式）用于全trace；②扩展角色/输入/浏览器矩阵使用独立命名空间或独立库，不能把后者额外行塞进前者造成宽泛BODY差异。P0 seed的disabled账号是后续状态操作对象，不能仅凭名字认定初始已停用。

## 7. 浏览器必走导航与验收点（均未执行）

使用README原样命令对应的真实5174→8090请求目标，分别清理/隔离浏览器存储；账号切换不共用陈旧token/session。浏览器工具须遵循browser-harness skill；本轮未启用或操作浏览器。

| 场景ID | 主体/导航与操作 | 必须保留的判定证据 |
|---|---|---|
| UI-01 | account登录→错误密码→正常登录→强制/主动改密→退出；忘记密码→申请→管理员批准→一次性重置/再次使用失败 | 网络真实API、失效token、空态/提示、截图与console，无凭据原件 |
| UI-02 | leader/adminA用户管理：建学生/老师(leader)、归属/配额、停号、重置/设密、申请批准/驳回、删号密码、未分配、删除审计 | A/B隔离与角色限制；adminA不能看到/修改B；操作后回读/DB实际状态 |
| UI-03 | leader/adminA作业管理→建/编辑→撤回/恢复→成绩总览；operator经作业管理入口只见成绩/导出；viewer无管理入口 | 全导航返回聊天、成功与空态；不能仅检查按钮隐藏，服务端反例同步验证 |
| UI-04 | viewerA作业提交：粘贴/.c/.so、Unicode名、大小边界、协议/截止/配额失败；提交后成绩与最高分；点击讲解 | 本次响应分值/列表分值/CSV一致；讲解不重评不改成绩、身份/来源/聊天恢复 |
| UI-05 | 成绩选空作业→非空三态→按班分组→实际下载CSV→以CSV解析器/可用查看器打开 | BOM EFBBBF、CRLF、中文/引号/逗号/换行、行列与顺序/空值、文件实际可读；不只看HTTP200/download点击 |
| UI-06 | 聊天新建→输入/附件→工具过程→正文/来源→历史列表→切会话→删除活动/已完成会话；用量展开、插件骨架、知识健康 | API请求/response、来源编号跨轮、5/6来源不截断，同文件多片段各可打开；刷新后历史与DB一致 |
| UI-07 | 提交慢fake任务→断开浏览器网络/SSE→后端继续→恢复网络接续→刷新找回；另例显式取消，再刷新与重放 | 同request/task/session/message；仅断线不取消；终态重放模型/MCP/扣次增量0；取消不被迟到结果覆盖；pending不会误显示完成 |
| UI-08 | 来源详情打开/关闭、多来源历史/旧release；切active后重新打开旧session | 内容、path/section、编号/rank正确，无跨会话串号；缺失来源可理解错误 |
| UI-09 | admin/leader知识与问题→全部/筛选/翻页→问题详情→认领/备注/关联/合并→新建/修订/撤回共享知识→修订历史→索引后原题验证→解决 | UI“已提交发布，索引更新完成后生效”与实际生效分开；B班问题隐藏；viewer/operator不能发布 |
| UI-10 | 展示正常Markdown表格/代码/链接与危险合成Markdown、来源内容、模板预览 | DOM净化（危险元素/属性/URL无执行）、无alert/额外网络/console error；保留正常代码/引用链接；静态DOMPurify存在不足以证明 |
| UI-11 | local/token/account各启动；local聊天/令牌登录/账号登录；刷新会话/401清存储；默认与恶意Origin | 三种模式适用导航/门控；5174真实CORS+console；不把local无作业当缺陷；记录README若需集成者补配置 |

前端是状态view（chat/admin/assignments/homework/rag）而非已配置路由器；“全导航”按真实可见入口走：新建对话、历史、用量、用户管理、作业管理、知识与问题、作业提交、改密/退出、插件骨架、知识健康、来源详情。网页200或挂载div不算闭环。下载与外部查看器不必引入真实学生资料。

## 8. 准确现有命令与下一实现动作（本轮均未执行）

以下仅是恢复入口，不能因复制命令而越过P4门禁。运行前主Agent分配独立端口/命名空间、串行PG窗口、显式环境及600口令文件；不得source旧.env。

```sh
# cwd = 本仓库根；无PG的比较器基础检查（运行后另存新证据）
PYTHONDONTWRITEBYTECODE=1 .migration-artifacts/p0/venv/bin/python -m unittest migration.oracle.test_review_fixes -v
python3 docs/java-migration/evidence/P2-regression/scripts/run_08_golden.py --selftest

# 全量严格verify：候选路径须为后续acceptance collector的实际Java产物
PYTHONDONTWRITEBYTECODE=1 .migration-artifacts/p0/venv/bin/python -m migration.oracle.verify --fixture p0-v2 --candidate .migration-artifacts/j5-01/java-candidate-trace-p0-v2.json

# P3/P4真实Java HTTP入口：会建/删独立合成库，需要显式DPDK_TEST_PG_*身份配置
./backend-java/mvnw -f backend-java/pom.xml -B -Dtest=ProductApplicationHttpTest,P4PlatformHttpTest test

# 前端单元验证（不等于浏览器）；本轮不npm install/构建/运行
cd frontend
npm test -- --reporter=verbose
```

R受控流水线准确顺序：`run_00_setup.sh` → 应用指向state.pg.database完成迁移 + fake MCP → `seed_accounts.sh` → `collect_state.py` → `run_01_auth.sh`、`run_02_admin.sh`、`run_03_assignments.sh`、`run_04_submission.sh`、`run_05_report.sh`、`run_06_usage.sh` → 再收当前有效token → `run_08_golden.py --state <600身份文件> --log <本轮日志> --out <本轮JSON>`。脚本默认输出指向R历史路径，P5不得覆盖R证据；应通过新collector与新产物目录执行，不运行旧`freeze`。

README原样浏览器启动见 `backend-java/README.md`：独立DB所有`DPDK_JAVA_DB_*`身份键齐全后 `./backend-java/mvnw -f backend-java/pom.xml spring-boot:run -Dspring-boot.run.arguments=--server.port=8090`；前端 `cd frontend && VITE_API_BASE=http://127.0.0.1:8090 npm run dev`（默认5174）。该README当前未给完整P5账户/固定上游/知识seed命令；后续集成者须补可运行准确步骤，不能把上述局部命令描述成完整P5启动证明。

新collector建议动作顺序（未建立脚本名）：①冻结构建/fixture/配置hash与目标身份；②读取frozen map建立66+3登记、生成适用角色/模式/输入扩展表；③独立库迁移+等价seed；④按golden顺序执行请求与settled checkpoint、采25表投影/上游ledger；⑤真实Java专例adapter；⑥verify与candidate副本变异；⑦扩展HTTP与浏览器场景；⑧逐case证据与缺陷退回原owner；⑨独立规范/规格审查与复验。P5不得为绿改金标或放宽time/identity/CORS口径。

## 9. 本轮验证与恢复点

- 实际执行：`pwd`、`git status --short`、branch/HEAD读取、`rg`/`sed`/`cat`；Python仅解析版本化JSON并汇总路由/历史verdict/sha，未import业务、未连库/HTTP、未运行测试。
- 静态断言：p0-v2黄金SHA一致；66映射+3协议route=69 route_id、186 observations；R JSON历史汇总72/25/89；25业务投影；acceptance目录不存在。
- 未执行：构建/单测/严格CLI/selftest、PG与seed、浏览器、负载、外部模型/embedding/MCP、任何生产或原环境操作。
- 观察到别人工作：`backend-java/src/main/java/io/dpdk/assistant/platform/wiring/OperationsWiring.java`在预检时已修改；未触碰、未暂存。
- 本轮进程/端口/容器：未启动；本轮无数据库或外部副作用；原项目/部署/DB保持只读。
- 回退：删除/修订本交接即可；本Agent不执行清理恢复资产。
- 下一门禁：主Agent确认P4最终工程门禁并派独立J5-01 worker后才实施；最先解决完整候选collector与严格比较器反例，再启动浏览器。无需新产品决策即可补合成验证；新增兼容差异/豁免仍须独立决定。
