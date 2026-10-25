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
| 同上三类单卡，接 `cab2bdf` 后（本地cherry-pick `83a3219`） | exit 1，19例：7通过、4失败、8错误、0跳过 | 精确发现上游窄修误命中，失败继续保留 |
| 同上三类单卡，接 `ada65f6` 后（本地cherry-pick `f19f3a2`） | **exit 0，19通过、0失败、0错误、0跳过** | 4并发 + 6发布/真实复验 + 9权限/工作流/黄金输入 |

PG 连接：仅显式 `127.0.0.1:55481`、角色 `p1_java`、主副本 `.migration-artifacts/p1/pgdata`。口令从主 Agent 指定的 gitignored 凭据行读取，仅置于子进程环境。`TestPg.createDatabase` 在建库前校验实例角色和 data_directory；每例独立动态测试库，finally 删除本轮库。无旧 `.env` 回退。

原始日志只在本工作树 `.migration-artifacts/j4-02-compile.log`、`j4-02-testcompile.log`、`j4-02-testcompile-2.log`、`j4-02-tests-{1,2,3}.log`，不提交。

## 首轮失败与修复责任

1. 真实 task→issue 集成发现：`TaskStore.insertIssue` 使用随机 `id`，而冻结 Python `_issue` 使用 `id=request_id`（实际 taskId），`RagTaskService.finishDetail` metadata.issue_id 也返回 taskId。结果自动问题的 metadata 指向不存在的 issue，反馈/读取和合并测试 404。已报主 Agent，由 task owner 修复；本卡不改 task 包。
2. 测试夹具曾给 `rag_index_state` 添加不存在的 `updated_at` 列，已按冻结 DDL 的 `name/release_id` 修复。此为测试修复，产品 DDL 未改。
3. 上游 `cab2bdf` 的同形SQL窄修误命中 `insertJob` 而非 `insertIssue`，已由主 Agent `ada65f6` 精确恢复job随机主键、修正issue/task身份；首轮和第二轮失败未覆盖。
4. 首次并发反馈的 `id/request_id` 双唯一键冲突：工作流创建问题使用 `ON CONFLICT DO NOTHING`，随后按request_id锁行；避免双连接首次反馈因非指定arbiter主键竞态出错。最终两条反馈均保留事件、只生成一问题，且时间戳严格递增。
5. pending查询测试使用冻结真实表名 `data_li_knowledge_v1`，已修正误用逻辑别名 `li_nodes` 的测试夹具，不改产品DDL。

## 最终断言

- 本人消息反馈唯一性、错误身份404、重复反馈重开/清验证、运行输入脱敏；teacher树隔离、leader全树、分页、认领、note、事件顺序。
- 自合并400、合并到已合并目标400、有合并来源转合并409，双连接互合并只有一个成功且无环。
- 当前release下answered复验才能resolved；partial和新release旧验证409；双连接新反馈/教师编辑让旧markVerified CAS失败且不写verify事件。
- shared_confirmed必需、作者/leader修改、其他老师404、revision过期409、并发revision一个成功一个409、历史完整；revision事件写失败回滚父知识行。
- 人工知识代码原文字节保留；pending知识全局可查但active release和LI成员不变；发布/撤回进入检索由J4-01组合补验。
- 180天边界保留，严格超龄只清工作流记录/事件；task请求和会话消息不由本卡删除，失效owner置NULL。
- 五条P0-v2冻结422响应逐JSON对照（GET issues / PATCH issue / POST feedback / POST+PATCH knowledge）。
- **真实复验链两例**：GroundedQuestionVerifier→真实PgReleaseReader→脚本化外部生成模型→显式judge→CAS；onlineJudge默认false仍judge一次，使用active-new而非原请求release-1，配额真实扣一次、admission归零、没有新task/message；生成期间新反馈使最终409，新反馈及open状态保留。

## 验收边界

- 本卡测试检查真实身份/任务/事务/配额/工作流持久化；并发校验以 PostgreSQL 双连接和独立服务实例执行，不以进程内锁替代数据库 CAS。
- 部分并发测试直接调用 `markVerified` 专验落库不变式；另有两例实际消费主 Agent 真实chat bridge，模型和embedding是唯一外部替身。知识发布→索引重建→复验→解决的完整合成场景仍由主 Agent 与index worker组合验证。
- pending 发布可查询与 active release 检索可见性分开；本卡不重建/切换索引。
- J4-02 H 正文脱敏指用户问题/反馈/note/事件等运行输入；人工知识 title/body 按 Python 原文保存，教学代码由人工 shared_confirmed 确认，未新增产品语义差异。
- 留存只实现 `maintainInTx(DbTx,int days)` 工作流参与者；task 临时输入、job、request 的180天整笔清理仍待主 Agent 协调接线。
- 实现、自动验证、完整场景验收、用户验收分别记录；本文件不放行 P4。
