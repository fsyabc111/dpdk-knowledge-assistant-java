# Java迁移唯一任务状态

更新时间：2026-10-25。本文件由主Agent单写；历史细节放handoff/evidence，不在此追加日志。

## 当前授权与阶段

- 当前授权：2026-10-25 用户以任务书直接预授权 P1+P2 连续批次（U11，/goal 启动）：J1-01～J1-04、J2-01～J2-05；P1工程门禁通过后自动进入P2无需中途确认；允许必要开发依赖、独立测试PG、合成数据、本地模型/MCP替身、必要验证、功能分支集成、正常提交与及时推送、React与Java联调最小测试接线。工程门禁不等于用户验收；验收由用户批次结束后统一进行；无硬截止，提前完成即收口。
- 明确不授权：P3及后续阶段、合并main、部署、修改原项目/原部署/业务库、调用真实模型/embedding/评测服务、删除恢复资产。
- 当前实施阶段：P1实施中（集成分支 codex/p1-p2-longrun，基线 d982f3a）。
- 功能 oracle：本仓库冻结 Python 副本；初始 HEAD `80b29135faddd16d5e39fdbf0633291ea6a20489`，源来源 manifest 见 COPY_REPORT。不持续吸收原目录变化。
- Git：干净 main 基线核验后创建 `codex/p0-baseline-oracle`；origin 与用户指定仓库相符；已按U10推送同名远程分支，验收内容提交d96bf4c的本地/远程SHA一致；记录见evidence/P0/push-verification.json。
- 当前 Java 构建：不存在，P0 不实现 Java；历史 Python 结果不计本轮验证。
- 所有权：本阶段worker均已收口并交回窗口，当前由主Agent负责公共集成；STATUS/MAIN/DECISIONS仍主Agent单写。当前无在跑worker/应用/PG，数据与恢复资产保留。
- 数据库迁移单一 owner：主 Agent（后续 J1-01 集成窗口）；P0 仅设计和合成验证，不写 Java DDL/业务实现。

## 任务状态表

所有 `planned` 项尚未分派实现。规划worker已结束，不算实现owner。实际分派时填写owner、工作树、契约版本和证据。

| ID | 状态 | 实现owner | 实现 | 自动验证 | 场景/用户验收 | 证据 |
|---|---|---|---|---|---|---|
| J0-01 | accepted | p0_contracts；主Agent最终接收 | P0契约最终冻结 | 378文件/全映射/10静态护栏通过 | P0范围合成通过 / 用户验收通过（U10） | evidence/J0-01/final-freeze.md |
| J0-02 | accepted | p0_oracle、p0_performance；主Agent最终接收 | p0-v2/合成基线交付 | 双库186×2、11PG、40CLI、三轮完整测量通过 | P0范围合成通过 / 用户验收通过（U10） | evidence/P0/phase-report.md |
| J1-01 | in_progress | 主Agent（集成者窗口） | 切片1完成：骨架/pom/wrapper、fail-closed配置、三路径迁移链（B0015/V0014/V0015）、身份预检、18端口契约接口 | 通过（26/26：三路径+重复启动+拒绝负例+身份负例+uuid5/时钟/错误映射） | 未执行 | evidence/J1-01/build-and-paths.md |
| J1-02 | implemented | j1-02 worker；主Agent集成 | 完成并已集成（1627178） | 通过（本切片83/83；集成后全量147/147） | 未执行 | evidence/J1-02/ |
| J1-03 | implemented | j1-03 worker；主Agent集成 | 完成并已集成（c00eb99） | 通过（本切片29项含10项合成PG；集成后全量147/147） | 未执行 | evidence/J1-03/ |
| J1-04 | implemented | 主Agent | 完成：POC切片+全链路集成（真实检索+固定模型替身） | 通过（本卡12/12；全量150/150） | 未执行 | evidence/J1-04/chain-samples.md |
| J2-01 | implemented | j2-01 worker；主Agent集成 | 完成并已集成（17b5c95） | 通过（本卡33/33；全量183/183；golden 19条零差异） | 未执行 | evidence/J2-01/ |
| J2-02 | implemented | j2-02 worker；主Agent集成 | 完成并已集成（f92ccab） | 通过（本卡35/35；全量218/218；golden 36条对照；IP-1/2/3如实登记） | 未执行 | evidence/J2-02/ |
| J2-03 | implemented | j2-03 worker；主Agent集成 | 完成并已集成 | 通过（本卡22/22；全量240/240；golden 28条，CSV字节级一致） | 未执行 | evidence/J2-03/ |
| J2-04 | implemented | j2-04 worker；主Agent集成 | 完成并已集成（69fef5e） | 通过（本卡21/21；全量298/298；fake MCP 调用计数/停号竞争实测） | 未执行 | evidence/J2-04/ |
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
| P1 | 2026-10-25 U11直接授权（P1+P2连续批次；工程门禁通过后自动进P2） | 四卡自动验证 150/150；独立审查双轴通过（0阻断）；**P1工程门禁通过，用户验收待进行**（2026-10-25登记，证据evidence/P1/phase-report.md） | 未验收（批次结束统一进行） | P2已按预授权自动生效 |
| P2 | 2026-10-25 U11预授权随P1工程门禁自动生效 | 实施启动（J2-01起） | 未验收（批次结束统一进行） | 无（P3未授权） |
| P3 | 待授权 | 未执行 | 未验收 | 无 |
| P4 | 待授权 | 未执行 | 未验收 | 无 |
| P5 | 待授权 | 未执行 | 未验收 | 无 |

## 当前阻塞/待决

- 无已知P0必修阻塞；两条审查线发现均关闭，失败及复审历史保留。
- 用户已确认安全差异U08、门槛U09；P0阶段用户验收已通过（U10）；P1放行仍待用户直接确认。
- 后续未验证：28写/状态路由完整重试并发组合、全认证角色组合、Java实际构建/Flyway三路径、React/TCP、zhparser、外部DPDK/harness、真实模型/embedding质量及生产schema。未执行不计通过。
- U10已明确授权本轮推送，取代此前P0不推送限制；仅发布codex/p0-baseline-oracle，不合并main或部署。

## 下一动作

**P1 工程门禁已通过（2026-10-25，五项齐备，双轴审查 0 阻断，推送核验 SHA 一致）。按 U11 预授权自动进入 P2**：J2-01（认证，worktree 派工）→ J2-02 → J2-03∥J2-05 → J2-04 → 集成回归 → P2 双轴审查与报告。不进入 P3。用户验收批次结束统一进行。
