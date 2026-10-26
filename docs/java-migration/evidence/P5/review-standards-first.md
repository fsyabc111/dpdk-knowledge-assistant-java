# P5 第一轮独立规范轴

2026-10-26。固定 BASE=`f020efa951b816cc3315f169c483af0998875800`、TARGET=`f1c5bb5584e5ee5c71ed9f6fbd3f383e92b3c22f`，均已验证；仅用两者三点 diff 与提交列表，源码/行号均取 TARGET（199文件，24916新增）。未编译、测试、连接PG/HTTP、操作容器或访问网络。不是当前移动HEAD的放行报告。

## 代码阻断

1. **[P2] 取消不等于候选收尾完成。** `backend-java/src/main/java/io/dpdk/assistant/chat/ChatTaskExecutor.java:192-224` 超时/中断仅 `future.cancel(true)`、`shutdownNow()`，不等待候选退出；`:230-235` 随后移除唯一收尾对象并快照flush/publish。候选的模型finally及 `retrieval/RetrievalUsage.java:20-29,40-46` 的shield共享loader仍可能稍后写recorder；`chat/RequestMetrics.java:25-32` 已发布后不会再发这些值。结果是终态可先出现，实际调用用量/阶段观测永久漏记。违反 `migration/contracts/interface-changes.md:185,193` 的异常/取消已发调用保留与终态前收尾约束。应建立完成握手或明确迟到调用归账机制，补闩锁控制的超时/取消回归，禁止sleep碰绿。

2. **[P2] 图异常被记成生成成功。** `backend-java/src/main/java/io/dpdk/assistant/chat/ChatTaskExecutor.java:385-416` 中，runProduct抛异常时局部 `result` 仍null；若无检索错误，finally必发 `llm(...,"success",...)`。严格产品入口的模型异常、预算异常、HarnessResultUnknown均可达此路径；外层却返回system_error或保留重驱动。违反 `interface-changes.md:189,193` 的真实过程/失败语义及 `ARCHITECTURE.md:63`。应依据实际执行结局分类，明确安全差异的指标映射，并核查失败/取消不增加success。

3. **[P2] 恢复runner会过报数据完整性。** `acceptance/deployment/rehearse.py:86-97` 仅比较 `docker_pg.py:113-120` 的11表计数、active ID等，却输出“all retained business rows equal”。`probes.py:53-63` 只校验一个聊天正文及引用，恢复后成绩score或其他未抽查行变动而行数不变仍会passed。违反 `LONGRUN_P3_P5_DRAFT.md:79` 的恢复业务断言及 `COORDINATION.md:53-54` 的证据完成语义。应比较保留业务行的稳定有序摘要/关键字段，或收窄结论；加入同计数、错成绩反例。

## 气味与待证据

完整Fowler baseline未另列有独立修复价值的 `possible` 建议；以上不是仅风格判断。afterCommit观测、显式PG身份与owned恢复资产的设计已读取，未发现本轮新增凭据/源码日志泄露的实证。

最新过程源码未编译、fresh指标矩阵、故障、正式双端容量及实际恢复未执行，均按已知pending记录，不计代码缺陷或通过；历史合成/浏览器结果不代用户/live验收。

**本轮3项P2代码/验收器阻断；尚不支持固定TARGET的P5工程放行。** 修复与CPU窗口后验证仍由主Agent负责。本worker仅新增此报告，不提交、不修改业务；与规格轴独立。
