#ifndef SESSION_H
#define SESSION_H

#include <stdint.h>
#include <time.h>

#ifdef __cplusplus
extern "C" {
#endif

/* IPv4/IPv6 地址联合体 */
typedef union ip_addr {
    uint32_t v4;           /* IPv4 地址（网络字节序） */
    uint8_t  v6[16];       /* IPv6 地址（128 位）     */
} ip_addr_t;

/* 会话五元组键（规范化）- 支持 IPv4/IPv6 双栈 */
typedef struct session_key {
    ip_addr_t ip_low;      /* 较小的IP地址 */
    ip_addr_t ip_high;     /* 较大的IP地址 */
    uint16_t port_low;     /* 较小的端口 */
    uint16_t port_high;    /* 较大的端口 */
    uint8_t  protocol;     /* 协议类型 */
    uint8_t  ip_version;   /* 4 = IPv4, 6 = IPv6 */
} session_key_t;

/* 扩展字段类型标识 */
typedef enum {
    EXT_FIELD_NONE = 0,
    EXT_FIELD_HTTP,
    EXT_FIELD_DNS,
    EXT_FIELD_DTLS,
    EXT_FIELD_RDP,
    EXT_FIELD_RTMP,
    EXT_FIELD_SMB,
    EXT_FIELD_TLS_INFRA,
    EXT_FIELD_TLS,

    EXT_FIELD_QUIC,
    EXT_FIELD_POP3,
    EXT_FIELD_IMAP,
    EXT_FIELD_RTSP,

    EXT_FIELD_SSH,
    EXT_FIELD_WIREGUARD,
    EXT_FIELD_OPENVPN,
    EXT_FIELD_IPSECVPN,
    EXT_FIELD_FTP_SMTP,
    EXT_FIELD_HTTPS,
    EXT_FIELD_V2RAY,
    EXT_FIELD_SHADOWSOCKS,

    EXT_FIELD_CUSTOM
} ext_field_type_t;

/* 流量统计 */
typedef struct session_stats {
    uint64_t packets_ab;   /* A->B 包数 */
    uint64_t packets_ba;   /* B->A 包数 */
    uint64_t bytes_ab;     /* A->B 字节数 */
    uint64_t bytes_ba;     /* B->A 字节数 */
} session_stats_t;

typedef struct geo_summary {
    char dst_country[8];
    uint8_t is_cross_border;
    uint8_t sensitive_country_hit;
    uint8_t abnormal_upload_hit;
    uint8_t geo_resolved;
    uint8_t cross_border_emitted;
    uint8_t sensitive_emitted;
    uint8_t abnormal_emitted;
    uint8_t spike_emitted;
    uint8_t reserved[3];

    uint64_t last_spike_bytes_up;
    time_t last_spike_check;

    double upload_download_ratio;
    uint64_t bytes_up;
    uint64_t bytes_down;
    time_t last_geo_update;
    time_t first_cross_border_ts;   /* 首次识别为跨境的时间，用来延迟 emit 以凑齐 bytes_down */
} geo_summary_t;

/* 多候选协议状态 */
#define SESSION_MAX_CANDIDATES 4

typedef struct session_candidate {
    ext_field_type_t type;
    void *data;
    uint8_t active;
    uint8_t confirmed;
    uint16_t score;
} session_candidate_t;

/* 会话条目
 *
 * 注意：session.c 依赖 session_key_t 的 ABI（偏移量），
 * 所以 session_key_t 必须保持原有字段不变。
 * 以下字段中：
 *   - ip_version / src_ip6 / dst_ip6 为新增（ABI 无关，仅在 session_entry_t 中追加）
 *   - tunnel_signal 为新增指针（用于跨插件共享信号）
 */
typedef struct session_entry {
    /* 五元组键（保持原有 ABI） */
    session_key_t key;

    /* ---- 以下为新增字段（向后追加，保持 ABI 兼容） ---- */

    /* IP 版本及原始地址 */
    uint8_t  ip_version;       /* IP 版本：4 或 6 */
    uint32_t src_ip;           /* IPv4 原始源 IP（网络字节序） */
    uint32_t dst_ip;           /* IPv4 原始目的 IP（网络字节序） */
    uint8_t  src_ip6[16];      /* IPv6 原始源地址（网络字节序） */
    uint8_t  dst_ip6[16];      /* IPv6 原始目的地址（网络字节序） */

    /* 原始端口（独立于 key，方便直接访问） */
    uint16_t src_port;         /* 原始源端口 */
    uint16_t dst_port;         /* 原始目的端口 */

    /* 时间信息 */
    time_t create_time;         /* 创建时间 */
    time_t last_seen;           /* 最后活跃时间 */

    /* 流量统计 */
    session_stats_t stats;

    /* 扩展字段（协议特征） */
    ext_field_type_t ext_type;
    void *ext_data;

    /* 多候选状态（用于冲突仲裁） */
    session_candidate_t candidates[SESSION_MAX_CANDIDATES];
    uint8_t ext_locked;        /* 是否已锁定（由候选仲裁决定） */

    /* 翻墙检测信号 - 由 engine_tunnel.so / engine_auxiliary.so 填充 */
    void *tunnel_signal;
    geo_summary_t *geo_summary;

    /* TLS 证书流重组缓冲（仅 main.c 内部使用，定义为不透明指针以避免循环依赖）。
     * 独立于 ext_data 槽位，避免被 engine_tls_infra 等插件占用后导致证书重组被跳过。
     * 实际类型见 main.c 中的 tls_stream_buf_t。 */
    void *tls_stream_buf;

    /* 哈希表链表 */
    struct session_entry *next;
} session_entry_t;

/* 会话管理器 */
typedef struct session_manager {
    session_entry_t **hash_table;
    uint32_t table_size;
    uint32_t session_count;
    uint32_t timeout;      /* 会话超时时间（秒） */
} session_manager_t;

typedef void (*session_expire_callback_t)(const session_entry_t *session, void *user_data);


/* ================= SSH 会话状态 ================= */

typedef struct ssh_flags {
    uint8_t version_seen_ab : 1;
    uint8_t version_seen_ba : 1;
    uint8_t handshake_complete : 1;
    uint8_t encrypted_phase : 1;
} ssh_flags_t;

#define SSH_BANNER_MAX_LEN 128

typedef struct ssh_ext_data {
    uint32_t magic;
    ssh_flags_t flags;
    uint8_t kexinit_seen : 1;
    uint8_t newkeys_seen : 1;
    uint8_t ssh2_seen : 1;
    uint8_t ssh1_seen : 1;
    uint8_t counted : 1;
    uint8_t reserved : 3;
    char banner_ab[SSH_BANNER_MAX_LEN];
    char banner_ba[SSH_BANNER_MAX_LEN];
    uint8_t banner_ab_len;
    uint8_t banner_ba_len;
} ssh_ext_data_t;


/* ---- WireGuard ---- */
typedef enum {
    WG_FLOW_INIT = 0,
    WG_FLOW_HS_INIT,
    WG_FLOW_HS_RESP,
    WG_FLOW_ESTABLISHED
} WireGuardFlowState;

// typedef struct wireguard_ext_data {
//     uint32_t magic;
//     WireGuardFlowState wireguard_state;

//     struct {
//         uint8_t hs_init_seen_ab : 1;     // A->B 看到 type=1
//         uint8_t hs_init_seen_ba : 1;     // B->A 看到 type=1
//         uint8_t hs_resp_seen_ab : 1;     // A->B 看到 type=2
//         uint8_t hs_resp_seen_ba : 1;     // B->A 看到 type=2
//         uint8_t cookie_seen : 1;         // 看到 type=3
//         uint8_t data_seen : 1;           // 看到 type=4
//         uint8_t reserved_wg : 2;
//     } wireguard_flags;

//     uint8_t counted : 1;                 // 是否已按“确认会话”计数
//     uint8_t reserved3 : 7;
// } wireguard_ext_data_t;

typedef struct wireguard_ext_data {
    uint32_t magic;
    /* nDPI风格状态 */
    uint8_t stage;              /* 0,1,2,3,4,5 */
    uint8_t confirmed : 1;
    uint8_t excluded  : 1;
    uint8_t counted   : 1;
    uint8_t is_tunnelbear : 1;
    uint8_t reserved  : 4;

    uint32_t peer_index[2];     /* A->B / B->A 保存 sender 或 receiver index */

    WireGuardFlowState wireguard_state;
    struct {
        uint8_t hs_init_seen_ab : 1;
        uint8_t hs_init_seen_ba : 1;
        uint8_t hs_resp_seen_ab : 1;
        uint8_t hs_resp_seen_ba : 1;
        uint8_t cookie_seen : 1;
        uint8_t data_seen : 1;
        uint8_t reserved_wg : 2;
    } wireguard_flags;
} wireguard_ext_data_t;


/* ---- OpenVPN ---- */
typedef enum {
    OVPN_FLOW_INIT = 0,
    OVPN_FLOW_RESET_SEEN,
    OVPN_FLOW_CONTROL_SEEN,
    OVPN_FLOW_ESTABLISHED
} OpenVPNFlowState;

// typedef struct openvpn_ext_data {
//     uint32_t magic;
//     OpenVPNFlowState state;

//     struct {
//         uint8_t hard_reset_seen_ab : 1;
//         uint8_t hard_reset_seen_ba : 1;
//         uint8_t control_seen_ab : 1;
//         uint8_t control_seen_ba : 1;
//         uint8_t data_seen : 1;
//         uint8_t reserved : 3;
//     } openvpn_flags;

//     uint8_t is_tcp : 1;     /* 可选：标记该会话按 TCP 形态识别 */
//     uint8_t counted : 1;    /* 可选：确认后只计数一次 */
//     uint8_t reserved2 : 6;
// } openvpn_ext_data_t;

#define OVPN_MAX_OPCODES 4

typedef struct openvpn_ext_data {
    uint32_t magic;


    /* 总状态：0=继续，1=排除，2=确认 */
    uint8_t standard_state;
    uint8_t heur_state;

    uint8_t is_tcp : 1;
    uint8_t counted : 1;
    uint8_t excluded : 1;
    uint8_t confirmed : 1;
    uint8_t reserved : 4;

    /* 当前方向首包定义的 A/B */
    uint8_t pkt_cnt[2];

    /* nDPI 标准算法关键字段 */
    uint8_t session_id[2][8];        /* 本方向 session id */
    uint8_t remote_session_id[2][8]; /* 可选，解析到就存 */
    uint8_t session_id_valid[2];

    /* HMAC / 解析状态 */
    int8_t hmac_size;                /* -1 unknown / 0 /16 /20 */
    uint8_t last_opcode[2];

    /* TCP OpenVPN 分帧 */
    uint16_t tcp_missing_bytes[2];

    /* heur-opcode 状态 */
    uint8_t heur_num_msgs;
    uint8_t heur_resets[2];
    uint8_t heur_codes[OVPN_MAX_OPCODES];
    uint8_t heur_codes_num;

    /* 兼容你现有统计标志 */
    OpenVPNFlowState state;
    struct {
        uint8_t hard_reset_seen_ab : 1;
        uint8_t hard_reset_seen_ba : 1;
        uint8_t control_seen_ab : 1;
        uint8_t control_seen_ba : 1;
        uint8_t data_seen : 1;
        uint8_t reserved2 : 3;
    } openvpn_flags;
} openvpn_ext_data_t;

#define OVPN_EXT_MAGIC 0x4F56584Fu /* 'OVXO' */


/* ---- FTP/SMTP ---- */
#define FTP_FILENAME_MAX_LEN 256

typedef struct ftp_smtp_ext_data {
    uint32_t magic;
    struct {
        uint8_t awaiting_220_resolution : 1;
        uint8_t ftp_command_seen : 1;
        uint8_t smtp_command_seen : 1;
        uint8_t ftp_confirmed : 1;
        uint8_t smtp_confirmed : 1;
        uint8_t server_220_seen : 1;
        uint8_t reserved : 2;
    } ftp_smtp_flags;
    uint8_t ftp_counted : 1;
    uint8_t smtp_counted : 1;
    uint8_t smtp_email_emitted : 1;
    uint8_t reserved2 : 5;
    /* nDPI-style lightweight state */
    uint8_t ftp_last_cmd_dir;   /* 0/1 */
    uint8_t ftp_waiting_reply;  /* bool */
    uint8_t smtp_cmd_count;
    uint8_t smtp_rsp_count;
    uint8_t smtp_last_cmd_dir;
    uint8_t smtp_waiting_reply;
    uint32_t smtp_seen_mask;
    char ftp_file_name[FTP_FILENAME_MAX_LEN];
    char ftp_last_emitted_file_name[FTP_FILENAME_MAX_LEN];
    char smtp_email[256];
} ftp_smtp_ext_data_t;

#define FTP_SMTP_EXT_MAGIC 0x46533231u /* 'FS21' */


/* ---- POP3 ---- */
#define POP3_LINE_BUF_MAX 1024

typedef struct pop3_ext_data {
    uint32_t magic;        /* 'P3X1' */
    uint8_t confirmed : 1;
    uint8_t counted   : 1;
    uint8_t email_emitted : 1;
    uint8_t server_dir_known : 1;
    uint8_t server_dir : 1;      /* 0=A->B server, 1=B->A server */
    uint8_t auth_done : 1;
    uint8_t transaction_seen : 1;
    uint8_t reserved  : 1;
    uint8_t auth_login_state;    /* 0=none,1=wait username,2=wait password */
    uint8_t detection_packets;   /* inspected packets before confirmation */
    uint8_t client_cmd_lines;    /* recognized client commands before confirmation */
    uint8_t server_resp_lines;   /* recognized server responses before confirmation */
    uint16_t seen_mask;          /* evidence bits for conservative confirmation */
    uint16_t line_len[2];      /* A->B / B->A 行缓冲长度 */
    char line_buf[2][POP3_LINE_BUF_MAX];
    char email[256];
} pop3_ext_data_t;

#define POP3_EXT_MAGIC 0x50335831u /* 'P3X1' */


/* ---- QUIC ---- */
typedef struct quic_ext_data {
    uint32_t magic;       /* 'QIC1' */
    uint8_t confirmed      : 1;
    uint8_t counted        : 1;
    uint8_t zero_rtt_seen  : 1;
    uint8_t state_armed    : 1; /* 缓存状态机制开启后开始提取/存储 QUIC 内部特征 */
    uint8_t reserved       : 4;

    uint8_t candidate_packets; /* 缓存数 */
    uint8_t ab_seen;           /* A->B 是否缓存过 */
    uint8_t ba_seen;           /* B->A 是否缓存过 */
    uint8_t sh_stage;          /* nDPI 风格 short-header 解析阶段 */

    uint8_t last_header_form;  /* 0=unknown,1=long,2=short,3=gquic,4=version_neg */
    uint8_t last_long_type;    /* IETF long header packet type */
    uint8_t packet_number_len; /* 1..4 */
    uint8_t dcid_len;          /* 0..20 */
    uint8_t scid_len;          /* 0..20 */

    uint32_t packet_number;    /* 按线速报文中的截断值保存 */
    uint32_t quic_version;     /* 最近一次识别到的 QUIC version */
    uint8_t zero_rtt_found;    /* 在 Initial 之前看到 0-RTT */
    uint8_t pending_vn_pair;   /* 已看到 forcing VN，等待服务端 VN */
    uint8_t seen_valid_vn;     /* 已看到合法 VN 报文 */
    uint8_t heuristic_client_last_byte;
    uint8_t heuristic_server_cid[8];
    uint8_t quic_confidence;   /* 0..100 */
    uint8_t is_doq;            /* ALPN=doq */
    uint8_t crypto_ch_complete;/* QUIC CRYPTO 重组出的 CH 是否完整 */
    uint8_t orig_dcid_len;     /* 首个 client initial 的 DCID 长度 */
    uint8_t dcid[20];          /* Destination Connection ID */
    uint8_t scid[20];          /* Source Connection ID */
    uint8_t orig_dcid[20];     /* 首个 client initial 的 DCID（用于 Initial 解密） */
    uint8_t crypto_bitmap[512];/* 最多 4096B 的位图 */
    uint8_t crypto_buf[4096];  /* ClientHello 重组缓冲 */
    uint16_t crypto_buf_len;   /* 当前最大写入边界 */
    char sni[256];
    char alpn[128];
    char detect_reason[64];
} quic_ext_data_t;

#define QUIC_EXT_MAGIC 0x51494331u /* 'QIC1' */

/* ================= IMAP 会话锁定（确认后整条流都归为 IMAP） ================= */
typedef struct imap_ext_data {
    uint32_t magic;        /* 'IMX1' */
    uint8_t confirmed : 1;
    uint8_t counted   : 1;
    uint8_t reserved  : 6;
} imap_ext_data_t;

#define IMAP_EXT_MAGIC 0x494D5831u /* 'IMX1' */


/* ---- RTSP ---- */
typedef struct rtsp_ext_data {
    uint32_t magic;        /* 'RSX1' */
    uint8_t confirmed : 1;
    uint8_t counted   : 1;
    uint8_t reserved  : 6;
} rtsp_ext_data_t;

#define RTSP_EXT_MAGIC 0x52535831u /* 'RSX1' */



/* ---- V2Ray family ---- */
#define V2RAY_EXT_MAGIC 0x56325259u /* 'V2RY' */

typedef struct v2ray_ext_data {
    uint32_t magic;
    uint8_t confirmed : 1;
    uint8_t counted   : 1;
    uint8_t excluded   : 1;
    uint8_t saw_connect : 1;
    uint8_t saw_ws_upgrade : 1;
    uint8_t saw_proxy_auth : 1;
    uint8_t saw_path_hint : 1;
    uint8_t reserved : 1;

    uint8_t score;
    uint8_t request_seen_ab : 1;
    uint8_t request_seen_ba : 1;
    uint8_t reserved2 : 6;

    uint64_t packet_count;
    uint64_t packets_ab;
    uint64_t packets_ba;
    uint64_t bytes_ab;
    uint64_t bytes_ba;
    uint64_t entropy_samples;
    double entropy_sum;
    double entropy_max;
    uint32_t dns_packet_count;
    uint32_t dns_large_count;
    time_t first_seen_ts;
    time_t last_seen_ts;

    char method[12];
    char host[128];
    char path[128];
    char upgrade[32];
} v2ray_ext_data_t;


/* ---- Shadowsocks proxy probabilistic identifier ---- */
#define SS_EXT_MAGIC 0x53534B53u /* 'SSKS' */

/* state values */
#define SS_STATE_INIT      0
#define SS_STATE_CANDIDATE 1
#define SS_STATE_CONFIRMED 2
#define SS_STATE_EXCLUDED  3

/* path_type values */
#define SS_PATH_UNKNOWN 0
#define SS_PATH_TCP     1
#define SS_PATH_UDP     2

/* reason_mask bits — explain why a session was flagged */
#define SS_R_HIGH_ENTROPY      (1u << 0)
#define SS_R_NO_KNOWN_HEADER   (1u << 1)
#define SS_R_LEN_PATTERN       (1u << 2)
#define SS_R_BIDIR_BALANCE     (1u << 3)
#define SS_R_PROXY_BURST       (1u << 4)
#define SS_R_NOT_TLS           (1u << 5)
#define SS_R_NOT_QUIC          (1u << 6)
#define SS_R_PORT_HINT         (1u << 7)
#define SS_R_NOT_HANDSHAKE     (1u << 8)
#define SS_R_LEN_RANDOM_VAR    (1u << 9)

/* exclusion reasons */
#define SS_X_NONE      0
#define SS_X_TLS       1
#define SS_X_QUIC      2
#define SS_X_WIREGUARD 3
#define SS_X_DNS_PLAIN 4
#define SS_X_HTTP      5
#define SS_X_SSH       6
#define SS_X_OTHER     7

#define SS_FIRST_LEN_TRACK 8

typedef struct shadowsocks_ext_data {
    uint32_t magic;            /* SS_EXT_MAGIC */
    uint8_t  state;            /* SS_STATE_* */
    uint8_t  confidence;       /* 0..100 */
    uint16_t score;            /* accumulated score, 0..1000 */
    uint32_t reason_mask;      /* SS_R_* bits */

    uint8_t  path_type;        /* SS_PATH_* */
    uint8_t  excluded_by;      /* SS_X_* */
    uint8_t  counted;          /* CH event already emitted */
    uint8_t  first_len_count;  /* number of first packet lengths recorded */

    uint16_t first_lens[SS_FIRST_LEN_TRACK]; /* first N packets payload length */
    uint16_t reserved;

    uint64_t packets_ab;
    uint64_t packets_ba;
    uint64_t bytes_ab;
    uint64_t bytes_ba;

    double   entropy_sum;
    double   entropy_max;
    uint32_t entropy_samples;
    uint32_t pkts_inspected;

    int64_t  first_seen_ts;
    int64_t  last_seen_ts;
} shadowsocks_ext_data_t;


/* ---- DNS ---- */
#define DNS_MAX_ANSWERS   8
#define DNS_VALUE_MAX_LEN 256
#define DNS_IP_STR_LEN    46
#define DNS_EXT_MAGIC     0x444E5331u  /* 'DNS1' */

typedef struct dns_answer_item {
    uint16_t type;
    uint16_t rdlen;
    uint32_t ttl;
    char     value[DNS_VALUE_MAX_LEN];
} dns_answer_item_t;

typedef struct dns_ext_data {
    uint32_t magic;
    uint8_t confirmed : 1;
    uint8_t is_response : 1;
    uint8_t has_query : 1;
    uint8_t has_answer : 1;
    uint8_t counted : 1;
    uint8_t reserved : 3;

    uint8_t pending_valid;

    uint16_t txid;
    uint16_t flags;
    uint16_t qtype;
    uint16_t qclass;
    uint16_t ancount;
    uint16_t nscount;
    uint16_t arcount;
    uint16_t rcode;

    char client_ip[DNS_IP_STR_LEN];
    char resolver_ip[DNS_IP_STR_LEN];
    char qname[256];

    uint16_t pending_txid;
    uint16_t pending_qtype;
    char pending_qname[256];

    uint8_t answer_count;
    dns_answer_item_t answers[DNS_MAX_ANSWERS];
    uint64_t last_update_ts;
} dns_ext_data_t;


/* ---- DTLS ---- */
#define DTLS_EXT_MAGIC 0x44544C53u /* 'DTLS' */

typedef struct dtls_ext_data {
    uint32_t magic;                  /* DTLS_EXT_MAGIC */

    uint8_t confirmed : 1;           /* 已确认为 DTLS */
    uint8_t counted : 1;             /* 预留：是否已计数 */
    uint8_t client_hello_seen : 1;   /* 看到 ClientHello */
    uint8_t server_hello_seen : 1;   /* 看到 ServerHello */
    uint8_t hello_verify_seen : 1;   /* 看到 HelloVerifyRequest */
    uint8_t certificate_seen : 1;    /* 看到 Certificate */
    uint8_t appdata_seen : 1;        /* 看到 Application Data */
    uint8_t alert_seen : 1;          /* 看到 Alert */
    uint8_t ccs_seen : 1;            /* 看到 ChangeCipherSpec */
    uint8_t other_handshake_seen : 1;/* 看到其他 Handshake 类型 */

    uint16_t version;                /* DTLS Version: 0xFEFF/0xFEFD/0xFEFC */
    uint8_t last_record_type;        /* 20/21/22/23 */
    uint8_t last_handshake_type;     /* 仅当 record_type=22 时有效 */

    uint16_t epoch;                  /* DTLS Record Header Epoch */
    uint16_t last_record_len;        /* 当前 Record 长度 */
    uint64_t last_update_ts;         /* 最近更新时间 */
} dtls_ext_data_t;


/* ---- TLS Infra ---- */
#define TLS_INFRA_EXT_MAGIC 0x54494E46u /* 'TINF' */

#ifndef TLS_SNI_MAX_LEN
#define TLS_SNI_MAX_LEN 256
#endif
#ifndef TLS_ALPN_MAX_LEN
#define TLS_ALPN_MAX_LEN 128
#endif

typedef struct tls_infra_ext_data {
    uint32_t magic;
    uint8_t confirmed : 1;
    uint8_t is_doh : 1;
    uint8_t is_dot : 1;
    uint8_t is_ech : 1;
    uint8_t is_dns_tunnel : 1;
    uint8_t counted : 1;
    uint8_t reserved : 2;
    uint8_t doh_event_emitted; 
    uint8_t dot_event_emitted;
    uint8_t ech_event_emitted;
    uint8_t tunnel_event_emitted;

    uint16_t tls_record_version;     /* TLS Record Version */
    uint8_t last_record_type;        /* 22=Handshake, 23=Application Data */
    uint16_t last_record_len;        /* 当前 Record Header 声明长度 */

    uint64_t first_seen_ts;          /* 首次命中时间 */
    uint64_t last_update_ts;         /* 最近更新时间 */
    uint32_t packet_count;           /* 当前会话累计命中包数 */

    char sni[TLS_SNI_MAX_LEN];       /* 提取到的 SNI */
    char alpn[TLS_ALPN_MAX_LEN];     /* 提取到的 ALPN 列表字符串 */
    char last_detection[64];         /* 最近一次命中的识别类型 */
} tls_infra_ext_data_t;


/* ---- RDP ---- */
#define RDP_EXT_MAGIC 0x52445031u /* 'RDP1' */

typedef struct rdp_ext_data {
    uint32_t magic;
    uint8_t confirmed : 1;
    uint8_t counted : 1;
    uint8_t cr_seen : 1;
    uint8_t cc_seen : 1;
    uint8_t cookie_seen : 1;
    uint8_t security_seen : 1;
    uint8_t reserved : 2;
    uint8_t last_tpdu_type;
    uint32_t requested_protocols;
    char cookie[128];
    uint64_t last_update_ts;
} rdp_ext_data_t;


/* RTMP / SMB info structs */
typedef struct rtmp_info {
    uint32_t handshake_count;
    uint32_t command_count;

    uint8_t handshake_seen;
    uint8_t connect_seen;
    uint8_t play_seen;
    uint8_t create_stream_seen;
    uint8_t result_seen;
    uint8_t onstatus_seen;
    uint8_t confirmed;
    uint8_t decision_emitted;
} rtmp_info_t;

typedef struct smb_info {
    uint8_t confirmed;       // 已确认为 SMB 会话
    uint8_t event_emitted;   // 已写入一次会话事件
    uint8_t version;         // 1=SMBv1, 2=SMBv2/v3
    uint8_t reserved;

    uint64_t packet_count;   // 会话内识别到的 SMB 包数
    uint64_t smbv1_count;    // 会话内 SMBv1 包数
    uint64_t smbv2_3_count;  // 会话内 SMBv2/v3 包数
} smb_info_t;

/* ---- HTTPS 会话状态（EXT_FIELD_HTTPS）---- */
#define HTTPS_EXT_MAGIC 0x48545053u  /* 'HTPS' */
typedef struct https_session_data {
    uint32_t magic;
    char     sni[256];        /* 从 ClientHello 提取的 SNI */
    uint16_t tls_version;     /* TLS 版本号（如 0x0303 = TLS 1.2） */
    int64_t  first_seen;      /* 会话首包时间戳（秒）*/
} https_session_data_t;


/* 接口函数 */
session_manager_t* session_manager_create(uint32_t table_size, uint32_t timeout);
void session_manager_destroy(session_manager_t *mgr);

session_entry_t* session_lookup(session_manager_t *mgr,
                                uint32_t src_ip, uint32_t dst_ip,
                                uint16_t src_port, uint16_t dst_port,
                                uint8_t protocol);

session_entry_t* session_create(session_manager_t *mgr,
                                uint32_t src_ip, uint32_t dst_ip,
                                uint16_t src_port, uint16_t dst_port,
                                uint8_t protocol);

int session_update(session_entry_t *session, uint32_t src_ip, uint32_t dst_ip,
                   uint32_t pkt_len);

/* IPv6 会话接口 */
session_entry_t* session_lookup_v6(session_manager_t *mgr,
                                   const uint8_t *src_ip6, const uint8_t *dst_ip6,
                                   uint16_t src_port, uint16_t dst_port,
                                   uint8_t protocol);

session_entry_t* session_create_v6(session_manager_t *mgr,
                                   const uint8_t *src_ip6, const uint8_t *dst_ip6,
                                   uint16_t src_port, uint16_t dst_port,
                                   uint8_t protocol);

int session_update_v6(session_entry_t *session,
                      const uint8_t *src_ip6, const uint8_t *dst_ip6,
                      uint32_t pkt_len);

int session_set_ext_data(session_entry_t *session, ext_field_type_t type, void *data);
void* session_get_ext_data(session_entry_t *session);
geo_summary_t* session_ensure_geo_summary(session_entry_t *session);
geo_summary_t* session_get_geo_summary(session_entry_t *session);
session_candidate_t* session_get_candidate(session_entry_t *session, ext_field_type_t type);
session_candidate_t* session_ensure_candidate(session_entry_t *session, ext_field_type_t type);
void* session_get_candidate_data(session_entry_t *session, ext_field_type_t type);
int session_set_candidate_data(session_entry_t *session, ext_field_type_t type, void *data);
int session_confirm_candidate(session_entry_t *session, ext_field_type_t type);
int session_is_locked(const session_entry_t *session);
void session_free_candidates(session_entry_t *session);
void session_set_expire_callback(session_expire_callback_t callback, void *user_data);

int session_flush_expired(session_manager_t *mgr);
/* 游标式限额摊销版过期清扫（P1 去内联化，消 RX 黑洞）：每次只清一小片、跨调用用 *cursor
 * 续扫全表，单次停顿硬性约束在 time_budget_us 内。详见 session.c 实现处注释。 */
int session_flush_expired_amortized(session_manager_t *mgr, uint32_t *cursor,
                                    uint32_t bucket_scan_cap, uint32_t time_budget_us);
uint32_t session_get_count(session_manager_t *mgr);

#ifdef __cplusplus
}
#endif

#endif /* SESSION_H */
