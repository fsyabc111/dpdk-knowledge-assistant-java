# 主 Agent 恢复点

- 日期：2026-10-24；目标：Java 主后端迁移，当前仅 P0 获直接用户授权。
- 授权：J0-01/J0-02、多 Agent、独立合成测试/PG/本地上游替身、开发分支和本地提交；不授权 P1/真实上游/旧环境写入/push/main 合并/部署/删除恢复资产。
- 工作目录：`/Volumes/study/code/new_project/dpdk-knowledge-assistant-java`；分支 `codex/p0-baseline-oracle`；起点 `80b29135faddd16d5e39fdbf0633291ea6a20489`。初始 main 干净、origin 核验正确。
- Oracle 来源：此仓库冻结 Python 副本；原目录只作来源记录。旧测试报告不计新验证。
- 当前：J0-01 p0-v1 初版可消费（66路由/29表/103配置/63模型/378文件校验通过），语义继续完善。J0-02 双独立库各184观测/66路由成功/14脚本/10PG用例/29表通过，Python严格自对照通过；正式3轮容量正在运行，独立审查准备中。
- owner：主 Agent 独占 STATUS/MAIN/DECISIONS/阶段报告/根共享文件；p0_contracts 独占 migration/contracts、migration/baseline、evidence/J0-01、handoffs/J0-01.md；p0_oracle 独占 migration/fixtures、migration/oracle（排除 performance 子目录）、evidence/J0-02（排除 performance）和 handoffs/J0-02.md；p0_performance 独占 migration/oracle/performance、evidence/J0-02/performance、handoffs/J0-02-performance.md。
- 后续数据库迁移唯一 owner 为主 Agent，在 J1-01 获授权窗口实现；P0 不实施 Java。
- 仍运行 worker：p0_oracle（总交接整理）、p0_performance（长测）。p0_contracts已交review-ready，公共契约暂停写入待followup。服务：本仓库专用 PG17.10，127.0.0.1:55473，数据 .migration-artifacts/p0/pgdata；库 p0_python_oracle/p0_candidate_oracle，角色 p0_oracle，另有 p0_benchmark_oracle 专供性能；三库 comment java-migration-p0-synthetic-80b29135；socket /tmp/dpdk-java-p0-80b29135。未启动模型/MCP/应用。独立 venv 冻结依赖已安装133包，pip check通过；原代理/直连PyPI下载慢已中断，最终锁文件原清华镜像+安装进程NO_PROXY=*成功，无系统配置变更。在跑安装：无。环境清单 .migration-artifacts/p0/environment.json。
- 未提交：P0 授权/恢复记录（主 Agent），J0-01 输出（worker）。禁止 reset/clean 覆盖。
- 决策：关键产品差异与实测门禁集中请用户确认；真实质量另获 live 授权。
- 下一动作：功能/性能资格运行（已开始）→正式3轮120s预热+600s测量→收集兼容取舍与阈值→独立审查。

- 长测：p0_performance持有exec session55014；真实Python ASGI+PG，3×(预热120s+测量600s)，日志 `.migration-artifacts/p0/performance/baseline.log`，证据 `evidence/J0-02/performance/baseline-round-*.json`。禁止同时跑额外负载或停止共享PG。正式长测开始后主Agent不连接PG。
- 用户已确认：COMP-003 Java安全差异（running轮询受600s墙；unknown/查询失败不重投，现有system_error终态+failure_code，保留job账本/清理临时输入），依据2026-10-24用户明确接受建议，见DECISIONS/P0-D13；阈值待三轮数据后确认。
