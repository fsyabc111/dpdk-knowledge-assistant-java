# 首批两篇样稿独立审核报告

审核类型：**独立 Agent 静态审核**（与首批编写者不同的任务上下文，重新逐条回读固定快照源码）。
本审核**不是**人工审核、**不是**运行验收、**不是**安全审计；未编译、未抓包、未调用模型、未执行项目测试。

- 审核日期：2026-09-18
- 被审对象：[drafts/plugin_lifecycle.md](../drafts/plugin_lifecycle.md)、[drafts/packet_flow.md](../drafts/packet_flow.md) 及两份事实表
- 源码版本：`20f882691ac03998e0e148e0a66041227cd3f751`（分支 master）
- 快照完整性：审核前重新把 `manifest.source_files` 的 22 个快照与 `git show <commit>:<path>` 逐字节比对，**无不一致、行数一致**
- 审核方式：以主仓库工作树文件为读取入口（其内容与固定提交逐字节相同，见下“工作区状态”），按事实编号定位到函数体，读调用者、失败分支与清理路径

## 结论先行

| 判定项 | 结果 |
| --- | --- |
| 改变教学主线的事实错误 | **未发现** |
| 行范围/符号定位错误 | 1 项定位质量缺陷（PF-006），不影响结论成立 |
| 图与文字不一致 | 1 项（packet_flow 流程图“否”分支），文字正确、图不精确 |
| 是否阻断第二批 | **不阻断**（详见末节） |
| 首批两篇状态 | 仍为**待人工审核**，本报告不代替人工确认 |

工作区状态：`git -C /Users/fsy/code/dpdk status --porcelain` 仅输出 `?? ai_chat_assistant/`（助手仓库本身，不是主项目源码差异），主仓库跟踪文件无改动，HEAD 等于固定提交。

---

## 一、插件生命周期（PL-001 … PL-011）

| 编号 | 原陈述（摘要） | 源码依据 | 判断 | 影响 | 建议修改 |
| --- | --- | --- | --- | --- | --- |
| PL-001 | ops 定义回调，ctx 存私有指针与统计，registry 存数组/链/句柄，注册分配 entry 内 ctx | `plugin/engine.h:83-86`（`engine_ctx{private_data,stats}`）、`:90-148`（ops 七个函数指针）、`:152-156`（entry 内嵌 `engine_ctx_t ctx`）、`:163-169`（registry 含 `engines[]/chain_head/plugin_list`）；`engine_registry.c:111,117-119`、`328,335-337` | **成立** | 主线正确 | 可补一句：`ctx` 是 entry 的**内嵌成员**而非指针，随 entry 一起分配/释放；两条注册路径（`register` 与 `load_plugin`）都这样做 |
| PL-002 | 插件收 session 与 pkt；session 有独立管理器；parsed_packet 含 payload 指针 | `engine.h:65-66`（`const uint8_t *payload; uint32_t payload_len`）、`:112-125`（can_handle/process 签名，session 可为 NULL）、`session/session.h:151-156` | **成立** | 无 | 无 |
| PL-003 | 加载依次 dlopen/dlsym/get_ops，名称、重复、容量检查后初始化并登记 | `engine_registry.c:281,289-290,300-305,311-318,320-325,340,349-350,365-369` | **成立**（顺序与代码一致） | 无 | 可注明 `ops->name` 空指针检查发生在加写锁**之前**（`:301`），重复名与容量检查在锁**之内**（`:311,320`），这是学生容易混的两层 |
| PL-004 | 启动固定列表按存在性加载；`--no-plugins` 跳过该函数；entry 无通用 enabled 字段 | `main.c:138,2379-2382,2425-2434`；`engine.h:152-156`（entry 只有 ops/ctx/next）、`:163-169` | **成立** | 无 | 可加强：`g_no_plugins` 全仓**只**被 `load_plugins_to_registry` 读取（grep 结果仅 `main.c:138/2379/2892`），运行时 HTTP 插件控制入口不检查它——即 `--no-plugins` 并不关闭热加载。当前写法没有错，但把这条边界写明更能防止学生误用 |
| PL-005 | init 失败不调用 cleanup；init 成功后句柄记录分配失败的回滚会调用 cleanup | init 失败：`engine_registry.c:122-127`（register 路径 free entry）、`:340-346`（load 路径 free entry + dlclose），两处均无 cleanup；回滚：`:353-363`（remove_from_chain → engine_count-- → cleanup → free → dlclose） | **成立**，且比原表述更普遍：**两条** init 失败分支都不调 cleanup | 主线正确 | 建议把“init 失败不调用 cleanup”明确覆盖 `engine_registry_register` 与 `engine_registry_load_plugin` 两处，避免学生以为只有一条加载路径 |
| PL-006 | priority 升序、同值后插；遍历先加 `packets_processed`，再要求 can_handle 非空且为 true，直接调 process | 插链 `engine_registry.c:37-50`：头插条件为严格小于；同值时 `curr->next->priority <= entry->priority` 继续前进 → 落在同值末尾；计数 `:201`；门控 `:204-205`；`:208` 直接 `ops->process(...)` 无空指针检查 | **成立** | 主线正确 | 可补：`packets_processed` 是**每 entry 每 registry** 的计数，流水线模式下 N 个 worker 各有一份，不存在单一全局值（与 PL-008 呼应） |
| PL-007 | HANDLED 增匹配并返回 OK，ERROR 增错误并返回 ERROR，其他值继续；SKIP 无独立分支 | `engine_registry.c:210-219`、`engine.h:26-32` | **成立** | 主线正确 | 可补一句：链自然走完（无人 HANDLED/ERROR）时 `engine_process` 返回 `ENGINE_OK`（`:226`），因此“返回 OK”不等于“有插件处理了” |
| PL-008 | 单线程全局 registry；worker 各建 registry/session；运行时逐实例加载，失败回滚此前实例 | `main.c:1607-1626`（每 worker `session_manager_create` + `engine_registry_create` + `load_plugins_to_registry`）、`:3104-3144`（DPDK 分支只起流水线，`init_session/init_engine` 在 else 分支 `:3149/3154`）、`:195-214`、`:453-484` | **成立** | 主线正确 | 建议补一条被漏掉的共享状态实例：`plugin/plugins/domain_intel.c:30-36` 有进程级 `g_domain_intel_refcount`/`g_threat_rules` 与 `g_domain_intel_lock`，`:318-356` 用引用计数管理。这正好把原文“独立 ctx 不等于库的全局变量也独立”从推测升级为**具体例证** |
| PL-009 | 会话销毁/过期释放会话及列明扩展；payload 为原数据偏移，回调 release 或 worker free 后不可依赖原地址 | `session/session.c:178-197`（destroy：free_candidates + ext_data + tunnel_signal + geo_summary + tls_stream_buf + entry）、`:457-495`（flush 同一组）、`:145-155`（free_candidates 跳过 `c->data == ext_data` 项）；`main.c:1090,1099`（payload 是 data 偏移）；`capture/capture.c:377-390`；`main.c:1954` | **成立** | 主线正确 | 可补：`engine.h:112-114` 声明 can_handle 的 session 可为 NULL，而 `main.c:1371/1919` 两条路径都保证非空才调用；即“接口允许 NULL”与“本项目实际不会传 NULL”是两件事（后者与 PL-011 一致） |
| PL-010 | 卸载 cleanup/dlclose；destroy cleanup/free entry 但未遍历 plugin_list；处理读锁、加载/卸载写锁 | `engine_registry.c:379-435`（unload：remove_from_chain→cleanup→数组压缩→free entry→dlclose→摘 plugin_list 节点→free）；`:252-269`（destroy 无 plugin_list 遍历、无 dlclose）；锁：`:196` 读锁、`:308/386` 写锁 | **成立** | 主线正确 | 建议补第三个释放边界：`engine_registry_unregister`（`:142-171`）调用 cleanup 并 free entry，但**既不 dlclose 也不摘 plugin_list 节点**。现有文字只对比了 unload 与 destroy 两态，漏掉这一态后学生可能以为“凡是移除引擎都会关库” |
| PL-011 | main 两条路径都要求会话与注册中心存在才调 engine_process | `main.c:1371-1381`、`:1919-1921` | **成立** | 无 | 无 |

## 二、报文处理主流程（PF-001 … PF-014）

| 编号 | 原陈述（摘要） | 源码依据 | 判断 | 影响 | 建议修改 |
| --- | --- | --- | --- | --- | --- |
| PF-001 | traffic_monitor 链接 main.o 与 capture/libcapture.a；子 Makefile 条件加入子目录后端 | `Makefile:56`（`CAPTURE_LIB = $(CAPTURE_DIR)/libcapture.a`）、`:62`（LIBS）、`:65-66`（TARGET/OBJS 不含 capture_dpdk）、`:73-76`（链接规则）、`:108-110`；`capture/Makefile:69-77,80-81` | **成立** | 主线正确 | 无。可保留“不是全仓所有构建入口的排除证明”这一限定，措辞准确 |
| PF-002 | 后端选择受编译宏与可用性判断控制，AUTO 先 DPDK 后 PCAP；显式不可用返回 NULL | `capture/capture.c:80-121`：显式 DPDK/PCAP 分支 `is_available()` 失败即 `return NULL`；AUTO 依 `#ifdef` 先后试 DPDK 再 PCAP，全不可用返回 NULL | **成立** | 主线正确 | 无 |
| PF-003 | 单线程 main 调 capture_loop；loop 用 capture_recv，收到包 handler，再 release | `main.c:3171-3172`；`capture/capture.c:319-363`（`:339` recv、`:343` handler、`:346` release、`:351` 非 0 停止） | **成立** | 主线正确 | 无 |
| PF-004 | 先计数后解析；解析失败返回 0 继续收包 | `main.c:1249-1250`（计数）、`:1258-1262`（解析失败 return 0）；`capture/capture.c:341-353`（返回 0 不 break） | **成立** | 主线正确 | 无 |
| PF-005 | parse_packet 按 caplen 读取，payload 是原 data 偏移；返回 0 不代表完整合法性认证 | `main.c:1045-1046`（data/caplen）、`:1075-1101`（IPv4 payload）、`:1126-1148`（IPv6）、`:1151`（timestamp） | **成立** | 主线正确 | 建议补一条会直接影响“解析成功”含义的事实：`parsed->protocol` 在 `:1070` 就已赋值，而端口/payload 的赋值受 `len >= l4_off + 14`（TCP）/`+8`（UDP）门控；条件不满足时函数仍返回 0，但 `src_port/dst_port` 保持调用方的 0 初值、`payload` 为 NULL。即“解析成功但端口为 0”是可达状态，且会进入下一步的会话建表 |
| PF-006 | 仅 TCP/UDP 走 lookup/create/update，按 IPv4/v6 分支；IPv4 lookup 跳过过期项，update 更新方向计数 | `main.c:1272-1300`；`session/session.c:209`（lookup 内调用 normalize）、`:215-225`（过期项 `entry=entry->next; continue;` 跳过，不删除）、`:256-272`（update 写 last_seen 与 ab/ba） | **成立** | 主线正确 | **定位质量缺陷**：证据把该范围的定位符号写成 `normalize_key_v4`，但该函数的**定义在 `session.c:58-79`，不在所引 L203–L271 内**；范围内命中的只是 `:209` 的调用点。建议把定位符号改为 `session_lookup`，或增加一条指向 `:58-79` 的引用。结论本身不受影响（跳过过期与方向计数确实都在 L203–L271） |
| PF-007 | 单线程 session_to_record/submit 在 engine_process 之前；record 在此填 TCP/UDP，submit 更新虚拟集合 | `main.c:1302-1307`、`:1371-1381`、`:1224-1232`（detected_protocol 仅按 `key.protocol` 写 TCP/UDP/PROTO_n）；`stats/storage_engine.c:1286-1313`（结尾 `virtual_collection_update_session`，无外部写） | **成立** | 主线正确 | 无。“不足以证明所有插件识别结果已进入这份记录”措辞得当 |
| PF-008 | main 未用 engine_process 返回值直接停抓；无 session 时插件入口不执行 | `main.c:1381`（返回值丢弃）、`:1541-1546`（只有 `!g_running` 才 return 1）、`capture/capture.c:351-353` | **成立** | 主线正确 | 无 |
| PF-009 | 回调尾部按时间条件做系统/协议统计、每小时 flush、30 秒会话清扫，提前返回会跳过 | `main.c:1457-1509`（`stats_interval`）、`:1511-1529`（`>= 3600`）、`:1531-1539`（`>= 30` → `session_flush_expired`）、`:1258-1262`（解析失败提前 return） | **成立**，但**图示与文字有张力**（见下“图审”） | 图会被学生当作分支事实 | 建议：①在文字里补一句“`now` 在回调开头 `:1246` 采集，三段时间条件都相对同一时刻”；②修正流程图（见第四节） |
| PF-010 | 过期清扫先回调后 free；manager_destroy 释放条目但不调用同样的过期回调 | `session/session.c:469-483`（回调在 free 前）、`:178-197`（destroy 无 `g_session_expire_callback`）、`:158`（回调全局变量） | **成立** | 主线正确 | 建议把该差异的**后果**写实：`main.c:2527` 注册的回调是 `handle_expired_session_for_profiles`（画像/裁决补记）。关闭路径 `:2604-2615` 直接对各 worker 会话表 `session_manager_destroy`，`:2630` 才注销回调；单线程在 `:3183-3186` 先做一次 `session_flush_expired` 再销毁。因此**流水线模式下退出时仍在表内的会话不会经过该回调，单线程模式下只有“此刻已过期”的会话会经过**。这是学生排查“画像/verdict 少记录”的关键点，目前两篇都未覆盖 |
| PF-011 | 单线程 release 调后端并清 data/backend_data；DPDK free mbuf；PCAP release 不手动 free；parsed.payload 未随之清空 | `capture/capture.c:377-390`；`capture/capture_dpdk.c:971-980`（`backend_data` 非空才 `rte_pktmbuf_free` 并置 NULL）；`capture/capture_pcap.c:328-335`（两参数 `(void)` 丢弃，仅注释说明由 libpcap 管理）；`main.c:1084-1100` | **成立** | 主线正确 | 无。“未依赖未核实的 libpcap 内部期限细节”的分寸得当 |
| PF-012 | DPDK 分支启动 workers 与 storage；worker 直接收各自 NIC queue，用独立会话/引擎；头注释与函数体冲突 | `main.c:1827-1833`（注释“从私有 worker ring 取包”）、`:1838`（queue_id）、`:1851-1852`（`capture_dpdk_recv_raw`）、`:1607-1626`、`:3127-3141`（`rte_eal_remote_launch`，queue_id = w） | **成立** | 主线正确 | 可补：`:1849-1862` 的 `now` 来自 `clock_gettime(CLOCK_MONOTONIC)`，`:1875` 把它写进 `cap_pkt.timestamp_sec`（`:1871` memset 使 usec 为 0）；而 `capture/capture_dpdk.c:659` 的单线程路径用 `time(NULL)`、`capture/capture_pcap.c:267` 用 pcap 头时间戳，且 `session.c` 内部始终用 `time(NULL)`。即**同一字段 `parsed->timestamp` 在两条路径下不在同一时钟域**。现有事实表未记录，建议单列一条 |
| PF-013 | worker 先调插件再构造 storage_task；入 ring 失败回收，最后释放 mbuf；storage lcore 消费会话/IP 记录 | `main.c:1918-1921`、`:1924-1952`（mempool 取任务、`:1948-1950` 入队失败 `rte_mempool_put`）、`:1954`（`rte_pktmbuf_free`）、`:2009-2030`（storage 侧 `storage_engine_submit_session` + 两次 `virtual_collection_update_ip` 后归还 task） | **成立** | 主线正确 | 无。可注明“mempool 取不到 task 时静默跳过，不记丢弃计数”这一分支（`:1926` 的 if 无 else），与原文“不能保证每次统计任务产生”一致 |
| PF-014 | worker 无包分支也转到分摊式过期清扫 | `main.c:1849-1856`（`nb_rx==0` → `goto worker_periodic`）、`:1957-1963`（`session_flush_expired_amortized(my_session, &flush_cursor, SWEEP_BUCKET_SCAN_CAP, SWEEP_TIME_BUDGET_US)`）、`:107-114`（参数定义）、`session/session.c:516-576`（游标、`bucket_scan_cap`、`time_budget_us`，回调在 free 前） | **成立** | 主线正确 | 建议补：`worker_periodic:` 位于 for 循环之后（`:1957`），因此**有包的那一轮同样会落到分摊清扫**，不只是 idle 分支；另外单线程模式仍是每 30 秒**全量** `session_flush_expired`（`:1531-1533`），两种模式清扫策略不同。原文“不验证时间预算的实际效果”的限定正确 |

## 三、图审（packet_flow 流程图）

现状：`flowchart TD` 中 `C{解析成功且为 TCP/UDP?}` 的“否”边指向 `G[按实际分支继续或返回]`，`G --> I[回调返回]`，绕过了 `H[执行到回调尾部时检查周期任务]`。

代码：只有 `parse_packet` 返回负值时（`main.c:1258-1262`）才提前 return；协议不是 TCP/UDP 时跳过的是 `:1272-1368` 整块，随后仍会顺序经过 `:1455` 日志 flush、`:1457` 统计、`:1511` 每小时 flush、`:1531` 会话清扫、`:1541` 停止判断。

判断：**图不精确，文字（PF-009 段正文）正确**。影响限于“ ICMP/ARP 等报文是否触发周期检查”这一分支事实，不动摇主线（对象顺序、统计先后、释放点均无误）。

建议：把判定拆成两级——先“解析成功？”（否 → 直接回调返回），再“TCP/UDP？”（否 → 汇入回调尾部的周期检查），使图与 `main.c` 控制流一致。

## 四、对第二批的授权判断

按任务书标准，只有“会改变教学主线的事实错误”才阻断。本次未发现此类问题：25 项事实的项目结论全部在固定快照中得到复核支持，措辞普遍带范围限定，未把注释、通识或未验证性能写成实现保证。

因此**允许继续第二批**，但须完整保留以下审核意见，并在第二批中落实：

1. **需要用户处理/确认的三项建议**（属首批内容质量，不属第二批范围，未擅自改动旧稿）：
   - PF-006 定位符号与行范围不匹配（改为 `session_lookup` 或增引 `session.c:58-79`）。
   - packet_flow 流程图“否”分支与 `main.c` 控制流不一致。
   - PF-010/PL-010 建议补写的“关闭路径不经过期回调”“`engine_registry_unregister` 是第三个释放边界”两条，属新增事实，须由用户决定是否回到首批修订。
2. **本次审核的既有约束保持不变**：首批两篇仍标“待人工审核”，本报告不改变其状态，不构成入库或发布依据。
3. 上述第 1 项的三处修改**均未在本次审核中实施**——按任务书要求，不修改原有样稿与事实表。

## 五、本审核的局限

- 只读源码，未执行任何构建、链接、抓包、插件加载或数据库操作；`dlopen`/`dlclose` 的实际资源后果、锁竞争行为、清扫时间预算有效性均未验证。
- “符号存在”不作为结论依据：本报告对每项都读到了具体分支行。
- 未读取 `teaching_harness` 教师参考答案、学生源码或任何凭据。
- 判断“教学主线”是否被改变，仍属本 Agent 的静态阅读结论；最终教学适切性需教师/模块负责人确认。


## 六、意见处置（2026-09-18 追加）

用户已就本报告第四节列出的三项意见作出处置决定，首批草稿与清单随之升到 rev 2：PF-006 定位拆开引用、
单线程流程图改为两级判定、补记 PL-012 / PL-013 / PF-015 三条事实。修订记录见 `../checks/validation.md` 与
`../delivery.md` 末尾。本报告正文保留原判断过程不改写，以便对照"审核时看到什么、之后改了什么"。
两篇仍为**待人工审核**，本处置不等于内容审核通过。
