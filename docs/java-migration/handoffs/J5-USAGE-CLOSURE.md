# J5-USAGE-CLOSURE 交接

- owner/工作树：model_adapter；`.migration-artifacts/worktrees/j5-usage-closure`，分支 `codex/j5-usage-closure`。
- 基线：`f1c5bb5584e5ee5c71ed9f6fbd3f383e92b3c22f`；root engine归属修复已合并 `0d95a6b`，未覆盖其error/cancelled/unknown engine和产品不计agentTokens决定。
- 提交：核心 `60a73e5`（已由root集成）；真实HTTP红回归 `b325d77`；产品闭环 `8c04f09`。Reader父片 `5d8a9ff` 与capacity `f5d6ce4` 为同补丁，归capacity，不应重复cherry-pick。
- 状态：实现完成；纯测试16/16、真实HTTP/独立PG局部7/7，无失败/错误/跳过；场景局部验证完成；全量verify、差分矩阵、formal容量与P5重审由root安排，未在本片执行；用户验收未执行。
- 已释放：ChatTaskExecutor/RequestMetrics/CandidateServer写锁均明确交回root，后续本worker不再并写；PG局部库与loopback服务已清理。

## 已批准范围与Python同缺陷

冻结Python `core/async_cache.py`使用asyncio.shield；`retrieval_service.py`共享loader捕获发起请求UsageRecorder；`rag_product_service.py:run finally→_log_trace`与`usage_service.py:flush_trace_usage`只快照一次。使用真实AsyncTTLCache/UsageRecorder、asyncio.Event复现：owner取消finally entries=0，放开loader后recorder entries=1，已flush仍0。日志 `.migration-artifacts/probes/python-shield-usage-difference.txt`。

root已裁定为P5独立“观测完整性”修复并登记DECISIONS；不改Python黄金、业务扣次/成绩/终态状态或安全语义。终态前原子checkpoint+安装迟到sink；实际迟到收据归原request/subject/session，PG提交后发布counter；共享loader不因owner取消而被杀，不等90秒；未实际执行的cache waiter只有cache_hit/call_count=0。

明确不承诺：transport已发、未回执时立刻SIGKILL所需durable unknown ticket。本片没有猜测HTTP开始或制造提前unknown占位；进程在迟到回执之前被强杀仍可能丢这段未回执账。普通可中断调用已有的真实unknown回执必须照记，token=null不是0。

旧p3-model未跟踪StreamTerminalLedgerVisibilityTest完整保留，主checkout `.migration-artifacts/recovery/p3-model-terminal-ledger/`另存SHA备份（dd91c3bc24ff4e7676400a997b838e62849d53b6428c7e6c9e4a9222d8479e1a）；未reset/clean。

## 改动与API

| 路径 | 目的 |
|---|---|
| `usage/UsageCore.java` | 线程安全快照；`retainProducer()`在异步提交前登记lease；`checkpointAndSubscribe(Consumer<UsageEntry>)`原子切分历史/增量；sink及completion回调均在monitor外；checkpoint.entries/acknowledge/fail/completion显式区分交付成功、失败及未排空；drain后清receiver |
| `retrieval/RetrievalUsage.java` | shared loader提交前持lease，finally关闭；取消waiter也记cache_hit/0；移除外层recorder锁，避免sink锁内JDBC；SingleFlightTtlCache/model客户端本体未改 |
| `chat/ChatTaskExecutor.java` | candidate提交前持lease，启动前被取消用CAS放弃并归还；普通可中断finally最多握手100ms；afterCompletion原子checkpoint并锁外flush，迟到sink只捕获身份字符串/计价策略，无request正文/源码/context；JDBC临时清interrupt并恢复；失败不影响业务但ack异常 |
| 同上：collector/停机 | `accountingCompletion(taskId)`可提前取ack；`awaitAccountingReceipts(Duration)`失败/超时返回false，不能以terminal代落完账；同task多个恢复轮次用ack集合而非覆盖旧轮次；drain后移除map，停机只尝试250ms不等待90秒 |
| `chat/RequestMetrics.java` | 等producer/sink全部ack后publish一次，不把取消后晚到的生成/检索阶段永久丢掉；保留root engine/error修复 |
| `acceptance/contract/CandidateServer.java` | 仅改`/settled`：runner空闲之外还等accounting ack（2秒），防25表checkpoint早于晚到写；其他helper/矩阵未改 |
| caller唯一coupling行 | 已按授权识别capacity Reader的QUERY_EMBEDDING_FAILED_FALLBACK并设置query_embedding_failed；Reader本体改动仍归capacity |

API使用次序：producer在提交前retain→记录真实条目→关闭lease；终态前checkpoint注册迟到sink并取得历史快照→锁外写历史→acknowledge（或fail）；completion等待全部producer和实际sink交付完成。失败sink不向答案/取消抛错，但completion异常、collector=false，不能伪过。计量仍经UsageLedgerStore真实afterCommit路径，一次历史或一次迟到条目，不重复快照追加。

## 确定性红证据

使用diagnosing-bugs反馈循环，未用sleep/重跑碰绿：

1. `CancelledCacheWaiterUsageTest`：预先登记shared future，取消等待者，owner仍成功；旧树waiter entries.size=0，预期cache_hit/0，实际红。
2. `LateUsageClosureIntegrationTest`：真实HTTP收到embedding请求后把响应挡在闩锁，真实取消在2秒内terminal/清临时输入；随后放开HTTP，旧树迟到PG/afterCommit收据闩锁超时，实际红。没有更改fixture或削弱断言。

日志均在本worktree `.migration-artifacts/probes/`（gitignored）：`cancelled-cache-waiter-red.log`、`late-usage-http-pg-red.log`。

## 实际验证与结果

工作目录为本worktree，JAVA_HOME=/opt/homebrew/opt/openjdk@21。PG使用四个显式DPDK_TEST_PG_*，维护URL=jdbc:postgresql://127.0.0.1:55481/postgres、角色p1_java、DATA_DIR为主checkout绝对 `.migration-artifacts/p1/pgdata`。密码仅从主checkout local-credentials.txt解析DPDK_TEST_PG_PASSWORD进env，无输出/提交。TestPg在CREATE/DROP前先核验维护身份；仅操作唯一新合成库。

```sh
./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest=UsageReceiptCheckpointTest,CancelledCacheWaiterUsageTest,RetrievalUsageTest,ProductExecutionModeTest
# 16/16，0失败/错误/跳过；usage-product-pure-green.log。

# 显式独立PG环境下：
./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest=LateUsageClosureIntegrationTest,ProductProcessMetricsPgTest,UsageProcessMetricsPgTest
# 最终7/7，0失败/错误/跳过；2026-10-26 19:24:11结束，总3.615秒。
# 日志usage-closure-final-pg.log。
```

逐项证据：

- 原子checkpoint与32并发条目：历史+迟到并集32、distinct32，无丢失/双计；sink和completion回调明确断言不持recorder monitor；producer/sink未结束时ack不完成；重复lease.close无负数；失败sink使ack异常且不覆盖业务。
- 取消shared embedding owner：真实HTTP仍被闩锁挡住，取消<2秒、terminal=cancelled、输入清空；此时无query_embed虚构占位，ack未排空；放开响应后只写原request一条，真实afterCommit sample恰好一次。
- 同场景未知usage：token列保持null、source=unknown、call_count=1，非0；确认已实际发出一次HTTP。
- 同场景迟到PG拒绝：专属库CHECK拒绝query_embed；无该行、无afterCommit sample，ack异常/collector=false；取消状态仍成立。
- 普通真实chat HTTP中断：远端response闩锁尚未释放，取消<2秒；unknown/call1/token=null已在terminal前checkpoint，ack完成；不为等远端usage阻塞取消。
- 既有ProductProcessMetricsPgTest2项与UsageProcessMetricsPgTest1项同轮通过，保持root结果标签及commit/rollback观测边界。

## 资源、回退、下一步

新库/loopback服务与测试客户端均已finally清理；没有真实模型/embedding/harness、原项目/旧部署/业务库写入，没有删恢复资产。异步sink不保留原问题/源码，drain即释放闭包与ack map；异常只保留有限的首个归账错误状态，供collector拒绝伪完成。

root集成顺序：core60a（已合入）→测试b325→Reader f5d6（若尚未合）→产品8c04→本handoff证据提交。接线改后需要root重编译CandidateServer并跑严格25表/指标矩阵；不能用本片局部绿替代full/formal或P5工程门禁。代码回退撤销本片；没有DDL/数据格式/黄金改动，SIGKILL未知票据限制继续保留。
