# 第三批 TLS 专题独立审核报告

审核类型：**独立 Agent 静态审核**（与第三批编写者不同的任务上下文，重新逐条回读固定快照源码）。
本审核**不是**人工审核、**不是**运行验收、**不是**安全审计；未编译、未加载 `.so`、未抓包、未构造报文、未调用模型、未执行项目测试。
[人工审核清单](review_checklist.md) 的复选框由人工审核者本人勾选，本报告不代其勾选、也不改变本篇"待人工审核"状态。

- 审核日期：2026-09-19
- 被审对象：[drafts/tls_module_boundaries.md](../drafts/tls_module_boundaries.md)、
  [evidence/tls_module_boundaries.md](../evidence/tls_module_boundaries.md)、[manifest.json](../manifest.json)（22 项 TLS 事实）、
  [checks/chunk_preview.json](../checks/chunk_preview.json)
- 源码版本：`20f882691ac03998e0e148e0a66041227cd3f751`（分支 master）；助手仓库 `a8d0fbec7667a0580a8c6b7fde838d1b7aa0012d`（main）
- 快照完整性：审核前把 `manifest.source_files` 的 22 个快照与 `git show <commit>:<path>` 逐字节比对，**0 处不一致、行数全部相同**
- 工作区状态：主仓库 HEAD 等于固定提交，`status --porcelain` 仅 `?? ai_chat_assistant/`；助手仓库 HEAD 等于记录值，仅 `?? knowledge_work/`
- 审核方式：按事实编号定位到函数体，读调用者、失败分支与写出点；不以"符号存在"作为结论依据
- 越集读取：为判定 TLS-012/014/021 三条断言的真伪，另读了 `plugin/plugins/mongo_storage.c`、`session/session.c`（首批快照内）、
  `plugin/plugins/engine_tunnel.c`、`plugin/plugins/engine_dns.cpp`、`docs/codebase_reference.md`、`docs/gen_design_doc.py`（后四者在主仓库工作树，
  内容与固定提交逐字节相同）。**这些文件不在本批来源清单，其内容不作为本篇的依据，只用于判定断言真伪**（见第二、四节）

## 结论先行

| 判定项 | 结果 |
| --- | --- |
| 改变教学主线的事实错误 | **未发现**（22 项的项目结论方向均与源码一致） |
| 经复核**不成立**的断言 | **1 条**：TLS-021 内"docs/codebase_reference.md 提到 engine_http.c"——该文件在本提交内 0 次出现 `engine_http`；提该文件名的是 `system_design.md:594/1475` |
| 可数的数字/范围错误 | **1 条**：TLS-019 "`tls_parser.c` 被三个 `.so` 各编一份" 少计，同文件另有 3 个目标链它 |
| 把源码注释当实现事实 | **1 条**：TLS-019 "条目硬上限 20000"（实际淘汰还要求该流缓冲已空） |
| 红线相邻句的误读风险 | **1 条**：TLS-018 段把"属密码学边界"紧接 `is_doh` 恒为 0 |
| 结论成立但依据未登记在本批来源集 | **2 处**：TLS-012（mongo_storage.c）、TLS-014（session.c）；两者本次回读均**为真** |
| 行范围/符号定位错误 | **未发现**（22 项 refs 的符号全部在所引行范围内命中，首批曾出现的 PF-006 类缺陷本批没有） |
| 图与文字不一致 | 1 项：流程图未标注"本图为单线程路径" |
| 自动检查记录的复算 | 一致；清单第 31 行"最长约 1017 字符"应为 **1016**（`validation.md` 与 `chunk_preview.json` 均为 1016、10 片） |
| 是否阻断后续批次 | **不阻断**（详见第六节） |
| 本篇状态 | 仍为**待人工审核**；本报告不代替人工确认，不构成入库或发布依据 |

用量：精确模型标识、token 与费用**不可得**，按批次约定不记为零。

---

## 一、逐条事实判定

| 编号 | 原陈述（摘要） | 源码依据（快照内 path:line） | 判断 | 影响 | 建议修改 |
| --- | --- | --- | --- | --- | --- |
| TLS-001 | 五个 ops 表与常量（2/20、3/30、4/40、5/50、443/180），五者 `get_stats`/`reset_stats` 均空 | `https_plugin.c:86-96`、`tls_plugin.c:184-194`、`cert_plugin.c:119-129`、`ja3_plugin.c:84-94`、`engine_tls_infra.cpp:808-818`（五处 `.priority` 与 `get_stats = NULL` 逐处核对） | **成立** | 主线正确 | 见第三节第 7 条：`extern "C"` 与"静态 ops 表"两处措辞 |
| TLS-002 | 固定列表按 access 判断后加载；流水线为每 worker 各加载一份；链序由 priority 决定；本簇列表恰好同序 | `main.c:2384-2436`（列表、`:2426` access、`:2427` load）、`main.c:1618-1626`（`:1625` 每 worker 一份）、`engine_registry.c:35-51`（`:37-38` 严格小于头插、`:45` 同值前进→同值末尾） | **成立**，"恰好同序"已复算 | 主线正确 | 列表内 `engine_tls_infra.so` 在 `engine_dns.so` 之后，与 180>120 相符 ✓；正文"中间还有 DNS 的 120"见第三节第 8 条 |
| TLS-003 | 判据两类：四个只看记录特征不看端口；`TLS` 有端口门控 | `tls_plugin.c:43-47`、`ja3_plugin.c:23-26`、`cert_plugin.c:30-33`、`engine_tls_infra.cpp:516-531`（TCP+有负载、`:519-520` 443/853 双向、`:526-528` 首字节 0x14/0x15/0x16/0x17 且 `payload[1]==0x03`） | **成立**，但 HTTPS 一侧依赖 TLS-006 才成立 | 若两条被分开召回会误读 | refs 未含 `https_plugin.c:24-33`（结论涉及它）；且该 `can_handle` 把 `dst_port` 传进 `is_https_traffic`，"不看端口"是短路后的效果——见第三节第 8、9 条 |
| TLS-004 | 本簇无人返回 `ENGINE_HANDLED` | `tls_plugin.c:104/141/152/170/173`、`https_plugin.c:46/76`、`ja3_plugin.c:34/74`、`cert_plugin.c:109`、`engine_tls_infra.cpp` 全文 return 枚举仅 SKIP（`:539/:549`）与 CONTINUE（`:753/:779`） | **成立** | 主线正确 | 无。"五个文件内 `ENGINE_HANDLED` 出现次数为 0"这一自审方法可复现 |
| TLS-005 | 私有计数无导出路径；只有 TLS 插件自增 `ctx->stats.packets_matched` | `engine_tls_infra.cpp:726`；`tls_plugin.c:106`、`https_plugin.c:39`、`ja3_plugin.c:37`、`cert_plugin.c:44`；registry 侧 `engine_registry.c:211` 仅在 HANDLED 时加 | **成立**（`:726` 的自增确实落在同一 entry 的 `ctx.stats`，会被 `engine_get_all_stats` 读到——反差是真的） | 主线正确 | 见第三节第 11 条（主表首句语义倒置）；另可补一句：`https_count++` 在 process 入口无条件自增，另三个在解析成功后才加 |
| TLS-006 | `(is_https_port && is_tls) || is_tls` 使端口条件被短路 | `https_identifier.c:47-64`（`:53` is_https_port、`:59` 合取式）、`:17-45`（`:30-36` 五个版本、`:40` 长度非 0 且 ≤16384） | **成立**，化简与附加两道门控均核到行 | 纠误条目，写得好 | 无 |
| TLS-007 | 两个解析器都要求捕获长度含完整记录与完整 ClientHello 体 | `tls_parser.c:207-210`（`length < 5U + record_length`）、`:222-225`（`handshake_length < 4 + hello_length`）；`ja3_fingerprint.c:98-101`（只有第二道，未校验记录层声明长度）；调用点 `tls_plugin.c:102-105`、`https_plugin.c:44-47`（失败即返 CONTINUE 不发事件） | **成立** | 主线正确 | 无 |
| TLS-008 | 旁路条件、缓冲 65536、溢出归零、只对 0x0b 提交 | `main.c:1384-1386`（protocol 6、payload 非空、`src_port == 443`、两全局句柄）、`:59-63`（`TLS_STREAM_BUF_SIZE 65536`）、`:1403-1405`（空缓冲只收 0x16 起头）、`:1408-1411`（溢出 `len = 0`，非 0x16 则 skip）、`:1417`（只扫 0x16 记录）、`:1427-1431/1437` | **成立** | 主线正确 | 精度建议：`:1403` 与 `:1410` 是**两道**不同的"非 0x16 就跳过"（空缓冲起步门 / 溢出后门），现写法像一条。旁路还只处理 `src_port == 443` 的服务端方向，可点一句 |
| TLS-009 | 旁路只在单线程可达；但 `engine_cert.so` 仍在 worker 注册中心内被调度 | 句柄声明 `main.c:160-161`；**唯一**赋值 `:2456/2465` 都在 `init_engine`（`:2441` 起）；`init_engine` 唯一调用点 `:3154`，位于 `:3146` 的 else（单线程）分支；旁路唯一调用点 `:1437` 在 `packet_handler`（`:1241` 起）内；worker 侧仅 `:1618-1626` 加载链，无 `tls_stream_buf` 等价代码（该符号在 main.c 只出现在 `:56/63/1389/1393/1395/1397`） | **成立**，任务书 §七.1 的待闭合线索就此闭合 | 本篇最干净的一条 | 无。"这不等于 worker 不产出证书记录"的写法正是红线要求的形态，保留 |
| TLS-010 | 两入口共用 `cert_engine_extract`，只以标签区分；链上入口忽略结果 | `cert_plugin.c:106-110`（忽略返回值 + `:107` 注释"通常是 TLS 分片包…属正常情况"）、`:36-42`（提取失败 `return ENGINE_ERROR`）、`:135-137`（旁路入口原样返回 `"Reassembled"`）、`:108`（`"Direct"`） | **成立** | 主线正确 | 无。"该插件的失败不上报为 ERROR"措辞准确 |
| TLS-011 | 只取链上第一张证书；字段来自 `d2i_X509` | `cert_extractor.c:117-122`（跳 3 字节链总长）、`:124-138`（只读一个 3 字节长度 + 该段 DER）、`:140-199`（issuer/subject/notBefore/notAfter；`else` 分支 `is_valid = 0`，`memset` 在 `:87` 保证解析失败时字段为空） | **成立** | 主线正确 | 任务书 §五.5 点名的 **SAN 未提取**未登记：全文件无 subjectAltName 路径（另见第三节第 13 条死代码） |
| TLS-012 | 三个写出点；不经 ch_event；MongoDB 未就绪即返回且持进程级锁 | `cert_plugin.c:17-26`（`:22-23` mkdir 相对路径）、`:36-104`（`:72-73` 落盘回填 der_path、`:76-79` mongo、`:81-101` engine_log_event）；该文件 include 无 `ch_event.h` ✓ | **断言成立但依据部分越集**：mongo 的静默返回与锁在 `plugin/plugins/mongo_storage.c`（不在 22 文件内），本次已核为真（见第四节） | 若不改，读者无从判断依据在哪 | 见第四节修法 (i)/(ii)；另建议对 `engine_log_event` 加半句"其下游落点不在本批来源内"——现文字只说"写一条插件日志事件"，未夸大成落库 ✓ |
| TLS-013 | 临时 `parsed_packet_t` 只填七个字段，未填 `ip_version` 与 IPv6 地址 | `main.c:1428-1436` 字段集恰为 payload/payload_len/src_ip/src_port/dst_ip/dst_port/protocol（指定初始化，其余为 0）；下游 `cert_plugin.c:58-65`（`:59` `ip_version == 6` 才走 AF_INET6） | **成立**（IPv6 会话经此通路取 0 → AF_INET 分支 → 全零地址） | 主线正确 | 无。"是否有意取舍未在代码中找到说明、不定性为缺陷"处理得当，保留 |
| TLS-014 | TLS_Parser 用候选槽不参与 `ext_locked` 仲裁；ServerHello 依赖该缓存 | `tls_plugin.c:115-135`（`:120` set_candidate，失败 free 后继续）、`:155-169`（命中未发过沿用客户端视角；无缓存翻转 src/dst 且 SNI 传 `""`）；`session.h:129-135`（ext_type/ext_data 与 candidates 并存、`:135` ext_locked） | **成立**，"不参与仲裁"需 `session.c` 才可证（不在本批清单，本次已核为真，见第四节） | 主线正确 | 同段正文"`TLS` 插件用单值槽 `EXT_FIELD_TLS_INFRA`"无对应事实编号（见第三节第 10 条） |
| TLS-015 | 套件字段单值、`count` 恒 1、未收录 ID 得占位名 | `tls_plugin.c:63-65`（`tls_cipher_suite_name(cipher_id)`、`cipher_suites_count = 1`）、`:108`（首个客户端首选）、`:163/168`（服务端选定）；`tls_parser.c:483-492`（`:490` `"CIPHER_0x%04X"`） | **成立** | 主线正确 | 可选补强：占位缓冲是 `static __thread`（`:489`），与本批"状态是否共享"主题呼应；refs 起始行 `:477` 落在名称表中段，可收窄到 `:483-492` |
| TLS-016 | 静默截断：套件 128、扩展 64、SNI 缓冲 256（复制上限 255）；循环到 64 停 → SNI 可能取不到 | `tls_parser.h:13-15`；`tls_parser.c:256-265`（`:265` count 记截断后）、`:296`（`extensions_count < MAX_EXTENSIONS` 与 SNI 同一趟循环）、`:318-341`（`:333` `sizeof(hello->sni) - 1`） | **成立** | 主线正确 | "四处"实列三处（清单第 22 行又写"三处"），且草稿"缓冲 255 字符"与主表"缓冲 256 字节"口径不一——见第三节第 9 条 |
| TLS-017 | 版本取最高非 GREASE 或回退 legacy；ServerHello 读真实协商版本；JA3 拼接后 MD5、未见 GREASE 过滤 | `tls_parser.h:19-23`；`tls_parser.c:15-17/23-39`（GREASE 判定与客户端版本选取）、`:310-315`（调用点）、`:41-50`（ServerHello 要求扩展体恰 2 字节）、`:409-415`；`ja3_fingerprint.c:84-85`（**JA3 只用记录层版本，从不看 supported_versions**）、`:223-233`（拼接 + MD5） | **成立** | 主线正确 | "ServerHello 同理"与"它表示客户端声明的最高版本"两句相邻，`它` 指代易被读成含 ServerHello；建议明确"ClientHello=客户端声明最高；ServerHello=协商结果"，并补半句"JA3 串用的是记录层版本" |
| TLS-018 | 只可能报出 ECH 与 DoT；未命中不发事件；`is_doh`/`is_dns_tunnel` 硬编码 0；每种类型每会话一次 | `engine_tls_infra.cpp:476-498`（`:484` ECH、`:489-492` 端口 853 或 dot ALPN、`:495-497` ECH 优先返单类型）、`:661-674`（`detail != NULL` 才走发送）、`:698-701`（两字段常量 0）、`:259-276`（dot/ech 两个 emitted 标志去重）、全文件 `ch_event_enqueue` 仅 `:703` 一处 | **成立** | 主线正确 | (a) 命中前提须补：`identify_tls_features` 只在**重组出完整 ClientHello 记录**（`:616-629`）后被调用，端口 853 单独不构成命中；(b) 红线相邻句问题见第二节第 3 条 |
| TLS-019 | 两套解析实现；TLS 插件重组参数；`tls_parser.c` 被三个 `.so` 各编一份；主程序不链它 | `engine_tls_infra.cpp:35-41`（常量）、`:286-304`（feed 的上限与超时语义）、`:601`（c2s/s2c 分方向 → 每方向 32768 成立）、`:606-610`（单包 4 条）；`plugin/plugins/Makefile:210-211`（通用 cpp 规则，`engine_tls_infra.so` 确走此规则）、`root Makefile:66`（OBJS 无 tls_parser.o ✓） | **两处需改**：见第二节第 1、2 条（.so 计数、"硬上限"） | "改它影响哪些插件"的答案被低估；结论方向不变 | 见第二节 |
| TLS-020 | JA3 与 `tls_parser.c` 在 Session ID 跳变上的检查集合不同；不作安全判断 | `ja3_fingerprint.c:117-120`（只有 `remaining < 1`，随后 `remaining -= 1 + session_id_length` 无校验）；`tls_parser.c:241-245`（`:243` 有 `remaining < 1 + session_id_length` 一道） | **成立**，止于"条件不同" ✓ | 红线守得住 | 无。给下一批代码评审的落点：JA3 侧 `remaining` 为 uint32，减法在 `session_id_length` 偏大时回绕，其后 `remaining < 2` 一类判断即失效——是否可达属安全审范围，本审核同样不下结论 |
| TLS-021 | 既有文档优先级表与实现全部不符；`engine_http.c` 不在该 `.so` 源列表 | `system_design.md:1473-1479`（表头含"优先级（参考）"，HTTP 100/HTTPS 110/TLS 120/TLS Infra 130/DNS 50）、`plugin/plugins/Makefile:109-111`（`engine_http.so` 源列表为 `http_plugin.c http_parser.c`） | **数值对比成立**；但同一句里"`docs/codebase_reference.md` 提到 engine_http.c" **不成立**（见第二节第 4 条），"整表错位"是**推断**当成事实陈述 | 有一条假断言 + 措辞层级问题 | 见第二节第 4 条；"整表错位"改为"每行数值都与实现不符（成因未在代码中找到）" |
| TLS-022 | 五环排查链 | `tls_plugin.c:71-74`、`engine_tls_infra.cpp:546-550`（两 refs 有效）；每一环指回本篇已定位处：轮次→`engine_registry.c:35-51/200-222`、判据→TLS-003、长度→`tls_parser.c:207-225`、截断→`tls_parser.c:296/333`、通路→`main.c:1384-1437` | **成立，可照着走** | 教学价值最高的一条 | 无。第一环"前面有无 HANDLED——本簇没有"可与 TLS-004 呼应注明"其他簇有"（自审 §逐条重读记录第 4 行已 grep 到 FTP/IMAP 等用 HANDLED，但该文件不在来源集，维持现在的不写更好） |

## 二、需修改（四条）

1. **TLS-019 的"三个 `.so`"少计**。同一提交 `plugin/plugins/Makefile` 里链入 `tls_parser.c` / `tls_parser.o` 的目标共 **6 个**：
   `engine_https.so`（`:120-125`）、`engine_tls.so`（`:127-129`）、`engine_ja3.so`（`:135-137`）、
   `engine_tunnel.so`（`:144-147`）、`engine_site_ranking.so`（`:199-205`，经 `tls_parser.o`）、`engine_quic.so`（`:218-220`）；
   其中 tunnel/quic/site_ranking 均在 `main.c:2384-2423` 的加载列表内。
   改法：事实与正文改成"至少 5 个 `.so` 直接链 `tls_parser.c`（另有 site_ranking 链其 `.o`）"，
   并把 refs 从 `:120-137` 扩到含 `:144-147`、`:199-205`、`:218-220`。"同一份源码的副本互不共享"的结论不受影响，
   但"改它会影响哪些插件"是本篇承诺回答的问题，数字必须对。
2. **TLS-019 的"条目硬上限 20000"是把注释当行为**。`engine_tls_infra.cpp:585` 的实际条件是
   `empty_bufs && (now - last > TLS_FLOW_IDLE_TIMEOUT || cur_sz > TLS_FLOW_MAX_ENTRIES)`——缓冲非空的流即使已过 2 万条也不淘汰；
   "hard upper bound before aggressive eviction" 是 `:41` 的注释措辞。按任务书 §八的四类表述分层，这属于"注释/设计意图"。
   改法：写成"空闲超 300 秒、或条目数已超 2 万**且该流缓冲已空**才被淘汰"。
3. **TLS-018 段的红线相邻句**。草稿与证据表在 `is_doh`/`is_dns_tunnel` 恒为 0 之后紧接"加密后还能观测到什么属密码学边界"，
   单片召回时（chunk 7）容易被读成"DoH 测不到是加密导致的"。代码事实支持更强的表述：全仓仅 `engine_tls_infra.cpp:698/701` 写这两个字段且写 0；
   `session.h:638/641` 的 ext 位域与 `:644/:647` 的 `doh_event_emitted`/`tunnel_event_emitted` **没有任何写入者**（全仓 grep 仅声明处命中）。
   即 DoH 属"代码未实现"，与"密码学上不可得"无关。
   改法：把"属密码学边界"一句限定为"握手之后的密文记录内容"，并加一句"`is_doh` 恒 0 是未实现，不是加密导致不可得"。
   注：`plugin/plugins/engine_tunnel.c:49/53` 仍按 `ext->is_doh`/`is_dns_tunnel` 分支（本版本为死分支）——
   这条能把"完全没人做 DoH"的误读也堵住，但该文件不在本批来源清单，须先按 §四登记补读理由才可写入正文。
4. **TLS-021 内"`docs/codebase_reference.md` 提到 engine_http.c" 经复核不成立**。该文件在固定提交内 `engine_http` **0 次命中**
   （在该提交下对 `docs/codebase_reference.md` grep `engine_http` 无任何命中；它只在 `:518` 列 `http_plugin.c`、`:524` 列 `engine_tls_infra.cpp（DoH/DoT/ECH）`）。
   写有 `engine_http.c` 的是 **另一份文档**：`docs/system_design.md:594` 与附录表 `:1475`（`gen_design_doc.py:551` 是 `:594` 那张表的生成处，该表无优先级列）。
   此断言来自任务书 §六 D 的"已核实"条目，本篇照抄而未回读该文件——正是任务书 §二"不得因为前一批 Agent 写了检查通过就省略源码复核"要拦的情形。
   改法：删去该半句，或改写为"`engine_http.c` 只出现在 `system_design.md:594/1475`，`plugin/plugins/Makefile:109` 的 `engine_http.so` 源列表里没有它"。
   顺带：同条"整表疑似按旧编号错位"在代码中找不到支撑（附录表的优先级不来自生成器），按任务书 §八应标为推断，
   事实部分只写到"每行数值都与实现不符"即可。

## 三、建议级（不改变结论，影响可核性与切片质量）

- **两处结论成立但依据越集**（TLS-012 / TLS-014）：见第四节。

7. **TLS-001 措辞**：`extern "C" … engine_get_ops` 只有 `engine_tls_infra.cpp:820` 字面成立（且在所引 `:808-818` 之外）；
   四个 `.c` 文件是默认 C 链接，`engine.h` 侧只有 `:16` 的整头文件包裹与 `:260` 的函数指针 typedef，并未声明该函数。
   "静态 ops 表"在四个 `.c` 里是文件级**非 static** const（只有 `:808` 的是 static）。建议改为"五个文件各自导出同名 `engine_get_ops`（C++ 侧显式 `extern "C"`）"，refs 扩到 `:808-821`。
8. **正文与事实表互相打脸的一处**：chunk 4 写"中间还有 DNS 的 120"，而 TLS-002 明确写"本簇之外…故不列具体值"。
   该值经我核为真（`engine_dns.cpp:1119-1121`：name DNS、protocol_id 53、priority 120），但文件不在本批清单。删数值或标跨批引用（DN-002）。
9. **上限计数三处不一致**：TLS-016 说"四处"只列三处、清单第 22 行说"三处"、草稿"缓冲 255 字符"对主表"缓冲 256 字节"。
   建议统一为"三处（128 套件 / 64 扩展 / SNI 缓冲 256、可用 255 字符）"；若要把 JA3 侧四个 snprintf 目标缓冲
   （`ja3_fingerprint.c:128/163-165` 的 2048/1024/512/128）算进去，就明说是"tls_parser 三处 + JA3 字符串四处"。
10. **断言未落事实号**：正文"单值槽 `EXT_FIELD_TLS_INFRA`"代码为真（`engine_tls_infra.cpp:199-215`，经 `session_set_ext_data` 占槽并置锁），
    但该段来源行只写 TLS-001、TLS-014。建议并入 TLS-014 的陈述与 refs（两句话即可）。
11. **TLS-005 主表首句语义倒置**："TLS_Parser 命中后自己给 `ctx->stats.packets_matched` 加一的位置在 TLS 基础设施插件里"——
    主语与施动者错配。证据表是会被切进 RAG 的形态，建议改为"本簇内 `packets_matched` 只有一处增加：TLS 基础设施插件发事件时（`:726`）；
    TLS_Parser 等四个只加私有计数，而五者 `get_stats` 为空"。
12. **图审**：流程节点 A"parse_packet 填 payload"只在单线程成立（`main.c:1241` 起的 `packet_handler`；worker 路径见首批 PF-012/013），
    节点 I 也只在单线程，但图未标"本图＝单线程路径"，与首批对 packet_flow 的同类意见一致。
    其余核对无误：C→G 的顺序与 priority 20/30/40/50/180 一致；H→I 与 `main.c:1381 → 1383` 先后一致（先 engine_process 后进旁路）。
    建议在 A 节点前加一行说明或把标题改为"一条 443 报文的执行顺序（单线程路径）"。
13. **任务书 §五.5 的 SAN 项未登记**：`cert_extractor.c` 无 subjectAltName 提取路径；文件内 `parse_asn1_length`（`:22-49`）与
    `extract_asn1_string`（`:52-79`）及 `:13-19` 的 `ASN1_*` 宏只互相调用、无其他调用者，属死代码，易被读成"自己解析 ASN.1 取字段"。
    建议在 TLS-011 补"未提取 SAN 与其余扩展项（本文件内未发现相应代码）"，并一句点出这两个未使用的辅助函数。
14. **清单第 31 行的数字**：应为"最长 1016 字符"（与 `validation.md`、`validation_result.json`、`chunk_preview.json` 一致）。
    清单第 22 行"三处上限截断"随第 9 条一并统一。

## 四、两处证据登记缺口（两种修法，请择一）

| 事实 | 越集依据（本批清单外） | 本次核验结果 | 修法 (i) 补登记 | 修法 (ii) 收窄 |
| --- | --- | --- | --- | --- |
| TLS-012 | `plugin/plugins/mongo_storage.c:288-317` | 为真：`:294` `if (!g_ready \|\| !g_col_cert) return;` 静默不写；`:295/:316` 全程持 `g_mongo_lock`（`:34` 注释说明是为修 libbson 并发崩溃加的进程级锁）；`:311-313` 写失败只记错误 | 该文件加入 `source_files`（22→23），refs 增一行 | 把两句改标"依第二批 SF-007 的同源观察/本批未登记来源"，或直接删去锁与静默返回两句（"同步写"这一层仍可保留，因调用点本身在 `cert_plugin.c:76-79`） |
| TLS-014 | `session/session.c`（本批未含；首批快照内有） | 为真：`session_set_candidate_data`（`:414-426`）只写 candidates，不触 `ext_locked`；置锁在 `session_set_ext_data`（`:371-373`），而 `session_confirm_candidate`（`:428-443`）才做锁定仲裁 | 加入 `session.c`（附补读理由） | 把"不参与 `ext_locked` 的锁定仲裁"改标为引用第二批 SF-007/SF-008 结论 |

另可考虑（不属缺口，属可选补强，来源集内即可证）：`plugin/plugins/Makefile:106-107` 说明插件**不链接** ch_storage/mongo_storage，
其符号由主程序经 `-Wl,-export-dynamic` 导出，而 `Makefile:66` 的 OBJS 确含 `plugin/plugins/mongo_storage.o`——
即"证书写入实际在主程序进程内的 mongo 模块里执行"。这一句能支撑 TLS-012 的"同步"含义，且不引入越集依据。

## 五、四条红线与整体验收项逐条判

| 清单项 | 判定 |
| --- | --- |
| 红线"代码没做 ↔ 密码学不可得"（清单第 9 行） | 22 项中无一处互写；唯一风险是 TLS-018 段的相邻句（第二节第 3 条） |
| 红线 `is_doh` 恒 0 的两种误读（第 10 行） | "支持 DoH 检测"已被现有措辞挡住；"完全没人做 DoH"未被挡住（engine_tunnel 死分支、session.h 死位域未提），且与"密码学边界"同段 → 建议按第二节第 3 条收口 |
| 红线"加载了 ≠ 每条路径都执行"（第 11 行）+ TLS-009 两处证据 | **通过**，TLS-009 是全篇写得最严谨的一条 |
| 红线"计数/调用 ≠ 已落库"（第 12 行） | 未发现违反：TLS-005 区分两套统计、TLS-007 写"不发事件"、TLS-012 写了静默返回条件（唯依据越集） |
| 每节自带条件与结论 | 通过：10 片各带"来源＋事实编号"行，与所在节同片；mermaid 与文字同片（chunk 5） |
| 逐片读 chunk_preview | 通过但两处风险：chunk 4 的"不看端口"缺 TLS-006 限定；chunk 7 一片装了 6 项事实（TLS-007/011/015/016/017/018），任一限定丢失都会被 judge 当证据——建议按第三节第 9 条与第二节第 3 条收口后再评估 |
| 无部署地址/凭据/学生信息/教师答案/可通过作业的实现 | 通过：正文与证据表无凭据形态、无 C/C++ 围栏、绝对路径仅出现在 manifest 的 `source_repo`/`assistant_repo` 字段（清单字段非正文）；"全零地址"已用中文表述，未出现字面量 |
| 篇幅 2122 字超建议 | 见第六节建议 |
| 是否值得入库实验 | 见第六节建议 |

## 六、对后续动作的判断

- **不阻断**任何既有计划：本篇无改变教学主线的事实错误，自动检查的复算结果与存档一致，快照与来源逐字节可复现。
- **但 TLS-019 的两个数字（`.so` 计数、2 万上限语义）、TLS-018 的相邻句，以及第二节第 4 条那条不成立断言，须先改再入库**：
  这四处都是 judge 会当证据引用的形态（可数、可断言、可被当作"文档另有出处"）。
- 篇幅 2122：**建议不拆篇**（拆两篇会薄，且 22 项事实本就是任务书 §八为单篇设定的上限）。
  定向压缩即可回收 40–80 字：chunk 4 里 TLS-019 的六个重组参数罗列（32768/4/30/60/300/2 万）压成"每方向 32768 字节、单包最多 4 条记录，流表另有修剪与淘汰参数"，
  数字仍留在证据表；接受超长的理由已在 `validation.md` 告警段写明，无需再放宽阈值。
- 入库实验前置（本批明确未做，本报告也未做）：独立保留题集、只改语料的 A/B、引用链路确认、模型预算。
- 若确认 TLS-013（未填 `ip_version`）与 TLS-020（检查集合不同）属实现问题，应另开代码批单独授权；本审核未改任何代码，也未改本篇任何文件。

## 七、本审核的局限

- 只读源码。未编译、未 `make`、未 `dlopen`、未抓包、未构造报文、未跑项目测试、未做数据库与索引操作、未调用模型；
  "同一 `.so` 被多个 worker 加载时文件级静态是否共享"（DR-001 遗留）本审核同样未验证，故未据此评判 TLS-019 的运行时措辞。
- 判定"教学主线是否被改变"仍属静态阅读结论；教学适切性需教师/模块负责人确认。
- 未读 `teaching_harness` 教师参考答案、学生源码或任何凭据。
- 越集读取（第四节两处 `mongo_storage.c`/`session.c`，以及为证伪 TLS-021 与顺带核值而读的
  `docs/codebase_reference.md`、`engine_dns.cpp`、`engine_tunnel.c`、`gen_design_doc.py`）只用于判定断言真伪，
  不使它们成为本篇依据；如要作为依据须走 rev 2 与补登记。
- 本报告**不勾选** [review_checklist.md](review_checklist.md)：那 18 个复选框的语义是人工审核者已打开快照自行核对。

## 八、意见处置（2026-09-19 追加）

用户授权按本报告落实修订，第三批已升到 **rev 2**。本报告正文保留审核当时的判断过程不改写，处置结果记在本节。

| 意见 | 处置 |
| --- | --- |
| 第二节 1（`.so` 计数） | **已改**：事实与正文按 Makefile 数得"5 个 `.so` + `engine_site_ranking.so` 链其 `.o`"，refs 增 `:144-147 / :199-205 / :218-220` |
| 第二节 2（2 万上限语义） | **已改**：写成"空闲超 300 秒、或条目数过 2 万**且该方向缓冲已空**"，注明注释与条件之别，refs 增 `:573-590` |
| 第二节 3（DoH 与"不可得"） | **已改**：加"须先重组出完整 ClientHello 记录"前提（refs 增 `:616-629`），并明写"代码未实现、不是加密导致不可得"；密码学一句限定到握手后的密文。`engine_tunnel.c` 的死分支**未写入正文**（该文件仍不在来源集） |
| 第二节 4（`codebase_reference.md` 误引） | **已删**：改引 `system_design.md:594`，"整表错位"降级为未证实解释 |
| 第四节两处越集依据 | 选**修法 (ii)**：正文与事实表只述本批来源内的调用点，mongo 落库语义与 `ext_locked` 仲裁规则移入证据表的待确认清单（现 9 项）。未扩来源集，22 个快照不变 |
| 第三节 7–14 | 7/8/9/10/11/12/13 已改；14 的清单数字随 rev 2 一并更正（现为"10 片、最长 1140 字符"、"篇幅 2249 字"） |

rev 2 后 `check_documents.py` 重跑：0 失败、1 告警（字数 2249，仍建议不拆篇），10 片最长 1140 字符；
证据表由 `build_evidence.py` 从清单单向重建；`build_manifest.py` 保持 rev 1 原样、未重跑。
前两批 36 个文件 sha256 与基线一致，本批 22 个快照仍与固定提交逐字节相同。

**状态不变**：本篇仍是**待人工审核**。编写者自审 + 独立 Agent 静态审核都不等于人工审核，也不构成入库、发布或质量提升的依据。
