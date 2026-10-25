# P4远程CI补修：终态账本可见性与微秒夹具

远程失败：GitHub Actions run40195398480（22c87e4）。unit的streamDoesNotRetryOther400Causes在终态后立即读账本时为空；postgres的真jar夹具把Linux纳秒Instant直接交给正确的Python微秒格式护栏，触发拒绝。原始日志只留.migration-artifacts/p4-ci-reader-failure.log，不提交认证/请求原文。

## 反馈环与因果

- 主Agent接管因模型容量中断的worker，保留其工作树，将回调内部不可变账本快照回归接收为StreamTerminalLedgerVisibilityTest。
- 排名假设：①终态先于finally记账；②400分类分支根本漏记；③账本实例/去重状态错误。用同一单线程executor排队哨兵确认runStream已退出：处理后账目为1，而终态内部冻结快照为0，排除②③，确认①。
- 修复前命令：`JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./backend-java/mvnw -f backend-java/pom.xml -B test -Dtest=StreamTerminalLedgerVisibilityTest`，**2项均失败：Expected size 1 but was 0**；添加处理退出哨兵后再次稳定2项同样失败。日志p4-stream-ledger-red.log与p4-stream-ledger-minimized-red.log。
- 无生产调试日志，无sleep/轮询碰绿；边界是成功/不可重试400终态回调内部真实消费者flush时点，HTTP仅loopback固定替身。

## 修复与验证

- OpenAiCompatibleChatClient.runStream保留成功/错误分类和取消处理，先recordOnce，再对仍订阅的消费者发onComplete/onError。stream_options兼容400仍仅原单次重发；其他400不重试，unknown不改0，尾帧/提前取消原语义不变。
- 新2项明确断言终态内唯一账、已知token或unknown、单调用和单终态。原stream8项（含400兼容与提前取消）、模型富消息16项、账本11项共 **37/37通过，0失败/错误/跳过**，日志.migration-artifacts/p4-stream-ledger-green.log。
- OperationsBootJarAcceptance.iso仅在测试夹具先截断MICROS；生产SystemClockAdapter的纳秒拒绝护栏未修改。补显式123456789ns→123456微秒断言，覆盖Mac原生时钟未暴露的Linux粒度。
- 下一步新源码完整verify、新jar native CLI和远程CI复验；以上局部通过不代替这些门禁。

## 后续真实Linux清理入口

- run40196930220的unit/frontend/deployment-materials已过，postgres走到真jar `purge-sessions` 才exit1。此前只修测试iso虽让夹具可运行，却进一步暴露生产SystemClockAdapter.nowUtc直接返回Linux纳秒值，SessionService.purgeExpired随后按Python微秒格式化而拒绝。
- 新确定性反馈环使用Clock.fixed(123456789ns)与真实SessionService+PG：修复前纯端口断言失败、清理入口报“微秒精度溢出”，格式化器拒绝测试通过（3项为1失败1错误）。日志p4-linux-clock-red.log。
- 仅在生产墙钟端口边界截断MICROS，匹配已冻结Python datetime；monotonicNanos预算与两个格式化器严格护栏未放宽。修复后同一3项全过（含dry-run保留/apply清理、原纳秒直接格式化仍拒绝），日志p4-linux-clock-green.log。
- 新完整verify/native/远程CI仍需重新绑定后才可关闭此跨平台补修；不把本机微秒时钟下通过当Linux证明。
