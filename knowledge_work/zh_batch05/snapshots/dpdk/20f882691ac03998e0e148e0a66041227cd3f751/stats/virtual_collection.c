/**
 * @file virtual_collection.c
 * @brief 虚拟集合管理实现
 *
 * @version 2.0
 * @date 2026-02-06
 */

#include "virtual_collection.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/* ========== 内部辅助函数 ========== */

/**
 * @brief 创建 IP 虚拟集合
 */
static ip_virtual_collection_t* create_ip_collection(void) {
    ip_virtual_collection_t *coll = calloc(1, sizeof(ip_virtual_collection_t));
    if (!coll) {
        return NULL;
    }

    // 创建哈希表（使用质数作为桶数量）
    coll->table = hash_table_create(65537); // 约 64K 桶
    if (!coll->table) {
        free(coll);
        return NULL;
    }

    coll->capacity = VIRTUAL_IP_COLLECTION_MAX_SIZE;
    coll->count = 0;

    // 创建待写入缓冲区
    coll->pending_capacity = 10000;
    coll->pending_keys = calloc(coll->pending_capacity, sizeof(char*));
    if (!coll->pending_keys) {
        hash_table_destroy(coll->table, false);
        free(coll);
        return NULL;
    }
    coll->pending_count = 0;

    return coll;
}

/**
 * @brief 销毁 IP 虚拟集合
 */
static void destroy_ip_collection(ip_virtual_collection_t *coll) {
    if (!coll) {
        return;
    }

    if (coll->table) {
        hash_table_destroy(coll->table, true); // 释放值内存
    }

    if (coll->pending_keys) {
        for (size_t i = 0; i < coll->pending_count; i++) {
            free(coll->pending_keys[i]);
        }
        free(coll->pending_keys);
    }

    free(coll);
}

/**
 * @brief 创建会话虚拟集合
 */
static session_virtual_collection_t* create_session_collection(void) {
    session_virtual_collection_t *coll = calloc(1, sizeof(session_virtual_collection_t));
    if (!coll) {
        return NULL;
    }

    coll->table = hash_table_create(16381); // 约 16K 桶
    if (!coll->table) {
        free(coll);
        return NULL;
    }

    coll->capacity = VIRTUAL_SESSION_COLLECTION_MAX_SIZE;
    coll->count = 0;

    coll->pending_capacity = 5000;
    coll->pending_keys = calloc(coll->pending_capacity, sizeof(char*));
    if (!coll->pending_keys) {
        hash_table_destroy(coll->table, false);
        free(coll);
        return NULL;
    }
    coll->pending_count = 0;

    return coll;
}

/**
 * @brief 销毁会话虚拟集合
 */
static void destroy_session_collection(session_virtual_collection_t *coll) {
    if (!coll) {
        return;
    }

    if (coll->table) {
        hash_table_destroy(coll->table, true);
    }

    if (coll->pending_keys) {
        for (size_t i = 0; i < coll->pending_count; i++) {
            free(coll->pending_keys[i]);
        }
        free(coll->pending_keys);
    }

    free(coll);
}

/**
 * @brief 创建协议虚拟集合
 */
static protocol_virtual_collection_t* create_protocol_collection(void) {
    protocol_virtual_collection_t *coll = calloc(1, sizeof(protocol_virtual_collection_t));
    if (!coll) {
        return NULL;
    }

    coll->table = hash_table_create(127); // 小哈希表
    if (!coll->table) {
        free(coll);
        return NULL;
    }

    coll->capacity = VIRTUAL_PROTOCOL_COLLECTION_MAX_SIZE;
    coll->count = 0;

    coll->pending_capacity = 100;
    coll->pending_keys = calloc(coll->pending_capacity, sizeof(char*));
    if (!coll->pending_keys) {
        hash_table_destroy(coll->table, false);
        free(coll);
        return NULL;
    }
    coll->pending_count = 0;

    return coll;
}

/**
 * @brief 销毁协议虚拟集合
 */
static void destroy_protocol_collection(protocol_virtual_collection_t *coll) {
    if (!coll) {
        return;
    }

    if (coll->table) {
        hash_table_destroy(coll->table, true);
    }

    if (coll->pending_keys) {
        for (size_t i = 0; i < coll->pending_count; i++) {
            free(coll->pending_keys[i]);
        }
        free(coll->pending_keys);
    }

    free(coll);
}

/**
 * @brief 创建裁决虚拟集合
 */
static verdict_virtual_collection_t* create_verdict_collection(void) {
    verdict_virtual_collection_t *coll = calloc(1, sizeof(verdict_virtual_collection_t));
    if (!coll) return NULL;

    coll->table = hash_table_create(8191); // 约 8K 桶
    if (!coll->table) {
        free(coll);
        return NULL;
    }

    coll->capacity = VIRTUAL_VERDICT_COLLECTION_MAX_SIZE;
    coll->count = 0;

    coll->pending_capacity = 2000;
    coll->pending_keys = calloc(coll->pending_capacity, sizeof(char*));
    if (!coll->pending_keys) {
        hash_table_destroy(coll->table, false);
        free(coll);
        return NULL;
    }
    coll->pending_count = 0;

    return coll;
}

static void destroy_verdict_collection(verdict_virtual_collection_t *coll) {
    if (!coll) return;
    if (coll->table) hash_table_destroy(coll->table, true);
    if (coll->pending_keys) {
        for (size_t i = 0; i < coll->pending_count; i++) free(coll->pending_keys[i]);
        free(coll->pending_keys);
    }
    free(coll);
}

static tunnel_signal_virtual_collection_t* create_tunnel_signal_collection(void) {
    tunnel_signal_virtual_collection_t *coll = calloc(1, sizeof(tunnel_signal_virtual_collection_t));
    if (!coll) return NULL;

    coll->table = hash_table_create(8191);
    if (!coll->table) {
        free(coll);
        return NULL;
    }

    coll->capacity = VIRTUAL_TUNNEL_SIGNAL_COLLECTION_MAX_SIZE;
    coll->count = 0;
    coll->pending_capacity = 2000;
    coll->pending_keys = calloc(coll->pending_capacity, sizeof(char *));
    if (!coll->pending_keys) {
        hash_table_destroy(coll->table, false);
        free(coll);
        return NULL;
    }

    return coll;
}

static void destroy_tunnel_signal_collection(tunnel_signal_virtual_collection_t *coll) {
    if (!coll) return;
    if (coll->table) hash_table_destroy(coll->table, true);
    if (coll->pending_keys) {
        for (size_t i = 0; i < coll->pending_count; i++) free(coll->pending_keys[i]);
        free(coll->pending_keys);
    }
    free(coll);
}

static auxiliary_signal_virtual_collection_t* create_auxiliary_signal_collection(void) {
    auxiliary_signal_virtual_collection_t *coll = calloc(1, sizeof(auxiliary_signal_virtual_collection_t));
    if (!coll) return NULL;

    coll->table = hash_table_create(8191);
    if (!coll->table) {
        free(coll);
        return NULL;
    }

    coll->capacity = VIRTUAL_AUXILIARY_SIGNAL_COLLECTION_MAX_SIZE;
    coll->count = 0;
    coll->pending_capacity = 2000;
    coll->pending_keys = calloc(coll->pending_capacity, sizeof(char *));
    if (!coll->pending_keys) {
        hash_table_destroy(coll->table, false);
        free(coll);
        return NULL;
    }

    return coll;
}

static void destroy_auxiliary_signal_collection(auxiliary_signal_virtual_collection_t *coll) {
    if (!coll) return;
    if (coll->table) hash_table_destroy(coll->table, true);
    if (coll->pending_keys) {
        for (size_t i = 0; i < coll->pending_count; i++) free(coll->pending_keys[i]);
        free(coll->pending_keys);
    }
    free(coll);
}

/**
 * @brief 创建学生画像虚拟集合
 */
static student_virtual_collection_t* create_student_collection(void) {
    student_virtual_collection_t *coll = calloc(1, sizeof(student_virtual_collection_t));
    if (!coll) return NULL;

    coll->table = hash_table_create(4099); // 约 4K 桶
    if (!coll->table) {
        free(coll);
        return NULL;
    }

    coll->capacity = VIRTUAL_STUDENT_COLLECTION_MAX_SIZE;
    coll->count = 0;

    coll->pending_capacity = 1000;
    coll->pending_keys = calloc(coll->pending_capacity, sizeof(char*));
    if (!coll->pending_keys) {
        hash_table_destroy(coll->table, false);
        free(coll);
        return NULL;
    }
    coll->pending_count = 0;

    return coll;
}

static void destroy_student_collection(student_virtual_collection_t *coll) {
    if (!coll) return;
    if (coll->table) hash_table_destroy(coll->table, true);
    if (coll->pending_keys) {
        for (size_t i = 0; i < coll->pending_count; i++) free(coll->pending_keys[i]);
        free(coll->pending_keys);
    }
    free(coll);
}

/* ========== 虚拟集合创建与销毁 ========== */

virtual_collections_t* virtual_collections_create(void) {
    virtual_collections_t *vc = calloc(1, sizeof(virtual_collections_t));
    if (!vc) {
        return NULL;
    }

    // 创建 IP 集合
    vc->ip_collection = create_ip_collection();
    if (!vc->ip_collection) {
        free(vc);
        return NULL;
    }

    // 创建会话集合
    vc->session_collection = create_session_collection();
    if (!vc->session_collection) {
        destroy_ip_collection(vc->ip_collection);
        free(vc);
        return NULL;
    }

    // 创建协议集合
    vc->protocol_collection = create_protocol_collection();
    if (!vc->protocol_collection) {
        destroy_ip_collection(vc->ip_collection);
        destroy_session_collection(vc->session_collection);
        free(vc);
        return NULL;
    }

    // 创建裁决集合
    vc->verdict_collection = create_verdict_collection();
    if (!vc->verdict_collection) {
        destroy_ip_collection(vc->ip_collection);
        destroy_session_collection(vc->session_collection);
        destroy_protocol_collection(vc->protocol_collection);
        free(vc);
        return NULL;
    }

    vc->tunnel_signal_collection = create_tunnel_signal_collection();
    if (!vc->tunnel_signal_collection) {
        destroy_ip_collection(vc->ip_collection);
        destroy_session_collection(vc->session_collection);
        destroy_protocol_collection(vc->protocol_collection);
        destroy_verdict_collection(vc->verdict_collection);
        free(vc);
        return NULL;
    }

    vc->auxiliary_signal_collection = create_auxiliary_signal_collection();
    if (!vc->auxiliary_signal_collection) {
        destroy_ip_collection(vc->ip_collection);
        destroy_session_collection(vc->session_collection);
        destroy_protocol_collection(vc->protocol_collection);
        destroy_verdict_collection(vc->verdict_collection);
        destroy_tunnel_signal_collection(vc->tunnel_signal_collection);
        free(vc);
        return NULL;
    }

    // 创建学生画像集合
    vc->student_collection = create_student_collection();
    if (!vc->student_collection) {
        destroy_ip_collection(vc->ip_collection);
        destroy_session_collection(vc->session_collection);
        destroy_protocol_collection(vc->protocol_collection);
        destroy_verdict_collection(vc->verdict_collection);
        destroy_tunnel_signal_collection(vc->tunnel_signal_collection);
        destroy_auxiliary_signal_collection(vc->auxiliary_signal_collection);
        free(vc);
        return NULL;
    }

    // 初始化全局锁
    if (pthread_mutex_init(&vc->global_lock, NULL) != 0) {
        destroy_ip_collection(vc->ip_collection);
        destroy_session_collection(vc->session_collection);
        destroy_protocol_collection(vc->protocol_collection);
        destroy_verdict_collection(vc->verdict_collection);
        destroy_tunnel_signal_collection(vc->tunnel_signal_collection);
        destroy_auxiliary_signal_collection(vc->auxiliary_signal_collection);
        destroy_student_collection(vc->student_collection);
        free(vc);
        return NULL;
    }

    // 初始化统计信息
    vc->total_updates = 0;
    vc->total_inserts = 0;
    vc->total_merges = 0;
    vc->total_evictions = 0;

    return vc;
}

void virtual_collections_destroy(virtual_collections_t *vc) {
    if (!vc) {
        return;
    }

    destroy_ip_collection(vc->ip_collection);
    destroy_session_collection(vc->session_collection);
    destroy_protocol_collection(vc->protocol_collection);
    destroy_verdict_collection(vc->verdict_collection);
    destroy_tunnel_signal_collection(vc->tunnel_signal_collection);
    destroy_auxiliary_signal_collection(vc->auxiliary_signal_collection);
    destroy_student_collection(vc->student_collection);

    pthread_mutex_destroy(&vc->global_lock);

    free(vc);
}

/* ========== IP 流量虚拟集合 ========== */

int virtual_collection_update_ip(virtual_collections_t *vc, const ip_flow_doc_t *doc) {
    if (!vc || !doc || !doc->ip[0]) {
        return -1;
    }

    pthread_mutex_lock(&vc->global_lock);

    // 查找是否已存在
    ip_flow_doc_t *existing = hash_table_find(vc->ip_collection->table, doc->ip);

    if (existing) {
        // 合并数据
        merge_ip_doc(existing, doc);
        existing->new_data = true;
        vc->total_updates++;
        vc->total_merges++;
        pthread_mutex_unlock(&vc->global_lock);

    } else {
        // 插入新记录
        ip_flow_doc_t *new_doc = malloc(sizeof(ip_flow_doc_t));
        if (!new_doc) {
            pthread_mutex_unlock(&vc->global_lock);
            return -1;
        }

        memcpy(new_doc, doc, sizeof(ip_flow_doc_t));
        new_doc->new_data = true;
        new_doc->in_db = false;

        if (hash_table_insert(vc->ip_collection->table, doc->ip, new_doc) != 0) {
            free(new_doc);
            pthread_mutex_unlock(&vc->global_lock);
            return -1;
        }

        vc->ip_collection->count++;
        vc->total_updates++;
        vc->total_inserts++;
        pthread_mutex_unlock(&vc->global_lock);
    }

    return 0;
}

ip_flow_doc_t* virtual_collection_find_ip(virtual_collections_t *vc, const char *ip) {
    if (!vc || !ip) {
        return NULL;
    }

    return (ip_flow_doc_t*)hash_table_find(vc->ip_collection->table, ip);
}

int virtual_collection_remove_ip(virtual_collections_t *vc, const char *ip) {
    if (!vc || !ip) {
        return -1;
    }

    int ret = hash_table_remove(vc->ip_collection->table, ip, true);
    if (ret == 0) {
        vc->ip_collection->count--;
    }

    return ret;
}

/* ========== 会话虚拟集合 ========== */

int virtual_collection_update_session(virtual_collections_t *vc, const session_doc_t *doc) {
    if (!vc || !doc || !doc->session_id[0]) {
        return -1;
    }

    pthread_mutex_lock(&vc->global_lock);

    session_doc_t *existing = hash_table_find(vc->session_collection->table, doc->session_id);

    if (existing) {
        // 合并数据
        merge_session_doc(existing, doc);
        existing->new_data = true;
        vc->total_updates++;
        vc->total_merges++;
        pthread_mutex_unlock(&vc->global_lock);

    } else {
        // 插入新记录
        session_doc_t *new_doc = malloc(sizeof(session_doc_t));
        if (!new_doc) {
            pthread_mutex_unlock(&vc->global_lock);
            return -1;
        }

        memcpy(new_doc, doc, sizeof(session_doc_t));
        new_doc->new_data = true;
        new_doc->in_db = false;

        if (hash_table_insert(vc->session_collection->table, doc->session_id, new_doc) != 0) {
            free(new_doc);
            pthread_mutex_unlock(&vc->global_lock);
            return -1;
        }

        vc->session_collection->count++;
        vc->total_updates++;
        vc->total_inserts++;
        pthread_mutex_unlock(&vc->global_lock);
    }

    return 0;
}

session_doc_t* virtual_collection_find_session(virtual_collections_t *vc, const char *session_id) {
    if (!vc || !session_id) {
        return NULL;
    }

    return (session_doc_t*)hash_table_find(vc->session_collection->table, session_id);
}

int virtual_collection_remove_session(virtual_collections_t *vc, const char *session_id) {
    if (!vc || !session_id) {
        return -1;
    }

    int ret = hash_table_remove(vc->session_collection->table, session_id, true);
    if (ret == 0) {
        vc->session_collection->count--;
    }

    return ret;
}

/* ========== 协议统计虚拟集合 ========== */

int virtual_collection_update_protocol(virtual_collections_t *vc, const protocol_doc_t *doc) {
    if (!vc || !doc || !doc->protocol_name[0]) {
        return -1;
    }

    pthread_mutex_lock(&vc->global_lock);

    protocol_doc_t *existing = hash_table_find(vc->protocol_collection->table, doc->protocol_name);

    if (existing) {
        // 合并数据
        merge_protocol_doc(existing, doc);
        existing->new_data = true;
        vc->total_updates++;
        vc->total_merges++;
        pthread_mutex_unlock(&vc->global_lock);

    } else {
        // 插入新记录
        protocol_doc_t *new_doc = malloc(sizeof(protocol_doc_t));
        if (!new_doc) {
            pthread_mutex_unlock(&vc->global_lock);
            return -1;
        }

        memcpy(new_doc, doc, sizeof(protocol_doc_t));
        new_doc->new_data = true;
        new_doc->in_db = false;

        if (hash_table_insert(vc->protocol_collection->table, doc->protocol_name, new_doc) != 0) {
            free(new_doc);
            pthread_mutex_unlock(&vc->global_lock);
            return -1;
        }

        vc->protocol_collection->count++;
        vc->total_updates++;
        vc->total_inserts++;
        pthread_mutex_unlock(&vc->global_lock);
    }

    return 0;
}

protocol_doc_t* virtual_collection_find_protocol(virtual_collections_t *vc, const char *protocol_name) {
    if (!vc || !protocol_name) {
        return NULL;
    }

    return (protocol_doc_t*)hash_table_find(vc->protocol_collection->table, protocol_name);
}

int virtual_collection_remove_protocol(virtual_collections_t *vc, const char *protocol_name) {
    if (!vc || !protocol_name) {
        return -1;
    }

    int ret = hash_table_remove(vc->protocol_collection->table, protocol_name, true);
    if (ret == 0) {
        vc->protocol_collection->count--;
    }

    return ret;
}

/* ========== 统计信息 ========== */

void virtual_collection_get_stats(virtual_collections_t *vc,
                                   size_t *ip_count,
                                   size_t *session_count,
                                   size_t *protocol_count) {
    if (!vc) {
        return;
    }

    if (ip_count) {
        *ip_count = hash_table_size(vc->ip_collection->table);
    }

    if (session_count) {
        *session_count = hash_table_size(vc->session_collection->table);
    }

    if (protocol_count) {
        *protocol_count = hash_table_size(vc->protocol_collection->table);
    }
}

void virtual_collection_print_stats(virtual_collections_t *vc) {
    if (!vc) {
        return;
    }

    size_t ip_count, session_count, protocol_count;
    virtual_collection_get_stats(vc, &ip_count, &session_count, &protocol_count);

    printf("\n[Virtual Collections Stats]\n");
    printf("  IP Collection: %zu / %zu (%.1f%%)\n",
           ip_count, vc->ip_collection->capacity,
           100.0 * ip_count / vc->ip_collection->capacity);
    printf("  Session Collection: %zu / %zu (%.1f%%)\n",
           session_count, vc->session_collection->capacity,
           100.0 * session_count / vc->session_collection->capacity);
    printf("  Protocol Collection: %zu / %zu (%.1f%%)\n",
           protocol_count, vc->protocol_collection->capacity,
           100.0 * protocol_count / vc->protocol_collection->capacity);

    pthread_mutex_lock(&vc->global_lock);
    printf("\n  Total Updates: %lu\n", vc->total_updates);
    printf("  Total Inserts: %lu\n", vc->total_inserts);
    printf("  Total Merges: %lu\n", vc->total_merges);
    printf("  Total Evictions: %lu\n", vc->total_evictions);
    pthread_mutex_unlock(&vc->global_lock);

    // 打印哈希表统计
    printf("\n");
    hash_table_print_stats(vc->ip_collection->table, "IP Collection");
    printf("\n");
    hash_table_print_stats(vc->session_collection->table, "Session Collection");
    printf("\n");
    hash_table_print_stats(vc->protocol_collection->table, "Protocol Collection");
}

void virtual_collection_get_metrics(virtual_collections_t *vc, storage_metrics_t *metrics) {
    if (!vc || !metrics) {
        return;
    }

    memset(metrics, 0, sizeof(storage_metrics_t));

    virtual_collection_get_stats(vc,
                                  &metrics->ip_collection_size,
                                  &metrics->session_collection_size,
                                  &metrics->protocol_collection_size);

    pthread_mutex_lock(&vc->global_lock);
    metrics->total_updates = vc->total_updates;
    metrics->total_inserts = vc->total_inserts;
    metrics->total_merges = vc->total_merges;
    metrics->total_evictions = vc->total_evictions;
    pthread_mutex_unlock(&vc->global_lock);

    metrics->last_update_time = time(NULL);
}

/* ========== 批量操作 ========== */

/**
 * @brief 回调函数：收集待写入的键
 */
typedef struct {
    char **keys;
    size_t count;
    size_t capacity;
} collect_keys_ctx_t;

static bool collect_pending_keys_callback(const char *key, void *value, void *user_data) {
    collect_keys_ctx_t *ctx = (collect_keys_ctx_t *)user_data;

    // 检查是否有新数据（根据类型判断）
    bool has_new_data = false;

    // 尝试转换为不同类型（简单判断）
    ip_flow_doc_t *ip_doc = (ip_flow_doc_t *)value;
    if (ip_doc && ip_doc->new_data) {
        has_new_data = true;
    }

    if (!has_new_data) {
        return true; // 继续遍历
    }

    // 添加到列表
    if (ctx->count < ctx->capacity) {
        ctx->keys[ctx->count] = strdup(key);
        if (ctx->keys[ctx->count]) {
            ctx->count++;
        }
    }

    return true; // 继续遍历
}

/* 会话集合专用回调 */
static bool collect_pending_session_keys_callback(const char *key, void *value, void *user_data) {
    collect_keys_ctx_t *ctx = (collect_keys_ctx_t *)user_data;

    session_doc_t *session_doc = (session_doc_t *)value;
    if (session_doc && session_doc->new_data) {
        if (ctx->count < ctx->capacity) {
            ctx->keys[ctx->count] = strdup(key);
            if (ctx->keys[ctx->count]) {
                ctx->count++;
            }
        }
    }

    return true;
}

/* 协议集合专用回调 */
static bool collect_pending_protocol_keys_callback(const char *key, void *value, void *user_data) {
    collect_keys_ctx_t *ctx = (collect_keys_ctx_t *)user_data;

    protocol_doc_t *protocol_doc = (protocol_doc_t *)value;
    if (protocol_doc && protocol_doc->new_data) {
        if (ctx->count < ctx->capacity) {
            ctx->keys[ctx->count] = strdup(key);
            if (ctx->keys[ctx->count]) {
                ctx->count++;
            }
        }
    }

    return true;
}


int virtual_collection_get_pending_ips(virtual_collections_t *vc, char ***keys, size_t *count) {
    if (!vc || !keys || !count) {
        return -1;
    }

    // 分配临时缓冲区
    size_t capacity = hash_table_size(vc->ip_collection->table);
    if (capacity == 0) {
        *keys = NULL;
        *count = 0;
        return 0;
    }

    char **temp_keys = malloc(sizeof(char*) * capacity);
    if (!temp_keys) {
        return -1;
    }

    collect_keys_ctx_t ctx = {
        .keys = temp_keys,
        .count = 0,
        .capacity = capacity
    };

    // 遍历哈希表，收集 new_data=true 的键
    hash_table_foreach(vc->ip_collection->table, collect_pending_keys_callback, &ctx);

    *keys = temp_keys;
    *count = ctx.count;

    return 0;
}

int virtual_collection_get_pending_sessions(virtual_collections_t *vc, char ***keys, size_t *count) {
    if (!vc || !keys || !count) {
        return -1;
    }

    size_t capacity = hash_table_size(vc->session_collection->table);
    if (capacity == 0) {
        *keys = NULL;
        *count = 0;
        return 0;
    }

    char **temp_keys = malloc(sizeof(char*) * capacity);
    if (!temp_keys) {
        return -1;
    }

    collect_keys_ctx_t ctx = {
        .keys = temp_keys,
        .count = 0,
        .capacity = capacity
    };

    hash_table_foreach(vc->session_collection->table, collect_pending_session_keys_callback, &ctx);

    *keys = temp_keys;
    *count = ctx.count;

    return 0;
}

int virtual_collection_get_pending_protocols(virtual_collections_t *vc, char ***keys, size_t *count) {
    if (!vc || !keys || !count) {
        return -1;
    }

    size_t capacity = hash_table_size(vc->protocol_collection->table);
    if (capacity == 0) {
        *keys = NULL;
        *count = 0;
        return 0;
    }

    char **temp_keys = malloc(sizeof(char*) * capacity);
    if (!temp_keys) {
        return -1;
    }

    collect_keys_ctx_t ctx = {
        .keys = temp_keys,
        .count = 0,
        .capacity = capacity
    };

    hash_table_foreach(vc->protocol_collection->table, collect_pending_protocol_keys_callback, &ctx);

    *keys = temp_keys;
    *count = ctx.count;

    return 0;
}

void virtual_collection_mark_ip_synced(virtual_collections_t *vc, const char *ip) {
    if (!vc || !ip) {
        return;
    }

    ip_flow_doc_t *doc = hash_table_find(vc->ip_collection->table, ip);
    if (doc) {
        doc->new_data = false;
        doc->in_db = true;
    }
}

void virtual_collection_mark_session_synced(virtual_collections_t *vc, const char *session_id) {
    if (!vc || !session_id) {
        return;
    }

    session_doc_t *doc = hash_table_find(vc->session_collection->table, session_id);
    if (doc) {
        doc->new_data = false;
        doc->in_db = true;
    }
}

void virtual_collection_mark_protocol_synced(virtual_collections_t *vc, const char *protocol_name) {
    if (!vc || !protocol_name) {
        return;
    }

    protocol_doc_t *doc = hash_table_find(vc->protocol_collection->table, protocol_name);
    if (doc) {
        doc->new_data = false;
        doc->in_db = true;
    }
}

/* ========== 裁决虚拟集合 ========== */

/* 裁决集合专用回调 */
static bool collect_pending_verdict_keys_callback(const char *key, void *value, void *user_data) {
    collect_keys_ctx_t *ctx = (collect_keys_ctx_t *)user_data;
    verdict_doc_t *doc = (verdict_doc_t *)value;
    if (doc && doc->new_data) {
        if (ctx->count < ctx->capacity) {
            ctx->keys[ctx->count] = strdup(key);
            if (ctx->keys[ctx->count]) ctx->count++;
        }
    }
    return true;
}

static bool collect_pending_tunnel_signal_keys_callback(const char *key, void *value, void *user_data) {
    collect_keys_ctx_t *ctx = (collect_keys_ctx_t *)user_data;
    tunnel_signal_doc_t *doc = (tunnel_signal_doc_t *)value;
    if (doc && doc->new_data) {
        if (ctx->count < ctx->capacity) {
            ctx->keys[ctx->count] = strdup(key);
            if (ctx->keys[ctx->count]) ctx->count++;
        }
    }
    return true;
}

static bool collect_pending_auxiliary_signal_keys_callback(const char *key, void *value, void *user_data) {
    collect_keys_ctx_t *ctx = (collect_keys_ctx_t *)user_data;
    auxiliary_signal_doc_t *doc = (auxiliary_signal_doc_t *)value;
    if (doc && doc->new_data) {
        if (ctx->count < ctx->capacity) {
            ctx->keys[ctx->count] = strdup(key);
            if (ctx->keys[ctx->count]) ctx->count++;
        }
    }
    return true;
}

static void build_append_entry_key(const char *session_id,
                                   long long event_time,
                                   unsigned long long seq,
                                   char *out, size_t out_size)
{
    if (!out || out_size == 0) return;
    snprintf(out, out_size, "%s#%lld#%llu",
             session_id ? session_id : "",
             event_time, seq);
}

typedef struct verdict_find_ctx {
    const char *session_id;
    verdict_doc_t *best;
    time_t best_time;
} verdict_find_ctx_t;

static bool find_latest_verdict_by_session_callback(const char *key, void *value, void *user_data) {
    (void)key;
    verdict_find_ctx_t *ctx = (verdict_find_ctx_t *)user_data;
    verdict_doc_t *doc = (verdict_doc_t *)value;
    if (!ctx || !doc) return true;
    if (strcmp(doc->session_id, ctx->session_id) != 0) return true;
    if (!ctx->best || doc->verdict_time >= ctx->best_time) {
        ctx->best = doc;
        ctx->best_time = doc->verdict_time;
    }
    return true;
}

typedef struct tunnel_find_ctx {
    const char *session_id;
    tunnel_signal_doc_t *best;
    time_t best_time;
} tunnel_find_ctx_t;

static bool find_latest_tunnel_by_session_callback(const char *key, void *value, void *user_data) {
    (void)key;
    tunnel_find_ctx_t *ctx = (tunnel_find_ctx_t *)user_data;
    tunnel_signal_doc_t *doc = (tunnel_signal_doc_t *)value;
    if (!ctx || !doc) return true;
    if (strcmp(doc->session_id, ctx->session_id) != 0) return true;
    if (!ctx->best || doc->session_end >= ctx->best_time) {
        ctx->best = doc;
        ctx->best_time = doc->session_end;
    }
    return true;
}

typedef struct auxiliary_find_ctx {
    const char *session_id;
    auxiliary_signal_doc_t *best;
    time_t best_time;
} auxiliary_find_ctx_t;

static bool find_latest_auxiliary_by_session_callback(const char *key, void *value, void *user_data) {
    (void)key;
    auxiliary_find_ctx_t *ctx = (auxiliary_find_ctx_t *)user_data;
    auxiliary_signal_doc_t *doc = (auxiliary_signal_doc_t *)value;
    if (!ctx || !doc) return true;
    if (strcmp(doc->session_id, ctx->session_id) != 0) return true;
    if (!ctx->best || doc->session_end >= ctx->best_time) {
        ctx->best = doc;
        ctx->best_time = doc->session_end;
    }
    return true;
}

int virtual_collection_update_verdict(virtual_collections_t *vc, const verdict_doc_t *doc) {
    char entry_key[192];
    verdict_doc_t *new_doc;

    if (!vc || !doc || !doc->session_id[0]) return -1;

    pthread_mutex_lock(&vc->global_lock);

    vc->verdict_collection->append_seq++;
    build_append_entry_key(doc->session_id,
                           (long long)doc->verdict_time,
                           (unsigned long long)vc->verdict_collection->append_seq,
                           entry_key, sizeof(entry_key));

    new_doc = malloc(sizeof(*new_doc));
    if (!new_doc) {
        pthread_mutex_unlock(&vc->global_lock);
        return -1;
    }
    memcpy(new_doc, doc, sizeof(*new_doc));
    new_doc->new_data = true;
    new_doc->in_db = false;

    if (hash_table_insert(vc->verdict_collection->table, entry_key, new_doc) != 0) {
        free(new_doc);
        pthread_mutex_unlock(&vc->global_lock);
        return -1;
    }
    vc->verdict_collection->count++;
    vc->total_updates++;
    vc->total_inserts++;

    pthread_mutex_unlock(&vc->global_lock);
    return 0;
}

verdict_doc_t* virtual_collection_find_verdict(virtual_collections_t *vc, const char *session_id) {
    verdict_doc_t *exact;
    verdict_find_ctx_t ctx;

    if (!vc || !session_id) return NULL;
    exact = (verdict_doc_t*)hash_table_find(vc->verdict_collection->table, session_id);
    if (exact) return exact;

    ctx.session_id = session_id;
    ctx.best = NULL;
    ctx.best_time = 0;
    hash_table_foreach(vc->verdict_collection->table, find_latest_verdict_by_session_callback, &ctx);
    return ctx.best;
}

int virtual_collection_get_pending_verdicts(virtual_collections_t *vc, char ***keys, size_t *count) {
    if (!vc || !keys || !count) return -1;

    size_t capacity = hash_table_size(vc->verdict_collection->table);
    if (capacity == 0) { *keys = NULL; *count = 0; return 0; }

    char **temp_keys = malloc(sizeof(char*) * capacity);
    if (!temp_keys) return -1;

    collect_keys_ctx_t ctx = { .keys = temp_keys, .count = 0, .capacity = capacity };
    hash_table_foreach(vc->verdict_collection->table, collect_pending_verdict_keys_callback, &ctx);

    *keys = temp_keys;
    *count = ctx.count;
    return 0;
}

void virtual_collection_mark_verdict_synced(virtual_collections_t *vc, const char *session_id) {
    if (!vc || !session_id) return;
    /* verdict 是按事件追加的日志集合(每条唯一 key),落库成功后无需在内存保留。
     * 直接从哈希表移除并释放,使集合内存恒定,避免无限增长。 */
    if (hash_table_remove(vc->verdict_collection->table, session_id, true) == 0
        && vc->verdict_collection->count > 0) {
        vc->verdict_collection->count--;
    }
}

int virtual_collection_update_tunnel_signal(virtual_collections_t *vc, const tunnel_signal_doc_t *doc) {
    char entry_key[192];
    tunnel_signal_doc_t *new_doc;

    if (!vc || !doc || !doc->session_id[0]) return -1;

    pthread_mutex_lock(&vc->global_lock);
    vc->tunnel_signal_collection->append_seq++;
    build_append_entry_key(doc->session_id,
                           (long long)doc->session_end,
                           (unsigned long long)vc->tunnel_signal_collection->append_seq,
                           entry_key, sizeof(entry_key));

    new_doc = malloc(sizeof(*new_doc));
    if (!new_doc) {
        pthread_mutex_unlock(&vc->global_lock);
        return -1;
    }
    memcpy(new_doc, doc, sizeof(*new_doc));
    new_doc->new_data = true;
    new_doc->in_db = false;
    if (hash_table_insert(vc->tunnel_signal_collection->table, entry_key, new_doc) != 0) {
        free(new_doc);
        pthread_mutex_unlock(&vc->global_lock);
        return -1;
    }
    vc->tunnel_signal_collection->count++;
    vc->total_updates++;
    vc->total_inserts++;
    pthread_mutex_unlock(&vc->global_lock);
    return 0;
}

tunnel_signal_doc_t* virtual_collection_find_tunnel_signal(virtual_collections_t *vc, const char *session_id) {
    tunnel_signal_doc_t *exact;
    tunnel_find_ctx_t ctx;

    if (!vc || !session_id) return NULL;
    exact = (tunnel_signal_doc_t *)hash_table_find(vc->tunnel_signal_collection->table, session_id);
    if (exact) return exact;

    ctx.session_id = session_id;
    ctx.best = NULL;
    ctx.best_time = 0;
    hash_table_foreach(vc->tunnel_signal_collection->table, find_latest_tunnel_by_session_callback, &ctx);
    return ctx.best;
}

int virtual_collection_get_pending_tunnel_signals(virtual_collections_t *vc, char ***keys, size_t *count) {
    size_t capacity;
    char **temp_keys;
    collect_keys_ctx_t ctx;

    if (!vc || !keys || !count) return -1;
    capacity = hash_table_size(vc->tunnel_signal_collection->table);
    if (capacity == 0) {
        *keys = NULL;
        *count = 0;
        return 0;
    }

    temp_keys = malloc(sizeof(char *) * capacity);
    if (!temp_keys) return -1;

    ctx.keys = temp_keys;
    ctx.count = 0;
    ctx.capacity = capacity;
    hash_table_foreach(vc->tunnel_signal_collection->table,
                       collect_pending_tunnel_signal_keys_callback, &ctx);
    *keys = temp_keys;
    *count = ctx.count;
    return 0;
}

void virtual_collection_mark_tunnel_signal_synced(virtual_collections_t *vc, const char *session_id) {
    if (!vc || !session_id) return;
    /* 隧道信号同为事件追加日志,落库后从内存移除以恒定内存。 */
    if (hash_table_remove(vc->tunnel_signal_collection->table, session_id, true) == 0
        && vc->tunnel_signal_collection->count > 0) {
        vc->tunnel_signal_collection->count--;
    }
}

int virtual_collection_update_auxiliary_signal(virtual_collections_t *vc, const auxiliary_signal_doc_t *doc) {
    char entry_key[192];
    auxiliary_signal_doc_t *new_doc;

    if (!vc || !doc || !doc->session_id[0]) return -1;

    pthread_mutex_lock(&vc->global_lock);
    vc->auxiliary_signal_collection->append_seq++;
    build_append_entry_key(doc->session_id,
                           (long long)doc->session_end,
                           (unsigned long long)vc->auxiliary_signal_collection->append_seq,
                           entry_key, sizeof(entry_key));

    new_doc = malloc(sizeof(*new_doc));
    if (!new_doc) {
        pthread_mutex_unlock(&vc->global_lock);
        return -1;
    }
    memcpy(new_doc, doc, sizeof(*new_doc));
    new_doc->new_data = true;
    new_doc->in_db = false;
    if (hash_table_insert(vc->auxiliary_signal_collection->table, entry_key, new_doc) != 0) {
        free(new_doc);
        pthread_mutex_unlock(&vc->global_lock);
        return -1;
    }
    vc->auxiliary_signal_collection->count++;
    vc->total_updates++;
    vc->total_inserts++;
    pthread_mutex_unlock(&vc->global_lock);
    return 0;
}

auxiliary_signal_doc_t* virtual_collection_find_auxiliary_signal(virtual_collections_t *vc, const char *session_id) {
    auxiliary_signal_doc_t *exact;
    auxiliary_find_ctx_t ctx;

    if (!vc || !session_id) return NULL;
    exact = (auxiliary_signal_doc_t *)hash_table_find(vc->auxiliary_signal_collection->table, session_id);
    if (exact) return exact;

    ctx.session_id = session_id;
    ctx.best = NULL;
    ctx.best_time = 0;
    hash_table_foreach(vc->auxiliary_signal_collection->table, find_latest_auxiliary_by_session_callback, &ctx);
    return ctx.best;
}

int virtual_collection_get_pending_auxiliary_signals(virtual_collections_t *vc, char ***keys, size_t *count) {
    size_t capacity;
    char **temp_keys;
    collect_keys_ctx_t ctx;

    if (!vc || !keys || !count) return -1;
    capacity = hash_table_size(vc->auxiliary_signal_collection->table);
    if (capacity == 0) {
        *keys = NULL;
        *count = 0;
        return 0;
    }

    temp_keys = malloc(sizeof(char *) * capacity);
    if (!temp_keys) return -1;

    ctx.keys = temp_keys;
    ctx.count = 0;
    ctx.capacity = capacity;
    hash_table_foreach(vc->auxiliary_signal_collection->table,
                       collect_pending_auxiliary_signal_keys_callback, &ctx);
    *keys = temp_keys;
    *count = ctx.count;
    return 0;
}

void virtual_collection_mark_auxiliary_signal_synced(virtual_collections_t *vc, const char *session_id) {
    if (!vc || !session_id) return;
    /* 可疑(辅助)信号同为事件追加日志,落库后从内存移除以恒定内存。 */
    if (hash_table_remove(vc->auxiliary_signal_collection->table, session_id, true) == 0
        && vc->auxiliary_signal_collection->count > 0) {
        vc->auxiliary_signal_collection->count--;
    }
}

/* ========== IP画像虚拟集合 ========== */

static void merge_profile_hourly(student_profile_doc_t *dst,
                                 const student_profile_doc_t *src)
{
    /* 小时桶按 hour 合并，避免同一 IP 当天多条会话把 24 小时数组打散成重复项。 */
    for (int i = 0; i < src->hourly_count && i < 24; i++) {
        int found = -1;
        for (int j = 0; j < dst->hourly_count; j++) {
            if (dst->hourly[j].hour == src->hourly[i].hour) {
                found = j;
                break;
            }
        }
        if (found < 0 && dst->hourly_count < 24) {
            found = dst->hourly_count++;
            dst->hourly[found].hour = src->hourly[i].hour;
        }
        if (found < 0) continue;

        dst->hourly[found].session_count += src->hourly[i].session_count;
        dst->hourly[found].bytes += src->hourly[i].bytes;
        dst->hourly[found].duration_minutes += src->hourly[i].duration_minutes;
    }
}

static void merge_profile_os_types(student_profile_doc_t *dst,
                                   const student_profile_doc_t *src)
{
    /* OS 类型只做集合并集，不做次数累计，便于后面直接判断多设备。 */
    for (int i = 0; i < src->os_count && i < MAX_OS_TYPES; i++) {
        bool exists = false;
        for (int j = 0; j < dst->os_count; j++) {
            if (strcmp(dst->os_types[j], src->os_types[i]) == 0) {
                exists = true;
                break;
            }
        }
        if (!exists && dst->os_count < MAX_OS_TYPES) {
            snprintf(dst->os_types[dst->os_count],
                     sizeof(dst->os_types[dst->os_count]),
                     "%s", src->os_types[i]);
            dst->os_count++;
        }
    }
}

static void refresh_profile_flags(student_profile_doc_t *doc)
{
    uint8_t night_hours = 0;

    /*
     * 熬夜标签不能简单累加单条会话的布尔值，
     * 必须在聚合后的小时桶上重新统计 00:00-06:00 里实际活跃了几个小时。
     */
    for (int i = 0; i < doc->hourly_count; i++) {
        if (doc->hourly[i].hour >= NIGHT_START_HOUR &&
            doc->hourly[i].hour < NIGHT_END_HOUR &&
            doc->hourly[i].session_count > 0) {
            night_hours++;
        }
    }

    doc->night_active_hours = night_hours;
    doc->is_night_owl = night_hours >= NIGHT_OWL_THRESHOLD_HOURS;
    doc->multi_device_count = (uint8_t)doc->os_count;
    doc->is_multi_device = doc->os_count > MULTI_DEVICE_THRESHOLD;
}

/* IP画像集合专用回调 */
static bool collect_pending_student_keys_callback(const char *key, void *value, void *user_data) {
    collect_keys_ctx_t *ctx = (collect_keys_ctx_t *)user_data;
    student_profile_doc_t *doc = (student_profile_doc_t *)value;
    if (doc && doc->new_data) {
        if (ctx->count < ctx->capacity) {
            ctx->keys[ctx->count] = strdup(key);
            if (ctx->keys[ctx->count]) ctx->count++;
        }
    }
    return true;
}

int virtual_collection_update_student(virtual_collections_t *vc, const student_profile_doc_t *doc) {
    if (!vc || !doc || !doc->profile_key[0]) return -1;

    pthread_mutex_lock(&vc->global_lock);

    student_profile_doc_t *existing = hash_table_find(vc->student_collection->table,
                                                      doc->profile_key);
    if (existing) {
        /* 同一 profile_key 代表同一个“IP + 日期”实体，这里做日报级合并。 */
        for (int i = 0; i < MAX_CATEGORY_TYPES; i++) {
            existing->category_duration[i] += doc->category_duration[i];
            existing->category_bytes[i]    += doc->category_bytes[i];
            existing->category_sessions[i] += doc->category_sessions[i];
        }
        existing->total_duration_minutes += doc->total_duration_minutes;
        existing->total_bytes            += doc->total_bytes;
        existing->total_sessions         += doc->total_sessions;
        existing->night_session_count    += doc->night_session_count;
        existing->night_bytes            += doc->night_bytes;
        existing->crossing_count         += doc->crossing_count;
        if (doc->max_confidence_level > existing->max_confidence_level) {
            existing->max_confidence_level = doc->max_confidence_level;
        }
        merge_profile_hourly(existing, doc);
        merge_profile_os_types(existing, doc);
        refresh_profile_flags(existing);
        existing->new_data = true;
        vc->total_updates++;
        vc->total_merges++;
    } else {
        /* 新画像直接插入，标记为内存中已有新数据。 */
        student_profile_doc_t *new_doc = malloc(sizeof(student_profile_doc_t));
        if (!new_doc) {
            pthread_mutex_unlock(&vc->global_lock);
            return -1;
        }
        memcpy(new_doc, doc, sizeof(student_profile_doc_t));
        new_doc->new_data = true;
        new_doc->in_db = false;
        refresh_profile_flags(new_doc);

        if (hash_table_insert(vc->student_collection->table,
                              doc->profile_key, new_doc) != 0) {
            free(new_doc);
            pthread_mutex_unlock(&vc->global_lock);
            return -1;
        }
        vc->student_collection->count++;
        vc->total_updates++;
        vc->total_inserts++;
    }

    pthread_mutex_unlock(&vc->global_lock);
    return 0;
}

student_profile_doc_t* virtual_collection_find_student(virtual_collections_t *vc, const char *profile_key) {
    if (!vc || !profile_key) return NULL;
    return (student_profile_doc_t*)hash_table_find(vc->student_collection->table, profile_key);
}

int virtual_collection_get_pending_students(virtual_collections_t *vc, char ***keys, size_t *count) {
    if (!vc || !keys || !count) return -1;

    size_t capacity = hash_table_size(vc->student_collection->table);
    if (capacity == 0) { *keys = NULL; *count = 0; return 0; }

    char **temp_keys = malloc(sizeof(char*) * capacity);
    if (!temp_keys) return -1;

    collect_keys_ctx_t ctx = { .keys = temp_keys, .count = 0, .capacity = capacity };
    hash_table_foreach(vc->student_collection->table, collect_pending_student_keys_callback, &ctx);

    *keys = temp_keys;
    *count = ctx.count;
    return 0;
}

void virtual_collection_mark_student_synced(virtual_collections_t *vc, const char *profile_key) {
    if (!vc || !profile_key) return;
    student_profile_doc_t *doc = hash_table_find(vc->student_collection->table, profile_key);
    if (doc) { doc->new_data = false; doc->in_db = true; }
}

/* 在 global_lock 保护下把指定 IP画像 拷贝出一份稳定快照(ip_profile_doc_t 为 flat 结构,
 * 可整体 memcpy),并在同一把锁内清除 new_data 标记。worker 线程对同一画像做原地聚合
 * 合并(existing->...+=)同样在 global_lock 下进行,故此处拷贝与之互斥,杜绝落库时读到
 * 半更新数据导致 libmongoc/libbson 崩溃。调用方在锁外用快照 upsert,不阻塞 worker。 */
int virtual_collection_copy_and_mark_student(virtual_collections_t *vc,
                                             const char *profile_key,
                                             student_profile_doc_t *out) {
    if (!vc || !profile_key || !out) return -1;
    pthread_mutex_lock(&vc->global_lock);
    student_profile_doc_t *doc = hash_table_find(vc->student_collection->table, profile_key);
    if (!doc) { pthread_mutex_unlock(&vc->global_lock); return -1; }
    memcpy(out, doc, sizeof(*out));
    doc->new_data = false;
    doc->in_db = true;
    pthread_mutex_unlock(&vc->global_lock);
    return 0;
}
