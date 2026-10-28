# P5 CI：SingleFlight 终态发布顺序修复

2026-10-28。CI实证来源：run `40656367531`、postgres job `122708258562`，主checkout原日志 `.migration-artifacts/p5-ci-40656367531-postgres-failed.log:47` 起：`RetrievalUsageTest.embeddingFailureRetainsUnknownAttemptsAndDoesNotPopulateCache` 第二次调用仍抛首次 `synthetic embedding failure`。原测试断言未改。

- 集成基线：`9471757`（业务生产内容与此前ff59一致）；worker `codex/j5-usage-closure`。
- 生产修复：`4a6d6b2` + `8d9cc26` + `36c4ce0`；最终 `backend-java/src/main/java` tree：`e77ed11ba386eb047dbff10eed0e1a3f3f78ec43`；SingleFlightTtlCache blob：`06a302be5495563e6bd96e0fbc72e28239023050`。
- 状态：窄实现/纯回归完成，fresh **109/109、0失败/错误/跳过**（先105，再按root明确扩域补4项clear回归）。本片未运行PG、HTTP业务服务、负载、Docker、全量verify或差分矩阵，也未代root登记CI/P5放行。

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
- 未改TTL/LRU、其他业务、pom、共享配置、生产口令实现、协议、黄金或阈值。

## root后续明确扩域：clear identity对齐

最初按“不扩clear”要求保留Java现状并报告：clear后旧loader仍可回填。root随后只读核对冻结Python `backend/app/core/async_cache.py:47-53`，确认写_values也位于当前task identity检查内；这段Java既有行为是迁移差异，不能表述为Python兼容。root明确扩本片窄范围，并由root登记接口/差异依据；没有擅改黄金或引入新产品语义。

新增 `SingleFlightClearIdentityTest` 4项，全部显式控制未完成raw future、clear及完成顺序，无sleep：

| 场景 | 修复前结果 | 修复后 |
|---|---|---|
| clear→旧成功 | cache.size=1，应0；旧waiter及sibling仍应完成原值 | 通过 |
| clear→新load未完成→旧成功 | 旧值冒充cache hit，应继续join新flight | 通过 |
| clear→新成功→旧成功 | 返回old result，应保留new result | 通过 |
| clear→新load→旧失败 | 原已通过：旧waiter异常，不得移除/取消新flight | 通过 |

clear独立红实跑4项、3失败、0错误/跳过，日志 `.migration-artifacts/probes/singleflight-clear-identity-red.log`。修复仅将onPopulated的成功写cache与inflight删除一并收进current identity匹配闸；旧loader不匹配就跳过状态更新，但raw→managed仍给旧waiter发布原成功值或失败。clear仍不取消旧loader，无额外fencing token或外部API变化。

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
# 按root明确扩域再补4项clear identity回归后fresh重跑：109/109，0失败/错误/跳过。
# 最终2026-10-28 17:12:00结束，总11.416秒。
```

最终109项绿日志 `.migration-artifacts/probes/singleflight-clear-identity-final-green.log`；前一105项 `singleflight-model-regression-final.log`，104项 `singleflight-model-regression-green.log`；均gitignored，不提交原始日志。109是包含模型客户端HTTP loopback替身与上述retrieval/usage纯测试的实际计数，不等于项目全量。原CI失败用例本轮执行通过，其原始attempts/unknown/cache-hit/0-call断言没有删除、重试兜底或改值。

## 交接与回退

无运行中进程、服务、测试库或容器；没有PG/真实上游/旧环境操作。仅本地意义提交供root集成与推送；主集成分支未由worker操作。root需对最终生产tree重做fullverify、fresh矩阵与CI，不能复用旧ff59通过声明。回退仅revert三个生产/回归提交，恢复原缓存实现，不触碰资产。
