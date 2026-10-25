# J4-02 验证记录

## 基线与范围

- Java 起点：`5d0d6a3`；分支 `codex/j4-02-knowledge`，独立工作树 `.migration-artifacts/worktrees/j4-02`。
- Python 行为基线：P0-v2 `migration/fixtures/p0-v2/python-golden.json`；人工知识接口源 `backend/app/api/rag.py`、反馈/验证/留存源 `backend/app/services/rag_issue_service.py`。
- 本卡只写 `knowledge/workflow/**`、对应测试、`resources/migration/J4-02/**` 和本卡交接/证据；无真实模型、embedding 或 MCP 调用。

## 实际执行

| 命令 | 结果 | 范围 |
|---|---|---|
| `JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B -DskipTests compile` | exit 0 | 工作流初版源码编译 |
| 同上 `-DskipTests test-compile` | exit 0 | 本卡与既有测试源码编译 |
| 同上 `-Dtest=KnowledgeWorkflowContractTest,IssueConcurrencyIntegrationTest,KnowledgePublicationIntegrationTest test` | exit 1，16例：5通过、2失败、9错误、0跳过 | 显式独立 PG 首轮；失败明细见下 |

PG 连接：仅显式 `127.0.0.1:55481`、角色 `p1_java`、主副本 `.migration-artifacts/p1/pgdata`。口令从主 Agent 指定的 gitignored 凭据行读取，仅置于子进程环境。`TestPg.createDatabase` 在建库前校验实例角色和 data_directory；每例独立动态测试库，finally 删除本轮库。无旧 `.env` 回退。

原始日志只在本工作树 `.migration-artifacts/j4-02-compile.log`、`j4-02-testcompile.log`、`j4-02-tests-1.log`，不提交。

## 首轮失败与修复责任

1. 真实 task→issue 集成发现：`TaskStore.insertIssue` 使用随机 `id`，而冻结 Python `_issue` 使用 `id=request_id`（实际 taskId），`RagTaskService.finishDetail` metadata.issue_id 也返回 taskId。结果自动问题的 metadata 指向不存在的 issue，反馈/读取和合并测试 404。已报主 Agent，由 task owner 修复；本卡不改 task 包。
2. 测试夹具曾给 `rag_index_state` 添加不存在的 `updated_at` 列，已按冻结 DDL 的 `name/release_id` 修复。此为测试修复，产品 DDL 未改。
3. 并发反馈首轮还出现 `rag_issues_pkey` 冲突，需待 task id 修复后复测，不能提前计通过。

## 验收边界

- 本卡测试检查真实身份/任务/事务/配额/工作流持久化；并发校验以 PostgreSQL 双连接和独立服务实例执行，不以进程内锁替代数据库 CAS。
- 当前测试直接调用 `markVerified` 检查验证落库不变式，不宣称完整原题检索/生成/judge 已通过；最终由主 Agent 的真实 chat bridge 和 index worker 合成验证。
- pending 发布可查询与 active release 检索可见性分开；本卡不重建/切换索引。
- J4-02 H 正文脱敏指用户问题/反馈/note/事件等运行输入；人工知识 title/body 按 Python 原文保存，教学代码由人工 shared_confirmed 确认，未新增产品语义差异。
- 留存只实现 `maintainInTx(DbTx,int days)` 工作流参与者；task 临时输入、job、request 的180天整笔清理仍待主 Agent 协调接线。
- 实现、自动验证、完整场景验收、用户验收分别记录；本文件不放行 P4。
