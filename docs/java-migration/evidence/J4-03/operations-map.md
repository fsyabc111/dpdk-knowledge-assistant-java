# J4-03 旧入口与Java物料映射

核对基线：5d0d6a3。Python文件仅作只读用途枚举，未执行旧入口。实际交付与未验证不混列。

| 旧脚本 | 用途 | Java入口/策略 | 当前验证域 |
|---|---|---|---|
| backend/scripts/create_user.py | 创建学号用户、角色、配额 | operations.sh create-users；默认dry-run，--apply复用AdminUserRepository | 纯解析/端口分派通过；真实PG由集成者适配后验证 |
| backend/scripts/purge_sessions.py | 会话留存清理 | purge-sessions；默认30天，dry-run/--apply | 真实SessionService适配待集成 |
| backend/scripts/rag_maintenance.py | 问题留存、索引回滚、只读盘点 | retain-issues / rollback / audit-grading-jobs；问题留存与任务输入单独命令 | J4-01/02真实适配待集成 |
| backend/scripts/ingest_llama_index.py | 全源扫描/嵌入/CAS发布 | ingest --manifest /绝对路径；dry-run扫描预览无embedding；根由显式配置给出 | IndexService适配待集成；默认不外发 |
| backend/scripts/rag_judge_eval.py | 离线judge报告 | judge --cases /绝对路径；本批固定样例或skipped_live/not_authorized | 固定验证不代表真实judge质量；真实模型未授权 |
| backend/scripts/audit_grading_jobs.py | 只读盘点历史成绩缺关联 | audit-grading-jobs --limit 200（1..10000） | 业务只读聚合适配待集成，不补写、不删除 |
| backend/scripts/assign_anonymous_sessions.py | 一次性匿名会话归属迁移 | 保留Python冻结参考；Java日常CLI不提供归属重写 | 原匿名数据迁移须另定数据范围/核验，当前不执行 |
| backend/scripts/migrate_sqlite_to_postgres.py | 一次性SQLite→PG迁移，可truncate | 保留参考，Java PostgreSQL冷启/0013升级用唯一受控MigrationController | 不提供日常truncate/SQLite迁移入口；历史资产不删除 |
| 无同名旧CLI（task TTL在服务） | 临时任务输入终态/TTL兜底 | purge-task-inputs；default dry-run / --apply | TaskStore公开维护方法需集成者补，不复制SQL |
| backend/tests/rag_eval | 检索验收 | retrieve --query TEXT --top-k 5；固定向量合成语料走真实检索 | 默认不真实embedding；适配器必须拒绝未经授权外发 |

Java日常主后端与CLI不导入Python服务，不运行旧start_dev/local_harness/deploy入口。冻结Python oracle和独立teaching_harness仍按既定边界保留。

## 日志与指标映射

`/metrics` 只供后端内网；nginx明确拒绝 `/metrics`、`/actuator`，只代理 `/api/`。HTTP统计真实filter dispatch：`assistant_java_http_requests_total{method,status}`、`assistant_java_http_dispatch_duration_seconds_{count,sum}`。只标签固定method/status，无路径、用户、会话、正文、源码。SSE此耗时不代表完整流生命周期，不能与Python端到端耗时直接比较。

领域指标消费 `OperationsMetricsPort.Snapshot` 只读快照；适配器不给出的指标不能用静态0冒充。领域账本聚合导出为 **gauge**（即便历史名称带_total），表示保留账本当前快照，删除/留存会减少；不宣称与Python进程counter语义完全相同。缺失项用 `assistant_java_domain_metric_available{metric="..."} 0` 标示。标签白名单拒绝question/user等正文维度。

| Python领域名 | Java状态与语义 |
|---|---|
| assistant_llm_requests_total / assistant_llm_request_duration_seconds | 待共享模型调用hook提供；不能由HTTP次数替代 |
| assistant_chat_retrieval_duration_seconds / assistant_chat_stage_duration_seconds | 待真实trace/hook；不能用dispatch时延冒充检索阶段 |
| assistant_chat_fallbacks_total / assistant_rate_limit_hits_total | 待共享fallback/limiter hook |
| assistant_rag_outcomes_total / assistant_rag_duration_seconds / assistant_rag_validation_duration_seconds | 待Task/验证真实事件hook |
| assistant_agent_tool_calls_total / assistant_agent_tool_duration_seconds / assistant_agent_tool_result_bytes / assistant_agent_request_tokens | 待Agent真实工具/预算hook；不以空0宣称兼容 |
| assistant_upstream_usage_tokens_total / assistant_upstream_usage_cost_micros_total / assistant_upstream_usage_unknown_total | 可由主集成者账本聚合适配；仅统计确实返回的用量，unknown独立计数；缺账不折0 |

既有SessionService保留结构化session_purge日志；其他模型/任务结构化事件归各真实业务owner。CLI stdout为单个JSON结果，stderr只输出校验错误或异常类名，禁止堆栈/配置/正文。集成阶段必须核对账本unknown与实际事件后再声明领域监控完成；当前本卡独立单测仅证明HTTP filter及安全导出，不证明未接hook的指标。

## 当前 judge 映射修正（2026-10-25）

`rag_judge_eval.py` 评的是 **候选答案+内嵌证据的证据裁决**，不评检索命中率。当前映射如下，替代前表中宽泛的 judge 说明：

| Python 用途 | Java 入口 | 运行/验收口径 |
|---|---|---|
| rag_judge_eval.py + tests/rag_eval/judge_cases.yml | `judge --cases /绝对路径/cases.json或yml` → OfflineEvidenceJudge → 既有 AnswerValidator | `{cases:[name, mode, question, answer, evidence, expect_status, expect_reason]}`；deterministic 零模型；judge 默认未授权逐例 skipped_live_not_authorized，不计通过；显式启用才用注入模型与超时 |
| tests/rag_eval 检索样例 | `retrieval-eval --cases /绝对路径/retrieval-cases.json` → evaluateRetrieval | 保留原固定 query→vector 检索命中率算法，domain=deterministic_retrieval/non_live，不充当证据裁决 |

Python 脚本源码的 mismatch 是报告-only，正常执行退出0。Java 保持该退出语义，summary 分列 deterministic_mismatched/model_mismatched/runtime_errors/skipped；不能拿退出0作阶段通过。非法文件/参数/装配失败仍非零。严格 JSON、共享 JUDGE_PROMPT、无工具调用、限时 Future+取消复用现有证据规则。原始响应、候选源码与完整 prompt 不进入 CLI 报告或错误日志。

当前纯自动验证：OfflineEvidenceJudge/OperationsCli/OperationsMetrics/OperationsBusinessAdapter **24/24，零跳过**；仅内嵌固定样例与固定模型边界，无真实外发或 PG。详细证据见 `adapters.md` 的修正节；共享配置及 live 质量未验收。
