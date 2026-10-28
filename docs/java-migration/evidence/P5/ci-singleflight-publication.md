# P5 CI：SingleFlight 终态发布顺序修复

2026-10-28。CI实证来源：run `40656367531`、postgres job `122708258562`，主checkout原日志 `.migration-artifacts/p5-ci-40656367531-postgres-failed.log:47` 起：`RetrievalUsageTest.embeddingFailureRetainsUnknownAttemptsAndDoesNotPopulateCache` 第二次调用仍抛首次 `synthetic embedding failure`。原测试断言未改。

- 集成基线：`9471757`（业务生产内容与此前ff59一致）；worker `codex/j5-usage-closure`。
- 生产修复：`4a6d6b2` + `8d9cc26`；最终 `backend-java/src/main/java` tree：`2fd547326e7aa34742986db90576df5903d74414`；SingleFlightTtlCache blob：`c0aed67b1526c639825a5d94740d5088f59d75cc`。
- 状态：窄实现/纯回归完成，fresh **105/105、0失败/错误/跳过**。本片未运行PG、HTTP业务服务、负载、Docker、全量verify或差分矩阵，也未代root登记CI/P5放行。

## 原因与确定性红

旧实现把原始source future直接登记为inflight，cache清理回调和shieldedView回调分别注册在同一个raw future。注册先后不建立“清理完成再发布”的因果关系；消费者可先观察异常，再重入getOrCreate，仍拿到未移除的旧失败future。成功路径也可能先观察完成，而cache尚未填充。

按diagnosing-bugs反馈循环，先验证三项假设：①发布先于移除；②同步factory完成时序；③旧inflight identity覆盖。新测试在消费者终态callback内部立即重试/读size，并用闩锁阻止callback退栈，使producer不能靠随后cleanup掩盖状态；无sleep、无多轮碰概率。

修复前 `SingleFlightCompletionOrderTest` 实跑3项：

| 场景 | 红结果 |
|---|---|
| 失败终态callback内立即重试 | factory数仍1，预期2；拿到旧失败inflight |
| 成功终态callback内读cache.size | 为0，预期1；状态未收口 |
| factory同步完成（失败/成功） | 通过；未复现假设② |

原始红日志：worker `.migration-artifacts/probes/singleflight-publication-red.log`。这与CI的失败后立即重试症状一致，不是延长等待或降低断言。

## 最小生产修复与边界

仅改 `model/SingleFlightTtlCache.java`：factory返回的raw future先驱动cache/inflight收口，再完成内部managed future；消费者仅经shieldedView观察managed。正常异步路径在cache monitor释放后发布，消费者重入时无需再等cleanup。population本身若失败则managed也必须失败，不能永远悬挂。

- 同步factory抛异常仍在登记managed之前原样同步传播，不转成异步业务成功。
- null future在登记前立即NPE，防新managed悬挂；随后fresh factory正常。
- 失败不缓存，立即重试fresh；成功在消费者终态前已可cache hit。
- 取消一个view不取消raw或sibling；不增加factory次数，不重复usage。
- 没改clear、TTL/LRU、其他业务、pom、共享配置、生产口令实现、协议、黄金或阈值。

已发现但本片明确不扩的旧行为：`clear()`只清values/inflight，不取消既有raw loader；旧loader完成仍可回填cache。onPopulated仅用identity条件保护inflight删除，成功回填并不受epoch约束。本片保持这段既有语义；不是声称已解决clear/fencing。

## 最终回归与计数

新增 `model/SingleFlightCompletionOrderTest` 5项：上述失败/成功发布顺序、同步完成、view取消隔离、null/同步throw无悬挂。原SingleFlightTtlCacheTest、RetrievalUsageTest与用量断言保持原样。

```sh
# 工作目录 .migration-artifacts/worktrees/j5-usage-closure；JDK21。
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest=SingleFlightCompletionOrderTest
# 修复前3项：2失败、0错误、0跳过，保留原件。

JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest=SingleFlightCompletionOrderTest,SingleFlightTtlCacheTest,RetrievalUsageTest,CancelledCacheWaiterUsageTest,UsageReceiptCheckpointTest
# 首轮修复（新测试当时3项）22/22通过。

JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B test \
  -Dtest='io.dpdk.assistant.model.*Test,RetrievalUsageTest,CancelledCacheWaiterUsageTest,UsageReceiptCheckpointTest'
# 增加无sleep的view取消回归后104/104。
# 再增加null/同步throw保护与第5项回归后fresh重跑：105/105，0失败/错误/跳过。
# 最终2026-10-28 17:07:56结束，总11.944秒。
```

最终绿日志 `.migration-artifacts/probes/singleflight-model-regression-final.log`；前一104项日志 `singleflight-model-regression-green.log`；均gitignored，不提交原始日志。105是包含模型客户端HTTP loopback替身与上述retrieval/usage纯测试的实际计数，不等于项目全量。原CI失败用例本轮执行通过，其原始attempts/unknown/cache-hit/0-call断言没有删除、重试兜底或改值。

## 交接与回退

无运行中进程、服务、测试库或容器；没有PG/真实上游/旧环境操作。仅本地意义提交供root集成与推送；主集成分支未由worker操作。root需对最终生产tree重做fullverify、fresh矩阵与CI，不能复用旧ff59通过声明。回退仅revert两个生产/回归提交，恢复原缓存实现，不触碰资产。
