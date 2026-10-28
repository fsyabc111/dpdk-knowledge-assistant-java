# Java 隔离部署入口

当前物料服务于U12/J5-03隔离工程演练。完整版本、命令、单写owner、备份恢复、应用回退及RTO/RPO口径见 `docs/java-migration/cutover-runbook.md`；验收工具说明见 `acceptance/deployment/README.md`。准备、build与配置展开不能替代实际部署验收。真实上游、生产切换、Python退役和恢复资产删除不在本批次。

## 本轮准备与入口

Java21、Node22、Python标准库、Docker Compose；Java主后端及业务CLI不依赖Python主后端。验收守卫只使用独立标准库工具，不导入backend/。

```bash
python3 -m unittest discover -s acceptance/deployment -p 'test_*.py' -v
# 首次新round准备；已有本卡目录时沿用，禁止覆盖旧恢复资产。
python3 acceptance/deployment/prepare.py --repo "$PWD"
export DPDK_JAVA_ENV_FILE="$PWD/.migration-artifacts/j5-03/runtime.env"
export DPDK_JAVA_DOCKER_CONTEXT=colima-java-u12
export DPDK_JAVA_DOCKER_ENDPOINT="unix://$HOME/.colima/java-u12/docker.sock"
# owner先核对该新profile的endpoint/Name/OSType与资产；首次建立/恢复见唯一runbook。
export DPDK_JAVA_DOCKER_DAEMON_ID="$(docker --context colima-java-u12 info --format '{{.ID}}')"
scripts-java/compose.sh                         # 静态配置展开，零daemon/PG操作
python3 acceptance/deployment/rehearse.py --repo "$PWD" plan
```

专属env及TLS key均0600且Git忽略。脚本拒绝旧.env、ambient DOCKER_HOST/TLS/旧context、非全源合成快照或shell覆盖配置。`.env.example`仅列字段；实际口令/env/manifest由prepare生成，不复制旧env。10个source/27个include逐一生成新合成文件，源挂载只读，四个容器内索引路径显式固定/sources，不猜父目录。

## Docker与数据库边界

- 所有Docker命令显式 `--context colima-java-u12`；不切换全局context、不启动/修改任何Colima profile。
- 实际命令前读取endpoint与daemonID/Name；只允许本轮新profile。project为owned manifest的 `dpdk-java-isolated-<round>`，每round使用新卷/网络，不复用旧项目或失败round卷。
- 只有HTTPS绑定127.0.0.1:18443；PG/主后端/fake/harness无宿主端口。nginx只反代/api，明确拒绝/metrics与/actuator；SSE关闭缓冲。
- backend UID10001、代理显式证书owner非root UID、fake UID1000；read_only、tmpfs、cap_drop ALL、no-new-privileges、CPU/RAM限制。PG写入仅自己的owned数据卷。
- fake默认固定本地模型/embedding/MCP结果，绝不执行源码，不代表live质量。真实harness-authorized profile仍默认关闭、pull never；默认guard禁止将它混入本轮场景。
- 每次数据库CREATE/DDL/restore前核对daemon、PG容器project/service/卷、维护库role/data_dir、base库comment及owned目标四项身份。数据库URL只能是postgres-java:5432内的本round owned库，role/data_dir不能override。

## 维护与备份

主Agent给出实际窗口且栈运行后使用容器内CLI（PG不映射宿主端口）：

```bash
scripts-java/operations-compose.sh --help
scripts-java/operations-compose.sh purge-sessions --retention-days 30
scripts-java/operations-compose.sh purge-sessions --retention-days 30 --apply
# 备份前核对当前scenario-state overlay与实际backend DB_NAME，dry-run.database必须一致。
scripts-java/backup.sh "$PWD/.migration-artifacts/j5-03/manual-backup.dump"
scripts-java/backup.sh --apply "$PWD/.migration-artifacts/j5-03/manual-backup.dump"
```

CLI强制关闭自身web/迁移/后台恢复，默认dry-run。备份默认为dry-run，实际custom dump先核对身份，`--exclude-table-data=public.rag_task_inputs`只排临时输入数据而保留结构；TOC还需确认TABLE存在且无TABLE DATA。恢复必须在另一个owned空库执行，保留消息/成绩/用量/releases/旧引用并验证源码哨兵为零。禁止以旧备份覆盖增量，不提供DROP/down -v/清卷入口。

CI仍按纯单测、显式PG、前端test/build、物料检查分层，不部署。主Agent最终按集成提交补远程CI和实际场景证据；本卡静态检查不宣称CI或容器验收已通过。
