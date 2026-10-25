# Java迁移唯一任务状态

更新时间：2026-10-25。本文件由主Agent单写；历史细节放handoff/evidence，不在此追加日志。

## 当前授权与阶段

- 当前授权：2026-10-25 用户以任务书直接预授权 P1+P2 连续批次（U11，/goal 启动）：J1-01～J1-04、J2-01～J2-05；P1工程门禁通过后自动进入P2无需中途确认；允许必要开发依赖、独立测试PG、合成数据、本地模型/MCP替身、必要验证、功能分支集成、正常提交与及时推送、React与Java联调最小测试接线。工程门禁不等于用户验收；验收由用户批次结束后统一进行；无硬截止，提前完成即收口。
- 明确不授权：P3及后续阶段、合并main、部署、修改原项目/原部署/业务库、调用真实模型/embedding/评测服务、删除恢复资产。
- 当前实施阶段：U11 批次实现已交付；用户于2026-10-25要求验收P0/P1/P2。对 c916e2d 的独立复核结论：**P0保留通过；P1/P2暂不通过**，规范轴3项、规格轴5项待关闭。既有“工程门禁通过/0阻断”保留为历史，不代表本轮验收结论。集成分支 codex/p1-p2-longrun；报告 evidence/P0-P2-acceptance-20261025/README.md。
- 功能 oracle：本仓库冻结 Python 副本；初始 HEAD `80b29135faddd16d5e39fdbf0633291ea6a20489`，源来源 manifest 见 COPY_REPORT。不持续吸收原目录变化。
- Git：当前分支 `codex/p1-p2-longrun`，origin 与用户指定仓库相符；本轮验收前本地与远程 c916e2d4f8e642cd163bcc0808a5bd20b224f339 一致。P0历史推送证据仍见 evidence/P0/push-verification.json。
- 当前 Java 构建：本轮带显式PG执行 verify，298项、0失败/0错误/0跳过；真实HTTP探针25/32通过、7项差异；前端102项及build通过。全绿套件未覆盖本轮发现的装配/输入/结果/配置缺陷。
- 所有权：独立规范/规格审查worker均只读收口；STATUS/MAIN仍主Agent单写。本轮应用/fake MCP/Vite/独立Chrome/PG均已停止。专属PG数据、合成验收库和恢复资产保留；未修改业务实现。
- 数据库迁移单一 owner：主 Agent；本轮只运行既有迁移和合成验证，不改DDL。测试辅助入口身份预检缺口为ST-01，不能用应用迁移预检代替。

## 任务状态表

所有 `planned` 项尚未分派实现。规划worker已结束，不算实现owner。实际分派时填写owner、工作树、契约版本和证据。

| ID | 状态 | 实现owner | 实现 | 自动验证 | 场景/用户验收 | 证据 |
|---|---|---|---|---|---|---|
| J0-01 | accepted | p0_contracts；主Agent最终接收 | P0契约最终冻结 | 378文件/全映射/10静态护栏通过 | P0范围合成通过 / 用户验收通过（U10） | evidence/J0-01/final-freeze.md |
| J0-02 | accepted | p0_oracle、p0_performance；主Agent最终接收 | p0-v2/合成基线交付 | 双库186×2、11PG、40CLI、三轮完整测量通过 | P0范围合成通过 / 用户验收通过（U10） | evidence/P0/phase-report.md |
| J1-01 | implemented | 主Agent（集成者窗口） | 已集成；测试辅助入口有ST-01预检缺口 | 既有套件通过；本轮298/298 | 迁移路径通过；错误预期仍建库已复现，待修 | evidence/J1-01/build-and-paths.md；evidence/P0-P2-acceptance-20261025/ |
| J1-02 | implemented | j1-02 worker；主Agent集成 | 已集成（1627178）；SP-01装配缺陷待修 | 既有本切片83/83；本轮全量298/298 | embedding单独配置启动失败已复现 | evidence/J1-02/；evidence/P0-P2-acceptance-20261025/ |
| J1-03 | implemented | j1-03 worker；主Agent集成 | 完成并已集成（c00eb99） | 通过（本切片29项含10项合成PG；集成后全量147/147） | 未执行 | evidence/J1-03/ |
| J1-04 | implemented | 主Agent | 完成：POC切片+全链路集成（真实检索+固定模型替身） | 通过（本卡12/12；全量150/150） | 未执行 | evidence/J1-04/chain-samples.md |
| J2-01 | implemented | j2-01 worker；主Agent集成 | 完成并已集成（17b5c95） | 通过（本卡33/33；全量183/183；golden 19条零差异） | 未执行 | evidence/J2-01/ |
| J2-02 | implemented | j2-02 worker；主Agent集成 | 完成并已集成（f92ccab） | 通过（本卡35/35；全量218/218；golden 36条对照；IP-1/2/3如实登记） | 未执行 | evidence/J2-02/ |
| J2-03 | implemented | j2-03 worker；主Agent集成 | 完成并已集成 | 通过（本卡22/22；全量240/240；golden 28条，CSV字节级一致） | 未执行 | evidence/J2-03/ |
| J2-04 | implemented | j2-04 worker；主Agent集成 | 已集成（69fef5e）；SP-02/03/04待修 | 既有本卡21/21、全量298/298；新边界探针发现差异 | 正常提交/计分通过；JSON/假值结果/环境配额失败 | evidence/J2-04/；evidence/P0-P2-acceptance-20261025/ |
| P2回归 | review | p2-reg worker；主Agent独立复核 | 真实闭环可运行；ST-02/03证据工具待修 | 历史DIFF=0不足以证明成功面；本轮严格HTTP25/32、7项差异 | 教师/学生浏览器基本闭环通过；默认联调SP-05失败 | evidence/P2-regression/；evidence/P0-P2-acceptance-20261025/ |
| J2-05 | implemented | j2-05 worker；主Agent集成 | 完成并已集成（含MessageInput扩展） | 通过（本卡37/37；全量277/277；golden 33条含模板字节级） | 未执行 | evidence/J2-05/ |
| J3-01 | planned | — | 未开始 | 未执行 | 未执行 | — |
| J3-02 | planned | — | 未开始 | 未执行 | 未执行 | — |
| J3-03 | planned | — | 未开始 | 未执行 | 未执行 | — |
| J3-04 | planned | — | 未开始 | 未执行 | 未执行 | — |
| J4-01 | planned | — | 未开始 | 未执行 | 未执行 | — |
| J4-02 | planned | — | 未开始 | 未执行 | 未执行 | — |
| J4-03 | planned | — | 未开始 | 未执行 | 未执行 | — |
| J5-01 | planned | — | 未开始 | 未执行 | 未执行 | — |
| J5-02 | planned | — | 未开始 | 未执行 | 未执行 | — |
| J5-03 | planned | — | 未开始 | 未执行 | 未执行 | — |
| J5-04 | planned | — | 未开始 | 未执行 | 未执行 | — |

## 阶段放行记录

| 阶段 | 实施授权依据 | 自动/场景结果 | 用户验收 | 下一阶段授权 |
|---|---|---|---|---|
| P0 | 2026-10-24直接授权J0-01/J0-02；U08/U09确认安全差异和门槛 | 自动/P0合成/两轴审查通过；Java/真实质量未验 | 2026-10-25登记用户验收通过（U10） | 无 |
| P1 | 2026-10-25 U11直接授权（P1+P2连续批次；工程门禁通过后自动进P2） | 历史四卡150/150与双轴0阻断；本轮全量298/298，但ST-01测试预检及SP-01 embedding单独装配复核失败。**本轮复核不通过**，见evidence/P0-P2-acceptance-20261025/ | 未登记用户验收通过 | P2此前已按预授权实施，当前先修复复验 |
| P2 | 2026-10-25 U11预授权随P1工程门禁自动生效 | 历史五卡298/298；本轮HTTP25/32，浏览器基本闭环通过；SP-02～05及ST-02/03阻断，历史DIFF=0证明不足。**本轮复核不通过**，同上 | 未登记用户验收通过 | 无（P3未授权） |
| P3 | 待授权 | 未执行 | 未验收 | 无 |
| P4 | 待授权 | 未执行 | 未验收 | 无 |
| P5 | 待授权 | 未执行 | 未验收 | 无 |

## 当前阻塞/待决

- P0无新增阻塞：本轮静态/差分器19项及CLI40项通过，冻结黄金SHA一致；历史长负载未重跑，保留U10既有验收。
- P1/P2共有8项待关闭：ST-01测试先建库后验身份；ST-02重放判定器可假通过；ST-03重放依赖旧worktree/临时文件；SP-01 embedding单独配置启动失败；SP-02 JSON空体/尾随垃圾；SP-03 results:false/0丢成绩；SP-04 GRADING_*未消费；SP-05默认Vite5174与CORS5173不一致。逐项复现和修复验收见新报告。
- integration_pending仍按PLAN留P3：停号取消任务、活动会话删除、TaskGradeParticipant。完整chat/SSE/恢复/讲解、全矩阵/容量、zhparser、真实上游质量和生产schema/切换仍未验证。
- 正常提交推送按用户仓库约定/U11执行，仅提交当前验收文档与脱敏证据，不合并main或部署。

## 下一动作

本轮只验收和报告，未修实现。下一实施批次先关闭ST-01与SP-01，再修P2输入/评测结果/配置/联调和严格重放，按新报告复验后再申请用户验收。不进入P3，不把既有套件全绿或历史DIFF=0替代缺陷关闭。
