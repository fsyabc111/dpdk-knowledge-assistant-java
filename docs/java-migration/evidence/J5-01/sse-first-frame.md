# SSE首帧实时性修复

2026-10-26。真实浏览器停止按钮未出现取消终态；直连Java新任务SSE在固定上游10秒延迟下3秒/4秒都未收到响应头，排除前端代理。失败证据保留主checkout gitignored j5-ui/browser-02。

定位：Thread.dump_to_file证明SSE泵已过start写入并等live队列，Servlet异步体等收口；不是任务锁阻塞。最小无PG真实Tomcat测试SseFirstFrameTest在模型等价闩锁保持阻塞时2秒等待响应头超时。临时带唯一DEBUG前缀的类型探针确认输出为StreamUtils.NonFlushingOutputStream，start/done flush均被吞；探针现已全部移除。

本机锁定spring-web7.0.10字节码确认ServletServerHttpResponse构造读取SpringProperties的spring.http.response.flush.enabled，缺省false。新增classpath根spring.properties显式true（不是无效的Boot YAML键），不改客户端/SSE帧格式/任务取消语义。正式jar和所有启动入口都会带此资源。

红：p5-sse-first-frame-minimal-r2.log与instrumented.log均同一最小测试Timeout。绿：p5-sse-first-frame-green.log，SseFirstFrameTest+ChatSseContractTest+CandidateClockTest共8/8、零跳过；测试明确先收到start、producer闩锁仍为1，再释放/关闭流，避免完整终态字符串伪装实时。旧首次探针漏关HTTP body造成诊断自身等待，已修测试资源关闭并保留日志，不把它当业务故障。

浏览器取消最终复验和完整应用回归待新服务启动，不以本8项代替全部P5。
