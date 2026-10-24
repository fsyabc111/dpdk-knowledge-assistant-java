/**
 * @file storage_engine.c
 * @brief 批量存储引擎 - ClickHouse HTTP 实现
 *
 * 使用原始 TCP socket 向 ClickHouse HTTP 接口写入数据。
 * INSERT 使用 JSONEachRow 格式；ReplacingMergeTree 负责按 last_time 去重。
 *
 * @version 3.0
 * @date 2026-03-04
 */

#include "storage_engine.h"
#include "../plugin/plugins/cross_border_types.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <stdarg.h>
#include <inttypes.h>
#include <time.h>

/* POSIX socket */
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <unistd.h>

/* ========== ClickHouse HTTP 辅助 ========== */

/**
 * @brief 动态字符串缓冲
 */
typedef struct {
    char  *data;
    size_t len;
    size_t cap;
} chbuf_t;

static chbuf_t *chbuf_new(size_t initial) {
    chbuf_t *b = malloc(sizeof(chbuf_t));
    if (!b) return NULL;
    b->data = malloc(initial);
    if (!b->data) { free(b); return NULL; }
    b->len = 0;
    b->cap = initial;
    b->data[0] = '\0';
    return b;
}

static void chbuf_free(chbuf_t *b) {
    if (b) { free(b->data); free(b); }
}

static int chbuf_ensure(chbuf_t *b, size_t extra) {
    if (b->len + extra + 1 <= b->cap) return 0;
    size_t new_cap = b->cap * 2;
    while (new_cap < b->len + extra + 1) new_cap *= 2;
    char *nd = realloc(b->data, new_cap);
    if (!nd) return -1;
    b->data = nd;
    b->cap  = new_cap;
    return 0;
}

static int chbuf_append(chbuf_t *b, const char *s, size_t n) {
    if (chbuf_ensure(b, n) != 0) return -1;
    memcpy(b->data + b->len, s, n);
    b->len += n;
    b->data[b->len] = '\0';
    return 0;
}

static int chbuf_appendf(chbuf_t *b, const char *fmt, ...) {
    char tmp[1024];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(tmp, sizeof(tmp), fmt, ap);
    va_end(ap);
    if (n <= 0 || n >= (int)sizeof(tmp)) return -1;
    return chbuf_append(b, tmp, (size_t)n);
}

/**
 * @brief JSON 字符串转义（写入到 out，最多 cap-1 字节）
 */
static void json_escape(const char *s, char *out, size_t cap) {
    size_t i = 0, j = 0;
    if (!s) { if (cap) out[0] = '\0'; return; }
    while (s[i] && j + 3 < cap) {
        unsigned char c = (unsigned char)s[i++];
        switch (c) {
            case '"':  out[j++] = '\\'; out[j++] = '"';  break;
            case '\\': out[j++] = '\\'; out[j++] = '\\'; break;
            case '\n': out[j++] = '\\'; out[j++] = 'n';  break;
            case '\r': out[j++] = '\\'; out[j++] = 'r';  break;
            case '\t': out[j++] = '\\'; out[j++] = 't';  break;
            default:
                if (c < 0x20) {
                    /* skip non-printable control chars */
                } else {
                    out[j++] = (char)c;
                }
        }
    }
    out[j] = '\0';
}

/**
 * @brief 发送 SQL 到 ClickHouse（HTTP POST）
 *
 * sql 为完整的 SQL 语句，包含 INSERT ... FORMAT JSONEachRow\n{data}
 * 或者 DDL 语句（CREATE TABLE IF NOT EXISTS ...）
 *
 * @return 0 成功，-1 失败
 */
static int ch_exec(const char *host, int port,
                   const char *user, const char *password,
                   const char *sql, size_t sql_len) {
    if (!host || !sql || sql_len == 0) return -1;

    /* 解析主机名 */
    struct addrinfo hints, *res = NULL;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family   = AF_INET;
    hints.ai_socktype = SOCK_STREAM;
    char port_str[16];
    snprintf(port_str, sizeof(port_str), "%d", port);
    if (getaddrinfo(host, port_str, &hints, &res) != 0) {
        fprintf(stderr, "[ClickHouse] getaddrinfo(%s:%d) failed\n", host, port);
        return -1;
    }

    int sock = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (sock < 0) { freeaddrinfo(res); return -1; }

    /* 超时设置 */
    struct timeval tv_send = {10, 0};
    struct timeval tv_recv = {30, 0};
    setsockopt(sock, SOL_SOCKET, SO_SNDTIMEO, &tv_send, sizeof(tv_send));
    setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv_recv, sizeof(tv_recv));

    if (connect(sock, res->ai_addr, res->ai_addrlen) < 0) {
        fprintf(stderr, "[ClickHouse] connect(%s:%d) failed\n", host, port);
        close(sock);
        freeaddrinfo(res);
        return -1;
    }
    freeaddrinfo(res);

    /* 构建 HTTP 请求头 */
    char header[512];
    int hlen = snprintf(header, sizeof(header),
        "POST / HTTP/1.0\r\n"
        "Host: %s:%d\r\n"
        "X-ClickHouse-User: %s\r\n"
        "X-ClickHouse-Key: %s\r\n"
        "Content-Type: text/plain; charset=utf-8\r\n"
        "Content-Length: %zu\r\n"
        "\r\n",
        host, port,
        user     ? user     : "default",
        password ? password : "",
        sql_len);
    if (hlen <= 0 || hlen >= (int)sizeof(header)) {
        close(sock);
        return -1;
    }

    /* 发送头 */
    ssize_t ret = send(sock, header, (size_t)hlen, 0);
    if (ret != hlen) { close(sock); return -1; }

    /* 发送 SQL 体 */
    size_t sent = 0;
    while (sent < sql_len) {
        ssize_t n = send(sock, sql + sent, sql_len - sent, 0);
        if (n <= 0) { close(sock); return -1; }
        sent += (size_t)n;
    }

    /* 读取响应 */
    char resp[4096];
    int total = 0, nr;
    while ((nr = (int)recv(sock, resp + total,
                           sizeof(resp) - 1 - (size_t)total, 0)) > 0) {
        total += nr;
        if (total >= (int)(sizeof(resp) - 1)) break;
    }
    resp[total] = '\0';
    close(sock);

    /* 解析状态码 */
    int status = 0;
    if (sscanf(resp, "HTTP/%*s %d", &status) == 1 && status == 200) {
        /* 观测：async_insert 下 CH 可能返回 200 但实际写入 0 行。仅对 INSERT
         * 语句校验响应头 X-ClickHouse-Summary 的 written_rows，异常时限流
         * WARN（每 60s 最多一条）。不重试、不改写入行为；头缺失（老版本 CH）
         * 则跳过校验不误报。 */
        if (strncmp(sql, "INSERT", 6) == 0) {
            const char *sum = strstr(resp, "X-ClickHouse-Summary:");
            const char *w = sum ? strstr(sum, "\"written_rows\":\"") : NULL;
            if (w && strtoll(w + 16, NULL, 10) == 0) {
                static time_t zero_warn_last = 0;
                time_t now_w = time(NULL);
                if (now_w - zero_warn_last >= 60) {
                    fprintf(stderr, "[ClickHouse] WARN: HTTP 200 但 written_rows=0, "
                            "SQL 前缀: %.120s\n", sql);
                    zero_warn_last = now_w;
                }
            }
        }
        return 0;
    }

    const char *body = strstr(resp, "\r\n\r\n");
    fprintf(stderr, "[ClickHouse] HTTP %d: %.300s\n",
            status, body ? body + 4 : resp);
    return -1;
}

/* ========== 建表 DDL ========== */

static const char *IP_TABLE_DDL =
    "CREATE TABLE IF NOT EXISTS %s.%sip\n"
    "(\n"
    "    ip              String,\n"
    "    is_ipv6         UInt8,\n"
    "    geo             String,\n"
    "    type            String,\n"
    "    cloud           String,\n"
    "    first_time      Int64,\n"
    "    last_time       Int64,\n"
    "    tx_flow         UInt64,\n"
    "    rx_flow         UInt64,\n"
    "    tx_packets      UInt64,\n"
    "    rx_packets      UInt64,\n"
    "    tx_tiny_packets UInt64,\n"
    "    rx_tiny_packets UInt64,\n"
    "    tcp_packets     UInt64,\n"
    "    udp_packets     UInt64,\n"
    "    icmp_packets    UInt64,\n"
    "    http_packets    UInt64,\n"
    "    https_packets   UInt64,\n"
    "    dns_packets     UInt64,\n"
    "    quic_packets    UInt64,\n"
    "    top_ports       String,\n"
    "    hourly_flow     String\n"
    ") ENGINE = ReplacingMergeTree(last_time)\n"
    "ORDER BY ip;\n";

static const char *SESSION_TABLE_DDL =
    "CREATE TABLE IF NOT EXISTS %s.%ssession\n"
    "(\n"
    "    session_id        String,\n"
    "    src_ip            String,\n"
    "    dst_ip            String,\n"
    "    src_port          UInt16,\n"
    "    dst_port          UInt16,\n"
    "    protocol          UInt8,\n"
    "    first_time        Int64,\n"
    "    last_time         Int64,\n"
    "    total_duration    UInt32,\n"
    "    packets_ab        UInt64,\n"
    "    packets_ba        UInt64,\n"
    "    bytes_ab          UInt64,\n"
    "    bytes_ba          UInt64,\n"
    "    detected_protocol String,\n"
    "    state             String,\n"
    "    http_method       String,\n"
    "    http_url          String,\n"
    "    http_host         String,\n"
    "    http_status_code  Int32,\n"
    "    dns_query         String,\n"
    "    dns_type          UInt16,\n"
    "    dns_resolved_ip   String,\n"
    "    segments          String\n"
    ") ENGINE = ReplacingMergeTree(last_time)\n"
    "ORDER BY session_id;\n";

static const char *PROTOCOL_TABLE_DDL =
    "CREATE TABLE IF NOT EXISTS %s.%sprotocol\n"
    "(\n"
    "    protocol_name     String,\n"
    "    protocol_id       UInt32,\n"
    "    first_time        Int64,\n"
    "    last_time         Int64,\n"
    "    packets_processed UInt64,\n"
    "    packets_matched   UInt64,\n"
    "    bytes_processed   UInt64,\n"
    "    sessions_created  UInt64,\n"
    "    errors            UInt64,\n"
    "    hourly_stats      String\n"
    ") ENGINE = ReplacingMergeTree(last_time)\n"
    "ORDER BY protocol_name;\n";

static const char *SYSTEM_TABLE_DDL =
    "CREATE TABLE IF NOT EXISTS %s.%ssystem\n"
    "(\n"
    "    ts                     Int64,\n"
    "    rx_packets             UInt64,\n"
    "    rx_bytes               UInt64,\n"
    "    rx_dropped             UInt64,\n"
    "    rx_errors              UInt64,\n"
    "    pps                    Float64,\n"
    "    bps                    Float64,\n"
    "    drop_rate              Float64,\n"
    "    active_sessions        UInt32,\n"
    "    total_sessions_created UInt64,\n"
    "    total_sessions_expired UInt64,\n"
    "    backend                String,\n"
    "    interface              String\n"
    ") ENGINE = MergeTree()\n"
    "ORDER BY ts;\n";

static const char *TUNNEL_SIGNALS_TABLE_DDL =
    "CREATE TABLE IF NOT EXISTS %s.tunnel_signals\n"
    "(\n"
    "    session_id            String,\n"
    "    session_start         DateTime,\n"
    "    session_end           DateTime,\n"
    "    src_ip                String,\n"
    "    dst_ip                String,\n"
    "    src_port              UInt16,\n"
    "    dst_port              UInt16,\n"
    "    protocol              UInt8,\n"
    "    entropy               Float32,\n"
    "    tunnel_probability    Float32,\n"
    "    is_non_standard_tls   UInt8,\n"
    "    has_no_dns            UInt8,\n"
    "    proxy_behavior_score  UInt8,\n"
    "    proxy_reason_flags    UInt16,\n"
    "    heartbeat_interval_sec UInt16,\n"
    "    heartbeat_confidence  UInt8,\n"
    "    heartbeat_mode        UInt8,\n"
    "    disguise_type         UInt8,\n"
    "    disguise_reason_flags UInt16\n"
    ") ENGINE = MergeTree()\n"
    "PARTITION BY toYYYYMMDD(session_end)\n"
    "ORDER BY (session_end, src_ip, dst_ip, src_port, dst_port, protocol);\n";

static const char *AUXILIARY_SIGNALS_TABLE_DDL =
    "CREATE TABLE IF NOT EXISTS %s.auxiliary_signals\n"
    "(\n"
    "    session_id              String,\n"
    "    session_start           DateTime,\n"
    "    session_end             DateTime,\n"
    "    src_ip                  String,\n"
    "    dst_ip                  String,\n"
    "    src_port                UInt16,\n"
    "    dst_port                UInt16,\n"
    "    protocol                UInt8,\n"
    "    covert_channel_type     UInt8,\n"
    "    covert_reason_flags     UInt16,\n"
    "    max_dns_qname_len       UInt16,\n"
    "    max_icmp_payload_len    UInt16,\n"
    "    threat_intel_type       String,\n"
    "    threat_intel_source     String,\n"
    "    threat_intel_pattern    String,\n"
    "    threat_intel_match_field UInt8,\n"
    "    threat_intel_match_mode UInt8\n"
    ") ENGINE = MergeTree()\n"
    "PARTITION BY toYYYYMMDD(session_end)\n"
    "ORDER BY (session_end, src_ip, dst_ip, src_port, dst_port, protocol);\n";

static const char *VERDICT_RESULTS_TABLE_DDL =
    "CREATE TABLE IF NOT EXISTS %s.verdict_results\n"
    "(\n"
    "    session_id              String,\n"
    "    session_start           DateTime,\n"
    "    session_end             DateTime,\n"
    "    src_ip                  String,\n"
    "    dst_ip                  String,\n"
    "    src_port                UInt16,\n"
    "    dst_port                UInt16,\n"
    "    protocol                UInt8,\n"
    "    is_crossing             UInt8,\n"
    "    total_score             UInt16,\n"
    "    confidence_level        UInt8,\n"
    "    triggered_factors       String,\n"
    "    score_proxy_behavior    UInt16,\n"
    "    score_threat_intel      UInt16,\n"
    "    score_tunnel_prob       UInt16,\n"
    "    score_no_dns            UInt16,\n"
    "    score_heartbeat         UInt16,\n"
    "    score_disguised         UInt16,\n"
    "    score_covert_channel    UInt16,\n"
    "    score_non_standard_tls  UInt16,\n"
    "    proxy_reason_flags      UInt16,\n"
    "    proxy_tool_name         String,\n"
    "    whitelist_hit           UInt8,\n"
    "    whitelist_reason        String,\n"
    "    geo_dst_country         String,\n"
    "    geo_is_cross_border     UInt8,\n"
    "    geo_sensitive_country_hit UInt8,\n"
    "    geo_abnormal_upload_hit UInt8,\n"
    "    verdict_time            DateTime\n"
    ") ENGINE = MergeTree()\n"
    "PARTITION BY toYYYYMMDD(verdict_time)\n"
    "ORDER BY (verdict_time, src_ip, dst_ip, src_port, dst_port, protocol);\n";

/* ========== 工具：构建 INSERT 头 ========== */

/**
 * 将 top_ports 数组序列化为 JSON 字符串（写入 buf）
 */
static void append_top_ports_json(chbuf_t *b, const ip_flow_doc_t *doc) {
    chbuf_append(b, "[", 1);
    for (int i = 0; i < doc->port_count && i < MAX_TOP_PORTS; i++) {
        if (i > 0) chbuf_append(b, ",", 1);
        chbuf_appendf(b, "{\"port\":%u,\"count\":%" PRIu64 "}",
                      doc->top_ports[i].port, doc->top_ports[i].count);
    }
    chbuf_append(b, "]", 1);
}

static void append_hourly_flow_json(chbuf_t *b, const ip_flow_doc_t *doc) {
    chbuf_append(b, "[", 1);
    for (int i = 0; i < doc->hourly_count && i < MAX_HOURLY_RECORDS; i++) {
        if (i > 0) chbuf_append(b, ",", 1);
        chbuf_appendf(b, "{\"hour\":%d,\"packets\":%u,\"cross\":%u,\"web\":%u}",
                      doc->hourly_flow[i].hour,
                      doc->hourly_flow[i].packets,
                      doc->hourly_flow[i].cross_border_packets,
                      doc->hourly_flow[i].web_packets);
    }
    chbuf_append(b, "]", 1);
}

static void append_segments_json(chbuf_t *b, const session_doc_t *doc) {
    chbuf_append(b, "[", 1);
    for (int i = 0; i < doc->segment_count && i < MAX_SEGMENTS; i++) {
        if (i > 0) chbuf_append(b, ",", 1);
        chbuf_appendf(b, "{\"start\":%" PRId64 ",\"end\":%" PRId64 ",\"flow\":%" PRIu64 "}",
                      (int64_t)doc->segments[i].start_time,
                      (int64_t)doc->segments[i].end_time,
                      doc->segments[i].flow);
    }
    chbuf_append(b, "]", 1);
}

static void append_hourly_stats_json(chbuf_t *b, const protocol_doc_t *doc) {
    chbuf_append(b, "[", 1);
    for (int i = 0; i < doc->hourly_count && i < MAX_HOURLY_RECORDS; i++) {
        if (i > 0) chbuf_append(b, ",", 1);
        chbuf_appendf(b, "{\"hour\":%d,\"packets\":%" PRIu64 ",\"bytes\":%" PRIu64 "}",
                      doc->hourly_stats[i].hour,
                      doc->hourly_stats[i].packets,
                      doc->hourly_stats[i].bytes);
    }
    chbuf_append(b, "]", 1);
}

static void format_session_endpoints(const session_entry_t *session,
                                     char *src_buf, size_t src_size,
                                     char *dst_buf, size_t dst_size)
{
    if (!session || !src_buf || !dst_buf) return;

    src_buf[0] = '\0';
    dst_buf[0] = '\0';

    if (session->ip_version == 6) {
        inet_ntop(AF_INET6, session->src_ip6, src_buf, src_size);
        inet_ntop(AF_INET6, session->dst_ip6, dst_buf, dst_size);
        return;
    }

    snprintf(src_buf, src_size, "%u.%u.%u.%u",
             (session->src_ip >> 24) & 0xFF, (session->src_ip >> 16) & 0xFF,
             (session->src_ip >> 8) & 0xFF, session->src_ip & 0xFF);
    snprintf(dst_buf, dst_size, "%u.%u.%u.%u",
             (session->dst_ip >> 24) & 0xFF, (session->dst_ip >> 16) & 0xFF,
             (session->dst_ip >> 8) & 0xFF, session->dst_ip & 0xFF);
}

static void format_session_id(const session_entry_t *session,
                              const char *src_ip, const char *dst_ip,
                              char *out, size_t out_size)
{
    if (!session || !src_ip || !dst_ip || !out || out_size == 0) return;

    if (session->ip_version == 6) {
        snprintf(out, out_size, "[%s]:%u-[%s]:%u-%u",
                 src_ip, session->src_port, dst_ip, session->dst_port,
                 session->key.protocol);
        return;
    }

    snprintf(out, out_size, "%s:%u-%s:%u-%u",
             src_ip, session->src_port, dst_ip, session->dst_port,
             session->key.protocol);
}

int storage_engine_build_tunnel_signal_doc(const session_entry_t *session, tunnel_signal_doc_t *doc)
{
    const tunnel_signal_t *signal;

    if (!session || !doc) return -1;

    memset(doc, 0, sizeof(*doc));
    format_session_endpoints(session, doc->src_ip, sizeof(doc->src_ip),
                             doc->dst_ip, sizeof(doc->dst_ip));
    format_session_id(session, doc->src_ip, doc->dst_ip,
                      doc->session_id, sizeof(doc->session_id));
    if (doc->session_id[0] == '\0') return -1;

    doc->src_port = session->src_port;
    doc->dst_port = session->dst_port;
    doc->protocol = session->key.protocol;
    doc->session_start = session->create_time;
    doc->session_end = session->last_seen;

    signal = (const tunnel_signal_t *)session->tunnel_signal;
    if (!signal) return 0;

    doc->entropy = signal->entropy;
    doc->tunnel_probability = signal->tunnel_probability;
    doc->is_non_standard_tls = signal->is_non_standard_tls ? 1 : 0;
    doc->has_no_dns = signal->has_no_dns ? 1 : 0;
    doc->proxy_behavior_score = signal->proxy_behavior_score;
    doc->proxy_reason_flags = signal->proxy_reason_flags;
    doc->heartbeat_interval_sec = signal->heartbeat_interval_sec;
    doc->heartbeat_confidence = signal->heartbeat_confidence;
    doc->heartbeat_mode = signal->heartbeat_mode;
    doc->disguise_type = signal->disguise_type;
    doc->disguise_reason_flags = signal->disguise_reason_flags;
    return 0;
}

int storage_engine_build_auxiliary_signal_doc(const session_entry_t *session, auxiliary_signal_doc_t *doc)
{
    const tunnel_signal_t *signal;

    if (!session || !doc) return -1;

    memset(doc, 0, sizeof(*doc));
    format_session_endpoints(session, doc->src_ip, sizeof(doc->src_ip),
                             doc->dst_ip, sizeof(doc->dst_ip));
    format_session_id(session, doc->src_ip, doc->dst_ip,
                      doc->session_id, sizeof(doc->session_id));
    if (doc->session_id[0] == '\0') return -1;

    doc->src_port = session->src_port;
    doc->dst_port = session->dst_port;
    doc->protocol = session->key.protocol;
    doc->session_start = session->create_time;
    doc->session_end = session->last_seen;

    signal = (const tunnel_signal_t *)session->tunnel_signal;
    if (!signal) return 0;

    doc->covert_channel_type = signal->covert_channel_type;
    doc->covert_reason_flags = signal->covert_reason_flags;
    doc->max_dns_qname_len = signal->max_dns_qname_len;
    doc->max_icmp_payload_len = signal->max_icmp_payload_len;
    strncpy(doc->threat_intel_type, signal->threat_intel_type, sizeof(doc->threat_intel_type) - 1);
    strncpy(doc->threat_intel_source, signal->threat_intel_source, sizeof(doc->threat_intel_source) - 1);
    strncpy(doc->threat_intel_pattern, signal->threat_intel_pattern, sizeof(doc->threat_intel_pattern) - 1);
    doc->threat_intel_match_field = signal->threat_intel_match_field;
    doc->threat_intel_match_mode = signal->threat_intel_match_mode;
    return 0;
}

/* ========== 存储引擎创建 ========== */

storage_engine_t *storage_engine_create(const char *ch_host, int ch_port,
                                         const char *ch_user, const char *ch_password,
                                         const char *db_name,
                                         const char *table_prefix) {
    if (!ch_host || !db_name) return NULL;

    storage_engine_t *e = calloc(1, sizeof(storage_engine_t));
    if (!e) return NULL;

    strncpy(e->ch_host,     ch_host,     sizeof(e->ch_host)     - 1);
    e->ch_port = ch_port > 0 ? ch_port : 8123;
    strncpy(e->ch_user,     ch_user     ? ch_user     : "default",
            sizeof(e->ch_user) - 1);
    strncpy(e->ch_password, ch_password ? ch_password : "",
            sizeof(e->ch_password) - 1);
    strncpy(e->db_name,     db_name,     sizeof(e->db_name)     - 1);
    strncpy(e->table_prefix,
            table_prefix ? table_prefix : "stats",
            sizeof(e->table_prefix) - 1);

    time_t now = time(NULL);
    struct tm *tm_info = localtime(&now);
    e->current_year = tm_info->tm_year + 1900;

    /* 创建虚拟集合 */
    e->vc = virtual_collections_create();
    if (!e->vc) {
        free(e);
        return NULL;
    }

    pthread_mutex_init(&e->metrics_lock, NULL);

    printf("[Storage Engine] Created (ClickHouse backend)\n");
    printf("  Host: %s:%d\n", e->ch_host, e->ch_port);
    printf("  Database: %s\n", e->db_name);
    printf("  Table prefix: %s\n", e->table_prefix);

    return e;
}

void storage_engine_destroy(storage_engine_t *engine) {
    if (!engine) return;

    printf("[Storage Engine] Flushing data before shutdown...\n");
    storage_engine_flush_all(engine);

    if (engine->vc) virtual_collections_destroy(engine->vc);
    pthread_mutex_destroy(&engine->metrics_lock);
    free(engine);

    printf("[Storage Engine] Destroyed\n");
}

/* ========== 建表 ========== */

int storage_engine_create_indexes(storage_engine_t *engine) {
    if (!engine) return -1;

    char sql[2048];
    int ret = 0;

    /* CREATE DATABASE IF NOT EXISTS */
    snprintf(sql, sizeof(sql),
             "CREATE DATABASE IF NOT EXISTS %s;\n", engine->db_name);
    if (ch_exec(engine->ch_host, engine->ch_port,
                engine->ch_user, engine->ch_password, sql, strlen(sql)) != 0) {
        fprintf(stderr, "[Storage Engine] Failed to create database %s\n", engine->db_name);
        ret = -1;
    }

    /* IP 表 */
    snprintf(sql, sizeof(sql), IP_TABLE_DDL, engine->db_name, engine->table_prefix);
    if (ch_exec(engine->ch_host, engine->ch_port,
                engine->ch_user, engine->ch_password, sql, strlen(sql)) != 0) {
        fprintf(stderr, "[Storage Engine] Failed to create IP table\n");
        ret = -1;
    }

    /* Session 表 */
    snprintf(sql, sizeof(sql), SESSION_TABLE_DDL, engine->db_name, engine->table_prefix);
    if (ch_exec(engine->ch_host, engine->ch_port,
                engine->ch_user, engine->ch_password, sql, strlen(sql)) != 0) {
        fprintf(stderr, "[Storage Engine] Failed to create session table\n");
        ret = -1;
    }

    /* Protocol 表 */
    snprintf(sql, sizeof(sql), PROTOCOL_TABLE_DDL, engine->db_name, engine->table_prefix);
    if (ch_exec(engine->ch_host, engine->ch_port,
                engine->ch_user, engine->ch_password, sql, strlen(sql)) != 0) {
        fprintf(stderr, "[Storage Engine] Failed to create protocol table\n");
        ret = -1;
    }

    /* System 表 */
    snprintf(sql, sizeof(sql), SYSTEM_TABLE_DDL, engine->db_name, engine->table_prefix);
    if (ch_exec(engine->ch_host, engine->ch_port,
                engine->ch_user, engine->ch_password, sql, strlen(sql)) != 0) {
        fprintf(stderr, "[Storage Engine] Failed to create system table\n");
        ret = -1;
    }

    snprintf(sql, sizeof(sql), TUNNEL_SIGNALS_TABLE_DDL, engine->db_name);
    if (ch_exec(engine->ch_host, engine->ch_port,
                engine->ch_user, engine->ch_password, sql, strlen(sql)) != 0) {
        fprintf(stderr, "[Storage Engine] Failed to create tunnel_signals table\n");
        ret = -1;
    }

    snprintf(sql, sizeof(sql), AUXILIARY_SIGNALS_TABLE_DDL, engine->db_name);
    if (ch_exec(engine->ch_host, engine->ch_port,
                engine->ch_user, engine->ch_password, sql, strlen(sql)) != 0) {
        fprintf(stderr, "[Storage Engine] Failed to create auxiliary_signals table\n");
        ret = -1;
    }

    snprintf(sql, sizeof(sql), VERDICT_RESULTS_TABLE_DDL, engine->db_name);
    if (ch_exec(engine->ch_host, engine->ch_port,
                engine->ch_user, engine->ch_password, sql, strlen(sql)) != 0) {
        fprintf(stderr, "[Storage Engine] Failed to create verdict_results table\n");
        ret = -1;
    }

    if (ret == 0) {
        printf("[Storage Engine] Tables ready in database '%s'\n", engine->db_name);
    }
    return ret;
}

/* ========== IP 集合 flush ========== */

int storage_engine_flush_ip_collection(storage_engine_t *engine) {
    if (!engine || !engine->vc) return -1;

    char **keys = NULL;
    size_t count = 0;
    if (virtual_collection_get_pending_ips(engine->vc, &keys, &count) != 0) return -1;
    if (count == 0) return 0;

    printf("[Storage Engine] Flushing %zu IP records...\n", count);

    /* INSERT 头 */
    char insert_hdr[256];
    snprintf(insert_hdr, sizeof(insert_hdr),
             "INSERT INTO %s.%sip FORMAT JSONEachRow\n",
             engine->db_name, engine->table_prefix);

    chbuf_t *buf = chbuf_new(1024 * 1024);  /* 1 MB 初始 */
    if (!buf) goto cleanup;

    chbuf_append(buf, insert_hdr, strlen(insert_hdr));

    int valid = 0;
    for (size_t i = 0; i < count; i++) {
        ip_flow_doc_t *doc = virtual_collection_find_ip(engine->vc, keys[i]);
        if (!doc) continue;

        char ip_esc[96], geo_esc[128], type_esc[32], cloud_esc[64];
        json_escape(doc->ip,    ip_esc,    sizeof(ip_esc));
        json_escape(doc->geo,   geo_esc,   sizeof(geo_esc));
        json_escape(doc->type,  type_esc,  sizeof(type_esc));
        json_escape(doc->cloud, cloud_esc, sizeof(cloud_esc));

        chbuf_appendf(buf,
            "{\"ip\":\"%s\",\"is_ipv6\":%d,"
            "\"geo\":\"%s\",\"type\":\"%s\",\"cloud\":\"%s\","
            "\"first_time\":%" PRId64 ",\"last_time\":%" PRId64 ","
            "\"tx_flow\":%" PRIu64 ",\"rx_flow\":%" PRIu64 ","
            "\"tx_packets\":%" PRIu64 ",\"rx_packets\":%" PRIu64 ","
            "\"tx_tiny_packets\":%" PRIu64 ",\"rx_tiny_packets\":%" PRIu64 ","
            "\"tcp_packets\":%" PRIu64 ",\"udp_packets\":%" PRIu64 ","
            "\"icmp_packets\":%" PRIu64 ",\"http_packets\":%" PRIu64 ","
            "\"https_packets\":%" PRIu64 ",\"dns_packets\":%" PRIu64 ","
            "\"quic_packets\":%" PRIu64 ",",
            ip_esc, (int)doc->is_ipv6,
            geo_esc, type_esc, cloud_esc,
            (int64_t)doc->first_time, (int64_t)doc->last_time,
            doc->tx_flow, doc->rx_flow,
            doc->tx_packets, doc->rx_packets,
            doc->tx_tiny_packets, doc->rx_tiny_packets,
            doc->tcp_packets, doc->udp_packets,
            doc->icmp_packets, doc->http_packets,
            doc->https_packets, doc->dns_packets,
            doc->quic_packets);

        chbuf_append(buf, "\"top_ports\":\"", 14);
        chbuf_t *tp = chbuf_new(512);
        if (tp) { append_top_ports_json(tp, doc); }
        /* JSON-escape the nested array string */
        if (tp) {
            char *escaped = malloc(tp->len * 2 + 4);
            if (escaped) {
                json_escape(tp->data, escaped, tp->len * 2 + 4);
                chbuf_append(buf, escaped, strlen(escaped));
                free(escaped);
            }
            chbuf_free(tp);
        }
        chbuf_append(buf, "\",\"hourly_flow\":\"", 17);
        chbuf_t *hf = chbuf_new(512);
        if (hf) { append_hourly_flow_json(hf, doc); }
        if (hf) {
            char *escaped = malloc(hf->len * 2 + 4);
            if (escaped) {
                json_escape(hf->data, escaped, hf->len * 2 + 4);
                chbuf_append(buf, escaped, strlen(escaped));
                free(escaped);
            }
            chbuf_free(hf);
        }
        chbuf_append(buf, "\"}\n", 3);
        valid++;
    }

    if (valid > 0) {
        if (ch_exec(engine->ch_host, engine->ch_port,
                    engine->ch_user, engine->ch_password,
                    buf->data, buf->len) == 0) {
            /* 标记已同步 */
            for (size_t i = 0; i < count; i++) {
                virtual_collection_mark_ip_synced(engine->vc, keys[i]);
            }
            printf("[Storage Engine] Flushed %d IP records\n", valid);
        } else {
            fprintf(stderr, "[Storage Engine] IP flush failed\n");
            valid = -1;
        }
    }

cleanup:
    chbuf_free(buf);
    for (size_t i = 0; i < count; i++) free(keys[i]);
    free(keys);
    return valid;
}

/* ========== 会话集合 flush ========== */

int storage_engine_flush_session_collection(storage_engine_t *engine) {
    if (!engine || !engine->vc) return -1;

    char **keys = NULL;
    size_t count = 0;
    if (virtual_collection_get_pending_sessions(engine->vc, &keys, &count) != 0) return -1;
    if (count == 0) return 0;

    printf("[Storage Engine] Flushing %zu session records...\n", count);

    char insert_hdr[256];
    snprintf(insert_hdr, sizeof(insert_hdr),
             "INSERT INTO %s.%ssession FORMAT JSONEachRow\n",
             engine->db_name, engine->table_prefix);

    chbuf_t *buf = chbuf_new(1024 * 1024);
    if (!buf) goto s_cleanup;

    chbuf_append(buf, insert_hdr, strlen(insert_hdr));

    int valid = 0;
    for (size_t i = 0; i < count; i++) {
        session_doc_t *doc = virtual_collection_find_session(engine->vc, keys[i]);
        if (!doc) continue;

        char sid_esc[128], src_esc[96], dst_esc[96];
        char dp_esc[64], st_esc[64];
        char hm_esc[32], hu_esc[512], hh_esc[256], dq_esc[512], dri_esc[96];

        json_escape(doc->session_id,       sid_esc, sizeof(sid_esc));
        json_escape(doc->src_ip,           src_esc, sizeof(src_esc));
        json_escape(doc->dst_ip,           dst_esc, sizeof(dst_esc));
        json_escape(doc->detected_protocol, dp_esc, sizeof(dp_esc));
        json_escape(doc->state,            st_esc,  sizeof(st_esc));
        json_escape(doc->http_method,      hm_esc,  sizeof(hm_esc));
        json_escape(doc->http_url,         hu_esc,  sizeof(hu_esc));
        json_escape(doc->http_host,        hh_esc,  sizeof(hh_esc));
        json_escape(doc->dns_query,        dq_esc,  sizeof(dq_esc));
        json_escape(doc->dns_resolved_ip,  dri_esc, sizeof(dri_esc));

        chbuf_appendf(buf,
            "{\"session_id\":\"%s\","
            "\"src_ip\":\"%s\",\"dst_ip\":\"%s\","
            "\"src_port\":%u,\"dst_port\":%u,\"protocol\":%u,"
            "\"first_time\":%" PRId64 ",\"last_time\":%" PRId64 ","
            "\"total_duration\":%u,"
            "\"packets_ab\":%" PRIu64 ",\"packets_ba\":%" PRIu64 ","
            "\"bytes_ab\":%" PRIu64 ",\"bytes_ba\":%" PRIu64 ","
            "\"detected_protocol\":\"%s\",\"state\":\"%s\","
            "\"http_method\":\"%s\",\"http_url\":\"%s\","
            "\"http_host\":\"%s\",\"http_status_code\":%d,"
            "\"dns_query\":\"%s\",\"dns_type\":%u,"
            "\"dns_resolved_ip\":\"%s\",",
            sid_esc,
            src_esc, dst_esc,
            (unsigned)doc->src_port, (unsigned)doc->dst_port,
            (unsigned)doc->protocol,
            (int64_t)doc->first_time, (int64_t)doc->last_time,
            doc->total_duration,
            doc->packets_ab, doc->packets_ba,
            doc->bytes_ab,   doc->bytes_ba,
            dp_esc, st_esc,
            hm_esc, hu_esc, hh_esc, doc->http_status_code,
            dq_esc, (unsigned)doc->dns_type, dri_esc);

        chbuf_append(buf, "\"segments\":\"", 13);
        chbuf_t *sg = chbuf_new(512);
        if (sg) { append_segments_json(sg, doc); }
        if (sg) {
            char *escaped = malloc(sg->len * 2 + 4);
            if (escaped) {
                json_escape(sg->data, escaped, sg->len * 2 + 4);
                chbuf_append(buf, escaped, strlen(escaped));
                free(escaped);
            }
            chbuf_free(sg);
        }
        chbuf_append(buf, "\"}\n", 3);
        valid++;
    }

    if (valid > 0) {
        if (ch_exec(engine->ch_host, engine->ch_port,
                    engine->ch_user, engine->ch_password,
                    buf->data, buf->len) == 0) {
            for (size_t i = 0; i < count; i++) {
                virtual_collection_mark_session_synced(engine->vc, keys[i]);
            }
            printf("[Storage Engine] Flushed %d session records\n", valid);
        } else {
            fprintf(stderr, "[Storage Engine] Session flush failed\n");
            valid = -1;
        }
    }

s_cleanup:
    chbuf_free(buf);
    for (size_t i = 0; i < count; i++) free(keys[i]);
    free(keys);
    return valid;
}

/* ========== 协议集合 flush ========== */

int storage_engine_flush_protocol_collection(storage_engine_t *engine) {
    if (!engine || !engine->vc) return -1;

    char **keys = NULL;
    size_t count = 0;
    if (virtual_collection_get_pending_protocols(engine->vc, &keys, &count) != 0) return -1;
    if (count == 0) return 0;

    printf("[Storage Engine] Flushing %zu protocol records...\n", count);

    char insert_hdr[256];
    snprintf(insert_hdr, sizeof(insert_hdr),
             "INSERT INTO %s.%sprotocol FORMAT JSONEachRow\n",
             engine->db_name, engine->table_prefix);

    chbuf_t *buf = chbuf_new(64 * 1024);
    if (!buf) goto p_cleanup;

    chbuf_append(buf, insert_hdr, strlen(insert_hdr));

    int valid = 0;
    for (size_t i = 0; i < count; i++) {
        protocol_doc_t *doc = virtual_collection_find_protocol(engine->vc, keys[i]);
        if (!doc) continue;

        char pn_esc[64];
        json_escape(doc->protocol_name, pn_esc, sizeof(pn_esc));

        chbuf_appendf(buf,
            "{\"protocol_name\":\"%s\",\"protocol_id\":%u,"
            "\"first_time\":%" PRId64 ",\"last_time\":%" PRId64 ","
            "\"packets_processed\":%" PRIu64 ",\"packets_matched\":%" PRIu64 ","
            "\"bytes_processed\":%" PRIu64 ",\"sessions_created\":%" PRIu64 ","
            "\"errors\":%" PRIu64 ",",
            pn_esc, doc->protocol_id,
            (int64_t)doc->first_time, (int64_t)doc->last_time,
            doc->packets_processed, doc->packets_matched,
            doc->bytes_processed, doc->sessions_created,
            doc->errors);

        chbuf_append(buf, "\"hourly_stats\":\"", 17);
        chbuf_t *hs = chbuf_new(256);
        if (hs) { append_hourly_stats_json(hs, doc); }
        if (hs) {
            char *escaped = malloc(hs->len * 2 + 4);
            if (escaped) {
                json_escape(hs->data, escaped, hs->len * 2 + 4);
                chbuf_append(buf, escaped, strlen(escaped));
                free(escaped);
            }
            chbuf_free(hs);
        }
        chbuf_append(buf, "\"}\n", 3);
        valid++;
    }

    if (valid > 0) {
        if (ch_exec(engine->ch_host, engine->ch_port,
                    engine->ch_user, engine->ch_password,
                    buf->data, buf->len) == 0) {
            for (size_t i = 0; i < count; i++) {
                virtual_collection_mark_protocol_synced(engine->vc, keys[i]);
            }
            printf("[Storage Engine] Flushed %d protocol records\n", valid);
        } else {
            fprintf(stderr, "[Storage Engine] Protocol flush failed\n");
            valid = -1;
        }
    }

p_cleanup:
    chbuf_free(buf);
    for (size_t i = 0; i < count; i++) free(keys[i]);
    free(keys);
    return valid;
}

int storage_engine_flush_tunnel_signal_collection(storage_engine_t *engine) {
    char **keys = NULL;
    size_t count = 0;
    char insert_hdr[256];
    chbuf_t *buf = NULL;
    int valid = 0;

    if (!engine || !engine->vc) return -1;
    if (virtual_collection_get_pending_tunnel_signals(engine->vc, &keys, &count) != 0) return -1;
    if (count == 0) return 0;

    snprintf(insert_hdr, sizeof(insert_hdr),
             "INSERT INTO %s.tunnel_signals FORMAT JSONEachRow\n",
             engine->db_name);
    buf = chbuf_new(64 * 1024);
    if (!buf) goto cleanup;
    chbuf_append(buf, insert_hdr, strlen(insert_hdr));

    for (size_t i = 0; i < count; i++) {
        tunnel_signal_doc_t *doc = virtual_collection_find_tunnel_signal(engine->vc, keys[i]);
        char sid_esc[128], src_esc[96], dst_esc[96];
        if (!doc) continue;

        json_escape(doc->session_id, sid_esc, sizeof(sid_esc));
        json_escape(doc->src_ip, src_esc, sizeof(src_esc));
        json_escape(doc->dst_ip, dst_esc, sizeof(dst_esc));

        chbuf_appendf(buf,
            "{\"session_id\":\"%s\",\"session_start\":%" PRId64 ",\"session_end\":%" PRId64 ","
            "\"src_ip\":\"%s\",\"dst_ip\":\"%s\","
            "\"src_port\":%u,\"dst_port\":%u,\"protocol\":%u,"
            "\"entropy\":%.6f,\"tunnel_probability\":%.6f,"
            "\"is_non_standard_tls\":%u,\"has_no_dns\":%u,"
            "\"proxy_behavior_score\":%u,\"proxy_reason_flags\":%u,"
            "\"heartbeat_interval_sec\":%u,\"heartbeat_confidence\":%u,"
            "\"heartbeat_mode\":%u,\"disguise_type\":%u,\"disguise_reason_flags\":%u}\n",
            sid_esc, (int64_t)doc->session_start, (int64_t)doc->session_end,
            src_esc, dst_esc,
            (unsigned)doc->src_port, (unsigned)doc->dst_port, (unsigned)doc->protocol,
            doc->entropy, doc->tunnel_probability,
            (unsigned)doc->is_non_standard_tls, (unsigned)doc->has_no_dns,
            (unsigned)doc->proxy_behavior_score, (unsigned)doc->proxy_reason_flags,
            (unsigned)doc->heartbeat_interval_sec, (unsigned)doc->heartbeat_confidence,
            (unsigned)doc->heartbeat_mode, (unsigned)doc->disguise_type,
            (unsigned)doc->disguise_reason_flags);
        valid++;
    }

    if (valid > 0) {
        if (ch_exec(engine->ch_host, engine->ch_port,
                    engine->ch_user, engine->ch_password,
                    buf->data, buf->len) == 0) {
            for (size_t i = 0; i < count; i++) {
                virtual_collection_mark_tunnel_signal_synced(engine->vc, keys[i]);
            }
        } else {
            valid = -1;
        }
    }

cleanup:
    chbuf_free(buf);
    for (size_t i = 0; i < count; i++) free(keys[i]);
    free(keys);
    return valid;
}

int storage_engine_flush_auxiliary_signal_collection(storage_engine_t *engine) {
    char **keys = NULL;
    size_t count = 0;
    char insert_hdr[256];
    chbuf_t *buf = NULL;
    int valid = 0;

    if (!engine || !engine->vc) return -1;
    if (virtual_collection_get_pending_auxiliary_signals(engine->vc, &keys, &count) != 0) return -1;
    if (count == 0) return 0;

    snprintf(insert_hdr, sizeof(insert_hdr),
             "INSERT INTO %s.auxiliary_signals FORMAT JSONEachRow\n",
             engine->db_name);
    buf = chbuf_new(64 * 1024);
    if (!buf) goto cleanup;
    chbuf_append(buf, insert_hdr, strlen(insert_hdr));

    for (size_t i = 0; i < count; i++) {
        auxiliary_signal_doc_t *doc = virtual_collection_find_auxiliary_signal(engine->vc, keys[i]);
        char sid_esc[128], src_esc[96], dst_esc[96];
        char type_esc[64], source_esc[128], pattern_esc[256];
        if (!doc) continue;

        json_escape(doc->session_id, sid_esc, sizeof(sid_esc));
        json_escape(doc->src_ip, src_esc, sizeof(src_esc));
        json_escape(doc->dst_ip, dst_esc, sizeof(dst_esc));
        json_escape(doc->threat_intel_type, type_esc, sizeof(type_esc));
        json_escape(doc->threat_intel_source, source_esc, sizeof(source_esc));
        json_escape(doc->threat_intel_pattern, pattern_esc, sizeof(pattern_esc));

        chbuf_appendf(buf,
            "{\"session_id\":\"%s\",\"session_start\":%" PRId64 ",\"session_end\":%" PRId64 ","
            "\"src_ip\":\"%s\",\"dst_ip\":\"%s\","
            "\"src_port\":%u,\"dst_port\":%u,\"protocol\":%u,"
            "\"covert_channel_type\":%u,\"covert_reason_flags\":%u,"
            "\"max_dns_qname_len\":%u,\"max_icmp_payload_len\":%u,"
            "\"threat_intel_type\":\"%s\",\"threat_intel_source\":\"%s\","
            "\"threat_intel_pattern\":\"%s\",\"threat_intel_match_field\":%u,"
            "\"threat_intel_match_mode\":%u}\n",
            sid_esc, (int64_t)doc->session_start, (int64_t)doc->session_end,
            src_esc, dst_esc,
            (unsigned)doc->src_port, (unsigned)doc->dst_port, (unsigned)doc->protocol,
            (unsigned)doc->covert_channel_type, (unsigned)doc->covert_reason_flags,
            (unsigned)doc->max_dns_qname_len, (unsigned)doc->max_icmp_payload_len,
            type_esc, source_esc, pattern_esc,
            (unsigned)doc->threat_intel_match_field,
            (unsigned)doc->threat_intel_match_mode);
        valid++;
    }

    if (valid > 0) {
        if (ch_exec(engine->ch_host, engine->ch_port,
                    engine->ch_user, engine->ch_password,
                    buf->data, buf->len) == 0) {
            for (size_t i = 0; i < count; i++) {
                virtual_collection_mark_auxiliary_signal_synced(engine->vc, keys[i]);
            }
        } else {
            valid = -1;
        }
    }

cleanup:
    chbuf_free(buf);
    for (size_t i = 0; i < count; i++) free(keys[i]);
    free(keys);
    return valid;
}

int storage_engine_flush_verdict_collection(storage_engine_t *engine) {
    char **keys = NULL;
    size_t count = 0;
    char insert_hdr[256];
    chbuf_t *buf = NULL;
    int valid = 0;

    if (!engine || !engine->vc) return -1;
    if (virtual_collection_get_pending_verdicts(engine->vc, &keys, &count) != 0) return -1;
    if (count == 0) return 0;

    snprintf(insert_hdr, sizeof(insert_hdr),
             "INSERT INTO %s.verdict_results FORMAT JSONEachRow\n",
             engine->db_name);
    buf = chbuf_new(64 * 1024);
    if (!buf) goto cleanup;
    chbuf_append(buf, insert_hdr, strlen(insert_hdr));

    for (size_t i = 0; i < count; i++) {
        verdict_doc_t *doc = virtual_collection_find_verdict(engine->vc, keys[i]);
        char sid_esc[128], src_esc[96], dst_esc[96];
        char factors_esc[512], geo_country_esc[32];
        char proxy_tool_name_esc[64];

        if (!doc) continue;

        json_escape(doc->session_id, sid_esc, sizeof(sid_esc));
        json_escape(doc->src_ip, src_esc, sizeof(src_esc));
        json_escape(doc->dst_ip, dst_esc, sizeof(dst_esc));
        json_escape(doc->triggered_factors, factors_esc, sizeof(factors_esc));
        json_escape(doc->geo_dst_country, geo_country_esc, sizeof(geo_country_esc));
        json_escape(doc->proxy_tool_name, proxy_tool_name_esc, sizeof(proxy_tool_name_esc));

        chbuf_appendf(buf,
            "{\"session_id\":\"%s\",\"session_start\":%" PRId64 ",\"session_end\":%" PRId64 ","
            "\"src_ip\":\"%s\",\"dst_ip\":\"%s\","
            "\"src_port\":%u,\"dst_port\":%u,\"protocol\":%u,"
            "\"is_crossing\":%u,\"total_score\":%u,\"confidence_level\":%u,"
            "\"triggered_factors\":\"%s\","
            "\"score_proxy_behavior\":%u,\"score_threat_intel\":%u,"
            "\"score_tunnel_prob\":%u,\"score_no_dns\":%u,"
            "\"score_heartbeat\":%u,\"score_disguised\":%u,"
            "\"score_covert_channel\":%u,\"score_non_standard_tls\":%u,"
            "\"proxy_reason_flags\":%u,\"proxy_tool_name\":\"%s\","
            "\"whitelist_hit\":%u,\"whitelist_reason\":\"%s\","
            "\"geo_dst_country\":\"%s\",\"geo_is_cross_border\":%u,"
            "\"geo_sensitive_country_hit\":%u,\"geo_abnormal_upload_hit\":%u,"
            "\"verdict_time\":%" PRId64 "}\n",
            sid_esc, (int64_t)doc->session_start, (int64_t)doc->session_end,
            src_esc, dst_esc,
            (unsigned)doc->src_port, (unsigned)doc->dst_port, (unsigned)doc->protocol,
            doc->is_crossing ? 1U : 0U, (unsigned)doc->total_score, (unsigned)doc->confidence_level,
            factors_esc,
            (unsigned)doc->score_proxy_behavior, (unsigned)doc->score_threat_intel,
            (unsigned)doc->score_tunnel_prob, (unsigned)doc->score_no_dns,
            (unsigned)doc->score_heartbeat, (unsigned)doc->score_disguised,
            (unsigned)doc->score_covert_channel, (unsigned)doc->score_non_standard_tls,
            (unsigned)doc->proxy_reason_flags, proxy_tool_name_esc,
            0U, "",
            geo_country_esc, doc->geo_is_cross_border ? 1U : 0U,
            doc->geo_sensitive_country_hit ? 1U : 0U,
            doc->geo_abnormal_upload_hit ? 1U : 0U,
            (int64_t)doc->verdict_time);
        valid++;
    }

    if (valid > 0) {
        if (ch_exec(engine->ch_host, engine->ch_port,
                    engine->ch_user, engine->ch_password,
                    buf->data, buf->len) == 0) {
            for (size_t i = 0; i < count; i++) {
                virtual_collection_mark_verdict_synced(engine->vc, keys[i]);
            }
        } else {
            valid = -1;
        }
    }

cleanup:
    chbuf_free(buf);
    for (size_t i = 0; i < count; i++) free(keys[i]);
    free(keys);
    return valid;
}

int storage_engine_flush_all(storage_engine_t *engine) {
    if (!engine) return -1;

    printf("\n[Storage Engine] ===== Starting Full Flush =====\n");
    int ip      = storage_engine_flush_ip_collection(engine);
    int sess    = storage_engine_flush_session_collection(engine);
    int prot    = storage_engine_flush_protocol_collection(engine);
    int verdict = storage_engine_flush_verdict_collection(engine);
    int tunnel  = storage_engine_flush_tunnel_signal_collection(engine);
    int aux     = storage_engine_flush_auxiliary_signal_collection(engine);
    printf("[Storage Engine] ===== Flush Complete =====\n");
    printf("  IP: %d  Session: %d  Protocol: %d  Verdict: %d  Tunnel: %d  Auxiliary: %d\n\n",
           ip, sess, prot, verdict, tunnel, aux);
    return 0;
}

/* ========== 兼容接口 ========== */

int storage_engine_submit_system(storage_engine_t *engine, const system_stats_t *stats) {
    if (!engine || !stats) return -1;

    char sql[2048];
    char be_esc[64], iface_esc[128];
    json_escape(stats->backend,   be_esc,    sizeof(be_esc));
    json_escape(stats->interface, iface_esc, sizeof(iface_esc));

    int n = snprintf(sql, sizeof(sql),
        "INSERT INTO %s.%ssystem FORMAT JSONEachRow\n"
        "{\"ts\":%" PRId64 ","
        "\"rx_packets\":%" PRIu64 ",\"rx_bytes\":%" PRIu64 ","
        "\"rx_dropped\":%" PRIu64 ",\"rx_errors\":%" PRIu64 ","
        "\"pps\":%f,\"bps\":%f,\"drop_rate\":%f,"
        "\"active_sessions\":%u,"
        "\"total_sessions_created\":%" PRIu64 ","
        "\"total_sessions_expired\":%" PRIu64 ","
        "\"backend\":\"%s\",\"interface\":\"%s\"}\n",
        engine->db_name, engine->table_prefix,
        (int64_t)stats->timestamp,
        stats->rx_packets, stats->rx_bytes,
        stats->rx_dropped, stats->rx_errors,
        stats->pps, stats->bps, stats->drop_rate,
        stats->active_sessions,
        stats->total_sessions_created,
        stats->total_sessions_expired,
        be_esc, iface_esc);

    if (n <= 0 || n >= (int)sizeof(sql)) return -1;
    return ch_exec(engine->ch_host, engine->ch_port,
                   engine->ch_user, engine->ch_password,
                   sql, (size_t)n);
}

int storage_engine_submit_protocol_stats(storage_engine_t *engine,
                                          const protocol_stats_t *stats,
                                          uint32_t count) {
    if (!engine || !stats || count == 0) return -1;

    for (uint32_t i = 0; i < count; i++) {
        protocol_doc_t doc = {0};
        strncpy(doc.protocol_name, stats[i].protocol_name,
                sizeof(doc.protocol_name) - 1);
        doc.protocol_id       = stats[i].protocol_id;
        doc.first_time        = stats[i].timestamp;
        doc.last_time         = stats[i].timestamp;
        doc.packets_processed = stats[i].packets_processed;
        doc.packets_matched   = stats[i].packets_matched;
        doc.bytes_processed   = stats[i].bytes_processed;
        doc.sessions_created  = stats[i].sessions_created;
        doc.errors            = stats[i].errors;
        doc.hourly_stats[0].hour    = TIMESTAMP_TO_HOUR(stats[i].timestamp);
        doc.hourly_stats[0].packets = stats[i].packets_processed;
        doc.hourly_stats[0].bytes   = stats[i].bytes_processed;
        doc.hourly_count = 1;
        virtual_collection_update_protocol(engine->vc, &doc);
    }
    return 0;
}

int storage_engine_submit_session(storage_engine_t *engine,
                                   const session_record_t *record) {
    if (!engine || !record) return -1;

    session_doc_t doc = {0};
    // memset(doc.session_id, 0, sizeof(doc.session_id));
    // memset(doc.src_ip, 0, sizeof(doc.src_ip));
    // memset(doc.dst_ip, 0, sizeof(doc.dst_ip));
    // memset(doc.detected_protocol, 0, sizeof(doc.detected_protocol));
    // memset(doc.state, 0, sizeof(doc.state));
    strncpy(doc.session_id,        record->session_id,        sizeof(doc.session_id)        - 1);
    strncpy(doc.src_ip,            record->src_ip,            sizeof(doc.src_ip)            - 1);
    strncpy(doc.dst_ip,            record->dst_ip,            sizeof(doc.dst_ip)            - 1);
    strncpy(doc.detected_protocol, record->detected_protocol, sizeof(doc.detected_protocol) - 1);
    strncpy(doc.state,             record->state,             sizeof(doc.state)             - 1);
    doc.src_port       = record->src_port;
    doc.dst_port       = record->dst_port;
    doc.protocol       = record->protocol;
    doc.first_time     = record->create_time;
    doc.last_time      = record->last_seen;
    doc.total_duration = record->duration;
    doc.packets_ab     = record->packets_ab;
    doc.packets_ba     = record->packets_ba;
    doc.bytes_ab       = record->bytes_ab;
    doc.bytes_ba       = record->bytes_ba;

    virtual_collection_update_session(engine->vc, &doc);
    return 0;
}

int storage_engine_submit_verdict(storage_engine_t *engine, const verdict_doc_t *doc) {
    if (!engine || !doc) return -1;
    /* 本轮 verdict 只保留在虚拟集合，供内存查询和画像关联使用。 */
    return virtual_collection_update_verdict(engine->vc, doc);
}

int storage_engine_submit_tunnel_signal(storage_engine_t *engine, const tunnel_signal_doc_t *doc) {
    if (!engine || !doc) return -1;
    return virtual_collection_update_tunnel_signal(engine->vc, doc);
}

int storage_engine_submit_auxiliary_signal(storage_engine_t *engine, const auxiliary_signal_doc_t *doc) {
    if (!engine || !doc) return -1;
    return virtual_collection_update_auxiliary_signal(engine->vc, doc);
}

static bool session_has_tunnel_signal(const tunnel_signal_t *sig)
{
    return sig && (sig->is_non_standard_tls || sig->disguise_type ||
                   sig->covert_channel_type || sig->heartbeat_mode ||
                   sig->threat_intel_match_field);
}

/* 可疑流量页高价值因子：隐蔽通道 / 伪装 / 心跳 / 威胁情报（不含 non_standard_tls） */
static bool session_has_covert_page_signal(const tunnel_signal_t *sig)
{
    return sig && (sig->disguise_type || sig->covert_channel_type ||
                   sig->heartbeat_mode || sig->threat_intel_match_field);
}

int storage_engine_submit_session_signals(storage_engine_t *engine, const session_entry_t *session) {
    const tunnel_signal_t *sig;
    if (!engine || !session) return -1;
    sig = (const tunnel_signal_t *)session->tunnel_signal;
    if (!session_has_tunnel_signal(sig)) {
        return 0;
    }
    {
        tunnel_signal_doc_t tdoc;
        if (storage_engine_build_tunnel_signal_doc(session, &tdoc) == 0)
            storage_engine_submit_tunnel_signal(engine, &tdoc);
    }
    if (session_has_covert_page_signal(sig)) {
        auxiliary_signal_doc_t adoc;
        if (storage_engine_build_auxiliary_signal_doc(session, &adoc) == 0)
            storage_engine_submit_auxiliary_signal(engine, &adoc);
    }
    return 0;
}

int storage_engine_submit_student(storage_engine_t *engine, const student_profile_doc_t *doc) {
    if (!engine || !doc) return -1;
    /* 兼容旧接口名，底层已切换为按 profile_key 聚合的 IP画像。 */
    return virtual_collection_update_student(engine->vc, doc);
}

int storage_engine_submit_ip_profile(storage_engine_t *engine, const ip_profile_doc_t *doc) {
    if (!engine || !doc) return -1;
    /* 当前阶段只做虚拟集合更新，不触发任何画像落库动作。 */
    return virtual_collection_update_student(engine->vc, doc);
}

/* ========== 指标 ========== */

void storage_engine_get_metrics(storage_engine_t *engine, storage_metrics_t *metrics) {
    if (!engine || !metrics) return;
    pthread_mutex_lock(&engine->metrics_lock);
    *metrics = engine->metrics;
    pthread_mutex_unlock(&engine->metrics_lock);
}

void storage_engine_print_stats(storage_engine_t *engine) {
    if (!engine || !engine->vc) return;
    printf("=== Storage Engine Stats (ClickHouse) ===\n");
    printf("  Host: %s:%d  DB: %s  Prefix: %s\n",
           engine->ch_host, engine->ch_port,
           engine->db_name, engine->table_prefix);
    printf("  IP collection: %zu\n",
           engine->vc->ip_collection ? engine->vc->ip_collection->count : 0);
    printf("  Session collection: %zu\n",
           engine->vc->session_collection ? engine->vc->session_collection->count : 0);
    printf("  Protocol collection: %zu\n",
           engine->vc->protocol_collection ? engine->vc->protocol_collection->count : 0);
    printf("==========================================\n");
}
