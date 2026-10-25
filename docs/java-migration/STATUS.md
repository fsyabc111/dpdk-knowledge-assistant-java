# Java迁移唯一任务状态

更新时间：2026-10-25。本文件由主Agent单写；历史细节保留于handoff/evidence。

## 当前授权与阶段

- U12：用户已直接启动LONGRUN_P3_P5_DRAFT，并于本轮直接发送LONGRUN_P3_RESUME续接指令，解除暂停。允许P3→P4→P5隔离工程交付和J5-04准备；工程门禁通过自动推进，最多3个worker，小步提交推送、持续交接。
- 禁止项：真实模型/embedding/真实harness验收、合并main、生产切换、Python退役、删除恢复资产、原项目/原部署/业务库写入。用户验收不由Agent代签。
- 当前：P3集成/验证收口中；公共契约、Spring运行装配、J3-03和J3-04已实现，独立双轴审查尚未开始，P3未放行。P4/P5已获条件授权，尚未实施。
- R段已关闭ST-01～03/SP-01～05并通过独立双轴审查（19a2d47）；历史报告evidence/U12-R-segment-20261025/。P0用户验收通过保留；P1/P2/P3用户验收均未登记。
- 当前Git：codex/p3-p5-longrun，origin为用户指定新仓库。最近已核验推送b760ea4；后续切片见MAIN，待本轮集成提交再次推送核验。
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
| J1-03 | implemented | 只读检索 | 本轮Spring检索配置3项通过；全量待r3 | 未登记；evidence/J1-03/ |
| J1-04 | implemented | 最小问答纵向切片 | 历史12项；正式入口已由J3-03接管 | 未登记；evidence/J1-04/ |
| J2-01 | implemented | 认证 | R段回归通过；全量待r3 | 未登记；evidence/J2-01/ |
| J2-02 | implemented | 管理/班级树/审计 | IP-1已真实Spring+PG取消闭环 | 未登记；evidence/J2-02/、J3-03/task-wiring.md |
| J2-03 | implemented | 作业/汇总/CSV | R段回归通过；全量待r3 | 未登记；evidence/J2-03/ |
| J2-04 | implemented | 正式提交/成绩 | SP-02～04已关闭；TaskGradeLedger接线修正 | 未登记；evidence/J2-04/ |
| J2-05 | implemented | 会话/配额/来源等 | SessionPort真实委托；会话删除组合保留源语义 | 未登记；evidence/J2-05/ |
| J3-01 | review | 原task worker+主集成 | 历史50项；本轮18装配+10真实Agent账本；全量待r3 | 未登记；handoffs/J3-01.md |
| J3-02 | review | 原Agent worker+主集成 | 富消息适配、COMP-003安全终态已真实PG10项验证 | 未登记；handoffs/J3-02.md、P3-MODEL.md |
| J3-03 | review | chat worker+主集成 | 产品HTTP/SSE/引用/超时/用量已实现；全量待r3；双轴待做 | 未登记；handoffs/J3-03.md（收口中） |
| J3-04 | review | explain worker+主集成 | 23项通过+1黄金增补；真第二JVM恢复，grade0 | 未登记；handoffs/J3-04.md、evidence/J3-04/ |
| J4-01 | planned | 待P3门禁后派工 | 未执行 | 未验 |
| J4-02 | planned | 待P3门禁后派工 | 未执行 | 未验 |
| J4-03 | planned | 待P3门禁后派工 | 未执行 | 未验 |
| J5-01 | planned | 待P4门禁 | 未执行 | 未验 |
| J5-02 | planned | 待P5依赖 | 未执行 | 未验 |
| J5-03 | planned | 待P5依赖 | 未执行 | 未验 |
| J5-04 | planned | 只做交付准备 | 未执行 | 切换/退役/删除均未授权 |

## 阶段放行

| 阶段 | 实施授权 | 工程/合成状态 | 用户验收 | 下一步 |
|---|---|---|---|---|
| P0 | U08/U09/U10 | 冻结oracle与阈值，历史通过 | U10通过 | 保留基线 |
| P1/P2 | U11实施，U12修复复验 | 原独立复核8阻断经R段修复+双轴关闭，R段316/316、strict32/32、DIFF=0 | 未登记 | 本轮整体回归继续 |
| P3 | U12与本轮直接续接授权 | 四卡集成验证收口，尚未独立双轴审查，未放行 | 未验 | 报告→审查→修复复验 |
| P4 | U12条件预授权 | 等P3工程门禁；未执行 | 未验 | J4-01/02并行，J4-03集成 |
| P5 | U12条件预授权（隔离范围） | 等P4工程门禁；未执行 | 未验 | J5-01～03与04准备 |

## 当前阻塞与下一动作

- P3全量r3和原前端client直连已通过；阶段报告和独立规范/规格双轴审查仍须完成后才放行P4。
- 新发现初发竞争、空账本、COMP003盲重投、TaskGrade装配等已实现修复，局部证据见MAIN；以最终集成树全量和审查为关闭依据。
- P4平台缺口：框架404/405/500、安全头、CLI/就绪/留存/部署/CI待实施；不把“登记”当豁免。
- 当前Docker context为colima-dpdk-ext，socket不存在，旧dpdk profile停止；P5只能建立全新隔离资源，不启动/迁动旧profile。
- P5浏览器、全矩阵、容量、冷启动/0013升级/真实备份恢复/回退尚未执行。live质量和生产资格不在本批次。
