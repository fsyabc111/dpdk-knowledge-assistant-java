# P3 公共任务装配验证

- 日期：2026-10-25；代码对象：公共契约/TaskWiring `f6b866e`，模型适配 `20615e8`；验证工作树 `codex/p3-model-adapter`。
- 新增测试路径：`platform/wiring/TaskWiringIntegrationTest.java`、`TaskSettingsBridgeTest.java`。本验证切片没有修改业务主代码、共享配置、DDL 或状态文档。
- 实现=公共装配由主 Agent 交付；自动验证=本切片18/18；场景验收=真实Spring上下文+隔离PG局部链路通过；用户验收=未执行。产品chat/SSE整链仍由J3-03负责。

## 实际验证结果

| 测试 | 场景与断言 | 结果 |
|---|---|---|
| springUsesOneRealCancellationAndSessionAdapterParticipatesInCallerTransaction | 真实TaskWiring装配只有一个LocalTaskCancellationPort，类型TaskLocalCancellation，无NoOp；SessionPortAdapter参与caller事务，append后注入异常回滚至零消息，正常提交保留正文/metadata | 通过 |
| attachmentAndRichSnapshotsSurviveAdmissionAndCompletionReturnsCommittedMessageId | admission附件名/后缀/执行源/来源四字段临时寄存；富Probe和AdmissionResult携带完整同一任务快照；重复受理无重复用户消息；finish返回已提交messageId；输入清理；probe/admit终态回放正文和messageId一致 | 通过 |
| disablingAccountThenCallingSpringCancellationInterruptsTaskAndClearsInputsAndSlot | Spring装配服务受理并启动阻塞执行体，真实AdminUserRepository.setEnabled提交停号后调用Spring取消Bean，中断被执行体观察；终态cancelled/account_disabled、临时输入清空、槽为零、无迟到答案 | 通过 |
| readyEventStartsHeartbeatWithExecutorAndContextCloseStopsIt | 真实ApplicationReadyEvent接线；有TaskExecutor时启动心跳，任务updated_at从可控旧值被真实PG UPDATE推进；取消收口；关闭上下文后心跳线程停止 | 通过 |
| readyEventWithoutExecutorDoesNotStartBackground | 无TaskExecutor时应用就绪也不启动后台循环 | 通过 |
| TaskSettingsBridgeTest（13项） | 实际加载TaskWiring/@Value，模拟同名systemEnvironment属性源；验证RAG_*桥接、显式dpdk.*优先、源缺省、NaN/Infinity/0/负值/非数值拒绝；不复制配置解析实现 | 13/13 |

PG场景使用TaskTestHarness提供真实事务、可控时钟、身份账号锁、会话服务及配额仓储，通过实际TaskWiring构造TaskStore/Runner/Recovery/Streams/RagTaskService。只替换TaskExecutor这一外部执行边界；配置测试的mock依赖仅用于避免数据库启动，未作为业务正确性证据。

## 执行与隔离

工作目录：`.migration-artifacts/worktrees/p3-model`；JDK21。先通过TaskTestHarness→TestPg.createDatabase的只读实例身份预检，核对维护角色和data_directory，再创建唯一p1_t_*合成库、迁移、测试、DROP。目标为127.0.0.1:55481专属测试实例；四个DPDK_TEST_PG_*环境变量显式提供，口令只从gitignored本地文件读入子进程环境，没有输出或提交。维护库和应用测试库不回退其他实例。

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest=TaskSettingsBridgeTest
# 退出0，13/13，无失败/错误/跳过。

# 显式提供四个DPDK_TEST_PG_*环境变量后：
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest=TaskWiringIntegrationTest,TaskSettingsBridgeTest
# 退出0，18/18（5项真实PG装配+13项配置），无失败/错误/跳过，耗时2.741秒。
```

原始日志：本worktree `.migration-artifacts/model/{settings-test,task-wiring-test}.log`（gitignored）。非法设置用例的启动失败警告为预期拒绝证据，未被计作成功上下文。未跑全量verify，PG锁已释放交主 Agent安排后续；无遗留应用/后台线程/测试库。无真实模型、embedding、harness调用，无生产变更。

## 接续与回退

新增测试由集成者cherry-pick到主线后纳入全量verify；Spring HTTP全应用扫描、chat/SSE与讲解链仍需各卡和集成者验证。本报告不替代P3阶段门禁。

回退仅撤销这两个测试类和本证据文件；无DDL或持久业务副作用。测试数据库按身份预检安全删除，恢复资产未删除。
