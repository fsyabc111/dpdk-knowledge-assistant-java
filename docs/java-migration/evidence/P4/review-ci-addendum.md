# P4 CI 可移植性修复：规范轴补充审查

日期：2026-10-25。已核实基线 `22c87e4da031bfe64136898cb9f9156a1078e4d6`、修复快照 `27082b918b83dd0de613737dadd2cfb5ed487dd9`，开始审查时 HEAD 等于修复快照。范围仅 `git diff 22c87e4...27082b9` 的 4 文件及 `ci-portability-fix.md`；不重开已关闭的 P4 两项规范阻断，不代规格轴裁决。

## 生产变更

`backend-java/src/main/java/io/dpdk/assistant/model/OpenAiCompatibleChatClient.java:422-454` 将成功/异常终结信号暂存到局部变量，先在 finally 执行唯一 `recordOnce`，再对仍有效的订阅发 `onComplete` 或 `onError`。因此终态消费者立即 flush 时账目已经可见，不依赖线程随后继续运行。`:427-439` 的 stream_options 特定400兼容重发、调用次数与错误分类未变；取消仍记账但不发送终态，尾帧已知 token/无尾帧 unknown 也未改变。回调本身位于记账之后，不能反向阻断本次用量记录。

符合 `ARCHITECTURE.md:63` 的模型用量/失败语义和 J4-03 的 unknown 保留要求。未新增模型/MCP调用、事务、日志原文、共享可变状态或重试分支；未发现本次引入的规范阻断。

## 回归可信度与夹具边界

- `backend-java/src/test/java/io/dpdk/assistant/model/StreamTerminalLedgerVisibilityTest.java:38-55` 在终态回调内部保存不可变账本快照；再通过同一单线程 executor 的排队哨兵确认处理体退出。它能区分“晚记”与“根本漏记”，没有 sleep/轮询，也没有先等待 finally 后才检查消费者视角。`:56-68` 检查单次调用、单终态及已知 token/unknown；两条用例覆盖成功和不可重试400。
- 已只读核查 `.migration-artifacts/p4-stream-ledger-minimized-red.log`：旧代码 **2项失败，回调快照大小0而期望1**；`p4-stream-ledger-green.log`：新代码相关 **37/37、0失败/错误/跳过、BUILD SUCCESS**。属于主 Agent/实现者实际执行，本审查未复跑。
- `backend-java/src/test/java/io/dpdk/assistant/operations/OperationsBootJarAcceptance.java:151-154,165-166` 仅将测试生成的 Instant 截断至微秒，并显式断言123456789ns→123456微秒。生产 `platform/time/SystemClockAdapter.java` 没有变化，原格式精度护栏仍保留；没有为绿色结果放宽生产校验或移除业务断言。

完整 smell baseline 未发现本增量需新增的 `possible` 建议；不扩大冻结后修复范围。

## 结论

**本次增量无新规范阻断，原 P4 规范轴通过结论保持。** 新源码全量、新 jar 验收与远程 CI 的最终状态由主 Agent 单独绑定本提交确认，本文不以37项局部通过代替这些门禁，也不代用户/live/生产验收。

本 worker 仅只读审查与新增本报告；未运行 PG、构建或测试，未修改业务源码、提交或推送。

## 追加：真实 Linux 生产时钟入口修复

固定增量为 `27082b918b83dd0de613737dadd2cfb5ed487dd9` → `f020efa951b816cc3315f169c483af0998875800`，已用 `git rev-parse` 核实；追加复审开始时 HEAD 等于后者。原记录保留：前一节关于“仅修改夹具”的判断限27082b9，不能据此覆盖后来暴露的生产 `purge-sessions` 入口。

本次生产修复位于 `backend-java/src/main/java/io/dpdk/assistant/platform/time/SystemClockAdapter.java:35-38`：将底层墙钟 Instant 在 `nowUtc()` 端口边界截断至 MICROS。`contracts/platform/ClockPort.java:8-15` 原本已要求墙钟字符串最多六位小数、与 Python datetime 对齐，新增注释明确端口输出精度；该修复兑现已有兼容约束，不是放松接收任意时间值。`SessionService.purgeExpired()` 的 `clock.nowUtc()`→cutoff→`SessionTime.iso()` 链现在消费合法微秒值，真实 Linux 系统墙钟可达的纳秒不再触发业务失败。

`monotonicNanos()`、`nextSessionIso()` 的单调预算/递增语义，以及 `SystemClockAdapter.pythonIso()`、`SessionTime` 的严格格式化实现均未修改。没有将非法纳秒的自动截断扩散到格式化器，也没有为平台差异调整留存天数或绕过身份/数据库事务。

新增 `WallClockMicrosecondContractTest.java:10-17` 使用123456789ns固定输入，分别断言端口输出123456微秒和两个 formatter 直接处理原始非法精度仍拒绝。`WallClockPurgeIntegrationTest.java:17-33` 使用相同真实 SystemClockAdapter、SessionService 与独立 PG，验证31天过期会话：默认dry-run保留一行，apply才实际删除。此回归触及生产入口，没有只把测试输入预先改成合法微秒。

已只读核查 `.migration-artifacts/p4-linux-clock-red.log`：相同3项原为 **1失败/1错误**，错误来自真实清理链的“微秒精度溢出”；`p4-linux-clock-green.log`：修复后 **3/3，0失败/错误/跳过，BUILD SUCCESS**。执行归主 Agent，本 worker 未复跑或占用 PG。

**追加结论：f020efa 未引入新的规范阻断，继续支持规范轴通过。** 这是生产端口边界修复，区别于前次测试夹具归一；完整新源码、新 jar 与远程 Linux CI 的最终结果仍须由主 Agent 绑定本提交确认，不以本机3项局部通过代替。用户/live/生产验收维度不变。此次仅追加本文件，未修改业务源码、构建、测试、提交或推送。
