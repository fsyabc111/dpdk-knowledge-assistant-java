# SP-P5-01：embedding失败的Reader降级切片

源：冻结 `backend/app/services/retrieval_service.py:698-711`；审查：主checkout只读 `evidence/P5/review-spec-first.md` SP-P5-01。工作树已合并集成 `f1c5bb5`（本地merge `ed76816`），既有Python正式基线证据完整保留。

## 原因与改动

有效发布下，Java原实现将外部queryEmbedding异常直接传播，真实词法/路径召回未执行；源Python仅关闭语义腿并标记fallback。最小改动限 `PgReleaseReader.java`：

- 非取消类embedding失败保留现有真实客户端/显式用量桥产生的失败usage，查询向量置空后执行真实PG候选、排序、引用。
- 公开 `QUERY_EMBEDDING_FAILED_FALLBACK`，值为源原文 `query embedding failed; retrieval used lexical/path candidates`，追加至 `RetrievalTrace.fallbacks`；`blockedReason`仍只表示fail-closed，不拿embedding失败冒充配置阻断。
- 真正Thread中断、InterruptedException因果链、InterruptedIOException（排除SocketTimeoutException）、ClosedByInterruptException、ModelCallCancelledException/CancellationException继续传播，不能转成词法成功；普通网络SocketTimeout仍可降级。
- 不修改 `RetrievalUsage`、UsageCore、model、共享contracts或ChatTaskExecutor，不改黄金、阈值或原Python代码。

## 已执行红绿

显式四项PG环境指向独立55481测试实例；TestPg为本组创建唯一动态新库并预检身份，结束仅清理自己的短测试库。真实loopback端点503，真实OpenAiCompatibleEmbeddingClient记失败usage；没有真实上游。

1. 原实现运行 `-Dtest=EmbeddingFailurePgTest#httpFailureKeepsRealLexicalCandidatesUnknownUsageAndDoesNotPoisonCache test`：退出1，实际 `ModelHttpException: HTTP 503` 从Reader直出，未抵达候选断言。原始红日志 `.migration-artifacts/j5-02-sp-p5-01-red-runtime.log`（主checkout）。
2. 最小修复后执行：

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B -Dtest=EmbeddingFailurePgTest,PgRetrievalIntegrationTest,RetrievalPipelineGoldenTest,RetrievalFeaturesGoldenTest test
```

退出0，**15项执行/0失败/0错误/0跳过**：既有PG10、新PG3、固定向量/特征黄金2。绿日志 `.migration-artifacts/j5-02-sp-p5-01-reader-final-green.log`。证明：

- 有效release的真实FTS命中 `li_embedding_failure_dns` / `src/dns.c`；embeddingDim=0、语义候选0、真实节点/路径保留、显式fallback，blockedReason=null。
- 连续两次503各真实调用端点并记unknown、call_count=1、tokens NULL，非0费用猜测；失败没有填充embedding缓存。
- 同一输入恢复200后真实语义腿恢复且得到实际token2；之后真实cache_hit零调用，端点累计仅3次。
- 无真实候选时chunks/nodes保持空，未伪造资料；取消/中断不转成功，普通SocketTimeout正确降级。

## caller待集成，暂不关闭完整发现

新增chat整链测试仍暂存等待caller owner的marker映射。当前已另执行2项真实产品PG红测，明确定位为：有真实引用可答但fallback计数0（应1）；无候选返回unanswered（源应system_error/retrieval_failed）。日志 `.migration-artifacts/j5-02-sp-p5-01-chat-red.log`。这些是caller缺少fallback marker归因，不通过改blockedReason或伪造chunk规避。

主Agent已将ChatTaskExecutor唯一写锁交 `model_adapter`，该owner需在trace处把marker映射至retrievalFailure/既有fallback指标路径。待其小片进入后再运行chat测试，验证回答/真实引用、持久化unknown token/cost NULL、fallback一次、无候选retrieval_failed、重放不重复调用/计量。Reader切片绿不冒充整链或P5放行。未执行Java正式容量或故障矩阵。
