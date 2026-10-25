# J4-03 运维业务适配交接

- owner/工作树：ops_integration；`.migration-artifacts/worktrees/j4-adapters`，分支 `codex/j4-03-adapters`。
- 源基线：`79ca3d0`（已含 `ada65f6`）；授权 U12/P4。
- 状态：实现完成；单卡自动验证 7/7 完成；共享装配与阶段场景验收待主 Agent；用户验收未执行。

## 完成与文件

| 路径 | 改动目的 | owner范围 |
|---|---|---|
| backend-java/.../operations/adapter/OperationsBusinessAdapter.java | 9 个真实业务运维方法，只调用领域公开接口，无 SQL | 是 |
| backend-java/.../operations/adapter/OperationsDomainMetricsAdapter.java | 持久任务/作业/用量 gauge，明确 unavailable 与 unknown | 是 |
| backend-java/.../task/TaskStore.java | 只增维护/清输入/审计/只读聚合及自有 callback；原方法行为与锁序不变 | 窄授权 |
| backend-java/.../usage/UsageLedgerStore.java | 只增不截断的只读 metricsSnapshot，保留 SQL NULL | 窄授权 |
| backend-java/.../admin/AdminUserRepository.java | 只增创建预览，复用角色/配额规则与 ID 模式 | 窄授权 |
| backend-java/src/test/.../operations/adapter/OperationsAdaptersIntegrationTest.java | TestPg 身份先验动态库，全部 public 表快照/同事务回滚/固定向量 | 是 |
| evidence/J4-03/adapters.md | 业务路径、指标映射、7 场景与限制 | 是 |

## 决策与接口差异

仅增加领域公开方法，无 REST/冻结契约/DDL 变化；TaskStore.MaintenanceParticipant 保持 task 不依赖 knowledge，由 adapter 传 `workflow::maintainInTx`。dry-run 不调用该写入口，工作流具体计数明确待 apply，不返回假零。

主 Agent 已确认以下构造签名，尚需共享 wiring 集成：

```java
OperationsBusinessAdapter(AdminUserRepository admin, SessionService sessions,
    TaskStore tasks, KnowledgeWorkflowService workflow, IndexService index,
    PgReleaseReader retrieval, Function<Path, SourceSettings> sourceSettings,
    Map<String, Vector1024> fixedRetrievalQueries)
OperationsDomainMetricsAdapter(TaskStore tasks, UsageLedgerStore usage)
```

`index/retrieval` 可 null → 能力不可用时报错；sourceSettings 必须由显式配置解析 manifest+dpdkRoot+assistantRoot/workingDirectory，不能猜外部根。
**运维 reader 必须单独 new PgReleaseReader(..., new HeuristicQueryRewriter(), null)**。产品 reader 即使有 queryVector 仍会先调用可能外发的 QueryRewritePort；主 Agent 已收到并承诺专用接线。固定 query→vector 默认空，未授权 query 拒绝。judge JSON 是固定检索样例，整批 pin 同一 release，返回 deterministic_retrieval/non_live，不代表原题模型证据核验，不调用 OriginalQuestionVerifier。

## 验证证据

- JDK21 compile/test-compile 通过；最终 `-Dtest=OperationsAdaptersIntegrationTest test` **7/7、0 skipped**（19:54:36 +08:00），无真实上游。
- PG 端口 55481，角色 p1_java，DATA_DIR 主 checkout `.migration-artifacts/p1/pgdata`；主 Agent 串行窗口，TestPg 先验后建/删动态库；已 teardown、已归还窗口。
- dry-run 全 public 表快照（包括 updated_at、身份、配额/用量）不变；apply 终态/TTL/孤儿输入清理、恢复保护、180d 留存、事件→问题→输入→作业→请求顺序、工作流原子回滚及幂等；其余断言见 evidence。
- 初次 fixture NOT NULL 错误已修复且重跑通过；完整日志位于该 worktree `.migration-artifacts/`，未提交。

## 未完成、阻塞与回退

无单卡阻塞。全量回归、共享 Bean/CLI 启动、真实上游、部署/备份恢复未验证。原 Python/部署/数据库只读未改；未修改 pom/contracts/platform/wiring/DDL/Application/STATUS/MAIN、Compose 或 fake-upstream。代码可撤销此提交；测试动态库已删除，不触碰恢复资产。

## 恢复点

无运行中进程/测试库；TaskStore/UsageLedgerStore/AdminUserRepository 窄写窗口交还主 Agent。下一步：主 Agent cherry-pick 本分支提交，接共享 Beans，用专用本地 reader，运行组合/全量验收并按授权推送目标开发分支；worker 未自行合并 main 或发布。

## 证据 judge 修正增量交接（2026-10-25）

前述 judge=固定检索算法的入口映射已修正，原算法完整迁到 `evaluateRetrieval(Path)` / CLI `retrieval-eval --cases`；不再把命中率当成 Python rag_judge_eval 的证据裁决。原 PG 检索断言也已迁移保留，当前仅重新 test-compile，未占 PG 窗口重跑。

新增 `OfflineEvidenceJudge(AgentChatModel model, Duration timeout, boolean modelJudgeEnabled)`，实现 `Function<Path,Map<String,Object>>`。Adapter 原8参保留，新增9参重载最后传此 Function；8参的 judge 明确未装配。主共享 wiring 使用默认 false 的 modelJudgeEnabled；deterministic 直接复用 AnswerValidator 零模型；judge 未授权 matched=null/skipped_live_not_authorized，显式启用才限时调用注入模型，空模型配置报错。复用共享 JUDGE_PROMPT、严格 JSON、无工具、超时取消；不复制证据判据，不输出候选源码或完整 prompt。

Python 源码已核对：正常生成报告时所有 mismatch 报告-only/exit0；Java 保持。summary 分列 deterministic_mismatched、model_mismatched、runtime_errors、skipped；不能将 exit0 当质量通过。逐case有 expect/actual/matched/error/usage，不授权的模型例不算通过。

新增/修改授权路径：operations/OfflineEvidenceJudge.java、OperationsPort.java、OperationsCli.java、operations/adapter/OperationsBusinessAdapter.java及对应测试、OperationsAdaptersIntegrationTest中入口改名；仅追加本卡 evidence/operations-map/handoff。Task/Usage/Admin 数据仓储本增量未改，OperationsWiring 未触碰。

当前验证：`-Dtest=OfflineEvidenceJudgeTest,OperationsCliTest,OperationsMetricsTest,OperationsBusinessAdapterTest test`，**24/24，0 skipped**，2026-10-25 20:06:36 +08:00；JDK21 compile/test-compile 同时通过。初次新增测试因泛型断言编译错误已修复，最终日志 `.migration-artifacts/offline-judge-final.log`。无 PG 或真实模型调用；历史7/7 PG不充当本提交新运行证据。后续由主 Agent cherry-pick 增量、接共享Bean、组合验收并推送。
