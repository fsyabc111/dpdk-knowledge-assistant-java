/* P1: -std=c11 严格模式下 <time.h> 不暴露 clock_gettime/CLOCK_MONOTONIC(POSIX),
 * 必须在任何 #include 之前声明 feature-test 宏 */
#define _POSIX_C_SOURCE 199309L
#include <stdio.h>
#include "session.h"
#include <stdlib.h>
#include <string.h>
#include <time.h>

static session_expire_callback_t g_session_expire_callback = NULL;
static void *g_session_expire_user_data = NULL;

/* -------------------------------------------------------------------------
 * 内部辅助：IPv4/IPv6 地址比较
 * 返回值：<0 表示 a<b，0 表示相等，>0 表示 a>b
 * ---------------------------------------------------------------------- */
static int ip_addr_cmp(uint8_t ip_version, const ip_addr_t *a, const ip_addr_t *b)
{
    if (ip_version == 6) {
        return memcmp(a->v6, b->v6, 16);
    }
    /* IPv4：比较 uint32_t（网络字节序，直接数值比较即可） */
    if (a->v4 < b->v4) return -1;
    if (a->v4 > b->v4) return  1;
    return 0;
}

/* 哈希函数（FNV-1a） */
static uint32_t session_hash(session_key_t *key, uint32_t table_size)
{
    uint32_t hash = 2166136261u;

    if (key->ip_version == 6) {
        /* IPv6：对 16 字节逐字节哈希 */
        for (int i = 0; i < 16; i++) {
            hash ^= key->ip_low.v6[i];
            hash *= 16777619;
        }
        for (int i = 0; i < 16; i++) {
            hash ^= key->ip_high.v6[i];
            hash *= 16777619;
        }
    } else {
        /* IPv4 */
        hash ^= key->ip_low.v4;
        hash *= 16777619;
        hash ^= key->ip_high.v4;
        hash *= 16777619;
    }

    hash ^= (key->port_low << 16) | key->port_high;
    hash *= 16777619;
    hash ^= key->protocol;
    return hash % table_size;
}

/* 规范化五元组（IPv4） */
static void normalize_key_v4(session_key_t *key,
                              uint32_t src_ip, uint32_t dst_ip,
                              uint16_t src_port, uint16_t dst_port,
                              uint8_t protocol)
{
    key->ip_version = 4;
    memset(&key->ip_low,  0, sizeof(ip_addr_t));
    memset(&key->ip_high, 0, sizeof(ip_addr_t));

    if (src_ip < dst_ip || (src_ip == dst_ip && src_port <= dst_port)) {
        key->ip_low.v4  = src_ip;
        key->ip_high.v4 = dst_ip;
        key->port_low   = src_port;
        key->port_high  = dst_port;
    } else {
        key->ip_low.v4  = dst_ip;
        key->ip_high.v4 = src_ip;
        key->port_low   = dst_port;
        key->port_high  = src_port;
    }
    key->protocol = protocol;
}

/* 规范化五元组（IPv6） */
static void normalize_key_v6(session_key_t *key,
                              const uint8_t *src_ip6, const uint8_t *dst_ip6,
                              uint16_t src_port, uint16_t dst_port,
                              uint8_t protocol)
{
    key->ip_version = 6;
    int cmp = memcmp(src_ip6, dst_ip6, 16);
    if (cmp < 0 || (cmp == 0 && src_port <= dst_port)) {
        memcpy(key->ip_low.v6,  src_ip6, 16);
        memcpy(key->ip_high.v6, dst_ip6, 16);
        key->port_low  = src_port;
        key->port_high = dst_port;
    } else {
        memcpy(key->ip_low.v6,  dst_ip6, 16);
        memcpy(key->ip_high.v6, src_ip6, 16);
        key->port_low  = dst_port;
        key->port_high = src_port;
    }
    key->protocol = protocol;
}

/* 比较两个键是否相等 */
static int key_equal(session_key_t *k1, session_key_t *k2)
{
    if (k1->ip_version != k2->ip_version) return 0;
    if (k1->port_low   != k2->port_low)   return 0;
    if (k1->port_high  != k2->port_high)  return 0;
    if (k1->protocol   != k2->protocol)   return 0;

    if (k1->ip_version == 6) {
        return memcmp(k1->ip_low.v6,  k2->ip_low.v6,  16) == 0 &&
               memcmp(k1->ip_high.v6, k2->ip_high.v6, 16) == 0;
    }
    return k1->ip_low.v4 == k2->ip_low.v4 &&
           k1->ip_high.v4 == k2->ip_high.v4;
}


static session_candidate_t* find_candidate(session_entry_t *session,
                                           ext_field_type_t type,
                                           int create_if_missing) {
    if (!session) return NULL;

    for (int i = 0; i < SESSION_MAX_CANDIDATES; i++) {
        if (session->candidates[i].active && session->candidates[i].type == type) {
            return &session->candidates[i];
        }
    }

    if (!create_if_missing) return NULL;

    for (int i = 0; i < SESSION_MAX_CANDIDATES; i++) {
        if (!session->candidates[i].active) {
            memset(&session->candidates[i], 0, sizeof(session->candidates[i]));
            session->candidates[i].active = 1;
            session->candidates[i].type = type;
            return &session->candidates[i];
        }
    }

    return NULL;
}

void session_free_candidates(session_entry_t *session) {
    if (!session) return;

    for (int i = 0; i < SESSION_MAX_CANDIDATES; i++) {
        session_candidate_t *c = &session->candidates[i];
        if (c->active && c->data && c->data != session->ext_data) {
            free(c->data);
        }
        memset(c, 0, sizeof(*c));
    }
}

/* =========================================================================
 * 会话管理器
 * ====================================================================== */

session_manager_t* session_manager_create(uint32_t table_size, uint32_t timeout)
{
    session_manager_t *mgr = malloc(sizeof(session_manager_t));
    if (!mgr) return NULL;

    mgr->hash_table = calloc(table_size, sizeof(session_entry_t*));
    if (!mgr->hash_table) {
        free(mgr);
        return NULL;
    }

    mgr->table_size    = table_size;
    mgr->session_count = 0;
    mgr->timeout       = timeout;
    return mgr;
}

void session_manager_destroy(session_manager_t *mgr)
{
    if (!mgr) return;

    for (uint32_t i = 0; i < mgr->table_size; i++) {
        session_entry_t *entry = mgr->hash_table[i];
        while (entry) {
            session_entry_t *next = entry->next;
            session_free_candidates(entry);
            if (entry->ext_data) free(entry->ext_data);
            if (entry->tunnel_signal) free(entry->tunnel_signal);
            if (entry->geo_summary) free(entry->geo_summary);
            if (entry->tls_stream_buf) free(entry->tls_stream_buf);
            free(entry);
            entry = next;
        }
    }
    free(mgr->hash_table);
    free(mgr);
}

/* =========================================================================
 * IPv4 会话接口
 * ====================================================================== */

session_entry_t* session_lookup(session_manager_t *mgr,
                                uint32_t src_ip, uint32_t dst_ip,
                                uint16_t src_port, uint16_t dst_port,
                                uint8_t protocol)
{
    session_key_t key;
    normalize_key_v4(&key, src_ip, dst_ip, src_port, dst_port, protocol);

    uint32_t hash = session_hash(&key, mgr->table_size);
    session_entry_t *entry = mgr->hash_table[hash];
    time_t now = time(NULL);

    while (entry) {
        if (key_equal(&entry->key, &key)) {
            if (now - entry->last_seen >= (time_t)mgr->timeout) {
                /* 过期会话，跳过并继续检查链中后续同 key 的活跃会话 */
                entry = entry->next;
                continue;
            }
            return entry;
        }
        entry = entry->next;
    }
    return NULL;
}

session_entry_t* session_create(session_manager_t *mgr,
                                uint32_t src_ip, uint32_t dst_ip,
                                uint16_t src_port, uint16_t dst_port,
                                uint8_t protocol)
{
    session_entry_t *entry = malloc(sizeof(session_entry_t));
    if (!entry) return NULL;

    memset(entry, 0, sizeof(session_entry_t));
    normalize_key_v4(&entry->key, src_ip, dst_ip, src_port, dst_port, protocol);

    entry->ip_version  = 4;
    entry->src_ip      = src_ip;
    entry->dst_ip      = dst_ip;
    entry->src_port    = src_port;
    entry->dst_port    = dst_port;
    entry->create_time = time(NULL);
    entry->last_seen   = entry->create_time;

    uint32_t hash = session_hash(&entry->key, mgr->table_size);
    entry->next = mgr->hash_table[hash];
    mgr->hash_table[hash] = entry;
    mgr->session_count++;

    return entry;
}

int session_update(session_entry_t *session, uint32_t src_ip, uint32_t dst_ip,
                   uint32_t pkt_len)
{
    if (!session) return -1;

    session->last_seen = time(NULL);

    if (src_ip == session->src_ip && dst_ip == session->dst_ip) {
        session->stats.packets_ab++;
        session->stats.bytes_ab += pkt_len;
    } else {
        session->stats.packets_ba++;
        session->stats.bytes_ba += pkt_len;
    }

    return 0;
}

/* =========================================================================
 * IPv6 会话接口
 * ====================================================================== */

session_entry_t* session_lookup_v6(session_manager_t *mgr,
                                   const uint8_t *src_ip6, const uint8_t *dst_ip6,
                                   uint16_t src_port, uint16_t dst_port,
                                   uint8_t protocol)
{
    session_key_t key;
    normalize_key_v6(&key, src_ip6, dst_ip6, src_port, dst_port, protocol);

    uint32_t hash = session_hash(&key, mgr->table_size);
    session_entry_t *entry = mgr->hash_table[hash];
    time_t now = time(NULL);

    while (entry) {
        if (key_equal(&entry->key, &key)) {
            if (now - entry->last_seen >= (time_t)mgr->timeout) {
                entry = entry->next;
                continue;
            }
            return entry;
        }
        entry = entry->next;
    }
    return NULL;
}

session_entry_t* session_create_v6(session_manager_t *mgr,
                                   const uint8_t *src_ip6, const uint8_t *dst_ip6,
                                   uint16_t src_port, uint16_t dst_port,
                                   uint8_t protocol)
{
    session_entry_t *entry = malloc(sizeof(session_entry_t));
    if (!entry) return NULL;

    memset(entry, 0, sizeof(session_entry_t));
    normalize_key_v6(&entry->key, src_ip6, dst_ip6, src_port, dst_port, protocol);

    entry->ip_version = 6;
    memcpy(entry->src_ip6, src_ip6, 16);
    memcpy(entry->dst_ip6, dst_ip6, 16);
    entry->src_port    = src_port;
    entry->dst_port    = dst_port;
    entry->create_time = time(NULL);
    entry->last_seen   = entry->create_time;

    uint32_t hash = session_hash(&entry->key, mgr->table_size);
    entry->next = mgr->hash_table[hash];
    mgr->hash_table[hash] = entry;
    mgr->session_count++;

    return entry;
}

int session_update_v6(session_entry_t *session,
                      const uint8_t *src_ip6, const uint8_t *dst_ip6,
                      uint32_t pkt_len)
{
    if (!session) return -1;

    session->last_seen = time(NULL);

    if (memcmp(src_ip6, session->src_ip6, 16) == 0 &&
        memcmp(dst_ip6, session->dst_ip6, 16) == 0) {
        session->stats.packets_ab++;
        session->stats.bytes_ab += pkt_len;
    } else {
        session->stats.packets_ba++;
        session->stats.bytes_ba += pkt_len;
    }

    return 0;
}

/* =========================================================================
 * 扩展数据 / 过期清理 / 统计
 * ====================================================================== */

int session_set_ext_data(session_entry_t *session, ext_field_type_t type, void *data)
{
    if (!session) return -1;

    if (session->ext_data && session->ext_data != data) {
        /* 清理所有指向旧 ext_data 的候选指针，防止 session_free_candidates
         * 在 session 过期时对已释放的 ext_data 执行 double-free。
         * 典型触发场景：两个不同协议插件在同一 session 上先后 confirm。 */
        for (int i = 0; i < SESSION_MAX_CANDIDATES; i++) {
            if (session->candidates[i].active &&
                session->candidates[i].data == session->ext_data) {
                session->candidates[i].data = NULL;
            }
        }
        free(session->ext_data);
    }

    session->ext_type = type;
    session->ext_data = data;
    session->ext_locked = 1;
    return 0;
}

void* session_get_ext_data(session_entry_t *session)
{
    return session ? session->ext_data : NULL;
}

geo_summary_t* session_ensure_geo_summary(session_entry_t *session)
{
    if (!session) return NULL;

    if (!session->geo_summary) {
        session->geo_summary = calloc(1, sizeof(*session->geo_summary));
    }

    return session->geo_summary;
}

geo_summary_t* session_get_geo_summary(session_entry_t *session)
{
    return session ? session->geo_summary : NULL;
}

session_candidate_t* session_get_candidate(session_entry_t *session,
                                           ext_field_type_t type) {
    return find_candidate(session, type, 0);
}

session_candidate_t* session_ensure_candidate(session_entry_t *session,
                                              ext_field_type_t type) {
    return find_candidate(session, type, 1);
}

void* session_get_candidate_data(session_entry_t *session,
                                 ext_field_type_t type) {
    session_candidate_t *c = find_candidate(session, type, 0);
    return c ? c->data : NULL;
}

int session_set_candidate_data(session_entry_t *session,
                               ext_field_type_t type,
                               void *data) {
    session_candidate_t *c = find_candidate(session, type, 1);
    if (!c) return -1;

    if (c->data && c->data != data) {
        free(c->data);
    }

    c->data = data;
    return 0;
}

int session_confirm_candidate(session_entry_t *session,
                              ext_field_type_t type) {
    session_candidate_t *c = find_candidate(session, type, 0);
    if (!c || !c->data) return -1;

    /* 若已被其他协议类型锁定，拒绝二次 confirm，防止 session_set_ext_data
     * 释放前一个已确认候选的 ext_data 后造成 double-free。 */
    if (session->ext_locked && session->ext_type != type) return -1;

    c->confirmed = 1;

    if (session_set_ext_data(session, type, c->data) != 0) {
        return -1;
    }

    return 0;
}

int session_is_locked(const session_entry_t *session) {
    return session ? session->ext_locked : 0;
}

void session_set_expire_callback(session_expire_callback_t callback, void *user_data)
{
    /* session 层只保存一个轻量回调指针，不直接依赖上层画像/裁决模块的具体实现。 */
    g_session_expire_callback = callback;
    g_session_expire_user_data = user_data;
}

int session_flush_expired(session_manager_t *mgr)
{
    if (!mgr) return -1;

    time_t now = time(NULL);
    int flushed = 0;

    for (uint32_t i = 0; i < mgr->table_size; i++) {
        session_entry_t **prev  = &mgr->hash_table[i];
        session_entry_t  *entry = *prev;

        while (entry) {
            if (now - entry->last_seen >= (time_t)mgr->timeout) {
                *prev = entry->next;
                /*
                 * 回调必须发生在 free 之前；
                 * 这里是上层拿到完整 session_entry 做最终画像/裁决补记的最后时机。
                 */
                if (g_session_expire_callback) {
                    g_session_expire_callback(entry, g_session_expire_user_data);
                }
                session_free_candidates(entry);
                if (entry->ext_data) free(entry->ext_data);
                if (entry->tunnel_signal) free(entry->tunnel_signal);
                if (entry->geo_summary) free(entry->geo_summary);
                if (entry->tls_stream_buf) free(entry->tls_stream_buf);
                free(entry);
                mgr->session_count--;
                flushed++;
                entry = *prev;
            } else {
                prev  = &entry->next;
                entry = entry->next;
            }
        }
    }

    return flushed;
}

/*
 * 游标式限额摊销版过期清扫（P1：去内联化，消 RX 黑洞）。
 *
 * 背景：worker_lcore_main 旧版每 30s 内联调用上面的 session_flush_expired，一次性把
 * 本 worker 表内全部过期会话处理完。RSS 塌缩使单 worker 独扛 ~百万会话时，一次全量清扫
 * （每会话 ~200µs 的 verdict 评估+画像）要跑 2.5-3.5 分钟，期间该线程完全不回 NIC 收包，
 * 队列无人排空 → imissed 暴涨 → RX 黑洞（见架构评估 2026-07-17 法证）。
 *
 * 本函数把"每 30s 全表一次"改为"每轮 poll 只做一小片、跨调用用 *cursor 续扫全表"：
 *   - *cursor         ：持久桶游标（由调用方 worker 局部变量保存，跨迭代传入）
 *   - bucket_scan_cap ：本次最多扫描的桶数（限制空表时的空扫成本，确定性、无需读时钟）
 *   - time_budget_us  ：本次处理过期会话的时间盒（主约束）；每释放一条过期会话后检查，
 *                       超时立即停在当前桶（*cursor 不前进，已释放条目已脱链，下次从本桶头
 *                       续扫自然跳过），把单次停顿硬性约束在 time_budget_us + 一条会话开销内。
 *
 * 安全性：会话表 per-worker、由本 worker 线程独占（无锁），故拆片处理无任何并发问题；
 * 游标遍历全表一整圈的墙钟 << timeout（300s），访问时对每条现判 last_seen → 不漏不早退。
 * 返回本次实际清理的会话数。
 */
int session_flush_expired_amortized(session_manager_t *mgr, uint32_t *cursor,
                                    uint32_t bucket_scan_cap, uint32_t time_budget_us)
{
    if (!mgr || !cursor) return -1;

    time_t now = time(NULL);
    int flushed = 0;
    uint32_t scanned = 0;
    uint32_t idx = *cursor;
    if (idx >= mgr->table_size) idx = 0;   /* 防御：游标越界（表大小变化/首次）*/

    struct timespec t0;
    clock_gettime(CLOCK_MONOTONIC, &t0);

    while (scanned < bucket_scan_cap) {
        session_entry_t **prev  = &mgr->hash_table[idx];
        session_entry_t  *entry = *prev;

        while (entry) {
            if (now - entry->last_seen >= (time_t)mgr->timeout) {
                *prev = entry->next;
                /* 回调必须在 free 之前（上层最终画像/裁决补记的最后时机）——与全量版一致 */
                if (g_session_expire_callback) {
                    g_session_expire_callback(entry, g_session_expire_user_data);
                }
                session_free_candidates(entry);
                if (entry->ext_data) free(entry->ext_data);
                if (entry->tunnel_signal) free(entry->tunnel_signal);
                if (entry->geo_summary) free(entry->geo_summary);
                if (entry->tls_stream_buf) free(entry->tls_stream_buf);
                free(entry);
                mgr->session_count--;
                flushed++;
                entry = *prev;

                /* 每处理完一条（昂贵事件，~200µs）检查时间盒；超时停在本桶，游标不前进。
                 * 时钟仅在有过期时读取（间隔 ~200µs），vDSO 开销可忽略。 */
                if (time_budget_us > 0) {
                    struct timespec t1;
                    clock_gettime(CLOCK_MONOTONIC, &t1);
                    uint64_t us = (uint64_t)(t1.tv_sec - t0.tv_sec) * 1000000ULL
                                + (uint64_t)(t1.tv_nsec - t0.tv_nsec) / 1000ULL;
                    if (us >= time_budget_us) {
                        *cursor = idx;      /* 停在当前桶，下次从本桶头续扫 */
                        return flushed;
                    }
                }
            } else {
                prev  = &entry->next;
                entry = entry->next;
            }
        }

        idx++;
        if (idx >= mgr->table_size) idx = 0;
        scanned++;
    }

    *cursor = idx;
    return flushed;
}

uint32_t session_get_count(session_manager_t *mgr)
{
    return mgr ? mgr->session_count : 0;
}
