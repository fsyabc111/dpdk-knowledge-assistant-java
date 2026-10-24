/**
 * @file event_batch.c
 * @brief 协议事件批量写入 ClickHouse —— 实现
 *
 * 核心设计：
 *   - 持久 CURL handle（HTTP/1.1 keep-alive），整个进程生命周期只初始化一次
 *   - 按 event_type_t 分桶，每桶维护一个静态 chbuf_t（4 MB 栈外缓冲）
 *   - 一次 event_batch_flush() 调用：遍历所有事件 → 分桶序列化 → 逐桶 HTTP POST
 *   - 只在 storage lcore（单线程）调用，所有状态变量均为 file-scope static，无锁
 */

#include "event_batch.h"
#include "../plugin/plugins/ch_storage.h"
#include "../plugin/plugins/ch_tables.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>   /* strncasecmp（解析 X-ClickHouse-Summary 头用）*/
#include <stdint.h>
#include <inttypes.h>  /* PRIu64（此前经其他头间接引入，显式包含更稳）*/
#include <time.h>

#include <curl/curl.h>

/* ------------------------------------------------------------------ */
/*  内部配置                                                              */
/* ------------------------------------------------------------------ */

#define BATCH_BUF_SIZE (4 * 1024 * 1024)   /* 每桶 4 MB 静态缓冲 */
#define CURL_TIMEOUT_S 10L

/* ------------------------------------------------------------------ */
/*  持久连接状态                                                           */
/* ------------------------------------------------------------------ */

static CURL       *s_curl      = NULL;
static char        s_base_url[512] = {0};   /* http://host:port/?database=db&user=u&password=p */
static int         s_ready     = 0;

/* ------------------------------------------------------------------ */
/*  每类型的批量缓冲（file-scope，storage lcore 单线程访问）               */
/* ------------------------------------------------------------------ */

typedef struct {
    char   data[BATCH_BUF_SIZE];
    size_t len;
    int    has_header;
    int    rows;        /* 本批已序列化行数（written_rows 校验用）*/
} chbuf_t;

/* EV_TYPE_MAX = 27，下标 0 不用 */
static chbuf_t s_bufs[EV_TYPE_MAX];

/* ------------------------------------------------------------------ */
/*  ClickHouse 表名映射                                                    */
/* ------------------------------------------------------------------ */

static const char *ev_table_name(event_type_t t)
{
    switch (t) {
    case EV_HTTP:          return CH_TBL_HTTP;
    case EV_HTTPS:         return CH_TBL_HTTPS;
    case EV_TLS:           return CH_TBL_TLS;
    case EV_JA3:           return CH_TBL_JA3;
    case EV_FTP:           return CH_TBL_FTP;
    case EV_SMTP:          return CH_TBL_SMTP;
    case EV_SSH:           return CH_TBL_SSH;
    case EV_QUIC:          return CH_TBL_QUIC;
    case EV_WIREGUARD:     return CH_TBL_WIREGUARD;
    case EV_POP3:          return CH_TBL_POP3;
    case EV_IMAP:          return CH_TBL_IMAP;
    case EV_RTSP:          return CH_TBL_RTSP;
    case EV_IPSECVPN:      return CH_TBL_IPSECVPN;
    case EV_OPENVPN:       return CH_TBL_OPENVPN;
    case EV_DNS:           return CH_TBL_DNS_RESOLUTION;
    case EV_DTLS:          return CH_TBL_DTLS;
    case EV_TLS_INFRA:     return CH_TBL_TLS_INFRA;
    case EV_RDP:           return CH_TBL_RDP;
    case EV_RTMP:          return CH_TBL_RTMP;
    case EV_SMB:           return CH_TBL_SMB;
    case EV_GEO:           return CH_TBL_GEO_ALERT;
    case EV_DOMAIN_ACCESS: return CH_TBL_DOMAIN_ACCESS;
    case EV_FOCUS_SITE:    return CH_TBL_FOCUS_SITE;
    case EV_THREAT_DOMAIN: return CH_TBL_TI_HITS_V2;  /* Phase 5：写新富 schema 表 */
    case EV_SITE_ACCESS:   return CH_TBL_SITE_ACCESS_RANK;
    case EV_ICMP_TUNNEL:   return CH_TBL_ICMP;
    case EV_V2RAY:         return CH_TBL_V2RAY_EVENTS;
    case EV_IDS:           return CH_TBL_IDS_HITS;
    case EV_SHADOWSOCKS:   return CH_TBL_SHADOWSOCKS_EVENTS;
    default:               return NULL;
    }
}

/* ------------------------------------------------------------------ */
/*  curl 响应体丢弃回调                                                    */
/* ------------------------------------------------------------------ */

static size_t curl_discard_cb(void *ptr, size_t size, size_t nmemb, void *ud)
{
    (void)ptr; (void)ud;
    return size * nmemb;
}

/* ------------------------------------------------------------------ */
/*  written_rows 观测（任务：async_insert=1 下 200 但实际写 0 行无人察觉） */
/* ------------------------------------------------------------------ */

/* 最近一次 POST 响应 X-ClickHouse-Summary 头中的 written_rows；-1=响应未携带
 * （老版本 CH 或异常响应时保持 -1，视为"未知"跳过校验，不误报）。 */
static int64_t s_last_written_rows = -1;

/* 响应头回调：仅提取 X-ClickHouse-Summary 的 written_rows。
 * 纯观测，不改任何写入行为。 */
static size_t curl_header_cb(char *ptr, size_t size, size_t nmemb, void *ud)
{
    (void)ud;
    size_t total = size * nmemb;
    static const char k[] = "X-ClickHouse-Summary:";

    if (total > sizeof(k) - 1 && strncasecmp(ptr, k, sizeof(k) - 1) == 0) {
        /* ptr 不保证 NUL 结尾，先拷贝到栈上再用字符串函数解析 */
        char line[512];
        size_t n = total - (sizeof(k) - 1);
        if (n >= sizeof(line)) n = sizeof(line) - 1;
        memcpy(line, ptr + sizeof(k) - 1, n);
        line[n] = '\0';
        const char *w = strstr(line, "\"written_rows\":\"");
        if (w) {
            s_last_written_rows = (int64_t)strtoll(w + 16, NULL, 10);
        }
    }
    return total;
}

/* ------------------------------------------------------------------ */
/*  初始化                                                                 */
/* ------------------------------------------------------------------ */

int event_batch_init(const char *ch_url, const char *db_name,
                     const char *ch_user, const char *ch_password)
{
    s_curl = curl_easy_init();
    if (!s_curl) {
        fprintf(stderr, "[EVENT_BATCH] curl_easy_init 失败\n");
        return -1;
    }

    /* 构造带认证参数的基础 URL */
    if (ch_user && ch_user[0]) {
        snprintf(s_base_url, sizeof(s_base_url),
                 "%s/?database=%s&user=%s&password=%s",
                 ch_url, db_name, ch_user, ch_password ? ch_password : "");
    } else {
        snprintf(s_base_url, sizeof(s_base_url),
                 "%s/?database=%s", ch_url, db_name);
    }

    /* 持久化选项（整个生命周期只设一次）*/
    curl_easy_setopt(s_curl, CURLOPT_URL,           s_base_url);
    curl_easy_setopt(s_curl, CURLOPT_WRITEFUNCTION, curl_discard_cb);
    /* 解析响应头里的 X-ClickHouse-Summary（written_rows 观测），响应体仍丢弃 */
    curl_easy_setopt(s_curl, CURLOPT_HEADERFUNCTION, curl_header_cb);
    curl_easy_setopt(s_curl, CURLOPT_TIMEOUT,       CURL_TIMEOUT_S);
    curl_easy_setopt(s_curl, CURLOPT_NOSIGNAL,      1L);
    curl_easy_setopt(s_curl, CURLOPT_TCP_KEEPALIVE, 1L);
    curl_easy_setopt(s_curl, CURLOPT_TCP_KEEPIDLE,  60L);
    curl_easy_setopt(s_curl, CURLOPT_TCP_KEEPINTVL, 10L);
    /* HTTP/1.1 默认 keep-alive，ClickHouse 支持 */
    curl_easy_setopt(s_curl, CURLOPT_HTTP_VERSION,  CURL_HTTP_VERSION_1_1);

    memset(s_bufs, 0, sizeof(s_bufs));
    s_ready = 1;
    return 0;
}

void event_batch_cleanup(void)
{
    if (s_curl) {
        curl_easy_cleanup(s_curl);
        s_curl = NULL;
    }
    s_ready = 0;
}

/* ------------------------------------------------------------------ */
/*  单桶 HTTP POST                                                        */
/* ------------------------------------------------------------------ */

static void buf_http_post(chbuf_t *b)
{
    if (!s_curl || b->len == 0 || !b->has_header) return;

    curl_easy_setopt(s_curl, CURLOPT_POSTFIELDSIZE, (long)b->len);
    curl_easy_setopt(s_curl, CURLOPT_POSTFIELDS,    b->data);

    s_last_written_rows = -1;   /* 每次 POST 前复位，由 header 回调重新填充 */

    CURLcode res = curl_easy_perform(s_curl);
    if (res != CURLE_OK) {
        fprintf(stderr, "[EVENT_BATCH] curl POST 失败: %s\n",
                curl_easy_strerror(res));
    } else {
        long http_code = 0;
        curl_easy_getinfo(s_curl, CURLINFO_RESPONSE_CODE, &http_code);
        if (http_code != 200) {
            /* 截断打印前 500 字节方便排查 */
            char preview[501] = {0};
            size_t plen = b->len < 500 ? b->len : 500;
            memcpy(preview, b->data, plen);
            fprintf(stderr, "[EVENT_BATCH] ClickHouse 返回 %ld，SQL 前缀: %s\n",
                    http_code, preview);
        } else if (b->rows > 0 && s_last_written_rows == 0) {
            /* async_insert=1 时 CH 可能返回 200 但实际写入 0 行，此前无人察觉。
             * 仅观测告警：每 60s 限流一条并累计被抑制批数，不重试不改写入行为。 */
            static time_t   s_zero_warn_last = 0;
            static uint64_t s_zero_warn_hits = 0;
            time_t now = time(NULL);
            s_zero_warn_hits++;
            if (now - s_zero_warn_last >= 60) {
                /* b->data 无 NUL 结尾，精度用 b->len 钳位，避免读到上一批残留 */
                int pfx = b->len < 120 ? (int)b->len : 120;
                fprintf(stderr, "[EVENT_BATCH] WARN: HTTP 200 但 written_rows=0 "
                        "(本批 %d 行，60s 内累计 %" PRIu64 " 批)，SQL 前缀: %.*s\n",
                        b->rows, s_zero_warn_hits, pfx, b->data);
                s_zero_warn_last = now;
                s_zero_warn_hits = 0;
            }
        }
    }

    /* 重置缓冲 */
    b->len        = 0;
    b->has_header = 0;
    b->rows       = 0;
}

/* ------------------------------------------------------------------ */
/*  JSON 序列化辅助：追加带转义的字符串字段                                */
/* ------------------------------------------------------------------ */

/* 向 buf 追加 JSON 字符串值（已含双引号），自动转义双引号和反斜杠 */
static size_t append_json_str(char *buf, size_t cap, size_t off,
                               const char *s)
{
    if (off >= cap) return off;
    buf[off++] = '"';
    for (; *s && off + 2 < cap; s++) {
        if (*s == '"' || *s == '\\') buf[off++] = '\\';
        buf[off++] = *s;
    }
    if (off < cap) buf[off++] = '"';
    return off;
}

/* 简化宏：追加 key:value 字符串字段（末尾带逗号）*/
#define JSTR(key, val) \
    do { \
        off += (size_t)snprintf(buf+off, cap-off, "\"" key "\":"); \
        off = append_json_str(buf, cap, off, (val)); \
        if (off < cap) buf[off++] = ','; \
    } while(0)

#define JNUM(key, fmt, val) \
    do { off += (size_t)snprintf(buf+off, cap-off, "\"" key "\":" fmt ",", (val)); } while(0)

/* 把 ClickHouse VALUES 格式的数组字面量（如 ['1.2.3.4','5.6.7.8']）
 * 转成 JSONEachRow 能接受的 JSON 数组（["1.2.3.4","5.6.7.8"]），
 * 做法很简单：只换单引号为双引号，内部是 IP / 域名 / 数字，不会有歧义字符。*/
#define JARR_CH(key, src) \
    do { \
        off += (size_t)snprintf(buf+off, cap-off, "\"" key "\":"); \
        const char *_p = (src); \
        if (!_p || !*_p) { \
            if (off + 2 < cap) { buf[off++] = '['; buf[off++] = ']'; } \
        } else { \
            for (; *_p && off + 1 < cap; _p++) { \
                buf[off++] = (*_p == '\'') ? '"' : *_p; \
            } \
        } \
        if (off < cap) buf[off++] = ','; \
    } while(0)

/* 把 Unix 秒格式化为 "YYYY-MM-DD HH:MM:SS" 字符串，写入 captured_at 字段。
 * 历史 bug：之前所有 case 都用 JNUM("ts", ...)，而 ClickHouse 表列名叫 captured_at；
 * 在 INSERT ... FORMAT JSONEachRow + input_format_skip_unknown_fields=1 的默认设置下，
 * "ts" 被静默丢弃，captured_at 走默认值，结果落库全是 UInt32 MAX（2106-02-07）。
 * 这里用字符串 DateTime 写入，既匹配列名，又绕开 date_time_input_format=basic 的限制。*/
#define JTS(ts_sec) \
    do { \
        time_t _t = (time_t)(ts_sec); \
        struct tm _tm; \
        localtime_r(&_t, &_tm); \
        off += (size_t)snprintf(buf+off, cap-off, \
            "\"captured_at\":\"%04d-%02d-%02d %02d:%02d:%02d\",", \
            _tm.tm_year + 1900, _tm.tm_mon + 1, _tm.tm_mday, \
            _tm.tm_hour, _tm.tm_min, _tm.tm_sec); \
    } while(0)

/* ------------------------------------------------------------------ */
/*  各类型 JSON 序列化（返回写入字节数，失败返回 0）                       */
/* ------------------------------------------------------------------ */

static size_t ev_to_json(const event_task_t *ev, char *buf, size_t cap)
{
    size_t off = 0;
    const ev_payload_u *p = &ev->payload;

    if (off < cap) buf[off++] = '{';

    switch (ev->type) {
    case EV_HTTP:
        JSTR("src_ip",       p->http.src_ip);
        JSTR("dst_ip",       p->http.dst_ip);
        JNUM("src_port",     "%u", p->http.src_port);
        JNUM("dst_port",     "%u", p->http.dst_port);
        JSTR("method",       p->http.method);
        JSTR("uri",          p->http.uri);
        JSTR("host",         p->http.host);
        JSTR("user_agent",   p->http.user_agent);
        JSTR("content_type", p->http.content_type);
        JNUM("protocol_type","%d", p->http.protocol_type);
        JTS(p->http.ts_sec);
        break;
    case EV_HTTPS:
        JSTR("src_ip",      p->https.src_ip);
        JSTR("dst_ip",      p->https.dst_ip);
        JNUM("src_port",    "%u", p->https.src_port);
        JNUM("dst_port",    "%u", p->https.dst_port);
        JNUM("bytes",       "%"PRIu64, p->https.bytes);
        JNUM("packets",     "%"PRIu32, p->https.packets);
        JNUM("tls_version", "%u", p->https.tls_version);
        JSTR("sni",         p->https.sni);
        JTS(p->https.ts_sec);
        break;
    case EV_TLS:
        JSTR("src_ip",              p->tls.src_ip);
        JSTR("dst_ip",              p->tls.dst_ip);
        JNUM("src_port",            "%u", p->tls.src_port);
        JNUM("dst_port",            "%u", p->tls.dst_port);
        JNUM("tls_version",         "%u", p->tls.tls_version);
        JSTR("sni",                 p->tls.sni);
        JSTR("cipher_suites",       p->tls.cipher_suites);
        JNUM("cipher_suites_count", "%u", p->tls.cipher_suites_count);
        JNUM("extensions_count",    "%u", p->tls.extensions_count);
        JTS(p->tls.ts_sec);
        break;
    case EV_JA3:
        JSTR("src_ip",           p->ja3.src_ip);
        JSTR("dst_ip",           p->ja3.dst_ip);
        JNUM("src_port",         "%u", p->ja3.src_port);
        JNUM("dst_port",         "%u", p->ja3.dst_port);
        JSTR("sni",              p->ja3.sni);
        JNUM("tls_version",      "%u", p->ja3.tls_version);
        JSTR("cipher_suites",    p->ja3.cipher_suites);
        JSTR("extensions",       p->ja3.extensions);
        JSTR("elliptic_curves",  p->ja3.elliptic_curves);
        JSTR("ec_point_formats", p->ja3.ec_point_formats);
        JSTR("ja3_string",       p->ja3.ja3_string);
        JSTR("ja3_hash",         p->ja3.ja3_hash);
        JTS(p->ja3.ts_sec);
        break;
    /* simple 类型公共 7 字段 + 按 EV 类型可选的 extra 列 */
    case EV_FTP:
    case EV_SMTP:
    case EV_WIREGUARD:
    case EV_POP3:
    case EV_IMAP:
    case EV_RTSP:
    case EV_IPSECVPN:
    case EV_OPENVPN:
        JSTR("src_ip",        p->simple.src_ip);
        JSTR("dst_ip",        p->simple.dst_ip);
        JNUM("src_port",      "%u", p->simple.src_port);
        JNUM("dst_port",      "%u", p->simple.dst_port);
        JNUM("protocol",      "%u", p->simple.protocol);
        JSTR("protocol_name", p->simple.protocol_name);
        /* 按 EV 类型映射 extra 列：ftp→file_name / smtp-pop3→email */
        if (ev->type == EV_FTP) {
            JSTR("file_name", p->simple.extra);
        } else if (ev->type == EV_SMTP || ev->type == EV_POP3) {
            JSTR("email",     p->simple.extra);
        }
        JTS(p->simple.ts_sec);
        break;
    case EV_SSH:
        JSTR("src_ip",         p->ssh.src_ip);
        JSTR("dst_ip",         p->ssh.dst_ip);
        JNUM("src_port",       "%u", p->ssh.src_port);
        JNUM("dst_port",       "%u", p->ssh.dst_port);
        JNUM("protocol",       "%u", p->ssh.protocol);
        JSTR("protocol_name",  p->ssh.protocol_name);
        JSTR("client_version", p->ssh.client_version);
        JSTR("server_version", p->ssh.server_version);
        JTS(p->ssh.ts_sec);
        break;
    case EV_QUIC:
        JSTR("src_ip",            p->quic.src_ip);
        JSTR("dst_ip",            p->quic.dst_ip);
        JNUM("src_port",          "%u", p->quic.src_port);
        JNUM("dst_port",          "%u", p->quic.dst_port);
        JNUM("protocol",          "%u", p->quic.protocol);
        JSTR("protocol_name",     p->quic.protocol_name);
        JNUM("last_header_form",  "%u", p->quic.last_header_form);
        JNUM("last_long_type",    "%u", p->quic.last_long_type);
        JNUM("packet_number_len", "%u", p->quic.packet_number_len);
        JNUM("dcid_len",          "%u", p->quic.dcid_len);
        JNUM("scid_len",          "%u", p->quic.scid_len);
        JNUM("packet_number",     "%"PRIu32, p->quic.packet_number);
        JSTR("dcid",              p->quic.dcid);
        JSTR("scid",              p->quic.scid);
        JNUM("quic_version",      "%"PRIu32, p->quic.quic_version);
        JSTR("sni",               p->quic.sni);
        JSTR("alpn",              p->quic.alpn);
        JNUM("is_doq",            "%u", p->quic.is_doq);
        JNUM("confidence",        "%u", p->quic.confidence);
        JSTR("detect_reason",     p->quic.detect_reason);
        JTS(p->quic.ts_sec);
        break;
    case EV_DNS:
        JSTR("client_ip",        p->dns.client_ip);
        JSTR("resolver_ip",      p->dns.resolver_ip);
        JNUM("src_port",         "%u", p->dns.src_port);
        JNUM("dst_port",         "%u", p->dns.dst_port);
        JSTR("transport_proto",  p->dns.transport_proto);
        JSTR("qname",            p->dns.qname);
        JNUM("qtype",            "%u", p->dns.qtype);
        JNUM("rcode",            "%u", p->dns.rcode);
        /* 这四个字段是 CH SQL VALUES 风格数组 ['a','b']，
         * JSONEachRow 需要 ["a","b"]，JARR_CH 把单引号转双引号 */
        JARR_CH("answer_ips",     p->dns.answer_ips_ch);
        JARR_CH("answer_domains", p->dns.answer_domains_ch);
        JARR_CH("answer_types",   p->dns.answer_types_ch);
        JARR_CH("ttls",           p->dns.ttls_ch);
        JNUM("answer_count",     "%u", p->dns.answer_count);
        JNUM("is_success",       "%u", p->dns.is_success);
        JNUM("is_cross_border",  "%u", p->dns.is_cross_border);
        JNUM("foreign_ip_count", "%u", p->dns.foreign_ip_count);
        JSTR("country_codes",    p->dns.country_codes_ch);
        JTS(p->dns.ts_sec);
        break;
    case EV_DTLS:
        JSTR("src_ip",         p->dtls.src_ip);
        JSTR("dst_ip",         p->dtls.dst_ip);
        JNUM("src_port",       "%u", p->dtls.src_port);
        JNUM("dst_port",       "%u", p->dtls.dst_port);
        JNUM("version",        "%u", p->dtls.version);
        JNUM("record_type",    "%u", p->dtls.record_type);
        JNUM("handshake_type", "%u", p->dtls.handshake_type);
        JNUM("epoch",          "%u", p->dtls.epoch);
        JNUM("record_len",     "%u", p->dtls.record_len);
        JNUM("payload_len",    "%"PRIu32, p->dtls.payload_len);
        JSTR("detail_desc",    p->dtls.detail_desc);
        JNUM("is_handshake",   "%u", p->dtls.is_handshake);
        JNUM("is_appdata",     "%u", p->dtls.is_appdata);
        JNUM("is_alert",       "%u", p->dtls.is_alert);
        JTS(p->dtls.ts_sec);
        break;
    case EV_TLS_INFRA:
        JSTR("src_ip",              p->tls_infra.src_ip);
        JSTR("dst_ip",              p->tls_infra.dst_ip);
        JNUM("src_port",            "%u", p->tls_infra.src_port);
        JNUM("dst_port",            "%u", p->tls_infra.dst_port);
        JNUM("tls_record_version",  "%u", p->tls_infra.tls_record_version);
        JNUM("record_type",         "%u", p->tls_infra.record_type);
        JNUM("record_len",          "%u", p->tls_infra.record_len);
        JNUM("payload_len",         "%"PRIu32, p->tls_infra.payload_len);
        JSTR("detected_type",       p->tls_infra.detected_type);
        JSTR("detection_method",    p->tls_infra.detection_method);
        JSTR("sni",                 p->tls_infra.sni);
        JSTR("alpn",                p->tls_infra.alpn);
        JNUM("is_doh",              "%u", p->tls_infra.is_doh);
        JNUM("is_dot",              "%u", p->tls_infra.is_dot);
        JNUM("is_ech",              "%u", p->tls_infra.is_ech);
        JNUM("is_dns_tunnel",       "%u", p->tls_infra.is_dns_tunnel);
        JTS(p->tls_infra.ts_sec);
        break;
    case EV_RDP:
        JSTR("src_ip",               p->rdp.src_ip);
        JSTR("dst_ip",               p->rdp.dst_ip);
        JNUM("src_port",             "%u", p->rdp.src_port);
        JNUM("dst_port",             "%u", p->rdp.dst_port);
        JNUM("tpdu_type",            "%u", p->rdp.tpdu_type);
        JSTR("tpdu_type_name",       p->rdp.tpdu_type_name);
        JSTR("cookie",               p->rdp.cookie);
        JNUM("requested_protocols",  "%"PRIu32, p->rdp.requested_protocols);
        JNUM("cookie_seen",          "%u", p->rdp.cookie_seen);
        JNUM("security_seen",        "%u", p->rdp.security_seen);
        JNUM("payload_len",          "%"PRIu32, p->rdp.payload_len);
        JTS(p->rdp.ts_sec);
        break;
    case EV_RTMP:
        JSTR("src_ip",      p->rtmp.src_ip);
        JSTR("dst_ip",      p->rtmp.dst_ip);
        JNUM("src_port",    "%u", p->rtmp.src_port);
        JNUM("dst_port",    "%u", p->rtmp.dst_port);
        JSTR("event_type",  p->rtmp.event_type);
        JNUM("is_handshake","%u", p->rtmp.is_handshake);
        JNUM("is_command",  "%u", p->rtmp.is_command);
        JNUM("payload_len", "%"PRIu32, p->rtmp.payload_len);
        JTS(p->rtmp.ts_sec);
        break;
    case EV_SMB:
        JSTR("src_ip",          p->smb.src_ip);
        JSTR("dst_ip",          p->smb.dst_ip);
        JNUM("src_port",        "%u", p->smb.src_port);
        JNUM("dst_port",        "%u", p->smb.dst_port);
        JNUM("smb_version",     "%u", p->smb.smb_version);
        JSTR("smb_version_name",p->smb.smb_version_name);
        JNUM("payload_len",     "%"PRIu32, p->smb.payload_len);
        JTS(p->smb.ts_sec);
        break;
    case EV_GEO:
        JSTR("event_type",  p->geo.event_type);
        JSTR("session_id",  p->geo.session_id);
        JSTR("src_ip",      p->geo.src_ip);
        JSTR("dst_ip",      p->geo.dst_ip);
        JSTR("dst_country", p->geo.dst_country);
        JNUM("src_port",    "%u", p->geo.src_port);
        JNUM("dst_port",    "%u", p->geo.dst_port);
        JNUM("protocol",    "%u", p->geo.protocol);
        JNUM("bytes_up",    "%"PRIu64, p->geo.bytes_up);
        JNUM("bytes_down",  "%"PRIu64, p->geo.bytes_down);
        JNUM("ratio",       "%.6f", p->geo.ratio);
        JSTR("message",     p->geo.message);
        JTS(p->geo.ts_sec);
        break;
    case EV_DOMAIN_ACCESS:
    case EV_FOCUS_SITE:
        JSTR("src_ip",       p->domain.src_ip);
        JSTR("dst_ip",       p->domain.dst_ip);
        JNUM("src_port",     "%u", p->domain.src_port);
        JNUM("dst_port",     "%u", p->domain.dst_port);
        JNUM("protocol",     "%u", p->domain.protocol);
        JSTR("domain",       p->domain.domain);
        JSTR("domain_group", p->domain.domain_group);
        JSTR("source_type",  p->domain.source_type);
        JSTR("focus_tag",    p->domain.focus_tag);
        JTS(p->domain.ts_sec);
        break;
    case EV_THREAT_DOMAIN: {
        /* Phase 5：写 ti_hits_v2 富 schema。
         * 字段映射：旧 source_type/threat_type/intel_source/domain → matched_field/-/matched_source/matched_value */
        const char *_field_name = "domain";
        switch (p->threat.v2_match_field) {
        case 1: _field_name = "ip";     break;
        case 2: _field_name = "domain"; break;
        case 3: _field_name = "sni";    break;
        case 4: _field_name = "ja3";    break;
        case 5: _field_name = "url";    break;
        }
        const char *_mode_name = "exact";
        switch (p->threat.v2_match_mode) {
        case 1: _mode_name = "exact";    break;
        case 2: _mode_name = "cidr";     break;
        case 3: _mode_name = "wildcard"; break;
        }
        const char *_tlp_name = "white";
        switch (p->threat.v2_tlp) {
        case 0: _tlp_name = "white"; break;
        case 1: _tlp_name = "green"; break;
        case 2: _tlp_name = "amber"; break;
        case 3: _tlp_name = "red";   break;
        }
        JSTR("src_ip",          p->threat.src_ip);
        JSTR("dst_ip",          p->threat.dst_ip);
        JNUM("src_port",        "%u", p->threat.src_port);
        JNUM("dst_port",        "%u", p->threat.dst_port);
        JNUM("l4_proto",        "%u", p->threat.protocol);
        JSTR("l7_proto",        p->threat.v2_l7_proto);
        JSTR("matched_field",   _field_name);
        JSTR("matched_mode",    _mode_name);
        JSTR("matched_value",   p->threat.domain);
        JSTR("matched_source",  p->threat.intel_source);
        JSTR("matched_stix_id", p->threat.v2_stix_id);
        JNUM("confidence",      "%u", p->threat.v2_confidence);
        JSTR("tlp",             _tlp_name);
        JTS(p->threat.ts_sec);  /* captured_at */
        break;
    }
    case EV_SITE_ACCESS:
        JSTR("domain",       p->site_access.domain);
        JSTR("domain_group", p->site_access.domain_group);
        JSTR("src_ip",       p->site_access.src_ip);
        JTS(p->site_access.ts_sec);
        break;
    case EV_ICMP_TUNNEL:
        JSTR("src_ip",      p->icmp.src_ip);
        JSTR("dst_ip",      p->icmp.dst_ip);
        JSTR("session_id",  p->icmp.session_id);
        JNUM("icmp_type",   "%u", p->icmp.icmp_type);
        JNUM("icmp_code",   "%u", p->icmp.icmp_code);
        JNUM("identifier",  "%u", p->icmp.identifier);
        JNUM("seq_num",     "%u", p->icmp.seq_num);
        JNUM("payload_len", "%u", p->icmp.payload_len);
        JSTR("payload_hex", p->icmp.payload_hex);
        JSTR("payload_text",p->icmp.payload_text);
        JTS(p->icmp.ts_sec);
        break;
    case EV_V2RAY:
        JSTR("src_ip",           p->v2ray.src_ip);
        JSTR("dst_ip",           p->v2ray.dst_ip);
        JNUM("src_port",         "%u", p->v2ray.src_port);
        JNUM("dst_port",         "%u", p->v2ray.dst_port);
        JSTR("detected_type",    p->v2ray.detected_type);
        JSTR("detection_method", p->v2ray.detection_method);
        JSTR("protocol_name",    p->v2ray.protocol_name);
        JSTR("method",           p->v2ray.method);
        JSTR("host",             p->v2ray.host);
        JSTR("path",             p->v2ray.path);
        JSTR("upgrade",          p->v2ray.upgrade);
        JNUM("score",            "%u", p->v2ray.score);
        JNUM("avg_entropy",      "%.6f", p->v2ray.avg_entropy);
        JNUM("dominant_ratio",   "%.6f", p->v2ray.dominant_ratio);
        JNUM("packet_count",     "%"PRIu64, p->v2ray.packet_count);
        JNUM("packets_ab",       "%"PRIu64, p->v2ray.packets_ab);
        JNUM("packets_ba",       "%"PRIu64, p->v2ray.packets_ba);
        JNUM("bytes_ab",         "%"PRIu64, p->v2ray.bytes_ab);
        JNUM("bytes_ba",         "%"PRIu64, p->v2ray.bytes_ba);
        JNUM("saw_connect",      "%u", p->v2ray.saw_connect);
        JNUM("saw_ws_upgrade",   "%u", p->v2ray.saw_ws_upgrade);
        JNUM("saw_proxy_auth",   "%u", p->v2ray.saw_proxy_auth);
        JNUM("saw_path_hint",    "%u", p->v2ray.saw_path_hint);
        JTS(p->v2ray.ts_sec);
        break;
    case EV_IDS:
        JSTR("src_ip",         p->ids.src_ip);
        JSTR("dst_ip",         p->ids.dst_ip);
        JNUM("src_port",       "%u", p->ids.src_port);
        JNUM("dst_port",       "%u", p->ids.dst_port);
        JNUM("l4_proto",       "%u", p->ids.protocol);
        JSTR("host",           p->ids.host);
        JSTR("uri",            p->ids.uri);
        JSTR("method",         p->ids.method);
        JNUM("sid",            "%d", p->ids.sid);
        JSTR("msg",            p->ids.msg);
        JSTR("cve",            p->ids.cve);
        JSTR("rule_source",    p->ids.rule_source);
        JSTR("matched_needle", p->ids.matched_needle);
        JTS(p->ids.ts_sec);
        break;
    case EV_SHADOWSOCKS: {
        /* 与 ch_insert_shadowsocks_event()（同步路径）的 23 列严格一致；
         * 派生列 state_name/confidence_name/path_name/reason_text 复用
         * ch_storage.c 的 ss_* 辅助函数，避免两条写库路径产生不同的行。
         * 历史 bug：本 case 缺失导致 DPDK 生产模式下 Shadowsocks 事件
         * 在 ev_table_name() 返回 NULL 处被丢弃，整表无数据（仅 pcap 调试
         * 模式经 ch_event.c 同步 fallback 才有数据）。 */
        JTS(p->shadowsocks.ts_sec);                       /* captured_at */
        JSTR("src_ip",          p->shadowsocks.src_ip);
        JSTR("dst_ip",          p->shadowsocks.dst_ip);
        JNUM("src_port",        "%u", p->shadowsocks.src_port);
        JNUM("dst_port",        "%u", p->shadowsocks.dst_port);
        JNUM("transport_proto", "%u", p->shadowsocks.transport_proto);
        JSTR("protocol_name",   "Shadowsocks");
        JNUM("state",           "%u", p->shadowsocks.state);
        JSTR("state_name",      ss_state_name(p->shadowsocks.state));
        JNUM("confidence",      "%u", p->shadowsocks.confidence);
        JSTR("confidence_name", ss_confidence_name(p->shadowsocks.confidence));
        JNUM("path_type",       "%u", p->shadowsocks.path_type);
        JSTR("path_name",       ss_path_name(p->shadowsocks.path_type));
        JNUM("score",           "%u", p->shadowsocks.score);
        JNUM("reason_mask",     "%u", p->shadowsocks.reason_mask);
        char _ss_reason[256];
        ss_format_reason(p->shadowsocks.reason_mask, _ss_reason, sizeof(_ss_reason));
        JSTR("reason_text",     _ss_reason);
        JNUM("avg_entropy",     "%.6f", p->shadowsocks.avg_entropy);
        JNUM("max_entropy",     "%.6f", p->shadowsocks.max_entropy);
        JNUM("packets_ab",      "%" PRIu64, p->shadowsocks.packets_ab);
        JNUM("packets_ba",      "%" PRIu64, p->shadowsocks.packets_ba);
        JNUM("bytes_ab",        "%" PRIu64, p->shadowsocks.bytes_ab);
        JNUM("bytes_ba",        "%" PRIu64, p->shadowsocks.bytes_ba);
        {   /* first_seen_ts 是 DateTime 列，与 captured_at 一样需字符串格式 */
            time_t _ft = (time_t)p->shadowsocks.first_seen_ts;
            struct tm _ftm; localtime_r(&_ft, &_ftm);
            off += (size_t)snprintf(buf+off, cap-off,
                "\"first_seen_ts\":\"%04d-%02d-%02d %02d:%02d:%02d\",",
                _ftm.tm_year+1900, _ftm.tm_mon+1, _ftm.tm_mday,
                _ftm.tm_hour, _ftm.tm_min, _ftm.tm_sec);
        }
        break;
    }
    default:
        /* 未知类型：输出空对象，外层会跳过 */
        if (off < cap) buf[off++] = '}';
        return off;
    }

    /* 去掉最后一个多余逗号，关闭 JSON 对象 */
    if (off > 1 && buf[off - 1] == ',') off--;
    if (off < cap) buf[off++] = '}';
    return off;
}

/* ------------------------------------------------------------------ */
/*  主接口：批量 flush                                                    */
/* ------------------------------------------------------------------ */

void event_batch_flush(event_task_t **evs, unsigned nb_ev)
{
    if (!s_ready || !s_curl || nb_ev == 0) return;

    /* 遍历所有事件，按 type 追加到对应 bucket */
    for (unsigned i = 0; i < nb_ev; i++) {
        event_task_t *ev = evs[i];
        if (!ev) continue;
        if (ev->type <= 0 || ev->type >= EV_TYPE_MAX) continue;

        const char *tbl = ev_table_name(ev->type);
        if (!tbl) continue;

        chbuf_t *b = &s_bufs[ev->type];

        /* 写 INSERT 头（首次）*/
        if (!b->has_header) {
            b->len = (size_t)snprintf(b->data, BATCH_BUF_SIZE,
                                      "INSERT INTO %s FORMAT JSONEachRow\n",
                                      tbl);
            b->has_header = 1;
        }

        /* 序列化一行 JSON */
        if (b->len + 4096 < BATCH_BUF_SIZE) {
            size_t written = ev_to_json(ev, b->data + b->len,
                                        BATCH_BUF_SIZE - b->len - 2);
            if (written > 0) {
                b->len += written;
                b->data[b->len++] = '\n';
                b->rows++;          /* 行计数：written_rows 校验的分母 */
            }
        }
        /* 缓冲接近满时提前 flush 本桶 */
        if (b->len + 8192 >= BATCH_BUF_SIZE) {
            buf_http_post(b);
        }
    }

    /* 逐桶 flush 剩余数据 */
    for (int t = 1; t < EV_TYPE_MAX; t++) {
        if (s_bufs[t].has_header) {
            buf_http_post(&s_bufs[t]);
        }
    }
}
