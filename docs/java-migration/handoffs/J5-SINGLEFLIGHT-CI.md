# J5-SINGLEFLIGHT-CI 交接

- owner/工作树：model_adapter；`.migration-artifacts/worktrees/j5-usage-closure`，`codex/j5-usage-closure`。
- 当前任务：修CI40656367531 / job122708258562实证的失败future重试竞态；不处理密码性能、不降阈值、不改黄金。
- 基线：同步主线9471757；同步前worktree干净。仅Reader证据add/add冲突，核对后保留主线含原红灯与后续17项绿的完整版本；没有覆盖WIP，未改Reader源码。
- 代码提交：4a6d6b2 + 8d9cc26 + 36c4ce0；最终生产tree=e77ed11ba386eb047dbff10eed0e1a3f3f78ec43；cache blob=06a302be5495563e6bd96e0fbc72e28239023050。
- 状态：实现完成；纯自动验证109/109，无失败/错误/跳过；全量/矩阵/CI重跑待root；用户验收未执行。

## 实现与证据

SingleFlight仅把raw完成与状态清理、managed终态发布组成因果链：先onPopulated移除inflight/填cache，再完成managed，shielded views只跟managed。同步factory异常保持原同步传播，null在登记前拒绝，无dangling项。view取消不伤共享raw/sibling，TTL/LRU不改。root随后明确扩clear identity对齐：当前flight才可删除inflight/写cache，旧loader仍向已有waiter完成原值/错误，不能回填/覆盖clear后的新值或移除新flight。

新SingleFlightCompletionOrderTest：终态callback内立即重入，并用闩锁禁止退栈；旧树失败两项（factory1应2、cache.size0应1），同步完成路径通过。先完成5项加模型包/检索用量纯回归fresh105/105；新增clear4项实跑3红后，对齐Python identity闸，最终fresh109/109。详细命令与全部红绿计数见 `evidence/P5/ci-singleflight-publication.md`。

## 范围、回退与接续

生产只写SingleFlightTtlCache.java；测试只新增两个model回归类，原RetrievalUsageTest与其他断言未改。未改pom/配置/其他业务/协议/黄金/阈值，未做PG/负载/Docker或真实上游调用。日志gitignored，未提交凭据/运行源码/原日志。

clear后旧loader可回填曾作为Java既有行为报告；root核对Python后确认是迁移差异，明确扩域由本片修正，不再保留为本片未修后续项。Python源码47-53行与独立红绿详见证据。源码锁已交回root，worker不再并写；工作区无未提交变更、无遗留进程。root依序cherry-pick4a6、8d9、36c4及文档（b225c64与后续修订）后，安排fullverify/fresh矩阵/CI并核验远程SHA。回退仅撤销本片三个源码/测试提交。
