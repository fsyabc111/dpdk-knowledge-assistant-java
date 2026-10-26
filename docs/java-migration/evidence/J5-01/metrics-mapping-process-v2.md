# 源过程指标实施（新用户决定，尚待CPU窗口后验证）

用户要求补齐源过程观测，旧12 unavailable/持久gauge不再作为正式验收豁免。历史metrics-mapping-full04/seed等证据保留其原来源和结论，只能重现旧实现；当前默认验收由source-process-v2替代，不伪造python_*或静态family凑数。

## 正式实现与装配边界

- ProcessMetricsRegistry实现root公共ProcessMetrics，class无@Component，由root的ObservabilityWiring唯一@Bean；业务hook由root在真实执行路径喂入。默认OperationsMetrics.Endpoint只调用renderProcess，不读DomainAdapter/SQL snapshot。render(snapshot)及历史构造只留诊断。
- 源backend/app/core/metrics.py全部16领域family用真counter/histogram，原默认/自定义bucket、单位、标签维度、注册顺序保持。无标签实例按Python客户端初始化零state和created；带标签family未观察到真实事件前不创建child/created sample。不是把TYPE头当成hook已工作。
- Counter随本次进程事件递增，留存删除不减少，不从数据库当前行数重建。token仅已知且>0才计，未知source行另增unknown，已知有币种成本才计。cache/local/replay零token不伪造子series；成本0真实事件可创建0 child，与source inc(0)一致。
- created使用真实System.currentTimeMillis，HTTP耗时用System.nanoTime；不读取业务ClockPort.nowUtc/nextSessionIso，不消耗P0冻结逻辑tick。测试DoubleSupplier仅用于独立测试实例。
- 工具结果沿源名字assistant_agent_tool_result_bytes，但冻结源实际len(json.dumps ensure_asciiFalse)为Unicode码点，不是UTF8字节。端口不重算；root producer按源码口径计算。未知tool归unknown，标签不接收源码/身份/正文。

## HTTP与SSE

冻结prometheus-fastapi-instrumentator7.0.2：requests counter标签method/status(Nxx)/handler；size Summary仅Content-Length header，缺失0；highr无标签桶0.01..60+Inf，lowr(method/handler)桶0.1/0.5/1/+Inf。使用实际请求filter头与状态观测，不读/缓冲/重读body，不把网络/TLS字节冒充该源同名size。

异步SSE在AsyncListener complete结束后才记duration，包含流的完整存活期；不是初次servlet返回耗时。onError/onTimeout只标记错误，完成去重；已提交响应的status保留，未匹配handler归none，不使用用户实际URL作标签。旧诊断HTTP dispatch计数继续只在诊断API显示。

## JVM四项实际能力

| 源Python项 | JVM实际数据与边界 |
|---|---|
| python_gc_objects_collected_total | JVM公开的是GC次数/耗时；没有对象回收数量等价值，不发伪python对象counter |
| python_gc_objects_uncollectable_total | 没有不可回收对象数量等价API；实际heap/nonheap已用字节只是诊断，不冒充对象数 |
| python_gc_collections_total | jvm_gc_collections_total按collector读取真实MXBean次数；额外jvm_gc_collection_seconds_total读取真实耗时，非Python generation标签 |
| python_info | jvm_info读取真实Java版本/vendor/VM，值1是info元数据，不伪称Python版本 |

不支持的MXBean计数(-1)不补0。语言运行时固有差异由root接受并明示；这不能重新变成12领域缺项的豁免。

## 当前验证与窗口

merge ccf5e97后，第一轮短纯测试exec已exit0（当时ProcessMetricsRegistryTest+旧OperationsMetricsTest），随后root进入Python36分钟CPU独占窗口；已确认无残留Maven/测试/HTTP/PG/浏览器/容器。本窗口内仅轻量编辑源码与测试/文档，后续异步filter、新正式validator、默认Bean装配和真实业务hooks尚未运行，不能记通过。

窗口释放后：运行正式registry/filter纯测试、Java实际registry导出固定过程事件→新validator拒绝反例；然后申请独立PG/真实Application HTTP窗口并由root对已接业务hooks做fresh矩阵/真实事件值核对。不会修改黄金或strict比较器，也不把未验证项变成pass。

源计费的0需要保留精确区别：token<=0不会创建usage token子series；未知token没有0子series；明确币种/成本=0的真实usage行会经inc(0)创建成本child及created，这与source一致，不能错误当作“假零”。非成本带标签事件counter由真实调用至少增加1，不允许伪造未观察的0 child。新validator按此区分，后续须以独立已知事件值补验，不把类型注册本身视作充分证据。
