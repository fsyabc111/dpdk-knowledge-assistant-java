# Java迁移唯一任务状态

更新时间：2026-10-28。主Agent单写；历史状态保存在handoffs/archive/STATUS-U12-P5-pre-resume-20261028.md。

## 当前授权与阶段

- U12直接授权续接LONGRUN_P3_RESUME：P3→P4→P5隔离工程交付与J5-04准备，工程门禁通过自动推进，最多3 worker，小步提交及时推送，持续交接。
- P3、P4工程门禁已通过；P5场景验收进行中，总门禁未通过。实现、自动验证、场景验收与用户验收分开，Agent不代签用户验收。
- 本批次禁止：真实模型/embedding/真实harness验收、合并main、生产切换、Python退役、删除恢复资产，原项目/部署/业务数据库写入。
- 当前分支codex/p3-p5-longrun，origin为用户指定新仓库；生产源码冻结ff59cdab65c2c8977f3f9c45701b773ad9116ccd，之后仅验收物料/文档。推送与远程核验见MAIN。
- 用户明确要求补齐源过程指标，不接受12 unavailable或持久gauge替代。16领域counter/histogram与真实业务hooks已补，正式Application实际因果验收16/16、25断言；独立runtime4映射与单case比较已获独立双轴复审并由主Agent按明确边界判定。

## 任务状态

| ID | 实现与工程验证 | 用户验收与证据 |
|---|---|---|
| J0-01/02 | P0冻结契约/oracle通过 | U10用户通过；evidence/P0/ |
| J1-01～04、J2-01～05 | 已实现；R段8阻断关闭，纳入当前组合验证 | 用户未登记；evidence/U12-R-segment-20261025/ |
| J3-01～04 | 已实现；P3源码5d0d6a3、591/591、两轴4阻断关闭 | 工程通过、用户未验；evidence/P3/与逐卡handoff |
| J4-01～03 | 已实现；P4 f020efa、707/707、同jar CLI2/16 JVM、CI四job与两轴通过 | 工程通过、用户未验；evidence/P4/ |
| J5-01 | 工程通过：full07 186观察/198步；原raw CLI rc1保留，唯一metrics政策后全trace相等；225/225；原22+最终5浏览器保存场景；16域25实际因果；政策双轴无阻断 | 用户未验；evidence/J5-01/ |
| J5-02 | Python正式3×(120+600)s有效；Java正式及双端判定未执行；默认clock3项及完整14场景已实际通过（11 SIGKILL/41queue/lifecycle），同协议双端短资格通过，Java登录充分预热后仍超限，当前窄性能诊断，正式72分钟未启动 | 用户未验；evidence/J5-02/ |
| J5-03 | 执行器/守卫/备份完整row proof已交；P4不可变镜像与归档保留；候选构建和实际冷启/0013升级/恢复/应用回退未执行 | 用户未验；evidence/J5-03/ |
| J5-04 | Maven100/npm213实际版本/许可声明，P4/base容器7份库存已交；候选manifest、缺失许可全文、接手复现收口中 | 仅交付准备；不含切换/退役/删除 |

## 当前代码门禁

- 固定ff59完整verify：777发现，774执行通过，0失败/0错误，3项DefaultBoundaryPgTest需owned显式入口；后续clock已实际3/3过，待整合证据，不把skip本身算pass。
- 同jar SHA14caee2d2ef692e8fd7bbe9ca119aaba6e78a615ea0ce6b2f2200b81f2ca722e，CLI2/2含16新JVM全过；前端102/102与build通过。
- GitHub CI40316421823固定ff59成功：unit/frontend/deployment-materials/postgres四job通过。远程CI不等实际Docker部署。
- 独立两轴复审固定ff59关闭规范3/规格2代码阻断，无新增代码阻断；总场景门禁仍未过。evidence/P5/validation-code.json及review-*-recheck.md。
- O01迟到活进程usage收据与O02失败指标分类的独立观测修复见DECISIONS；未回执SIGKILL的durable unknown成本票据不作保证，不扩大业务/权限/计分语义。

## 下一动作与资源

1. J5-01工程核心已过；保留原raw rc1、限定metrics政策和独立实际因果证据，不改黄金/核心比较器/业务数组。
2. J5-02全部14故障场景已过；helper修复改变协议指纹，保留旧Python基线，双端资格已过但Java登录p95充分预热仍超冻结门槛，先窄诊断/修复，再新Python+Java各三轮合计72分钟，严格配对判定。冻结阈值不变，不缩时/隐藏失败。
3. 最终源码重点浏览器回归；J5-03候选不可变构建后实际cold/0013/backup_restore/rollback与完整业务proof。
4. P5阶段报告、最终独立双轴、交付manifest/许可/README接手复现、逐卡handoff、远程SHA相等及干净工作区。

专属native PG127.0.0.1:55481；所有建删/DDL先四项显式配置+身份预检。口令仅在本地ignore环境中；禁止回退旧.env。Docker仅新colima-java-u12显式context，当前Stopped；旧dpdk保持Stopped，全局context不变。故障/容量独占期间不构建/浏览器/容器/其他PG工作。所有失败、DB、镜像、备份、恢复资产保留。精确路径/恢复命令见MAIN。
