# J5-02 默认边界 clock 资格（2026-10-28）

本次 `--case clock` 退出0；`DefaultBoundaryPgTest` 实际3项执行，0失败、0错误、0跳过（Surefire 0.801s）。这补齐默认verify中受owned库环境guard跳过的3项，不能视为完整故障矩阵、RTO或容量通过。

运行源 `00f265bba16fe8907b6ca24976284693f6a96546`，由本工作树合并integration `9250d76`；唯一公共handoff冲突按主Agent授权取integration原文。原Python baseline与17项检索/产品回归证据保留，冻结backend tree仍为 `38efd2a4d4ad284726989974bbb521ec4d4801dd`。先fresh执行 `test-compile dependency:build-classpath` 成功，classpath SHA见summary。

## 实际边界

- 使用真实PG、受理/仓储/配额/TaskRunner、`TaskSettings.defaults()`，执行墙仍600s。可控monotonic与ScheduledExecutor推进599s仍pending，推进到600.5s终态`system_error/timeout`，输入清空、活动槽归零；迟到finish被CAS拒绝，timeout不被覆盖。
- 真实受理输入created/expires差为默认24h；到期前1微秒可读，到期点不可读。再推进31s后恢复不执行外部调用，落为`system_error/interrupted`。
- 终态输入即时清空；monotonic299s注册句柄仍在，300s后清除。真实持久probe仍replay，assistant消息1、chat扣次1。

时间由可控Clock与调度器推进，未把600s/24h/300s配置改短，未实际等待600s墙钟。本测试的执行体只用合成返回；不访问真实模型/MCP。

## 复现与资产

四项PG环境显式注入，密码仅从主checkout本地安全文件的指定行读入内存；先核验loopback55481、user、data_directory，再创建本轮库，Java连接前再次核验精确库名/comment。

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B -DskipTests test-compile dependency:build-classpath -Dmdep.includeScope=test -Dmdep.outputFile="$PWD/.migration-artifacts/NEW-classpath.txt"
# 下列命令需先注入四项PG环境，并使用全新output；不使用旧.env
<主checkout>/.migration-artifacts/p0/venv/bin/python -B acceptance/resilience/run.py --case clock --classpath "$PWD/.migration-artifacts/NEW-classpath.txt" --output "$PWD/.migration-artifacts/NEW-clock"
```

原始manifest、Surefire XML、日志与摘要均保留在本worktree `.migration-artifacts/j5-02-clock-20261028-01`，未提交原始日志/配置/输入。首个只读postflight查询将文本时间直接相减报SQLSTATE42883，修正显式timestamptz转换后成功；诊断也保留，未重跑或覆盖测试。

运行07:56:58.724–07:57:03.588 UTC，driver PID40498已退出；结束后精确进程名检查Java/Maven/caffeinate均0。本轮owned库保留，comment/角色/数据目录复核通过；库连接0，任务为answered/supported、system_error/interrupted、system_error/timeout各1，临时输入0。未停共享PG实例、未删库。完整结果与原始文件SHA256见summary。
