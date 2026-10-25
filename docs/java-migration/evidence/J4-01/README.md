# J4-01 合成验证与验收映射

## 范围与结果

独立工作树 `codex/j4-01-index` 从 P3 门禁 `5d0d6a3` 建立，合并共享契约 `2bea943`。真实 DPDK 源、模型、embedding、MCP、生产和旧数据库均未调用/修改。默认缺源使用**显式合成配置**，不隐式跳过，也不创建空活动索引。

- 先行黄金：`c94bcbb`，21 组 Python 输入、39 个精确切块；实现切片 `70e574e`。
- 补充完整扫描黄金：10 节点，包含多根/根同长 tie/目录重叠去重/路径段与 Unicode 排序、全部 content/metadata、published knowledge/修订、Python JSON 原串。
- 无 PG 验证：39 tests，0 failure/error/skipped；包括新增 symlink/.. 与 POSIX 反斜杠身份。
- 显式独立 PG 首轮：13 tests，0 failure/error/skipped；与初轮 38 个单元合计 51 tests。
- 最终源码 `fcb3e26`：39 单元/黄金 + 13 PG = **52 tests，0 failure/error/skipped**；文件摘要与源码 SHA 见 `validation.json`。
- PG 使用 TestPg 实例身份守卫创建/删除 `j401` 临时数据库；结束后再次核对显式实例身份并读库清单/连接：`p1_t_j401_%` 数据库 0、连接 0。原始日志只留主 checkout `.migration-artifacts/j4-01-index/`。
- scanner 最后兼容修正与公开 activeRelease 后已复跑全部本卡单元/PG；最终原始日志 `.migration-artifacts/j4-01-index/final-single-card.log`。

## 卡内六项验收

| 任务卡项 | 实际证据 |
|---|---|
| 1. 同快照零新增、正文/metadata/知识修订逐项变化 | `IndexServiceTest.identicalMetadataReusesIdsAndSkipsPublishEmbeddingAndUsage`、`bodyMetadataConfigurationAndPureDeletionPlans`；PG `publicIngestIdempotencyAndContentMetadataIncrement`、`pureRemovalAndConfigurationDrift`、`publishedKnowledgeOnlyRevisionAndOrder`。纯删除零 embedding，变更旧节点保留。deleted 与 Python 一致，按旧 id 不在新成员中的数量统计，更新换 id 也计入。 |
| 2. Unicode/CRLF/排序/切块边界/JSON 黄金 | `BoundedLinesGoldenTest.pythonGolden` 21 项；`YamlKnowledgeSourceTest` 7 项；PG `completeSnapshotMatchesFrozenPythonContentMetadataConfigurationAndOrdering` 与 10 节点 Python snapshot fixture。 |
| 3. 缺源/空源/失败及非法 embedding 不动 active；已花用量可见 | 单元 7 类 invalidEmbedding/前批成功后批失败；PG `missingAndEmptySourceNeverReplaceActive`、`invalidEmbeddingsDoNotMutateActiveOrReleaseTables`、`consumedUsagePersistsEvenWhenLaterBatchOrCasFailsAndLedgerFailureDegrades`。unknown token 保持 NULL；写账异常按 Python 告警降级。 |
| 4. 两发布者 CAS 单赢家、失败无半 release、历史引用可读 | PG `sameExpectedActiveTwoConcurrentPublishersOnlyOneWins`、`releaseAndMembershipAndPhysicalFailureRollBackWholeTransaction`、`publicScanThenPlanDoesNotOverwritePublicationDuringScanWindow`、首个增量测试的旧 `li_` 引用正文断言。CAS 指针、节点、release、成员同事务。 |
| 5. 回滚检查/只改 active/会话绑定不漂移 | PG `checkedRollbackRejectsUnknownConfigurationMembersAndPhysicalDamage`、`legitimateRollbackOnlyChangesActiveAndSessionBindingDoesNotDrift`；复用实际 SessionService，非伪造结果。统一 `IndexHealth` 输出 declared/members/physical/configMatch。 |
| 6. 合成 PG 先行，P5 再评估真实快照/embedding/质量 | 本卡只使用临时合成文件、固定向量与显式 PG；P5 live/质量未执行。本证据不宣称真实召回/生成质量等价。 |

额外入口保护：`generatedFtsConfigurationMismatchFailsBeforeEmbedding` 证明活生成列 FTS regconfig 与配置不一致时硬失败；`safeYamlRejectsArbitraryTags` 使用 SnakeYAML SafeConstructor；扫描源根/文件/目录内符号链接越界均拒绝。

## 可复现命令

工作目录为 J4-01 工作树。无 PG：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./backend-java/mvnw -f backend-java/pom.xml -q -Dtest=BoundedLinesGoldenTest,YamlKnowledgeSourceTest,IndexServiceTest test
```

PG 在主 Agent 分配独占窗口后，显式设置 `DPDK_TEST_PG_URL/USER/PASSWORD/DATA_DIR`，运行同命令并增加 `PgIndexIntegrationTest`。凭据由主 checkout 的 ignored 本地文件读取，不出现在提交、日志摘要或命令参数中。无环境时 PG 测试整类跳过，不能记通过。

黄金生成命令（不联网/不接库）：主 checkout `.migration-artifacts/p0/venv/bin/python docs/java-migration/evidence/J4-01/generate_golden.py`。生成器读取冻结 Python 纯函数；完整扫描只用临时合成源，published SQL 由固定 rows 替身替换。生成器与 Java 实现独立，禁止用 Java 结果重写黄金。

## 接线与限制

- 入口 `IndexService` 显式构造，`activeRelease()` 供健康/运维复用同一仓储；`SourceSettings` 四个 Path 参数，真实 scan 在读文件前固定 expectedActive，SourceSnapshot 保留两参和单参合成构造。plan/ingest 使用原 active 做 CAS。
- `IndexBatchEmbedding.embed(List<String>, UsageCore.UsageRecorder)`；`IndexUsageSink.flush(UsageRecorder)`。服务按 1..10 分批，接线适配由主 Agent 完成，公共 EmbeddingPort 未改变。
- 当前 main 已有 SnakeYAML 传递依赖且本次编译通过，无新增 pom；直接依赖显式化由主 Agent 决定。
- 无 DDL、共享配置、wiring、HTTP/CLI/调度修改；不清理物理旧节点。健康校验及回滚共用单一服务；当前 rollback 的健康读取与 active 更新按 Python 原行为分段，未引入额外分布式协议。
- 回退：撤下 index 接线与写入者，代码回退本卡提交；仅合成库随测试销毁，生产没有索引写入或回滚动作。
