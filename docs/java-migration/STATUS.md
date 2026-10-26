# Java迁移唯一任务状态

更新时间：2026-10-26。本文件由主Agent单写；历史细节保留于handoff/evidence。

## 当前授权与阶段

- U12：用户已直接启动LONGRUN_P3_P5_DRAFT，并于本轮直接发送LONGRUN_P3_RESUME续接指令，解除暂停。允许P3→P4→P5隔离工程交付和J5-04准备；工程门禁通过自动推进，最多3个worker，小步提交推送、持续交接。
- 禁止项：真实模型/embedding/真实harness验收、合并main、生产切换、Python退役、删除恢复资产、原项目/原部署/业务库写入。用户验收不由Agent代签。
- 当前：P3与P4工程门禁已通过；P4源码f020efa，全量707/707、CLI2/2含16新JVM、远程CI四job、独立双轴复审均通过。P5已自动启动，真实全矩阵已采集但strict FAIL，正在修复；J5-02/03准备并行，正式容量和部署待依赖放行。
- R段已关闭ST-01～03/SP-01～05并通过独立双轴审查（19a2d47）；历史报告evidence/U12-R-segment-20261025/。P0用户验收通过保留；P1/P2/P3用户验收均未登记。
- 当前Git：codex/p3-p5-longrun，origin为用户指定新仓库。最近已核验推送2735147；后续切片见MAIN，待本轮集成提交再次推送核验。
- Python行为基线：本仓库初始80b29135faddd16d5e39fdbf0633291ea6a20489冻结副本；Python黄金只读，COMP-003按已批准Java安全差异另验。
- 本轮实际验证：Spring+PG装配18/18、模型worker84/84、真实Agent账本10/10、讲解23/23并另增1黄金测试；完整嵌入式HTTP/SSE探针通过（前端原client直连项新增后待全量复验）。前端102/102和build通过。
- 全量r1：581项5失败，失败证据保留；占槽测试容量/释放闩、旧SessionPort占位断言、前端探针过严terminal断言均已修。r2测试编译缺throws已修；r3显式PG全量verify **585/585，0失败/0错误/0跳过**，包含原前端client真实HTTP/SSE/multipart验证。此为工程/合成验证，非用户验收。
- 公共契约/配置/装配/DDL/STATUS/MAIN由主Agent单写。原PG55481专属实例保留运行；任何建删库均TestPg先验身份。全量/故障PG验证由主Agent排队。

## 任务状态

| ID | 状态 | owner/实现 | 自动与合成验证 | 用户验收/证据 |
|---|---|---|---|---|
| J0-01 | accepted | P0契约冻结 | 全映射与护栏历史通过 | U10通过；evidence/J0-01/ |
| J0-02 | accepted | P0-v2差分oracle | 双库186×2及PG/CLI/测量历史通过 | U10通过；evidence/P0/ |
| J1-01 | implemented | 主Agent；独立PG/构建/迁移 | R段ST-01已关闭 | 未登记；evidence/J1-01/、U12-R-segment-20261025/ |
| J1-02 | implemented | 模型/MCP端口 | R段SP-01已关闭；本轮富模型适配84项worker通过 | 未登记；evidence/J1-02/、J3-03/model-adapter.md |
| J1-03 | implemented | 只读检索 | 本轮Spring检索配置3项通过；本轮全量591/591 | 未登记；evidence/J1-03/ |
| J1-04 | implemented | 最小问答纵向切片 | 历史12项；正式入口已由J3-03接管 | 未登记；evidence/J1-04/ |
| J2-01 | implemented | 认证 | R段回归通过；本轮全量591/591 | 未登记；evidence/J2-01/ |
| J2-02 | implemented | 管理/班级树/审计 | IP-1已真实Spring+PG取消闭环 | 未登记；evidence/J2-02/、J3-03/task-wiring.md |
| J2-03 | implemented | 作业/汇总/CSV | R段回归通过；本轮全量591/591 | 未登记；evidence/J2-03/ |
| J2-04 | implemented | 正式提交/成绩 | SP-02～04已关闭；TaskGradeLedger接线修正 | 未登记；evidence/J2-04/ |
| J2-05 | implemented | 会话/配额/来源等 | SessionPort真实委托；会话删除组合保留源语义 | 未登记；evidence/J2-05/ |
| J3-01 | implemented | 原task worker+主集成 | 历史50项；本轮18装配+10真实Agent账本；本轮全量591/591 | 未登记；handoffs/J3-01.md |
| J3-02 | implemented | 原Agent worker+主集成 | 富消息适配、COMP-003安全终态已真实PG10项验证 | 未登记；handoffs/J3-02.md、P3-MODEL.md |
| J3-03 | implemented | chat worker+主集成 | 产品HTTP/SSE/引用/超时/用量已实现；本轮全量591/591；双轴待做 | 未登记；handoffs/J3-03.md（收口中） |
| J3-04 | implemented | explain worker+主集成 | 23项通过+1黄金增补；真第二JVM恢复，grade0 | 未登记；handoffs/J3-04.md、evidence/J3-04/ |
| J4-01 | implemented | index worker；主Agent集成 | 单卡52/52，合并树待最终复验 | 用户未验；evidence/J4-01/ |
| J4-02 | implemented | knowledge worker；主Agent集成 | 单卡19/19+组合真实闭环4项通过 | 用户未验；evidence/J4-02/、P4/workflow-index.md |
| J4-03 | implemented | operations/ops_integration+主Agent | 合并树698/698；真jar2/2含16新JVM；Node5/5；离线judge/运维真实接线 | 用户未验；evidence/J4-03/ |
| J5-01 | in_progress | acceptance_matrix+root | 真实186HTTP/198步，strict FAIL修复中；浏览器待做 | 未验 |
| J5-02 | in_progress | capacity | 入口/故障脚本纯测试通过；实际PG/容量待窗口 | 未验 |
| J5-03 | in_progress | operations | 守卫/合成快照/镜像准备；实际演练待做 | 未验 |
| J5-04 | in_progress | root+operations交付准备 | 实际Maven100/npm213依赖许可、manifest模板、运行/恢复/接手手册已交；最终源/场景待绑定 | 切换/退役/删除均未授权 |

## 阶段放行

| 阶段 | 实施授权 | 工程/合成状态 | 用户验收 | 下一步 |
|---|---|---|---|---|
| P0 | U08/U09/U10 | 冻结oracle与阈值，历史通过 | U10通过 | 保留基线 |
| P1/P2 | U11实施，U12修复复验 | 原独立复核8阻断经R段修复+双轴关闭，R段316/316、strict32/32、DIFF=0 | 未登记 | 本轮整体回归继续 |
| P3 | U12与本轮直接续接授权 | 591/591，两轴复审关闭4阻断；工程门禁通过 | 未验 | 报告→审查→修复复验 |
| P4 | U12条件预授权 | 707/707、Boot CLI2/16 JVM、远程CI四job与双轴复审通过 | 未验 | J4-01/02并行，J4-03集成 |
| P5 | U12条件预授权（隔离范围） | 已放行，隔离工程验收进行中 | 未验 | J5-01～03与04准备 |

## 当前阻塞与下一动作

- P4最终证据：evidence/P4/phase-report.md、validation-reviewed.json与两轴复审/CI增补，生产f020efa，CI40198306859四job成功。
- J5-01 full03 strict FAIL：用量、框架文档/HEAD/OPTIONS、指标需逐项修复，不改黄金/宽泛归一；6引用真实持久化helper及浏览器待补。
- J5-02正式72分钟双端TCP三轮容量、J5-03实际冷启/升级/恢复/回退按主Agent窗口；先做独立准备，不能声称通过。
- 四项PG显式且身份预检；Docker只使用新java-u12 context，旧环境不动。socket当前不可用，先继续本地独立工作。
- 精确资源与下一步见handoffs/MAIN.md；所有恢复资产保留。

### 2026-10-26当前续接补充

- P5 full06真实186/198已收敛到单一metrics响应family差异；全部持久时间、用量15/15、11真实事务专例/14上游向量/semantic已对齐，原raw verify仍rc1，不称全矩阵通过。扩展224已过，新增privacy/CSV为225待本轮结束证据。
- 用户已明确选择补齐源过程指标，禁止以原12项unavailable/持久gauge当豁免；ProcessMetrics端口ccf5e97，operations实现collector，root接业务事件与共享装配。
- 浏览器22个保存断言场景/快照：实际多角色、跨班、上传成绩、讲解grade调用不增、6引用与第6片段、旧release来源、断线刷新、反馈/认领记录/共享发布pending、operator成绩/CSV、Markdown消毒；SSE首帧新发现Spring7默认non-flushing，84e76b4修复，原红/最小真实TCP8项绿+实际浏览器首帧/取消复验通过。不是用户验收。
- 源前端负责人直属学生树缺陷单列known-source-ui-gap.md，未扩大范围改前端。
- Python TCP正式基线拟先独立3轮120+600秒（至少36分钟），Java3轮待指标源码冻结后同环境测；两端合并才可判容量。正式期间全机不构建/测试/浏览器/其他PG负载，caffeinate与时钟污染拒绝。当前尚未启动。
