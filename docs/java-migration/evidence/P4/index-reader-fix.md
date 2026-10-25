# P4 规范阻断修复：索引经公开契约读取发布知识

## 问题、边界与变更

规范审查指出 `JdbcIndexStore` 直接 SELECT `rag_knowledge` 越过知识模块的 SQL 所有权，也未兑现任务卡中的知识读取接口。按冻结阶段范围，仅移除此跨模块 SQL 并修复必要构造调用；无 DDL、排序/切块/发布/回滚算法或产品行为变更。

- 主 Agent 共享提交 `b43381a`：`contracts.knowledge.PublishedKnowledgeReader`、知识模块的真实 `PublishedKnowledgeRepository(TransactionRunner)`、KnowledgeWiring 和契约登记。
- worker 代码提交 `bdcb98f`：删除 index 的 `Published` 本地 record、`published()` 和 `rag_knowledge` SQL；IndexService 只消费 `PublishedKnowledgeReader.published()`，不依赖知识实现。
- IndexService 公开构造在原9参之后必传 `PublishedKnowledgeReader`，包内测试构造在 reader 后接 Supplier。构造期拒绝 null；不保留隐式空知识来源的兼容构造。
- index PG、`P4WorkflowIndexIntegrationTest` 与 `OperationsAdaptersIntegrationTest` 显式注入真实知识仓储。纯单测明确注入批准的空 published fixtures；新增必须依赖/调用一次/有序知识/revision 回归。两组合测试仅改变构造实参，不改变已有断言。
- 共享 IndexWiring 由主 Agent 单写；worker 未提交共享文件。

## 验证范围

保留 21组/39片段切块黄金、10节点完整发布知识扫描黄金、修订增量、撤回组合与运维适配断言。定向执行四个 index 测试类及两个授权组合类；全部使用显式身份守卫临时 PG 与固定上游，无真实模型/embedding/MCP。

验证快照为工作树 merge 提交 `b5c0de1d0d1e154a7aee8bc5f93be3cabe76be15`，包含 worker 修复 `bdcb98f`、共享 Reader/Repository `b43381a` 和共享 wiring `ce31250`。本次结果独立于历史52项，不复用旧通过声明。

实际命令（工作目录为 J4-01 独立工作树）：

```sh
JAVA_HOME=<显式JDK21> DPDK_TEST_PG_{URL,USER,PASSWORD,DATA_DIR}=<显式隔离实例> ./backend-java/mvnw -f backend-java/pom.xml -q -Dtest=BoundedLinesGoldenTest,YamlKnowledgeSourceTest,IndexServiceTest,PgIndexIntegrationTest,P4WorkflowIndexIntegrationTest,OperationsAdaptersIntegrationTest test
```

四项 PG 配置齐备，使用专用 `127.0.0.1:55481`、`p1_java` 与预期 `.migration-artifacts/p1/pgdata`；密码从 ignored 本地文件解析而未输出/提交。退出码 **0**。

| 测试类 | tests | failure/error/skipped |
|---|---:|---|
| BoundedLinesGoldenTest | 21 | 0/0/0 |
| YamlKnowledgeSourceTest | 7 | 0/0/0 |
| IndexServiceTest | 12 | 0/0/0 |
| PgIndexIntegrationTest | 13 | 0/0/0 |
| P4WorkflowIndexIntegrationTest | 2 | 0/0/0 |
| OperationsAdaptersIntegrationTest | 7 | 0/0/0 |
| **合计** | **62** | **0/0/0** |

原 index 52 项全部保持通过；新增1项显式知识 Reader 回归使 index 达到53项；两组合共9项。没有用跳过计通过。

PG 所有动态库由 TestPg 身份守卫创建并在 finally 删除。测试结束又显式核对 current_user 与 data_directory，随后检查 `p1_t_j401_%`、`p1_t_workflowindex_%`、`p1_t_j403adapters_%`：临时数据库 **0**、连接 **0**。PG 窗口已归还主 Agent。

原始日志仅留主 checkout `.migration-artifacts/p4-index-reader-fix.log`，未提交。`git diff --check` 通过；静态检索 `main:index/**` 的 `rag_knowledge|knowledge.workflow|PublishedKnowledgeRepository` 无命中。

## 未验证、回退与下一步

主线全量及双轴复审由主 Agent 完成；本卡仅关闭指定模块边界阻断，不自行放行 P4。真实上游、原数据库和生产仍未触及。回退代码时应成组撤回 Reader 契约/仓储、IndexService构造与主 wiring 注入，不能恢复隐式空来源；无 DDL/生产数据回退事项。

验证快照关键文件 SHA256：


- `backend-java/src/main/java/io/dpdk/assistant/index/IndexService.java`：`7c3be9122f21c41eae926f8ff67bda57ab0b6aacf97b9a7d50ece345d35ba071`。
- `backend-java/src/main/java/io/dpdk/assistant/index/JdbcIndexStore.java`：`598dd54d694c35615acb56259c67920ecabbadf7c4f143c23d159640d45fc86c`。
- `backend-java/src/main/java/io/dpdk/assistant/contracts/knowledge/PublishedKnowledgeReader.java`：`083d5a173089aad645f49e505a2443d8292a25d99e1b9e05ff4484ca79bf59e8`。
- `backend-java/src/main/java/io/dpdk/assistant/knowledge/workflow/PublishedKnowledgeRepository.java`：`d18df105ee1f50ae165dd7e7d61e8b5057e42b63167004cd29ed5fcea3db08ed`。
