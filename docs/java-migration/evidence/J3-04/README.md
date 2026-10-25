# J3-04：已有成绩按需讲解证据

## 范围和验证对象

- U12 隔离/合成范围；真实模型、embedding、harness 均未调用。
- 业务链：`SubmissionExplainController → ExplainDataService → ProductChatService.startExplain → RagTaskService/TaskRunner → ChatTaskExecutor/AgentOrchestrator → TaskStore.finish`。
- 权限/作业/成绩/任务/会话/配额/事务/检索/引用/持久化均为真实 Java+独立 PG；仅外部模型、1024维embedding、MCP 是固定替身。
- 本卡实现、自动验证、合成场景分列；用户验收未执行。P3 放行由主 Agent 根据集成树复验和双轴审查决定。

## 卡片 A–G 对照

| 要求 | 实际断言与证据 | 结果 |
|---|---|---|
| A 权限与装配 | 真实认证令牌；匿名401；missing/非本人/作业不符/不可见同404；local整组404；模型缺装配503且零任务行 | 通过 |
| B 截止后与标题变化 | 已截止作业仍started；完成后改标题仍replayed原task/session，不重跑模型 | 通过 |
| C 并发、附着和配额 | 12并发首发：1 started+11 attached，恰一任务/一user消息/一次chat扣额；pending HTTP附着；终态HTTP重放 | 通过 |
| D 零重评/成绩不变 | grade_plugin=0；讲解前后grading_submissions整行JSON精确相等、行数=1；不新增正式成绩 | 通过 |
| E 进程恢复与伪造反例 | 真第二JVM：全新registry/executor/context缓存，DatabaseIdentityGuard先验身份，按可信成绩重建当前标题/80分；purpose+派生双身份+本人反例；普通chat即使携派生ID和context.purpose也只落chat且不播种成绩 | 通过 |
| F 参考与源码 | 全PASS：MCP全部0；FAIL：get_reference_answer=1，grade_plugin=0；context字段严格白名单，不含source_text/code_hash；日志仅安全标记/计数 | 通过 |
| G 断线/取消/停号 | 退订后任务继续pending/running；显式取消后迟到候选不覆盖cancelled；终态输入清理；失效令牌讲解重放与任务查询均401；DB终态SSE重建 | 通过 |

`ExplainRecoveryProcess` 是真实 Java 任务引擎恢复进程；完整 HTTP 服务重启、浏览器断线和隔离部署恢复仍留 P5，未用本卡替代。

## 命令和结果

工作目录为本卡 worktree；PG四项显式环境按 backend-java/README.md 指向专属55481，口令从gitignored凭据文件读取，不写证据。

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest=SubmissionExplainContractTest,ExplainDataIntegrationTest,ExplainReplayIntegrationTest,ExplainRecoveryIntegrationTest
# 实际：23/23，Failures=0，Errors=0，Skipped=0，BUILD SUCCESS
python3 backend-java/src/test/resources/migration/J3-04/generate_explain_golden.py
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest=SubmissionExplainContractTest
# 后续纯函数oracle增补：13/13（原12 + 1黄金循环），零失败/错误/跳过
```

最终本卡合计24个测试：13纯函数（含黄金循环：3派生身份+8context向量）+3数据面+4重放+4恢复。23项全链后仅新增纯函数黄金对照，最终24项集成树统一复验由主 Agent 执行，不能把两次命令说成一次全量24项运行。

- 黄金生成从冻结 Python AST 提取 `explain_request_identity/build_explain_context`，不导入服务、不联网、不使用 Java 输出生成 expected。
- fixture SHA256：`33cc9a9a7cefaa59a80e4bd5723fc945d62620e3672467b05f1f2471e78836ab`。
- 第二 JVM 命令：`java -cp <test-classpath> io.dpdk.assistant.assignment.explanation.ExplainRecoveryProcess <synthetic-db> <task_id>`；继承四项显式PG环境。
- 第二 JVM 成功输出：`RECOVERED_CONTEXT=true, MODEL_CALLS=2, GRADE_CALLS=0, REFERENCE_CALLS=1, STATUS=answered`；父进程断言一assistant消息/一次扣次/同task重放/成绩快照不变/输入清理。
- 动态测试库均由 TestPg 预检后创建和删除；本卡无运行进程/端口/容器。

## 首轮失败与处置（不覆盖历史）

第一次21项：17通过、3失败、1测试错误。真实Python产品验证器不把预播种成绩计作新工具证据，而旧Python讲解测试mock了validate。成功场景改为固定模型先kb_search、真实检索合成知识、引用final；保留独立的 `unanswered/no_evidence` 反例断言，生产门禁未放宽。取消测试改为取消前保留句柄，避免中断回查失败后registry已移除时二次查找NPE。第二轮23项全部通过。

原始证据（gitignored）：

- `.migration-artifacts/j3-04/full.log`：首轮失败。
- `.migration-artifacts/j3-04/full-rerun.log`：23/23。
- `.migration-artifacts/j3-04/pure-golden.log`：13/13纯函数最终增补。
- `backend-java/.migration-artifacts/j3-04/recovery-child-15520e142b3d4bac92b63ec928477314.log`：成功第二JVM。

## 接线与剩余

- 主 Agent 统一装配 `ChatTaskExecutor.setExplainResolver(ExplainDataService::resolve)`；worker基座已按同形装配验证，完整Spring应用探针由主Agent执行。
- 此卡不改public契约、不改成绩/参考答案策略、不伪造新评测。
- 无本卡未完成实现或合成场景阻断；仍需集成树复验、P3双轴审查。live/用户验收未执行。
