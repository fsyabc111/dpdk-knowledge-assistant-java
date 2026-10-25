# J5-02 容量与恢复预检交接（计划，不是执行证据）

- owner：capacity 子 Agent；共享工作区 `/Volumes/study/code/new_project/dpdk-knowledge-assistant-java`。
- 当前权限：U12 下的只读准备；P4 最后集成中，等待主 Agent 的 P4 门禁及 P5 实施消息。仅本文件可写；不提交/推送。
- 状态：预检完成；实现、自动验证、场景验收、用户验收均未执行。未启动 PG、Java、Python 服务、负载或模型。
- 权威：`acceptance.md` J5-02、`LONGRUN_P3_P5_DRAFT.md` §5、`migration/contracts/thresholds.json`。根 AGENTS 的初始“Java 尚未实现”属于历史，不能替代当前主 Agent 授权。

## 最小硬依赖与阻断

1. P4 门禁和 P5 实施放行；J5-01 核心闭环可运行；主 Agent 排他分配独立 PG/负载窗口，禁止与集成 verify 并跑。
2. **当前没有可直接复用的 Java 容量 adapter。** 实际目录是 `migration/oracle/performance/`，不是 `migration/performance/`。`run.py` 的 Runtime 写死 Python 应用、55473 专用 P0 库、ASGITransport、Python UserService seed、内存 TASKS 等待和 admission 采样；Resources 只采自身应用/负载/统计进程及 PG。不能只替换 base URL，也不能把功能 oracle trace 当性能 adapter。
3. 推荐另建 P5 双端 TCP adapter：同一独立负载器、相同 loopback HTTP 协议/轮询/连接策略，分别测冻结 Python server 和 Java server。Python server 内加载冻结代码与同样合成边界；Java 以测试装配替换模型/检索/MCP 边界，业务身份/事务/权限/Agent/任务均真实。两端都只有应用进程+相同 Python 负载/统计/采样进程，无外置 fake 额外漏算；若改用外置 fake，必须登记并对齐进程与延迟测量域，不能静默扣掉其成本。
4. TCP 使历史 ASGI 布局变化，必须在新合成环境完整重测 Python server 基线，独立输出到 `evidence/J5-02/`，不覆盖 J0-02。**新增基线不授权改冻结数字**：Java仍须满足下表所有现有上限；新基线用于证明同环境可比及报告增量。若匹配布局基线或冻结限值不满足，记录失败/阻断；不能拿新较慢基线放宽门禁。未补齐布局仅可观察，不判容量通过。
5. 要补真实 SIGKILL→新 JVM 恢复和冻结600s可控调度证据；现有测试不覆盖这两项（见下）。当前 `OperationsWiring.java` 有他人未提交变更，本 owner 不触碰。

## 冻结 normal mix 与判定

固定3轮；每轮120s预热、600s测量，至少2160s墙钟加启动/排空/校验；Python和Java顺序执行最少4320s，不并跑。每次独立建库/重置的策略两端一致；同一端连续三轮保留同样累计数据增长，不能每轮清空Java而Python累积。

60学生+1组长+1作业；账号quota=100000只防测试触顶，正式评测日quota仍20；bcrypt12、主体每分钟20、PG池32、20活动/20等待/队列10s、任务600s、输入TTL24h、心跳10s、grace30s、终态运行句柄TTL300s。每阶段60账号各真实登录一次；每5s读作业（初始按账号index×25ms错峰）；10问答worker闭环轮转60账号，每问新request/session；正式提交单worker，成功后发起讲解并等终态，下一次提交前等10s。聊天与讲解共用10槽；不测开放到达流量。

模型**每调用1s**，每问真实两轮模型+kb_search工具循环；检索外部边界50ms，固定1个合成片段；MCP200ms。不能改成每个问题总1s。无需embedding/真实模型/真实harness。源码仅运行时生成，不落长期证据。

| 组 / 计时 | 冻结绝对p95(ms) | 冻结Python最差轮+允许差值(ms) | 实际同时门禁(ms) |
|---|---:|---:|---:|
| login：POST `/api/auth/login` |500|260.369+52.074|312.443|
| assignment_read：GET `/api/assignments` |100|33.225+50|83.225|
| chat：POST `/api/chat` 到完整非流式答案 |3000|2163.409+432.682|2596.091|
| formal_submit：POST `/api/assignments/{id}/submit` |500|219.307+50|269.307|
| explain_admission：POST `/api/assignments/{id}/submissions/{sid}/explain` |250|99.866+50|149.866|
| explain_terminal：从上述讲解发起到终态GET完成 |3000|2177.715+435.543|2613.258|

历史 explain_terminal 用进程内 managed.done 再GET；TCP adapter须对两端采用完全相同终态观察机制并固定其频率/请求数，记录额外开销，不把原内存等待当TCP证据。建议显式GET轮询（固定10ms），总账单列轮询请求；先短测证明轮询成本和延迟可控，不能省略/缩短600s门禁。

每组每测量轮独立满足两条p95与异常率≤0.001；所有成功、429、超时、异常耗时和分母保留，不能仅统计成功或合并三轮掩盖失败。最近秩p50/p95；n≥1000才报p99，其他null；启动前10槽等待不混入原端点计时，排空另报。正常mix的拒绝仍异常；独立背压用例的预期429单列。零越权、零重复业务扣次、零重复聊天成绩、零永不终态任务；模型费用unknown不能转成0。

## CPU/RSS 与环境对齐

- 冻结比较域：应用+负载+统计/采样器。TCP两端均为应用进程+同一Python驱动；Java用应用JVM PID，禁止把Maven启动父进程误当应用；若存在必要子进程，应显式列入并对齐。
- 每约1s采所有比较PID的累计CPU与RSS，保留UTC、单调时间和实际采样间隔；同一时间样本先求和，再以真实Δt算组合CPU，100%=一核；最后算测量窗口组合CPU p95≤100%，组合RSS峰≤512MiB。不能相加不同时间的峰值/百分位，也不能改为各进程分别≤512MiB。
- PG另列：同实例进程RSS共享页重复，当前无PG独占硬上限；采pg_stat_activity定位其他库干扰。不得把PG挪出/纳入应用域换通过。负载窗口独占，检测其他活跃负载则保留证据、判不可比并说明重测原因。
- 记录硬件/OS/JDK/Python/PG与vector版本、commit+dirty diff hash、runner hash、DB身份/comment/data_directory、数据规模/表计数、连接池、所有任务配置、边界调用次数/延迟、TCP/连接复用、JVM flags/堆、预热启动行为。原基线M4/10逻辑核/16GiB、Python3.11.15、PG17.10/vector0.8.4；本次须现场核验，不能引用旧快照当当前。

## 恢复、队列与时钟补证

已有入口（本次只读未运行）：

- `TaskRecoveryTwoProcessTest` + `TaskTwoProcessHelper`：真第二/第三JVM、同PG；测试活属主不抢、停心跳后拨旧认领、竞争恰好一次、pending非终态。它没有杀死运行中的生产者，不是HTTP kill/restart证据。
- `ExplainRecoveryIntegrationTest` + `ExplainRecoveryProcess`：真新JVM从已受理讲解恢复、无重评测、重放无重复扣次；也没有SIGKILL。
- `RealAgentLedgerIntegrationTest`：finished复用；running只查到finished、不重投；unknown/query_failed/error_payload安全终态、submitted保留、输入清理。此证据仍需跨SIGKILL与外部替身账本补齐。
- `TaskRunnerConcurrencyTest`：4/4等缩小队列与60ms执行墙，TTL推进400s后DB重建；不能标为20/20/10s/600s完整验收。
- `OperationsAdaptersIntegrationTest#inputDryRunZeroWritesThenCleansTerminalOrphanAnd24HourExpiry`：终态/孤儿/过期清理，当前以构造expires_at样例验证，需再补从真实受理生成24h expires_at、可控时钟推进边界。

放行后故障方案：Java生产者A真实启动，在受理后、模型前后、MCP提交前后、成绩事务前后、终态后断线建立可控门栓。测试控制器只SIGKILL自己创建且已核验的A PID，等待退出；PG与独立MCP替身账本存活；新Java恢复者B启动，确认ready后先查job再调用真实 `ensureManaged`/`recoverOrphans`。分别记录kill→B-ready、grace、扫描等待、执行段、用户可见终态；不以新JVM启动时间吞进1s或反过来省略后声称全程1s。

1s硬门槛仅 `kill-restart-accepted` / `kill-restart-before-terminal` 的10ms模型/10ms检索/10msMCP脚本，进程已启动、槽位可用、PG健康、无未决外部job或已有finished结果，计 `ensureManaged` 到持久化终态及输入清理。running等待用600s外墙约束；unknown/查失败按COMP003安全终态，不声称外部作业完成。跨进程断言同task/job身份、quota/grade各最多1、MCP重投0、submitted账本保留、终态清输入、迟到完成不覆盖取消；保留Python已知unknown重投失败，不改黄金。30+300=330s只是调度等待推导，自动循环实测另列，不能宣传生产RTO。

独立队列场景：同一Java实例41请求，用合成模型闩锁保持20活动/20等待；第41立即429服务繁忙；等待者真实10s后20个429等待超时；放行20活动并完成；分母41、20成功/21预期429、配额20、输入0、active/waiting0。不能用普通10并发mix代替。

600s边界：TaskRunner构造器已接收 `ScheduledExecutorService`，但实际watchdog按该调度器的真实delay运行，`TestClock.advanceMono*` 单独不能触发。新增可控调度器仅测试注入，仍保留 `TaskSettings.defaults().ragTaskMaxSeconds=600s`，验证599s pending、600.5s timeout/system_error、清输入/释放槽/迟到finish被拒绝；可附一例真实600s墙钟，但必须区分模拟时钟与墙钟证据。不得把60ms配置测试标作600s。

24h边界：真实受理与storeInputs校验expires_at-created_at=24h；推进墙钟至23:59:59、恰好24h、24h+ε，按冻结Python `<`/`>`边界断言dry-run零写/执行清理、有效pending可恢复、过期无输入安全判死。终态即清另验，不等待TTL；源码哨兵扫任务/会话/消息/审计/备份的合成标记（仅输出命中计数）。重放句柄TTL用单调时钟299s/300s/+ε并按原比较符断言，过期后DB重建同身份且上游不再调用。

## 后续准确命令与实施顺序

**以下均未运行。先收到主Agent门禁、配置显式PG并排定独占窗口。** 当前PG/JDK/venv路径只确认文件存在，未确认服务状态/身份/版本。

现有定向回归的真实命令（会创建并最终删除自己的合成测试数据库；不保留故障恢复资产的场景不能直接交给自动teardown）：

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
# 主Agent设置 DPDK_TEST_PG_URL/USER/PASSWORD/DATA_DIR；不得打印口令或source旧.env。
./backend-java/mvnw -f backend-java/pom.xml -B -Dtest=TaskRecoveryTwoProcessTest,TaskRunnerConcurrencyTest,TaskIpClosureTest,TaskProbeReplayTest,RealAgentLedgerIntegrationTest,ExplainRecoveryIntegrationTest,OperationsAdaptersIntegrationTest test
```

结果必须读Surefire确认目标测试实际执行，不能把PG环境缺失导致skip算通过。README中的测试实例是55481、角色p1_java、data_directory=`$PWD/.migration-artifacts/p1/pgdata`，需要主Agent现场确认后使用；P5容量建议新专用实例/库和自己的guard，不能直接复用会被旧runner TRUNCATE的P0 benchmark库。

P0现有normalmix命令仅说明准确历史入口，**不应在P5直接执行**（会清空固定P0 benchmark库并覆盖J0-02证据，且仍ASGI）：

```sh
.migration-artifacts/p0/venv/bin/python migration/oracle/performance/run.py benchmark --rounds 3 --warmup 120 --measurement 600 --model-delay 1 --retrieval-delay 0.05 --mcp-delay 0.2 --label baseline
```

新TCP容量/故障CLI目前不存在，不能给出伪装成可执行的命令。P5实现顺序为：

1. 在J5-02 owner目录实现独立seed/身份guard、Python server、Java测试装配、共同驱动/对齐采样/比较器（若需要改生产代码或根构建，交主Agent集成），为每个CLI写真实`--help`与README命令；只用新证据目录。
2. 负例先证实guard拒绝旧库/缺配置、比较器拒绝遗漏轮次/超时剔除/错组合资源/错账本；短qualification仅验入口，不充当正式容量。
3. 顺序执行同环境Python与Java各3轮normalmix，再做41请求队列、双JVM SIGKILL矩阵、600s/24h/TTL边界；故障与容量不可互相干扰。
4. 逐轮比较、PG业务总账与网络禁止断言、失败保留、实现/自动/场景/用户验收分开；主Agent据证据放行。本预检不设虚构CLI名称，不降低阈值。

## 验证与恢复点

本次仅执行 `pwd`、`git status --short`、`rg`/`sed`/`cat`/`ls` 读取，不查询数据库、不调用真实上游。唯一新增文件为本handoff。无本owner新进程/端口/容器；无业务/测试脚本改动，无提交/推送。回退仅移除本计划文件；不能删除他人运行资产或恢复数据库。等待主Agent P4/P5门禁消息再实施。
