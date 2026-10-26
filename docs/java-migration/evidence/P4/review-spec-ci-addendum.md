# P4 CI 补修规格窄复审

固定修复快照 `27082b918b83dd0de613737dadd2cfb5ed487dd9`；已核对 `22c87e4...27082b9` 四文件差分。本次业务只读，未运行 PG、构建或测试，未提交推送。

**结论：本补修未发现新的规格阻断；原 SP-P4-01 JSON 阻断关闭结论仍成立。** WorkflowBody 未在本差分中修改。最终工程门禁仍须主 Agent 当前全量、新jar native CLI及远程CI复验结果，不能由局部绿色替代。

`model/OpenAiCompatibleChatClient.java:423-453` 只调整终态可见顺序：暂存完成/错误，finally先用原 `recordOnce` 保存唯一账目，再向尚未取消的订阅者发原终态。消费方在回调内立即取快照/flush时，已发生调用的账目因此可见。

已核查不变边界：stream_options/include_usage对应400仍最多一次原兼容重发，其他400不重试；取消仍停止读取且不发终态；缺失usage尾帧仍记unknown而非0；已消费尾帧保留原token；`pumpStream`、尾帧解析、取消信号和CAS唯一记账均未改动，未引入模型或MCP重投。

新增 `StreamTerminalLedgerVisibilityTest` 在终态回调内冻结不可变账本快照，并用同executor排队哨兵区分“晚记账”与“漏记账”；成功和不可重试400均断言一次外部调用、一次终态和一条账，分别保留已知token/unknown。`ci-portability-fix.md` 记录旧实现2/2红及相关37/37绿；本复审仅核查源码与这些执行证据，未重跑。

`OperationsBootJarAcceptance.java:151-154` 仅将测试构造的Instant截断到MICROS，新增纳秒输入的精确微秒输出断言；生产 `SystemClockAdapter.pythonIso` 未改，微秒溢出拒绝护栏仍在。这是把Linux测试时钟归一到冻结Python时间域，没有放宽生产精度或业务断言。

## Linux 真实清理入口后续复核

追加固定快照 `f020efa951b816cc3315f169c483af0998875800`，比较 `27082b9...f020efa`。**未发现新规格阻断；墙钟修复符合冻结Python微秒契约，原JSON关闭仍成立。**

`platform/time/SystemClockAdapter.java:36-39` 仅在 `nowUtc()` 墙钟端口截断MICROS，`ClockPort`同时明确此精度；这是归一Linux/JDK可能提供的纳秒输入，不修改UTC日期或留存/过期时长。SessionService清理、任务TTL/孤儿宽限等墙钟阈值现在使用Python可表示的微秒时间域。`monotonicNanos()`、任务耗时/缓存/限流所用单调时钟、请求Future/执行墙及预算参数均未修改；`nextSessionIso`原有微秒递增语义及两个格式化器护栏也未放宽。

已检查固定纳秒Clock的两项端口/严格格式化测试和真实SessionService+PG清理测试：123456789ns在端口归一为123456微秒，原始纳秒值直接格式化仍被拒绝；dry-run保留旧会话、apply删除。`ci-portability-fix.md`明确保留旧3项中的1失败/1错误，并记录修复后同3项绿，未以测试替身代替真实清理逻辑。本复核未跑PG或测试；新全量/native CLI及远程CI仍由主 Agent验证，不提前宣称Linux最终交付通过。
