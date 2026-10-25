# J5-02 故障与恢复入口

所有下列命令会写本轮合成PG、启动Java、注入故障。必须先取得主Agent的J5-01核心通过与独占PG/CPU窗口消息；目前仅实现和纯验证，尚无执行通过证据。

共享四项 `DPDK_TEST_PG_*` 必填，`acceptance/contract/isolation.py` 生成动态 `p5_resilience_` 库与精确comment并逐次核验。不会读旧.env、旧P0库或默认5432；默认不删数据库、日志、外部账本和备份，失败不覆盖或隐藏。

先按 `acceptance/load/README.md` 构建Java测试classpath。纯验证：

```sh
PYTHONDONTWRITEBYTECODE=1 /Volumes/study/code/new_project/dpdk-knowledge-assistant-java/.migration-artifacts/p0/venv/bin/python -B -m unittest acceptance.resilience.test_plan acceptance.load.test_statistics -v
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B -Dtest=ManualSchedulerTest test
```

窗口放行后先单例：

```sh
PYTHONDONTWRITEBYTECODE=1 /Volumes/study/code/new_project/dpdk-knowledge-assistant-java/.migration-artifacts/p0/venv/bin/python -B acceptance/resilience/run.py --case accepted --classpath "$PWD/.migration-artifacts/j5-classpath.txt" --pg-bin /opt/homebrew/opt/postgresql@17/bin --output "$PWD/.migration-artifacts/j5-resilience-accepted-001"
```

完整矩阵：

```sh
PYTHONDONTWRITEBYTECODE=1 /Volumes/study/code/new_project/dpdk-knowledge-assistant-java/.migration-artifacts/p0/venv/bin/python -B acceptance/resilience/run.py --case all --classpath "$PWD/.migration-artifacts/j5-classpath.txt" --pg-bin /opt/homebrew/opt/postgresql@17/bin --output "$PWD/.migration-artifacts/j5-resilience-all-001"
```

`--pg-bin` 须现场核实，不能将示例路径当当前依赖已验证。单例与完整运行都拒绝覆盖既有输出目录。可以用 `--case clock|queue|lifecycle|mcp_unknown|...` 定向复验，但失败原目录保留；只有完整矩阵可声明本卡场景覆盖。

矩阵包含：

- 真实默认600s TaskRunner与可控ScheduledExecutor：599s仍pending、600.5s触发timeout/system_error、清输入/放槽、拒绝迟到finish；配置仍600，不声称等待真实600s墙钟。真实受理24h expires_at，在到期前/恰好到期校验；终态立即清与300s运行句柄TTL边界（过期DB重放不重扣）。PG边界测试不调用TestPg建删库，仅attach本轮owned库并检查精确身份。
- 单Java实例41请求：20活动、20等待、第41立即429、20等待者真实10s后429；放行20活动完成。所有41响应/耗时保留，21预期背压单列，配额20/输入0/槽0。
- 真实TCP stream断开后后台完成、重放不再调用模型/MCP/扣次；显式取消可重复，故意忽略中断的合成模型迟到返回不能覆盖取消；停号真实业务事务撤token/取消pending、不落未完成成绩。受理前错误401/422无副作用。
- 每个SIGKILL案例先真实Java生产者A走multipart受理，固定模型/MCP仅10ms脚本，在模型前后、MCP出门前/已提交、成绩事务前/后、终态事务前/后门栓停住。控制器只杀本轮Popen核验过的A PID，断言POSIX -9退出，再启动新Java恢复者B，共用PG与fsync外部替身账本（只存工具/job身份，无源码）。保留kill前后RPO投影和源hash；不保证未持久化的模型中间输出。
- B ready后，owned行拨旧绕过grace，再调用真实ensureManaged。明确分别报告kill→ready与执行段。只有accepted/before_terminal、10ms脚本、已有finished或无未决外部job、进程已起/PG健康/槽可用，执行段门槛1s。running只查/等，随后替身显式变finished；unknown/查失败必须system_error安全终态、保留submitted、清临时输入、零重投。不将COMP003差异改写为Python兼容失败或外部任务已完成。
- 每个终态案例扫描全部长期表/日志的随机合成源码标记，并执行真实pg_dump后扫描备份；无pg_dump执行不判完整哨兵通过。原始源码与备份只在gitignored运行目录，不提交。

外部替身账本在A/B之间留存；不模拟真实harness容器隔离/跨主机exactly-once。重启丢内存账本的unknown已通过新JVM重新装配且保留独立ledger模拟；真实harness场景待另授权。默认330s调度等待仍是推导，本入口不将拨旧+定向驱动称为自动扫描最坏RTO实测。
