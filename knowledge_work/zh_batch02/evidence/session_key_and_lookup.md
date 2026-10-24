# 会话键与读写路径：一条流怎样被登记和计数：事实证据表

固定源码版本：`20f882691ac03998e0e148e0a66041227cd3f751`。下面行号只对应该快照。核查类型：静态阅读与编写者自审；**不是独立审核，也不是运行测试**。

快照默认不纳入 Git（见 `snapshots/.gitignore`）；缺失时按 README 的导出命令从本地 Git 对象恢复。定位文本命中只证明“该范围内出现该字符串”，不代替对分支语义的审核。

| 事实编号 | 正文位置 | 项目事实 | 依据（版本同上） | 核查说明 | 状态 | 与旧说明/推断的差异 |
| --- | --- | --- | --- | --- | --- | --- |
| SL-001 | 核心对象和术语 | 三类对象所有者不同：parsed_packet 在单线程是回调栈上局部量；session_entry 由 manager malloc 并挂哈希桶链表；engine_ctx 内嵌在引擎条目内。会话条目同时保存规范化 key 与创建者的原始五元组。 | [main.c](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/main.c) L1241–L1246，`packet_handler`；[engine.h](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/plugin/engine.h) L83–L86，`engine_ctx`；[session.h](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/session/session.h) L18–L25，`session_key`；[session.h](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/session/session.h) L105–L120，`src_ip` | 结构定义与调用点共同支持；指针参数不转移所有权。 | 已核对（编写者静态自审；rev 2 拆分后仍待人工审核） | 首批 PL-001/PL-002 只说参数是指针；本篇补出 parsed 的栈生命周期与“键归一、方向保留”的双字段设计。 |
| SL-002 | 会话键与双向归一 | IPv4 归一把两个 uint32 直接比大小、地址相等才比端口；IPv6 用 16 字节 memcmp 字典序；key_equal 先比 ip_version、端口、协议，因此 IPv4 与 IPv6 会话不会互相命中。 | [session.c](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/session/session.c) L58–L79，`src_ip < dst_ip`；[session.c](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/session/session.c) L82–L101，`memcmp`；[session.c](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/session/session.c) L104–L117，`ip_version` | 两个 normalize 函数与 key_equal 逐行对读；未运行任何报文。 | 已核对（编写者静态自审；rev 2 拆分后仍待人工审核） | session/README.md 只说“自动规范化确保双向流量映射到同一会话”，未说明大小比较口径与跨版本隔离。 |
| SL-003 | 会话键与双向归一 | packets_ab 的参照物是创建该会话的那个报文方向：session_create 保存原始 src/dst，session_update 拿当前源地址与会话原始源地址比较来决定加 ab 还是 ba。键本身不记录谁是客户端。 | [session.c](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/session/session.c) L238–L246，`entry->src_ip`；[session.c](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/session/session.c) L256–L272，`packets_ab` | 从 create 的赋值读到 update 的比较，逐句对应。 | 已核对（编写者静态自审；rev 2 拆分后仍待人工审核） | 与“键已规范化所以方向天然正确”的常见推断相反。 |
| SL-004 | 查询、创建与更新的实际调用 | main 只在 protocol 为 6 或 17 时进入 lookup→（为空则）create→update；IPv6 与 IPv4 是并行实现的三份函数，地址比较与哈希混合方式不同。 | [main.c](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/main.c) L1271–L1300，`session_lookup_v6`；[session.c](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/session/session.c) L278–L301，`normalize_key_v6`；[session.c](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/session/session.c) L29–L55，`ip_version` | 调用点与两套实现逐一对照；未把接口形状相同当作实现相同。 | 已核对（编写者静态自审；rev 2 拆分后仍待人工审核） | session/README.md 的使用示例只演示 IPv4，容易被读成唯一路径。 |
| SL-005 | 查询、创建与更新的实际调用 | session_create 不查重、不检查会话数上限，直接 malloc、填键、头插进桶并 session_count++；session_update 只刷新 last_seen 并按原始地址二选一累加包数与字节数。 | [session.c](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/session/session.c) L229–L254，`session_count++`；[session.c](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/session/session.c) L256–L272，`last_seen = time(NULL)` | 函数体无容量与重复判断；结论限于本版本这两个函数。 | 已核对（编写者静态自审；rev 2 拆分后仍待人工审核） | 与“会话层会去重并限容量”的推测相反。 |
| SL-006 | 计数与字段口径 | session_update 的字节数形参名为 pkt_len，main 两条路径传入的实参都是 caplen，即整帧捕获长度（含各层头，截断时为截断后长度），不是负载长度。 | [session.c](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/session/session.c) L256–L258，`pkt_len`；[main.c](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/main.c) L1286–L1299，`caplen`；[main.c](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/main.c) L1899–L1914，`cap_pkt.caplen` | 只看调用实参，不看形参命名。 | 已核对（编写者静态自审；rev 2 拆分后仍待人工审核） | session/README.md 的“双向流量统计”未定义字节口径。 |
| SL-007 | 边界、异常与常见误解 | 接口防御不一致：session_lookup 与 session_create 不校验传入 manager 是否为空，而 session_manager_destroy、两个 flush 函数与 session_get_count 都先判空。session.c 还定义了一个静态地址比较辅助函数 ip_addr_cmp，本文件内没有调用点。 | [session.c](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/session/session.c) L203–L212，`mgr->table_size`；[session.c](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/session/session.c) L17–L26，`ip_addr_cmp` | 判空逐函数核对；未使用的静态函数不作为实现依据。 | 已核对（编写者静态自审；rev 2 拆分后仍待人工审核） | 提醒“符号存在不等于结论成立”，该函数容易被误当成键规范化的公共底层实现。 |
| SL-008 | 阅读顺序与自查问题 | “同一流出现多个会话”有三条互不相同的证据链：桶内已过期但未被清扫的老条目、短包让端口保持 0 从而多流共用同一个端口 0 的键、以及同一五元组的包被分到不同 worker 队列。三者定位方法不同，RSS 实际分流本批未验证。 | [session.c](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/session/session.c) L215–L226，`last_seen`；[main.c](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/main.c) L1078–L1082，`protocol == 6`；[main.c](../snapshots/dpdk/20f882691ac03998e0e148e0a66041227cd3f751/main.c) L3132–L3141，`queue_id` | 三条链路各自的代码位置已定位；第三条只证明“每 worker 绑一个队列”，不证明哈希对称性。 | 已核对（编写者静态自审；rev 2 拆分后仍待人工审核） | 首批 PF-012 明确保留 RSS 分流未验证；本篇把它列为待查方向而不是结论。 |

## 待确认清单

下列内容本批**未**得出结论，正文也不得依赖其成立：
- 短包导致端口保持 0 后究竟会合并多少条流：未构造报文、未统计。
- RSS 是否把同一五元组的双向报文稳定分到同一队列：未读硬件配置、未测量，正文仅登记为排查方向。
- `session/README.md` 建议的哈希表大小与真实负载下的桶长分布：未测量。
- 会话层默认参数在真实流量下的内存占用：未计算、未运行。

## 与首批事实的关系

首批 25 项 PL/PF 事实已经独立静态审核（见 `../zh_pilot/reviews/independent_review_20260918.md`）。本批在其之上补会话与 DNS 两段，不重复其结论；两篇首批样稿仍为**待人工审核**。
