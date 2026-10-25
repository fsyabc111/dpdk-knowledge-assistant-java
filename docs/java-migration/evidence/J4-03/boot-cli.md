# J4-03 Boot jar 运维 CLI 实际进程验收

- 日期：2026-10-25（Asia/Taipei）；最终业务源码：`1ed547b`（含日志早期接线及角色枚举修复）。
- 结果：显式验收类 `OperationsBootJarAcceptance` **2/2通过，16个真实新JVM命令均完成严格断言**；失败0、错误0、跳过0，20:31:33结束，测试14.67秒、构建18.422秒。
- 类名不匹配默认 `*Test`，仅通过 `-Dtest=OperationsBootJarAcceptance` 执行；不混入默认全量计数，不把698项历史verify扩报为700项当前全量。
- 实现=本显式验收入口完成；自动验证/独立PG场景=通过；用户验收/live/生产切换=未执行。

## 源码与构建产物绑定

复用主checkout在修复后重新package的实际Boot jar，未使用worker的test阶段旧jar：

```
/Volumes/study/code/new_project/dpdk-knowledge-assistant-java/backend-java/target/dpdk-assistant-backend-0.1.0-SNAPSHOT.jar
SHA256 f57f8045cafeab09a5d8098b5ebea09e6e7d188d15229b2879073bfa73e93bf3
```

验收启动时逐字节SHA核对jar中的OperationsMain、OperationsCli、OperationsBusinessAdapter、TaskStore、TaskWiring与当前源码编译类一致。最终hash记录在worker `.migration-artifacts/model/boot-cli-native-final/jar-sha256.txt`。

## 16条真实命令与业务断言

每条命令通过新JDK21进程启动jar PropertiesLauncher→OperationsMain；没有在测试进程内直接调用OperationsCli.execute。成功业务stdout必须是且仅是一份JSON，并严格核对command/apply/result；没有过滤banner、提取末尾JSON或以exit0替代业务正确性。

| 回执序号 | 参数（共同java前缀见下） | 退出码 | 实际断言 |
|---|---|---|---|
| 1 | create-users --id U00000000902 --roles viewer,operator --daily-quota 7 | 0 | 默认dry_run=true，would_create恰好该ID；全部public表数据SHA与schema指纹不变；老pending.updated_at不变 |
| 2 | 同上加 --apply | 0 | 只创建该合成账号；总用户数由1到2，roles精确viewer/operator，配额7；其他全部表与schema不变 |
| 3 | 重复同一apply | 0 | created为空、skipped恰好该ID；全部表/schema快照与首轮apply后完全相同 |
| 4 | audit-grading-jobs --limit 20 | 0 | read_only=true、re_evaluated=false；只回元数据、has_result=true，不泄露合成源码或原始结果哨兵；数据库完全不变 |
| 5 | purge-task-inputs | 0 | 预览inputs=2；数据库完全不变 |
| 6 | purge-task-inputs --apply | 0 | 清理2条符合终态/过期规则的输入，恢复有效pending输入保留 |
| 7 | purge-sessions | 0 | 默认30天预览sessions=1；数据库完全不变 |
| 8 | purge-sessions --apply | 0 | 仅删除31天闲置会话，目标行确实消失 |
| 9 | retain-issues | 0 | **默认180天**、expired=1；数据库完全不变 |
| 10 | retain-issues --apply | 0 | 181天请求/issue/event删除，60天请求保留；有效pending仍pending且updated_at/输入保持，未误按30天清理 |
| 11 | create-users --id U00000000903 --apply（期望DB名故意不匹配） | 1 | stdout空；stderr受控失败且不含密码/源码；全表/schema不变，账号不存在 |
| 12 | --help（完全无DB配置） | 0 | stdout为中文CLI帮助、含180日retain说明；stderr空，不启动业务容器 |
| 13 | not-a-command（无DB） | 2 | stdout空、参数错误stderr非空 |
| 14 | create-users --id bad（无DB） | 2 | 同上，非法ID在业务容器前拒绝 |
| 15 | retain-issues --retention-days 0（无DB） | 2 | 同上，非法天数在业务容器前拒绝 |
| 16 | create-users --id U00000000902 --roles teacher（无DB） | 2 | 非既有角色teacher在parser拒绝；对应operator正例已在1–3真实PG验收 |

只读快照覆盖所有public表的有序jsonb行集合SHA，以及public列/默认值、索引定义、约束定义的schema指纹。用于比对的原行内容（含合成密码hash/源码寄存输入）不输出到交付证据。另预存一条有有效临时输入、更新时间已过恢复宽限的pending任务；所有非维护写路径和最终retain之后updated_at不变，证明CLI没有偷偷运行孤儿认领/心跳。

child环境故意尝试以较低优先级打开background/Flyway/SQL初始化，OperationsMain强制安全设置仍覆盖它们。没有发生应用启动迁移、意外后台恢复或默认模型调用。

## 隔离环境与非秘密复跑命令

工作目录 `.migration-artifacts/worktrees/p3-model`；JAVA_HOME=/opt/homebrew/opt/openjdk@21。维护测试进程四个DPDK_TEST_PG_*显式指向专属127.0.0.1:55481实例及期望data_directory。TaskTestHarness→TestPg在CREATE/DROP前先只读核验维护身份，随后唯一合成库受控迁移。

```sh
./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest=OperationsBootJarAcceptance \
  -Ddpdk.acceptance.jar=/Volumes/study/code/new_project/dpdk-knowledge-assistant-java/backend-java/target/dpdk-assistant-backend-0.1.0-SNAPSHOT.jar \
  -Ddpdk.acceptance.logs=/Volumes/study/code/new_project/dpdk-knowledge-assistant-java/.migration-artifacts/worktrees/p3-model/.migration-artifacts/model/boot-cli-native-final
```

每条child实际执行前缀：

```sh
/opt/homebrew/opt/openjdk@21/bin/java \
  -Dloader.main=io.dpdk.assistant.operations.OperationsMain \
  -cp /Volumes/study/code/new_project/dpdk-knowledge-assistant-java/backend-java/target/dpdk-assistant-backend-0.1.0-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher <command> [options]
```

业务child从空白环境构造，只保留基本PATH/LANG、AUTH_MODE=account，以及六个显式DPDK_JAVA_DB_URL/USER/PASSWORD/NAME/COMMENT/DATA_DIR。口令取TestPg.password，仅通过env，不进入命令、stdout或提交。没有继承任何DPDK_MODEL_*、真实模型/embedding/harness地址、JAVA_TOOL_OPTIONS或旧Spring配置。

最终原始日志：worker `.migration-artifacts/model/boot-cli-native-final.log`；逐条stdout/stderr与 `commands.jsonl`（参数、退出码、输出SHA）在 `boot-cli-native-final/`，均gitignored。全部16条按表中预期退出，无超时。动态数据库和全部child已finally关闭/删除，PG排队锁已明确归还主Agent；无全量verify、旧项目/生产写入或恢复资产删除。

## 失败证据与修复闭环

失败、历史artifact均保留，不计为通过：

1. 首轮 `5a6ea65` / artifact SHA `0848f001d8f7681f0bb7ca5200d38c8886cbf5c5a5f199a89e1956668220234a`：维护PG连接拒绝，TestPg零写入退出。主Agent核实专属PG曾干净停止，按原pgdata恢复并重新身份核验，未切换旧实例。日志 `boot-cli-acceptance.log`。
2. PG恢复后首artifact实际stdout含Spring banner、Boot/Hikari/迁移禁用INFO+末尾JSON，严格解析失败；日志 `boot-cli-acceptance-rerun-fixed.log` 与 `boot-cli-rerun-fixed/01-stdout.log`。回执新增代码的局部变量java遮挡包名编译错误已单独修正，失败日志保留。
3. `8a46b44` / artifact SHA `1add4aa0348dc72934b6618359e9b1df2b7bc4ebc680fcc156589e19f9cec9e0`：banner和多数框架日志消失，但application.yml显式io.dpdk INFO仍污染stdout，严格解析继续失败；日志 `boot-cli-early-safety.log`。
4. 另核对出CLI硬编码teacher而拒operator，与既有AuthServerSettings/AdminTreeRules允许枚举不一致，help示例无法运行；本验收补了正反例。主Agent `1ed547b` 复用既有角色枚举、显式关闭io.dpdk日志并把框架启动期stdout转stderr，重新package后完整2/2、16进程通过。

回退仅撤销新增 `OperationsBootJarAcceptance.java` 与本证据；业务修复归主Agent提交。任何新jar必须重新package并重复artifact/关键类与严格行为验收，不能继承旧jar通过声明。
