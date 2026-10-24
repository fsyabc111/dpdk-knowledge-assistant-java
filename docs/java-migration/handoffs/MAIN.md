# 主 Agent 恢复点

- 日期：2026-10-24；目标：Java 主后端迁移，当前仅 P0 获授权。用户验收/P1仍未放行。
- 目录：`/Volumes/study/code/new_project/dpdk-knowledge-assistant-java`；分支 `codex/p0-baseline-oracle`；初始基线 `80b29135faddd16d5e39fdbf0633291ea6a20489`。
- 授权：J0-01/J0-02、多 Agent、合成专用PG/替身/依赖/测试/本地提交；禁止P1/真实上游/原项目部署业务库写入/push/main合并/部署/删除恢复资产。
- Oracle只读本仓库冻结Python副本，不吸收原目录新变化；原Python/前端未改。
- 已有本地提交：`08158c6` 初版、`c94f43d` 首轮修复；之后动态证据/门槛/最终回修尚待集成本地提交。未推送。

## 当前完成与门禁

- J0-01：378文件校验一致；66路由/29表/103配置/64模型映射，修正初版漏掉的ManagerAssignment继承模型；10项静态护栏通过。
- J0-02初版p0-v1/rev2保留不改；新p0-v2增加业务初态、逐case实际delta、终态、PG/检索结果和非空管理列表。两独立库已采集186观测/66路由/11PG用例/29表，正在修正自对照中的文本内嵌生成身份；未最终冻结。
- 三轮正式负载已完成，每轮120秒预热+600秒测量；8419问答p95=2154.735ms、p99=2195.212ms；21600读作业p95=30.137ms；正常分组异常0。PG终态/扣次10278，正式成绩/扣次174，临时输入0；warmup和讲解解释了与测量问答数的口径差异。
- 负载session55014已正常退出0、真实app shutdown完成；故障/负载子进程均已结束。正式测量窗口已关闭，独立库动态补验现已开放。
- 双轴初审已结束并回修；规范S-01/S-02关闭，S-03嵌套损坏输入还需最终复审；Spec S3/S4代码层关闭，S1/S2等待v2动态/manifest/非空列表证据。报告在evidence/P0，原报告不可覆盖。
- 旧完整业务重试/并发矩阵、真实0013升级、React场景、Java构建、真实模型/embedding/harness均未宣称通过，按原任务卡后续验证。

## 已确认决策

- U08/P0-D13：用户接受Java安全差异；running只等/查且受600秒外墙；unknown/查询失败不重投，system_error终态/failure_code=harness_result_unknown，保留submitted账本、清临时输入。Python重投失败证据保留。
- U09/P0-D15：用户已接受全部合成门槛；权威值migration/contracts/thresholds.json。资源是应用+负载/采样合计；1秒RTO仅10ms脚本执行，330秒调度等待仅推导；固定向量raw score容差1e-12、顺序和舍入后引用分数精确。
- P0-D14：Java21/Boot4.0.9/AI2.0.2；JDBC7.0.10/Flyway11.14.1/PGJDBC42.7.14随BOM；官方依据evidence/J0-01/java-stack-selection.md。没有Java构建。Flyway B15/V14/V15三路径设计待P1实测，迁移唯一owner为主Agent/root。

## 所有权与资源

- 主Agent：STATUS/MAIN/DECISIONS/ARCHITECTURE/阶段报告/thresholds/公共集成；p0_contracts已交还静态和契约窗口。
- 当前唯一在跑worker：p0_oracle，拥有J0-02非performance代码/fixture/证据/交接（approved-differences/comp003由主Agent集成）；正做动态补验、CLI嵌套输入修复及最终冻结。
- p0_performance已收口并释放所有performance路径；不再跑负载/PG扫描。两个review agent当前已收口，待v2完成后followup最终复审。
- 本仓库专用PG17.10+vector0.8.4仍运行：127.0.0.1:55473，角色p0_oracle，库p0_python_oracle/p0_candidate_oracle/p0_benchmark_oracle；comment=java-migration-p0-synthetic-80b29135；data_directory=.migration-artifacts/p0/pgdata；socket=/tmp/dpdk-java-p0-80b29135。
- venv=.migration-artifacts/p0/venv，Python3.11.15、133包且pip check通过；安装未改系统代理。原始日志/输入均在忽略的.migration-artifacts，禁止提交。

## 下一动作

1. 接收p0_oracle v2真实自对照、manifest、业务值反例/非空列表/有效token停用/CLI全部结果；保留所有失败，不改Python。
2. 提交最终代码/fixture审查快照，followup规范与Spec两条独立复审，关闭剩余条件项。
3. 主Agent同步最终冻结/差异/四层验收与阶段报告，校验来源和秘密/运行产物、只做本地提交。
4. 所有worker确认不再用PG后，仅停止本任务PG进程；保留pgdata/venv/日志/恢复资产。交付P0报告并等待用户验收/P1放行。
