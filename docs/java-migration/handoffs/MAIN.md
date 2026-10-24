# 主 Agent 恢复点

- 日期：2026-10-24；目标：Java 主后端迁移，当前仅 P0 获直接用户授权。
- 授权：J0-01/J0-02、多 Agent、独立合成测试/PG/本地上游替身、开发分支和本地提交；不授权 P1/真实上游/旧环境写入/push/main 合并/部署/删除恢复资产。
- 工作目录：`/Volumes/study/code/new_project/dpdk-knowledge-assistant-java`；分支 `codex/p0-baseline-oracle`；起点 `80b29135faddd16d5e39fdbf0633291ea6a20489`。初始 main 干净、origin 核验正确。
- Oracle 来源：此仓库冻结 Python 副本；原目录只作来源记录。旧测试报告不计新验证。
- 当前：J0-01 p0-v1 初版可消费（66路由/29表/103配置/63模型/378文件校验通过），语义继续完善。J0-02 双独立库各184观测/66路由成功/14脚本/10PG用例/29表通过，Python严格自对照通过；正式3轮容量正在运行，独立审查准备中。
- owner：主 Agent 独占 STATUS/MAIN/DECISIONS/阶段报告/根共享文件；p0_contracts 独占 migration/contracts、migration/baseline、evidence/J0-01、handoffs/J0-01.md；p0_oracle 独占 migration/fixtures、migration/oracle（排除 performance 子目录）、evidence/J0-02（排除 performance）和 handoffs/J0-02.md；p0_performance 独占 migration/oracle/performance、evidence/J0-02/performance、handoffs/J0-02-performance.md。
- 后续数据库迁移唯一 owner 为主 Agent，在 J1-01 获授权窗口实现；P0 不实施 Java。
- 在跑：p0_performance（长测）、p0_oracle（审查修复，长测后才跑PG）、p0_contracts（继承模型与契约静态修复）。双轴审查和技术栈研究已结束，修后需复审。
- 服务：本仓库专用 PG17.10，127.0.0.1:55473，数据 .migration-artifacts/p0/pgdata；库 p0_python_oracle/p0_candidate_oracle，角色 p0_oracle，另有 p0_benchmark_oracle 专供性能；三库 comment java-migration-p0-synthetic-80b29135；socket /tmp/dpdk-java-p0-80b29135。模型/MCP仅替身，正式性能应用运行中。独立 venv Python3.11.15+133包，pip check通过；未改系统代理。环境清单 .migration-artifacts/p0/environment.json。
- 未提交：P0 授权/恢复记录（主 Agent），J0-01 输出（worker）。禁止 reset/clean 覆盖。
- 决策：关键产品差异与实测门禁集中请用户确认；真实质量另获 live 授权。
- 下一动作：功能/性能资格运行（已开始）→正式3轮120s预热+600s测量→收集兼容取舍与阈值→独立审查。

- 长测：p0_performance持有exec session55014；真实Python ASGI+PG，3×(预热120s+测量600s)，日志 `.migration-artifacts/p0/performance/baseline.log`，证据 `evidence/J0-02/performance/baseline-round-*.json`。禁止同时跑额外负载或停止共享PG。正式长测开始后主Agent不连接PG。
- 用户已确认：COMP-003 Java安全差异（running轮询受600s墙；unknown/查询失败不重投，现有system_error终态+failure_code，保留job账本/清理临时输入），依据2026-10-24用户明确接受建议，见DECISIONS/P0-D13；阈值待三轮数据后确认。
- 已有本地审查提交 `08158c6`，未推送；其后主Agent同步COMP-003批准状态、failure_code=`harness_result_unknown`和schema owner/动态证据，尚未提交。公共契约集成窗口现归主Agent。
- 独立审查初步确认：静态模型枚举漏传递继承 ManagerAssignment（submission_count/student_count）；verify合法JSON非object/非法日期可能抛非约定异常。等待两轴正式报告后分别交J0-01/J0-02 owner修复；不连接PG干扰长测。
- 功能初版fixture p0-v1/rev2，golden c041d83636b31684aeef9f1497925a0ebdc3112b9a7ea781f1fd92093b943edf；CLI验证184观测通过。P0未收口：逐case DB状态值/非空manager列表需独立审查确认；有效token停用脚本待长测结束执行。若扩展黄金，必须新版本而非覆盖。

- P0-D14已选Java21 / Boot4.0.9 / AI2.0.2及BOM管理的JDBC7.0.10 / Flyway11.14.1 / PGJDBC42.7.14；官方依据见evidence/J0-01/java-stack-selection.md。Java未构建运行，P1未授权。
- 待修：业务状态投影（Spec P1）、继承模型及非空列表、SSE CRLF/error-only、身份命名格式、损坏输入CLI。扩展p0-v2须保留p0-v1；最终双库与有效token停用补验仅在长测结束后执行。
