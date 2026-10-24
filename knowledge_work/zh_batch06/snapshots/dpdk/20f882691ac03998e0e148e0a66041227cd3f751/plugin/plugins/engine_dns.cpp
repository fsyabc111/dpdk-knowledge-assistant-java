/**
 * DNS 协议解析引擎 - 插件化实现
 * 设计说明：
 * 1. 负责识别并处理基于 UDP 53 端口的标准 DNS 请求与响应流量。
 * 2. 通过域名解析函数完成 DNS Question/Answer 区域中的名称还原，支持压缩指针递归解析与基础合法性校验。
 * 3. 通过“预解析 -> 状态落地”的两阶段流程，先校验报文有效性，再更新会话层与插件缓存，降低伪记录与空字段写入风险。
 * 4. 会话层保存当前 DNS 会话的标准化摘要，用于主流程、统计模块及后续协议关联读取。
 * 5. 插件缓存保存 Client_IP 视角下的 Domain -> Records 映射，用于后续域名/IP 关联分析与调试输出。
 */

#include "../engine.h"
#include "../engine_log.h"
#include "domain_intel.h"
#include "../../session/session.h"
#include "ch_storage.h"
#include "ch_event.h"
#include <iostream>
#include <map>
#include <set>
#include <string>
#include <vector>
#include <arpa/inet.h>
#include <cstring>
#include <ctime>
#include <cctype>
#include <cstdio>
#include <inttypes.h>
#include <unistd.h>
#include <mutex>
#include <pthread.h>
#include "../../log.h"

using namespace std;

/* 日志级别（从主程序导出，0=静默，1=详细） */
extern "C" int g_log_level __attribute__((weak));
static inline int dns_log_level() { return (&g_log_level ? g_log_level : 1); }

static void dns_build_session_id(const char *client_ip,
                                 uint16_t client_port,
                                 const char *resolver_ip,
                                 uint16_t resolver_port,
                                 uint8_t proto,
                                 char *sid,
                                 size_t sid_len)
{
    if (!sid || sid_len == 0) return;
    snprintf(sid, sid_len, "%s:%u-%s:%u-%u",
             (client_ip && client_ip[0]) ? client_ip : "0.0.0.0", (unsigned)client_port,
             (resolver_ip && resolver_ip[0]) ? resolver_ip : "0.0.0.0", (unsigned)resolver_port,
             (unsigned)proto);
}

/* ========== 数据结构定义 ========== */

#define DNS_FLAG_RESPONSE 0x8000
#define DNS_FLAG_OPCODE(FLGS) ((FLGS >> 11) & 0x0F)
#define DNS_FLAG_RCODE(FLGS) ((FLGS) & 0x000F)   // DNS RCODE 提取宏
#define ILLEGALCODE "解析错误"
#define CACHE_TIMEOUT 30 // 主动清理阈值：30秒
#define DNS_CACHE_MAX_RECORDS 20000
#define DNS_CACHE_MAX_RECORDS_PER_CLIENT 512
#ifndef DNS_IP_STR_LEN
#define DNS_IP_STR_LEN 46
#endif

struct DnsRecord {
    string domain;
    vector<string> answers;
    time_t last_update;
    uint64_t response_count;
    uint8_t last_is_success;
    uint8_t last_rcode;
    uint16_t last_qtype;
};

// 引擎私有数据：维护全局缓存与统计信息
typedef struct dns_engine_data {
    // Key: Client_IP -> { Domain -> Record }
    map<string, map<string, DnsRecord>> cache;

    // 缓存锁：保护 cache / 统计 / 清理逻辑的并发访问
    mutex cache_mutex;

    uint64_t total_queries;
    uint64_t total_answers;
    size_t cache_record_count;
    time_t last_cleanup_time; // 记录上次主动扫描的时间
} dns_engine_data_t;

// DNS query 解析结果
typedef struct dns_query_info {
    string qname;
    uint16_t qtype;
    uint16_t qclass;
    uint16_t txid;
    uint16_t flags;
    uint16_t ancount;
    uint16_t nscount;
    uint16_t arcount;
    uint16_t rcode;
} dns_query_info_t;

// DNS response 解析结果（复用 query 基础字段）
typedef struct dns_response_info {
    dns_query_info_t query;
    uint8_t answer_count;
} dns_response_info_t;

/* ========== 辅助解析函数 ========== */

/**
 * UTF-8 有效性检查函数
 */
static bool is_valid_utf8(const string &str) {
    if (str == ".") return true;
    int i = 0;
    int len = str.length();
    while (i < len) {
        unsigned char c = str[i];
        if (c <= 0x7F) i++;
        else if ((c & 0xE0) == 0xC0) {
            if (i + 1 >= len || (str[i+1] & 0xC0) != 0x80) return false;
            i += 2;
        } else if ((c & 0xF0) == 0xE0) {
            if (i + 2 >= len || (str[i+1] & 0xC0) != 0x80 || (str[i+2] & 0xC0) != 0x80) return false;
            i += 3;
        } else if ((c & 0xF8) == 0xF0) {
            if (i + 3 >= len || (str[i+1] & 0xC0) != 0x80 || (str[i+2] & 0xC0) != 0x80 || (str[i+3] & 0xC0) != 0x80) return false;
            i += 4;
        } else return false;
    }
    return true;
}

/**
 * 主动清理过期缓存逻辑
 */
static void dns_cache_cleanup(dns_engine_data_t *data) {
    if (!data) return;

    time_t now = time(nullptr);
    lock_guard<mutex> lock(data->cache_mutex);

    // 每 5 秒扫描一次；如果缓存已经超出硬上限，则立即收缩
    if (now - data->last_cleanup_time < 5 && data->cache_record_count <= DNS_CACHE_MAX_RECORDS) return;

    auto erase_domain = [&](map<string, map<string, DnsRecord>>::iterator client_it,
                            map<string, DnsRecord>::iterator domain_it) {
        if (client_it == data->cache.end() || domain_it == client_it->second.end()) return;
        if (data->cache_record_count > 0) {
            --data->cache_record_count;
        }
        client_it->second.erase(domain_it);
    };

    for (auto it_client = data->cache.begin(); it_client != data->cache.end(); ) {
        auto &domain_map = it_client->second;
        for (auto it_domain = domain_map.begin(); it_domain != domain_map.end(); ) {
            if (now - it_domain->second.last_update > CACHE_TIMEOUT) {
                auto doomed = it_domain++;
                erase_domain(it_client, doomed);
            } else {
                ++it_domain;
            }
        }

        while (!domain_map.empty() && domain_map.size() > DNS_CACHE_MAX_RECORDS_PER_CLIENT) {
            auto oldest_domain = domain_map.end();
            time_t oldest_ts = 0;
            bool found = false;
            for (auto it_domain = domain_map.begin(); it_domain != domain_map.end(); ++it_domain) {
                if (!found || it_domain->second.last_update < oldest_ts) {
                    found = true;
                    oldest_ts = it_domain->second.last_update;
                    oldest_domain = it_domain;
                }
            }
            if (!found) break;
            erase_domain(it_client, oldest_domain);
        }

        if (domain_map.empty()) {
            it_client = data->cache.erase(it_client);
        } else {
            ++it_client;
        }
    }

    while (data->cache_record_count > DNS_CACHE_MAX_RECORDS && !data->cache.empty()) {
        auto oldest_client = data->cache.end();
        map<string, DnsRecord>::iterator oldest_domain;
        time_t oldest_ts = 0;
        bool found = false;

        for (auto it_client = data->cache.begin(); it_client != data->cache.end(); ++it_client) {
            for (auto it_domain = it_client->second.begin(); it_domain != it_client->second.end(); ++it_domain) {
                if (!found || it_domain->second.last_update < oldest_ts) {
                    found = true;
                    oldest_ts = it_domain->second.last_update;
                    oldest_client = it_client;
                    oldest_domain = it_domain;
                }
            }
        }

        if (!found) break;
        erase_domain(oldest_client, oldest_domain);
        if (oldest_client->second.empty()) {
            data->cache.erase(oldest_client);
        }
    }

    data->last_cleanup_time = now;
}

static DnsRecord& dns_cache_get_record_locked(dns_engine_data_t *data,
                                              const char *client_ip,
                                              const string &domain,
                                              bool *is_new_record) {
    map<string, DnsRecord> &domain_map = data->cache[string(client_ip)];
    auto it = domain_map.find(domain);
    bool created = false;

    if (it == domain_map.end()) {
        it = domain_map.emplace(domain, DnsRecord{}).first;
        created = true;
        data->cache_record_count++;
    }

    if (is_new_record) {
        *is_new_record = created;
    }

    return it->second;
}

static bool dns_ext_same_pending_request(const dns_ext_data_t *ext,
                                         const dns_query_info_t &info) {
    if (!ext || !ext->pending_valid) return false;
    if (ext->pending_txid != info.txid) return false;
    if (ext->pending_qtype != info.qtype) return false;
    return strncmp(ext->pending_qname, info.qname.c_str(), sizeof(ext->pending_qname)) == 0;
}

static void dns_ext_mark_pending_request(dns_ext_data_t *ext,
                                         const dns_query_info_t &info,
                                         const char *client_ip,
                                         const char *resolver_ip,
                                         time_t now) {
    if (!ext) return;

    ext->confirmed = 1;
    ext->is_response = 0;
    ext->has_query = 1;
    ext->counted = 0;
    ext->pending_valid = 1;
    ext->pending_txid = info.txid;
    ext->pending_qtype = info.qtype;
    strncpy(ext->pending_qname, info.qname.c_str(), sizeof(ext->pending_qname) - 1);
    ext->pending_qname[sizeof(ext->pending_qname) - 1] = '\0';

    ext->txid = info.txid;
    ext->flags = info.flags;
    ext->ancount = info.ancount;
    ext->nscount = info.nscount;
    ext->arcount = info.arcount;
    ext->rcode = info.rcode;
    ext->qtype = info.qtype;
    ext->qclass = info.qclass;
    ext->last_update_ts = (uint64_t)now;

    strncpy(ext->client_ip, client_ip, sizeof(ext->client_ip) - 1);
    ext->client_ip[sizeof(ext->client_ip) - 1] = '\0';
    strncpy(ext->resolver_ip, resolver_ip, sizeof(ext->resolver_ip) - 1);
    ext->resolver_ip[sizeof(ext->resolver_ip) - 1] = '\0';
    strncpy(ext->qname, info.qname.c_str(), sizeof(ext->qname) - 1);
    ext->qname[sizeof(ext->qname) - 1] = '\0';
}

/**
 * 核心逻辑：从 DNS 包解析域名
 * entry:
 * packet, dns包
 * packetLen, dns包大小
 * offset, 偏移值
 * name, 域名返回到此变量
 * depth, 0
 * return:
 * >=0: 返回新的偏移值, 域名在name中
 * -1: 错误
 * -2: 非utf-8编码
 */
static int dns_parse_name(const uint8_t *packet, int packetLen, int offset, string &name, int depth) {
    if (depth > 10 || offset >= packetLen) {    // 防止指针循环，设置最大递归深度
        return -1;
    }
    bool firstLabel = true;       // 记录是否是第一个标签，用于控制点号分隔符

    // 循环解析域名
    while (offset < packetLen) {
        // 三种情况：0--结束; >=0xc0--指针; 0<0xc0--普通标签
        uint8_t byte = packet[offset];
        if (byte == 0) {    // 域名结束
            offset++;
            break;
        }
        // 处理指针
        else if ((byte & 0xC0) == 0xC0) {
            if (offset + 1 >= packetLen) {  // 检查是否越界
                return -1;
            }
            // 计算指针偏移量，去掉高2位后取14位
            int pointerOffset = ((byte & 0x3F) << 8) | packet[offset + 1];
            if (pointerOffset >= packetLen) {     // 验证指针偏移量有效性
                return -1;
            }
            // 递归解析指针指向的域名
            string pointedName;
            int result = dns_parse_name(packet, packetLen, pointerOffset, pointedName, depth + 1);
            if (result == -1) {
                return -1;
            }
            if (result == -2) {
                return -2; // 传递UTF-8错误
            }
            if (!firstLabel) {    // 如果不是第一个标签，先添加分隔符
                name += ".";
            }
            name += pointedName;
            offset += 2;          // 指针占2字节，更新偏移量
            break;                // 指针后通常是域名结束
        }
        // 处理普通标签
        else {
            int labelLen = byte;

            if (offset + 1 + labelLen > packetLen || labelLen > 63) {    // 检查标签内容是否越界
                return -1;
            }
            // DNS标签允许的字符：字母、数字、连字符（RFC 1035）
            // 对于国际化域名，标签可能包含Punycode编码（以"xn--"开头）
            bool labelValid = true;
            for (int i = 1; i <= labelLen; i++) {
                uint8_t ch = packet[offset + i];
                // 基本DNS字符集检查
                if (!((ch >= 'a' && ch <= 'z') ||
                      (ch >= 'A' && ch <= 'Z') ||
                      (ch >= '0' && ch <= '9') ||
                      ch == '-')) {
                    // 如果是国际化域名标签（以xn--开头），放宽字符限制
                    if (i <= 4 && labelLen >= 4) {
                        // 检查是否以"xn--"开头
                        if (offset + 4 <= packetLen) {
                            if (packet[offset + 1] == 'x' && packet[offset + 2] == 'n' &&
                                packet[offset + 3] == '-' && packet[offset + 4] == '-') {
                                // Punycode编码允许更多字符
                                if (ch >= 33 && ch <= 126) { // 可打印ASCII字符
                                    continue;
                                }
                            }
                        }
                    }
                    labelValid = false;
                    break;
                }
            }

            if (!labelValid) {
                return -1;          // 无效字符
            }

            if (!firstLabel) {      // 如果不是第一个标签，先添加分隔符
                name += ".";
            }
            name.append((const char*)(packet + offset + 1), labelLen);    // 将标签内容追加到 name
            offset += 1 + labelLen;     // 更新偏移量：1字节长度 + 标签内容
            firstLabel = false;           // 已经不是第一标签
        }
    }
    if (offset > packetLen) {             // 最终检查偏移量是否有效
        return -1;
    }
    if (name.length() > 253) {
        return -1;                          // 长度应小于等于253
    }
    // 处理根域名特殊情况
    if (name.empty()) {
        // 根域名表示为单个点
        name = ".";
    }
    bool has_dot = false;
    for (size_t i = 0; i < name.length(); i++) {    // 小写转换和字符检查
        unsigned char ch = (unsigned char)name[i];
        if (ch < 32 || ch == 127)
            return -1;                     // 含有不可显示字符
        if (ch == '.') has_dot = true;
        name[i] = (char)tolower(ch);
    }

    // 根域名只有一个点，也是有效的
    if (!has_dot && name != ".") {
        return -1;                          // 非根域名应至少有一个"."
    }

    // 检查UTF-8编码有效性
    if (is_valid_utf8(name)) {
        return offset;
    } else {
        return -2;      // 非utf-8编码
    }
}

/* 安全读取网络字节序 16 位整数，避免直接强转访问 */
static uint16_t dns_read_u16(const uint8_t *p) {
    uint16_t v;
    memcpy(&v, p, sizeof(v));
    return ntohs(v);
}

/* 安全读取网络字节序 32 位整数 */
static uint32_t dns_read_u32(const uint8_t *p) {
    uint32_t v;
    memcpy(&v, p, sizeof(v));
    return ntohl(v);
}

/* 根据 session 的固定角色格式化 client/resolver 地址，兼容 IPv4/IPv6
 * 说明：
 * 1. 这里不再根据当前包是 request/response 来交换方向。
 * 2. 直接以 session 中“谁是客户端、谁是 DNS 服务器”的固定语义输出。
 * 3. 对于正常 DNS 会话：
 *    - dst_port == 53: session->src 是 client，session->dst 是 resolver
 *    - src_port == 53: session->dst 是 client，session->src 是 resolver
 */
static bool dns_format_session_roles(const session_entry_t *session,
                                     char *client_ip_str,
                                     size_t client_ip_len,
                                     char *resolver_ip_str,
                                     size_t resolver_ip_len,
                                     uint16_t *client_port,
                                     uint16_t *resolver_port) {
    if (!session || !client_ip_str || !resolver_ip_str || !client_port || !resolver_port) return false;

    client_ip_str[0] = '\0';
    resolver_ip_str[0] = '\0';
    *client_port = 0;
    *resolver_port = 0;

    bool src_is_client = false;
    bool dst_is_client = false;

    if (session->dst_port == 53 && session->src_port != 53) {
        src_is_client = true;
    } else if (session->src_port == 53 && session->dst_port != 53) {
        dst_is_client = true;
    } else if (session->dst_port == 53) {
        src_is_client = true;
    } else if (session->src_port == 53) {
        dst_is_client = true;
    } else {
        return false;
    }

    if (session->ip_version == 4) {
        struct in_addr client_addr;
        struct in_addr resolver_addr;

        if (src_is_client) {
            client_addr.s_addr = session->src_ip;
            resolver_addr.s_addr = session->dst_ip;
            *client_port = session->src_port;
            *resolver_port = session->dst_port;
        } else if (dst_is_client) {
            client_addr.s_addr = session->dst_ip;
            resolver_addr.s_addr = session->src_ip;
            *client_port = session->dst_port;
            *resolver_port = session->src_port;
        } else {
            return false;
        }

        if (inet_ntop(AF_INET, &client_addr, client_ip_str, client_ip_len) == nullptr) return false;
        if (inet_ntop(AF_INET, &resolver_addr, resolver_ip_str, resolver_ip_len) == nullptr) return false;
        return true;
    }

    if (session->ip_version == 6) {
        const uint8_t *client_ip6 = nullptr;
        const uint8_t *resolver_ip6 = nullptr;

        if (src_is_client) {
            client_ip6 = session->src_ip6;
            resolver_ip6 = session->dst_ip6;
            *client_port = session->src_port;
            *resolver_port = session->dst_port;
        } else if (dst_is_client) {
            client_ip6 = session->dst_ip6;
            resolver_ip6 = session->src_ip6;
            *client_port = session->dst_port;
            *resolver_port = session->src_port;
        } else {
            return false;
        }

        if (inet_ntop(AF_INET6, client_ip6, client_ip_str, client_ip_len) == nullptr) return false;
        if (inet_ntop(AF_INET6, resolver_ip6, resolver_ip_str, resolver_ip_len) == nullptr) return false;
        return true;
    }

    return false;
}

/* ClickHouse Array(String) 字面量转义 */
static string dns_ch_escape_string(const string &s) {
    string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        if (c == '\\' || c == '\'') out.push_back('\\');
        out.push_back(c);
    }
    return out;
}

/* 限长以保证数组完整、避免超 512/256 定长缓冲被 snprintf 从中间截断致 CH 整批拒收：
 * 逐元素追加，若加入下一个元素（含结尾 ']'）会使总长超过 max_bytes，则停止追加并正常闭合 ']'，
 * 只保留能完整放下的前若干个元素，绝不产生截断到一半的元素或残缺数组。 */
static string dns_build_ch_string_array(const vector<string> &arr, size_t max_bytes) {
    string out = "[";
    for (size_t i = 0; i < arr.size(); ++i) {
        string piece;
        if (i > 0) piece += ",";
        piece += "'";
        piece += dns_ch_escape_string(arr[i]);
        piece += "'";
        if (out.size() + piece.size() + 1 > max_bytes) break; /* +1 预留结尾 ']' */
        out += piece;
    }
    out += "]";
    return out;
}

/* 同上，数值数组同样限长以避免超缓冲截断致 CH 整批拒收 */
static string dns_build_ch_uint16_array(const vector<uint16_t> &arr, size_t max_bytes) {
    string out = "[";
    for (size_t i = 0; i < arr.size(); ++i) {
        string piece;
        if (i > 0) piece += ",";
        piece += to_string(arr[i]);
        if (out.size() + piece.size() + 1 > max_bytes) break; /* +1 预留结尾 ']' */
        out += piece;
    }
    out += "]";
    return out;
}

/* 同上，数值数组同样限长以避免超缓冲截断致 CH 整批拒收 */
static string dns_build_ch_uint32_array(const vector<uint32_t> &arr, size_t max_bytes) {
    string out = "[";
    for (size_t i = 0; i < arr.size(); ++i) {
        string piece;
        if (i > 0) piece += ",";
        piece += to_string(arr[i]);
        if (out.size() + piece.size() + 1 > max_bytes) break; /* +1 预留结尾 ']' */
        out += piece;
    }
    out += "]";
    return out;
}

/* 构造 DNS 响应事务唯一键，用于去重写库 */
// 预处理，先解析 DNS 请求，成功后再决定是否创建 session ext
static bool dns_parse_request_info(const uint8_t *payload, uint32_t len, dns_query_info_t &info) {
    if (!payload || len < 12) return false;

    uint16_t flags = dns_read_u16(payload + 2);
    if ((flags & DNS_FLAG_RESPONSE) != 0) return false;
    if (DNS_FLAG_OPCODE(flags) != 0) return false;

    string qname;
    int qname_end = dns_parse_name(payload, len, 12, qname, 0);
    if (qname_end < 0) return false;
    if (qname_end + 4 > (int)len) return false;
    if (qname.empty()) return false;

    info.qname = qname;
    info.qtype = dns_read_u16(payload + qname_end);
    info.qclass = dns_read_u16(payload + qname_end + 2);
    info.txid = dns_read_u16(payload + 0);
    info.flags = flags;
    info.ancount = dns_read_u16(payload + 6);
    info.nscount = dns_read_u16(payload + 8);
    info.arcount = dns_read_u16(payload + 10);
    info.rcode = DNS_FLAG_RCODE(flags);

    // 过滤明显无意义的根域名空请求，减少伪记录
    if (info.qname == "." && info.qtype == 0) {
        return false;
    }

    return true;
}

// 预处理，先解析 DNS 响应基础字段，成功后再决定是否创建 session ext
static bool dns_parse_response_info(const uint8_t *payload, uint32_t len, dns_response_info_t &info) {
    if (!payload || len < 12) return false;

    uint16_t flags = dns_read_u16(payload + 2);
    if ((flags & DNS_FLAG_RESPONSE) == 0) return false;

    string qname;
    int qname_end = dns_parse_name(payload, len, 12, qname, 0);
    if (qname_end < 0) return false;
    if (qname_end + 4 > (int)len) return false;
    if (qname.empty()) return false;

    info.query.qname = qname;
    info.query.qtype = dns_read_u16(payload + qname_end);
    info.query.qclass = dns_read_u16(payload + qname_end + 2);
    info.query.txid = dns_read_u16(payload + 0);
    info.query.flags = flags;
    info.query.ancount = dns_read_u16(payload + 6);
    info.query.nscount = dns_read_u16(payload + 8);
    info.query.arcount = dns_read_u16(payload + 10);
    info.query.rcode = DNS_FLAG_RCODE(flags);
    info.answer_count = 0;

    return true;
}

/* 获取或创建 DNS 会话扩展数据 */
static dns_ext_data_t* dns_get_or_create_ext(session_entry_t *session) {
    if (!session) return nullptr;

    if (session->ext_type == EXT_FIELD_DNS && session->ext_data) {
        dns_ext_data_t *ext = (dns_ext_data_t *)session->ext_data;
        if (ext->magic == DNS_EXT_MAGIC) {
            return ext;
        }
    }

    dns_ext_data_t *ext = (dns_ext_data_t *)calloc(1, sizeof(dns_ext_data_t));
    if (!ext) return nullptr;

    ext->magic = DNS_EXT_MAGIC;
    session_set_ext_data(session, EXT_FIELD_DNS, ext);
    return ext;
}

/* 向会话层追加一条 DNS answer 记录 */
static void dns_append_answer_ext(dns_ext_data_t *ext,
                                  uint16_t type,
                                  uint16_t rdlen,
                                  uint32_t ttl,
                                  const string &value) {
    if (!ext) return;
    if (ext->answer_count >= DNS_MAX_ANSWERS) return;

    dns_answer_item_t &item = ext->answers[ext->answer_count++];
    item.type = type;
    item.rdlen = rdlen;
    item.ttl = ttl;

    strncpy(item.value, value.c_str(), sizeof(item.value) - 1);
    item.value[sizeof(item.value) - 1] = '\0';
}

/* ========== 业务逻辑分流 ========== */

static void handle_dns_request(dns_engine_data_t *data,
                               const uint8_t *payload,
                               uint32_t len,
                               const char* client_ip,
                               const char* resolver_ip,
                               dns_ext_data_t *ext) {
    dns_query_info_t info;
    if (!dns_parse_request_info(payload, len, info)) return;
    time_t now = time(nullptr);

    {
        lock_guard<mutex> lock(data->cache_mutex);
        data->total_queries++;
        DnsRecord &rec = dns_cache_get_record_locked(data, client_ip, info.qname, NULL);

        // 请求解析成功后，才更新会话层 ext
        if (ext) {
            bool same_pending = dns_ext_same_pending_request(ext, info);
            bool recently_completed = same_pending && ext->counted &&
                                      (now - (time_t)ext->last_update_ts <= 10);

            if (!same_pending || !recently_completed) {
                dns_ext_mark_pending_request(ext, info, client_ip, resolver_ip, now);
            } else {
                ext->confirmed = 1;
                ext->is_response = 0;
                ext->has_query = 1;
                ext->last_update_ts = (uint64_t)now;
            }
        }

        // 仅记录请求触达时间，不保存历史答案，避免 cache 线性膨胀
        rec.domain = info.qname;
        rec.last_update = time(nullptr);
        rec.last_qtype = info.qtype;
        rec.last_rcode = info.rcode;
    }

    if (dns_log_level() > 1) {
        TM_LOG_DEBUG("DNS", "Request: client=%s qname=%s", client_ip, info.qname.c_str());
    }
}

static void handle_dns_response(dns_engine_data_t *data,
                                session_entry_t *session,
                                const uint8_t *payload,
                                uint32_t len,
                                const char* client_ip,
                                const char* resolver_ip,
                                uint16_t client_port,
                                uint16_t resolver_port,
                                dns_ext_data_t *ext,
                                engine_ctx_t *ctx) {
    (void)ctx;
    dns_response_info_t info;
    if (!dns_parse_response_info(payload, len, info)) return;
    time_t now = time(nullptr);

    uint16_t ancount = info.query.ancount;
    string qname = info.query.qname;
    int offset = dns_parse_name(payload, len, 12, qname, 0);
    if (offset < 0) return;
    offset += 4;

    //构造数据库写入所需的结构
    vector<string> answer_ips;
    vector<string> answer_domains;
    vector<uint16_t> answer_types;
    vector<uint32_t> ttl_list;
    vector<string> answer_summaries;

    // 响应解析成功后，才更新会话层 ext
    if (ext) {
        // 诊断日志：用 dns_log_level >= 2 开启，观察 pending 匹配失败的真实原因
        // 定论前不恢复严格 dns_ext_same_pending_request 检查
        if (dns_log_level() > 1) {
            bool match_valid  = ext->pending_valid != 0;
            bool match_txid   = ext->pending_txid == info.query.txid;
            bool match_qtype  = ext->pending_qtype == info.query.qtype;
            bool match_qname  = strncmp(ext->pending_qname,
                                        info.query.qname.c_str(),
                                        sizeof(ext->pending_qname)) == 0;
            TM_LOG_DEBUG("DNS", "Diag resp qname=%s txid=%u qtype=%u | pending_valid=%d pending_txid=%u pending_qtype=%u pending_qname=%s | match_valid=%d match_txid=%d match_qtype=%d match_qname=%d",
                         info.query.qname.c_str(),
                         (unsigned)info.query.txid, (unsigned)info.query.qtype,
                         (int)ext->pending_valid,
                         (unsigned)ext->pending_txid, (unsigned)ext->pending_qtype,
                         ext->pending_qname,
                         (int)match_valid, (int)match_txid,
                         (int)match_qtype, (int)match_qname);
        }

        // 当前 band-aid：仅用 counted 去重，不要求 pending 匹配
        if (ext->counted) {
            return;
        }

        ext->confirmed = 1;
        ext->is_response = 1;
        ext->has_answer = 1;
        ext->txid = info.query.txid;
        ext->flags = info.query.flags;
        ext->ancount = info.query.ancount;
        ext->nscount = info.query.nscount;
        ext->arcount = info.query.arcount;
        ext->rcode = info.query.rcode;
        ext->qtype = info.query.qtype;
        ext->qclass = info.query.qclass;
        ext->last_update_ts = (uint64_t)time(nullptr);

        strncpy(ext->client_ip, client_ip, sizeof(ext->client_ip) - 1);
        ext->client_ip[sizeof(ext->client_ip) - 1] = '\0';
        strncpy(ext->resolver_ip, resolver_ip, sizeof(ext->resolver_ip) - 1);
        ext->resolver_ip[sizeof(ext->resolver_ip) - 1] = '\0';
        strncpy(ext->qname, info.query.qname.c_str(), sizeof(ext->qname) - 1);
        ext->qname[sizeof(ext->qname) - 1] = '\0';

        // 每次处理响应前重置 answer_count，仅保留本次响应摘要
        ext->answer_count = 0;
        ext->last_update_ts = (uint64_t)now;
    }

    {
        lock_guard<mutex> lock(data->cache_mutex);
        DnsRecord& rec = dns_cache_get_record_locked(data, client_ip, info.query.qname, NULL);
        rec.domain = info.query.qname;
        rec.last_update = now;
        rec.last_qtype = info.query.qtype;
        rec.last_rcode = info.query.rcode;
        rec.last_is_success = (info.query.rcode == 0) ? 1 : 0;
        rec.response_count++;
        rec.answers.clear();
    }

    for (uint16_t i = 0; i < ancount; ++i) {
        if (offset + 10 > (int)len) break;

        string ans_name;
        offset = dns_parse_name(payload, len, offset, ans_name, 0);
        if (offset < 0) break;

        uint16_t type = dns_read_u16(payload + offset);
        uint32_t ttl = dns_read_u32(payload + offset + 4);
        uint16_t rdlen = dns_read_u16(payload + offset + 8);
        int rdata_ptr = offset + 10;
        if (rdata_ptr + rdlen > (int)len) break;

        string record_info = "";
        string answer_value = "";

        answer_types.push_back(type);
        ttl_list.push_back(ttl);

        if (type == 1 && rdlen == 4) { // A
            char ip[DNS_IP_STR_LEN];
            snprintf(ip, sizeof(ip), "%d.%d.%d.%d",
                     payload[rdata_ptr], payload[rdata_ptr+1],
                     payload[rdata_ptr+2], payload[rdata_ptr+3]);
            record_info = string("[A] ") + ip;
            answer_value = ip;
            answer_ips.push_back(answer_value);
        } else if (type == 28 && rdlen == 16) { // AAAA
            char ipv6[INET6_ADDRSTRLEN];
            if (inet_ntop(AF_INET6, payload + rdata_ptr, ipv6, sizeof(ipv6))) {
                record_info = string("[AAAA] ") + ipv6;
                answer_value = ipv6;
                answer_ips.push_back(answer_value);
            }
        } else if (type == 5 || type == 2) { // CNAME/NS
            string target;
            if (dns_parse_name(payload, len, rdata_ptr, target, 0) >= 0) {
                record_info = (type == 5 ? "[CNAME] " : "[NS] ") + target;
                answer_value = target;
                answer_domains.push_back(answer_value);
            }
        }

        if (!record_info.empty()) answer_summaries.push_back(record_info);

        // 仅把成功解析出的 answer 写入会话层
        if (ext && !answer_value.empty()) {
            dns_append_answer_ext(ext, type, rdlen, ttl, answer_value);
        }

        offset = rdata_ptr + rdlen;
    }

    {
        /* 目标缓冲见 ch_event.h：answer_ips_ch/answer_domains_ch=512、answer_types_ch/ttls_ch=256。
         * 传入留余量的上限（512->500、256->248），保证生成的数组字面量能完整放进定长缓冲，
         * 不会被下面的 snprintf 从中间截断成非法 JSON 数组导致 CH 整批 INSERT 被拒。 */
        string answer_ips_ch = dns_build_ch_string_array(answer_ips, 500);
        string answer_domains_ch = dns_build_ch_string_array(answer_domains, 500);
        string answer_types_ch = dns_build_ch_uint16_array(answer_types, 248);
        string ttls_ch = dns_build_ch_uint32_array(ttl_list, 248);
        uint8_t is_success = (info.query.rcode == 0) ? 1 : 0;

        if (info.query.rcode != 0) {
            if (dns_log_level() > 1) {
                TM_LOG_DEBUG("DNS", "CH skip: qname=%s client=%s:%u resolver=%s:%u txid=%u rcode=%u",
                             info.query.qname.c_str(),
                             client_ip,
                             client_port,
                             resolver_ip,
                             resolver_port,
                             info.query.txid,
                             (unsigned)info.query.rcode);
            }
            return;
        }

        {
            ev_dns_t _ev = {};
            snprintf(_ev.client_ip,   sizeof(_ev.client_ip),   "%s", client_ip);
            snprintf(_ev.resolver_ip, sizeof(_ev.resolver_ip), "%s", resolver_ip);
            _ev.src_port = client_port;
            _ev.dst_port = resolver_port;
            snprintf(_ev.transport_proto, sizeof(_ev.transport_proto), "UDP");
            snprintf(_ev.qname, sizeof(_ev.qname), "%s", info.query.qname.c_str());
            _ev.qtype = info.query.qtype;
            _ev.rcode = (uint8_t)info.query.rcode;
            snprintf(_ev.answer_ips_ch,     sizeof(_ev.answer_ips_ch),     "%s", answer_ips_ch.c_str());
            snprintf(_ev.answer_domains_ch, sizeof(_ev.answer_domains_ch), "%s", answer_domains_ch.c_str());
            snprintf(_ev.answer_types_ch,   sizeof(_ev.answer_types_ch),   "%s", answer_types_ch.c_str());
            snprintf(_ev.ttls_ch,           sizeof(_ev.ttls_ch),           "%s", ttls_ch.c_str());
            _ev.answer_count = (uint16_t)answer_types.size();
            _ev.is_success   = is_success;
            _ev.ts_sec       = (int64_t)now;
            ch_event_enqueue(EV_DNS, &_ev, sizeof(_ev));
        }
        if (ext) {
            if (!ext->counted) {
                char sid[160] = {0};
                uint8_t proto = session ? session->key.protocol : 17;
                dns_build_session_id(client_ip, client_port,
                                     resolver_ip, resolver_port,
                                     proto, sid, sizeof(sid));
                engine_log_event("DNS",
                                 ENGINE_LOG_INFO,
                                 ENGINE_EVENT_DECISION,
                                 sid,
                                 client_ip,
                                 resolver_ip,
                                 "confirm",
                                 "{}");
            }
            ext->counted = 1;
        }
        if (dns_log_level() > 1) {
            TM_LOG_DEBUG("DNS", "CH write: qname=%s client=%s:%u resolver=%s:%u txid=%u answers=%zu",
                         info.query.qname.c_str(),
                         client_ip,
                         client_port,
                         resolver_ip,
                         resolver_port,
                         info.query.txid,
                         answer_summaries.size());
        }
    }

    {
        lock_guard<mutex> lock(data->cache_mutex);
        data->total_answers++;
        DnsRecord& rec = data->cache[client_ip][info.query.qname];
        rec.domain = info.query.qname;
        rec.last_update = now;
        rec.last_qtype = info.query.qtype;
        rec.last_rcode = info.query.rcode;
        rec.last_is_success = 1;
        rec.response_count++;
        rec.answers = answer_summaries;
    }
}

/* ========== 插件标准接口 ========== */

static int dns_plugin_init(engine_ctx_t *ctx) {
    dns_engine_data_t *data = new dns_engine_data_t();
    if (!data) return ENGINE_ERROR;
    domain_intel_init();

    data->total_queries = 0;
    data->total_answers = 0;
    data->cache_record_count = 0;
    data->last_cleanup_time = time(nullptr);

    ctx->private_data = data;
    engine_log_event("DNS",
                     ENGINE_LOG_INFO,
                     ENGINE_EVENT_STATS,
                     "",
                     "",
                     "",
                     "init",
                     "{}");

    if (dns_log_level() > 0) {
        TM_LOG_INFO("DNS Engine", "Initialized with full RFC 1035 validator.");
    }
    if (dns_log_level() > 1) {
        TM_LOG_DEBUG("DNS Engine", "Init: pid=%d tid=%lu ctx=%p private_data=%p",
                     getpid(),
                     (unsigned long)pthread_self(),
                     (void*)ctx,
                     (void*)(ctx ? ctx->private_data : NULL));
    }
    return ENGINE_OK;
}

static bool dns_plugin_can_handle(engine_ctx_t *ctx, const session_entry_t *session, const parsed_packet_t *pkt) {
    (void)ctx; (void)session;
    return (pkt->protocol == 17 && (pkt->dst_port == 53 || pkt->src_port == 53));
}

static int dns_plugin_process(engine_ctx_t *ctx, session_entry_t *session, const parsed_packet_t *pkt) {
    dns_engine_data_t *data = (dns_engine_data_t *)ctx->private_data;
    if (!ctx || !data || !session || !pkt || !pkt->payload) return ENGINE_SKIP;

    // --- 主动扫描清理逻辑 ---
    dns_cache_cleanup(data);
    if (dns_log_level() > 1) {
        TM_LOG_DEBUG("DNS", "Process: ctx=%p session=%p payload_len=%u src_port=%u dst_port=%u",
                     (void*)ctx,
                     (void*)session,
                     pkt ? pkt->payload_len : 0,
                     pkt ? pkt->src_port : 0,
                     pkt ? pkt->dst_port : 0);
    }

    if (pkt->payload_len < 12) return ENGINE_SKIP;

    char client_ip_str[DNS_IP_STR_LEN];
    char resolver_ip_str[DNS_IP_STR_LEN];
    uint16_t client_port = 0;
    uint16_t resolver_port = 0;
    uint16_t flag = dns_read_u16(pkt->payload + 2);
    bool is_response = (flag & DNS_FLAG_RESPONSE) != 0;

    /* 通过 session 中的固定角色统一格式化 client/resolver，兼容 IPv4/IPv6 */
    if (!dns_format_session_roles(session,
                                  client_ip_str,
                                  sizeof(client_ip_str),
                                  resolver_ip_str,
                                  sizeof(resolver_ip_str),
                                  &client_port,
                                  &resolver_port)) {
        return ENGINE_SKIP;
    }

    // 只有预解析成功，才创建/更新 DNS ext，避免留下空 qname 记录
    if (!is_response) {
        dns_query_info_t info;
        if (!dns_parse_request_info(pkt->payload, pkt->payload_len, info)) {
            return ENGINE_SKIP;
        }

        dns_ext_data_t *ext = dns_get_or_create_ext(session);
        handle_dns_request(data, pkt->payload, pkt->payload_len, client_ip_str, resolver_ip_str, ext);

        if (ext && dns_log_level() > 1) {
            TM_LOG_DEBUG("DNS", "EXT client=%s resolver=%s qname=%s qtype=%u is_response=%u answers=%u",
                         ext->client_ip,
                         ext->resolver_ip,
                         ext->qname,
                         ext->qtype,
                         ext->is_response,
                         ext->answer_count);
        }

        if (!info.qname.empty() && info.qname != ".") {
            domain_intel_process_dns(client_ip_str, resolver_ip_str,
                                     pkt->src_port, pkt->dst_port,
                                     info.qname.c_str(),
                                     (int64_t)time(nullptr));
        }
    } else {
        dns_response_info_t info;
        if (!dns_parse_response_info(pkt->payload, pkt->payload_len, info)) {
            return ENGINE_SKIP;
        }

        dns_ext_data_t *ext = dns_get_or_create_ext(session);
        handle_dns_response(data,
                            session,
                            pkt->payload,
                            pkt->payload_len,
                            client_ip_str,
                            resolver_ip_str,
                            client_port,
                            resolver_port,
                            ext,
                            ctx);

        if (ext && dns_log_level() > 1) {
            TM_LOG_DEBUG("DNS", "EXT client=%s resolver=%s qname=%s qtype=%u is_response=%u answers=%u",
                         ext->client_ip,
                         ext->resolver_ip,
                         ext->qname,
                         ext->qtype,
                         ext->is_response,
                         ext->answer_count);
        }

        if (ext && ext->answer_count > 0 && dns_log_level() > 1) {
            TM_LOG_DEBUG("DNS", "EXT answer type=%u value=%s ttl=%u",
                         ext->answers[0].type,
                         ext->answers[0].value,
                         ext->answers[0].ttl);
        }
    }

    dns_cache_cleanup(data);

    ctx->stats.packets_matched++;
    return ENGINE_CONTINUE;
}

static void dns_plugin_cleanup(engine_ctx_t *ctx) {
    domain_intel_cleanup();
    if (ctx->private_data) {
        dns_engine_data_t *data = (dns_engine_data_t *)ctx->private_data;
        size_t client_count = 0;
        {
            lock_guard<mutex> lock(data->cache_mutex);
            client_count = data->cache.size();
            if (dns_log_level() > 0) {
                TM_LOG_INFO("DNS Engine", "Cleanup: TotalQueries=%" PRIu64 " TotalAnswers=%" PRIu64 " Clients=%zu Records=%zu",
                            data->total_queries,
                            data->total_answers,
                            client_count,
                            data->cache_record_count);
            }

            if (dns_log_level() > 1) {
                for (auto const& [ip, domains] : data->cache) {
                    for (auto const& [domain, record] : domains) {
                        TM_LOG_DEBUG("DNS", "Cache client=%s qname=%s answers=%zu",
                                     ip.c_str(), domain.c_str(), record.answers.size());
                    }
                }
            }
        }

        delete data;
        ctx->private_data = NULL;
    }
}

static const engine_ops_t dns_engine_ops = {
    .name = "DNS",
    .protocol_id = 53,
    .priority = 120,
    .init = dns_plugin_init,
    .can_handle = dns_plugin_can_handle,
    .process = dns_plugin_process,
    .cleanup = dns_plugin_cleanup,
    .get_stats = NULL,
    .reset_stats = NULL
};

extern "C" const engine_ops_t* engine_get_ops(void) {
    return &dns_engine_ops;
}