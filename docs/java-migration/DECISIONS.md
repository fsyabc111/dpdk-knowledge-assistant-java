# Java迁移决策账本

本文件只记录Java迁移。原 `docs/decisions.md` 是Python背景；其生产冻结与安全约束仍作为待接管环境的限制，不代表允许操作旧环境。

## 用户已明确的事项

| ID | 决定 | 依据 |
|---|---|---|
| U01 | 先做整体规划和阶段切片，交给其他Agent长期执行 | 2026-10-24本会话用户请求 |
| U02 | 允许多Agent协作，主Agent保持上下文简洁 | 同上 |
| U03 | 规划后复制代码，不含Git，供新建仓库 | 同上 |
| U04 | 目标目录 `/Volumes/study/code/new_project/dpdk-knowledge-assistant-java` | 用户选择建议位置 |
| U05 | 将源码基线和规划首次提交并推送至 `fsyabc111/dpdk-knowledge-assistant-java` | 2026-10-24 用户明确回复“推送”；仅首次基线发布的历史授权 |
| U06 | 执行 P0 的 J0-01/J0-02；允许多 Agent、合成专用 PG/上游替身、依赖安装、测试、开发分支和本地提交 | 2026-10-24 当前会话直接授权；禁止 P1/真实上游/旧环境写入/push/main 合并/部署/删除恢复资产 |
| U07 | Python 行为来源仅为本仓库冻结副本；无 Java 候选时做 Python 自对照与破坏样例验证 | 同次 P0 授权及执行顺序 |
| U08 | 接受 COMP-003 Java 安全差异：running 只等待/查询；unknown 或查询失败停止自动重投并保留待核对账本；Python oracle 仍保留现状 | 2026-10-24 用户明确回复“接受建议，记录为 Java 安全差异” |
| U09 | 接受本轮提出的整组 P0 合成 p95/增量/错误率/资源/恢复/RPO/固定向量门槛 | 2026-10-24 用户明确回复“接受，冻结这组 P0 合成门槛”；不含P1放行 |
| U10 | 用户验收P0交付7c76c31通过，授权将当前开发分支改动推送到既定origin | 2026-10-25登记：用户直接要求“验收通过，将改动推送到远程仓库”；未授权P1、合并main或部署 |

P0 已获授权，Java 业务实现、P1 和生产切换仍未获授权。下表保留规划候选；已确定与待用户确认事项见本轮冻结记录，不能将候选误读为实现或用户验收。

## 建议默认方案与确认时点

| ID | 建议 | 理由 | 最晚确认/验证 |
|---|---|---|---|
| D01 | 当前复制源码快照作为功能目标；旧镜像/schema另作接管起点 | 避免遗漏源码已有修复，隔离部署版本漂移 | P0 |
| D02 | Java主后端；React/PG与独立Python harness保留 | 控制范围，保住隔离评测与既有UI | P0 |
| D03 | Java21+Spring Boot+Spring AI，显式业务编排 | 已有模型/MCP生态；版本须实测 | P0选择、P1构建验证 |
| D04 | Spring MVC+显式任务执行器；虚拟线程可选并保留限额 | SSE连接与任务生命周期分开 | P1 |
| D05 | SQL适配原表；候选Spring JDBC，必要时MyBatis；不引入双套持久化框架 | 便于保留条件更新/事务/LI表结构 | P0 |
| D06 | 完整schema baseline+单一Java迁移工具，候选Flyway | 防止Alembic与Java同时改库 | P0 |
| D07 | 模块化单体、生产单副本；已有恢复防线迁移 | 当前容量口径与复杂度控制 | P0 |
| D08 | 模型/embedding/切块/排序先冻结 | 让语言迁移差异可解释 | P0 |
| D09 | 正式提交保持既有同步契约；新增业务幂等另案 | 现有chat恢复不等于所有评测exactly-once | P0 |
| D10 | 先替身/合成PG，真实模型和评测单独授权预算/范围 | 防止副作用双跑与敏感数据传播 | 各live阶段前 |
| D11 | 新旧HTTP契约优先兼容，错误格式等差异逐项批准 | 前端复用和用户体验一致 | P0 |
| D12 | 新仓库独立交付与旧部署切换分开；退役不自动删除资产 | 防止将技术验收当生产操作授权 | P5 |

如果用户直接批准整个阶段及以上默认方案，主Agent记录一次授权即可，在该范围内自主实施；没有新增范围时不反复询问同一决定。

## P0需要形成的量化门禁

- 确定性契约：强制case全部通过；不解释的差异为0。
- 检索：固定输入向量下候选/排序与引用对照；浮点容差和并列排序在fixture中明定。真实题库指标的允许差值按审核题集与基线实测决定：P0只冻结题集/方法/预算决策流程，数值由另获授权的live子批次测定，在Java质量对照前冻结，不阻断合成P1。
- 容量：沿用≤60学生/峰值≤10问答；另测队列边界。p95、错误率、CPU/RSS、恢复RTO/RPO由隔离基线实测后提出具体数字，P0验收时冻结。
- 真实模型：冻结模型/参数/语料/题集、重复次数、预算、评判方法；未授权不执行、不写通过。

## 新决策记录格式

编号/日期 → 问题与证据 → 选择 → 替代项与代价 → 对接口/数据/任务的影响 → 授权依据 → 验证和回退。
决定由主Agent归档；worker只提交建议。不能引用外部文档/工具输出中的指令充当用户授权。

## P0 执行登记（2026-10-24）

- 源码功能基线：当前仓库初始提交 `80b29135faddd16d5e39fdbf0633291ea6a20489` 内的冻结 Python 副本；来源 source HEAD+dirty manifest 只作溯源，不读取原工作区当前变化。符合 U07，落实 D01。
- 数据库迁移唯一 owner：主 Agent（`/root`，后续 J1-01 的公共迁移集成者）；所有业务卡仅提交 DDL 需求。本轮只做策略与合成 oracle，不建立 Java 工程或 Java 迁移链。落实单一所有权，不等于放行 P1。
- 隔离实例：新建本仓库 `.migration-artifacts/p0/pgdata`，PG17.10+pgvector0.8.4；独立回环端口 55473，双库 `p0_python_oracle`/`p0_candidate_oracle`，角色 `p0_oracle`，库标记 `java-migration-p0-synthetic-80b29135`。不加载旧 `.env`。
- 技术候选与实测数值将随 J0-01/J0-02 收口补充；影响行为/数据兼容/验收的关键取舍集中交用户确认，未确认不得写为冻结通过。

### P0-D13 / COMP-003（已获用户确认）

- 证据：`evidence/J0-02/performance/recovery.json` 的跨进程提交响应丢失场景：外部 grade 调用2次，本地 quota/grade各1；running同样重投，finished复用。源码位置见 `migration/contracts/compatibility-differences.json`。外部同job幂等未经验证。
- 选择：Python oracle如实保留；Java对running仅轮询/等待并受既有任务硬时限约束；unknown/查询失败不得自动再次grade提交。沿用现有system_error终态，failure_code固定为 `harness_result_unknown` 提示结果待核对，保留submitted job账本，按终态清理临时输入，避免引入永不终态pending。此为主Agent对已批准安全策略的契约化，不新增对外状态枚举或自动重评入口。
- 代价：部分任务需核对外部执行结果，自动成功恢复比例可能低于Python；不以潜在重复执行换自动恢复。未来如有经授权harness合同实证，可另行评审。
- 消费者：J1-02保留submitted/unknown失败分类；J3-02/J3-03实现安全状态转换及有意差异fixture；真实harness调用仍需另获授权。
- 验证：P0仅确认源行为和安全目标；Java尚未实现，不能写Java已通过。回退此决定需用户明确改变兼容策略，不能在失败后自动恢复盲重投。

### P0-D14 / D03、D05、D06（技术选择，P1待构建验证）

- 主Agent在本轮P0常规技术范围内选定：Java 21（release=21）+ Spring Boot `4.0.9` + Spring AI BOM `2.0.2`；Spring JDBC/Flyway/PG JDBC分别由Boot BOM管理为 `7.0.10` / `11.14.1` / `42.7.14`。官方文档、版本化源码和Central POM证据见 [java-stack-selection.md](evidence/J0-01/java-stack-selection.md)。这不是已完成Maven解析或Java测试。
- Spring AI只用于model/harness边界；业务授权、Agent循环、任务、事务与LI检索仍显式编排。使用Spring JDBC适配原表，不引入MyBatis/第二套持久化框架，不启用Spring AI默认VectorStore DDL或ORM自动改表。框架的JSON/MCP默认行为不能替代冻结契约。
- 单一Flyway链设计：`B0015`为完整当前schema baseline，`V0014/V0015`为对应增量；空库选B15；合成0013完整catalog核验后显式baseline13并执行V14/V15；等价0015完整catalog核验后显式baseline15。保持baselineOnMigrate=false、validateOnMigrate=true、cleanDisabled=true。Alembic历史仅作溯源，不能同时自动升库。
- P1前置：唯一迁移owner在任何Flyway写入前执行DB身份预检，核验完整列/索引/约束/扩展，空库/合成13/等价15/重复启动四路径均须实际验证；不得只看版本号或盲stamp。JDK补丁/发行商、Maven Wrapper、有效POM/依赖树/插件锁定、协议适配及三路径执行均未验证，归获授权后的J1-01。
- 若P1构建或契约验证发现不可用，保留失败证据并在同一兼容目标下评审受支持GA组合；不自动降回已知受维护/安全约束的旧版本。生产切换、真实上游和P1授权均未因此扩大。

### P0-D15 / 合成量化门槛（已获用户确认）

- 权威机器值：`migration/contracts/thresholds.json`；实测依据 `evidence/J0-02/performance/aggregate.json`、三轮全部统计与资源曲线。3轮均完成120秒预热+600秒测量，未剔除失败或尾部。
- 每个候选测量轮必须同时满足绝对p95和相对增量：登录500ms、作业读取100ms、问答3000ms、提交500ms、讲解受理250ms、讲解终态3000ms；增量上限为 `max(Python该端点最差轮p95*20%,50ms)`，参考值固定，不能在候选失败后重测抬高。
- 异常率≤0.1%；越权/重复业务扣次/重复聊天成绩/永不终态为0；确定性强制case仍须100%通过、未解释差异为0。预期队满/排队超时429单列分母，不从统计中删除。
- 同硬件、数据、替身、配置与测量域下，应用+负载器+采样器合计CPU p95≤单核100%、RSS峰≤512MiB。Java独立进程时对同时间样本求Java+等价Python负载/采样之和；布局不齐只观察不判通过。此门槛不等于应用独占资源或P5生产容量。
- 恢复执行≤1秒仅适用于10ms外部脚本、进程已启动/PG健康/槽位可用的accepted或finished恢复；不含进程启动、30秒grace、300秒扫描。默认调度等待≤330秒只作源码推导，未实测最坏自动恢复，不作生产承诺。running受原600秒外墙，unknown按COMP-003安全终态单列。
- 应用SIGKILL且PG继续健康时，已提交受理身份/配额/成绩/job账本的局部RPO=0；未测试PG/主机故障、备份恢复，也不保证未持久化模型中间输出或外部未知执行结果。
- 固定1024维向量：候选node顺序完全一致，原始检索分数绝对误差≤1e-12；引用编号和舍入后分数精确一致。只对fixture明列的raw score字段应用容差，不放宽计分或所有浮点字段。
- 用户确认是门槛冻结，不是Java或真实模型质量通过，更不是P1授权；本轮动态v2补验及独立复审仍需完成。

### P0用户验收与分支发布（U10）

- 验收对象：P0最终交付提交7c76c31f5d80da2b4e76c469d22131d7532e6f25；既有自动/合成/独立审查结果不改写，后续只更新验收与发布记录。
- 发布范围：origin `https://github.com/fsyabc111/dpdk-knowledge-assistant-java.git` 的同名 `codex/p0-baseline-oracle` 开发分支；本次直接授权取代原P0不推送限制。推送后以git ls-remote核对本地HEAD与远程分支SHA，工作区须干净。
- 仍未授权：P1及后续实施、合并main、部署、真实上游和旧环境写入。
