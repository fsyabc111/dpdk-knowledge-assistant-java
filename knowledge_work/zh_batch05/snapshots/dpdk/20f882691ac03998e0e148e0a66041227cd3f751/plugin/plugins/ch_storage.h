/**
 * @file ch_storage.h
 * @brief ClickHouse 存储模块 - 全局单例接口
 *
 * 通过 ClickHouse HTTP 接口（默认 8123 端口）写入高频时序数据。
 * 依赖：libcurl
 *
 * 各表：
 *   - http_requests    : HTTP/HLS/DASH 请求记录
 *   - https_traffic    : HTTPS 流量识别记录
 *   - tls_sessions     : TLS ClientHello 记录
 *   - ja3_fingerprints : JA3 指纹记录
 */

#ifndef CH_STORAGE_H
#define CH_STORAGE_H

#include <stdint.h>
#include <stddef.h>   /* size_t（供 ss_format_reason 等声明使用） */
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ------------------------------------------------------------------ */
/*  初始化 / 清理                                                       */
/* ------------------------------------------------------------------ */

/**
 * 初始化 ClickHouse 连接（全局单例）。
 * 必须在所有 ch_insert_* 调用之前调用一次。
 *
 * @param url      ClickHouse HTTP 地址，例如 "http://localhost:8123"
 * @param db_name  数据库名称
 * @param user     用户名（NULL 表示不鉴权）
 * @param password 密码（NULL 表示不鉴权）
 * @return  0 成功，-1 失败
 */
int ch_storage_init(const char *url, const char *db_name,
                    const char *user, const char *password);

/**
 * 清理资源，程序退出前调用一次。
 */
void ch_storage_cleanup(void);

/**
 * 返回当前是否已成功初始化。
 */
bool ch_storage_is_ready(void);

/* ------------------------------------------------------------------ */
/*  DDL：建表（首次运行时调用）                                          */
/* ------------------------------------------------------------------ */

/**
 * 在 ClickHouse 中建立所需的四张表（若已存在则跳过）。
 * @return 0 成功，-1 失败
 */
int ch_create_tables(void);

/** Raw SQL execute with caller-provided full URL. Used by engine_log. */
int ch_exec_raw(const char *full_url, const char *sql);

/* ------------------------------------------------------------------ */
/*  各插件写入接口                                                       */
/* ------------------------------------------------------------------ */

void ch_insert_http(const char *src_ip, const char *dst_ip,
                    uint16_t src_port, uint16_t dst_port,
                    const char *method, const char *uri,
                    const char *host, const char *user_agent,
                    const char *content_type, int protocol_type,
                    int64_t ts_sec);

void ch_insert_https(const char *src_ip, const char *dst_ip,
                     uint16_t src_port, uint16_t dst_port,
                     uint64_t bytes, uint32_t packets,
                     uint16_t tls_version, const char *sni,
                     int64_t ts_sec);

void ch_insert_tls(const char *src_ip, const char *dst_ip,
                   uint16_t src_port, uint16_t dst_port,
                   uint16_t tls_version, const char *sni,
                   const char *cipher_suites,
                   uint16_t cipher_suites_count, uint16_t extensions_count,
                   int64_t ts_sec);

void ch_insert_ja3(const char *src_ip, const char *dst_ip,
                   uint16_t src_port, uint16_t dst_port,
                   const char *sni, uint16_t tls_version,
                   const char *cipher_suites, const char *extensions,
                   const char *elliptic_curves, const char *ec_point_formats,
                   const char *ja3_string, const char *ja3_hash,
                   int64_t ts_sec);

void ch_insert_ssh(const char *src_ip, const char *dst_ip,
                   uint16_t src_port, uint16_t dst_port,
                   uint8_t protocol,
                   const char *protocol_name,
                   const char *client_version,
                   const char *server_version,
                   int64_t ts_sec);


void ch_insert_quic(const char *src_ip, const char *dst_ip,
                    uint16_t src_port, uint16_t dst_port,
                    uint8_t protocol,
                    const char *protocol_name,
                    uint8_t last_header_form,
                    uint8_t last_long_type,
                    uint8_t packet_number_len,
                    uint8_t dcid_len,
                    uint8_t scid_len,
                    uint32_t packet_number,
                    const char *dcid,
                    const char *scid,
                    uint32_t quic_version,
                    const char *sni,
                    const char *alpn,
                    uint8_t is_doq,
                    uint8_t confidence,
                    const char *detect_reason,
                    int64_t ts_sec);

void ch_insert_ftp(const char *src_ip, const char *dst_ip,
                   uint16_t src_port, uint16_t dst_port,
                   uint8_t protocol,
                   const char *protocol_name,
                   const char *file_name,
                   int64_t ts_sec);

void ch_insert_smtp(const char *src_ip, const char *dst_ip,
                    uint16_t src_port, uint16_t dst_port,
                    uint8_t protocol,
                    const char *protocol_name,
                    const char *email,
                    int64_t ts_sec);

void ch_update_smtp_email(const char *src_ip, const char *dst_ip,
                          uint16_t src_port, uint16_t dst_port,
                          uint8_t protocol,
                          const char *protocol_name,
                          const char *email);

void ch_insert_wireguard(const char *src_ip, const char *dst_ip,
                         uint16_t src_port, uint16_t dst_port,
                         uint8_t protocol,
                         const char *protocol_name,
                         int64_t ts_sec);

void ch_insert_pop3(const char *src_ip, const char *dst_ip,
                    uint16_t src_port, uint16_t dst_port,
                    uint8_t protocol,
                    const char *protocol_name,
                    const char *email,
                    int64_t ts_sec);

void ch_update_pop3_email(const char *src_ip, const char *dst_ip,
                          uint16_t src_port, uint16_t dst_port,
                          uint8_t protocol,
                          const char *protocol_name,
                          const char *email);

void ch_insert_imap(const char *src_ip, const char *dst_ip,
                    uint16_t src_port, uint16_t dst_port,
                    uint8_t protocol,
                    const char *protocol_name,
                    int64_t ts_sec);

void ch_insert_rtsp(const char *src_ip, const char *dst_ip,
                    uint16_t src_port, uint16_t dst_port,
                    uint8_t protocol,
                    const char *protocol_name,
                    int64_t ts_sec);

void ch_insert_ipsecvpn(const char *src_ip, const char *dst_ip,
                        uint16_t src_port, uint16_t dst_port,
                        uint8_t protocol,
                        const char *protocol_name,
                        int64_t ts_sec);
                        
void ch_insert_openvpn(const char *src_ip, const char *dst_ip,
                       uint16_t src_port, uint16_t dst_port,
                       uint8_t protocol,
                       const char *protocol_name,
                       int64_t ts_sec);

void ch_insert_dns_resolution(const char *client_ip,
                              const char *resolver_ip,
                              uint16_t src_port,
                              uint16_t dst_port,
                              const char *transport_proto,
                              const char *qname,
                              uint16_t qtype,
                              uint8_t rcode,
                              const char *answer_ips_ch,
                              const char *answer_domains_ch,
                              const char *answer_types_ch,
                              const char *ttls_ch,
                              uint16_t answer_count,
                              uint8_t is_success,
                              int64_t ts_sec);

void ch_insert_dtls_event(const char *src_ip,
                        const char *dst_ip,
                        uint16_t src_port,
                        uint16_t dst_port,
                        uint16_t version,
                        uint8_t record_type,
                        uint8_t handshake_type,
                        uint16_t epoch,
                        uint16_t record_len,
                        uint32_t payload_len,
                        const char *detail_desc,
                        uint8_t is_handshake,
                        uint8_t is_appdata,
                        uint8_t is_alert,
                        const char *transport_proto,
                        int64_t ts_sec);

void ch_insert_tls_infra_event(const char *src_ip,
                            const char *dst_ip,
                            uint16_t src_port,
                            uint16_t dst_port,
                            uint16_t tls_record_version,
                            uint8_t record_type,
                            uint16_t record_len,
                            uint32_t payload_len,
                            const char *detected_type,
                            const char *detection_method,
                            const char *sni,
                            const char *alpn,
                            uint8_t is_doh,
                            uint8_t is_dot,
                            uint8_t is_ech,
                            uint8_t is_dns_tunnel,
                            int64_t ts_sec);

void ch_insert_rdp_event(const char *src_ip,
                        const char *dst_ip,
                        uint16_t src_port,
                        uint16_t dst_port,
                        uint8_t tpdu_type,
                        const char *tpdu_type_name,
                        const char *cookie,
                        uint32_t requested_protocols,
                        uint8_t cookie_seen,
                        uint8_t security_seen,
                        uint32_t payload_len,
                        const char *transport_proto,
                        int64_t ts_sec);

void ch_insert_rtmp_event(const char *src_ip,
                        const char *dst_ip,
                        uint16_t src_port,
                        uint16_t dst_port,
                        const char *event_type,
                        uint8_t is_handshake,
                        uint8_t is_command,
                        uint32_t payload_len,
                        const char *transport_proto,
                        int64_t ts_sec);

void ch_insert_smb_event(const char *src_ip,
                        const char *dst_ip,
                        uint16_t src_port,
                        uint16_t dst_port,
                        uint8_t smb_version,
                        const char *smb_version_name,
                        uint32_t payload_len,
                        const char *transport_proto,
                        int64_t ts_sec);

void ch_insert_geo_event(const char *event_type,
                         const char *session_id,
                         const char *src_ip, const char *dst_ip,
                         const char *dst_country,
                         uint16_t src_port, uint16_t dst_port,
                         uint8_t protocol,
                         uint64_t bytes_up, uint64_t bytes_down,
                         double ratio,
                         const char *message,
                         int64_t ts_sec);

void ch_insert_domain_access(const char *src_ip, const char *dst_ip,
                             uint16_t src_port, uint16_t dst_port,
                             uint8_t protocol,
                             const char *domain,
                             const char *domain_group,
                             const char *source_type,
                             const char *focus_tag,
                             int64_t ts_sec);

void ch_insert_threat_domain(const char *src_ip, const char *dst_ip,
                             uint16_t src_port, uint16_t dst_port,
                             uint8_t protocol,
                             const char *domain,
                             const char *source_type,
                             const char *threat_type,
                             const char *intel_source,
                             int64_t ts_sec);

/* Phase 5: 富 schema 命中表。实参类型是 ev_threat_t；用 void* 规避
 * ch_storage.h -> ch_event.h 的耦合（ch_event.h 依赖 DPDK ring，拉进来太重） */
void ch_insert_ti_hit_v2(const void *ev_threat_ptr);

/* IDS 规则命中（engine_ids.so）。实参类型是 ev_ids_t，同样用 void*. */
void ch_insert_ids_hit(const void *ev_ids_ptr);

void ch_insert_focus_site(const char *src_ip, const char *dst_ip,
                          uint16_t src_port, uint16_t dst_port,
                          uint8_t protocol,
                          const char *domain,
                          const char *domain_group,
                          const char *source_type,
                          const char *focus_tag,
                          int64_t ts_sec);

/**
 * F4.2.3 站点访问排名写入。
 * 基于 SNI 统计各类站点的访问量，Web 界面按 Top N 查询。
 *
 * @param domain       域名（SNI）
 * @param domain_group 域名分组（如 "社交媒体"、"视频网站"）
 * @param src_ip      访问源 IP
 * @param ts_sec      时间戳（秒）
 */
void ch_insert_site_access(const char *domain,
                           const char *domain_group,
                           const char *src_ip,
                           int64_t ts_sec);

/**
 * F3.3.1 ICMP 隧道可疑包写入。
 * 仅记录 payload_len >= ICMP_TUNNEL_PAYLOAD_MIN 的 ICMP Echo 包。
 *
 * @param src_ip       源 IP 字符串
 * @param dst_ip       目标 IP 字符串
 * @param session_id   所属会话 ID
 * @param icmp_type    ICMP 类型（0=Echo Reply, 8=Echo Request）
 * @param icmp_code    ICMP 代码
 * @param identifier   ICMP 标识符（Echo 头第 5-6 字节）
 * @param seq_num      ICMP 序列号（Echo 头第 7-8 字节）
 * @param payload_len  ICMP Echo 数据部分字节数（去掉 8 字节 ICMP 头）
 * @param payload_hex  数据部分前 256 字节的 hex 字符串（空字符串表示无数据）
 * @param payload_text 数据部分的可打印 ASCII 预览（非打印字符替换为 '.'）
 * @param ts_sec       捕获时间戳（秒）
 */
void ch_insert_icmp_event(const char *src_ip,
                          const char *dst_ip,
                          const char *session_id,
                          uint8_t icmp_type,
                          uint8_t icmp_code,
                          uint16_t identifier,
                          uint16_t seq_num,
                          uint16_t payload_len,
                          const char *payload_hex,
                          const char *payload_text,
                          int64_t ts_sec);

void ch_insert_v2ray_event(const void *ev_v2ray_ptr);

void ch_insert_shadowsocks_event(const void *ev_ss_ptr);

/* Shadowsocks 名称/原因映射：供 stats/event_batch.c 的异步批量写库路径复用，
 * 确保 DPDK 异步路径与同步 ch_insert_shadowsocks_event() 产生完全一致的行。 */
const char *ss_state_name(uint8_t s);
const char *ss_path_name(uint8_t p);
const char *ss_confidence_name(uint8_t c);
void        ss_format_reason(uint32_t mask, char *out, size_t cap);

void ch_insert_operation_log(const char *event_type,
                              const char *source,
                              const char *actor,
                              const char *plugin_name,
                              uint32_t plugin_id,
                              const char *detail,
                              uint8_t status,
                              const char *extra);

#ifdef __cplusplus
}
#endif

#endif /* CH_STORAGE_H */
