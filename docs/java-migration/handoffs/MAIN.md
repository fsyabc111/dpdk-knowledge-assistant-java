# 主 Agent 恢复点

## 当前：2026-10-25 U12续接执行中

- 用户已直接发送LONGRUN_P3_RESUME启动指令；P3→P4→P5工程门禁自动推进，live/合并main/生产/退役/删除恢复资产仍禁止。
- 本轮恢复基线codex/p3-p5-longrun@90fae3d，origin一致；旧树490/490需在本轮集成后重新验证。
- 主Agent单写contracts/platform/wiring/STATUS/MAIN/DDL/共享配置；先补契约与Spring装配。
- worker：codex/p3-model-adapter（model/**）；codex/j3-03-chat（chat/**）；codex/j3-04-explain（assignment/explanation/**）；独立工作树位于.migration-artifacts/worktrees/。
- PG55481监听已实查；写入必须显式环境+TestPg身份预检，PG单卡排队，全量主Agent统一执行。
- 待办：契约字段和附件临时输入→Spring单Bean取消、TaskSettings、heartbeat→模型适配集成→J3-03→J3-04→P3报告/双轴审查。
- P3门禁前不进入P4业务；源码输入、原始日志、口令、恢复资产禁止提交。
- 下一步：核对本轮diff及worker消息；装配后执行任务受理和Spring上下文验证。

### 续接检查点：装配通过，P3整链阻断修复中

- 已推送f6b866e（首个公共契约/任务运行装配）；此后主线已集成模型326991d/6c2c8ea、讲解b125aa9/014d6b0/a17383d、chat初步dfea660、装配测试cd925e9，均为开发切片，P3未放行。
- 实际证据：模型worker84/84；Spring+PG装配18/18；讲解数据PG3/3；主线模型/检索配置等48/48；COMP003修复后TaskLedger20/20。旧490全量尚未本轮重跑。
- 新阻断：ChatAgentPorts空Map被当已有job（chat修）；initial_request并发败方按新session查错赢家（主修）；Agent submitted running/unknown仍盲重投违反COMP003（主修并加安全终态异常，chat映射）；TaskGradeLedger装配误用无事务构造（主改真实guard/tx）。必须真实仓储复验后关闭。
- 主线新增ChatWiring/RetrievalWiring与模型Bean，编译/打包通过，端到端应用启动/HTTP仍待实测；所有未验证项保留。
- worker：chat单卡整链与输入/SSE差分；explain整链与真第二JVM恢复；model_adapter独占RealAgentLedgerIntegrationTest新文件。PG排队由主Agent指定，禁止同时跑全量。
- Docker只读核查：当前colima-dpdk-ext socket不存在、原dpdk profile停止；后续P5需要全新隔离profile，不启动/修改旧profile。外盘剩余75GiB。

## 当前：2026-10-25 U12 P3交接点（用户指令：J3-01完成后暂停交接）

**本会话最终状态。恢复本批次从这里开始。**

- **R段（已收口，19a2d47）**：8项验收问题全部关闭+双轴审查通过（规范轴0阻断；规格轴B-1已关闭）。证据：evidence/U12-R-segment-20261025/README.md（含review-spec.md/review-standards.md与处置记录）。全量316/316、strict probe 32/32、严格重放DIFF=0（PASS72/ALLOWED-DIFF25/SKIP89）、SP-04四组真实进程探针、SP-05浏览器README原样实测。
- **P3（暂停于交接点）**：
  - J3-02 已合并（3d426e9，fast-forward）：Agent五工具受控循环，自有124/124。
  - J3-01 已合并（d927fb1）：任务账本/受理事务/进程托管/事件流/恢复，8类50/50，真双JVM+合成PG认领实测，integration_pending三项卡内闭环（IP-1停号取消/IP-J205-2会话删除/TaskGradeParticipant）。
  - **合并树全量verify 490/490（0失败/0错误/0跳过）**——集成分支可构建快照。
  - 接口变更裁决已登记：migration/contracts/interface-changes.md 的「J3-02集成裁决」「J3-01集成裁决」两节（J3-01六项+J3-02三项，含受理/阻断/登记分类）。
- **P3集成窗口待办（下一会话执行顺序）**：①契约字段补全（Probe/AdmissionResult/CompletedAnswer/TaskRow + AdmissionInput附件四字段=J3-03阻断项）；②Spring装配（SessionPortAdapter委托SessionService.appendMessage、admin.NoOpLocalTaskCancellation→task.TaskLocalCancellation单Bean、TaskSettings/heartbeat接线、AgentChatModel真实模型适配器）；③J3-03 chat/SSE集成（SSE冻结契约见migration/contracts/http-and-sse.md；AgentScenario/TaskLedgerTest替身换真实仓储）；④J3-04讲解；⑤P3阶段报告+双轴审查→自动进P4。
- **注意**：IP-1运行时闭环依赖装配替换（NoOp仍在），装配完成前不得宣称生产语义取消已接线。
- 资源：PG 55481运行中（专属实例，口令.migration-artifacts/p1/local-credentials.txt，600）；无应用/MCP/Vite/Chrome进程。worker worktrees已无用可清理：.migration-artifacts/worktrees/j3-01、j3-02（分支worker/j3-01@40cb46c、worker/j3-02@3d426e9均已推送）。
- 提交链（集成分支codex/p3-p5-longrun，均ls-remote核验）：4637ac9授权→f38aff9 R段修复→8c89318/d9364ed ST-02/03→4debf9f R段报告→19a2d47 R段收口→3d426e9 J3-02→3f78f00接口裁决→d68e84f检查点→d927fb1 J3-01合并→（本提交）交接终稿。

## 历史：U12 P3进行中（2026-10-25，J3-02已合并时点）

- **P3状态**：J3-02（Agent五工具）已完成并合并——worker/j3-02@3d426e9（fast-forward进集成分支），自有测试124/124绿（合并树复跑确认），黄金场景Python可达子集对齐，三项接口变更申请已裁决登记（3f78f00：TaskGradePort/TaskJobLedgerPort消费面归J3-01实现，ChatModelPort富接口留J3-03适配）。J3-01（任务生命周期）worker仍在运行（worktree .migration-artifacts/worktrees/j3-01，基线19a2d47）。
- 提交链：19a2d47（R段收口）→ 3d426e9（J3-02）→ 3f78f00（接口裁决）。
- J3-01完成后：合并（预期无冲突：task/** vs agent/**）→ 全量verify → 集成接线（AgentScenario/TaskLedgerTest替身换真实仓储 + AgentChatModel真实模型适配器归J3-03）→ J3-03 chat/SSE → J3-04讲解 → P3阶段报告+双轴审查。
- integration_pending 三项由J3-01交付闭环（IP-1停号取消、IP-J205-2活动会话删除、TaskGradeParticipant）。
- SSE冻结契约已预读（http-and-sse.md）：帧格式双LF、done/terminal:false区分、error-only合法结束、累计sources、引用按chunks顺序绑定。

## 历史：U12 R段收口（2026-10-25，双轴审查通过）

- **R段正式收口**：双轴独立审查——规范轴通过（0阻断5建议）、规格轴1阻断B-1（SP-04实测证据缺口）**已关闭**（补齐GRADING_MAX_CODE_BYTES=100→413/边界200、GRADING_TIMEOUT_SEC=1慢初始化→504+零成绩行、慢tools/call→200不中断语义、非法值真实进程启动失败×2；全部sp04b探针存证 .migration-artifacts/u12-rerun/）。审查处置全记录：evidence/U12-R-segment-20261025/README.md（含S-1～S-5与规范轴①②⑤处置）。审查后全量verify复跑316/316。
- R段最终证据链：Java修复 f38aff9 → ST-02/03 worker 9b4de06（合并8c89318）→ 重放集成 d9364ed → R段报告 4debf9f → 审查处置（本提交）。严格重放DIFF=0（PASS72/ALLOWED-DIFF25/SKIP89）、探针32/32、--selftest 17/17、guard库150条清理。
- **P3 启动（U12授权自动推进）**：J3-01（任务生命周期/恢复）∥J3-02（Agent五工具，用冻结接口先行）→ J3-03（chat/SSE集成）→ J3-04（讲解）。integration_pending 三项（IP-1停号取消、IP-J205-2活动会话删除、TaskGradeParticipant）由J3-01真实实现闭环。worker策略：最多2-3个并行，集成者（本Agent）单写契约/pom/公共装配。
- 资源：PG 55481运行中；探针实例/MCP已全部停止。P3共享PG纪律不变（TestPg身份预检已闭环，可安全动态建删库）。
- 下一动作：派发J3-01/J3-02 worker（独立worktree+分支），集成者准备J3-03契约接线。

## 历史：U12 R段完成（2026-10-25）

- **R段（验收8项修复+P1/P2工程复验）实现与验证完成**：Java修复 f38aff9（ST-01 TestPg身份预检+8负例、SP-01 AnyNestedCondition四组合、SP-02 FAIL_ON_TRAILING_TOKENS严格JSON、SP-03 resultsOf truthy falsy链、SP-04 GRADING_*桥接、SP-05 CORS默认5174+README）、ST-02/03 worker分支 9b4de06 合并 8c89318、重放集成收口 d9364ed（声明表补齐+五轮端到端）。全量verify **316/316**、strict probe **32/32**、SP-04真实进程探针7/7、SP-05浏览器README原样全通过（teacher-grades-u12.png）、严格重放 **DIFF=0**（PASS72/ALLOWED-DIFF25/SKIP89，--selftest 17/17）。报告：evidence/U12-R-segment-20261025/README.md。
- 150个历史guard库按before/after清单清除（仅p1_t_*命名空间；验收库与恢复资产保留）。重放轮临时库已DROP。
- **下一步**：双轴独立审查worker（只读）→ 修复复验 → R段收口 → P3（J3-01～04）。用户验收未登记（独立放行点）。
- 资源：PG 55481运行中；Java/Vite/MCP/Chrome已停。PG口令U12重设，存 .migration-artifacts/p1/local-credentials.txt（600，gitignored）。原始探针/日志 .migration-artifacts/u12-rerun/（gitignored）。
- 提交链：4637ac9→f38aff9→8c89318→d9364ed，本地/远程SHA一致。

## 历史：U12 批次启动（2026-10-25）

- 用户以 LONGRUN_P3_P5_DRAFT.md 正式启动长时任务（U12授权）：先关闭验收8项问题（ST-01～03、SP-01～05）并完成P1/P2工程复验，再连续P3（J3-01～04）→P4（J4-01～03）→P5隔离工程交付+J5-04交付准备。工程门禁通过自动推进；最多3个worker；小步提交推送、持续交接。**不在本批次**：真实调用验收、合并main、生产切换、Python退役、删除恢复资产。
- 集成分支：`codex/p3-p5-longrun`（自 f06ae18 建立）；`codex/p1-p2-longrun` 保留为P1/P2验收状态存档。环境核对：工作区干净、PG55481/18090/18100/5173无运行进程、磁盘74GB可用。
- 8项问题定义：evidence/P0-P2-acceptance-20261025/README.md。修复顺序按任务书R段：先ST-01（TestPg身份预检）+SP-01（ModelWiring四组合），再ST-02/03+SP-02/03/04+SP-05。
- 约束提醒：ST-01关闭前不得重跑PG全量测试；PG启动/写操作前先核验身份（pgdata=.migration-artifacts/p1/pgdata，恢复命令见下文历史段）；Java输出不得反向改黄金。
- 资源：沿用P1专属PG（55481，p1_java，comment=java-migration-p1-test）。验收遗留5个guard库待ST-01修复时按before/after清单审查清理，不全局删除。
- 下一动作：R段实现修复 → 全量复验 → 独立审查 → R段报告 → 自动进入P3。

## 历史：2026-10-25 独立验收复核

- 用户要求先验收P0/P1/P2；对象 `codex/p1-p2-longrun@c916e2d4f8e642cd163bcc0808a5bd20b224f339`。结论：**P0保留通过，P1/P2暂不通过**；8项需关闭，用户验收未登记通过，P3仍未授权。
- 入口：`evidence/P0-P2-acceptance-20261025/README.md`。规范轴ST-01测试辅助入口先写库后验身份、ST-02重放可假通过、ST-03回归不可独立复现；规格轴SP-01 embedding单独启动、SP-02提交JSON边界、SP-03 results假值丢成绩、SP-04 GRADING_*失效、SP-05 Vite/CORS端口错配。
- 本轮实测：PG下Java verify 298/298、P0单元19/19、CLI40/40、React102/102及build；独立HTTP25/32，7项差异；真实浏览器教师/学生基本路径和学生提交80分/折算16分通过。embedding启动失败、错误PG期望仍建库、判定器三个假通过反例均已复现。原始日志/探针在 `.migration-artifacts/acceptance-20261025/`，不提交原始输入/凭据/日志。
- 本轮不改业务代码/DDL/黄金；只更新验收证据、STATUS和本交接，按仓库约定正常提交推送。原Python/React/部署相对main无源码改动。
- 资源：本轮Java18090/fake MCP18100/Vite5173/独立Chrome19222已停止；PG55481已正常停止。保留 `.migration-artifacts/p1/pgdata`、验收库 `p1_java_accept_20261025` 与本轮套件遗留的5个guard库；显式错误身份探针库已删除；未清理历史库/恢复资产。恢复专属PG命令（仓库根）：`/opt/homebrew/opt/postgresql@17/bin/pg_ctl -D .migration-artifacts/p1/pgdata -l .migration-artifacts/p1/postgres.log -o '-p 55481 -h 127.0.0.1 -k /tmp/dpdk-java-p1-80b29135' start`；任何测试写入前先人工核验身份，直至ST-01修复。
- 下一步：按8项问题修复复验，先P1隔离/装配，再P2和严格差分；不扩到P3，不把历史“0阻断”当作当前结论。以下U11收口内容保留为历史。

## 历史：U11 批次工程收口

- 日期：2026-10-25（**U11 批次工程收口**）；集成分支 `codex/p1-p2-longrun`，收口提交见 git log（全程 SSH 通路推送，每次 ls-remote 核对本地/远程 SHA 一致）。会话与子 Agent 均在 GLM-5.3 Flash。
- **P1 工程门禁通过**（双轴 0 阻断，ef728cb）：四卡 150/150、三路径迁移链实测、身份预检负例、最小问答全链路。
- **P2 工程门禁通过**（双轴 0 阻断）：五卡集成 298/298；启动端到端（9ba2378 装配修复批次后 health UP/迁移完成/422 形态）；集成回归零阻塞（c7cbab3：场景 1-7、契约 DIFF=0、React 联调接线）；规格建议 D2/S1/S3 已关闭（060a185，500 兜底 Starlette 平价+断言更新+SKIP 分类）；规范建议 S-1/S-2/S-4 已处理（口令脱敏、STATUS 更正、DECISIONS P2-D1 差异登记），S-3/S-5 说明入报告。
- **批次边界**：不进入 P3、不合并 main、不部署、未删除任何恢复资产。用户验收由用户统一进行（工程门禁通过≠用户验收）。
- integration_pending 交接 P3：IP-1 停号→任务取消、IP-J205-2 活动会话删除、TaskGradeParticipant（均 J3-01）；secure-headers 与 CORS 403 差异在 DECISIONS P2-D1（J4-03 评估实现）；500 形状 P5 全矩阵复核。
- 运行资源：PG 55481 实例运行中（.migration-artifacts/p1/pgdata，保留）；candidate 库已由回归 worker 清理。全部 worker worktree 已删除；集成分支工作区干净。

## 历史（P0→P1→P2 过程记录，以上方当前状态为准）

- 目录：`/Volumes/study/code/new_project/dpdk-knowledge-assistant-java`；P0 于 `codex/p0-baseline-oracle` 完成（初始基线`80b29135faddd16d5e39fdbf0633291ea6a20489`）。
- 当前源码目标：本仓库冻结Python副本；原目录仅来源，不同步变化；backend/前端原件未改。
- 验证代码/证据提交：08158c6初版、c94f43d修复、099dca2最终v2数据、a207a4a最后CLI类型守卫；用户已验收交付7c76c31；验收记录提交d96bf4c已推送到同名origin分支，本地/远程SHA一致，证据evidence/P0/push-verification.json。
- U11 授权/联调接线/瞬态失败记录：CORS 对等配置与 README（e0a5c26）；一次与 worker 并发占用测试 PG 的瞬态 BUILD FAILURE，复跑全绿（记录在案）。

## P1 收口完成，P2 进行中（2026-10-25）

- **P1 工程门禁通过（ef728cb 已推送核验）**：五项齐备（四卡 150/150、PG/危险目标拒绝、替身+固定向量+问答链路、双轴独立审查 0 阻断、报告交接推送核验 SHA 一致）。规格审查建议 S1 已补登记（interface-changes.md）；S6 待 P2 接线关闭。用户验收待批次结束。P2 按 U11 预授权自动生效。
- P2 已派工：J2-01（认证）worktree .migration-artifacts/worktrees/j2-01（worker/j2-01@ce6a139）；依赖链 J2-01→J2-02→J2-03∥J2-05→J2-04。spring-security-crypto 已由集成者预置（ce6a139）。
- U11 联调接线落地（e0a5c26）：CORS 对等配置、backend-java/README.md 运行/联调命令。注意：该提交曾因与 J2-01 worker 并发占用同一测试 PG 实例出现一次瞬态 BUILD FAILURE，随即复跑 150/150 全绿（记录在案；后续集成验证尽量避开 worker 测试高峰或复跑确认）。
- 推送通路：origin pushurl=ssh://git@ssh.github.com:443/...（HTTPS 劣化期间的临时切换，恢复正常后可改回）；每次推送 ls-remote 核对。

## P1 收口历史（bb07ab7..ef728cb）

- 提交链：bb07ab7（J1-04 完成+接口登记，150/150）→ c919550（model/harness Spring 接线）→ 2c49e78（phase-report 初稿+规范轴结论）→ a29b4ad/c7a44f4（推送恢复登记）→ ef728cb（门禁终稿）。
- 独立审查：规范轴通过（0 阻断 4 建议，review-standards.md）；规格轴通过（0 阻断 6 建议，review-spec.md）。
- J1 worker 分支：worker/j1-02@9d3e3b7、worker/j1-03@251b65f（已合并，worktree 已清理）。
- P1 独立测试 PG（55481）持续运行供 P2 使用；测试库动态建删。

## J1-01 切片1（历史记录，f84d2c9）

- backend-java 可构建：JDK21.0.12.1 + Boot4.0.9 + Maven Wrapper 3.9.12；`./backend-java/mvnw -f backend-java/pom.xml -B verify` 26/26 通过（含三路径迁移集成测试）。
- 三路径迁移链实测：空库B0015 / 合成0013→V0014+V0015（讲解回填+伪造反例）/ 等价0015空转 / 重复启动无变化 / 部分表拒绝；catalog 差分对 P0 oracle 真实结构清零。
- P1 独立测试PG运行中：127.0.0.1:55481，角色 p1_java（SUPERUSER，合成实例），data_directory=.migration-artifacts/p1/pgdata，测试库动态建删，comment=java-migration-p1-test。参考schema与完整日志在 .migration-artifacts/p1/（不提交）。
- 契约接口：contracts 下 18 端口全部落地；占位类型由 owner 经接口变更登记补全。
- 下一步：git worktree 派工 J1-02（main:model|harness + test 同名）与 J1-03（main:retrieval + test 同名）并行；两者只读契约/fixture，不改公共文件；主Agent 单写 pom/契约/DDL。完成后集成并跑 J1-04。

## 最终冻结与证据

- J0-01 accepted：378文件一致，66路由/29表/103设置/64模型/96环境键/4YAML键/依赖映射；10静态护栏通过。最终记录evidence/J0-01/final-freeze.md。
- J0-02 accepted：契约p0-v1，当前fixture p0-v2；golden SHA8d0658661fedb31c6a0690ea97cb3167534504049c65861b3c67e6691337de14。历史p0-v1/rev2保留。
- 双独立库各186HTTP/66路由/11PG/29表，25业务表198步状态链可重建。40真实CLI（0×2/1×20/2×18）、9组纯回归、有效token停用4断言通过。
- 完整3×(预热120s+测量600s)：8419问答p95=2154.735ms、p99=2195.212ms；21600读作业p95=30.137ms；正常分组异常0。全阶段含预热/讲解终态与chat扣次10278，正式成绩/扣次174，inputs0，费用unknown不转零。
- 规范原3项、Spec原4项均关闭；最新报告evidence/P0/review-standards-closure.md、review-spec-final.md；历史失败报告不覆盖。
- 阶段报告evidence/P0/phase-report.md；实现/自动/合成场景/用户验收分列。无Java候选，不宣称Java或生产容量通过。

## 决策与下一阶段

- U08/P0-D13：Java running只等/查且受600秒墙；unknown/查询失败不重投，system_error/harness_result_unknown终态、保留submitted账本、清输入。Python重投失败证据保留。
- U09/P0-D15：用户确认的权威门槛migration/contracts/thresholds.json；组合CPU/RSS测量域必须对齐；RTO1秒仅10ms脚本执行，330秒调度等待仅推导；raw检索score容差1e-12，顺序及citation.round4精确。
- P0-D14：Java21/Boot4.0.9/AI2.0.2、BOM管理JDBC7.0.10/Flyway11.14.1/PGJDBC42.7.14；仅官方资料核对，实际构建留P1。
- 单一DDL owner主Agent/root（后续J1-01窗口）；B15/V14/V15三路径设计须PG身份与完整catalog前置，实际Java迁移未验。
- 28路由完整重试并发、全auth角色组合、React/TCP、zhparser、真实DPDK/harness/模型质量、旧部署/schema均未验证，仍按后续任务卡验收。
- 下一动作：分支已按U10推送并核验；等待用户直接放行P1后，从STATUS和migration/contracts/P1_HANDOFF.md恢复，不自动推进。

## 所有权、进程与恢复

- 所有worker均已结束并释放窗口；当前公共集成/STATUS/MAIN/DECISIONS归主Agent。没有运行应用/模型/MCP/负载/故障子进程。
- 本任务PG17.10+vector0.8.4已由主Agent正常stop退出0（PID17563），不影响其他PG。数据在.migration-artifacts/p0/pgdata，未删除；venv、日志、历史fixture和恢复资产均保留。
- 旧PG身份：127.0.0.1:55473，角色p0_oracle，p0_python_oracle/p0_candidate_oracle/p0_benchmark_oracle，comment=java-migration-p0-synthetic-80b29135；socket=/tmp/dpdk-java-p0-80b29135。
- 独立venv=.migration-artifacts/p0/venv，Python3.11.15+133包，pip check通过。原始运行物全部忽略，不提交。
- 在仓库根恢复本任务PG（不是生产部署）：

```sh
mkdir -p /tmp/dpdk-java-p0-80b29135
/opt/homebrew/opt/postgresql@17/bin/pg_ctl -D .migration-artifacts/p0/pgdata -l .migration-artifacts/p0/postgres.log -o '-p 55473 -h 127.0.0.1 -k /tmp/dpdk-java-p0-80b29135' start
```

启动后先用oracle安全入口校验URL/role/comment/data_directory，再按migration/oracle/README.md运行；run会重置指定合成库，不与同库并发，禁止回退旧.env或执行旧启动/Compose。
