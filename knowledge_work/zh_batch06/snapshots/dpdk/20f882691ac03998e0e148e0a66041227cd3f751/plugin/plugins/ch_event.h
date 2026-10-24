/**
 * @file ch_event.h
 * @brief 异步 ClickHouse 事件队列 —— 插件侧公共 API
 *
 * 设计目标：将插件热路径中的同步 curl 写入替换为非阻塞 ring 入队，
 * 彻底消除 worker lcore 因网络 I/O 而阻塞的问题。
 *
 * 使用方式（DPDK 多核模式）：
 *   1. main.c 在 init_pipeline_rings() 中调用 ch_event_init()
 *   2. 各插件 process() 函数调用 ch_event_enqueue() 代替 ch_insert_*()
 *   3. storage_lcore_main() 消费 event ring，调用 event_batch_flush()
 *
 * 单线程模式（libpcap）：
 *   ch_event_init() 不被调用，s_event_ring == NULL，
 *   ch_event_enqueue() 内部自动 fallback 到同步 ch_insert_*()
 */

#ifndef CH_EVENT_H
#define CH_EVENT_H

#include <stdint.h>
#include <stdbool.h>
#include <stddef.h>

#ifdef CAPTURE_ENABLE_DPDK
#include <rte_ring.h>
#include <rte_mempool.h>
#endif

#ifdef __cplusplus
extern "C" {
#endif

/* ------------------------------------------------------------------ */
/*  事件类型                                                             */
/* ------------------------------------------------------------------ */

typedef enum {
    EV_HTTP          =  1,
    EV_DNS           =  2,
    EV_JA3           =  3,
    EV_TLS           =  4,
    EV_FTP           =  5,
    EV_SMTP          =  6,
    EV_SSH           =  7,
    EV_QUIC          =  8,
    EV_WIREGUARD     =  9,
    EV_POP3          = 10,
    EV_IMAP          = 11,
    EV_RTSP          = 12,
    EV_IPSECVPN      = 13,
    EV_OPENVPN       = 14,
    EV_DTLS          = 15,
    EV_RDP           = 16,
    EV_RTMP          = 17,
    EV_SMB           = 18,
    EV_TLS_INFRA     = 19,
    EV_GEO           = 20,
    EV_FOCUS_SITE    = 21,
    EV_SITE_ACCESS   = 22,
    EV_THREAT_DOMAIN = 23,
    EV_ICMP_TUNNEL   = 24,
    EV_DOMAIN_ACCESS = 25,
    EV_HTTPS         = 26,
    EV_V2RAY         = 27,
    EV_IDS           = 28,
    EV_SHADOWSOCKS   = 29,
    EV_TYPE_MAX      = 30,
} event_type_t;

/* ------------------------------------------------------------------ */
/*  各协议 payload 结构体（值拷贝，栈上填充后入队，无动态分配）           */
/* ------------------------------------------------------------------ */

typedef struct {
    char     src_ip[46];
    char     dst_ip[46];
    uint16_t src_port;
    uint16_t dst_port;
    char     method[16];
    char     uri[512];
    char     host[256];
    char     user_agent[256];
    char     content_type[128];
    int      protocol_type;
    int64_t  ts_sec;
} ev_http_t;

typedef struct {
    char     src_ip[46];
    char     dst_ip[46];
    uint16_t src_port;
    uint16_t dst_port;
    uint64_t bytes;
    uint32_t packets;
    uint16_t tls_version;
    char     sni[256];
    int64_t  ts_sec;
} ev_https_t;

typedef struct {
    char     src_ip[46];
    char     dst_ip[46];
    uint16_t src_port;
    uint16_t dst_port;
    uint16_t tls_version;
    char     sni[256];
    char     cipher_suites[2048];
    uint16_t cipher_suites_count;
    uint16_t extensions_count;
    int64_t  ts_sec;
} ev_tls_t;

typedef struct {
    char     src_ip[46];
    char     dst_ip[46];
    uint16_t src_port;
    uint16_t dst_port;
    char     sni[256];
    uint16_t tls_version;
    char     cipher_suites[512];
    char     extensions[512];
    char     elliptic_curves[256];
    char     ec_point_formats[128];
    char     ja3_string[512];
    char     ja3_hash[36];
    int64_t  ts_sec;
} ev_ja3_t;

/* FTP / SMTP / WireGuard / POP3 / IMAP / RTSP / IPSecVPN / OpenVPN
 * 参数结构相同，复用 ev_simple_t
 *
 * extra[] 是可选字段：
 *   - EV_FTP  → file_name
 *   - EV_SMTP → email
 *   - EV_POP3 → email
 *   - 其它    → 保持空串（CH 列使用 DEFAULT '' 的表不会出错） */
typedef struct {
    char     src_ip[46];
    char     dst_ip[46];
    uint16_t src_port;
    uint16_t dst_port;
    uint8_t  protocol;
    char     protocol_name[64];
    char     extra[256];
    int64_t  ts_sec;
} ev_simple_t;

typedef struct {
    char     src_ip[46];
    char     dst_ip[46];
    uint16_t src_port;
    uint16_t dst_port;
    uint8_t  protocol;
    char     protocol_name[64];
    char     client_version[128];
    char     server_version[128];
    int64_t  ts_sec;
} ev_ssh_t;

typedef struct {
    char     src_ip[46];
    char     dst_ip[46];
    uint16_t src_port;
    uint16_t dst_port;
    uint8_t  protocol;
    char     protocol_name[64];
    uint8_t  last_header_form;
    uint8_t  last_long_type;
    uint8_t  packet_number_len;
    uint8_t  dcid_len;
    uint8_t  scid_len;
    uint32_t packet_number;
    char     dcid[64];
    char     scid[64];
    uint32_t quic_version;
    char     sni[256];
    char     alpn[128];
    uint8_t  is_doq;
    uint8_t  confidence;
    char     detect_reason[64];
    int64_t  ts_sec;
} ev_quic_t;

typedef struct {
    char     client_ip[46];
    char     resolver_ip[46];
    uint16_t src_port;
    uint16_t dst_port;
    char     transport_proto[8];   /* "UDP" / "TCP" */
    char     qname[256];
    uint16_t qtype;
    uint8_t  rcode;
    char     answer_ips_ch[512];
    char     answer_domains_ch[512];
    char     answer_types_ch[256];
    char     ttls_ch[256];
    uint16_t answer_count;
    uint8_t  is_success;
    uint8_t  is_cross_border;
    uint16_t foreign_ip_count;
    char     country_codes_ch[256];
    int64_t  ts_sec;
} ev_dns_t;

typedef struct {
    char     src_ip[46];
    char     dst_ip[46];
    uint16_t src_port;
    uint16_t dst_port;
    uint16_t version;
    uint8_t  record_type;
    uint8_t  handshake_type;
    uint16_t epoch;
    uint16_t record_len;
    uint32_t payload_len;
    char     detail_desc[256];
    uint8_t  is_handshake;
    uint8_t  is_appdata;
    uint8_t  is_alert;
    int64_t  ts_sec;
} ev_dtls_t;

typedef struct {
    char     src_ip[46];
    char     dst_ip[46];
    uint16_t src_port;
    uint16_t dst_port;
    uint16_t tls_record_version;
    uint8_t  record_type;
    uint16_t record_len;
    uint32_t payload_len;
    char     detected_type[128];
    char     detection_method[64];
    char     sni[256];
    char     alpn[128];
    uint8_t  is_doh;
    uint8_t  is_dot;
    uint8_t  is_ech;
    uint8_t  is_dns_tunnel;
    int64_t  ts_sec;
} ev_tls_infra_t;

typedef struct {
    char     src_ip[46];
    char     dst_ip[46];
    uint16_t src_port;
    uint16_t dst_port;
    uint8_t  tpdu_type;
    char     tpdu_type_name[64];
    char     cookie[256];
    uint32_t requested_protocols;
    uint8_t  cookie_seen;
    uint8_t  security_seen;
    uint32_t payload_len;
    int64_t  ts_sec;
} ev_rdp_t;

typedef struct {
    char     src_ip[46];
    char     dst_ip[46];
    uint16_t src_port;
    uint16_t dst_port;
    char     event_type[64];
    uint8_t  is_handshake;
    uint8_t  is_command;
    uint32_t payload_len;
    int64_t  ts_sec;
} ev_rtmp_t;

typedef struct {
    char     src_ip[46];
    char     dst_ip[46];
    uint16_t src_port;
    uint16_t dst_port;
    uint8_t  smb_version;
    char     smb_version_name[32];
    uint32_t payload_len;
    int64_t  ts_sec;
} ev_smb_t;

typedef struct {
    char     event_type[64];
    char     session_id[128];
    char     src_ip[46];
    char     dst_ip[46];
    char     dst_country[8];
    uint16_t src_port;
    uint16_t dst_port;
    uint8_t  protocol;
    uint64_t bytes_up;
    uint64_t bytes_down;
    double   ratio;
    char     message[256];
    int64_t  ts_sec;
} ev_geo_t;

typedef struct {
    char     src_ip[46];
    char     dst_ip[46];
    uint16_t src_port;
    uint16_t dst_port;
    uint8_t  protocol;
    char     domain[256];
    char     domain_group[128];
    char     source_type[32];
    char     focus_tag[128];
    int64_t  ts_sec;
} ev_domain_t;    /* 用于 EV_DOMAIN_ACCESS / EV_FOCUS_SITE */

typedef struct {
    char     src_ip[46];
    char     dst_ip[46];
    uint16_t src_port;
    uint16_t dst_port;
    uint8_t  protocol;
    char     domain[256];
    char     source_type[32];
    char     threat_type[128];
    char     intel_source[128];
    int64_t  ts_sec;
    /* ---- Phase 4/5 扩展字段（v2 表使用；旧消费者不读这些字段）---- */
    uint8_t  v2_match_field;   /* 1=ip 2=domain 3=sni 4=ja3 5=url */
    uint8_t  v2_match_mode;    /* 1=exact 2=cidr 3=wildcard */
    uint8_t  v2_confidence;    /* 0..100 */
    uint8_t  v2_tlp;           /* 0=white 1=green 2=amber 3=red */
    char     v2_stix_id[64];   /* OpenCTI indicator id，可空 */
    char     v2_l7_proto[32];  /* 会话 L7 协议名（HTTPS/DNS/...），可空 */
} ev_threat_t;

typedef struct {
    char     domain[256];
    char     domain_group[128];
    char     src_ip[46];
    int64_t  ts_sec;
} ev_site_access_t;

/* EV_IDS —— ET Open / Suricata 规则引擎命中（engine_ids.so）。
 * 和 ev_threat_t 的区别：ev_threat 是 IOC 层面（IP/domain/url 原子指标），
 * ev_ids 是规则层面（URI/payload 模式），粒度更细，粘附到具体 CVE / sid。*/
typedef struct {
    char     src_ip[46];
    char     dst_ip[46];
    uint16_t src_port;
    uint16_t dst_port;
    uint8_t  protocol;       /* 固定 6 (TCP)，预留 UDP/17 */
    char     host[256];      /* HTTP Host 头 */
    char     uri[512];       /* HTTP 请求 URI */
    char     method[16];     /* GET/POST/... */
    int32_t  sid;            /* Suricata 规则 sid */
    char     msg[256];       /* 规则 msg */
    char     cve[24];        /* CVE-YYYY-NNNN，可空 */
    char     rule_source[64];/* 规则所属 .rules 文件名 */
    char     matched_needle[128]; /* 实际触发规则的 URI 子串 */
    int64_t  ts_sec;
} ev_ids_t;

typedef struct {
    char     src_ip[46];
    char     dst_ip[46];
    char     session_id[128];
    uint8_t  icmp_type;
    uint8_t  icmp_code;
    uint16_t identifier;
    uint16_t seq_num;
    uint16_t payload_len;
    char     payload_hex[520];   /* 256 字节 hex = 512 字符 + 终止符 */
    char     payload_text[260];
    int64_t  ts_sec;
} ev_icmp_t;

typedef struct {
    char     src_ip[46];
    char     dst_ip[46];
    uint16_t src_port;
    uint16_t dst_port;
    char     detected_type[64];
    char     detection_method[64];
    char     protocol_name[32];
    char     method[16];
    char     host[256];
    char     path[256];
    char     upgrade[64];
    uint8_t  score;
    double   avg_entropy;
    double   dominant_ratio;
    uint64_t packet_count;
    uint64_t packets_ab;
    uint64_t packets_ba;
    uint64_t bytes_ab;
    uint64_t bytes_ba;
    uint8_t  saw_connect;
    uint8_t  saw_ws_upgrade;
    uint8_t  saw_proxy_auth;
    uint8_t  saw_path_hint;
    int64_t  ts_sec;
} ev_v2ray_t;

typedef struct {
    char     src_ip[46];
    char     dst_ip[46];
    uint16_t src_port;
    uint16_t dst_port;
    uint8_t  transport_proto;     /* 6 = TCP, 17 = UDP */
    uint8_t  state;               /* SS_STATE_* */
    uint8_t  confidence;          /* 0..100 */
    uint8_t  path_type;           /* SS_PATH_* */
    uint16_t score;
    uint32_t reason_mask;
    double   avg_entropy;
    double   max_entropy;
    uint64_t packets_ab;
    uint64_t packets_ba;
    uint64_t bytes_ab;
    uint64_t bytes_ba;
    int64_t  first_seen_ts;
    int64_t  ts_sec;
} ev_shadowsocks_t;

/* ------------------------------------------------------------------ */
/*  ring 中传递的元素（固定大小，从 mempool 分配，无动态分配）            */
/* ------------------------------------------------------------------ */

typedef union {
    ev_http_t        http;
    ev_https_t       https;
    ev_tls_t         tls;
    ev_ja3_t         ja3;
    ev_simple_t      simple;   /* ftp/smtp/wireguard/pop3/imap/rtsp/ipsecvpn/openvpn */
    ev_ssh_t         ssh;
    ev_quic_t        quic;
    ev_dns_t         dns;
    ev_dtls_t        dtls;
    ev_tls_infra_t   tls_infra;
    ev_rdp_t         rdp;
    ev_rtmp_t        rtmp;
    ev_smb_t         smb;
    ev_geo_t         geo;
    ev_domain_t      domain;   /* domain_access / focus_site */
    ev_threat_t      threat;
    ev_site_access_t site_access;
    ev_icmp_t        icmp;
    ev_v2ray_t       v2ray;
    ev_ids_t         ids;
    ev_shadowsocks_t shadowsocks;
} ev_payload_u;

typedef struct {
    event_type_t  type;
    ev_payload_u  payload;
} event_task_t;

/* ------------------------------------------------------------------ */
/*  公共 API                                                             */
/* ------------------------------------------------------------------ */

#ifdef CAPTURE_ENABLE_DPDK
/**
 * 初始化（由 main.c 在 init_pipeline_rings() 中调用）。
 * @param ring  已创建的 event ring（MPSC）
 * @param pool  已创建的 event_task_t mempool
 * @return 0 成功，-1 失败
 */
int ch_event_init(struct rte_ring *ring, struct rte_mempool *pool);
#endif

/**
 * 非阻塞入队。ring 满或 mempool 耗尽时丢弃并计数（不阻塞 worker lcore）。
 * 单线程模式（ring == NULL）时 fallback 到同步 ch_insert_*()。
 *
 * @param type         事件类型
 * @param payload      指向栈上 ev_*_t 的指针
 * @param payload_size sizeof(ev_*_t)
 */
void ch_event_enqueue(event_type_t type,
                      const void  *payload,
                      size_t       payload_size);

/**
 * 返回自启动以来因 ring/pool 满而丢弃的事件数（诊断用）。
 */
uint64_t ch_event_get_dropped(void);

/* ------------------------------------------------------------------ */
/*  便捷宏：与原 ch_insert_* 签名对应，一行替换                           */
/* ------------------------------------------------------------------ */

#define CH_EV_HTTP(src_ip,dst_ip,sp,dp,meth,uri,host,ua,ct,pt,ts) \
    do { ev_http_t _e = {0}; \
         snprintf(_e.src_ip,sizeof(_e.src_ip),"%s",(src_ip)); \
         snprintf(_e.dst_ip,sizeof(_e.dst_ip),"%s",(dst_ip)); \
         _e.src_port=(sp); _e.dst_port=(dp); \
         snprintf(_e.method,sizeof(_e.method),"%s",(meth)); \
         snprintf(_e.uri,sizeof(_e.uri),"%s",(uri)); \
         snprintf(_e.host,sizeof(_e.host),"%s",(host)); \
         snprintf(_e.user_agent,sizeof(_e.user_agent),"%s",(ua)); \
         snprintf(_e.content_type,sizeof(_e.content_type),"%s",(ct)); \
         _e.protocol_type=(pt); _e.ts_sec=(ts); \
         ch_event_enqueue(EV_HTTP,&_e,sizeof(_e)); } while(0)

#define CH_EV_SIMPLE(ev_type,src_ip,dst_ip,sp,dp,proto,pname,ts) \
    do { ev_simple_t _e = {0}; \
         snprintf(_e.src_ip,sizeof(_e.src_ip),"%s",(src_ip)); \
         snprintf(_e.dst_ip,sizeof(_e.dst_ip),"%s",(dst_ip)); \
         _e.src_port=(sp); _e.dst_port=(dp); \
         _e.protocol=(proto); \
         snprintf(_e.protocol_name,sizeof(_e.protocol_name),"%s",(pname)); \
         _e.ts_sec=(ts); \
         ch_event_enqueue((ev_type),&_e,sizeof(_e)); } while(0)

/* 带 extra 字段（FTP file_name / SMTP-POP3 email） */
#define CH_EV_SIMPLE_EX(ev_type,src_ip,dst_ip,sp,dp,proto,pname,extra_str,ts) \
    do { ev_simple_t _e = {0}; \
         snprintf(_e.src_ip,sizeof(_e.src_ip),"%s",(src_ip)); \
         snprintf(_e.dst_ip,sizeof(_e.dst_ip),"%s",(dst_ip)); \
         _e.src_port=(sp); _e.dst_port=(dp); \
         _e.protocol=(proto); \
         snprintf(_e.protocol_name,sizeof(_e.protocol_name),"%s",(pname)); \
         snprintf(_e.extra,sizeof(_e.extra),"%s",(extra_str)?(extra_str):""); \
         _e.ts_sec=(ts); \
         ch_event_enqueue((ev_type),&_e,sizeof(_e)); } while(0)

#define CH_EV_V2RAY(src_ip,dst_ip,sp,dp,detected_type_val,detection_method_val,protocol_name_val,method_val,host_val,path_val,upgrade_val,score_val,avg_entropy_val,dominant_ratio_val,packet_count_val,packets_ab_val,packets_ba_val,bytes_ab_val,bytes_ba_val,saw_connect_val,saw_ws_upgrade_val,saw_proxy_auth_val,saw_path_hint_val,ts_val) \
    do { ev_v2ray_t _e = {0}; \
         snprintf(_e.src_ip,sizeof(_e.src_ip),"%s",(src_ip)); \
         snprintf(_e.dst_ip,sizeof(_e.dst_ip),"%s",(dst_ip)); \
         _e.src_port=(sp); _e.dst_port=(dp); \
         snprintf(_e.detected_type,sizeof(_e.detected_type),"%s",(detected_type_val)); \
         snprintf(_e.detection_method,sizeof(_e.detection_method),"%s",(detection_method_val)); \
         snprintf(_e.protocol_name,sizeof(_e.protocol_name),"%s",(protocol_name_val)); \
         snprintf(_e.method,sizeof(_e.method),"%s",(method_val)); \
         snprintf(_e.host,sizeof(_e.host),"%s",(host_val)); \
         snprintf(_e.path,sizeof(_e.path),"%s",(path_val)); \
         snprintf(_e.upgrade,sizeof(_e.upgrade),"%s",(upgrade_val)); \
         _e.score=(score_val); _e.avg_entropy=(avg_entropy_val); _e.dominant_ratio=(dominant_ratio_val); \
         _e.packet_count=(packet_count_val); _e.packets_ab=(packets_ab_val); _e.packets_ba=(packets_ba_val); \
         _e.bytes_ab=(bytes_ab_val); _e.bytes_ba=(bytes_ba_val); \
         _e.saw_connect=(saw_connect_val); _e.saw_ws_upgrade=(saw_ws_upgrade_val); \
         _e.saw_proxy_auth=(saw_proxy_auth_val); _e.saw_path_hint=(saw_path_hint_val); \
         _e.ts_sec=(ts_val); \
         ch_event_enqueue(EV_V2RAY,&_e,sizeof(_e)); } while(0)

#define CH_EV_SHADOWSOCKS(src_ip_v,dst_ip_v,sp,dp,proto_v,state_v,confidence_v,path_type_v,score_v,reason_mask_v,avg_entropy_v,max_entropy_v,packets_ab_v,packets_ba_v,bytes_ab_v,bytes_ba_v,first_seen_ts_v,ts_v) \
    do { ev_shadowsocks_t _e = {0}; \
         snprintf(_e.src_ip,sizeof(_e.src_ip),"%s",(src_ip_v)); \
         snprintf(_e.dst_ip,sizeof(_e.dst_ip),"%s",(dst_ip_v)); \
         _e.src_port=(sp); _e.dst_port=(dp); \
         _e.transport_proto=(proto_v); \
         _e.state=(state_v); _e.confidence=(confidence_v); \
         _e.path_type=(path_type_v); \
         _e.score=(score_v); _e.reason_mask=(reason_mask_v); \
         _e.avg_entropy=(avg_entropy_v); _e.max_entropy=(max_entropy_v); \
         _e.packets_ab=(packets_ab_v); _e.packets_ba=(packets_ba_v); \
         _e.bytes_ab=(bytes_ab_v); _e.bytes_ba=(bytes_ba_v); \
         _e.first_seen_ts=(first_seen_ts_v); _e.ts_sec=(ts_v); \
         ch_event_enqueue(EV_SHADOWSOCKS,&_e,sizeof(_e)); } while(0)

#ifdef __cplusplus
}
#endif

#endif /* CH_EVENT_H */
