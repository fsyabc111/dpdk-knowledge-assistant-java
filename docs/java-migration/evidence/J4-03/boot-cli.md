# J4-03 Boot jar 运维 CLI 实际进程验收

- 日期：2026-10-25（Asia/Taipei）；源码对象：`5a6ea65`；worker `codex/p3-model-adapter`。
- 新增显式验收类：`test/operations/OperationsBootJarAcceptance.java`，类名不匹配默认 `*Test`，仅通过 `-Dtest=OperationsBootJarAcceptance` 执行；不混入默认全量计数。
- 复用主checkout本轮已package的实际jar：`/Volumes/study/code/new_project/dpdk-knowledge-assistant-java/backend-java/target/dpdk-assistant-backend-0.1.0-SNAPSHOT.jar`。
- 实际SHA256：`0848f001d8f7681f0bb7ca5200d38c8886cbf5c5a5f199a89e1956668220234a`。验收启动时还逐字节哈希核对jar中的OperationsMain、OperationsCli、OperationsBusinessAdapter、TaskStore、TaskWiring与当前源码编译类一致，拒绝用test阶段遗留旧jar冒充交付物。

## 当前实际执行状态

2026-10-25 20:20首轮：编译通过；两个测试方法中，无数据库help/非法参数方法通过，数据库方法在TestPg维护实例只读预检时连接127.0.0.1:55481被拒绝，未建库、零业务写入。主Agent随后核查专属PG已干净停止并按原pgdata恢复、只读核验身份后重新授权测试。

恢复后的当前阻断（20:23实测）：create-users默认dry-run子进程退出0，但stdout含Spring Boot banner、启动/Hikari/迁移禁用INFO与末尾JSON，严格结构化stdout解析失败。`OperationsMain` 在context initializer里才设置banner/logging已经晚于Boot早期输出。测试未筛掉日志或提取末尾JSON冒充通过；已报主Agent修复早期配置并重新package。

原始失败证据：worker `.migration-artifacts/model/boot-cli-acceptance.log`（PG拒绝）、`boot-cli-acceptance-rerun-fixed.log`与 `boot-cli-rerun-fixed/01-stdout.log`（真实jar结构化stdout失败）。测试生成命令回执时曾出现局部变量java遮挡包名的编译问题，已重命名修正并实际编译执行；该失败日志也保留。当前业务DB验收仍未完成，不能计为通过。所有子进程和动态测试库均已finally清理，PG锁已交还主Agent。
已通过的真实jar子进程断言：

- 以JDK21 `PropertiesLauncher` 加载实际Boot jar并指定OperationsMain；不配置DB、不启动Spring业务容器也能 `--help` 退出0、stdout为中文CLI帮助、stderr空。
- 未知命令、create-users非法ID、retain-issues非法天数分别退出2；stdout空、stderr明确参数错误，未尝试业务端口。
- jar中关键类与当前编译类一致，artifact哈希已记录。

## 已实现、待PG执行的严格断言

- 真TaskTestHarness/TestPg唯一合成库；先维护身份预检，再迁移。
- 预存有有效临时输入、更新时间已过恢复宽限的老pending任务。CLI子进程即使通过低优先级env尝试打开background/Flyway/SQL初始化，安全启动设置仍须禁用；create-users默认dry-run前后全部public表数据哈希与schema指纹完全相同，pending.updated_at不变。
- create-users `--apply`只创建指定合成U账号；重复apply created为空且skipped为同一账号、数据库不再变化。
- audit-grading-jobs结构化只读输出，不重评、不泄露寄存源码/原始结果，所有public表不变。
- purge-task-inputs与purge-sessions先dry-run零写，再apply只清符合条件对象，保留恢复有效输入。
- retain-issues默认180天：181天请求/工作流记录删除，60天请求保留；默认不可误用30天；有效pending与其updated_at保持原样。
- 目标身份不匹配时退出1，stdout空，数据库全表/schema快照不变，待创建账号不存在。

## 非秘密命令与日志

工作目录 `.migration-artifacts/worktrees/p3-model`，JAVA_HOME=/opt/homebrew/opt/openjdk@21。

```sh
./backend-java/mvnw -f backend-java/pom.xml -B test-compile
./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest=OperationsBootJarAcceptance \
  -Ddpdk.acceptance.jar=/Volumes/study/code/new_project/dpdk-knowledge-assistant-java/backend-java/target/dpdk-assistant-backend-0.1.0-SNAPSHOT.jar \
  -Ddpdk.acceptance.logs=/Volumes/study/code/new_project/dpdk-knowledge-assistant-java/.migration-artifacts/worktrees/p3-model/.migration-artifacts/model/boot-cli
```

维护测试进程显式四个DPDK_TEST_PG_*环境变量。实际业务child从空白环境构造，只有基本PATH/LANG、AUTH_MODE=account与六个DPDK_JAVA_DB_URL/USER/PASSWORD/NAME/COMMENT/DATA_DIR；口令仅通过env，不进入命令、stdout或提交。没有继承任何DPDK_MODEL_*、真实模型/embedding/harness地址、JAVA_TOOL_OPTIONS或旧Spring配置。

真实child执行形态：

```sh
/opt/homebrew/opt/openjdk@21/bin/java \
  -Dloader.main=io.dpdk.assistant.operations.OperationsMain \
  -cp /Volumes/study/code/new_project/dpdk-knowledge-assistant-java/backend-java/target/dpdk-assistant-backend-0.1.0-SNAPSHOT.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher <command> [options]
```

child stdout必须是且仅是一份可解析JSON并核对command/apply/result；不是仅凭exit0认定业务通过。原stdout/stderr和逐条命令退出码/SHA回执留gitignored `.migration-artifacts/model/boot-cli/`。未使用原项目/业务库、真实上游，未跑全量verify或删除恢复资产；回退只移除本验收类与证据。
