# J4-03 真实业务运维适配证据

- 日期：2026-10-25；owner：ops_integration。
- 基线：`79ca3d0`，包含 `ada65f6` 的多 job 独立主键与 taskId 问题身份修复。
- 授权：U12/P4；原环境、真实上游、生产发布与合并 main 均未执行。
- 工作树：`.migration-artifacts/worktrees/j4-adapters`；分支：`codex/j4-03-adapters`。

## 实现与边界

| 入口 | 真实业务路径 | dry-run/只读保证 |
|---|---|---|
| create-users | AdminUserRepository.previewCreateUsers / createUsers | 预览复用角色、配额常量与 ID 模式，查询已有用户及请求内重复，不哈希、不写库；拟创建/已存在/invalid/skipped 分列，skipped 保持原顺序 |
| purge-sessions | SessionService.purgeExpired | 直接复用其 dryRun，不复制会话 SQL |
| purge-task-inputs | TaskStore.purgeInputs | expires_at 到期、终态或孤儿输入清理；有效 pending 输入保留 |
| retain-issues | TaskStore.maintain + KnowledgeWorkflowService.maintainInTx | 默认调用方 dry-run；预览零写且不调用工作流写入口，工作流计数明确待 apply，不伪造零 |
| ingest | 显式 Function<Path,SourceSettings> → IndexService.scan/plan/ingest | dry-run 仅 scan/plan；不 embedding、不发布、不记用量。3 根路径来自注入配置，不猜测外部源 |
| rollback | IndexService.health / rollbackChecked | dry-run 先验证版本配置及 declared/member/physical 完整性；无写 |
| audit-grading-jobs | TaskStore.auditGradingJobs | 仅作业元数据、是否有结果及关联成绩数；不返回原始结果、输入或代码，不自动重评 |
| retrieve | 专用 PgReleaseReader + 显式固定 query→1024 维向量 | 未配置 query 立即拒绝；专用 reader 必须纯本地 QueryRewritePort，embeddingPort=null，不复用可能外发改写的产品 reader |
| judge | 本地 JSON 数组 `[{"question":"已配置固定问题","expected_chunk_ids":["li_..."]}]` | 全部先验证、整批固定同一 release，计算固定检索样例 recall；`domain=deterministic_retrieval, non_live=true`；不调用模型/harness，不替代 Python rag_judge_eval 的模型证据判断 |

留存对齐 `backend/app/services/rag_issue_service.py:maintain`：10 分钟陈旧且无有效输入的 pending 才判 interrupted 并登记问题；清到期/非 pending 输入；已删 user/session 引用归一；通过 callback 在同一 DbTx 调用工作流 owner 的引用归一与 events→issues 删除；再删除超留存期 inputs→jobs→requests。180 天场景实测；未复制工作流 SQL，原 TaskStore 方法及锁序未改。

## 持久指标映射

所有领域值都是当前持久账本聚合 gauge，留存删除后可下降；不能当作进程单调 counter。HTTP collector 仍提供真实 HTTP 请求数与 dispatch 耗时。

| 来源指标/需求 | Java 输出 | 口径 |
|---|---|---|
| assistant_rag_outcomes_total | 同名 gauge，status/reason | 非 pending 的现存任务行数 |
| 当前任务/作业 | assistant_java_persisted_task_records / assistant_java_persisted_job_records | TaskStore 公开聚合，状态/原因/工具维度，不含用户或正文 |
| 任务已保存耗时 | assistant_java_persisted_task_elapsed_seconds_sum | 终态 elapsed_ms 之和；明确不是完整请求 histogram |
| assistant_upstream_usage_tokens_total | 同名 gauge，stage/kind/source | 仅 SUM 已知 total_tokens；全 NULL 组不输出 token 零值 |
| assistant_upstream_usage_cost_micros_total | 同名 gauge，stage/kind/model/currency | 仅已定价成本与明确币种；不跨币种相加 |
| assistant_upstream_usage_unknown_total | 同名 gauge，stage/kind | usage_source=unknown 的账本行数 |
| 缺失 token/cost | assistant_java_usage_token_unknown_records / assistant_java_usage_cost_unknown_records | 显式缺失行数，不把 NULL 当零；cache/local/replay 的零调用不谎称未知上游调用 |
| 当前用量行/调用/已记录耗时 | assistant_java_persisted_usage_records / usage_calls / usage_duration_seconds_sum（均带 assistant_java_persisted_ 前缀） | UsageLedgerStore 不截断公开聚合，重复标签合并 |

以下原域观测没有足够持久证据，显式列在 `Snapshot.unavailable`，由已有 collector 输出 available=0，未填静态假零：
`assistant_llm_requests_total`、`assistant_llm_request_duration_seconds`、`assistant_chat_retrieval_duration_seconds`、`assistant_chat_stage_duration_seconds`、`assistant_chat_fallbacks_total`、`assistant_rate_limit_hits_total`、`assistant_rag_duration_seconds`、`assistant_rag_validation_duration_seconds`、`assistant_agent_tool_calls_total`、`assistant_agent_tool_duration_seconds`、`assistant_agent_tool_result_bytes`、`assistant_agent_request_tokens`。

## 当前实际验证

- JDK：`/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`。
- `./backend-java/mvnw -f backend-java/pom.xml -DskipTests compile`：通过。
- `./backend-java/mvnw -f backend-java/pom.xml -DskipTests test-compile`：最终通过。
- 主 Agent 串行授予 PG 窗口；环境显式为 `jdbc:postgresql://127.0.0.1:55481/postgres`、USER=`p1_java`、DATA_DIR=主 checkout `.migration-artifacts/p1/pgdata`。口令仅在进程环境中从主 checkout `.migration-artifacts/p1/local-credentials.txt` 的指定行解析，未输出或提交。
- `./backend-java/mvnw -f backend-java/pom.xml -Dtest=OperationsAdaptersIntegrationTest test`：最终 2026-10-25 19:54:36 +08:00，**7/7，0 failure/error/skip**；TestPg 建/删动态库前校验实例角色和 data_directory，迁移身份再次核验，测试后动态库 teardown 完成，PG 窗口已归还主 Agent。
- 场景：admin 已存在/无效/重复预览及 apply；终态/孤儿/24h 到期输入；180d 留存顺序、陈旧任务中断、有效输入恢复保护、引用归一、apply 后重复幂等；工作流中段异常整笔回滚；会话预览/apply及作业只读审计；已知/未知用量与非重复指标；ingest/rollback dry-run 无 embedding/flush/发布/账本变化、apply ingest+rollback、固定向量检索及整批固定 release judge。
- dry-run/read-only 前后动态枚举 **全部 public 表** 做完整 JSON 行快照比较，含任务 updated_at、输入、用量、配额、身份/令牌及工作流；不是只比较行数。
- 初次执行为 fixture 缺失 `rag_issue_events.detail` NOT NULL，已修复合成数据并最终复验通过；未改 DDL/放松断言。
- 日志保留本工作树 `.migration-artifacts/compile.log`、`testcompile-final.log`、`j4-adapters-integration.log`（初次失败）及 `j4-adapters-integration-final.log`；不提交原始日志。

## 未验证与下一步

未运行全量回归、共享 Bean 启动/CLI 端到端、真实模型/评测、真实部署、备份恢复。共享装配由主 Agent 独占，必须使用运维专用本地改写 reader；固定查询映射默认空，能力不可用时 fail-closed。实现完成/单卡自动验证完成；阶段验收与用户验收由主 Agent 记录。
