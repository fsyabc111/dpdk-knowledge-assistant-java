# 独立 README 接手：构建/测试第一段

2026-10-28，独立接手者为此前仅审查、未实现业务/部署代码的规范轴worker。按根README和backend README执行本段明确授权范围，不修生产或文档，不提交。**本段构建/测试与帮助入口可跟随；不是完整独立部署或J5-04总验收通过。**

## 源码、版本与前置

- 主worktree：`/Volumes/study/code/new_project/dpdk-knowledge-assistant-java`；起始工作区干净，提交 `5039e3a340940d584ff6b388d19f2b2a9107de96`。
- `backend-java/src/main` Git tree：`6e1ec8a514e9ea0151fbc9848b8e4c6f1e6be3e3`，只读核实与2a950相同；`src/main/java` tree：`e77ed11ba386eb047dbff10eed0e1a3f3f78ec43`。没有修改源码。
- 使用README的 `JAVA_HOME=/opt/homebrew/opt/openjdk@21` 并将其bin置于本段子进程PATH首位；实际OpenJDK Homebrew **21.0.12.1**。实际Node **24.18.1**、系统Python **3.9.6**；本次不冒称Node22复现，未安装/切换新工具链。
- 原native TestPg由owner提供：`jdbc:postgresql://127.0.0.1:55481/postgres`、角色`p1_java`、DATA_DIR为本checkout `.migration-artifacts/p1/pgdata`。仅解析owner指定ignored凭据文件的 `DPDK_TEST_PG_PASSWORD` 键注入子进程环境，未打印/提交口令；未加载旧.env。测试仍执行自身TestPg身份预检和动态库生命周期。
- 本段不提供 `J5_BOUNDARY_DB_NAME/COMMENT`、不收养别轮owned库。因此DefaultBoundary的3项skip必须单列，待另行owned clock入口覆盖。
- Maven缓存与`frontend/node_modules`起始已存在；npm ci实际重新安装前端依赖。此结果是现有获准工作区的独立操作复现，不是空白新机器/离线依赖安装证明。

## 实际命令与结果

全部从仓库根目录运行，前端三条命令仅将cwd设为frontend；没有改手册参数或为绿修断言。

| 命令 | 本次结果 | 退出码/耗时 |
|---|---|---|
| `$JAVA_HOME/bin/java -version` | OpenJDK21.0.12.1 | 0 |
| `node --version` / `python3 --version` | v24.18.1 / 3.9.6 | 均0 |
| `./backend-java/mvnw -f backend-java/pom.xml -B verify` | **789发现，786执行通过，0失败/0错误，3显式skip；BUILD SUCCESS** | 0 / 74.592s |
| `cd frontend && npm ci` | 实际安装164 packages；未修改lock | 0 / 4.116s |
| `cd frontend && npm test` | **3文件/102测试全部通过** | 0 / 1.088s |
| `cd frontend && npm run build` | Vite7.3.6，1584模块，dist产物生成 | 0 / 1.017s |
| `python3 -m unittest discover -s acceptance/deployment -p 'test_*.py' -v` | **36/36纯测试通过**，不操作Docker/PG | 0 / 0.153s |
| `scripts-java/operations.sh --help` | 当前新jar输出中文帮助、dry-run/--apply、180天retain和judge边界 | 0 / 0.172s |

Maven计数依据本轮log摘要及其中实际suite清单匹配的XML。target/surefire-reports另有旧`OperationsBootJarAcceptance`2项XML，**未在本轮verify执行，不累计为791，也不作为当前jar的native2项通过**。3项skip全部属于 `io.dpdk.assistant.acceptance.resilience.DefaultBoundaryPgTest`；本段未运行`--case clock`，不继承旧clock通过声明。

## 新工件与日志

- 本次jar：`backend-java/target/dpdk-assistant-backend-0.1.0-SNAPSHOT.jar`。
- jar SHA256：`9393c8dd25101e0f83d814061d2cc38d4a8ea61efed20a7f198df382d7e6f5f3`。
- pom SHA256：`a6a04b7167194ba9b1df38d04fb62b08c4e232ae0da7c4e109c92d91e4fb55cc`；frontend lock SHA256：`8a15f4eff452e1557df0e7fd932f2efa830d784c9c7a9cb016d95bab2a768a19`。
- 全新ignored目录：`.migration-artifacts/readme-independent-build-20261028-2wwfc03e/`。`results.json`记录命令/cwd/PID/退出码/耗时、逐日志SHA、当前suite计数与排除旧报告；每条命令有独立log。没有覆盖历史证据或恢复资产，原始日志不提交。

## 本段未执行与手册跟随缺口

- 主checkout没有 `.migration-artifacts/j5-03/runtime.env` 和 `deployment-manifest.json`。因此**静态compose/plan前置未满足，未执行**；没有伪造daemon/env、重跑prepare覆盖目录或借其他worktree资产冒充本checkout前置。
- `operations-compose.sh --help`需要实际运行容器，不属于本段native帮助结果；未恢复两VM、未调用Docker runtime、未起PG业务栈、未执行cold/0013/备份恢复/回退、未运行浏览器。
- 部署手册推荐Node22，本机实际Node24下前端成功；Node22资格仍需按最终接手环境单独记录，不以此轮版本替代。
- npm ci有allow-scripts提示，原文保留在ignored日志；本轮命令退出0且后续test/build成功，没有额外执行approve或改配置。
- 3项owned boundary与新jar独立native验收不在本段结果内；实际部署与完整接手继续等J5-02及主Agent窗口。P5总门禁、用户/live/生产验收未通过，不因本段绿而改变。

## 资源归还

本段串行子命令全部结束，无后台服务；已只读核查Maven PID70683、Surefire JVM70810、前端77307/77349/77398、纯测77428、帮助77429均不再存在。原专属PG实例未启停；测试动态库由其自身finally处理，本段未另做手工PG清理或修改共享库。**target构建锁与PG测试窗口已归还主owner**，不再执行Maven/CLI/PG。本段仅新增本报告及全新ignored日志/正常构建产物，没有生产/文档修复或提交。
