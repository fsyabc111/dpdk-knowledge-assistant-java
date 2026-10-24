/**
 * @file ch_event.c
 * @brief 异步 ClickHouse 事件队列 —— 实现
 *
 * DPDK 模式：ch_event_enqueue() 从 mempool 取 event_task_t，
 *            值拷贝 payload 后推入 g_event_ring，立即返回（< 200ns）。
 *
 * 单线程 fallback：ring 未初始化时调用原始同步 ch_insert_*()。
 */

#include "ch_event.h"
#include "ch_storage.h"

#include <string.h>
#include <stdio.h>
#include <unistd.h>
#include "../../log.h"

#ifdef CAPTURE_ENABLE_DPDK
#include <rte_ring.h>
#include <rte_mempool.h>
#include <rte_atomic.h>
#endif

/* ------------------------------------------------------------------ */
/*  模块状态（文件作用域单例）                                            */
/* ------------------------------------------------------------------ */

#ifdef CAPTURE_ENABLE_DPDK
static struct rte_ring    *s_event_ring = NULL;
static struct rte_mempool *s_event_pool = NULL;
#endif

/* 使用 GCC/DPDK 原子操作，避免引入 C11 _Atomic */
static volatile uint64_t s_dropped = 0;

/* ------------------------------------------------------------------ */
/*  初始化（仅 DPDK 模式）                                               */
/* ------------------------------------------------------------------ */

#ifdef CAPTURE_ENABLE_DPDK
int ch_event_init(struct rte_ring *ring, struct rte_mempool *pool)
{
    if (!ring || !pool) {
        TM_LOG_ERROR("CH_EVENT", "ch_event_init: ring or pool is NULL");
        return -1;
    }
    s_event_ring = ring;
    s_event_pool = pool;
    return 0;
}
#endif

/* ------------------------------------------------------------------ */
/*  统计查询                                                              */
/* ------------------------------------------------------------------ */

uint64_t ch_event_get_dropped(void)
{
    return s_dropped;
}

/* ------------------------------------------------------------------ */
/*  单线程 fallback：将 event_task_t 路由到对应的同步 ch_insert_*()      */
/* ------------------------------------------------------------------ */

static void event_sync_fallback(event_type_t type, const ev_payload_u *p)
{
    if (!ch_storage_is_ready()) return;

    switch (type) {
    case EV_HTTP:
        ch_insert_http(p->http.src_ip, p->http.dst_ip,
                       p->http.src_port, p->http.dst_port,
                       p->http.method, p->http.uri,
                       p->http.host, p->http.user_agent,
                       p->http.content_type, p->http.protocol_type,
                       p->http.ts_sec);
        break;
    case EV_HTTPS:
        ch_insert_https(p->https.src_ip, p->https.dst_ip,
                        p->https.src_port, p->https.dst_port,
                        p->https.bytes, p->https.packets,
                        p->https.tls_version, p->https.sni,
                        p->https.ts_sec);
        break;
    case EV_TLS:
        ch_insert_tls(p->tls.src_ip, p->tls.dst_ip,
                      p->tls.src_port, p->tls.dst_port,
                      p->tls.tls_version, p->tls.sni,
                      p->tls.cipher_suites,
                      p->tls.cipher_suites_count, p->tls.extensions_count,
                      p->tls.ts_sec);
        break;
    case EV_JA3:
        ch_insert_ja3(p->ja3.src_ip, p->ja3.dst_ip,
                      p->ja3.src_port, p->ja3.dst_port,
                      p->ja3.sni, p->ja3.tls_version,
                      p->ja3.cipher_suites, p->ja3.extensions,
                      p->ja3.elliptic_curves, p->ja3.ec_point_formats,
                      p->ja3.ja3_string, p->ja3.ja3_hash,
                      p->ja3.ts_sec);
        break;
    case EV_FTP:
        ch_insert_ftp(p->simple.src_ip, p->simple.dst_ip,
                      p->simple.src_port, p->simple.dst_port,
                      p->simple.protocol, p->simple.protocol_name,
                      p->simple.extra,
                      p->simple.ts_sec);
        break;
    case EV_SMTP:
        ch_insert_smtp(p->simple.src_ip, p->simple.dst_ip,
                       p->simple.src_port, p->simple.dst_port,
                       p->simple.protocol, p->simple.protocol_name,
                       p->simple.extra,
                       p->simple.ts_sec);
        break;
    case EV_SSH:
        ch_insert_ssh(p->ssh.src_ip, p->ssh.dst_ip,
                      p->ssh.src_port, p->ssh.dst_port,
                      p->ssh.protocol, p->ssh.protocol_name,
                      p->ssh.client_version, p->ssh.server_version,
                      p->ssh.ts_sec);
        break;
    case EV_QUIC:
        ch_insert_quic(p->quic.src_ip, p->quic.dst_ip,
                       p->quic.src_port, p->quic.dst_port,
                       p->quic.protocol, p->quic.protocol_name,
                       p->quic.last_header_form, p->quic.last_long_type,
                       p->quic.packet_number_len,
                       p->quic.dcid_len, p->quic.scid_len,
                       p->quic.packet_number,
                       p->quic.dcid, p->quic.scid,
                       p->quic.quic_version,
                       p->quic.sni, p->quic.alpn,
                       p->quic.is_doq, p->quic.confidence,
                       p->quic.detect_reason,
                       p->quic.ts_sec);
        break;
    case EV_WIREGUARD:
        ch_insert_wireguard(p->simple.src_ip, p->simple.dst_ip,
                            p->simple.src_port, p->simple.dst_port,
                            p->simple.protocol, p->simple.protocol_name,
                            p->simple.ts_sec);
        break;
    case EV_POP3:
        ch_insert_pop3(p->simple.src_ip, p->simple.dst_ip,
                       p->simple.src_port, p->simple.dst_port,
                       p->simple.protocol, p->simple.protocol_name,
                       p->simple.extra,
                       p->simple.ts_sec);
        break;
    case EV_IMAP:
        ch_insert_imap(p->simple.src_ip, p->simple.dst_ip,
                       p->simple.src_port, p->simple.dst_port,
                       p->simple.protocol, p->simple.protocol_name,
                       p->simple.ts_sec);
        break;
    case EV_RTSP:
        ch_insert_rtsp(p->simple.src_ip, p->simple.dst_ip,
                       p->simple.src_port, p->simple.dst_port,
                       p->simple.protocol, p->simple.protocol_name,
                       p->simple.ts_sec);
        break;
    case EV_IPSECVPN:
        ch_insert_ipsecvpn(p->simple.src_ip, p->simple.dst_ip,
                           p->simple.src_port, p->simple.dst_port,
                           p->simple.protocol, p->simple.protocol_name,
                           p->simple.ts_sec);
        break;
    case EV_OPENVPN:
        ch_insert_openvpn(p->simple.src_ip, p->simple.dst_ip,
                          p->simple.src_port, p->simple.dst_port,
                          p->simple.protocol, p->simple.protocol_name,
                          p->simple.ts_sec);
        break;
    case EV_DNS:
        ch_insert_dns_resolution(p->dns.client_ip, p->dns.resolver_ip,
                                 p->dns.src_port, p->dns.dst_port, p->dns.transport_proto,
                                 p->dns.qname, p->dns.qtype, p->dns.rcode,
                                 p->dns.answer_ips_ch, p->dns.answer_domains_ch,
                                 p->dns.answer_types_ch, p->dns.ttls_ch,
                                 p->dns.answer_count,
                                 p->dns.is_success, p->dns.ts_sec);
        break;
    case EV_DTLS:
        ch_insert_dtls_event(p->dtls.src_ip, p->dtls.dst_ip,
                             p->dtls.src_port, p->dtls.dst_port,
                             p->dtls.version, p->dtls.record_type,
                             p->dtls.handshake_type, p->dtls.epoch,
                             p->dtls.record_len, p->dtls.payload_len,
                             p->dtls.detail_desc,
                             p->dtls.is_handshake, p->dtls.is_appdata,
                             p->dtls.is_alert, "UDP", p->dtls.ts_sec);
        break;
    case EV_TLS_INFRA:
        ch_insert_tls_infra_event(p->tls_infra.src_ip, p->tls_infra.dst_ip,
                                  p->tls_infra.src_port, p->tls_infra.dst_port,
                                  p->tls_infra.tls_record_version,
                                  p->tls_infra.record_type,
                                  p->tls_infra.record_len,
                                  p->tls_infra.payload_len,
                                  p->tls_infra.detected_type,
                                  p->tls_infra.detection_method,
                                  p->tls_infra.sni, p->tls_infra.alpn,
                                  p->tls_infra.is_doh, p->tls_infra.is_dot,
                                  p->tls_infra.is_ech, p->tls_infra.is_dns_tunnel,
                                  p->tls_infra.ts_sec);
        break;
    case EV_RDP:
        ch_insert_rdp_event(p->rdp.src_ip, p->rdp.dst_ip,
                            p->rdp.src_port, p->rdp.dst_port,
                            p->rdp.tpdu_type, p->rdp.tpdu_type_name,
                            p->rdp.cookie, p->rdp.requested_protocols,
                            p->rdp.cookie_seen, p->rdp.security_seen,
                            p->rdp.payload_len, "", p->rdp.ts_sec);
        break;
    case EV_RTMP:
        ch_insert_rtmp_event(p->rtmp.src_ip, p->rtmp.dst_ip,
                             p->rtmp.src_port, p->rtmp.dst_port,
                             p->rtmp.event_type,
                             p->rtmp.is_handshake, p->rtmp.is_command,
                             p->rtmp.payload_len, "", p->rtmp.ts_sec);
        break;
    case EV_SMB:
        ch_insert_smb_event(p->smb.src_ip, p->smb.dst_ip,
                            p->smb.src_port, p->smb.dst_port,
                            p->smb.smb_version, p->smb.smb_version_name,
                            p->smb.payload_len, "", p->smb.ts_sec);
        break;
    case EV_GEO:
        ch_insert_geo_event(p->geo.event_type, p->geo.session_id,
                            p->geo.src_ip, p->geo.dst_ip,
                            p->geo.dst_country,
                            p->geo.src_port, p->geo.dst_port,
                            p->geo.protocol,
                            p->geo.bytes_up, p->geo.bytes_down,
                            p->geo.ratio, p->geo.message,
                            p->geo.ts_sec);
        break;
    case EV_DOMAIN_ACCESS:
        ch_insert_domain_access(p->domain.src_ip, p->domain.dst_ip,
                                p->domain.src_port, p->domain.dst_port,
                                p->domain.protocol,
                                p->domain.domain, p->domain.domain_group,
                                p->domain.source_type, p->domain.focus_tag,
                                p->domain.ts_sec);
        break;
    case EV_FOCUS_SITE:
        ch_insert_focus_site(p->domain.src_ip, p->domain.dst_ip,
                             p->domain.src_port, p->domain.dst_port,
                             p->domain.protocol,
                             p->domain.domain, p->domain.domain_group,
                             p->domain.source_type, p->domain.focus_tag,
                             p->domain.ts_sec);
        break;
    case EV_THREAT_DOMAIN:
        /* Phase 5 起只写 ti_hits_v2（富 schema）；旧 threat_domain_events
         * 已停止写入，避免重复。旧表历史数据仍可通过 CH 直接查询。 */
        ch_insert_ti_hit_v2(&p->threat);
        break;
    case EV_SITE_ACCESS:
        ch_insert_site_access(p->site_access.domain,
                              p->site_access.domain_group,
                              p->site_access.src_ip,
                              p->site_access.ts_sec);
        break;
    case EV_ICMP_TUNNEL:
        ch_insert_icmp_event(p->icmp.src_ip, p->icmp.dst_ip,
                             p->icmp.session_id,
                             p->icmp.icmp_type, p->icmp.icmp_code,
                             p->icmp.identifier, p->icmp.seq_num,
                             p->icmp.payload_len,
                             p->icmp.payload_hex, p->icmp.payload_text,
                             p->icmp.ts_sec);
        break;
    case EV_V2RAY:
        ch_insert_v2ray_event(&p->v2ray);
        break;
    case EV_IDS:
        ch_insert_ids_hit(&p->ids);
        break;
    case EV_SHADOWSOCKS:
        ch_insert_shadowsocks_event(&p->shadowsocks);
        break;
    default:
        break;
    }
}

/* ------------------------------------------------------------------ */
/*  主入口：非阻塞入队（DPDK）或同步写（单线程 fallback）                 */
/* ------------------------------------------------------------------ */

void ch_event_enqueue(event_type_t type,
                      const void  *payload,
                      size_t       payload_size)
{
#ifdef CAPTURE_ENABLE_DPDK
    if (s_event_ring && s_event_pool) {
        /* DPDK 模式：非阻塞入队 */
        event_task_t *task = NULL;
        if (rte_mempool_get(s_event_pool, (void **)&task) != 0) {
            __sync_fetch_and_add(&s_dropped, 1);
            return;
        }
        task->type = type;
        memset(&task->payload, 0, sizeof(task->payload));
        if (payload_size > sizeof(task->payload))
            payload_size = sizeof(task->payload);
        memcpy(&task->payload, payload, payload_size);

        if (rte_ring_enqueue(s_event_ring, task) != 0) {
            rte_mempool_put(s_event_pool, task);
            __sync_fetch_and_add(&s_dropped, 1);
        }
        return;
    }
#endif
    /* 单线程 fallback：同步写 ClickHouse */
    event_sync_fallback(type, (const ev_payload_u *)payload);
}
