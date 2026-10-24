/**
 * @file ch_storage.c
 * @brief ClickHouse 存储模块实现
 *
 * 通过 ClickHouse HTTP 接口发送 INSERT INTO ... VALUES (...) 语句。
 * 依赖：libcurl
 *
 * 设计要点：
 *  - 全局单例，失败时仅打印警告，不影响主程序运行
 *  - 使用 VALUES 格式（行文本），无需额外序列化库
 *  - 字符串字段中的单引号、反斜杠做转义，防止 SQL 注入
 */

#include "ch_storage.h"
#include "ch_tables.h"
#include "ch_event.h"       /* for ev_threat_t */
#include "../../log.h"

#include <curl/curl.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

/* ------------------------------------------------------------------ */
/*  内部状态                                                            */
/* ------------------------------------------------------------------ */

static char  g_url[512]      = {0};   /* http://host:port          */
static char  g_db[64]        = {0};   /* 数据库名                  */
static char  g_user[128]     = {0};   /* 用户名（可为空）          */
static char  g_password[128] = {0};   /* 密码（可为空）            */
static bool  g_ready         = false;

/* ------------------------------------------------------------------ */
/*  辅助：转义字符串（单引号 → \'，反斜杠 → \\）                        */
/* ------------------------------------------------------------------ */

static void escape_str(const char *src, char *dst, size_t dst_size)
{
    if (!src) { dst[0] = '\0'; return; }
    size_t j = 0;
    for (size_t i = 0; src[i] && j + 2 < dst_size; i++) {
        if (src[i] == '\'') {
            dst[j++] = '\\';
            dst[j++] = '\'';
        } else if (src[i] == '\\') {
            dst[j++] = '\\';
            dst[j++] = '\\';
        } else {
            dst[j++] = src[i];
        }
    }
    dst[j] = '\0';
}

/* ------------------------------------------------------------------ */
/*  辅助：用 libcurl 执行一次 HTTP POST（SQL 查询）                     */
/* ------------------------------------------------------------------ */

/* libcurl 写回调：丢弃响应体（只关心状态码）*/
static size_t curl_discard_cb(void *ptr, size_t size, size_t nmemb, void *userdata)
{
    (void)ptr; (void)userdata;
    return size * nmemb;
}

static int ch_exec(const char *sql)
{
    CURL *curl = curl_easy_init();
    if (!curl) {
        TM_LOG_ERROR("CH", "curl_easy_init failed");
        return -1;
    }

    /* 构造完整 URL：http://host:port/?database=<db>&query=<sql> */
    /* ClickHouse HTTP 接口：POST body 即为 SQL 语句 */
    char full_url[1024];
    if (g_user[0]) {
        snprintf(full_url, sizeof(full_url),
                 "%s/?database=%s&user=%s&password=%s",
                 g_url, g_db, g_user, g_password);
    } else {
        snprintf(full_url, sizeof(full_url),
                 "%s/?database=%s", g_url, g_db);
    }

    curl_easy_setopt(curl, CURLOPT_URL, full_url);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, sql);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, (long)strlen(sql));
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, curl_discard_cb);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 5L);   /* 5 秒超时 */
    curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);  /* 多线程安全 */

    CURLcode res = curl_easy_perform(curl);
    int ret = 0;

    if (res != CURLE_OK) {
        TM_LOG_ERROR("CH", "HTTP request failed: %s", curl_easy_strerror(res));
        ret = -1;
    } else {
        long http_code = 0;
        curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &http_code);
        if (http_code != 200) {
            TM_LOG_ERROR("CH", "ClickHouse returned error code: %ld, SQL: %.200s",
                    http_code, sql);
            ret = -1;
        }
    }

    curl_easy_cleanup(curl);
    return ret;
}

/* Public variant: caller provides the full URL. */
int ch_exec_raw(const char *full_url, const char *sql)
{
    CURL *curl = curl_easy_init();
    if (!curl) return -1;
    curl_easy_setopt(curl, CURLOPT_URL, full_url);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDS, sql);
    curl_easy_setopt(curl, CURLOPT_POSTFIELDSIZE, (long)strlen(sql));
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, curl_discard_cb);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 5L);
    curl_easy_setopt(curl, CURLOPT_NOSIGNAL, 1L);
    CURLcode res = curl_easy_perform(curl);
    int ret = (res == CURLE_OK) ? 0 : -1;
    if (res != CURLE_OK) {
        TM_LOG_ERROR("CH", "ch_exec_raw failed: %s", curl_easy_strerror(res));
    }
    curl_easy_cleanup(curl);
    return ret;
}

/* ------------------------------------------------------------------ */
/*  初始化 / 清理                                                       */
/* ------------------------------------------------------------------ */

int ch_storage_init(const char *url, const char *db_name,
                    const char *user, const char *password)
{
    if (g_ready) {
        TM_LOG_WARN("CH", "already initialized, skipping duplicate");
        return 0;
    }

    if (!url || !db_name) {
        TM_LOG_ERROR("CH", "URL or database name is empty");
        return -1;
    }

    strncpy(g_url,      url,      sizeof(g_url)      - 1);
    strncpy(g_db,       db_name,  sizeof(g_db)       - 1);
    strncpy(g_user,     user      ? user     : "", sizeof(g_user)     - 1);
    strncpy(g_password, password  ? password : "", sizeof(g_password) - 1);

    if (curl_global_init(CURL_GLOBAL_DEFAULT) != CURLE_OK) {
        TM_LOG_ERROR("CH", "curl_global_init failed");
        return -1;
    }

    /* 连通性测试：SELECT 1 */
    if (ch_exec("SELECT 1") != 0) {
        TM_LOG_ERROR("CH", "ClickHouse connectivity test failed, check URL and credentials");
        curl_global_cleanup();
        return -1;
    }

    g_ready = true;
    TM_LOG_INFO("CH", "connection successful, url: %s, db: %s", g_url, g_db);

    /* 自动建表 */
    ch_create_tables();

    /* 启动校验：ch_tables.h 列出的每张表是否真的存在 */
    {
        const char *expected[] = CH_ALL_TABLES;
        size_t nexp = sizeof(expected) / sizeof(expected[0]);
        int missing = 0;
        for (size_t i = 0; i < nexp; i++) {
            char sql[256];
            snprintf(sql, sizeof(sql), "EXISTS TABLE %s", expected[i]);
            /* ch_exec 目前不取返回体，只看 HTTP 200；EXISTS 不会返回非 200，
             * 所以退化为 DESCRIBE：存在返回 200，不存在返回 404。 */
            snprintf(sql, sizeof(sql), "DESCRIBE TABLE %s FORMAT Null", expected[i]);
            if (ch_exec(sql) != 0) {
                TM_LOG_ERROR("CH", "[FATAL] startup validation failed: table '%s' does not exist",
                        expected[i]);
                missing++;
            }
        }
        if (missing > 0) {
            TM_LOG_ERROR("CH", "[FATAL] %d tables missing, aborting startup", missing);
            curl_global_cleanup();
            g_ready = false;
            return -1;
        }
        TM_LOG_INFO("CH", "startup validation passed: %zu tables exist", nexp);
    }

    return 0;
}

void ch_storage_cleanup(void)
{
    if (!g_ready) return;
    curl_global_cleanup();
    g_ready = false;
    TM_LOG_INFO("CH", "ClickHouse connection resources released");
}

bool ch_storage_is_ready(void)
{
    return g_ready;
}

/* ------------------------------------------------------------------ */
/*  DDL：建表                                                           */
/* ------------------------------------------------------------------ */

int ch_create_tables(void)
{
    static const char *ddl[] = {
        /* HTTP 请求表 */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_HTTP " ("
        "  captured_at    DateTime,"
        "  src_ip         String,"
        "  dst_ip         String,"
        "  src_port       UInt16,"
        "  dst_port       UInt16,"
        "  method         String,"
        "  uri            String,"
        "  host           String,"
        "  user_agent     String,"
        "  content_type   String,"
        "  protocol_type  Int32,"
        "  protocol_name  String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, src_ip, dst_ip)",

        /* HTTPS 流量表 */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_HTTPS " ("
        "  captured_at  DateTime,"
        "  src_ip       String,"
        "  dst_ip       String,"
        "  src_port     UInt16,"
        "  dst_port     UInt16,"
        "  bytes        UInt64,"
        "  packets      UInt32,"
        "  tls_version  UInt16,"
        "  sni          String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, src_ip, dst_ip)",

        /* 兼容旧表：若已存在则追加缺失列（ADD COLUMN IF NOT EXISTS 幂等） */
        "ALTER TABLE https_traffic ADD COLUMN IF NOT EXISTS bytes UInt64 DEFAULT 0",
        "ALTER TABLE https_traffic ADD COLUMN IF NOT EXISTS packets UInt32 DEFAULT 0",
        "ALTER TABLE https_traffic ADD COLUMN IF NOT EXISTS tls_version UInt16 DEFAULT 0",
        "ALTER TABLE https_traffic ADD COLUMN IF NOT EXISTS sni String DEFAULT ''",

        /* TLS 会话表 */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_TLS " ("
        "  captured_at          DateTime,"
        "  src_ip               String,"
        "  dst_ip               String,"
        "  src_port             UInt16,"
        "  dst_port             UInt16,"
        "  tls_version          UInt16,"
        "  sni                  String,"
        "  cipher_suites        String,"
        "  cipher_suites_count  UInt16,"
        "  extensions_count     UInt16"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, src_ip, dst_ip)",

        /* 兼容旧表：追加 cipher_suites 列（IF NOT EXISTS 幂等） */
        "ALTER TABLE tls_sessions ADD COLUMN IF NOT EXISTS cipher_suites String DEFAULT ''",

        /* JA3 指纹表 */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_JA3 " ("
        "  captured_at       DateTime,"
        "  src_ip            String,"
        "  dst_ip            String,"
        "  src_port          UInt16,"
        "  dst_port          UInt16,"
        "  sni               String,"
        "  tls_version       UInt16,"
        "  cipher_suites     String,"
        "  extensions        String,"
        "  elliptic_curves   String,"
        "  ec_point_formats  String,"
        "  ja3_string        String,"
        "  ja3_hash          String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, ja3_hash, src_ip)",

        /* SSH 事件表 */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_SSH " ("
        "  captured_at    DateTime,"
        "  src_ip         String,"
        "  dst_ip         String,"
        "  src_port       UInt16,"
        "  dst_port       UInt16,"
        "  protocol       UInt8,"
        "  protocol_name  String,"
        "  client_version String,"
        "  server_version String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, src_ip, dst_ip, src_port, dst_port)",

        /* QUIC 事件表 */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_QUIC " ("
        "  captured_at       DateTime,"
        "  src_ip            String,"
        "  dst_ip            String,"
        "  src_port          UInt16,"
        "  dst_port          UInt16,"
        "  protocol          UInt8,"
        "  protocol_name     String,"
        "  last_header_form  UInt8,"
        "  last_long_type    UInt8,"
        "  packet_number_len UInt8,"
        "  dcid_len          UInt8,"
        "  scid_len          UInt8,"
        "  packet_number     UInt32,"
        "  dcid              String,"
        "  scid              String,"
        "  quic_version      UInt32,"
        "  sni               String,"
        "  alpn              String,"
        "  is_doq            UInt8,"
        "  confidence        UInt8,"
        "  detect_reason     String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, src_ip, dst_ip, src_port, dst_port)",
        "ALTER TABLE quic_events ADD COLUMN IF NOT EXISTS quic_version UInt32 DEFAULT 0",
        "ALTER TABLE quic_events ADD COLUMN IF NOT EXISTS sni String DEFAULT ''",
        "ALTER TABLE quic_events ADD COLUMN IF NOT EXISTS alpn String DEFAULT ''",
        "ALTER TABLE quic_events ADD COLUMN IF NOT EXISTS is_doq UInt8 DEFAULT 0",
        "ALTER TABLE quic_events ADD COLUMN IF NOT EXISTS confidence UInt8 DEFAULT 0",
        "ALTER TABLE quic_events ADD COLUMN IF NOT EXISTS detect_reason String DEFAULT ''",

        /* FTP 事件表 */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_FTP " ("
        "  captured_at    DateTime,"
        "  src_ip         String,"
        "  dst_ip         String,"
        "  src_port       UInt16,"
        "  dst_port       UInt16,"
        "  protocol       UInt8,"
        "  protocol_name  String,"
        "  file_name      String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, src_ip, dst_ip, src_port, dst_port)",

        "ALTER TABLE ftp_events ADD COLUMN IF NOT EXISTS file_name String DEFAULT ''",

        /* SMTP 事件表 */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_SMTP " ("
        "  captured_at    DateTime,"
        "  src_ip         String,"
        "  dst_ip         String,"
        "  src_port       UInt16,"
        "  dst_port       UInt16,"
        "  protocol       UInt8,"
        "  protocol_name  String,"
        "  email          String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, src_ip, dst_ip, src_port, dst_port)",
        "ALTER TABLE smtp_events ADD COLUMN IF NOT EXISTS email String DEFAULT ''",

        /* WireGuard 事件表 */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_WIREGUARD " ("
        "  captured_at    DateTime,"
        "  src_ip         String,"
        "  dst_ip         String,"
        "  src_port       UInt16,"
        "  dst_port       UInt16,"
        "  protocol       UInt8,"
        "  protocol_name  String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, src_ip, dst_ip, src_port, dst_port)",

        /* POP3 事件表 */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_POP3 " ("
        "  captured_at    DateTime,"
        "  src_ip         String,"
        "  dst_ip         String,"
        "  src_port       UInt16,"
        "  dst_port       UInt16,"
        "  protocol       UInt8,"
        "  protocol_name  String,"
        "  email          String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, src_ip, dst_ip, src_port, dst_port)",
        "ALTER TABLE pop3_events ADD COLUMN IF NOT EXISTS email String DEFAULT ''",

        /* IMAP 事件表 */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_IMAP " ("
        "  captured_at    DateTime,"
        "  src_ip         String,"
        "  dst_ip         String,"
        "  src_port       UInt16,"
        "  dst_port       UInt16,"
        "  protocol       UInt8,"
        "  protocol_name  String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, src_ip, dst_ip, src_port, dst_port)",

        /* RTSP 事件表 */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_RTSP " ("
        "  captured_at    DateTime,"
        "  src_ip         String,"
        "  dst_ip         String,"
        "  src_port       UInt16,"
        "  dst_port       UInt16,"
        "  protocol       UInt8,"
        "  protocol_name  String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, src_ip, dst_ip, src_port, dst_port)",

        /* IPSecVPN 事件表 */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_IPSECVPN " ("
        "  captured_at    DateTime,"
        "  src_ip         String,"
        "  dst_ip         String,"
        "  src_port       UInt16,"
        "  dst_port       UInt16,"
        "  protocol       UInt8,"
        "  protocol_name  String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, src_ip, dst_ip, src_port, dst_port)",

        /* OpenVPN 事件表 */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_OPENVPN " ("
        "  captured_at    DateTime,"
        "  src_ip         String,"
        "  dst_ip         String,"
        "  src_port       UInt16,"
        "  dst_port       UInt16,"
        "  protocol       UInt8,"
        "  protocol_name  String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, src_ip, dst_ip, src_port, dst_port)",
        
        /* dns 事件表 */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_DNS_RESOLUTION " ("
        "  captured_at       DateTime,"
        "  client_ip         String,"
        "  resolver_ip       String,"
        "  src_port          UInt16,"
        "  dst_port          UInt16,"
        "  transport_proto   String,"
        "  qname             String,"
        "  qtype             UInt16,"
        "  rcode             UInt8,"
        "  answer_ips        Array(String),"
        "  answer_domains    Array(String),"
        "  answer_types      Array(UInt16),"
        "  ttls              Array(UInt32),"
        "  answer_count      UInt16,"
        "  is_success        UInt8,"
        "  protocol_name     String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, qname, client_ip, resolver_ip)",

        /* dtls 事件表 */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_DTLS " ("
        "  captured_at     DateTime,"
        "  src_ip          String,"
        "  dst_ip          String,"
        "  src_port        UInt16,"
        "  dst_port        UInt16,"
        "  version         UInt16,"
        "  record_type     UInt8,"
        "  handshake_type  UInt8,"
        "  epoch           UInt16,"
        "  record_len      UInt16,"
        "  payload_len     UInt32,"
        "  transport_proto  String,"
        "  detail_desc     String,"
        "  is_handshake    UInt8,"
        "  is_appdata      UInt8,"
        "  is_alert        UInt8,"
        "  protocol_name   String"
        ") ENGINE = MergeTree() "
        "PARTITION BY toYYYYMMDD(captured_at) "
        "ORDER BY (captured_at, src_ip, dst_ip, src_port, dst_port)",

        "CREATE TABLE IF NOT EXISTS " CH_TBL_TLS_INFRA " ("
        "  captured_at         DateTime,"
        "  src_ip              String,"
        "  dst_ip              String,"
        "  src_port            UInt16,"
        "  dst_port            UInt16,"
        "  tls_record_version  UInt16,"
        "  record_type         UInt8,"
        "  record_len          UInt16,"
        "  payload_len         UInt32,"
        "  detected_type       String,"
        "  detection_method    String,"
        "  sni                 String,"
        "  alpn                String,"
        "  is_doh              UInt8,"
        "  is_dot              UInt8,"
        "  is_ech              UInt8,"
        "  is_dns_tunnel       UInt8,"
        "  protocol_name       String"
        ") ENGINE = MergeTree() "
        "PARTITION BY toYYYYMMDD(captured_at) "
        "ORDER BY (captured_at, src_ip, dst_ip, src_port, dst_port)",
        /* 兼容列：新增 is_dns_tunnel 字段以兼容新版本 */
        "ALTER TABLE tls_infra_events ADD COLUMN IF NOT EXISTS is_dns_tunnel UInt8 DEFAULT 0",

        "CREATE TABLE IF NOT EXISTS " CH_TBL_RDP " ("
        "  captured_at           DateTime,"
        "  src_ip                String,"
        "  dst_ip                String,"
        "  src_port              UInt16,"
        "  dst_port              UInt16,"
        "  transport_proto       String,"
        "  tpdu_type             UInt8,"
        "  tpdu_type_name        String,"
        "  cookie                String,"
        "  requested_protocols   UInt32,"
        "  cookie_seen           UInt8,"
        "  security_seen         UInt8,"
        "  payload_len           UInt32,"
        "  protocol_name         String"
        ") ENGINE = MergeTree() "
        "PARTITION BY toYYYYMMDD(captured_at) "
        "ORDER BY (captured_at, src_ip, dst_ip, src_port, dst_port)",

        "CREATE TABLE IF NOT EXISTS " CH_TBL_RTMP " ("
        "  captured_at     DateTime,"
        "  src_ip          String,"
        "  dst_ip          String,"
        "  src_port        UInt16,"
        "  dst_port        UInt16,"
        "  transport_proto  String,"
        "  event_type      String,"
        "  is_handshake    UInt8,"
        "  is_command      UInt8,"
        "  payload_len     UInt32,"
        "  protocol_name   String"
        ") ENGINE = MergeTree() "
        "PARTITION BY toYYYYMMDD(captured_at) "
        "ORDER BY (captured_at, src_ip, dst_ip, src_port, dst_port)",

        "CREATE TABLE IF NOT EXISTS " CH_TBL_SMB " ("
        "  captured_at        DateTime,"
        "  src_ip             String,"
        "  dst_ip             String,"
        "  src_port           UInt16,"
        "  dst_port           UInt16,"
        "  transport_proto    String,"
        "  smb_version        UInt8,"
        "  smb_version_name   String,"
        "  payload_len        UInt32,"
        "  protocol_name      String"
        ") ENGINE = MergeTree() "
        "PARTITION BY toYYYYMMDD(captured_at) "
        "ORDER BY (captured_at, src_ip, dst_ip, src_port, dst_port)",

        "CREATE TABLE IF NOT EXISTS " CH_TBL_GEO_ALERT " ("
        "  captured_at   DateTime,"
        "  event_type    String,"
        "  session_id    String,"
        "  src_ip        String,"
        "  dst_ip        String,"
        "  dst_country   String,"
        "  src_port      UInt16,"
        "  dst_port      UInt16,"
        "  protocol      UInt8,"
        "  bytes_up      UInt64,"
        "  bytes_down    UInt64,"
        "  ratio         Float64,"
        "  message       String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, event_type, dst_country, src_ip)",

        "CREATE TABLE IF NOT EXISTS " CH_TBL_DOMAIN_ACCESS " ("
        "  captured_at   DateTime,"
        "  src_ip        String,"
        "  dst_ip        String,"
        "  src_port      UInt16,"
        "  dst_port      UInt16,"
        "  protocol      UInt8,"
        "  domain        String,"
        "  domain_group  String,"
        "  source_type   String,"
        "  focus_tag     String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, domain_group, src_ip)",

        "CREATE TABLE IF NOT EXISTS " CH_TBL_THREAT_DOMAIN " ("
        "  captured_at   DateTime,"
        "  src_ip        String,"
        "  dst_ip        String,"
        "  src_port      UInt16,"
        "  dst_port      UInt16,"
        "  protocol      UInt8,"
        "  domain        String,"
        "  source_type   String,"
        "  threat_type   String,"
        "  intel_source  String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, threat_type, domain, src_ip)",

        /* Phase 5: 富 schema 命中表，与 threat_domain_events 双写过渡 */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_TI_HITS_V2 " ("
        "  captured_at      DateTime,"
        "  src_ip           String,"
        "  dst_ip           String,"
        "  src_port         UInt16,"
        "  dst_port         UInt16,"
        "  l4_proto         UInt8,"
        "  l7_proto         LowCardinality(String),"
        "  matched_field    LowCardinality(String),"
        "  matched_mode     LowCardinality(String),"
        "  matched_value    String,"
        "  matched_source   LowCardinality(String),"
        "  matched_stix_id  String,"
        "  confidence       UInt8,"
        "  tlp              LowCardinality(String),"
        "  reported_to_cti  UInt8 DEFAULT 0"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, matched_field, matched_source)"
        "  TTL captured_at + INTERVAL 90 DAY",

        "CREATE TABLE IF NOT EXISTS " CH_TBL_FOCUS_SITE " ("
        "  captured_at   DateTime,"
"  src_ip        String,"
"  dst_ip        String,"
"  src_port      UInt16,"
"  dst_port      UInt16,"
"  protocol      UInt8,"
"  domain        String,"
"  domain_group  String,"
"  source_type   String,"
"  focus_tag     String"
") ENGINE = MergeTree()"
"  PARTITION BY toYYYYMMDD(captured_at)"
"  ORDER BY (captured_at, focus_tag, domain_group, src_ip)",

        "CREATE TABLE IF NOT EXISTS " CH_TBL_V2RAY_EVENTS " ("
        "  captured_at       DateTime,"
        "  src_ip            String,"
        "  dst_ip            String,"
        "  src_port          UInt16,"
        "  dst_port          UInt16,"
        "  detected_type     String,"
        "  detection_method  String,"
        "  protocol_name     String,"
        "  method            String,"
        "  host              String,"
        "  path              String,"
        "  upgrade           String,"
        "  score             UInt8,"
        "  avg_entropy       Float64,"
        "  dominant_ratio    Float64,"
        "  packet_count      UInt64,"
        "  packets_ab        UInt64,"
        "  packets_ba        UInt64,"
        "  bytes_ab          UInt64,"
        "  bytes_ba          UInt64,"
        "  saw_connect       UInt8,"
        "  saw_ws_upgrade    UInt8,"
        "  saw_proxy_auth    UInt8,"
        "  saw_path_hint     UInt8"
") ENGINE = MergeTree()"
"  PARTITION BY toYYYYMMDD(captured_at)"
"  ORDER BY (captured_at, src_ip, dst_ip, src_port, dst_port)",

        /* Shadowsocks 概率型识别事件表（仅记录已确认会话） */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_SHADOWSOCKS_EVENTS " ("
        "  captured_at       DateTime,"
        "  src_ip            String,"
        "  dst_ip            String,"
        "  src_port          UInt16,"
        "  dst_port          UInt16,"
        "  transport_proto   UInt8,"
        "  protocol_name     String,"
        "  state             UInt8,"
        "  state_name        String,"
        "  confidence        UInt8,"
        "  confidence_name   String,"
        "  path_type         UInt8,"
        "  path_name         String,"
        "  score             UInt16,"
        "  reason_mask       UInt32,"
        "  reason_text       String,"
        "  avg_entropy       Float64,"
        "  max_entropy       Float64,"
        "  packets_ab        UInt64,"
        "  packets_ba        UInt64,"
        "  bytes_ab          UInt64,"
        "  bytes_ba          UInt64,"
        "  first_seen_ts     DateTime"
") ENGINE = MergeTree()"
"  PARTITION BY toYYYYMMDD(captured_at)"
"  ORDER BY (captured_at, src_ip, dst_ip, src_port, dst_port)",

        /* F4.2.3 站点访问排名表（SNI 统计） */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_SITE_ACCESS_RANK " ("
        "  captured_at   DateTime,"
        "  domain        String,"
        "  domain_group  String,"
        "  src_ip        String,"
        "  access_count  UInt64"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, domain_group, access_count)",

        /* F3.3.1 ICMP 隧道可疑包事件表 */
        "CREATE TABLE IF NOT EXISTS " CH_TBL_ICMP " ("
        "  captured_at   DateTime,"
        "  session_id    String,"
        "  src_ip        String,"
        "  dst_ip        String,"
        "  icmp_type     UInt8,"
        "  icmp_code     UInt8,"
        "  identifier    UInt16,"
        "  seq_num       UInt16,"
        "  payload_len   UInt16,"
        "  payload_hex   String,"
        "  payload_text  String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, src_ip, dst_ip)",

        "CREATE TABLE IF NOT EXISTS " CH_TBL_OPERATION_LOGS " ("
        "  captured_at   DateTime,"
        "  event_type    LowCardinality(String),"
        "  source        LowCardinality(String),"
        "  actor         String,"
        "  plugin_name   String,"
        "  plugin_id     UInt32,"
        "  detail        String,"
        "  status        UInt8,"
        "  extra         String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, event_type, source)",

        "CREATE TABLE IF NOT EXISTS " CH_TBL_ENGINE_LOGS " ("
        "  captured_at        DateTime,"
        "  engine_name        LowCardinality(String),"
        "  log_level          LowCardinality(String),"
        "  category           LowCardinality(String),"
        "  uploader_name      String,"
        "  student_id         String,"
        "  engine_description String,"
        "  session_id         String,"
        "  src_ip             String,"
        "  dst_ip             String,"
        "  message            String,"
        "  extra              String"
        ") ENGINE = MergeTree()"
        "  PARTITION BY toYYYYMMDD(captured_at)"
        "  ORDER BY (captured_at, engine_name, category, log_level)",

        NULL
    };

    int ret = 0;
    for (int i = 0; ddl[i]; i++) {
        if (ch_exec(ddl[i]) != 0) {
            TM_LOG_ERROR("CH", "table creation failed (DDL #%d)", i + 1);
            ret = -1;
        }
    }

    if (ret == 0) {
        TM_LOG_INFO("CH", "all tables ready");
    }
    return ret;
}

/* ------------------------------------------------------------------ */
/*  写入 HTTP 请求                                                      */
/* ------------------------------------------------------------------ */

void ch_insert_http(const char *src_ip, const char *dst_ip,
                    uint16_t src_port, uint16_t dst_port,
                    const char *method, const char *uri,
                    const char *host, const char *user_agent,
                    const char *content_type, int protocol_type,
                    int64_t ts_sec)
{
    if (!g_ready) return;

    const char *proto_name = "HTTP";
    if (protocol_type == 1) proto_name = "HLS";
    else if (protocol_type == 2) proto_name = "DASH";

    char e_uri[2048], e_host[512], e_ua[1024], e_ct[256];
    escape_str(uri,          e_uri, sizeof(e_uri));
    escape_str(host,         e_host, sizeof(e_host));
    escape_str(user_agent,   e_ua,   sizeof(e_ua));
    escape_str(content_type, e_ct,   sizeof(e_ct));

    char sql[4096];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_HTTP " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,"
        "method,uri,host,user_agent,content_type,protocol_type,protocol_name) "
        "VALUES ("
        "toDateTime(%lld),"
        "'%s','%s',%u,%u,"
        "'%s','%s','%s','%s','%s',%d,'%s'"
        ")",
        (long long)ts_sec,
        src_ip ? src_ip : "", dst_ip ? dst_ip : "",
        src_port, dst_port,
        method ? method : "", e_uri, e_host, e_ua, e_ct,
        protocol_type, proto_name);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "http_requests write failed");
    }
}

/* ------------------------------------------------------------------ */
/*  写入 HTTPS 识别记录                                                 */
/* ------------------------------------------------------------------ */

void ch_insert_https(const char *src_ip, const char *dst_ip,
                     uint16_t src_port, uint16_t dst_port,
                     uint64_t bytes, uint32_t packets,
                     uint16_t tls_version, const char *sni,
                     int64_t ts_sec)
{
    if (!g_ready) return;

    char e_sni[512];
    escape_str(sni, e_sni, sizeof(e_sni));

    char sql[768];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_HTTPS " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,bytes,packets,tls_version,sni) "
        "VALUES (toDateTime(%lld),'%s','%s',%u,%u,%"PRIu64",%u,%u,'%s')",
        (long long)ts_sec,
        src_ip ? src_ip : "", dst_ip ? dst_ip : "",
        src_port, dst_port,
        bytes, packets, tls_version, e_sni);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "https_traffic write failed");
    }
}

/* ------------------------------------------------------------------ */
/*  写入 TLS ClientHello 记录                                           */
/* ------------------------------------------------------------------ */

void ch_insert_tls(const char *src_ip, const char *dst_ip,
                   uint16_t src_port, uint16_t dst_port,
                   uint16_t tls_version, const char *sni,
                   const char *cipher_suites,
                   uint16_t cipher_suites_count, uint16_t extensions_count,
                   int64_t ts_sec)
{
    if (!g_ready) return;

    char e_sni[512];
    char e_cs[2048];
    escape_str(sni,           e_sni, sizeof(e_sni));
    escape_str(cipher_suites, e_cs,  sizeof(e_cs));

    char sql[3072];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_TLS " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,"
        "tls_version,sni,cipher_suites,cipher_suites_count,extensions_count) "
        "VALUES (toDateTime(%lld),'%s','%s',%u,%u,%u,'%s','%s',%u,%u)",
        (long long)ts_sec,
        src_ip ? src_ip : "", dst_ip ? dst_ip : "",
        src_port, dst_port,
        tls_version, e_sni, e_cs,
        cipher_suites_count, extensions_count);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "tls_sessions write failed");
    }
}

/* ------------------------------------------------------------------ */
/*  写入 JA3 指纹记录                                                   */
/* ------------------------------------------------------------------ */

void ch_insert_ja3(const char *src_ip, const char *dst_ip,
                   uint16_t src_port, uint16_t dst_port,
                   const char *sni, uint16_t tls_version,
                   const char *cipher_suites, const char *extensions,
                   const char *elliptic_curves, const char *ec_point_formats,
                   const char *ja3_string, const char *ja3_hash,
                   int64_t ts_sec)
{
    if (!g_ready) return;

    char e_sni[512], e_cs[2048], e_ext[2048], e_ec[512], e_ecpf[256];
    char e_js[2048], e_jh[64];
    escape_str(sni,              e_sni,  sizeof(e_sni));
    escape_str(cipher_suites,    e_cs,   sizeof(e_cs));
    escape_str(extensions,       e_ext,  sizeof(e_ext));
    escape_str(elliptic_curves,  e_ec,   sizeof(e_ec));
    escape_str(ec_point_formats, e_ecpf, sizeof(e_ecpf));
    escape_str(ja3_string,       e_js,   sizeof(e_js));
    escape_str(ja3_hash,         e_jh,   sizeof(e_jh));

    char sql[6144];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_JA3 " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,"
        "sni,tls_version,cipher_suites,extensions,"
        "elliptic_curves,ec_point_formats,ja3_string,ja3_hash) "
        "VALUES (toDateTime(%lld),'%s','%s',%u,%u,"
        "'%s',%u,'%s','%s','%s','%s','%s','%s')",
        (long long)ts_sec,
        src_ip ? src_ip : "", dst_ip ? dst_ip : "",
        src_port, dst_port,
        e_sni, tls_version, e_cs, e_ext, e_ec, e_ecpf, e_js, e_jh);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "ja3_fingerprints write failed");
    }
}

void ch_insert_ssh(const char *src_ip, const char *dst_ip,
                   uint16_t src_port, uint16_t dst_port,
                   uint8_t protocol,
                   const char *protocol_name,
                   const char *client_version,
                   const char *server_version,
                   int64_t ts_sec)
{
    if (!g_ready) return;

    char e_proto[64];
    char e_client[256];
    char e_server[256];

    escape_str(protocol_name,  e_proto,  sizeof(e_proto));
    escape_str(client_version, e_client, sizeof(e_client));
    escape_str(server_version, e_server, sizeof(e_server));

    char sql[2048];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_SSH " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,protocol,protocol_name,client_version,server_version) "
        "VALUES ("
        "toDateTime(%lld),"
        "'%s','%s',%u,%u,%u,'%s','%s','%s'"
        ")",
        (long long)ts_sec,
        src_ip ? src_ip : "",
        dst_ip ? dst_ip : "",
        src_port,
        dst_port,
        protocol,
        e_proto[0] ? e_proto : "SSH",
        e_client,
        e_server);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "ssh_events write failed");
    }
}

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
                    int64_t ts_sec)
{
    uint32_t stored_quic_version = (quic_version == 0) ? 1u : quic_version;
    if (!g_ready) return;

    char e_proto[64];
    char e_dcid[128];
    char e_scid[128];
    char e_sni[512];
    char e_alpn[256];
    char e_reason[256];

    escape_str(protocol_name, e_proto, sizeof(e_proto));
    escape_str(dcid, e_dcid, sizeof(e_dcid));
    escape_str(scid, e_scid, sizeof(e_scid));
    escape_str(sni, e_sni, sizeof(e_sni));
    escape_str(alpn, e_alpn, sizeof(e_alpn));
    escape_str(detect_reason, e_reason, sizeof(e_reason));

    char sql[2048];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_QUIC " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,protocol,protocol_name,"
        "last_header_form,last_long_type,packet_number_len,dcid_len,scid_len,"
        "packet_number,dcid,scid,quic_version,sni,alpn,is_doq,confidence,detect_reason) "
        "VALUES ("
        "toDateTime(%lld),"
        "'%s','%s',%u,%u,%u,'%s',"
        "%u,%u,%u,%u,%u,%u,'%s','%s',%u,'%s','%s',%u,%u,'%s'"
        ")",
        (long long)ts_sec,
        src_ip ? src_ip : "",
        dst_ip ? dst_ip : "",
        src_port,
        dst_port,
        protocol,
        e_proto[0] ? e_proto : "QUIC",
        (unsigned)last_header_form,
        (unsigned)last_long_type,
        (unsigned)packet_number_len,
        (unsigned)dcid_len,
        (unsigned)scid_len,
        (unsigned)packet_number,
        e_dcid,
        e_scid,
        (unsigned)stored_quic_version,
        e_sni,
        e_alpn,
        (unsigned)is_doq,
        (unsigned)confidence,
        e_reason);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "quic_events write failed");
    }
}

void ch_insert_ftp(const char *src_ip, const char *dst_ip,
                   uint16_t src_port, uint16_t dst_port,
                   uint8_t protocol,
                   const char *protocol_name,
                   const char *file_name,
                   int64_t ts_sec)
{
    if (!g_ready) return;

    char e_proto[64];
    char e_file[512];
    escape_str(protocol_name, e_proto, sizeof(e_proto));
    escape_str(file_name, e_file, sizeof(e_file));

    char sql[1536];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_FTP " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,protocol,protocol_name,file_name) "
        "VALUES ("
        "toDateTime(%lld),"
        "'%s','%s',%u,%u,%u,'%s','%s'"
        ")",
        (long long)ts_sec,
        src_ip ? src_ip : "",
        dst_ip ? dst_ip : "",
        src_port,
        dst_port,
        protocol,
        e_proto[0] ? e_proto : "FTP",
        e_file);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "ftp_events write failed");
    }
}

void ch_insert_smtp(const char *src_ip, const char *dst_ip,
                    uint16_t src_port, uint16_t dst_port,
                    uint8_t protocol,
                    const char *protocol_name,
                    const char *email,
                    int64_t ts_sec)
{
    if (!g_ready) return;

    char e_proto[64];
    char e_email[512];
    escape_str(protocol_name, e_proto, sizeof(e_proto));
    escape_str(email, e_email, sizeof(e_email));

    char sql[1536];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_SMTP " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,protocol,protocol_name,email) "
        "VALUES ("
        "toDateTime(%lld),"
        "'%s','%s',%u,%u,%u,'%s','%s'"
        ")",
        (long long)ts_sec,
        src_ip ? src_ip : "",
        dst_ip ? dst_ip : "",
        src_port,
        dst_port,
        protocol,
        e_proto[0] ? e_proto : "SMTP",
        e_email);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "smtp_events write failed");
    }
}

void ch_update_smtp_email(const char *src_ip, const char *dst_ip,
                          uint16_t src_port, uint16_t dst_port,
                          uint8_t protocol,
                          const char *protocol_name,
                          const char *email)
{
    if (!g_ready) return;
    if (!email || email[0] == '\0') return;

    char e_proto[64];
    char e_email[512];
    escape_str(protocol_name, e_proto, sizeof(e_proto));
    escape_str(email, e_email, sizeof(e_email));

    char sql[2048];
    snprintf(sql, sizeof(sql),
        "ALTER TABLE smtp_events UPDATE email='%s' "
        "WHERE src_ip='%s' AND dst_ip='%s' "
        "AND src_port=%u AND dst_port=%u AND protocol=%u "
        "AND protocol_name='%s' AND (email='' OR email='-')",
        e_email,
        src_ip ? src_ip : "",
        dst_ip ? dst_ip : "",
        src_port,
        dst_port,
        protocol,
        e_proto[0] ? e_proto : "SMTP");

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "smtp_events email update failed");
    }
}

void ch_insert_wireguard(const char *src_ip, const char *dst_ip,
                         uint16_t src_port, uint16_t dst_port,
                         uint8_t protocol,
                         const char *protocol_name,
                         int64_t ts_sec)
{
    if (!g_ready) return;

    char e_proto[64];
    escape_str(protocol_name, e_proto, sizeof(e_proto));

    char sql[1024];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_WIREGUARD " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,protocol,protocol_name) "
        "VALUES ("
        "toDateTime(%lld),"
        "'%s','%s',%u,%u,%u,'%s'"
        ")",
        (long long)ts_sec,
        src_ip ? src_ip : "",
        dst_ip ? dst_ip : "",
        src_port,
        dst_port,
        protocol,
        e_proto[0] ? e_proto : "WireGuard");

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "wireguard_events write failed");
    }
}

void ch_insert_pop3(const char *src_ip, const char *dst_ip,
                    uint16_t src_port, uint16_t dst_port,
                    uint8_t protocol,
                    const char *protocol_name,
                    const char *email,
                    int64_t ts_sec)
{
    if (!g_ready) return;

    char e_proto[64];
    char e_email[512];
    escape_str(protocol_name, e_proto, sizeof(e_proto));
    escape_str(email, e_email, sizeof(e_email));

    char sql[1536];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_POP3 " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,protocol,protocol_name,email) "
        "VALUES ("
        "toDateTime(%lld),"
        "'%s','%s',%u,%u,%u,'%s','%s'"
        ")",
        (long long)ts_sec,
        src_ip ? src_ip : "",
        dst_ip ? dst_ip : "",
        src_port,
        dst_port,
        protocol,
        e_proto[0] ? e_proto : "POP3",
        e_email);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "pop3_events write failed");
    }
}

void ch_update_pop3_email(const char *src_ip, const char *dst_ip,
                          uint16_t src_port, uint16_t dst_port,
                          uint8_t protocol,
                          const char *protocol_name,
                          const char *email)
{
    if (!g_ready) return;
    if (!email || email[0] == '\0') return;

    char e_proto[64];
    char e_email[512];
    escape_str(protocol_name, e_proto, sizeof(e_proto));
    escape_str(email, e_email, sizeof(e_email));

    char sql[2048];
    snprintf(sql, sizeof(sql),
        "ALTER TABLE pop3_events UPDATE email='%s' "
        "WHERE src_ip='%s' AND dst_ip='%s' "
        "AND src_port=%u AND dst_port=%u AND protocol=%u "
        "AND protocol_name='%s' AND (email='' OR email='-')",
        e_email,
        src_ip ? src_ip : "",
        dst_ip ? dst_ip : "",
        src_port,
        dst_port,
        protocol,
        e_proto[0] ? e_proto : "POP3");

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "pop3_events email update failed");
    }
}

void ch_insert_imap(const char *src_ip, const char *dst_ip,
                    uint16_t src_port, uint16_t dst_port,
                    uint8_t protocol,
                    const char *protocol_name,
                    int64_t ts_sec)
{
    if (!g_ready) return;

    char e_proto[64];
    escape_str(protocol_name, e_proto, sizeof(e_proto));

    char sql[1024];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_IMAP " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,protocol,protocol_name) "
        "VALUES ("
        "toDateTime(%lld),"
        "'%s','%s',%u,%u,%u,'%s'"
        ")",
        (long long)ts_sec,
        src_ip ? src_ip : "",
        dst_ip ? dst_ip : "",
        src_port,
        dst_port,
        protocol,
        e_proto[0] ? e_proto : "IMAP");

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "imap_events write failed");
    }
}

void ch_insert_rtsp(const char *src_ip, const char *dst_ip,
                    uint16_t src_port, uint16_t dst_port,
                    uint8_t protocol,
                    const char *protocol_name,
                    int64_t ts_sec)
{
    if (!g_ready) return;

    char e_proto[64];
    escape_str(protocol_name, e_proto, sizeof(e_proto));

    char sql[1024];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_RTSP " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,protocol,protocol_name) "
        "VALUES ("
        "toDateTime(%lld),"
        "'%s','%s',%u,%u,%u,'%s'"
        ")",
        (long long)ts_sec,
        src_ip ? src_ip : "",
        dst_ip ? dst_ip : "",
        src_port,
        dst_port,
        protocol,
        e_proto[0] ? e_proto : "RTSP");

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "rtsp_events write failed");
    }
}

void ch_insert_ipsecvpn(const char *src_ip, const char *dst_ip,
                        uint16_t src_port, uint16_t dst_port,
                        uint8_t protocol,
                        const char *protocol_name,
                        int64_t ts_sec)
{
    if (!g_ready) return;

    char e_proto[64];
    escape_str(protocol_name, e_proto, sizeof(e_proto));

    char sql[1024];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_IPSECVPN " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,protocol,protocol_name) "
        "VALUES ("
        "toDateTime(%lld),"
        "'%s','%s',%u,%u,%u,'%s'"
        ")",
        (long long)ts_sec,
        src_ip ? src_ip : "",
        dst_ip ? dst_ip : "",
        src_port,
        dst_port,
        protocol,
        e_proto[0] ? e_proto : "IPSecVPN");

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "ipsecvpn_events write failed");
    }
}

void ch_insert_openvpn(const char *src_ip, const char *dst_ip,
                       uint16_t src_port, uint16_t dst_port,
                       uint8_t protocol,
                       const char *protocol_name,
                       int64_t ts_sec)
{
    if (!g_ready) return;

    char e_proto[64];
    escape_str(protocol_name, e_proto, sizeof(e_proto));

    char sql[1024];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_OPENVPN " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,protocol,protocol_name) "
        "VALUES ("
        "toDateTime(%lld),"
        "'%s','%s',%u,%u,%u,'%s'"
        ")",
        (long long)ts_sec,
        src_ip ? src_ip : "",
        dst_ip ? dst_ip : "",
        src_port,
        dst_port,
        protocol,
        e_proto[0] ? e_proto : "OpenVPN");

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "openvpn_events write failed");
    }
}

void ch_insert_dns_resolution(
    const char *client_ip,
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
    int64_t ts_sec)
{
    if (!g_ready) return;
        //debug
        TM_LOG_INFO("CH", "DNS write: pid=%d client=%s:%u resolver=%s:%u qname=%s qtype=%u rcode=%u answer_count=%u is_success=%u ts=%lld",
            getpid(),
            client_ip ? client_ip : "",
            src_port,
            resolver_ip ? resolver_ip : "",
            dst_port,
            qname ? qname : "",
            (unsigned)qtype,
            (unsigned)rcode,
            (unsigned)answer_count,
            (unsigned)is_success,
            (long long)ts_sec);

    char e_client[128], e_resolver[128], e_qname[1024], e_transport[32];
    escape_str(client_ip ? client_ip : "", e_client, sizeof(e_client));
    escape_str(resolver_ip ? resolver_ip : "", e_resolver, sizeof(e_resolver));
    escape_str(qname ? qname : "", e_qname, sizeof(e_qname));
    escape_str(transport_proto ? transport_proto : "", e_transport, sizeof(e_transport));

    char sql[8192];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_DNS_RESOLUTION " "
        "(captured_at,client_ip,resolver_ip,src_port,dst_port,transport_proto,"
        "qname,qtype,rcode,answer_ips,answer_domains,answer_types,ttls,"
        "answer_count,is_success,protocol_name) "
        "VALUES ("
        "toDateTime(%lld),"
        "'%s','%s',%u,%u,'%s',"
        "'%s',%u,%u,"
        "%s,%s,%s,%s,"
        "%u,%u,'DNS'"
        ")",
        (long long)ts_sec,
        e_client, e_resolver,
        src_port, dst_port, e_transport,
        e_qname, qtype, rcode,
        answer_ips_ch ? answer_ips_ch : "[]",
        answer_domains_ch ? answer_domains_ch : "[]",
        answer_types_ch ? answer_types_ch : "[]",
        ttls_ch ? ttls_ch : "[]",
        answer_count,
        is_success);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "dns_resolution_events write failed");
    }
}

void ch_insert_dtls_event(
    const char *src_ip,
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
    int64_t ts_sec)
{
    if (!g_ready) return;

    char e_src_ip[128], e_dst_ip[128], e_desc[512];
    char e_transport[32];
    escape_str(src_ip ? src_ip : "", e_src_ip, sizeof(e_src_ip));
    escape_str(dst_ip ? dst_ip : "", e_dst_ip, sizeof(e_dst_ip));
    escape_str(detail_desc ? detail_desc : "", e_desc, sizeof(e_desc));
    escape_str(transport_proto ? transport_proto : "", e_transport, sizeof(e_transport));

    char sql[4096];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_DTLS " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,version,record_type,handshake_type,"
        "epoch,record_len,payload_len,transport_proto,detail_desc,is_handshake,is_appdata,is_alert,protocol_name) "
        "VALUES (toDateTime(%lld),'%s','%s',%u,%u,%u,%u,%u,%u,%u,%u,'%s','%s',%u,%u,%u,'DTLS')",
        (long long)ts_sec,
        e_src_ip,
        e_dst_ip,
        src_port,
        dst_port,
        version,
        record_type,
        handshake_type,
        epoch,
        record_len,
        payload_len,
        e_transport,
        e_desc,
        is_handshake,
        is_appdata,
        is_alert);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "dtls_events write failed");
    }
}

void ch_insert_tls_infra_event(
    const char *src_ip,
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
    int64_t ts_sec)
{
    if (!g_ready) return;

    char e_src_ip[128], e_dst_ip[128], e_detected[256], e_method[128], e_sni[512], e_alpn[512];
    escape_str(src_ip ? src_ip : "", e_src_ip, sizeof(e_src_ip));
    escape_str(dst_ip ? dst_ip : "", e_dst_ip, sizeof(e_dst_ip));
    escape_str(detected_type ? detected_type : "", e_detected, sizeof(e_detected));
    escape_str(detection_method ? detection_method : "", e_method, sizeof(e_method));
    escape_str(sni ? sni : "", e_sni, sizeof(e_sni));
    escape_str(alpn ? alpn : "", e_alpn, sizeof(e_alpn));

    char sql[4096];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_TLS_INFRA " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,tls_record_version,record_type,record_len,payload_len,"
        "detected_type,detection_method,sni,alpn,is_doh,is_dot,is_ech,is_dns_tunnel,protocol_name) "
        "VALUES (toDateTime(%lld),'%s','%s',%u,%u,%u,%u,%u,%u,'%s','%s','%s','%s',%u,%u,%u,%u,'TLS_INFRA')",
        (long long)ts_sec,
        e_src_ip,
        e_dst_ip,
        src_port,
        dst_port,
        tls_record_version,
        record_type,
        record_len,
        payload_len,
        e_detected,
        e_method,
        e_sni,
        e_alpn,
        is_doh,
        is_dot,
        is_ech,
        is_dns_tunnel);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "tls_infra_events write failed");
    }
}

void ch_insert_rdp_event(
    const char *src_ip,
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
    int64_t ts_sec)
{
    if (!g_ready) return;

    char e_src_ip[128], e_dst_ip[128], e_tpdu_name[64], e_cookie[1024];
    escape_str(src_ip ? src_ip : "", e_src_ip, sizeof(e_src_ip));
    escape_str(dst_ip ? dst_ip : "", e_dst_ip, sizeof(e_dst_ip));
    escape_str(tpdu_type_name ? tpdu_type_name : "", e_tpdu_name, sizeof(e_tpdu_name));
    escape_str(cookie ? cookie : "", e_cookie, sizeof(e_cookie));

    char e_transport[32];
    escape_str(transport_proto ? transport_proto : "", e_transport, sizeof(e_transport));

    char sql[4096];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_RDP " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,transport_proto,tpdu_type,tpdu_type_name,cookie,"
        "requested_protocols,cookie_seen,security_seen,payload_len,protocol_name) "
        "VALUES (toDateTime(%lld),'%s','%s',%u,%u,'%s',%u,'%s','%s',%u,%u,%u,%u,'RDP')",
        (long long)ts_sec,
        e_src_ip,
        e_dst_ip,
        src_port,
        dst_port,
        e_transport,
        tpdu_type,
        e_tpdu_name,
        e_cookie,
        requested_protocols,
        cookie_seen,
        security_seen,
        payload_len);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "rdp_events write failed");
    }
}

void ch_insert_rtmp_event(
    const char *src_ip,
    const char *dst_ip,
    uint16_t src_port,
    uint16_t dst_port,
    const char *event_type,
    uint8_t is_handshake,
    uint8_t is_command,
    uint32_t payload_len,
    const char *transport_proto,
    int64_t ts_sec)
{
    if (!g_ready) return;

    char e_src_ip[128], e_dst_ip[128], e_event_type[512];
    escape_str(src_ip ? src_ip : "", e_src_ip, sizeof(e_src_ip));
    escape_str(dst_ip ? dst_ip : "", e_dst_ip, sizeof(e_dst_ip));
    escape_str(event_type ? event_type : "", e_event_type, sizeof(e_event_type));

    char e_transport[32];
    escape_str(transport_proto ? transport_proto : "", e_transport, sizeof(e_transport));

    char sql[4096];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_RTMP " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,transport_proto,event_type,is_handshake,is_command,payload_len,protocol_name) "
        "VALUES (toDateTime(%lld),'%s','%s',%u,%u,'%s','%s',%u,%u,%u,'RTMP')",
        (long long)ts_sec,
        e_src_ip,
        e_dst_ip,
        src_port,
        dst_port,
        e_transport,
        e_event_type,
        is_handshake,
        is_command,
        payload_len);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "rtmp_events write failed");
    }
}

void ch_insert_smb_event(
    const char *src_ip,
    const char *dst_ip,
    uint16_t src_port,
    uint16_t dst_port,
    uint8_t smb_version,
    const char *smb_version_name,
    uint32_t payload_len,
    const char *transport_proto,
    int64_t ts_sec)
{
    if (!g_ready) return;

    char e_src_ip[128], e_dst_ip[128], e_ver_name[128];
    escape_str(src_ip ? src_ip : "", e_src_ip, sizeof(e_src_ip));
    escape_str(dst_ip ? dst_ip : "", e_dst_ip, sizeof(e_dst_ip));
    escape_str(smb_version_name ? smb_version_name : "", e_ver_name, sizeof(e_ver_name));

    char e_transport[32];
    escape_str(transport_proto ? transport_proto : "", e_transport, sizeof(e_transport));

    char sql[4096];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_SMB " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,transport_proto,smb_version,smb_version_name,payload_len,protocol_name) "
        "VALUES (toDateTime(%lld),'%s','%s',%u,%u,'%s',%u,'%s',%u,'SMB')",
        (long long)ts_sec,
        e_src_ip,
        e_dst_ip,
        src_port,
        dst_port,
        e_transport,
        smb_version,
        e_ver_name,
        payload_len);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "smb_events write failed");
    }
}

void ch_insert_geo_event(const char *event_type,
                         const char *session_id,
                         const char *src_ip, const char *dst_ip,
                         const char *dst_country,
                         uint16_t src_port, uint16_t dst_port,
                         uint8_t protocol,
                         uint64_t bytes_up, uint64_t bytes_down,
                         double ratio,
                         const char *message,
                         int64_t ts_sec)
{
    char e_type[64], e_session[256], e_country[32], e_message[1024];
    char sql[4096];

    if (!g_ready) return;

    escape_str(event_type, e_type, sizeof(e_type));
    escape_str(session_id, e_session, sizeof(e_session));
    escape_str(dst_country, e_country, sizeof(e_country));
    escape_str(message, e_message, sizeof(e_message));

    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_GEO_ALERT " "
        "(captured_at,event_type,session_id,src_ip,dst_ip,dst_country,"
        "src_port,dst_port,protocol,bytes_up,bytes_down,ratio,message) "
        "VALUES ("
        "toDateTime(%lld),"
        "'%s','%s','%s','%s','%s',"
        "%u,%u,%u,%" PRIu64 ",%" PRIu64 ",%.6f,'%s'"
        ")",
        (long long)ts_sec,
        e_type,
        e_session,
        src_ip ? src_ip : "",
        dst_ip ? dst_ip : "",
        e_country,
        src_port,
        dst_port,
        protocol,
        bytes_up,
        bytes_down,
        ratio,
        e_message);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "geo_alert_events write failed");
    }
}

void ch_insert_domain_access(const char *src_ip, const char *dst_ip,
                             uint16_t src_port, uint16_t dst_port,
                             uint8_t protocol,
                             const char *domain,
                             const char *domain_group,
                             const char *source_type,
                             const char *focus_tag,
                             int64_t ts_sec)
{
    char e_domain[512], e_group[256], e_source[32], e_focus[64];
    char sql[2048];

    if (!g_ready) return;

    escape_str(domain, e_domain, sizeof(e_domain));
    escape_str(domain_group, e_group, sizeof(e_group));
    escape_str(source_type, e_source, sizeof(e_source));
    escape_str(focus_tag, e_focus, sizeof(e_focus));

    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_DOMAIN_ACCESS " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,protocol,domain,domain_group,source_type,focus_tag) "
        "VALUES (toDateTime(%lld),'%s','%s',%u,%u,%u,'%s','%s','%s','%s')",
        (long long)ts_sec,
        src_ip ? src_ip : "",
        dst_ip ? dst_ip : "",
        src_port, dst_port, protocol,
        e_domain, e_group, e_source, e_focus);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "domain_access_events write failed");
    }
}

void ch_insert_threat_domain(const char *src_ip, const char *dst_ip,
                             uint16_t src_port, uint16_t dst_port,
                             uint8_t protocol,
                             const char *domain,
                             const char *source_type,
                             const char *threat_type,
                             const char *intel_source,
                             int64_t ts_sec)
{
    char e_domain[512], e_source[32], e_threat[64], e_intel[64];
    char sql[2048];

    if (!g_ready) return;

    escape_str(domain, e_domain, sizeof(e_domain));
    escape_str(source_type, e_source, sizeof(e_source));
    escape_str(threat_type, e_threat, sizeof(e_threat));
    escape_str(intel_source, e_intel, sizeof(e_intel));

    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_THREAT_DOMAIN " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,protocol,domain,source_type,threat_type,intel_source) "
        "VALUES (toDateTime(%lld),'%s','%s',%u,%u,%u,'%s','%s','%s','%s')",
        (long long)ts_sec,
        src_ip ? src_ip : "",
        dst_ip ? dst_ip : "",
        src_port, dst_port, protocol,
        e_domain, e_source, e_threat, e_intel);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "threat_domain_events write failed");
    }
}

/* ---- Phase 5 富 schema 命中表 ---- */

static const char *v2_match_field_name(uint8_t v)
{
    switch (v) {
    case 1: return "ip";
    case 2: return "domain";
    case 3: return "sni";
    case 4: return "ja3";
    case 5: return "url";
    default: return "unknown";
    }
}

static const char *v2_match_mode_name(uint8_t v)
{
    switch (v) {
    case 1: return "exact";
    case 2: return "cidr";
    case 3: return "wildcard";
    default: return "unknown";
    }
}

static const char *v2_tlp_name(uint8_t v)
{
    switch (v) {
    case 0: return "white";
    case 1: return "green";
    case 2: return "amber";
    case 3: return "red";
    default: return "white";
    }
}

void ch_insert_ti_hit_v2(const void *ev_ptr)
{
    const ev_threat_t *ev = (const ev_threat_t *)ev_ptr;
    if (!g_ready || !ev) return;

    char e_value[512], e_source[64], e_stix[128], e_l7[64];
    char sql[4096];

    escape_str(ev->domain,       e_value,  sizeof(e_value));
    escape_str(ev->intel_source, e_source, sizeof(e_source));
    escape_str(ev->v2_stix_id,   e_stix,   sizeof(e_stix));
    escape_str(ev->v2_l7_proto,  e_l7,     sizeof(e_l7));

    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_TI_HITS_V2 " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,l4_proto,l7_proto,"
        " matched_field,matched_mode,matched_value,matched_source,matched_stix_id,confidence,tlp) "
        "VALUES (toDateTime(%lld),'%s','%s',%u,%u,%u,'%s','%s','%s','%s','%s','%s',%u,'%s')",
        (long long)ev->ts_sec,
        ev->src_ip, ev->dst_ip,
        ev->src_port, ev->dst_port, ev->protocol,
        e_l7,
        v2_match_field_name(ev->v2_match_field),
        v2_match_mode_name(ev->v2_match_mode),
        e_value,
        e_source,
        e_stix,
        ev->v2_confidence,
        v2_tlp_name(ev->v2_tlp));

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "ti_hits_v2 write failed");
    }
}

void ch_insert_ids_hit(const void *ev_ptr)
{
    const ev_ids_t *ev = (const ev_ids_t *)ev_ptr;
    if (!g_ready || !ev) return;

    char e_host[512], e_uri[1024], e_msg[512], e_cve[64], e_src[128], e_needle[256];
    char sql[4096];

    escape_str(ev->host,           e_host,   sizeof(e_host));
    escape_str(ev->uri,            e_uri,    sizeof(e_uri));
    escape_str(ev->msg,            e_msg,    sizeof(e_msg));
    escape_str(ev->cve,            e_cve,    sizeof(e_cve));
    escape_str(ev->rule_source,    e_src,    sizeof(e_src));
    escape_str(ev->matched_needle, e_needle, sizeof(e_needle));

    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_IDS_HITS " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,l4_proto,"
        " host,uri,method,sid,msg,cve,rule_source,matched_needle) "
        "VALUES (toDateTime(%lld),'%s','%s',%u,%u,%u,"
        "'%s','%s','%s',%d,'%s','%s','%s','%s')",
        (long long)ev->ts_sec,
        ev->src_ip, ev->dst_ip,
        ev->src_port, ev->dst_port, ev->protocol,
        e_host, e_uri, ev->method,
        ev->sid, e_msg, e_cve, e_src, e_needle);

    int rc = ch_exec(sql);
    if (rc != 0) {
        const char *msg = "[CH] ids_hits write failed\n";
        (void)!write(2, msg, 27);
    } else {
        /* 成功的心跳标记，验证后可删 */
        static int shown = 0;
        if (shown < 3) {
            char b[128];
            int n = snprintf(b, sizeof(b), "[CH] ids_hits write ok sid=%d uri=%.60s\n", ev->sid, ev->uri);
            (void)!write(2, b, (size_t)n);
            shown++;
        }
    }
}

void ch_insert_focus_site(const char *src_ip, const char *dst_ip,
                          uint16_t src_port, uint16_t dst_port,
                          uint8_t protocol,
                          const char *domain,
                          const char *domain_group,
                          const char *source_type,
                          const char *focus_tag,
                          int64_t ts_sec)
{
    char e_domain[512], e_group[256], e_source[32], e_focus[64];
    char sql[2048];

    if (!g_ready) return;

    escape_str(domain, e_domain, sizeof(e_domain));
    escape_str(domain_group, e_group, sizeof(e_group));
    escape_str(source_type, e_source, sizeof(e_source));
    escape_str(focus_tag, e_focus, sizeof(e_focus));

    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_FOCUS_SITE " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,protocol,domain,domain_group,source_type,focus_tag) "
        "VALUES (toDateTime(%lld),'%s','%s',%u,%u,%u,'%s','%s','%s','%s')",
        (long long)ts_sec,
        src_ip ? src_ip : "",
        dst_ip ? dst_ip : "",
        src_port, dst_port, protocol,
        e_domain, e_group, e_source, e_focus);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "focus_site_events write failed");
    }
}

void ch_insert_site_access(const char *domain,
                           const char *domain_group,
                           const char *src_ip,
                           int64_t ts_sec)
{
    if (!g_ready) return;

    char e_domain[512], e_group[256], e_src[128];
    escape_str(domain ? domain : "", e_domain, sizeof(e_domain));
    escape_str(domain_group ? domain_group : "", e_group, sizeof(e_group));
    escape_str(src_ip ? src_ip : "", e_src, sizeof(e_src));

    char sql[2048];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_SITE_ACCESS_RANK " "
        "(captured_at,domain,domain_group,src_ip,access_count) "
        "VALUES (toDateTime(%lld),'%s','%s','%s',1)",
        (long long)ts_sec,
        e_domain, e_group, e_src);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "site_access_ranking write failed");
    }
}

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
                          int64_t ts_sec)
{
    if (!g_ready) return;

    char e_src[128], e_dst[128], e_sid[256];
    /* payload_hex 最多 512 字节 hex + null；payload_text 同长度，转义后最多翻倍 */
    char e_hex[1100], e_text[1100];

    escape_str(src_ip     ? src_ip     : "", e_src,  sizeof(e_src));
    escape_str(dst_ip     ? dst_ip     : "", e_dst,  sizeof(e_dst));
    escape_str(session_id ? session_id : "", e_sid,  sizeof(e_sid));
    escape_str(payload_hex  ? payload_hex  : "", e_hex,  sizeof(e_hex));
    escape_str(payload_text ? payload_text : "", e_text, sizeof(e_text));

    char sql[3072];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_ICMP " "
        "(captured_at,session_id,src_ip,dst_ip,"
        "icmp_type,icmp_code,identifier,seq_num,"
        "payload_len,payload_hex,payload_text) "
        "VALUES (toDateTime(%lld),'%s','%s','%s',"
        "%u,%u,%u,%u,"
        "%u,'%s','%s')",
        (long long)ts_sec,
        e_sid, e_src, e_dst,
        (unsigned)icmp_type, (unsigned)icmp_code,
        (unsigned)identifier, (unsigned)seq_num,
        (unsigned)payload_len, e_hex, e_text);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "icmp_events write failed");
    }
}

void ch_insert_v2ray_event(const void *ev_ptr)
{
    const ev_v2ray_t *ev = (const ev_v2ray_t *)ev_ptr;
    if (!g_ready || !ev) return;

    char e_src[128], e_dst[128];
    char e_detected[128], e_method[128], e_proto[64];
    char e_mth[64], e_host[512], e_path[512], e_upgrade[128];

    escape_str(ev->src_ip, e_src, sizeof(e_src));
    escape_str(ev->dst_ip, e_dst, sizeof(e_dst));
    escape_str(ev->detected_type, e_detected, sizeof(e_detected));
    escape_str(ev->detection_method, e_method, sizeof(e_method));
    escape_str(ev->protocol_name, e_proto, sizeof(e_proto));
    escape_str(ev->method, e_mth, sizeof(e_mth));
    escape_str(ev->host, e_host, sizeof(e_host));
    escape_str(ev->path, e_path, sizeof(e_path));
    escape_str(ev->upgrade, e_upgrade, sizeof(e_upgrade));

    char sql[8192];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_V2RAY_EVENTS " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,detected_type,detection_method,"
        "protocol_name,method,host,path,upgrade,score,avg_entropy,dominant_ratio,"
        "packet_count,packets_ab,packets_ba,bytes_ab,bytes_ba,saw_connect,"
        "saw_ws_upgrade,saw_proxy_auth,saw_path_hint) "
        "VALUES (toDateTime(%lld),'%s','%s',%u,%u,'%s','%s','%s','%s','%s','%s','%s',"
        "%u,%.6f,%.6f,%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%u,%u,%u,%u)",
        (long long)ev->ts_sec,
        e_src, e_dst,
        ev->src_port, ev->dst_port,
        e_detected, e_method, e_proto, e_mth, e_host, e_path, e_upgrade,
        (unsigned)ev->score,
        ev->avg_entropy,
        ev->dominant_ratio,
        ev->packet_count,
        ev->packets_ab,
        ev->packets_ba,
        ev->bytes_ab,
        ev->bytes_ba,
        ev->saw_connect,
        ev->saw_ws_upgrade,
        ev->saw_proxy_auth,
        ev->saw_path_hint);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "v2ray_events write failed");
    }
}

/* ------------------------------------------------------------------ */
/*  Shadowsocks 概率型识别事件                                          */
/* ------------------------------------------------------------------ */

const char *ss_state_name(uint8_t s)
{
    switch (s) {
    case 0: return "init";
    case 1: return "candidate";
    case 2: return "confirmed";
    case 3: return "excluded";
    default: return "unknown";
    }
}

const char *ss_path_name(uint8_t p)
{
    switch (p) {
    case 1:  return "tcp";
    case 2:  return "udp";
    default: return "unknown";
    }
}

const char *ss_confidence_name(uint8_t c)
{
    if (c >= 80) return "high";
    if (c >= 50) return "medium";
    if (c >  0)  return "low";
    return "none";
}

void ss_format_reason(uint32_t mask, char *out, size_t cap)
{
    if (!out || cap == 0) return;
    out[0] = '\0';
    if (mask == 0) { snprintf(out, cap, "none"); return; }

    static const struct { uint32_t bit; const char *tag; } TAGS[] = {
        { 1u << 0, "high_entropy" },
        { 1u << 1, "no_known_header" },
        { 1u << 2, "len_pattern" },
        { 1u << 3, "bidir_balance" },
        { 1u << 4, "proxy_burst" },
        { 1u << 5, "not_tls" },
        { 1u << 6, "not_quic" },
        { 1u << 7, "port_hint" },
        { 1u << 8, "not_handshake" },
        { 1u << 9, "len_random_var" },
    };

    size_t off = 0;
    for (size_t i = 0; i < sizeof(TAGS) / sizeof(TAGS[0]); i++) {
        if (!(mask & TAGS[i].bit)) continue;
        int n = snprintf(out + off, cap - off, "%s%s",
                         off == 0 ? "" : ",", TAGS[i].tag);
        if (n < 0 || (size_t)n >= cap - off) break;
        off += (size_t)n;
    }
}

void ch_insert_shadowsocks_event(const void *ev_ss_ptr)
{
    const ev_shadowsocks_t *ev = (const ev_shadowsocks_t *)ev_ss_ptr;
    if (!g_ready || !ev) return;

    char e_src[128], e_dst[128];
    char e_reason[256];
    char raw_reason[256];

    escape_str(ev->src_ip, e_src, sizeof(e_src));
    escape_str(ev->dst_ip, e_dst, sizeof(e_dst));

    ss_format_reason(ev->reason_mask, raw_reason, sizeof(raw_reason));
    escape_str(raw_reason, e_reason, sizeof(e_reason));

    const char *state_name = ss_state_name(ev->state);
    const char *path_name  = ss_path_name(ev->path_type);
    const char *conf_name  = ss_confidence_name(ev->confidence);

    char sql[4096];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_SHADOWSOCKS_EVENTS " "
        "(captured_at,src_ip,dst_ip,src_port,dst_port,transport_proto,protocol_name,"
        "state,state_name,confidence,confidence_name,path_type,path_name,"
        "score,reason_mask,reason_text,avg_entropy,max_entropy,"
        "packets_ab,packets_ba,bytes_ab,bytes_ba,first_seen_ts) "
        "VALUES (toDateTime(%lld),'%s','%s',%u,%u,%u,'Shadowsocks',"
        "%u,'%s',%u,'%s',%u,'%s',"
        "%u,%u,'%s',%.6f,%.6f,"
        "%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",%" PRIu64 ",toDateTime(%lld))",
        (long long)ev->ts_sec,
        e_src, e_dst,
        ev->src_port, ev->dst_port,
        (unsigned)ev->transport_proto,
        (unsigned)ev->state, state_name,
        (unsigned)ev->confidence, conf_name,
        (unsigned)ev->path_type, path_name,
        (unsigned)ev->score, (unsigned)ev->reason_mask, e_reason,
        ev->avg_entropy, ev->max_entropy,
        ev->packets_ab, ev->packets_ba,
        ev->bytes_ab, ev->bytes_ba,
        (long long)ev->first_seen_ts);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "shadowsocks_events write failed");
    }
}

void ch_insert_operation_log(const char *event_type,
                              const char *source,
                              const char *actor,
                              const char *plugin_name,
                              uint32_t plugin_id,
                              const char *detail,
                              uint8_t status,
                              const char *extra)
{
    if (!g_ready) return;

    char e_event[64], e_source[32], e_actor[128];
    char e_plugin[128], e_detail[1024], e_extra[2048];

    escape_str(event_type  ? event_type  : "", e_event,  sizeof(e_event));
    escape_str(source      ? source      : "", e_source, sizeof(e_source));
    escape_str(actor       ? actor       : "", e_actor,  sizeof(e_actor));
    escape_str(plugin_name ? plugin_name : "", e_plugin, sizeof(e_plugin));
    escape_str(detail      ? detail      : "", e_detail, sizeof(e_detail));
    escape_str(extra       ? extra       : "", e_extra,  sizeof(e_extra));

    char sql[4096];
    snprintf(sql, sizeof(sql),
        "INSERT INTO " CH_TBL_OPERATION_LOGS " "
        "(captured_at,event_type,source,actor,plugin_name,plugin_id,detail,status,extra) "
        "VALUES (now(),'%s','%s','%s','%s',%u,'%s',%u,'%s')",
        e_event, e_source, e_actor, e_plugin,
        (unsigned)plugin_id, e_detail, (unsigned)status, e_extra);

    if (ch_exec(sql) != 0) {
        TM_LOG_ERROR("CH", "operation_logs write failed");
    }
}
