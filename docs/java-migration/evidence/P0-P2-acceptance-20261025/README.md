# P0/P1/P2 独立验收复核

日期：2026-10-25。验收对象：`codex/p1-p2-longrun@c916e2d4f8e642cd163bcc0808a5bd20b224f339`。对照基线：`main@80b29135faddd16d5e39fdbf0633291ea6a20489`。

**结论：P0 保留通过；P1/P2 本轮复核不通过。** 既有测试全绿，但本轮发现 8 项需关闭的问题：规范轴 3 项、规格轴 5 项。实现和自动测试完成不等于已满足用户验收；本轮不登记用户验收通过，不进入 P3。

本轮只新增验收证据、更新状态和主交接，不修改 Java/Python/React 实现、黄金文件或比较规则，不调用真实模型、embedding、评测服务，不合并或部署。源码 `backend/`、`frontend/`、旧部署目录相对 main 无差异。

下文方括号P1/P2表示问题优先级；条目末尾括号中的P1/P2表示迁移阶段。

## 实际验证

| 验证 | 本轮结果 | 边界 |
|---|---|---|
| P0 静态清单与差分器单元回归 | 19/19 通过 | 含 378 文件冻结基线、覆盖护栏；不连接业务库 |
| P0 v2 真实 CLI 正反例 | 40/40；已冻结 fixture 拒绝覆盖 | 包含状态/成绩/扣次/来源篡改、损坏输入；复用既存合成 trace，不冒充本轮重新采集双库 |
| Java `mvnw -B verify` | 298 项，失败 0、错误 0、跳过 0，BUILD SUCCESS | 显式专属 PG 55481；含迁移三路径、检索、模型/MCP 协议替身、各业务卡 PG 测试 |
| React `npm test` | 102/102 通过 | 原前端测试，不能独立证明 Java 兼容 |
| React `npm run build` | 成功 | 1584 modules；未部署 |
| 独立真实 HTTP 探针 | 32 项：25 通过、7 项差异 | 差异对应 SP-02/03/04/05；与历史宽松重放脚本独立 |
| embedding 单独配置启动 | 退出 1，缺少 ModelClients Bean | SP-01，未向死端点发模型调用 |
| PG 测试前置身份探针 | 错误 expected-data-dir 下仍建库成功 | ST-01；只在人工核验的专属实例，探针库随即删除 |
| 历史重放判定器变异 | 错状态→PASS、列表反序→PASS、错分数→STATE-DIFF | ST-02；从原脚本 AST 抽取真实判定分支执行，不改黄金或源码 |
| 浏览器最小业务路径 | 教师/学生登录、用户管理、作业列表、成绩展示、学生提交通过 | 独立 Chrome + browser-harness；学生结果80分/用例4/5/折算16/历史最高16；Vite显式使用5173，默认5174问题见SP-05；原生select键盘选择未生效，使用DOM change事件选择协议，提交和结果展示通过真实页面完成 |

构建：JDK 21.0.12.1、Boot 4.0.9、Maven Wrapper 3.9.12。构建 JAR SHA256 `7de0c0d225e4084ac0218471a00116a24b9b9891a5ba827e3354d58aa1fc5eb9`。p0-v2 黄金 SHA256 `8d0658661fedb31c6a0690ea97cb3167534504049c65861b3c67e6691337de14`，与冻结记录一致。

测试先确认本仓库 `.migration-artifacts/p1/pgdata`、角色 `p1_java`、端口 55481。应用使用新库 `p1_java_accept_20261025`，comment=`java-migration-p1-test`，启动日志为 `EMPTY/applied=1/catalogVerified=true`，重启为 `HISTORY/applied=0`。Java 18090、fake MCP 18100、Vite 5173；只使用合成账号与输入。

正常路径已实测：错误密码与不存在用户同体 401、Bearer 鉴权头、学生管理面 404、跨班作业隐藏、发布/撤回/恢复、正式提交 80 分折算 16/20、成绩权限、CSV BOM/CRLF、unknown 费用 null、源码标记不入成绩 JSON、代码 hash 一致、停号吊销和重新启用不复活旧 token、另一班不受影响。浏览器教师成绩截图：[teacher-grades.png](teacher-grades.png)。

## Standards：规范轴

### ST-01 [P1] PG 测试辅助类先写入，后校验身份

- 位置：[TestPg.java:55](../../../../backend-java/src/test/java/io/dpdk/assistant/platform/pg/TestPg.java#L55)，`createDatabase` 直接连接环境 URL 并执行 CREATE/COMMENT，`dropDatabase` 也无实例预检。各测试随后才通过应用迁移入口校验。
- 依据：根 AGENTS 数据库规则“测试启动先校验目标身份”；J1-01 要求测试 PG 安全预检及危险目标拒绝。
- 本轮复现：在已人工核验的专属实例上把 `DPDK_TEST_PG_DATA_DIR` 设为 `/deliberately-wrong-acceptance-identity`，直接调用现有 TestPg；输出 `WRONG_EXPECTED_DATA_DIR_BUT_CREATED=p1_t_acceptwrongidentity_...`，随后 drop 成功。错误期望未阻止首个写操作。
- 影响：误配测试连接可能先在错误实例创建或删除库；应用自身的迁移预检不能保护测试辅助入口。
- 修复验收：CREATE/DROP 前验证受控实例身份和 URL；用错误 host/port/user/data-dir/测试标记反例证明零写入。负例测试要在 finally 清理自己创建的库。本轮全量测试后额外遗留 5 个 guard 库，属于该辅助测试的资源清理问题；没有删除既存历史库。

### ST-02 [P2] 历史 P2 重放判定器可把真实契约回归判为通过

- 位置：[run_08_golden.py:379](../P2-regression/scripts/run_08_golden.py#L379)、[列表比较:400](../P2-regression/scripts/run_08_golden.py#L400)、[全局归一化:51](../P2-regression/scripts/run_08_golden.py#L51)。
- 依据：`acceptance.md` J0-02 要求比较 HTTP 状态、值、顺序、副作用和身份关联，不允许删除 ID/时钟断言；历史报告声称状态相等和身份双射。
- 本轮抽取原判定分支，给出三个确定性反例：golden HTTP 200/live 500、同体 `score=80` → **PASS**；成绩行 `[10,20]` 反序 → **PASS**；`score:80→99` → **STATE-DIFF**（不计失败）。原因分别是成功对象分支漏比状态、列表转 set 排序、任意同类型值差异被归为环境状态。
- 影响：历史“DIFF=0”不足以证明成功响应兼容；不否定单卡测试及确已执行的正常业务路径。
- 修复验收：逐 case 声明允许差异，恢复状态/值/数组顺序及实体双射验证；上述三种变异都必须失败，并用冻结 Python oracle 重新对照。不可用本轮 Java 输出重写黄金。

### ST-03 [P2] 已交付回归入口不能从空环境独立复现

- 位置：[run_08_golden.py:24](../P2-regression/scripts/run_08_golden.py#L24) 固定已删除 worktree；第 41 行固定旧作业 ID；第 215 行依赖未交付的 `/tmp/p2reg/tools/BcryptGen.java`；第 113/221 行仍硬编码合成 PG 口令。
- 依据：卡片交付要求可复现命令，AGENTS 要求配置通过环境/模板交付；README 声称脚本可复现、口令由环境提供。
- 影响：按 README 从当前 checkout 不能重放既有证据；临时文件/旧身份不是交付依赖。这里只发现合成测试口令，不宣称真实凭据泄漏。
- 修复验收：从仓库定位 ROOT、显式环境配置、受控空库种子入口、动态保存本轮身份，完全不依赖旧 worktree 和既存 `/tmp` 状态；干净临时目录重跑。

## Spec：规格轴

### SP-01 [P2] 单独配置 embedding 无法启动（P1）

- 位置：[ModelWiring.java:28](../../../../backend-java/src/main/java/io/dpdk/assistant/platform/wiring/ModelWiring.java#L28)、第 43–45 行。
- 依据：J1-02“保持聊天与 embedding 分离配置”。ModelClients 仅在配置 chat.base-url 时创建，embedding Bean 却总要依赖它。
- 复现：正常数据库配置下仅设置 `dpdk.model.embedding.{base-url,api-key,model-name}`，端点使用本地死地址；进程退出 1：`embeddingClient required a bean of type ModelClients that could not be found`。
- 修复验收：chat-only、embedding-only、两者、均未配置四种启动组合；均保持无默认外部端点和无真实调用。

### SP-02 [P2] 提交 JSON 解析与冻结 Python 不兼容（P2）

- 位置：[SubmitRequestParser.java:79](../../../../backend-java/src/main/java/io/dpdk/assistant/assignment/submission/SubmitRequestParser.java#L79)。
- 依据：J2-04 输入/编码边界和错误状态保持兼容；冻结 Python `assignments.py:275–278` 使用完整 `request.json()`，解析失败返回 422。
- 复现：有效学生 token，向存在作业 `/submit` 发 `Content-Type: application/json`：空 body 和纯空白各返回 **500**（Python **422**）；合法对象后附 `garbage` 返回 **200**，并触发 MCP/扣次/成绩保存（Python **422**）。前两者是 readTree 返回 null 后解引用；后者是未检查根节点后的尾随 token。
- Python 预期通过 AST 抽取冻结 `_parse_submit_request` 的原函数，使用 Starlette Request 和原 Pydantic 请求模型直接执行确认；没有导入应用或读取 `.env`。
- 修复验收：空/空白/尾随垃圾/多个根对象/合法对象及合法非对象边界；所有拒绝情形零 MCP、零配额、零成绩。

### SP-03 [P2] `results:false/0` 使已执行的成功评测丢失成绩（P2）

- 位置：[AssignmentSubmissionService.java:195](../../../../backend-java/src/main/java/io/dpdk/assistant/assignment/submission/AssignmentSubmissionService.java#L195)。
- 依据：J2-04 要求保持畸形 payload 现行语义；Python 第 353 行为 `list(payload.get('results') or [])`。
- 复现：fake MCP 返回 `ok:true,score:80,passed:4,cases_total:5`，分别把 results 设为 false 和 0。两次 Java 均 **500**；Python 为 **200/results:[]**。本轮最终严格探针共 6 次 grade 调用、扣次 6，但仅保存 4 行成绩；两次缺行正是这两个 false/0 分支。
- 影响：评测已经执行并扣次，用户拿不到有效成绩；没有自动重发证据，不应通过重投掩盖。
- 修复验收：false/0/0.0/null/空串/空对象/空数组及正常数组的冻结真值语义；比较 HTTP、调用次数、扣次、落库和响应。

### SP-04 [P2] 声明支持的 GRADING_* 环境变量未被读取（P2）

- 位置：[SubmissionWiring.java:17](../../../../backend-java/src/main/java/io/dpdk/assistant/assignment/submission/SubmissionWiring.java#L17)。
- 依据：J1-01 配置映射和 J2-04 配额/大小/超时；此文件第 9 行明确承诺 `GRADING_TIMEOUT_SEC/GRADING_MAX_CODE_BYTES/GRADING_DAILY_QUOTA`。
- 复现：启动进程设置 `GRADING_DAILY_QUOTA=2`，同一学生第三次有效提交仍 **200**，预期 **429**。代码只读取 `dpdk.grading.daily-quota`，无同名旧环境变量桥接。另两个变量按静态绑定存在相同缺口，本轮未实测其实际超时和字节上限。
- 修复验收：明确唯一兼容配置及优先级；以非默认 quota/max bytes/timeout 分别实测，非法值启动失败，不能仅改注释消除契约。

### SP-05 [P2] README 默认 React 联调命令被 CORS 拒绝（P2）

- 位置：`frontend/package.json:7` 的 dev 固定 5174；`backend-java/src/main/resources/application.yml:34` 默认 CORS 只允许 5173；`backend-java/README.md:42` 未覆盖 Vite 端口或 CORS。
- 复现：相同 `/api/auth/login` 预检，Origin `http://127.0.0.1:5173` 为 **200**，Origin `http://127.0.0.1:5174` 为 **403**。本轮浏览器显式 `--port 5173` 后登录成功，这个绕行不计默认命令通过。
- 影响：用户照 README 启动前后端会遇到浏览器登录失败。此问题与已登记的“非允许来源 400→403”平台差异不同。
- 修复验收：统一文档/脚本/允许来源；从 README 原样运行，真实浏览器登录并打开作业/成绩，同时仍拒绝非允许来源。

## 证据与下一步

- [verification-summary.json](verification-summary.json)：构建、测试、探针和隔离信息摘要。
- [probe-results.json](probe-results.json)：32 项严格 HTTP 结果；不含 token、口令或运行源码。
- [golden-mutations.json](golden-mutations.json)：原脚本判定分支的三项假通过证据。
- 原始日志和探针程序仅留 `.migration-artifacts/acceptance-20261025/`，不提交。第一版探针误用 PATCH 停号；该批相关断言作废并保留原始证据，改为冻结 POST 契约、重启清除登录限流状态后完整复跑，上表仅计最终 32 项结果。
- 收口：Java/fake MCP/Vite/独立Chrome已停止，PG55481正常停止；PG数据、验收合成库和5个本轮套件遗留guard库保留。错误身份探针库已删除；既存历史库和恢复资产未删除。后续修复ST-01时可按本轮before/after清单审查清理，不能全局删除。

尚未验证：P0 历史长负载未本轮重跑；P3 停号取消任务/活动会话删除/TaskGradeParticipant、完整 chat/SSE/恢复/讲解，P4 索引运维，P5 全矩阵、故障容量、真实上游质量、生产 schema/切换，均不计通过。额外观察到框架未匹配路由/方法被全局兜底转成 500，此属已声明待 P5 复核的框架错误面，不作为本轮新增第 9 个阻断。

下一步仅限按 ST-01～03、SP-01～05 修复和复验；先关闭 P1 隔离与装配，再补 P2 输入/结果/配置/联调及严格差分。报告本身不授权 P3、合并或部署。用户验收仍待缺陷关闭后的直接确认。

规范轴3项，最高优先级P1（ST-01测试隔离）；规格轴5项，最高优先级P2（含已执行评测丢成绩及非法JSON进入评测）。
