# 主 Agent 恢复点

- 日期：2026-10-25（U11批次启动）；目标：P1+P2连续批次（J1-01～J2-05），集成分支 `codex/p1-p2-longrun`（自 d982f3a 创建）。P1工程门禁通过自动进P2；不进P3、不合并main、不部署。及时提交推送为强制要求；批次结束统一用户验收。
- 上一轮P0已完成并用户验收（U10）；P0证据/evidence/P0/phase-report.md，恢复资产保留。
- 目录：`/Volumes/study/code/new_project/dpdk-knowledge-assistant-java`；分支`codex/p0-baseline-oracle`；初始基线`80b29135faddd16d5e39fdbf0633291ea6a20489`。
- 当前授权：仅J0-01/J0-02、多Agent、合成PG/替身/依赖/测试/本地提交；U10现已直接授权推送同名开发分支。禁止P1/真实上游/旧环境写入/main合并/部署/删除恢复资产。
- 当前源码目标：本仓库冻结Python副本；原目录仅来源，不同步变化；backend/前端原件未改。
- 验证代码/证据提交：08158c6初版、c94f43d修复、099dca2最终v2数据、a207a4a最后CLI类型守卫；之后仅文档/状态/审查收口。用户已验收交付7c76c31；验收记录提交d96bf4c已推送到同名origin分支，本地/远程SHA一致，证据evidence/P0/push-verification.json；其后仅补发布记录。

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
