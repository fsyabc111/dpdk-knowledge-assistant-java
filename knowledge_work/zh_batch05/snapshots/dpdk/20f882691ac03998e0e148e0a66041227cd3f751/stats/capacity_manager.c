/**
 * @file capacity_manager.c
 * @brief 容量管理实现
 *
 * @version 2.0
 * @date 2026-02-06
 */

#include "capacity_manager.h"
#include <stdlib.h>
#include <string.h>
#include <stdio.h>

/* ========== 辅助函数 ========== */

/**
 * @brief 时间戳比较函数（用于 qsort）
 */
static int compare_time(const void *a, const void *b) {
    time_t ta = *(const time_t *)a;
    time_t tb = *(const time_t *)b;

    if (ta < tb) return -1;
    if (ta > tb) return 1;
    return 0;
}

/**
 * @brief 回调函数：收集所有 last_time
 */
typedef struct {
    time_t *timestamps;
    size_t count;
    size_t capacity;
} collect_timestamps_ctx_t;

static bool collect_ip_timestamps_callback(const char *key, void *value, void *user_data) {
    collect_timestamps_ctx_t *ctx = (collect_timestamps_ctx_t *)user_data;
    ip_flow_doc_t *doc = (ip_flow_doc_t *)value;

    if (ctx->count < ctx->capacity) {
        ctx->timestamps[ctx->count++] = doc->last_time;
    }

    return true;
}

static bool collect_session_timestamps_callback(const char *key, void *value, void *user_data) {
    collect_timestamps_ctx_t *ctx = (collect_timestamps_ctx_t *)user_data;
    session_doc_t *doc = (session_doc_t *)value;

    if (ctx->count < ctx->capacity) {
        ctx->timestamps[ctx->count++] = doc->last_time;
    }

    return true;
}

static bool collect_protocol_timestamps_callback(const char *key, void *value, void *user_data) {
    collect_timestamps_ctx_t *ctx = (collect_timestamps_ctx_t *)user_data;
    protocol_doc_t *doc = (protocol_doc_t *)value;

    if (ctx->count < ctx->capacity) {
        ctx->timestamps[ctx->count++] = doc->last_time;
    }

    return true;
}

/* ========== IP 集合整理 ========== */

int arrange_ip_collection(virtual_collections_t *vc) {
    if (!vc || !vc->ip_collection) {
        return -1;
    }

    size_t current_count = hash_table_size(vc->ip_collection->table);

    // 检查是否需要整理
    if (current_count <= vc->ip_collection->capacity) {
        return 0; // 无需整理
    }

    printf("[Capacity Manager] Arranging IP collection: %zu / %zu\n",
           current_count, vc->ip_collection->capacity);

    // 收集所有 last_time
    time_t *timestamps = malloc(sizeof(time_t) * current_count);
    if (!timestamps) {
        return -1;
    }

    collect_timestamps_ctx_t ctx = {
        .timestamps = timestamps,
        .count = 0,
        .capacity = current_count
    };

    hash_table_foreach(vc->ip_collection->table, collect_ip_timestamps_callback, &ctx);

    if (ctx.count == 0) {
        free(timestamps);
        return 0;
    }

    // 排序找到中位数
    qsort(timestamps, ctx.count, sizeof(time_t), compare_time);
    time_t threshold = timestamps[vc->ip_collection->capacity / 2];

    free(timestamps);

    // 获取所有键
    char **keys = NULL;
    size_t key_count = 0;

    if (hash_table_get_keys(vc->ip_collection->table, &keys, &key_count) != 0) {
        return -1;
    }

    // 删除 last_time < threshold 的记录
    size_t deleted = 0;

    for (size_t i = 0; i < key_count; i++) {
        ip_flow_doc_t *doc = hash_table_find(vc->ip_collection->table, keys[i]);
        if (doc && doc->last_time < threshold) {
            if (virtual_collection_remove_ip(vc, keys[i]) == 0) {
                deleted++;
            }
        }
    }

    // 释放键列表
    for (size_t i = 0; i < key_count; i++) {
        free(keys[i]);
    }
    free(keys);

    // 更新统计
    pthread_mutex_lock(&vc->global_lock);
    vc->total_evictions += deleted;
    pthread_mutex_unlock(&vc->global_lock);

    printf("[Capacity Manager] Deleted %zu IP records, current size: %zu\n",
           deleted, hash_table_size(vc->ip_collection->table));

    return deleted;
}

/* ========== 会话集合整理 ========== */

int arrange_session_collection(virtual_collections_t *vc) {
    if (!vc || !vc->session_collection) {
        return -1;
    }

    size_t current_count = hash_table_size(vc->session_collection->table);

    if (current_count <= vc->session_collection->capacity) {
        return 0;
    }

    printf("[Capacity Manager] Arranging session collection: %zu / %zu\n",
           current_count, vc->session_collection->capacity);

    // 收集所有 last_time
    time_t *timestamps = malloc(sizeof(time_t) * current_count);
    if (!timestamps) {
        return -1;
    }

    collect_timestamps_ctx_t ctx = {
        .timestamps = timestamps,
        .count = 0,
        .capacity = current_count
    };

    hash_table_foreach(vc->session_collection->table, collect_session_timestamps_callback, &ctx);

    if (ctx.count == 0) {
        free(timestamps);
        return 0;
    }

    // 排序找到中位数
    qsort(timestamps, ctx.count, sizeof(time_t), compare_time);
    time_t threshold = timestamps[vc->session_collection->capacity / 2];

    free(timestamps);

    // 获取所有键
    char **keys = NULL;
    size_t key_count = 0;

    if (hash_table_get_keys(vc->session_collection->table, &keys, &key_count) != 0) {
        return -1;
    }

    // 删除旧记录
    size_t deleted = 0;

    for (size_t i = 0; i < key_count; i++) {
        session_doc_t *doc = hash_table_find(vc->session_collection->table, keys[i]);
        if (doc && doc->last_time < threshold) {
            if (virtual_collection_remove_session(vc, keys[i]) == 0) {
                deleted++;
            }
        }
    }

    // 释放键列表
    for (size_t i = 0; i < key_count; i++) {
        free(keys[i]);
    }
    free(keys);

    // 更新统计
    pthread_mutex_lock(&vc->global_lock);
    vc->total_evictions += deleted;
    pthread_mutex_unlock(&vc->global_lock);

    printf("[Capacity Manager] Deleted %zu session records, current size: %zu\n",
           deleted, hash_table_size(vc->session_collection->table));

    return deleted;
}

/* ========== 协议集合整理 ========== */

int arrange_protocol_collection(virtual_collections_t *vc) {
    if (!vc || !vc->protocol_collection) {
        return -1;
    }

    size_t current_count = hash_table_size(vc->protocol_collection->table);

    if (current_count <= vc->protocol_collection->capacity) {
        return 0;
    }

    printf("[Capacity Manager] Arranging protocol collection: %zu / %zu\n",
           current_count, vc->protocol_collection->capacity);

    // 协议数量通常很少，简单删除最旧的
    time_t *timestamps = malloc(sizeof(time_t) * current_count);
    if (!timestamps) {
        return -1;
    }

    collect_timestamps_ctx_t ctx = {
        .timestamps = timestamps,
        .count = 0,
        .capacity = current_count
    };

    hash_table_foreach(vc->protocol_collection->table, collect_protocol_timestamps_callback, &ctx);

    if (ctx.count == 0) {
        free(timestamps);
        return 0;
    }

    qsort(timestamps, ctx.count, sizeof(time_t), compare_time);
    time_t threshold = timestamps[vc->protocol_collection->capacity / 2];

    free(timestamps);

    char **keys = NULL;
    size_t key_count = 0;

    if (hash_table_get_keys(vc->protocol_collection->table, &keys, &key_count) != 0) {
        return -1;
    }

    size_t deleted = 0;

    for (size_t i = 0; i < key_count; i++) {
        protocol_doc_t *doc = hash_table_find(vc->protocol_collection->table, keys[i]);
        if (doc && doc->last_time < threshold) {
            if (virtual_collection_remove_protocol(vc, keys[i]) == 0) {
                deleted++;
            }
        }
    }

    for (size_t i = 0; i < key_count; i++) {
        free(keys[i]);
    }
    free(keys);

    pthread_mutex_lock(&vc->global_lock);
    vc->total_evictions += deleted;
    pthread_mutex_unlock(&vc->global_lock);

    printf("[Capacity Manager] Deleted %zu protocol records, current size: %zu\n",
           deleted, hash_table_size(vc->protocol_collection->table));

    return deleted;
}

/* ========== 整理所有集合 ========== */

int arrange_all_collections(virtual_collections_t *vc) {
    if (!vc) {
        return -1;
    }

    printf("\n[Capacity Manager] ===== Starting Capacity Management =====\n");

    int ip_deleted = arrange_ip_collection(vc);
    int session_deleted = arrange_session_collection(vc);
    int protocol_deleted = arrange_protocol_collection(vc);

    printf("[Capacity Manager] ===== Capacity Management Complete =====\n");
    printf("  IP Records Deleted: %d\n", ip_deleted);
    printf("  Session Records Deleted: %d\n", session_deleted);
    printf("  Protocol Records Deleted: %d\n\n", protocol_deleted);

    return 0;
}

/* ========== 容量检查 ========== */

void check_capacity_need_arrange(virtual_collections_t *vc,
                                  bool *ip_need,
                                  bool *session_need,
                                  bool *protocol_need) {
    if (!vc) {
        return;
    }

    if (ip_need) {
        size_t ip_count = hash_table_size(vc->ip_collection->table);
        *ip_need = (ip_count > vc->ip_collection->capacity);
    }

    if (session_need) {
        size_t session_count = hash_table_size(vc->session_collection->table);
        *session_need = (session_count > vc->session_collection->capacity);
    }

    if (protocol_need) {
        size_t protocol_count = hash_table_size(vc->protocol_collection->table);
        *protocol_need = (protocol_count > vc->protocol_collection->capacity);
    }
}

void get_capacity_usage(virtual_collections_t *vc,
                        double *ip_usage,
                        double *session_usage,
                        double *protocol_usage) {
    if (!vc) {
        return;
    }

    if (ip_usage) {
        size_t ip_count = hash_table_size(vc->ip_collection->table);
        *ip_usage = (double)ip_count / vc->ip_collection->capacity;
    }

    if (session_usage) {
        size_t session_count = hash_table_size(vc->session_collection->table);
        *session_usage = (double)session_count / vc->session_collection->capacity;
    }

    if (protocol_usage) {
        size_t protocol_count = hash_table_size(vc->protocol_collection->table);
        *protocol_usage = (double)protocol_count / vc->protocol_collection->capacity;
    }
}

void print_capacity_info(virtual_collections_t *vc) {
    if (!vc) {
        return;
    }

    double ip_usage, session_usage, protocol_usage;
    get_capacity_usage(vc, &ip_usage, &session_usage, &protocol_usage);

    printf("\n[Capacity Info]\n");
    printf("  IP Collection: %.1f%% (%zu / %zu)\n",
           ip_usage * 100,
           hash_table_size(vc->ip_collection->table),
           vc->ip_collection->capacity);
    printf("  Session Collection: %.1f%% (%zu / %zu)\n",
           session_usage * 100,
           hash_table_size(vc->session_collection->table),
           vc->session_collection->capacity);
    printf("  Protocol Collection: %.1f%% (%zu / %zu)\n",
           protocol_usage * 100,
           hash_table_size(vc->protocol_collection->table),
           vc->protocol_collection->capacity);

    bool ip_need, session_need, protocol_need;
    check_capacity_need_arrange(vc, &ip_need, &session_need, &protocol_need);

    if (ip_need || session_need || protocol_need) {
        printf("\n  ⚠️  Capacity management needed:\n");
        if (ip_need) printf("    - IP Collection\n");
        if (session_need) printf("    - Session Collection\n");
        if (protocol_need) printf("    - Protocol Collection\n");
    } else {
        printf("\n  ✓ All collections within capacity\n");
    }

    printf("\n");
}
