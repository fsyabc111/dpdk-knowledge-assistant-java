# J5-USAGE-CLOSURE 交接

- owner/工作树：model_adapter；`.migration-artifacts/worktrees/j5-usage-closure`，分支 `codex/j5-usage-closure`。
- 基线：`f1c5bb5584e5ee5c71ed9f6fbd3f383e92b3c22f`；旧p3-model未跟踪StreamTerminalLedgerVisibilityTest完整保留，并在主checkout `.migration-artifacts/recovery/p3-model-terminal-ledger/`另存SHA备份，未reset/clean。
- 当前范围：主Agent已裁定为P5独立观测完整性修复。Python同缺陷单列，不改黄金、业务扣次/成绩/终态状态/安全语义。
- 当前状态：核心实现已完成、纯测试14/14；ChatTaskExecutor/RequestMetrics接线等待主Agent完成engine归属修复后释放写锁；完整HTTP/PG场景已红，尚未绿。用户验收未执行。

## Python对照与已批准边界

冻结Python `core/async_cache.py`使用asyncio.shield；`retrieval_service.py`共享loader捕获发起请求UsageRecorder；`rag_product_service.py:run finally→_log_trace`与`usage_service.py:flush_trace_usage`一次快照。使用真实AsyncTTLCache/UsageRecorder、asyncio.Event复现：owner取消finally entries=0，放开loader后recorder entries=1，已flush快照仍0。日志在本worktree `.migration-artifacts/probes/python-shield-usage-difference.txt`。这是源端同形缺陷，不私改oracle。

主Agent裁定：终态前原子checkpoint+安装迟到sink；迟到实际收据归原request/subject/session，PG提交后发布counter；共享loader不杀、不等90秒；cache waiter仅零调用。未收到回执时进程被SIGKILL所需durable transport-start unknown ticket，不在本片承诺，不伪造提前unknown。

## 当前改动

- `usage/UsageCore.java`：线程安全快照与producer lease；checkpointAndSubscribe原子切分历史/增量；sink和completion回调在monitor外；checkpoint ack等待已登记producer和sink退出；失败交付以异常ack暴露，不覆盖业务；drain释放归账闭包。
- `retrieval/RetrievalUsage.java`：创建共享异步loader之前登记lease、finally关闭；取消waiter也记录cache_hit/call_count=0；移除外层recorder同步块，允许迟到sink锁外写库。
- 新纯测试 `UsageReceiptCheckpointTest`（3项）、`CancelledCacheWaiterUsageTest`（1项）。
- 新 `LateUsageClosureIntegrationTest`：真实PG与本机embedding HTTP，闩锁确认调用已发、响应未返；取消须及时终态/清输入，放开响应后迟到收据须一次归原请求与afterCommit观测。初始known用例已在旧树红；现已扩known/unknown两分支，待接线后执行。

## 实际验证

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest=CancelledCacheWaiterUsageTest
# 修复前1失败：取消waiter账本size0，预期cache_hit/0；owner共享future正常。

# 四个显式独立PG环境下（TestPg先验；主checkout55481/p1_java/专属pgdata）：
./backend-java/mvnw -f backend-java/pom.xml -B test -Dtest=LateUsageClosureIntegrationTest
# 修复前1失败：取消已终态后HTTP迟到收据的afterCommit latch超时，无sleep。

./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest=UsageReceiptCheckpointTest,CancelledCacheWaiterUsageTest,RetrievalUsageTest
# 核心修复后14/14，失败0、错误0、跳过0。
```

原始日志本worktree `.migration-artifacts/probes/`，gitignored。PG红窗口已归还主Agent，测试库/loopback服务已清理。未调用真实模型/embedding/harness，无原环境写入。

## 下一步与回退

取得root engine修复提交及ChatTaskExecutor/RequestMetrics写锁后接线：candidate在提交前持lease，bounded完成握手；afterCompletion挂锁外增量sink并flush checkpoint、ack；提供可等收据完成信号供测试/collector/停机检查；移除任何包在recorder外层同步块内的JDBC路径。

完整known/unknown HTTP-PG、共享等待者、普通中断unknown和late counter需复验；未通过前不宣称该阻断关闭。代码回退撤销本片，不动DDL、黄金或恢复资产。
