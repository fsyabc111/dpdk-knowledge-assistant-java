# J5-SINGLEFLIGHT-CI 交接

- owner/工作树：model_adapter；`.migration-artifacts/worktrees/j5-usage-closure`，`codex/j5-usage-closure`。
- 当前任务：修CI40656367531 / job122708258562实证的失败future重试竞态；不处理密码性能、不降阈值、不改黄金。
- 基线：同步主线9471757；同步前worktree干净。仅Reader证据add/add冲突，核对后保留主线含原红灯与后续17项绿的完整版本；没有覆盖WIP，未改Reader源码。
- 代码提交：4a6d6b2 + 8d9cc26；生产tree=2fd547326e7aa34742986db90576df5903d74414；cache blob=c0aed67b1526c639825a5d94740d5088f59d75cc。
- 状态：实现完成；纯自动验证105/105，无失败/错误/跳过；全量/矩阵/CI重跑待root；用户验收未执行。

## 实现与证据

SingleFlight仅把raw完成与状态清理、managed终态发布组成因果链：先onPopulated移除inflight/填cache，再完成managed，shielded views只跟managed。同步factory异常保持原同步传播，null在登记前拒绝，无dangling项。view取消不伤共享raw/sibling，TTL/LRU及clear原语义不改。

新SingleFlightCompletionOrderTest：终态callback内立即重入，并用闩锁禁止退栈；旧树失败两项（factory1应2、cache.size0应1），同步完成路径通过。最终5项加模型包/检索用量纯回归fresh105/105。详细命令与全部红绿计数见 `evidence/P5/ci-singleflight-publication.md`。

## 范围、回退与接续

生产只写SingleFlightTtlCache.java；测试只新增其model回归，原RetrievalUsageTest与其他断言未改。未改pom/配置/其他业务/协议/黄金/阈值，未做PG/负载/Docker或真实上游调用。日志gitignored，未提交凭据/运行源码/原日志。

旧clear后loader仍可能回填cache已报告root并记为后续边界，本片不扩成epoch/fencing修复。源码锁已交回root，worker不再并写；工作区无未提交变更、无遗留进程。root依序cherry-pick4a6、8d9及文档提交后，安排fullverify/fresh矩阵/CI并核验远程SHA。回退仅撤销本片两个源码/测试提交。
