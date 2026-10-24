#!/usr/bin/env python3
"""一次性生成 zh_batch03/manifest.json 并就地校验每条引用的行范围与定位文本。

只读快照，写 manifest.json。用途是建立本批清单；此后修改事实请直接编辑 manifest.json，
不要重跑本脚本（重跑会覆盖后续修订）。证据表由 build_evidence.py 从 manifest 单向生成。
"""
import json
import uuid
from pathlib import Path

C = '20f882691ac03998e0e148e0a66041227cd3f751'
BASE = Path(__file__).resolve().parents[1] if '__file__' in dir() else Path('.')
BASE = Path('/Users/fsy/code/dpdk/ai_chat_assistant/knowledge_work/zh_batch03')
SNAP = BASE / 'snapshots' / 'dpdk' / C
ST = '已核对（编写者静态自审；待人工审核）'

FILES = ["main.c", "plugin/engine.h", "plugin/engine_registry.c",
         "plugin/plugins/tls_plugin.c", "plugin/plugins/tls_parser.c", "plugin/plugins/tls_parser.h",
         "plugin/plugins/https_plugin.c", "plugin/plugins/https_identifier.c", "plugin/plugins/https_identifier.h",
         "plugin/plugins/cert_plugin.c", "plugin/plugins/cert_extractor.c", "plugin/plugins/cert_extractor.h",
         "plugin/plugins/ja3_plugin.c", "plugin/plugins/ja3_fingerprint.c", "plugin/plugins/ja3_fingerprint.h",
         "plugin/plugins/engine_tls_infra.cpp", "plugin/plugins/Makefile", "session/session.h",
         "plugin/plugins/ch_event.h", "plugin/plugins/ch_event.c", "docs/system_design.md", "Makefile"]
src = {f: (SNAP / f).read_text().splitlines() for f in FILES}


def R(path, s, e, sym):
    return {'path': path, 'start_line': s, 'end_line': e, 'symbol': sym}


S_MATRIX = '模块职责矩阵'
S_ORDER = '一条 443 报文的执行顺序'
S_CERT = '证书数据的两条通路与会话状态'
S_OBS = '可观测边界：能拿到什么'
S_EDGE = '边界、异常与常见误解'
S_DOC = '与已有说明的差异'
S_READ = '阅读顺序与自查问题'
S_CORE = '核心对象和术语'

FACTS = [
    ('TLS-001', S_CORE,
     '五个 TLS 相关插件在各自文件末尾准备静态 ops 表并由 extern "C" 的 engine_get_ops 导出；ops 常量分别是 '
     'HTTPS_Identifier(protocol_id 2, priority 20)、TLS_Parser(3, 30)、Certificate_Extractor(4, 40)、'
     'JA3_Fingerprint(5, 50)、名为 TLS 的 TLS 基础设施插件(443, 180)；五者的 get_stats 与 reset_stats 均为空。',
     [R('plugin/plugins/https_plugin.c', 86, 96, '.priority'),
      R('plugin/plugins/tls_plugin.c', 184, 194, '.priority'),
      R('plugin/plugins/cert_plugin.c', 119, 129, '.priority'),
      R('plugin/plugins/ja3_plugin.c', 84, 94, '.priority'),
      R('plugin/plugins/engine_tls_infra.cpp', 808, 818, '.priority')],
     '五处 ops 初始化列表逐项读；名为 TLS 的 ops 在 engine_tls_infra.cpp，不在 tls_plugin.c。',
     '既有文档按"模块名=文件名片段"理解插件归属，容易把 tls_plugin.c 与 engine_tls_infra.cpp 混为一谈。'),
    ('TLS-002', S_MATRIX,
     '装载入口是 main.c 的固定插件列表（按 access 存在性判断后 dlopen），列表顺序为 https、tls、cert、ja3、dns……；'
     'DPDK 流水线另为每个 worker 的注册中心各加载一份。但链上实际执行顺序由 insert_to_chain 按 priority 升序决定，'
     '与列表顺序无关；两者不一致时以 priority 为准。',
     [R('main.c', 2384, 2436, 'engine_registry_load_plugin'),
      R('main.c', 1618, 1626, 'load_plugins_to_registry'),
      R('plugin/engine_registry.c', 35, 51, 'insert_to_chain')],
     '列表、每 worker 加载点与插链实现三处对照；未运行加载。',
     '首批 PL-006 只写"priority 升序、同值后插"；本篇把"列表顺序≠执行顺序"明确成可核对结论。'),
    ('TLS-003', S_MATRIX,
     '五个 can_handle 分成两类判据：HTTPS_Identifier、TLS_Parser、Certificate_Extractor、JA3_Fingerprint 不看端口，'
     '只看 payload 首字节 0x16 与握手类型字节；名为 TLS 的 TLS 基础设施插件要求 TCP 且有负载，端口为 443/853 即放行，'
     '否则要求当前包看起来像 TLS 记录头（首字节 0x14/0x15/0x16/0x17 且第二字节 0x03）。',
     [R('plugin/plugins/tls_plugin.c', 40, 48, 'payload[0] != 0x16'),
      R('plugin/plugins/ja3_plugin.c', 22, 27, 'payload[5] == 0x01'),
      R('plugin/plugins/cert_plugin.c', 29, 34, 'payload[5] == 0x0b'),
      R('plugin/plugins/engine_tls_infra.cpp', 512, 534, 'pkt->src_port == 853')],
     '四个 can_handle 函数体逐行读；JA3 的两个长度检查重复但不改变结论。',
     '通识常说"TLS 检测看 443 端口"，本版本四个插件的识别其实与端口无关。'),
    ('TLS-004', S_MATRIX,
     '本簇五个插件都不返回 ENGINE_HANDLED：TLS_Parser、HTTPS_Identifier、JA3、Certificate_Extractor 一律返回 '
     'ENGINE_CONTINUE，TLS 基础设施插件在不满足前置时返回 ENGINE_SKIP、其余返回 ENGINE_CONTINUE。'
     '因此 ClientHello 会被这三个看记录特征的插件依次处理，而不是第一个处理完就截断链路。',
     [R('plugin/plugins/tls_plugin.c', 141, 141, 'ENGINE_CONTINUE'),
      R('plugin/plugins/https_plugin.c', 76, 76, 'ENGINE_CONTINUE'),
      R('plugin/plugins/ja3_plugin.c', 74, 74, 'ENGINE_CONTINUE'),
      R('plugin/plugins/cert_plugin.c', 109, 109, 'ENGINE_CONTINUE'),
      R('plugin/plugins/engine_tls_infra.cpp', 536, 539, 'ENGINE_SKIP')],
     '逐文件搜 return ENGINE_ 常量并核对所在分支；未在返回 HANDLED 的插件中找到本簇成员。',
     '首批 PL-007 给出调度器侧语义；本篇补出"本簇无人吃包"这一实际后果。'),
    ('TLS-005', S_ORDER,
     'TLS_Parser 命中后自己给 ctx->stats.packets_matched 加一的位置在 TLS 基础设施插件里（命中并发送事件时），'
     '而 TLS_Parser、HTTPS_Identifier、JA3、Certificate 只增加各自私有数据里的计数（tls_count/https_count/ja3_count/cert_count），'
     '这些私有计数没有任何 get_stats 导出路径，因此不会出现在注册中心统计里。',
     [R('plugin/plugins/engine_tls_infra.cpp', 726, 726, 'packets_matched'),
      R('plugin/plugins/tls_plugin.c', 106, 106, 'tls_count++'),
      R('plugin/plugins/https_plugin.c', 39, 39, 'https_count++'),
      R('plugin/plugins/ja3_plugin.c', 37, 37, 'ja3_count++'),
      R('plugin/plugins/cert_plugin.c', 44, 44, 'cert_count++')],
     '私有计数的自增点与 ops 的 get_stats=NULL 合读；未验证其他上报路径是否存在。',
     '容易把"插件内部有计数器"当成"计数器会被看到"；本篇区分注册中心统计与私有统计。'),
    ('TLS-006', S_EDGE,
     'HTTPS 判定函数 is_https_traffic 里端口条件被自身短路：返回式是 (is_https_port && is_tls) || is_tls，'
     '只要 is_tls 为真即为 HTTPS，dst_port 是否 443 不改变结果。该函数另要求记录版本在 0x0300/0x0301/0x0302/0x0303/0x0304 之内、'
     '记录长度非 0 且不超过 16384。',
     [R('plugin/plugins/https_identifier.c', 47, 64, 'is_https_port'),
      R('plugin/plugins/https_identifier.c', 17, 45, 'record_length')],
     '合取式化简后只剩 is_tls；未运行验证其他分支。',
     '代码形状像是"端口或特征"两条路，实际端口不参与判定；不写这条就会被读成 443 门控。'),
    ('TLS-007', S_OBS,
     '两个 ClientHello 解析器都要求被捕获的负载已含完整数据：tls_parser.c 先要求 length >= 5 + record_length，'
     '再要求握手体长度 4 + hello_length 也在内；ja3_fingerprint.c 也要求 4 + hello_length 在内（但不再校验记录层声明长度）。'
     '因此 ClientHello 跨 TCP 段或被截断时，TLS_Parser、HTTPS_Identifier 与 JA3 三条通路都会解析失败、不产生事件。',
     [R('plugin/plugins/tls_parser.c', 206, 225, 'record_length'),
      R('plugin/plugins/ja3_fingerprint.c', 98, 101, 'hello_length'),
      R('plugin/plugins/tls_plugin.c', 102, 105, 'parse_tls_client_hello')],
     '两处长度门控与三个调用点合读；未构造分片报文验证，只陈述代码条件。',
     '通识常假设 TLS 解析自带跨段重组；本版本这三条通路没有，跨段能力只在证书与 TLS 基础设施两处（见 TLS-009、TLS-018）。'),
    ('TLS-008', S_CERT,
     'main.c 在单线程回调里为证书另开一条通路：仅当 protocol 为 6、payload 非空、src_port 为 443 且两个全局句柄非空时进入；'
     '它把包追加进会话上的 tls_stream_buf，扫描其中完整的 0x16 记录，仅对握手类型 0x0b 调用 cert_engine_process_stream。'
     '缓冲区 TLS_STREAM_BUF_SIZE 为 65536，追加会溢出时把 len 归零重新开始，且当前包非 0x16 就跳过。',
     [R('main.c', 1383, 1386, 'parsed.src_port == 443'),
      R('main.c', 56, 63, 'TLS_STREAM_BUF_SIZE'),
      R('main.c', 1407, 1413, 'sbuf->len = 0'),
      R('main.c', 1427, 1431, 'handshake_type == 0x0b')],
     '条件、缓冲上限与溢出重置三处定位；未运行验证溢出后的实际丢包量。',
     '既有文档只说"证书提取插件"，未说明这条不经注册中心的第二条入口。'),
    ('TLS-009', S_CERT,
     '该旁路只在单线程路径可达：g_cert_engine_ctx 与 g_cert_process_stream 的唯一赋值点在 init_engine()，'
     '而 init_engine() 只在 main() 的非 DPDK 分支被调用；旁路调用点也都在 packet_handler 内，worker 循环没有等价代码。'
     '这不同于"证书插件在 worker 里不运行"——engine_cert.so 仍会被逐个加载进 worker 的注册中心并按链调度。',
     [R('main.c', 2441, 2466, 'g_cert_engine_ctx ='),
      R('main.c', 3149, 3157, 'init_engine'),
      
      R('main.c', 1618, 1626, 'load_plugins_to_registry')],
     '赋值点、调用点与两条初始化分支逐处核对；"引擎被调度"与"旁路执行"分别陈述。',
     '任务书把这条列为待闭合线索；此处按代码路径闭合，且未写成"worker 模式不产出证书"。'),
    ('TLS-010', S_CERT,
     'cert_engine_process（链上入口）与 cert_engine_process_stream（旁路入口）共用 cert_engine_extract，'
     '只以 source_label 区分 "Direct" 与 "Reassembled"。链上入口忽略提取结果恒返 ENGINE_CONTINUE，'
     '注释说明提取失败通常是证书跨段包、属正常情况；旁路入口则把提取结果原样返回。',
     [R('plugin/plugins/cert_plugin.c', 106, 110, 'ENGINE_CONTINUE'),
      R('plugin/plugins/cert_plugin.c', 36, 41, 'ENGINE_ERROR'),
      R('plugin/plugins/cert_plugin.c', 135, 137, 'cert_engine_process_stream')],
     '两个入口与共用函数三处对照；未验证失败率。',
     '首批 PL-007 说返回值决定链走向；本篇补出"该插件的失败不上报为 ERROR"这一实际选择。'),
    ('TLS-011', S_OBS,
     '证书提取只处理链上的第一张证书：跳过 3 字节证书链总长后，只读一个 3 字节长度和这一段 DER，'
     '其余链上证书记录不在本函数输出。字段来自 OpenSSL d2i_X509 解析出的 issuer、subject 与 notBefore/notAfter；'
     '解析不出 X509 时这些字段保持为空。',
     [R('plugin/plugins/cert_extractor.c', 113, 134, 'cert_length'),
      R('plugin/plugins/cert_extractor.c', 140, 167, 'd2i_X509')],
     '指针推进与三次长度校验逐段读；未构造多证书链报文验证。',
     '不能把"提取到证书"读成"提取到证书链"。'),
    ('TLS-012', S_CERT,
     '一次证书提取写三个地方：DER 落盘到相对路径 capture_logs/certificates（目录在 init 时 mkdir 创建）、'
     '关键字段与文件路径经 mongo_insert_certificate 同步写 MongoDB、再写一条 engine_log_event 决策事件。'
     '该通路不经 ch_event 事件环；MongoDB 写入在模块未就绪或集合为空时直接返回（静默不写），并且持有一把进程级互斥锁。',
     [R('plugin/plugins/cert_plugin.c', 17, 26, 'mkdir'),
      R('plugin/plugins/cert_plugin.c', 71, 79, 'mongo_insert_certificate'),
      R('plugin/plugins/cert_plugin.c', 81, 100, 'engine_log_event')],
     '三个写出点与 init 的目录副作用一并定位；未运行、未测写入延迟或失败率。',
     '与 TLS/HTTPS/JA3 走异步入环不同，本篇明确区分两种上报通路。'),
    ('TLS-013', S_EDGE,
     '旁路构造的临时 parsed_packet_t 只填 payload、payload_len、src_ip、src_port、dst_ip、dst_port、protocol 七个字段，'
     '未填 ip_version 与 src_ip6/dst_ip6；而 cert_engine_extract 靠 ip_version 决定用 AF_INET6 还是 AF_INET 格式化地址。'
     '因此经这条通路提交的 IPv6 会话证书，其地址字段会落到 IPv4 分支、取全零的 src_ip/dst_ip。'
     '是否属有意取舍未在代码中找到说明，列入待确认。',
     [R('main.c', 1428, 1437, 'cert_pkt'),
      R('plugin/plugins/cert_plugin.c', 58, 65, 'pkt->ip_version == 6')],
     '初始化列表字段集合与下游分支条件对照得出；未运行任何 IPv6 报文，未定性为缺陷。',
     'cert_plugin.c 的注释恰好警告过 IPv6 会写成 0.0.0.0；本篇指出这条通路自己会绕过该防护。'),
    ('TLS-014', S_CORE,
     'TLS_Parser 不用会话的 ext_data 单值槽，而是把自定义的 tls_session_state_t 放进候选数组的 EXT_FIELD_TLS 项'
     '（session_set_candidate_data），槽位分配失败不影响主流程；它因此不参与 ext_locked 的锁定仲裁。'
     'ServerHello 分支依赖该缓存：命中且未发过则沿用客户端视角补一条记录，没有缓存时翻转 src/dst 并以空 SNI 发一条。',
     [R('plugin/plugins/tls_plugin.c', 115, 134, 'session_set_candidate_data'),
      R('plugin/plugins/tls_plugin.c', 155, 169, 'have_client_hello'),
      R('session/session.h', 129, 135, 'ext_locked')],
     '写入点、读取点与会话结构字段三处对照；候选数组容量上限的影响见第二批 SF-008。',
     '第二批 SF-007/SF-008 讲槽位规则；本篇给出一个只用候选槽的真实插件例子。'),
    ('TLS-015', S_OBS,
     'TLS 事件里的套件字段只有一个值：emit_tls_event 把 tls_cipher_suite_name(首个客户端首选套件) 或服务端选定套件写入 '
     'cipher_suites，并把 cipher_suites_count 恒置 1，所以该字段不是客户端全部套件列表；'
     '未收录在名称表里的 ID 会返回形如 CIPHER_0x 加十六进制的占位串。',
     [R('plugin/plugins/tls_plugin.c', 50, 69, 'cipher_suites_count'),
      R('plugin/plugins/tls_parser.c', 477, 492, 'CIPHER_0x')],
     '写出点与名称查询函数尾部对照；占位串的具体格式按源码字面描述。',
     'JA3 事件里同名字段是完整套件串，二者口径不同，不能互相替代。'),
    ('TLS-016', S_OBS,
     '解析结果有四处硬上限会静默截断：密码套件最多保留 128 个（count 记的是截断后的数量）、扩展类型最多记录 64 个、'
     'SNI 缓冲 256 字节（复制长度上限 255）。扩展遍历与 SNI 提取共用同一趟循环，且循环在扩展数达到 64 时停止，'
     '因此 server_name 扩展排在第 64 个之后时取不到 SNI。',
     [R('plugin/plugins/tls_parser.h', 13, 15, 'MAX_EXTENSIONS'),
      R('plugin/plugins/tls_parser.c', 256, 265, 'MAX_CIPHER_SUITES'),
      R('plugin/plugins/tls_parser.c', 296, 296, 'MAX_EXTENSIONS'),
      R('plugin/plugins/tls_parser.c', 317, 335, 'TLS_EXTENSION_SNI')],
     '上限常量、循环条件与复制长度三处核对；未构造超限 ClientHello 验证。',
     '"解析成功"不等于"字段完整"；is_valid 为 1 时 SNI 仍可能为空。'),
    ('TLS-017', S_OBS,
     '版本字段有专门的取值规则：ClientHello 带 supported_versions 扩展时取其中最高的非 GREASE 版本，'
     '否则回退到记录层 legacy 版本；ServerHello 同理从 supported_versions 读取真实协商版本。'
     'JA3 串则由版本号、套件、扩展、曲线与点格式的原始顺序拼接后取 MD5，源码中未看到 GREASE 值过滤。',
     [R('plugin/plugins/tls_parser.h', 19, 23, 'supported_versions'),
      R('plugin/plugins/tls_parser.c', 307, 315, 'tls_pick_client_supported_version'),
      R('plugin/plugins/ja3_fingerprint.c', 223, 233, 'ja3_string')],
     '版本选取与 JA3 拼接两处对照；GREASE 未过滤是"未在该文件找到处理"的结论，不是性能或正确性判断。',
     'TLS 1.3 中间盒兼容会让记录层版本停在 0x0303；本篇说明字段取的是哪个版本，避免读成实际协商版本。'),
    ('TLS-018', S_OBS,
     'TLS 基础设施插件只可能报出 ECH 与 DoT 两类：命中条件是 ClientHello 带 ECH 扩展，或端口为 853、'
     '或 ALPN 中出现 dot；未命中时不发任何事件。事件结构里的 is_doh 与 is_dns_tunnel 被硬编码为 0，'
     '所以字段存在不代表这两类已被检测。同一会话里每种类型只发一次（由扩展里的 emitted 标志去重）。',
     [R('plugin/plugins/engine_tls_infra.cpp', 476, 498, 'hit_dot'),
      R('plugin/plugins/engine_tls_infra.cpp', 661, 666, 'detail != NULL'),
      R('plugin/plugins/engine_tls_infra.cpp', 698, 701, 'is_doh'),
      R('session/session.h', 644, 647, 'dot_event_emitted')],
     '命中条件、发送门控、硬编码字段与去重标志四处核对；ECH 命中时 detail 会先于 DoT 返回，这一点按源码顺序描述。',
     'session.h 里 tls_infra_ext_data 有 is_doh/is_dns_tunnel 字段，容易被读成"支持 DoH 检测"。'),
    ('TLS-019', S_MATRIX,
     '本版本一个进程里不止一份 TLS 解析实现：TLS 基础设施插件自带一套 ClientHello 解析与按方向的 TCP 重组，'
     '它的构建走通用规则、只链自己这一个 .cpp 与事件对象，不链 tls_parser.c；其重组缓冲每方向 32768 字节、'
     '单包最多取 4 条记录、30 秒超时，流表按 60 秒修剪、空闲 300 秒淘汰、条目硬上限 20000。'
     '另一方面，tls_parser.c 被 engine_https.so、engine_tls.so、engine_ja3.so 各静态编入一份，主程序对象列表里没有它，'
     '所以该文件里的文件级静态数据（如套件名称表）在三个插件实例中各有一份、互不共享。',
     [R('plugin/plugins/engine_tls_infra.cpp', 35, 41, 'TLS_REASM_MAX_BUF'),
      R('plugin/plugins/engine_tls_infra.cpp', 601, 610, 'TLS_REASM_MAX_RECORDS_PER_PKT'),
      R('plugin/plugins/Makefile', 210, 211, '%.so: %.cpp'),
      R('plugin/plugins/Makefile', 120, 137, 'tls_parser.c'),
      R('Makefile', 66, 66, 'OBJS')],
     '两条构建规则、通用 .cpp 规则、常量与取记录循环五处对照；未执行 make，也未测这些上限的实际效果。',
     '与"全项目共用一个 TLS 解析器"的直觉相反：既是两套解析实现，同一份源码又被复制进三个 .so。"改它影响哪些插件"与"状态是否共享"要分开回答。'),
    ('TLS-020', S_EDGE,
     'JA3 的解析在跳过 Session ID 时只检查剩余长度是否够 1 字节读取该字段本身，未再检查 1 + session_id_length 是否仍在'
     '声明的 ClientHello 长度内；tls_parser.c 的同一位置有这道检查。两者的边界处理不一致，属代码事实差异。'
     '该差异是否构成越界读、影响哪些输入，本批未分析，登记为需单独代码评审的问题。',
     [R('plugin/plugins/ja3_fingerprint.c', 116, 120, 'session_id_length'),
      R('plugin/plugins/tls_parser.c', 240, 245, 'session_id_length')],
     '两处同名变量的检查条件逐行对照。本条只到"检查集合不同"为止。',
     '不做安全结论、不写"存在漏洞"：判定可达性与影响需要单独的代码审计批次，不属于资料建设。'),
    ('TLS-021', S_DOC,
     '与既有说明的差异（只登记，不修订）：docs/system_design.md 的插件优先级表整表错位——它记 HTTP 100 / HTTPS 110 / '
     'TLS 120 / TLS Infra 130 / DNS 50，实现分别是 HTTPS_Identifier 20 / TLS_Parser 30 / Certificate 40 / JA3 50 / '
     'DNS 120 / TLS Infra 180；docs/codebase_reference.md 提到 engine_http.c，而 plugin/plugins/Makefile 的插件目标里没有对应项。',
     [R('docs/system_design.md', 1474, 1479, 'TLS Infra'),
      R('plugin/plugins/Makefile', 109, 111, 'engine_http.so')],
     '文档表格五行与五个 ops 常量、两条构建规则逐项比对；未修订文档本身。',
     '整表错位意味着引用该表的每一行都要重新核对，不能挑其中看起来对的几行；另外它把 HTTP 源文件写成 engine_http.c + http_plugin.c，而该 .so 的源列表里没有 engine_http.c。'),
    ('TLS-022', S_READ,
     '"某类 TLS 报文为什么没出 SNI／证书／JA3"的排查顺序：先确认链上是否轮到该插件（priority 与前置是否返回 HANDLED）；'
     '再看 can_handle 类别（端口门控还是记录特征）；再看捕获长度是否满足完整记录与完整 ClientHello 体的要求；'
     '再看上限截断；最后确认看的是哪条通路——证书有链上与旁路两条入口，且旁路只在单线程路径。',
     [R('plugin/plugins/tls_plugin.c', 71, 74, 'ENGINE_CONTINUE'),
      R('plugin/plugins/engine_tls_infra.cpp', 546, 550, 'ENGINE_SKIP')],
     '把本篇各事实落到一条可执行顺序上；未新增未验证判据。',
     '既有资料没有一条按返回值与解析条件定位的 TLS 排查链。'),
]

docs = [{
    'document_id': str(uuid.uuid4()), 'revision': 1,
    'topic': 'TLS、HTTPS、SNI 与证书：模块职责和可观测边界',
    'document_path': 'drafts/tls_module_boundaries.md',
    'evidence_path': 'evidence/tls_module_boundaries.md',
    'status': '待人工审核', 'source_commit': C, 'facts': []}]

bad = []
for fid, sec, claim, refs, reason, diff in FACTS:
    for r in refs:
        lines = src.get(r['path'])
        if lines is None:
            bad.append((fid, 'UNKNOWN PATH', r['path'])); continue
        s0, e0 = r['start_line'], r['end_line']
        if not 1 <= s0 <= e0 <= len(lines):
            bad.append((fid, 'RANGE', r['path'], s0, e0, len(lines))); continue
        if r['symbol'] not in '\n'.join(lines[s0 - 1:e0]):
            hits = [i + 1 for i, ln in enumerate(lines) if r['symbol'] in ln][:4]
            bad.append((fid, 'SYMBOL MISS', r['path'], s0, e0, r['symbol'], 'found at', hits))
    docs[0]['facts'].append({'id': fid, 'section': sec, 'claim': claim, 'source_commit': C,
                             'references': refs, 'reasoning': reason, 'status': ST, 'difference': diff})

manifest = {
    'batch': 'zh_batch03', 'revision': 1, 'status': '待人工审核',
    'source_repo': '/Users/fsy/code/dpdk', 'assistant_repo': '/Users/fsy/code/dpdk/ai_chat_assistant',
    'source_commit': C, 'assistant_commit': 'a8d0fbec7667a0580a8c6b7fde838d1b7aa0012d',
    'inherited_source_commit_reason': ('沿用前两批的主项目提交以保持系列一致；开工时主仓库 HEAD 恰为该提交，'
                                       '22 个来源文件工作树字节与固定提交全部相同，TLS 簇无变化。'),
    'task_book': '../tasks/zh_batch03_taskbook.md',
    'source_files': [{'path': f, 'snapshot': f'snapshots/dpdk/{C}/{f}', 'lines': len(src[f])} for f in FILES],
    'documents': docs,
    'reviewer': None, 'model_identifier': '未提供精确运行标识', 'token_usage': None, 'cost': None,
    'usage_note': '未调用应用 LLM/embedding；本会话用量不可得，不计作零。',
    'index_status': '未入库',
    'verification': ('静态检查与编写者自审；首批独立静态审核见 zh_pilot/reviews/independent_review_20260918.md，'
                     '本批尚无独立审核。'),
}
if bad:
    for b in bad: print('FAIL', b)
    raise SystemExit(1)
(BASE / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
print('wrote manifest:', len(FACTS), 'facts')
