/**
 * @file main.c
 * @brief 流量监测系统 - 主程序
 * @version 1.0
 * @date 2026-01-27
 * 
 * 功能：
 *   1. 整合抓包、会话管理、协议解析、统计模块
 *   2. 支持 DPDK 和 libpcap 双后端
 *   3. 支持命令行参数配置
 *   4. 优雅的信号处理和资源清理
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <signal.h>
#include <unistd.h>
#include <getopt.h>
#include <errno.h>
#define TM_NO_PRINTF_OVERRIDE  /* main.c 已手动替换，无需宏覆盖 */
#include "log.h"
#include "http_server.h"
#include <time.h>
#include <stdbool.h>
#include <stdint.h>
#include <inttypes.h>
#include <pthread.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <fcntl.h>

#include <dlfcn.h>
#include <arpa/inet.h>

#include "capture/capture.h"
#include "session/session.h"
#include "plugin/engine.h"
#include "stats/stats.h"
#include "stats/storage_engine.h"
#include "stats/virtual_collection.h"
#include "stats/capacity_manager.h"
#include "stats/student_profile.h"
#include "stats/verdict_engine.h"
#include "plugin/plugins/ch_storage.h"
#include "plugin/plugins/mongo_storage.h"
#include "plugin/plugins/ch_event.h"
#ifdef CAPTURE_ENABLE_DPDK
#include "stats/event_batch.h"
#endif
#include "plugin/engine_log.h"

/* 证书引擎处理函数指针类型 */
typedef int (*cert_process_stream_fn)(engine_ctx_t *ctx, const parsed_packet_t *pkt);

/* TLS 流重组缓冲区：挂载在 session->tls_stream_buf（独立于 ext_data 槽位），
 * 用于跨包重组证书数据。独立字段避免了与 engine_tls_infra 等插件争抢
 * session 的 ext_type/ext_data 槽位，确保证书重组对任意协议都能正常工作。 */
#define TLS_STREAM_BUF_SIZE 65536
typedef struct {
    uint8_t  buf[TLS_STREAM_BUF_SIZE];
    uint32_t len;
} tls_stream_buf_t;

/* DPDK 多核流水线头文件 */
#ifdef CAPTURE_ENABLE_DPDK
#include <rte_ring.h>
#include <rte_mempool.h>
#include <rte_lcore.h>
#include <rte_launch.h>
#include <rte_mbuf.h>
#include <rte_pause.h>
#include <rte_ethdev.h>
#endif

/* 命名管道路径 */
#define PLUGIN_CMD_FIFO "/tmp/traffic_monitor_plugin_cmd"

/* 最大引擎数量 */
#define MAX_ENGINES 16

/* 默认 worker 数量（DPDK 多队列模式，运行时可通过 -w 修改）*/
#define DEFAULT_NB_WORKERS  4

/* ---- 多核流水线常量 ---- */
#ifdef CAPTURE_ENABLE_DPDK
#define LCORE_MAIN          0               /* 管理线程 */
#define LCORE_STORAGE       1               /* 存储线程（原 lcore4，现移至 lcore1）*/
#define LCORE_WORKER_BASE   2               /* worker 从 lcore2 开始 */
#define STORAGE_RING_SIZE   131072          /* storage ring 大小（2的幂）*/
#ifdef __SANITIZE_ADDRESS__
#define STORAGE_POOL_SIZE   4096           /* ASAN 模式下缩小 pool，减少 hugepage 压力 */
#else
#define STORAGE_POOL_SIZE   131072          /* storage task mempool 元素数 */
#endif
#define STORAGE_POOL_CACHE  256             /* per-lcore 缓存大小 */
#define PIPELINE_BURST_SIZE 256            /* 每次 burst 大小（从 64 增大到 256） */

/* event ring：将插件 ch_insert_* 调用从 worker 热路径中分离 */
#define EVENT_RING_SIZE    65536           /* 2^16，MPSC */
#define EVENT_POOL_SIZE    65536           /* mempool 元素数 */
#define EVENT_POOL_CACHE   256             /* per-lcore 缓存 */
#define EVENT_BATCH_SIZE   256             /* 每次出队最多处理的事件数（低水位基准）*/
#define EVENT_BATCH_MAX    4096            /* 积压时单轮出队上限 = evs[] 数组大小（4096*8B=32KB 栈占用，安全）*/
#define EVENT_FLUSH_INTERVAL_S 2           /* 超时兜底 flush 间隔（秒）*/

/* 过期会话清扫「去内联化」摊销参数（见 session_flush_expired_amortized）：
 * 旧版每 30s 在 worker 线程内联全量清扫，RSS 塌缩下单 worker 独扛 ~百万会话时一次要跑
 * 2.5-3.5min 不 poll → NIC 队列无人排空 → imissed 暴涨的 RX 黑洞。改为每轮 poll 只清一小片、
 * 跨迭代游标续扫。SWEEP_TIME_BUDGET_US ≈ 一个 PIPELINE_BURST(256) 的线速到达时间
 * (256/1.25Mpps≈205µs) 取 250µs，使单轮清扫 ≈ ring 中性、远小于 nb_rxd=4096 的 ~3.28ms
 * 灌满时间。峰值仍追不上属单核饱和（RSS 塌缩，P2 的事），但绝不再黑洞。两参数可调。*/
#define SWEEP_TIME_BUDGET_US   250         /* 单轮过期清扫时间盒(µs)，主约束 */
#define SWEEP_BUCKET_SCAN_CAP  2048        /* 单轮最多扫描桶数，限制空表空扫成本 */

/* log ring：worker → 异步日志线程（锁无关，避免 mutex 阻塞热路径）*/
#define LOG_RING_SIZE      65536           /* 2^16，MPSC */
#ifdef __SANITIZE_ADDRESS__
#define LOG_POOL_SIZE      4096
#else
#define LOG_POOL_SIZE      65536
#endif
#define LOG_POOL_CACHE     256
#endif

/*============================================================================
 *                              全局变量
 *============================================================================*/

/* 运行标志 */
static volatile sig_atomic_t g_running = 1;

/* 日志级别：0=ERROR, 1=WARN, 2=INFO, 3=DEBUG
 * 通过 -rdynamic 导出，供各协议解析插件（.so）读取 */
int g_log_level = TM_LOG_LVL_INFO;

/* 禁用插件加载标志（--no-plugins / -P） */
static int g_no_plugins = 0;

/* 全局句柄 */
static capture_handle_t *g_capture = NULL;
static session_manager_t *g_session_mgr = NULL;
static engine_registry_t *g_engine_registry = NULL;
static storage_engine_t *g_storage_engine = NULL;  // v2.0 存储引擎
static student_profile_engine_t *g_ip_profile_engine = NULL;
static verdict_engine_t *g_verdict_engine = NULL;

/* 统计计数器 */
static volatile uint64_t g_packet_count = 0;
static volatile uint64_t g_byte_count = 0;
static volatile uint64_t g_drop_count = 0;   /* ring满/非法包丢弃计数 */
static volatile uint64_t g_verdict_skip_count = 0; /* 无信号 verdict 门控跳过计数（不写 verdict_results）*/
static time_t g_start_time = 0;
static const char *g_shutdown_reason = "normal"; /* 由信号处理器或正常退出路径设置 */
static time_t g_last_stats_time = 0;
static time_t g_last_flush_time = 0;  // 上次刷盘时间
static time_t g_last_session_flush = 0; // 上次过期会话清理时间（单线程模式）

/* 证书引擎上下文和处理函数（用于直接调用证书处理） */
static engine_ctx_t *g_cert_engine_ctx = NULL;
static cert_process_stream_fn g_cert_process_stream = NULL;

/* ---- 多核流水线全局变量（仅 DPDK 模式）---- */
#ifdef CAPTURE_ENABLE_DPDK
static int                 g_nb_workers = DEFAULT_NB_WORKERS; /* 运行时 worker 数量 */
static struct rte_ring    *g_storage_ring  = NULL;      /* workers → storage lcore */
static struct rte_mempool *g_storage_task_pool = NULL;  /* storage_task_t 内存池 */
static struct rte_ring    *g_event_ring    = NULL;      /* workers → storage lcore（协议事件）*/
static struct rte_mempool *g_event_task_pool = NULL;    /* event_task_t 内存池 */
static session_manager_t  *g_worker_sessions[16];       /* 每 worker 独立会话表（最多16）*/
static engine_registry_t  *g_worker_engines[16];        /* 每 worker 独立引擎链（最多16）*/
static volatile int        g_pipeline_mode = 0;          /* 1=DPDK多核, 0=单线程 */
static volatile uint64_t   g_worker_rx[16];              /* per-worker 真实收包计数（诊断RSS分布）*/
static struct rte_ring    *g_log_ring     = NULL;      /* worker → 异步日志线程 */
static struct rte_mempool *g_log_pool     = NULL;      /* 日志消息 mempool */
#endif

static char g_plugin_log_path[256] = "";

static void http_get_sys_stats(http_sys_stats_t *out) {
    time_t now = time(NULL);
    out->packet_count = g_packet_count;
    out->byte_count = g_byte_count;
    out->drop_count = g_drop_count;
    out->uptime_sec = now - g_start_time;
#ifdef CAPTURE_ENABLE_DPDK
    out->nb_workers = g_pipeline_mode ? g_nb_workers : 1;
#else
    out->nb_workers = 1;
#endif
    strncpy(out->plugin_log_path, g_plugin_log_path, sizeof(out->plugin_log_path) - 1);
    out->plugin_log_path[sizeof(out->plugin_log_path) - 1] = '\0';
}

static int collect_plugin_registries(engine_registry_t **registries, int max_count)
{
    int count = 0;

#ifdef CAPTURE_ENABLE_DPDK
    if (g_pipeline_mode) {
        for (int i = 0; i < g_nb_workers && count < max_count; i++) {
            if (g_worker_engines[i]) {
                registries[count++] = g_worker_engines[i];
            }
        }
        return count;
    }
#endif

    if (g_engine_registry && count < max_count) {
        registries[count++] = g_engine_registry;
    }
    return count;
}

static bool registry_has_engine_locked(engine_registry_t *registry, const char *name)
{
    bool found = false;
    if (!registry || !name) return false;

    pthread_rwlock_rdlock(&registry->registry_lock);
    for (uint32_t i = 0; i < registry->engine_count; i++) {
        engine_entry_t *entry = registry->engines[i];
        if (entry && entry->ops && strcmp(entry->ops->name, name) == 0) {
            found = true;
            break;
        }
    }
    pthread_rwlock_unlock(&registry->registry_lock);
    return found;
}

static bool is_safe_plugin_path(const char *plugin_path)
{
    const char *builtin_prefix = "plugin/plugins/engine_";
    const char *custom_prefix = "Flask_network_new/instance/custom_plugins/";
    size_t len;

    if (!plugin_path || plugin_path[0] == '\0') return false;
    if (plugin_path[0] == '/' || strchr(plugin_path, '\\')) return false;
    if (strstr(plugin_path, "..")) return false;

    len = strlen(plugin_path);
    if (len <= 3 || strcmp(plugin_path + len - 3, ".so") != 0) return false;

    if (strncmp(plugin_path, builtin_prefix, strlen(builtin_prefix)) == 0 &&
        strchr(plugin_path + strlen(builtin_prefix), '/') == NULL) {
        return true;
    }
    if (strncmp(plugin_path, custom_prefix, strlen(custom_prefix)) == 0 &&
        strchr(plugin_path + strlen(custom_prefix), '/') == NULL) {
        return true;
    }
    return false;
}

static int read_plugin_engine_info(const char *plugin_path,
                                   char *engine_name,
                                   size_t engine_name_size,
                                   uint32_t *protocol_id,
                                   uint32_t *priority,
                                   char *detail,
                                   size_t detail_size)
{
    void *handle;
    engine_get_ops_func_t get_ops;
    const engine_ops_t *ops;

    if (!engine_name || engine_name_size == 0) return -1;
    engine_name[0] = '\0';
    if (detail && detail_size > 0) detail[0] = '\0';

    dlerror();
    handle = dlopen(plugin_path, RTLD_LAZY);
    if (!handle) {
        if (detail && detail_size > 0)
            snprintf(detail, detail_size, "dlopen failed: %s", dlerror());
        return -1;
    }

    get_ops = (engine_get_ops_func_t)dlsym(handle, "engine_get_ops");
    if (!get_ops) {
        if (detail && detail_size > 0)
            snprintf(detail, detail_size, "missing engine_get_ops: %s", dlerror());
        dlclose(handle);
        return -1;
    }

    ops = get_ops();
    if (!ops || !ops->name || ops->name[0] == '\0') {
        if (detail && detail_size > 0)
            snprintf(detail, detail_size, "plugin returned invalid engine ops");
        dlclose(handle);
        return -1;
    }

    strncpy(engine_name, ops->name, engine_name_size - 1);
    engine_name[engine_name_size - 1] = '\0';
    if (protocol_id) *protocol_id = ops->protocol_id;
    if (priority) *priority = ops->priority;
    dlclose(handle);
    return 0;
}

static void json_escape_text(const char *src, char *dst, size_t dst_size)
{
    size_t pos = 0;
    if (!dst || dst_size == 0) return;
    if (!src) src = "";

    for (const unsigned char *p = (const unsigned char *)src; *p && pos + 1 < dst_size; p++) {
        const char *escape = NULL;
        char hex[7];

        switch (*p) {
            case '"':  escape = "\\\""; break;
            case '\\': escape = "\\\\"; break;
            case '\n': escape = "\\n";  break;
            case '\r': escape = "\\r";  break;
            case '\t': escape = "\\t";  break;
            default:
                if (*p < 0x20) {
                    snprintf(hex, sizeof(hex), "\\u%04x", *p);
                    escape = hex;
                }
                break;
        }

        if (escape) {
            size_t n = strlen(escape);
            if (pos + n >= dst_size) break;
            memcpy(dst + pos, escape, n);
            pos += n;
        } else {
            dst[pos++] = (char)*p;
        }
    }
    dst[pos] = '\0';
}

static int write_plugin_control_response(char *response,
                                         size_t response_size,
                                         int *status_code,
                                         int http_status,
                                         const char *action,
                                         const char *engine_name,
                                         int affected_workers,
                                         const char *error,
                                         const char *detail)
{
    char escaped_action[32];
    char escaped_engine[ENGINE_NAME_MAX_LEN * 2];
    char escaped_error[128];
    char escaped_detail[512];

    if (status_code) *status_code = http_status;
    json_escape_text(action ? action : "", escaped_action, sizeof(escaped_action));
    json_escape_text(engine_name ? engine_name : "", escaped_engine, sizeof(escaped_engine));
    json_escape_text(error ? error : "error", escaped_error, sizeof(escaped_error));
    json_escape_text(detail ? detail : "", escaped_detail, sizeof(escaped_detail));
    snprintf(response, response_size,
             "{\"ok\":false,\"action\":\"%s\",\"engine_name\":\"%s\","
             "\"affected_workers\":%d,\"error\":\"%s\",\"detail\":\"%s\"}",
             escaped_action, escaped_engine,
             affected_workers > 0 ? affected_workers : 0,
             escaped_error, escaped_detail);
    return -1;
}

static int http_plugin_control(const char *action,
                               const char *value,
                               char *response,
                               size_t response_size,
                               int *status_code)
{
    engine_registry_t *registries[16];
    int registry_count = collect_plugin_registries(registries, 16);

    if (!action || !value || !response || response_size == 0) {
        return -1;
    }
    response[0] = '\0';

    if (strcmp(action, "inspect") == 0) {
        char engine_name[ENGINE_NAME_MAX_LEN];
        char detail[256];
        uint32_t protocol_id = 0;
        uint32_t priority = 0;

        if (!is_safe_plugin_path(value)) {
            return write_plugin_control_response(
                response, response_size, status_code, 400,
                action, "", 0,
                "invalid_path", "plugin path must be plugin/plugins/engine_*.so or Flask_network_new/instance/custom_plugins/*.so");
        }
        if (access(value, R_OK) != 0) {
            return write_plugin_control_response(
                response, response_size, status_code, 404,
                action, "", 0,
                "file_not_found", "plugin shared object does not exist or is not readable");
        }
        if (read_plugin_engine_info(value, engine_name, sizeof(engine_name),
                                    &protocol_id, &priority,
                                    detail, sizeof(detail)) != 0) {
            return write_plugin_control_response(
                response, response_size, status_code, 400,
                action, "", 0,
                "invalid_plugin", detail);
        }

        char escaped_engine[ENGINE_NAME_MAX_LEN * 2];
        json_escape_text(engine_name, escaped_engine, sizeof(escaped_engine));
        if (status_code) *status_code = 200;
        snprintf(response, response_size,
                 "{\"ok\":true,\"action\":\"inspect\",\"engine_name\":\"%s\","
                 "\"protocol_id\":%u,\"priority\":%u,\"affected_workers\":0,"
                 "\"error\":\"\",\"detail\":\"\"}",
                 escaped_engine, protocol_id, priority);
        return 0;
    }

    if (registry_count <= 0) {
        return write_plugin_control_response(
            response, response_size, status_code, 503,
            action, "", 0,
            "registry_unavailable", "no active engine registry");
    }

    if (strcmp(action, "load") == 0) {
        char engine_name[ENGINE_NAME_MAX_LEN];
        char detail[256];

        if (!is_safe_plugin_path(value)) {
            return write_plugin_control_response(
                response, response_size, status_code, 400,
                action, "", 0,
                "invalid_path", "plugin path must be plugin/plugins/engine_*.so or Flask_network_new/instance/custom_plugins/*.so");
        }
        if (access(value, R_OK) != 0) {
            return write_plugin_control_response(
                response, response_size, status_code, 404,
                action, "", 0,
                "file_not_found", "plugin shared object does not exist or is not readable");
        }
        if (read_plugin_engine_info(value, engine_name, sizeof(engine_name),
                                    NULL, NULL, detail, sizeof(detail)) != 0) {
            return write_plugin_control_response(
                response, response_size, status_code, 400,
                action, "", 0,
                "invalid_plugin", detail);
        }

        int loaded_count = 0;
        for (int i = 0; i < registry_count; i++) {
            if (registry_has_engine_locked(registries[i], engine_name)) {
                loaded_count++;
            }
        }
        if (loaded_count == registry_count) {
            return write_plugin_control_response(
                response, response_size, status_code, 409,
                action, engine_name, loaded_count,
                "already_loaded", "plugin is already loaded on all workers");
        }
        if (loaded_count > 0) {
            return write_plugin_control_response(
                response, response_size, status_code, 409,
                action, engine_name, loaded_count,
                "inconsistent_state", "plugin is loaded on only some workers");
        }

        int loaded_workers = 0;
        for (int i = 0; i < registry_count; i++) {
            if (engine_registry_load_plugin(registries[i], value) != ENGINE_OK) {
                for (int j = 0; j < loaded_workers; j++) {
                    engine_registry_unload_plugin(registries[j], engine_name);
                }
                return write_plugin_control_response(
                    response, response_size, status_code, 500,
                    action, engine_name, loaded_workers,
                    "load_failed", "failed to load plugin on all workers; rollback attempted");
            }
            loaded_workers++;
        }

        char escaped_engine[ENGINE_NAME_MAX_LEN * 2];
        json_escape_text(engine_name, escaped_engine, sizeof(escaped_engine));
        if (status_code) *status_code = 200;
        snprintf(response, response_size,
                 "{\"ok\":true,\"action\":\"load\",\"engine_name\":\"%s\","
                 "\"affected_workers\":%d,\"error\":\"\",\"detail\":\"\"}",
                 escaped_engine, registry_count);
        TM_LOG_INFO("PLUGIN", "HTTP 控制加载插件成功: %s (%s), workers=%d",
                    value, engine_name, registry_count);
        {
            char op_detail[512];
            snprintf(op_detail, sizeof(op_detail), "path=%s workers=%d", value, registry_count);
            ch_insert_operation_log("plugin_load", "collector", "system",
                                    engine_name, 0, op_detail, 1, "{}");
        }
        return 0;
    }

    if (strcmp(action, "unload") == 0) {
        const char *engine_name = value;

        for (int i = 0; i < registry_count; i++) {
            if (!registry_has_engine_locked(registries[i], engine_name)) {
                return write_plugin_control_response(
                    response, response_size, status_code, 409,
                    action, engine_name, 0,
                    "not_loaded", "plugin is not loaded on all workers");
            }
        }

        int unloaded_workers = 0;
        for (int i = 0; i < registry_count; i++) {
            if (engine_registry_unload_plugin(registries[i], engine_name) != ENGINE_OK) {
                return write_plugin_control_response(
                    response, response_size, status_code, 500,
                    action, engine_name, unloaded_workers,
                    "unload_failed", "failed to unload plugin from all workers");
            }
            unloaded_workers++;
        }

        char escaped_engine[ENGINE_NAME_MAX_LEN * 2];
        json_escape_text(engine_name, escaped_engine, sizeof(escaped_engine));
        if (status_code) *status_code = 200;
        snprintf(response, response_size,
                 "{\"ok\":true,\"action\":\"unload\",\"engine_name\":\"%s\","
                 "\"affected_workers\":%d,\"error\":\"\",\"detail\":\"\"}",
                 escaped_engine, registry_count);
        TM_LOG_INFO("PLUGIN", "HTTP 控制卸载插件成功: %s, workers=%d",
                    engine_name, registry_count);
        {
            char op_detail[256];
            snprintf(op_detail, sizeof(op_detail), "engine=%s workers=%d", engine_name, registry_count);
            ch_insert_operation_log("plugin_unload", "collector", "system",
                                    engine_name, 0, op_detail, 1, "{}");
        }
        return 0;
    }

    return write_plugin_control_response(
        response, response_size, status_code, 400,
        action, "", 0,
        "unknown_action", "supported actions are inspect, load and unload");
}

static void handle_expired_session_for_profiles(const session_entry_t *session,
                                                void *user_data)
{
    (void)user_data;

    if (!session || !g_storage_engine || !g_ip_profile_engine) {
        return;
    }

    /*
     * 过期回调按“先裁决、再画像”的顺序执行：
     * - verdict 结果先进入 verdict 虚拟集合
     * - IP 画像再复用 verdict 的 crossing/confidence 结果补齐关联字段
     * 这样画像里只保留最小必要的翻墙摘要，不需要重新推断一次。
     */
    verdict_doc_t verdict_doc;
    verdict_doc_t *verdict_ptr = NULL;
    memset(&verdict_doc, 0, sizeof(verdict_doc));

    if (g_verdict_engine && session->tunnel_signal) {
        if (verdict_engine_evaluate(g_verdict_engine, session, &verdict_doc) == 0) {
            /* 写门控：绝大多数会话是 is_crossing=0 且无任何因子命中的"无信号"行，
             * 全量落 verdict_results 是写放大与磁盘膨胀之源（每天 7000 万+行）。
             * 下游翻墙查询恒带 WHERE is_crossing=1，无信号行无人消费，直接跳过写库。
             * is_crossing=1 永远照写；有得分或有触发因子的行也保留（覆盖因子权重
             * 为 0 但命中的场景），供排障与调阈值使用。 */
            if (verdict_doc.is_crossing || verdict_doc.total_score > 0 ||
                verdict_doc.triggered_factors[0] != '\0') {
                storage_engine_submit_verdict(g_storage_engine, &verdict_doc);
            } else {
                /* 过期回调在多个 worker 线程并发触发，计数用原子累加 */
                __atomic_fetch_add(&g_verdict_skip_count, 1, __ATOMIC_RELAXED);
            }
            verdict_ptr = &verdict_doc;   /* 画像关联仍用完整裁决结果，不受门控影响 */
        }
    }

    /* tunnel_signals 保留宽口径(含 non_standard_tls)；auxiliary_signals 仅高价值因子
     * (隐蔽通道/伪装/心跳/TI)，与可疑流量页展示口径一致。 */
    storage_engine_submit_session_signals(g_storage_engine, session);

    ip_profile_doc_t profile_doc;
    if (student_profile_process_with_verdict(g_ip_profile_engine, session,
                                             verdict_ptr, &profile_doc) == 0) {
        storage_engine_submit_ip_profile(g_storage_engine, &profile_doc);
    }
}

/*============================================================================
 *                              配置结构
 *============================================================================*/

typedef struct app_config {
    /* 抓包配置 */
    capture_backend_t backend;
    char interface[64];
    bool promiscuous;
    int snaplen;
    char bpf_filter[1024];
    
    /* 会话配置 */
    uint32_t session_table_size;
    uint32_t session_timeout;
    
    /* 统计配置 (ClickHouse) */
    char ch_host[256];
    int  ch_port;
    char ch_user[64];
    char ch_password[64];
    char mongodb_uri[256];   /* MongoDB 连接 URI（证书存储用） */
    char db_name[64];
    char log_file[256];      /* 日志文件路径 */
    uint32_t stats_interval;
    bool enable_db;
    
    /* 运行模式 */
    bool daemon_mode;
    int packet_count;           // -1 表示无限

    /* DPDK EAL 扩展参数（NUMA 绑定用）*/
    char lcore_map[64];         // --lcores 重映射，如 "(0-4)@(8,9,10,11,12)"，空=默认 -l 0-N
    char socket_mem[64];        // --socket-mem，如 "0,1024"，空=不指定

    /* 多队列 worker 数量 */
    int nb_workers;             /* RSS worker 线程数（同时决定 RX 队列数），默认 DEFAULT_NB_WORKERS */

    /* 禁用插件加载（纯会话模块测试用） */
    bool no_plugins;

    /* HTTP 管理界面端口（0 = 不启动）*/
    int http_port;

} app_config_t;

/* ---- 存储任务：worker → storage lcore 的工作单元（仅 DPDK 模式）---- */
#ifdef CAPTURE_ENABLE_DPDK
typedef struct storage_task {
    session_record_t record;        /* 由 session_to_record() 填充的会话记录 */
    ip_flow_doc_t    src_ip_doc;    /* 源 IP 流量文档（worker 预构建）*/
    ip_flow_doc_t    dst_ip_doc;    /* 目标 IP 流量文档 */
    bool             has_session;   /* record/ip_doc 是否有效 */
    uint8_t          protocol;      /* IP 协议号 */
    uint16_t         src_port;
    uint16_t         dst_port;
} storage_task_t;

/* worker lcore 参数 */
typedef struct worker_lcore_args {
    int worker_id;
    uint16_t queue_id;   /* 此 worker 直接轮询的 NIC 队列号 */
} worker_lcore_args_t;

static worker_lcore_args_t g_worker_args[16]; /* 最多 16 workers */
#endif

/*============================================================================
 *                              信号处理
 *============================================================================*/

/* ---- 多核流水线辅助函数（仅 DPDK 模式）---- */
#ifdef CAPTURE_ENABLE_DPDK
/**
 * @brief 计算流的 worker 分片 ID（保留备用，新架构由 RSS 硬件分流）
 *
 * @return worker 索引 [0, g_nb_workers)
 */
static __attribute__((unused)) uint32_t compute_flow_hash(uint32_t src_ip, uint32_t dst_ip,
                                  uint16_t src_port, uint16_t dst_port,
                                  uint8_t protocol)
{
    /* 规范化：与 session.c:normalize_key() 完全一致 */
    uint32_t ip_low, ip_high;
    uint16_t port_low, port_high;

    if (src_ip < dst_ip || (src_ip == dst_ip && src_port < dst_port)) {
        ip_low = src_ip;  ip_high = dst_ip;
        port_low = src_port; port_high = dst_port;
    } else {
        ip_low = dst_ip;  ip_high = src_ip;
        port_low = dst_port; port_high = src_port;
    }

    /* FNV-1a 哈希：与 session.c:session_hash() 完全一致 */
    uint32_t hash = 2166136261u;
    hash ^= ip_low;    hash *= 16777619;
    hash ^= ip_high;   hash *= 16777619;
    hash ^= (uint32_t)(port_low << 16) | port_high;
    hash *= 16777619;
    hash ^= (uint32_t)protocol;

    return hash % g_nb_workers;
}
#endif /* CAPTURE_ENABLE_DPDK */

/**
 * @brief 信号处理函数
 */
static void signal_handler(int signum)
{
    switch (signum) {
        case SIGINT:
        case SIGTERM:
            TM_LOG_INFO("MAIN", "收到信号 %d，准备退出...", signum);
            g_running = 0;
            /* 记录触发关闭的信号名称，供 engine_log_shutdown 使用 */
            g_shutdown_reason = (signum == SIGINT) ? "SIGINT" : "SIGTERM";

#ifdef CAPTURE_ENABLE_DPDK
            if (!g_pipeline_mode) {
#endif
                /* 单线程模式：在信号处理器中提交最终统计并刷盘 */
                if (g_storage_engine) {
                    TM_LOG_INFO("MAIN", "正在提交最终统计数据...");

                    if (g_engine_registry) {
                        time_t now = time(NULL);
                        for (uint32_t i = 0; i < g_engine_registry->engine_count; i++) {
                            engine_entry_t *engine = g_engine_registry->engines[i];
                            if (!engine || !engine->ops) continue;

                            protocol_doc_t proto_doc = {0};
                            strncpy(proto_doc.protocol_name, engine->ops->name,
                                   sizeof(proto_doc.protocol_name) - 1);
                            proto_doc.protocol_id = engine->ops->protocol_id;
                            proto_doc.first_time = g_start_time;
                            proto_doc.last_time = now;
                            proto_doc.packets_processed = engine->ctx.stats.packets_processed;
                            proto_doc.packets_matched = engine->ctx.stats.packets_matched;
                            proto_doc.bytes_processed = 0;
                            proto_doc.sessions_created = engine->ctx.stats.sessions_created;
                            proto_doc.errors = engine->ctx.stats.errors;

                            virtual_collection_update_protocol(g_storage_engine->vc, &proto_doc);
                        }
                    }

                    TM_LOG_INFO("MAIN", "正在刷盘数据到数据库...");
                    storage_engine_flush_all(g_storage_engine);
                    storage_engine_print_stats(g_storage_engine);
                }

                /* 停止抓包循环 */
                if (g_capture) {
                    capture_break_loop(g_capture);
                }
#ifdef CAPTURE_ENABLE_DPDK
            }
            /* 多核流水线模式：各 lcore 检测 g_running==0 后自行退出并排空 ring，
             * storage lcore 负责最终刷盘 */
#endif
            break;

        case SIGHUP:
            tm_log_reopen();
            engine_log_file_reopen();
            TM_LOG_INFO("MAIN", "收到 SIGHUP，日志文件已重新打开");
            break;

        case SIGUSR1:
            TM_LOG_INFO("MAIN", "收到 SIGUSR1 信号，卸载 DNS 插件...");
            if (g_engine_registry) {
                if (engine_registry_unload_plugin(g_engine_registry, "DNS") == 0) {
                    TM_LOG_INFO("MAIN", "DNS 插件已卸载");
                } else {
                    TM_LOG_ERROR("MAIN", "DNS 插件卸载失败");
                }
            }
            break;

        case SIGUSR2:
            TM_LOG_INFO("MAIN", "收到 SIGUSR2 信号，重新加载 DNS 插件...");
            if (g_engine_registry) {
                if (engine_registry_load_plugin(g_engine_registry,
                    "plugin/plugins/engine_dns.so") == 0) {
                    TM_LOG_INFO("MAIN", "DNS 插件已重新加载");
                } else {
                    TM_LOG_ERROR("MAIN", "DNS 插件加载失败");
                }
            }
            break;
            
        default:
            break;
    }
}

/**
 * @brief 插件命令处理线程
 */
static void* plugin_command_thread(void* arg)
{
    (void)arg;  // 未使用的参数
    char cmd_buffer[512];
    FILE* fifo_fp = NULL;

    TM_LOG_INFO("PLUGIN", "命令处理线程已启动");
    TM_LOG_INFO("PLUGIN", "监听命令管道: %s", PLUGIN_CMD_FIFO);
    TM_LOG_INFO("PLUGIN", "使用方法: echo 'load plugin/plugins/xxx.so' > %s", PLUGIN_CMD_FIFO);
    TM_LOG_INFO("PLUGIN", "使用方法: echo 'unload PluginName' > %s", PLUGIN_CMD_FIFO);

    while (g_running) {
        // 打开 FIFO（阻塞直到有写入者）
        fifo_fp = fopen(PLUGIN_CMD_FIFO, "r");
        if (!fifo_fp) {
            if (g_running) {
                TM_LOG_ERROR("PLUGIN", "无法打开命令管道: %s", strerror(errno));
                sleep(1);
            }
            continue;
        }

        // 读取命令
        while (g_running && fgets(cmd_buffer, sizeof(cmd_buffer), fifo_fp)) {
            // 移除换行符
            size_t len = strlen(cmd_buffer);
            if (len > 0 && cmd_buffer[len-1] == '\n') {
                cmd_buffer[len-1] = '\0';
            }

            TM_LOG_INFO("PLUGIN", "收到命令: %s", cmd_buffer);

            // 解析命令
            if (strncmp(cmd_buffer, "load ", 5) == 0) {
                // 加载插件
                char* plugin_path = cmd_buffer + 5;

                // 跳过前导空格
                while (*plugin_path == ' ') plugin_path++;

                if (strlen(plugin_path) == 0) {
                    TM_LOG_ERROR("PLUGIN", "插件路径为空");
                    continue;
                }

                TM_LOG_INFO("PLUGIN", "正在加载插件: %s", plugin_path);

                if (g_engine_registry) {
                    int ret = engine_registry_load_plugin(g_engine_registry, plugin_path);
                    if (ret == 0) {
                        TM_LOG_INFO("PLUGIN", "插件加载成功: %s", plugin_path);
                    } else {
                        TM_LOG_ERROR("PLUGIN", "插件加载失败: %s (错误码: %d)",
                               plugin_path, ret);
                    }
                } else {
                    TM_LOG_ERROR("PLUGIN", "引擎注册表未初始化");
                }

            } else if (strncmp(cmd_buffer, "unload ", 7) == 0) {
                // 卸载插件
                char* plugin_name = cmd_buffer + 7;

                // 跳过前导空格
                while (*plugin_name == ' ') plugin_name++;

                if (strlen(plugin_name) == 0) {
                    TM_LOG_ERROR("PLUGIN", "插件名称为空");
                    continue;
                }

                TM_LOG_INFO("PLUGIN", "正在卸载插件: %s", plugin_name);

                if (g_engine_registry) {
                    int ret = engine_registry_unload_plugin(g_engine_registry, plugin_name);
                    if (ret == 0) {
                        TM_LOG_INFO("PLUGIN", "插件卸载成功: %s", plugin_name);
                    } else {
                        TM_LOG_ERROR("PLUGIN", "插件卸载失败: %s (错误码: %d)",
                               plugin_name, ret);
                    }
                } else {
                    TM_LOG_ERROR("PLUGIN", "引擎注册表未初始化");
                }

            } else if (strcmp(cmd_buffer, "list") == 0) {
                // 列出插件
                TM_LOG_INFO("PLUGIN", "已加载的插件:");
                // 这里可以添加列出插件的功能
                TM_LOG_INFO("PLUGIN", "  - HTTP (静态引擎)");
                TM_LOG_INFO("PLUGIN", "  - DNS (动态插件，如果已加载)");

            } else if (strcmp(cmd_buffer, "help") == 0) {
                TM_LOG_INFO("PLUGIN", "可用命令:");
                TM_LOG_INFO("PLUGIN", "  load <path>          加载插件");
                TM_LOG_INFO("PLUGIN", "  unload <name>        卸载插件");
                TM_LOG_INFO("PLUGIN", "  list                 列出已加载插件");
                TM_LOG_INFO("PLUGIN", "  loglevel <level>     设置插件日志级别 (error/warn/info/debug)");
                TM_LOG_INFO("PLUGIN", "  help                 显示此帮助");

            } else if (strncmp(cmd_buffer, "loglevel ", 9) == 0) {
                /* 运行时动态调整插件日志级别 */
                const char *lvl_str = cmd_buffer + 9;
                int new_level = -1;
                if      (strcmp(lvl_str, "error") == 0) new_level = TM_LOG_LVL_ERROR;
                else if (strcmp(lvl_str, "warn")  == 0) new_level = TM_LOG_LVL_WARN;
                else if (strcmp(lvl_str, "info")  == 0) new_level = TM_LOG_LVL_INFO;
                else if (strcmp(lvl_str, "debug") == 0) new_level = TM_LOG_LVL_DEBUG;

                if (new_level >= 0) {
                    tm_log_set_level(new_level);
                    TM_LOG_INFO("PLUGIN", "插件日志级别已设置为: %s", lvl_str);
                } else {
                    TM_LOG_ERROR("PLUGIN", "未知级别 '%s'，可选: error warn info debug", lvl_str);
                }

            } else if (strlen(cmd_buffer) > 0) {
                TM_LOG_ERROR("PLUGIN", "未知命令: %s", cmd_buffer);
                TM_LOG_INFO("PLUGIN", "使用 'help' 查看可用命令");
            }
        }

        fclose(fifo_fp);
        fifo_fp = NULL;
    }

    TM_LOG_INFO("PLUGIN", "命令处理线程已退出");
    return NULL;
}

/**
 * @brief 创建命名管道并启动命令处理线程
 */
static pthread_t g_plugin_thread;
static bool g_plugin_thread_started = false;

static int start_plugin_command_handler(void)
{
    // 删除旧的 FIFO（如果存在）
    unlink(PLUGIN_CMD_FIFO);

    // 创建命名管道
    if (mkfifo(PLUGIN_CMD_FIFO, 0666) < 0) {
        if (errno != EEXIST) {
            TM_LOG_ERROR("PLUGIN", "无法创建命名管道: %s", strerror(errno));
            return -1;
        }
    }

    // 修改权限，确保所有用户都可以写入
    if (chmod(PLUGIN_CMD_FIFO, 0666) < 0) {
        TM_LOG_WARN("PLUGIN", "无法修改管道权限: %s", strerror(errno));
    }

    TM_LOG_INFO("PLUGIN", "命名管道已创建: %s", PLUGIN_CMD_FIFO);

    // 启动命令处理线程
    if (pthread_create(&g_plugin_thread, NULL, plugin_command_thread, NULL) != 0) {
        TM_LOG_ERROR("PLUGIN", "无法创建命令处理线程: %s", strerror(errno));
        unlink(PLUGIN_CMD_FIFO);
        return -1;
    }

    g_plugin_thread_started = true;
    return 0;
}

/**
 * @brief 停止命令处理线程并清理
 */
static void stop_plugin_command_handler(void)
{
    if (g_plugin_thread_started) {
        TM_LOG_INFO("PLUGIN", "正在停止命令处理线程...");

        // 线程会在 g_running 变为 0 时自动退出
        // 向 FIFO 写入一个空行以唤醒阻塞的 fopen
        FILE* fp = fopen(PLUGIN_CMD_FIFO, "w");
        if (fp) {
            fprintf(fp, "\n");
            fclose(fp);
        }

        pthread_join(g_plugin_thread, NULL);
        g_plugin_thread_started = false;

        TM_LOG_INFO("PLUGIN", "命令处理线程已停止");
    }

    // 删除命名管道
    unlink(PLUGIN_CMD_FIFO);
}

/**
 * @brief 注册信号处理
 */
static int setup_signals(void)
{
    struct sigaction sa;
    
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = signal_handler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;
    
    if (sigaction(SIGINT, &sa, NULL) < 0) {
        perror("sigaction(SIGINT)");
        return -1;
    }
    
    if (sigaction(SIGTERM, &sa, NULL) < 0) {
        perror("sigaction(SIGTERM)");
        return -1;
    }
    
    if (sigaction(SIGHUP, &sa, NULL) < 0) {
        perror("sigaction(SIGHUP)");
        return -1;
    }
    
    if (sigaction(SIGUSR1, &sa, NULL) < 0) {
        perror("sigaction(SIGUSR1)");
        return -1;
    }
    
    if (sigaction(SIGUSR2, &sa, NULL) < 0) {
        perror("sigaction(SIGUSR2)");
        return -1;
    }
    
    /* 忽略 SIGPIPE */
    signal(SIGPIPE, SIG_IGN);
    
    return 0;
}

/*============================================================================
 *                              协议解析器
 *============================================================================*/

/**
 * @brief 简单的协议解析函数
 */
static int parse_packet(const capture_packet_t *cap_pkt, parsed_packet_t *parsed)
{
    const uint8_t *data = cap_pkt->data;
    uint32_t len = cap_pkt->caplen;

    if (len < 14) {
        return -1;  /* 太短 */
    }

    /* 解析以太网头 */
    parsed->eth_type = (data[12] << 8) | data[13];
    uint32_t offset = 14;   /* 跳过 Ethernet header (6+6+2 字节) */

    /* 剥离 802.1Q VLAN 标签（含 QinQ/802.1ad），每次 +4 字节 */
    while ((parsed->eth_type == 0x8100 || parsed->eth_type == 0x88A8) &&
           offset + 4 <= len) {
        parsed->eth_type = (data[offset + 2] << 8) | data[offset + 3];
        offset += 4;
    }

    /* 解析 IP 层 */
    if (parsed->eth_type == 0x0800) {  /* IPv4 */
        if (len < offset + 20) return -1;

        parsed->ip_version = 4;
        parsed->is_ipv4  = true;
        parsed->ttl      = data[offset + 8];
        parsed->protocol = data[offset + 9];
        /* 使用 offset 而非硬编码，正确处理 VLAN 标签后的 IP 头偏移 */
        memcpy(&parsed->src_ip, data + offset + 12, 4);
        memcpy(&parsed->dst_ip, data + offset + 16, 4);

        uint8_t  ihl      = (data[offset] & 0x0F) * 4;
        uint32_t l4_off   = offset + ihl;

        /* 解析传输层 */
        if (parsed->protocol == 6 && len >= l4_off + 14) {  /* TCP (需要到 flags 字节) */
            parsed->src_port  = ((uint32_t)data[l4_off]     << 8) | data[l4_off + 1];
            parsed->dst_port  = ((uint32_t)data[l4_off + 2] << 8) | data[l4_off + 3];
            parsed->tcp_flags = data[l4_off + 13];

            uint8_t tcp_hdr_len = ((data[l4_off + 12] >> 4) & 0x0F) * 4;
            if (tcp_hdr_len < 20) tcp_hdr_len = 20;
            if (l4_off + tcp_hdr_len > len) {
                parsed->payload     = NULL;
                parsed->payload_len = 0;
            } else {
                parsed->payload     = data + l4_off + tcp_hdr_len;
                parsed->payload_len = len - l4_off - tcp_hdr_len;
            }

        } else if (parsed->protocol == 17 && len >= l4_off + 8) {  /* UDP */
            parsed->src_port  = ((uint32_t)data[l4_off]     << 8) | data[l4_off + 1];
            parsed->dst_port  = ((uint32_t)data[l4_off + 2] << 8) | data[l4_off + 3];
            parsed->tcp_flags = 0;

            parsed->payload     = data + l4_off + 8;
            parsed->payload_len = len - l4_off - 8;
        }

    } else if (parsed->eth_type == 0x86DD) {  /* IPv6 */
        if (len < offset + 40) return -1;  /* IPv6 固定头 40 字节 */

        parsed->ip_version = 6;
        parsed->is_ipv4    = false;
        parsed->ttl        = data[offset + 7];       /* Hop Limit */
        uint8_t next_hdr   = data[offset + 6];       /* Next Header */
        memcpy(parsed->src_ip6, data + offset + 8,  16);
        memcpy(parsed->dst_ip6, data + offset + 24, 16);

        uint32_t l4_off = offset + 40;

        /* 跳过常见扩展头：逐跳(0)、路由(43)、分片(44)、目的选项(60) */
        while (next_hdr == 0 || next_hdr == 43 || next_hdr == 44 || next_hdr == 60) {
            if (len < l4_off + 2) return -1;
            uint8_t ext_len = (next_hdr == 44) ? 8 : ((uint32_t)(data[l4_off + 1] + 1) * 8);
            next_hdr = data[l4_off];
            l4_off  += ext_len;
            if (l4_off > len) return -1;
        }
        parsed->protocol = next_hdr;

        /* 解析传输层（与 IPv4 相同） */
        if (parsed->protocol == 6 && len >= l4_off + 14) {  /* TCP */
            parsed->src_port  = ((uint32_t)data[l4_off]     << 8) | data[l4_off + 1];
            parsed->dst_port  = ((uint32_t)data[l4_off + 2] << 8) | data[l4_off + 3];
            parsed->tcp_flags = data[l4_off + 13];

            uint8_t tcp_hdr_len = ((data[l4_off + 12] >> 4) & 0x0F) * 4;
            if (tcp_hdr_len < 20) tcp_hdr_len = 20;
            if (l4_off + tcp_hdr_len > len) {
                parsed->payload     = NULL;
                parsed->payload_len = 0;
            } else {
                parsed->payload     = data + l4_off + tcp_hdr_len;
                parsed->payload_len = len - l4_off - tcp_hdr_len;
            }

        } else if (parsed->protocol == 17 && len >= l4_off + 8) {  /* UDP */
            parsed->src_port  = ((uint32_t)data[l4_off]     << 8) | data[l4_off + 1];
            parsed->dst_port  = ((uint32_t)data[l4_off + 2] << 8) | data[l4_off + 3];
            parsed->tcp_flags = 0;

            parsed->payload     = data + l4_off + 8;
            parsed->payload_len = len - l4_off - 8;
        }
    }
    
    parsed->timestamp = cap_pkt->timestamp_sec * 1000000LL + cap_pkt->timestamp_usec;
    
    return 0;
}

/*============================================================================
 *                              数据包处理
 *============================================================================*/

/**
 * @brief 将会话转换为记录格式
 */
static void session_to_record(const session_entry_t *session, session_record_t *record)
{
    if (!session || !record) {
        return;
    }

    memset(record, 0, sizeof(session_record_t));

    if (session->ip_version == 6) {
        /* IPv6 地址格式化 */
        char src_buf[46] = {0}, dst_buf[46] = {0};
        inet_ntop(AF_INET6, session->src_ip6, src_buf, sizeof(src_buf));
        inet_ntop(AF_INET6, session->dst_ip6, dst_buf, sizeof(dst_buf));

        snprintf(record->session_id, sizeof(record->session_id),
                 "[%s]:%u-[%s]:%u-%u",
                 src_buf, session->src_port,
                 dst_buf, session->dst_port,
                 session->key.protocol);

        snprintf(record->src_ip, sizeof(record->src_ip), "%s", src_buf);
        snprintf(record->dst_ip, sizeof(record->dst_ip), "%s", dst_buf);
    } else {
        /* IPv4 地址格式化（原有逻辑） */
        snprintf(record->session_id, sizeof(record->session_id),
                 "%u.%u.%u.%u:%u-%u.%u.%u.%u:%u-%u",
                 (session->src_ip >> 24) & 0xFF, (session->src_ip >> 16) & 0xFF,
                 (session->src_ip >> 8) & 0xFF, session->src_ip & 0xFF,
                 session->src_port,
                 (session->dst_ip >> 24) & 0xFF, (session->dst_ip >> 16) & 0xFF,
                 (session->dst_ip >> 8) & 0xFF, session->dst_ip & 0xFF,
                 session->dst_port,
                 session->key.protocol);

        snprintf(record->src_ip, sizeof(record->src_ip),
                 "%u.%u.%u.%u",
                 (session->src_ip >> 24) & 0xFF, (session->src_ip >> 16) & 0xFF,
                 (session->src_ip >> 8) & 0xFF, session->src_ip & 0xFF);

        snprintf(record->dst_ip, sizeof(record->dst_ip),
                 "%u.%u.%u.%u",
                 (session->dst_ip >> 24) & 0xFF, (session->dst_ip >> 16) & 0xFF,
                 (session->dst_ip >> 8) & 0xFF, session->dst_ip & 0xFF);
    }

    /* 端口和协议 */
    record->src_port = session->src_port;
    record->dst_port = session->dst_port;
    record->protocol = session->key.protocol;

    /* 时间 */
    record->create_time = session->create_time;
    record->last_seen = session->last_seen;
    record->duration = (uint32_t)(session->last_seen - session->create_time);

    /* 流量统计 */
    record->packets_ab = session->stats.packets_ab;
    record->packets_ba = session->stats.packets_ba;
    record->bytes_ab = session->stats.bytes_ab;
    record->bytes_ba = session->stats.bytes_ba;

    /* 协议名称 */
    if (session->key.protocol == 6) {
        strncpy(record->detected_protocol, "TCP", sizeof(record->detected_protocol) - 1);
    } else if (session->key.protocol == 17) {
        strncpy(record->detected_protocol, "UDP", sizeof(record->detected_protocol) - 1);
    } else {
        snprintf(record->detected_protocol, sizeof(record->detected_protocol),
                 "PROTO_%u", session->key.protocol);
    }

    /* 状态 */
    strncpy(record->state, "ACTIVE", sizeof(record->state) - 1);
}

/**
 * @brief 数据包处理回调函数
 */
static int packet_handler(const capture_packet_t *pkt, void *user_data)
{
    app_config_t *config = (app_config_t *)user_data;
    parsed_packet_t parsed = {0};
    session_entry_t *session = NULL;
    time_t now = time(NULL);

    /* 更新全局计数器 */
    g_packet_count++;
    g_byte_count += pkt->caplen;

    /* DEBUG: 每 10 个包打印一次 */
    if (g_packet_count % 10 == 1) {
        TM_LOG_DEBUG("MAIN", "已捕获 %lu 个数据包", g_packet_count);
    }

    /* 1. 解析数据包 */
    if (parse_packet(pkt, &parsed) < 0) {
        if (g_packet_count % 10 == 1) {
            TM_LOG_DEBUG("MAIN", "解析失败");
        }
        return 0;  /* 解析失败，继续处理下一个包 */
    }

    /* DEBUG: 打印解析后的包信息 */
    if (g_packet_count % 10 == 1) {
        TM_LOG_DEBUG("MAIN", "协议=%u, src_port=%u, dst_port=%u, payload_len=%u",
               parsed.protocol, parsed.src_port, parsed.dst_port, parsed.payload_len);
    }
    
    /* 2. 会话管理和统计提交 */
    if (parsed.protocol == 6 || parsed.protocol == 17) {  /* TCP/UDP */
        /* 查找或创建会话（IPv4/IPv6 分支） */
        if (parsed.ip_version == 6) {
            session = session_lookup_v6(g_session_mgr,
                                       parsed.src_ip6, parsed.dst_ip6,
                                       parsed.src_port, parsed.dst_port,
                                       parsed.protocol);
            if (!session) {
                session = session_create_v6(g_session_mgr,
                                           parsed.src_ip6, parsed.dst_ip6,
                                           parsed.src_port, parsed.dst_port,
                                           parsed.protocol);
            }
            if (session)
                session_update_v6(session, parsed.src_ip6, parsed.dst_ip6, pkt->caplen);
        } else {
            session = session_lookup(g_session_mgr,
                                     parsed.src_ip, parsed.dst_ip,
                                     parsed.src_port, parsed.dst_port,
                                     parsed.protocol);
            if (!session) {
                session = session_create(g_session_mgr,
                                         parsed.src_ip, parsed.dst_ip,
                                         parsed.src_port, parsed.dst_port,
                                         parsed.protocol);
            }
            if (session)
                session_update(session, parsed.src_ip, parsed.dst_ip, pkt->caplen);
        }

        if (session) {
            /* 提交会话数据到存储引擎 */
            if (g_storage_engine) {
                session_record_t record;
                session_to_record(session, &record);
                int ret = storage_engine_submit_session(g_storage_engine, &record);
                if (g_packet_count % 10 == 1) {
                    TM_LOG_DEBUG("MAIN", "提交会话到存储引擎: %s (返回: %d)",
                           record.session_id, ret);
                }

                /* 更新 IP 流量统计 */
                bool is_ipv6 = (session->ip_version == 6);
                // 更新源 IP
                ip_flow_doc_t src_ip_doc = {0};
                snprintf(src_ip_doc.ip, sizeof(src_ip_doc.ip), "%s", record.src_ip);
                src_ip_doc.is_ipv6 = is_ipv6;
                src_ip_doc.first_time = now;
                src_ip_doc.last_time = now;
                src_ip_doc.tx_flow = record.bytes_ab;
                src_ip_doc.rx_flow = record.bytes_ba;
                src_ip_doc.tx_packets = record.packets_ab;
                src_ip_doc.rx_packets = record.packets_ba;

                // 协议统计
                if (parsed.protocol == 6) {
                    src_ip_doc.tcp_packets = 1;
                } else if (parsed.protocol == 17) {
                    src_ip_doc.udp_packets = 1;
                }

                // HTTP/DNS 统计
                if (parsed.dst_port == 80 || parsed.src_port == 80) {
                    src_ip_doc.http_packets = 1;
                } else if (parsed.dst_port == 53 || parsed.src_port == 53) {
                    src_ip_doc.dns_packets = 1;
                }

                virtual_collection_update_ip(g_storage_engine->vc, &src_ip_doc);

                // 更新目的 IP
                ip_flow_doc_t dst_ip_doc = {0};
                snprintf(dst_ip_doc.ip, sizeof(dst_ip_doc.ip), "%s", record.dst_ip);
                dst_ip_doc.is_ipv6 = is_ipv6;
                dst_ip_doc.first_time = now;
                dst_ip_doc.last_time = now;
                dst_ip_doc.tx_flow = record.bytes_ba;
                dst_ip_doc.rx_flow = record.bytes_ab;
                dst_ip_doc.tx_packets = record.packets_ba;
                dst_ip_doc.rx_packets = record.packets_ab;

                if (parsed.protocol == 6) {
                    dst_ip_doc.tcp_packets = 1;
                } else if (parsed.protocol == 17) {
                    dst_ip_doc.udp_packets = 1;
                }

                if (parsed.dst_port == 80 || parsed.src_port == 80) {
                    dst_ip_doc.http_packets = 1;
                } else if (parsed.dst_port == 53 || parsed.src_port == 53) {
                    dst_ip_doc.dns_packets = 1;
                }

                virtual_collection_update_ip(g_storage_engine->vc, &dst_ip_doc);
            }
        }
    }

    /* 3. 协议解析引擎处理 */
    if (session && g_engine_registry) {
        /* DEBUG: 打印 DNS 和 HTTP 包信息 */
        if (parsed.protocol == 17 && (parsed.dst_port == 53 || parsed.src_port == 53)) {
            TM_LOG_DEBUG("MAIN", "DNS 包: src_port=%u, dst_port=%u, payload_len=%u",
                   parsed.src_port, parsed.dst_port, parsed.payload_len);
        }
        if (parsed.protocol == 6 && (parsed.dst_port == 80 || parsed.src_port == 80)) {
            TM_LOG_DEBUG("MAIN", "HTTP 包: src_port=%u, dst_port=%u, payload_len=%u",
                   parsed.src_port, parsed.dst_port, parsed.payload_len);
        }
        engine_process(g_engine_registry, session, &parsed);

        /* TLS 证书流重组：仅处理来自 443 端口的服务端数据，重组跨包的 Certificate 记录 */
        if (parsed.protocol == 6 && parsed.payload && parsed.payload_len > 0 &&
            parsed.src_port == 443 &&
            g_cert_engine_ctx && g_cert_process_stream) {

            /* 取出或创建该会话的流重组缓冲区。
             * 缓冲区挂在 session->tls_stream_buf 专属字段上，与 ext_data 槽位
             * 完全解耦：即便 engine_tls_infra / SSH / WireGuard 等插件已经
             * 占用了 ext_type/ext_data，证书流重组仍可独立进行。
             * 该字段的释放由 session 模块在 session 过期 / 销毁时统一处理。 */
            tls_stream_buf_t *sbuf = (tls_stream_buf_t *)session->tls_stream_buf;
            if (!sbuf) {
                sbuf = calloc(1, sizeof(tls_stream_buf_t));
                if (sbuf) {
                    session->tls_stream_buf = sbuf;
                }
            }

            if (sbuf) {
                /* 缓冲区为空时：只有以 0x16 开头的包才开始积累 */
                if (sbuf->len == 0 && parsed.payload[0] != 0x16) {
                    goto skip_cert;
                }

                /* 追加新数据，超出缓冲区则重置后重新开始 */
                if (sbuf->len + parsed.payload_len > TLS_STREAM_BUF_SIZE) {
                    sbuf->len = 0;
                    if (parsed.payload[0] != 0x16) goto skip_cert;
                }
                memcpy(sbuf->buf + sbuf->len, parsed.payload, parsed.payload_len);
                sbuf->len += parsed.payload_len;

                /* 扫描缓冲区中所有完整的 TLS 记录 */
                uint32_t offset = 0;
                while (offset + 5 <= sbuf->len && sbuf->buf[offset] == 0x16) {
                    uint16_t tls_record_len = (sbuf->buf[offset + 3] << 8) |
                                               sbuf->buf[offset + 4];

                    /* 记录不完整，等待后续包 */
                    if (offset + 5 + tls_record_len > sbuf->len) {
                        break;
                    }

                    uint8_t handshake_type = sbuf->buf[offset + 5];
                    if (handshake_type == 0x0b) {
                        parsed_packet_t cert_pkt = {
                            .payload     = sbuf->buf + offset,
                            .payload_len = tls_record_len + 5,
                            .src_ip      = parsed.src_ip,
                            .src_port    = parsed.src_port,
                            .dst_ip      = parsed.dst_ip,
                            .dst_port    = parsed.dst_port,
                            .protocol    = parsed.protocol
                        };
                        g_cert_process_stream(g_cert_engine_ctx, &cert_pkt);
                    }

                    offset += 5 + tls_record_len;
                }

                /* 移除已处理的数据，保留尚未完整的尾部 */
                if (offset > 0) {
                    sbuf->len -= offset;
                    if (sbuf->len > 0) {
                        memmove(sbuf->buf, sbuf->buf + offset, sbuf->len);
                    }
                }
            }
            skip_cert:;
        }
    }

    engine_log_flush();

    /* 4. 定期提交统计信息 */
    if (g_storage_engine && (now - g_last_stats_time) >= config->stats_interval) {
        capture_stats_t cap_stats;

        /* 获取抓包统计 */
        if (capture_get_stats(g_capture, &cap_stats) == 0) {
            system_stats_t sys_stats = {0};

            sys_stats.timestamp = now;
            sys_stats.rx_packets = cap_stats.rx_packets;
            sys_stats.rx_bytes = cap_stats.rx_bytes;
            sys_stats.rx_dropped = cap_stats.rx_dropped;
            sys_stats.active_sessions = session_get_count(g_session_mgr);

            /* 计算速率 */
            time_t elapsed = now - g_start_time;
            if (elapsed > 0) {
                sys_stats.pps = (double)g_packet_count / elapsed;
                sys_stats.bps = (double)g_byte_count * 8 / elapsed;
            }

            strncpy(sys_stats.backend, capture_get_backend_name(g_capture),
                   sizeof(sys_stats.backend) - 1);

            /* 提交统计 */
            storage_engine_submit_system(g_storage_engine, &sys_stats);

            /* 提交协议统计 */
            if (g_engine_registry) {
                /* 直接使用虚拟集合更新协议统计 */
                for (uint32_t i = 0; i < g_engine_registry->engine_count; i++) {
                    engine_entry_t *engine = g_engine_registry->engines[i];
                    if (!engine || !engine->ops) continue;

                    protocol_doc_t proto_doc = {0};
                    strncpy(proto_doc.protocol_name, engine->ops->name,
                           sizeof(proto_doc.protocol_name) - 1);
                    proto_doc.protocol_id = engine->ops->protocol_id;
                    proto_doc.first_time = g_start_time;
                    proto_doc.last_time = now;
                    proto_doc.packets_processed = engine->ctx.stats.packets_processed;
                    proto_doc.packets_matched = engine->ctx.stats.packets_matched;
                    proto_doc.bytes_processed = 0;  /* engine_stats 没有此字段 */
                    proto_doc.sessions_created = engine->ctx.stats.sessions_created;
                    proto_doc.errors = engine->ctx.stats.errors;

                    virtual_collection_update_protocol(g_storage_engine->vc, &proto_doc);
                }
            }
        }

        g_last_stats_time = now;
    }

    /* 5. 定期刷盘和容量管理 (每小时) */
    if (g_storage_engine && (now - g_last_flush_time) >= 3600) {
        TM_LOG_INFO("MAIN", "===== 开始定期刷盘 =====");

        /* 批量保存到数据库 */
        int saved = storage_engine_flush_all(g_storage_engine);
        TM_LOG_INFO("MAIN", "已保存 %d 条记录到数据库", saved);

        /* 容量管理 */
        arrange_all_collections(g_storage_engine->vc);

        /* 打印统计信息 */
        storage_engine_print_stats(g_storage_engine);
        print_capacity_info(g_storage_engine->vc);

        TM_LOG_INFO("MAIN", "===== 刷盘完成 =====");

        g_last_flush_time = now;
    }

    /* 6. 周期性清理过期会话（单线程模式，每 30s 一次） */
    if (g_session_mgr && (now - g_last_session_flush) >= 30) {
        int expired = session_flush_expired(g_session_mgr);
        if (expired > 0) {
            TM_LOG_INFO("SESSION", "清理 %d 个过期会话（当前活跃: %u）",
                   expired, session_get_count(g_session_mgr));
        }
        g_last_session_flush = now;
    }

    /* 检查运行标志 */
    if (!g_running) {
        return 1;  /* 停止捕获 */
    }
    
    return 0;  /* 继续处理 */
}

/*============================================================================
 *                        多核流水线 lcore 函数（DPDK 模式）
 *============================================================================*/

#ifdef CAPTURE_ENABLE_DPDK

/**
 * @brief 构建 ip_flow_doc_t（供 worker 预填充，发给 storage lcore）
 */
static void fill_ip_doc(ip_flow_doc_t *doc, const char *ip_str,
                        uint64_t tx_flow, uint64_t rx_flow,
                        uint64_t tx_pkts, uint64_t rx_pkts,
                        uint8_t protocol, uint16_t src_port, uint16_t dst_port,
                        time_t now, bool is_ipv6)
{
    memset(doc, 0, sizeof(ip_flow_doc_t));
    strncpy(doc->ip, ip_str, sizeof(doc->ip) - 1);
    doc->is_ipv6    = is_ipv6;
    doc->first_time = now;
    doc->last_time  = now;
    doc->tx_flow    = tx_flow;
    doc->rx_flow    = rx_flow;
    doc->tx_packets = tx_pkts;
    doc->rx_packets = rx_pkts;
    if (protocol == 6)        doc->tcp_packets = 1;
    else if (protocol == 17)  doc->udp_packets = 1;
    if (src_port == 80 || dst_port == 80)   doc->http_packets  = 1;
    else if (src_port == 53 || dst_port == 53) doc->dns_packets = 1;
}

static void load_plugins_to_registry(engine_registry_t *reg);  /* 前向声明 */

/**
 * @brief 初始化流水线 ring、mempool 及每 worker 资源
 */
static int init_pipeline_rings(const app_config_t *config)
{
    /* storage ring：多 worker(MP) → 1 storage lcore(SC) */
    g_storage_ring = rte_ring_create("storage_ring", STORAGE_RING_SIZE,
                                     rte_socket_id(), RING_F_SC_DEQ);
    if (!g_storage_ring) {
        TM_LOG_ERROR("PIPELINE", "创建 storage_ring 失败");
        return -1;
    }

    /* storage task mempool */
    g_storage_task_pool = rte_mempool_create(
        "storage_task_pool",
        STORAGE_POOL_SIZE,
        sizeof(storage_task_t),
        STORAGE_POOL_CACHE,
        0, NULL, NULL, NULL, NULL,
        rte_socket_id(), 0);
    if (!g_storage_task_pool) {
        TM_LOG_ERROR("PIPELINE", "创建 storage_task_pool 失败");
        return -1;
    }

    /* 每 worker 独立会话表（分片 = 总表大小 / g_nb_workers）*/
    for (int i = 0; i < g_nb_workers; i++) {
        g_worker_sessions[i] = session_manager_create(
            config->session_table_size / g_nb_workers,
            config->session_timeout);
        if (!g_worker_sessions[i]) {
            TM_LOG_ERROR("PIPELINE", "创建 worker[%d] 会话表失败", i);
            return -1;
        }
    }

    /* 每 worker 独立引擎链 */
    for (int i = 0; i < g_nb_workers; i++) {
        g_worker_engines[i] = engine_registry_create();
        if (!g_worker_engines[i]) {
            TM_LOG_ERROR("PIPELINE", "创建 worker[%d] 引擎注册中心失败", i);
            return -1;
        }
        load_plugins_to_registry(g_worker_engines[i]);
    }

    g_pipeline_mode = 1;
    TM_LOG_INFO("PIPELINE", "流水线资源初始化完成");
    TM_LOG_INFO("PIPELINE", "  workers: %d (各自直接轮询 NIC queue 0..%d)",
           g_nb_workers, g_nb_workers - 1);
    TM_LOG_INFO("PIPELINE", "  storage_ring: size=%d", STORAGE_RING_SIZE);
    TM_LOG_INFO("PIPELINE", "  storage_task_pool: %d elements", STORAGE_POOL_SIZE);

    /* event ring：worker → storage lcore（协议事件异步写 ClickHouse）*/
    g_event_ring = rte_ring_create("event_ring", EVENT_RING_SIZE,
                                   rte_socket_id(), RING_F_SC_DEQ);
    if (!g_event_ring) {
        TM_LOG_ERROR("PIPELINE", "创建 event_ring 失败");
        return -1;
    }

#ifdef __SANITIZE_ADDRESS__
    unsigned event_pool_sz = 4096;
#else
    unsigned event_pool_sz = EVENT_POOL_SIZE;
#endif
    g_event_task_pool = rte_mempool_create(
        "event_task_pool",
        event_pool_sz,
        sizeof(event_task_t),
        EVENT_POOL_CACHE,
        0, NULL, NULL, NULL, NULL,
        rte_socket_id(), 0);
    if (!g_event_task_pool) {
        TM_LOG_ERROR("PIPELINE", "创建 event_task_pool 失败");
        return -1;
    }

    /* 将 ring/pool 注入 ch_event 模块，插件侧即可使用 */
    ch_event_init(g_event_ring, g_event_task_pool);
    TM_LOG_INFO("PIPELINE", "  event_ring: size=%d, pool=%u elements",
                EVENT_RING_SIZE, event_pool_sz);

    /* log ring：worker → 异步日志线程（锁无关 dispatch，避免 mutex 阻塞热路径）*/
    {
        g_log_ring = rte_ring_create("log_ring", LOG_RING_SIZE,
                                     rte_socket_id(), RING_F_SC_DEQ);
        if (!g_log_ring) {
            TM_LOG_WARN("PIPELINE", "创建 log_ring 失败，日志降级为 mutex 队列");
        }
        unsigned log_pool_sz = LOG_POOL_SIZE;
        g_log_pool = rte_mempool_create(
            "log_pool",
            log_pool_sz,
            TM_LOG_MSG_SZ,
            LOG_POOL_CACHE,
            0, NULL, NULL, NULL, NULL,
            rte_socket_id(), 0);
        if (!g_log_pool) {
            TM_LOG_WARN("PIPELINE", "创建 log_pool 失败，日志降级为 mutex 队列");
            if (g_log_ring) {
                rte_ring_free(g_log_ring);
                g_log_ring = NULL;
            }
        }
        if (g_log_ring && g_log_pool) {
            tm_log_set_dpdk_ring(g_log_ring, g_log_pool);
            TM_LOG_INFO("PIPELINE", "  log_ring: size=%d, pool=%u elements",
                        LOG_RING_SIZE, log_pool_sz);
        }
    }

    return 0;
}

/**
 * @brief lcore 1（原抓包线程）— 已被多队列 RSS 架构替代，保留备用
 *
 * 新架构中每个 worker lcore 直接轮询各自的 NIC 队列，此函数不再启动。
 */
static __attribute__((unused)) int capture_lcore_main(void *arg)
{
    (void)arg;
    struct rte_mbuf *mbufs[PIPELINE_BURST_SIZE];
    uint32_t lcore_id = rte_lcore_id();

    TM_LOG_INFO("LCORE", "[LCORE%u] 抓包线程启动", lcore_id);

    while (g_running) {
        int nb_rx = capture_dpdk_recv_raw(g_capture, 0, mbufs, PIPELINE_BURST_SIZE);
        if (nb_rx == 0) {
            rte_pause();
            continue;
        }

        __atomic_fetch_add(&g_packet_count, nb_rx, __ATOMIC_RELAXED);

        for (int i = 0; i < nb_rx; i++) {
            struct rte_mbuf *m = mbufs[i];
            const uint8_t *data = rte_pktmbuf_mtod(m, const uint8_t *);
            uint32_t pkt_len = rte_pktmbuf_data_len(m);

            __atomic_fetch_add(&g_byte_count, pkt_len, __ATOMIC_RELAXED);

            /* 预取下一包头，减少 cache miss */
            if (i + 1 < nb_rx)
                rte_prefetch0(rte_pktmbuf_mtod(mbufs[i + 1], void *));

            /* 解析以太网类型，支持 VLAN 标签剥离（802.1Q/802.1ad） */
            if (pkt_len < 14) {
                rte_pktmbuf_free(m);
                __atomic_fetch_add(&g_drop_count, 1, __ATOMIC_RELAXED);
                continue;
            }
            uint16_t eth_type = ((uint16_t)data[12] << 8) | data[13];
            uint32_t l3_offset = 14;

            /* 剥离最多两层 VLAN 标签 (QinQ = 0x88A8 / 0x8100) */
            while ((eth_type == 0x8100 || eth_type == 0x88A8) &&
                   pkt_len >= l3_offset + 4) {
                eth_type  = ((uint16_t)data[l3_offset + 2] << 8) | data[l3_offset + 3];
                l3_offset += 4;
            }

            /* 处理 IPv4 和 IPv6 */
            if (eth_type == 0x0800) {
                /* IPv4 */
                if (pkt_len < l3_offset + 20) {
                    rte_pktmbuf_free(m);
                    __atomic_fetch_add(&g_drop_count, 1, __ATOMIC_RELAXED);
                    continue;
                }
                uint8_t proto = data[l3_offset + 9];
                if (proto != 6 && proto != 17) {
                    rte_pktmbuf_free(m);
                    __atomic_fetch_add(&g_drop_count, 1, __ATOMIC_RELAXED);
                    continue;
                }
                uint8_t ihl = (data[l3_offset] & 0x0F) * 4;
                if (pkt_len < l3_offset + ihl + 4) {
                    rte_pktmbuf_free(m);
                    __atomic_fetch_add(&g_drop_count, 1, __ATOMIC_RELAXED);
                    continue;
                }
                uint32_t src_ip = ((uint32_t)data[l3_offset + 12] << 24) |
                                  ((uint32_t)data[l3_offset + 13] << 16) |
                                  ((uint32_t)data[l3_offset + 14] <<  8) |
                                   (uint32_t)data[l3_offset + 15];
                uint32_t dst_ip = ((uint32_t)data[l3_offset + 16] << 24) |
                                  ((uint32_t)data[l3_offset + 17] << 16) |
                                  ((uint32_t)data[l3_offset + 18] <<  8) |
                                   (uint32_t)data[l3_offset + 19];
                uint32_t l4_off = l3_offset + ihl;
                uint16_t src_port = ((uint16_t)data[l4_off]     << 8) | data[l4_off + 1];
                uint16_t dst_port = ((uint16_t)data[l4_off + 2] << 8) | data[l4_off + 3];

                uint32_t wid __attribute__((unused)) =
                    compute_flow_hash(src_ip, dst_ip, src_port, dst_port, proto);
                /* 新架构：worker 直接轮询各自队列，无需 ring 分发，直接释放 */
                rte_pktmbuf_free(m);

            } else if (eth_type == 0x86DD) {
                /* IPv6：固定头 40 字节 */
                if (pkt_len < l3_offset + 40) {
                    rte_pktmbuf_free(m);
                    __atomic_fetch_add(&g_drop_count, 1, __ATOMIC_RELAXED);
                    continue;
                }
                uint8_t proto = data[l3_offset + 6];  /* Next Header */
                if (proto != 6 && proto != 17) {
                    rte_pktmbuf_free(m);
                    __atomic_fetch_add(&g_drop_count, 1, __ATOMIC_RELAXED);
                    continue;
                }
                uint32_t l4_off = l3_offset + 40;
                if (pkt_len < l4_off + 4) {
                    rte_pktmbuf_free(m);
                    __atomic_fetch_add(&g_drop_count, 1, __ATOMIC_RELAXED);
                    continue;
                }
                /* 用 src/dst IPv6 后 4 字节计算流哈希，保证同流路由到同一 worker */
                uint32_t src_last4, dst_last4;
                memcpy(&src_last4, data + l3_offset + 20, 4);  /* src addr [12..15] */
                memcpy(&dst_last4, data + l3_offset + 36, 4);  /* dst addr [12..15] */
                uint16_t src_port = ((uint16_t)data[l4_off]     << 8) | data[l4_off + 1];
                uint16_t dst_port = ((uint16_t)data[l4_off + 2] << 8) | data[l4_off + 3];

                uint32_t wid __attribute__((unused)) =
                    compute_flow_hash(src_last4, dst_last4, src_port, dst_port, proto);
                /* 新架构：worker 直接轮询各自队列，无需 ring 分发，直接释放 */
                rte_pktmbuf_free(m);

            } else {
                /* 其他协议丢弃 */
                rte_pktmbuf_free(m);
                __atomic_fetch_add(&g_drop_count, 1, __ATOMIC_RELAXED);
                continue;
            }
        }
    }

    TM_LOG_INFO("LCORE", "[LCORE%u] 抓包线程退出", lcore_id);
    return 0;
}

/**
 * @brief lcore 2/3：解析 worker 线程
 *
 * 从私有 worker ring 取包 → parse_packet → session → engine_process
 * → 构建 storage_task_t → 放入 storage ring。
 * 关闭时排空 ring 再退出（graceful drain）。
 */
static int worker_lcore_main(void *arg)
{
    worker_lcore_args_t *wargs = (worker_lcore_args_t *)arg;
    int wid = wargs->worker_id;
    uint16_t queue_id = wargs->queue_id;   /* 直接轮询此 NIC 队列 */
    uint32_t lcore_id = rte_lcore_id();

    session_manager_t *my_session = g_worker_sessions[wid];
    engine_registry_t *my_engine  = g_worker_engines[wid];

    struct rte_mbuf *mbufs[PIPELINE_BURST_SIZE];
    uint32_t flush_cursor = 0;   /* 过期清扫游标：跨 poll 迭代续扫全表，见 worker_periodic */

    TM_LOG_INFO("LCORE", "[LCORE%u] Worker %d 启动，绑定 NIC queue %u", lcore_id, wid, queue_id);

    while (g_running) {
        /* 直接从 NIC 硬件队列收包（RSS 保证同流始终进入同一队列）*/
        int nb_rx = capture_dpdk_recv_raw(g_capture, queue_id,
                                          mbufs, PIPELINE_BURST_SIZE);
        if (nb_rx == 0) {
            rte_pause();
            goto worker_periodic;
        }

        __atomic_fetch_add(&g_packet_count, nb_rx, __ATOMIC_RELAXED);
        __atomic_fetch_add(&g_worker_rx[wid], nb_rx, __ATOMIC_RELAXED);
        struct timespec ts;
        clock_gettime(CLOCK_MONOTONIC, &ts);
        time_t now = ts.tv_sec;

        for (int i = 0; i < nb_rx; i++) {
            struct rte_mbuf *m = mbufs[i];

            __atomic_fetch_add(&g_byte_count, rte_pktmbuf_data_len(m), __ATOMIC_RELAXED);

            /* 构建 capture_packet_t 供 parse_packet 使用 */
            capture_packet_t cap_pkt;
            memset(&cap_pkt, 0, sizeof(cap_pkt));
            cap_pkt.data          = rte_pktmbuf_mtod(m, const uint8_t *);
            cap_pkt.caplen        = rte_pktmbuf_data_len(m);
            cap_pkt.pktlen        = rte_pktmbuf_pkt_len(m);
            cap_pkt.timestamp_sec = now;

            parsed_packet_t parsed;
            memset(&parsed, 0, sizeof(parsed));
            if (parse_packet(&cap_pkt, &parsed) < 0) {
                rte_pktmbuf_free(m);
                continue;
            }

            /* 会话管理（无锁：每 worker 独立会话表）*/
            session_entry_t *session = NULL;
            if (parsed.protocol == 6 || parsed.protocol == 17) {
                if (parsed.ip_version == 6) {
                    session = session_lookup_v6(my_session,
                                               parsed.src_ip6, parsed.dst_ip6,
                                               parsed.src_port, parsed.dst_port,
                                               parsed.protocol);
                    if (!session) {
                        session = session_create_v6(my_session,
                                                   parsed.src_ip6, parsed.dst_ip6,
                                                   parsed.src_port, parsed.dst_port,
                                                   parsed.protocol);
                    }
                    if (session)
                        session_update_v6(session, parsed.src_ip6, parsed.dst_ip6,
                                          cap_pkt.caplen);
                } else {
                    session = session_lookup(my_session,
                                            parsed.src_ip, parsed.dst_ip,
                                            parsed.src_port, parsed.dst_port,
                                            parsed.protocol);
                    if (!session) {
                        session = session_create(my_session,
                                                parsed.src_ip, parsed.dst_ip,
                                                parsed.src_port, parsed.dst_port,
                                                parsed.protocol);
                    }
                    if (session)
                        session_update(session, parsed.src_ip, parsed.dst_ip,
                                       cap_pkt.caplen);
                }
            }

            /* 协议引擎处理（读锁保护，管理线程可能热加载插件）*/
            if (session && my_engine) {
                engine_process(my_engine, session, &parsed);
            }

            /* 构建 storage_task_t 并发送到存储 ring */
            if (g_storage_engine && session) {
                storage_task_t *task = NULL;
                if (rte_mempool_get(g_storage_task_pool, (void **)&task) == 0) {
                    memset(task, 0, sizeof(*task));
                    session_to_record(session, &task->record);
                    task->has_session = true;
                    task->protocol    = parsed.protocol;
                    task->src_port    = parsed.src_port;
                    task->dst_port    = parsed.dst_port;

                    /* 源 IP 文档：tx=A→B, rx=B→A */
                    fill_ip_doc(&task->src_ip_doc, task->record.src_ip,
                                task->record.bytes_ab,  task->record.bytes_ba,
                                task->record.packets_ab, task->record.packets_ba,
                                parsed.protocol, parsed.src_port, parsed.dst_port,
                                now, (parsed.ip_version == 6));

                    /* 目标 IP 文档：tx=B→A, rx=A→B */
                    fill_ip_doc(&task->dst_ip_doc, task->record.dst_ip,
                                task->record.bytes_ba,  task->record.bytes_ab,
                                task->record.packets_ba, task->record.packets_ab,
                                parsed.protocol, parsed.src_port, parsed.dst_port,
                                now, (parsed.ip_version == 6));

                    if (rte_ring_enqueue(g_storage_ring, task) != 0) {
                        rte_mempool_put(g_storage_task_pool, task); /* ring 满回收 */
                    }
                }
            }

            rte_pktmbuf_free(m);
        }

    worker_periodic:
        /* 过期会话清扫：游标式限额摊销（P1 去内联化）。每轮 poll 只清一小片、时间盒
         * SWEEP_TIME_BUDGET_US 硬性封顶、跨迭代 flush_cursor 续扫全表。取代旧版"每30s内联
         * 全量清扫"——后者在单 worker 独扛百万会话时一次跑 2.5-3.5min 不 poll，导致 NIC 队列
         * 无人排空、imissed 暴涨的 RX 黑洞。idle 分支(nb_rx==0)也会走到这里，正好用空闲清扫。*/
        session_flush_expired_amortized(my_session, &flush_cursor,
                                        SWEEP_BUCKET_SCAN_CAP, SWEEP_TIME_BUDGET_US);
    }

    TM_LOG_INFO("LCORE", "[LCORE%u] Worker %d 退出 (活跃会话: %u)",
           lcore_id, wid, session_get_count(my_session));
    return 0;
}

/**
 * @brief lcore 4：存储线程
 *
 * 消费 storage_ring 中的 storage_task_t，更新虚拟集合。
 * 定期聚合协议统计、刷盘到 MongoDB。
 * 关闭时排空 ring、执行最终刷盘。
 */
static int storage_lcore_main(void *arg)
{
    (void)arg;
    uint32_t lcore_id = rte_lcore_id();
    storage_task_t *tasks[PIPELINE_BURST_SIZE];
    event_task_t   *evs[EVENT_BATCH_MAX];   /* 上限须与下方自适应 burst 的钳位一致 */
    time_t last_stats_time  = time(NULL);
    time_t last_flush_time  = time(NULL);
    time_t last_event_flush = time(NULL);
    time_t last_evt_table_flush = time(NULL); /* 事件型集合近实时刷库定时器 */

    TM_LOG_INFO("LCORE", "[LCORE%u] 存储线程启动", lcore_id);

    /* 初始化批量写 ClickHouse 模块（持久 curl handle）*/
    if (g_storage_engine) {
        char ch_url[512];
        snprintf(ch_url, sizeof(ch_url), "http://%s:%d",
                 g_storage_engine->ch_host, g_storage_engine->ch_port);
        if (event_batch_init(ch_url, g_storage_engine->db_name,
                             g_storage_engine->ch_user,
                             g_storage_engine->ch_password) != 0) {
            TM_LOG_WARN("LCORE", "[LCORE%u] event_batch_init 失败，协议事件将被丢弃", lcore_id);
        }

        /* Initialize engine log flush */
        engine_log_init(ch_url, g_storage_engine->db_name,
                        g_storage_engine->ch_user,
                        g_storage_engine->ch_password);
        engine_log_init_http();
    }

    while (g_running || !rte_ring_empty(g_storage_ring)
                     || (g_event_ring && !rte_ring_empty(g_event_ring))) {

        /* ── 1. 消费 storage ring（会话/IP 文档）── */
        unsigned nb_deq = rte_ring_dequeue_burst(g_storage_ring,
                                                  (void **)tasks,
                                                  PIPELINE_BURST_SIZE, NULL);
        if (nb_deq == 0 && !g_running
            && (g_event_ring && rte_ring_empty(g_event_ring)))
            break;

        for (unsigned i = 0; i < nb_deq; i++) {
            storage_task_t *task = tasks[i];
            if (!task) continue;

            if (g_storage_engine && task->has_session) {
                storage_engine_submit_session(g_storage_engine, &task->record);
                virtual_collection_update_ip(g_storage_engine->vc, &task->src_ip_doc);
                virtual_collection_update_ip(g_storage_engine->vc, &task->dst_ip_doc);
            }
            rte_mempool_put(g_storage_task_pool, task);
        }

        /* ── 2. 消费 event ring（协议事件批量写 ClickHouse）── */
        if (g_event_ring) {
            /* 批量自适应：ring 积压越深单轮出队越多，过载时加速排空，
             * 避免 65536 深的 ring 每轮只排 256 条。burst 钳位在
             * [EVENT_BATCH_SIZE, EVENT_BATCH_MAX]，上限与 evs[] 大小一致，不会越界。 */
            unsigned ev_burst = rte_ring_count(g_event_ring);
            if (ev_burst < EVENT_BATCH_SIZE)      ev_burst = EVENT_BATCH_SIZE;
            else if (ev_burst > EVENT_BATCH_MAX)  ev_burst = EVENT_BATCH_MAX;
            unsigned nb_ev = rte_ring_dequeue_burst(g_event_ring,
                                                     (void **)evs,
                                                     ev_burst, NULL);
            if (nb_ev > 0) {
                event_batch_flush(evs, nb_ev);
                for (unsigned i = 0; i < nb_ev; i++) {
                    if (evs[i]) rte_mempool_put(g_event_task_pool, evs[i]);
                }
                last_event_flush = time(NULL);
            } else {
                /* 超时兜底：低流量时防止事件在 ring 里积压过久 */
                time_t now_ev = time(NULL);
                if (now_ev - last_event_flush >= EVENT_FLUSH_INTERVAL_S) {
                    last_event_flush = now_ev;
                }
            }
        }

        /* ── 3. 消费 engine log ring（引擎日志批量写 ClickHouse）── */
        engine_log_flush();

        if (nb_deq == 0) {
            if (!g_running) break;
            rte_pause();
            goto storage_periodic;
        }

    storage_periodic:
        {
            time_t now = time(NULL);

            /* 每 10s 聚合各 worker 的协议统计：
             * 先在读锁下快速复制 stats，立刻释放锁，
             * 再在锁外调用 virtual_collection_update_protocol（可能阻塞）。 */
            if (g_storage_engine && (now - last_stats_time) >= 10) {
                /* 临时缓冲：每 worker 最多 ENGINE_MAX_ENGINES 个协议条目 */
                typedef struct { char name[ENGINE_NAME_MAX_LEN]; uint32_t id;
                                 uint64_t processed, matched, created, errors; } proto_snap_t;
                proto_snap_t snaps[16 * ENGINE_MAX_ENGINES];
                int nsnap = 0;

                for (int w = 0; w < g_nb_workers; w++) {
                    if (!g_worker_engines[w]) continue;
                    pthread_rwlock_rdlock(&g_worker_engines[w]->registry_lock);
                    for (uint32_t e = 0; e < g_worker_engines[w]->engine_count &&
                                        nsnap < 16 * (int)ENGINE_MAX_ENGINES; e++) {
                        engine_entry_t *eng = g_worker_engines[w]->engines[e];
                        if (!eng || !eng->ops) continue;
                        proto_snap_t *s = &snaps[nsnap++];
                        strncpy(s->name, eng->ops->name, ENGINE_NAME_MAX_LEN - 1);
                        s->name[ENGINE_NAME_MAX_LEN - 1] = '\0';
                        s->id       = eng->ops->protocol_id;
                        s->processed = eng->ctx.stats.packets_processed;
                        s->matched   = eng->ctx.stats.packets_matched;
                        s->created   = eng->ctx.stats.sessions_created;
                        s->errors    = eng->ctx.stats.errors;
                    }
                    pthread_rwlock_unlock(&g_worker_engines[w]->registry_lock);
                }

                /* 锁外更新虚拟集合 */
                for (int k = 0; k < nsnap; k++) {
                    protocol_doc_t proto_doc;
                    memset(&proto_doc, 0, sizeof(proto_doc));
                    strncpy(proto_doc.protocol_name, snaps[k].name,
                            sizeof(proto_doc.protocol_name) - 1);
                    proto_doc.protocol_id       = snaps[k].id;
                    proto_doc.first_time        = g_start_time;
                    proto_doc.last_time         = now;
                    proto_doc.packets_processed = snaps[k].processed;
                    proto_doc.packets_matched   = snaps[k].matched;
                    proto_doc.sessions_created  = snaps[k].created;
                    proto_doc.errors            = snaps[k].errors;
                    virtual_collection_update_protocol(g_storage_engine->vc, &proto_doc);
                }
                last_stats_time = now;
            }

            /* 事件型集合(翻墙裁决/可疑信号/隧道信号)按事件追加,需近实时落库。
             * 每 30s 刷一次:get_pending 只取本周期 new_data 增量,ClickHouse 写入
             * 成功后即从内存集合移除(见 virtual_collection_mark_*_synced),内存恒定。
             * 聚合型 ip/session/protocol 行数随刷新次数增长,仍走下面 3600s 全量刷。 */
            if (g_storage_engine && (now - last_evt_table_flush) >= 30) {
                storage_engine_flush_verdict_collection(g_storage_engine);
                storage_engine_flush_tunnel_signal_collection(g_storage_engine);
                storage_engine_flush_auxiliary_signal_collection(g_storage_engine);

                /* IP画像(profile_key=IP+日期 聚合)落库到 MongoDB ip_profiles_daily。
                 * 使用 mongo_storage 内部为画像单开的独立 client(仅本 storage 线程调用,
                 * 与 worker 线程的 g_client 隔离,避免 libmongoc 并发崩溃)。
                 * 集合内存按 profile_key 持续合并,落库后保留并标记已同步;后续再合并会
                 * 重新置 new_data 并 upsert(replace_one 幂等)。此前该桥接缺失,页面恒空。 */
                {
                    char **pkeys = NULL; size_t pcount = 0;
                    if (virtual_collection_get_pending_students(g_storage_engine->vc, &pkeys, &pcount) == 0
                        && pkeys) {
                        for (size_t pi = 0; pi < pcount; pi++) {
                            /* 锁内拷贝画像快照并标记已同步,锁外再 upsert,避免与 worker
                             * 线程原地合并画像产生数据竞争(此前并发访问导致 libbson 崩溃)。 */
                            ip_profile_doc_t prof_copy;
                            if (virtual_collection_copy_and_mark_student(
                                    g_storage_engine->vc, pkeys[pi], &prof_copy) == 0) {
                                /* 批量攒批:内部每满 1000 条自动执行一次 bulk upsert,
                                 * 把此前逐条一次的网络往返压成 1/1000,消除画像量大时
                                 * 30s flush 的长阻塞(曾饿死事件排空致 lag 爬升)。 */
                                mongo_bulk_upsert_ip_profile_add(&prof_copy);
                            }
                            free(pkeys[pi]);
                        }
                        free(pkeys);
                        /* 收尾执行不足一批的剩余画像 */
                        mongo_bulk_upsert_ip_profile_flush();
                    }
                }
                last_evt_table_flush = now;
            }

            /* 每 3600s 刷盘 */
            if (g_storage_engine && (now - last_flush_time) >= 3600) {
                TM_LOG_INFO("LCORE", "[LCORE%u] 定期刷盘到数据库...", lcore_id);
                storage_engine_flush_all(g_storage_engine);
                arrange_all_collections(g_storage_engine->vc);
                storage_engine_print_stats(g_storage_engine);
                print_capacity_info(g_storage_engine->vc);
                last_flush_time = now;
            }
        }
    }

    /* 关闭时最终刷盘 */
    if (g_storage_engine) {
        TM_LOG_INFO("LCORE", "[LCORE%u] 关闭时最终刷盘...", lcore_id);
        storage_engine_flush_all(g_storage_engine);
    }

    /* 关闭批量写模块 */
    event_batch_cleanup();

    /* 汇报 event ring 丢弃计数 */
    uint64_t ev_dropped = ch_event_get_dropped();
    if (ev_dropped > 0) {
        TM_LOG_WARN("LCORE", "[LCORE%u] event_ring 丢弃事件数: %"PRIu64, lcore_id, ev_dropped);
    }

    TM_LOG_INFO("LCORE", "[LCORE%u] 存储线程退出", lcore_id);
    return 0;
}

/**
 * @brief lcore 0：管理循环
 *
 * 负责：定期打印统计、等待 g_running 变为 0，
 * 然后通过 rte_eal_mp_wait_lcore() 等待所有 secondary lcore 退出。
 */
static void run_main_loop(uint32_t stats_interval)
{
    time_t last_print = time(NULL);

    TM_LOG_INFO("LCORE", "[LCORE0] 管理循环启动");

    while (g_running) {
        sleep(1);
        time_t now = time(NULL);

        if (now - last_print >= (time_t)stats_interval) {
            uint32_t total_sessions = 0;
            for (int w = 0; w < g_nb_workers; w++) {
                if (g_worker_sessions[w])
                    total_sessions += session_get_count(g_worker_sessions[w]);
            }
            /* verdict_skips = 无信号 verdict 写门控跳过数，观测门控效果 */
            TM_LOG_INFO("STATS", "packets=%" PRIu64 " bytes=%" PRIu64
                   " sessions=%u drops=%" PRIu64 " verdict_skips=%" PRIu64,
                   g_packet_count, g_byte_count, total_sessions, g_drop_count,
                   g_verdict_skip_count);

            if (g_capture) {
                capture_stats_t cap_stats;
                if (capture_get_stats(g_capture, &cap_stats) == 0) {
                    TM_LOG_INFO("STATS", "hw_rx=%" PRIu64 " hw_dropped=%" PRIu64,
                           cap_stats.rx_packets, cap_stats.rx_dropped);
                }
                /* RSS 验证：打印每队列包计数，确认 RSS 分流是否均匀 */
                capture_dpdk_print_queue_stats(g_capture);
                /* per-worker 真实收包（不依赖 ixgbe q_ipackets 是否支持）*/
                {
                    uint64_t wtotal = 0;
                    for (int w = 0; w < g_nb_workers; w++) wtotal += g_worker_rx[w];
                    TM_LOG_INFO("RSS", "Per-worker SW RX (total=%" PRIu64 "):", wtotal);
                    for (int w = 0; w < g_nb_workers; w++) {
                        double pct = wtotal > 0 ? 100.0 * g_worker_rx[w] / wtotal : 0.0;
                        TM_LOG_INFO("RSS", "  worker %d (queue %d): %" PRIu64 " pkts (%.1f%%)",
                               w, w, g_worker_rx[w], pct);
                    }
                }
            }
            last_print = now;
        }
    }

    TM_LOG_INFO("LCORE", "[LCORE0] 等待所有流水线 lcore 退出...");
    rte_eal_mp_wait_lcore();
    TM_LOG_INFO("LCORE", "[LCORE0] 所有流水线 lcore 已退出");
}

#endif /* CAPTURE_ENABLE_DPDK */

/*============================================================================
 *                              初始化函数
 *============================================================================*/

/**
 * @brief 初始化抓包模块
 */
static int init_capture(app_config_t *config)
{
    capture_config_t cap_config;

    TM_LOG_INFO("MAIN", "初始化抓包模块...");

    /* 初始化配置 */
    capture_config_init(&cap_config);

    cap_config.backend = config->backend;
    strncpy(cap_config.interface, config->interface, sizeof(cap_config.interface) - 1);
    cap_config.promiscuous = config->promiscuous;
    cap_config.snaplen = config->snaplen;

    if (strlen(config->bpf_filter) > 0) {
        strncpy(cap_config.bpf_filter, config->bpf_filter,
                sizeof(cap_config.bpf_filter) - 1);
    }

#ifdef CAPTURE_ENABLE_DPDK
    /* DPDK 多核流水线：动态构建 EAL 参数，支持 NUMA 绑定 */
    if (config->backend == CAPTURE_BACKEND_DPDK ||
        config->backend == CAPTURE_BACKEND_AUTO) {
        static char s_lcore_val[64];
        static char s_socket_val[64];
        static char *dpdk_eal_argv[12];   /* 最多: prog + --lcores val --socket-mem val --iova-mode va --proc-type=primary NULL */
        int eal_argc = 0;

        dpdk_eal_argv[eal_argc++] = "traffic_monitor";

        if (config->lcore_map[0]) {
            /* NUMA 绑定模式：--lcores 将逻辑 lcore ID 0-4 重映射到指定物理 CPU
             * 不加 --socket-mem / --no-numa：让 EAL 自动从已分配大页中按需取用
             * 脚本已在目标节点分配 512 页、其他节点分配 64 页（供 eth_dev_shared_data）*/
            dpdk_eal_argv[eal_argc++] = "--lcores";
            strncpy(s_lcore_val, config->lcore_map, sizeof(s_lcore_val) - 1);
            s_lcore_val[sizeof(s_lcore_val) - 1] = '\0';
            dpdk_eal_argv[eal_argc++] = s_lcore_val;
            TM_LOG_INFO("MAIN", "EAL --lcores %s", s_lcore_val);
        } else {
            /* 默认：lcore ID 0-{g_nb_workers+1} 直接映射到物理 CPU */
            static char lcore_range[32];
            snprintf(lcore_range, sizeof(lcore_range), "0-%d", g_nb_workers + 1);
            dpdk_eal_argv[eal_argc++] = "-l";
            dpdk_eal_argv[eal_argc++] = lcore_range;
            /* 非 NUMA 模式仍支持显式 --socket-mem（兼容旧用法）*/
            if (config->socket_mem[0]) {
                dpdk_eal_argv[eal_argc++] = "--socket-mem";
                strncpy(s_socket_val, config->socket_mem, sizeof(s_socket_val) - 1);
                s_socket_val[sizeof(s_socket_val) - 1] = '\0';
                dpdk_eal_argv[eal_argc++] = s_socket_val;
                TM_LOG_INFO("MAIN", "EAL --socket-mem %s", s_socket_val);
            }
        }

#ifdef __SANITIZE_ADDRESS__
        /* ASAN 改变虚拟地址空间布局，必须用 VA IOVA 模式才能正常 mmap hugepage */
        dpdk_eal_argv[eal_argc++] = "--iova-mode=va";
        TM_LOG_INFO("MAIN", "ASAN 模式：强制 --iova-mode=va");
#endif

        dpdk_eal_argv[eal_argc++] = "--proc-type=primary";
        dpdk_eal_argv[eal_argc]   = NULL;

        cap_config.argc = eal_argc;
        cap_config.argv = dpdk_eal_argv;

        /* RSS 多队列：队列数 = worker 数，硬件分流 */
        cap_config.rx_queues  = g_nb_workers;
        cap_config.rss_enable = (g_nb_workers > 1);
        /* 显式覆盖 rx_desc：capture.c 默认值 4096 太小，万兆网卡需 16384 */
        cap_config.rx_desc    = 16384;
        /* 显式覆盖 mbuf 池大小（capture_config_init 默认值 8192*16 不够用）*/
        cap_config.mbuf_count = 8192 * 64;  /* 524288 mbufs */
        TM_LOG_INFO("MAIN", "DPDK 多队列模式：rx_queues=%d, rss=%s, mbuf_count=%d",
               g_nb_workers, g_nb_workers > 1 ? "enabled" : "disabled",
               cap_config.mbuf_count);
    }
#endif

    /* 创建句柄 */
    g_capture = capture_create(&cap_config);
    if (!g_capture) {
        TM_LOG_ERROR("MAIN", "创建抓包句柄失败");
        return -1;
    }

    /* 打开接口 */
    if (capture_open(g_capture) < 0) {
        TM_LOG_ERROR("MAIN", "打开接口失败: %s",
                capture_get_error(g_capture));
        return -1;
    }

    TM_LOG_INFO("MAIN", "抓包模块初始化成功 (后端: %s, 接口: %s)",
           capture_get_backend_name(g_capture), config->interface);

    return 0;
}

/**
 * @brief 初始化会话管理模块
 */
static int init_session(app_config_t *config)
{
    TM_LOG_INFO("MAIN", "初始化会话管理模块...");

    g_session_mgr = session_manager_create(config->session_table_size,
                                          config->session_timeout);
    if (!g_session_mgr) {
        TM_LOG_ERROR("MAIN", "创建会话管理器失败");
        return -1;
    }

    TM_LOG_INFO("MAIN", "会话管理模块初始化成功 (表大小: %u, 超时: %u秒)",
           config->session_table_size, config->session_timeout);
    
    return 0;
}

/**
 * @brief 将所有协议解析插件加载到指定引擎注册中心
 */
static void load_plugins_to_registry(engine_registry_t *reg)
{
    if (g_no_plugins) {
        TM_LOG_INFO("MAIN", "插件加载已禁用 (--no-plugins)");
        return;
    }

    static const char *plugins[] = {
        /* 观测型 engine 前置：它们 return CONTINUE，统计完立即放行给后续协议 engine。
         * 必须放在 HTTP/TLS/DNS 等 "HANDLED 吃掉包" 的 engine 之前，
         * 否则永远收不到已被分类的流量双向包（bytes_ba 永远 0 就是这个原因）。 */
        "plugin/plugins/engine_geo_policy.so",

        "plugin/plugins/engine_http.so",
        "plugin/plugins/engine_https.so",
        "plugin/plugins/engine_tls.so",
        "plugin/plugins/engine_cert.so",
        "plugin/plugins/engine_ja3.so",

        "plugin/plugins/engine_dns.so",
        "plugin/plugins/engine_dtls.so",
        "plugin/plugins/engine_rdp.so",
        "plugin/plugins/engine_rtmp.so",
        "plugin/plugins/engine_smb.so",
        "plugin/plugins/engine_tls_infra.so",
        "plugin/plugins/engine_ti.so",
        "plugin/plugins/engine_ids.so",

        "plugin/plugins/engine_ftp.so",
        "plugin/plugins/engine_smtp.so",
        "plugin/plugins/engine_openvpn.so",
        "plugin/plugins/engine_ssh.so",
        "plugin/plugins/engine_wireguard.so",
        "plugin/plugins/engine_pop3.so",
        "plugin/plugins/engine_imap.so",
        "plugin/plugins/engine_quic.so",
        "plugin/plugins/engine_rtsp.so",
        "plugin/plugins/engine_ipsecvpn.so",
        "plugin/plugins/engine_v2ray.so",
        "plugin/plugins/engine_shadowsocks.so",

        "plugin/plugins/engine_auxiliary.so",
        "plugin/plugins/engine_tunnel.so",
        "plugin/plugins/engine_focus_site.so",
        "plugin/plugins/engine_site_ranking.so",
        NULL
    };

    for (int i = 0; plugins[i] != NULL; i++) {
        if (access(plugins[i], F_OK) == 0) {
            if (engine_registry_load_plugin(reg, plugins[i]) == 0) {
                TM_LOG_INFO("MAIN", "已加载插件: %s", plugins[i]);
            } else {
                TM_LOG_ERROR("MAIN", "加载插件失败: %s", plugins[i]);
            }
        } else {
            TM_LOG_WARN("MAIN", "插件文件不存在，跳过: %s", plugins[i]);
        }
    }
}

/**
 * @brief 初始化协议解析引擎
 */
static int init_engine(void)
{
    TM_LOG_INFO("MAIN", "初始化协议解析引擎...");

    g_engine_registry = engine_registry_create();
    if (!g_engine_registry) {
        TM_LOG_ERROR("MAIN", "创建引擎注册中心失败");
        return -1;
    }

    load_plugins_to_registry(g_engine_registry);

    /* 获取证书引擎上下文和处理函数（用于直接调用证书处理） */
    engine_entry_t *cert_entry = engine_registry_find(g_engine_registry, "Certificate_Extractor");
    if (cert_entry) {
        g_cert_engine_ctx = &cert_entry->ctx;

        /* 复用已加载的 .so handle（RTLD_NOLOAD：已加载则返回 handle，否则返回 NULL） */
        void *cert_handle = dlopen("plugin/plugins/engine_cert.so", RTLD_LAZY | RTLD_NOLOAD);
        if (!cert_handle) {
            cert_handle = dlopen("plugin/plugins/engine_cert.so", RTLD_LAZY | RTLD_GLOBAL);
        }

        if (cert_handle) {
            g_cert_process_stream = (cert_process_stream_fn)dlsym(cert_handle, "cert_engine_process_stream");
            if (g_cert_process_stream) {
                TM_LOG_INFO("MAIN", "已获取证书引擎上下文和处理函数");
            } else {
                TM_LOG_WARN("MAIN", "未找到证书处理函数: %s", dlerror());
            }
        } else {
            TM_LOG_WARN("MAIN", "无法打开证书插件: %s", dlerror());
        }
    } else {
        TM_LOG_WARN("MAIN", "未找到证书引擎");
    }

    TM_LOG_INFO("MAIN", "协议解析引擎初始化成功");

    return 0;
}

/**
 * @brief 初始化统计模块
 */
static int init_stats(app_config_t *config)
{
    TM_LOG_INFO("MAIN", "初始化统计模块 (v3.0 ClickHouse 存储架构)...");

    if (!config->enable_db) {
        TM_LOG_INFO("MAIN", "数据库未启用，跳过存储引擎初始化");
        return 0;
    }

    /* 创建存储引擎 */
    g_storage_engine = storage_engine_create(
        config->ch_host,
        config->ch_port,
        config->ch_user,
        config->ch_password,
        config->db_name,
        "stats");
    if (!g_storage_engine) {
        TM_LOG_ERROR("MAIN", "创建存储引擎失败");
        return -1;
    }

    g_ip_profile_engine = student_profile_create();
    if (!g_ip_profile_engine) {
        TM_LOG_ERROR("MAIN", "创建 IP画像引擎失败");
        storage_engine_destroy(g_storage_engine);
        g_storage_engine = NULL;
        return -1;
    }

    g_verdict_engine = verdict_engine_create();
    if (!g_verdict_engine) {
        TM_LOG_ERROR("MAIN", "创建裁决引擎失败");
        student_profile_destroy(g_ip_profile_engine);
        g_ip_profile_engine = NULL;
        storage_engine_destroy(g_storage_engine);
        g_storage_engine = NULL;
        return -1;
    }

    /* 会话真正超时回收时，再统一生成 verdict/IP画像，避免实时包路径重复累计。 */
    session_set_expire_callback(handle_expired_session_for_profiles, NULL);

    /* 创建索引 */
    TM_LOG_INFO("MAIN", "创建数据库索引...");
    if (storage_engine_create_indexes(g_storage_engine) < 0) {
        TM_LOG_ERROR("MAIN", "创建索引失败");
        session_set_expire_callback(NULL, NULL);
        verdict_engine_destroy(g_verdict_engine);
        g_verdict_engine = NULL;
        student_profile_destroy(g_ip_profile_engine);
        g_ip_profile_engine = NULL;
        storage_engine_destroy(g_storage_engine);
        g_storage_engine = NULL;
        return -1;
    }

    TM_LOG_INFO("MAIN", "统计模块初始化成功 (v3.0 ClickHouse 架构)");
    TM_LOG_INFO("MAIN", "  虚拟集合: 500万IP + 30万会话 + 1000协议");
    TM_LOG_INFO("MAIN", "  批量操作: 1000条/批");
    TM_LOG_INFO("MAIN", "  刷盘间隔: 3600秒 (1小时)");
    TM_LOG_INFO("MAIN", "  ClickHouse: %s:%d / %s",
           config->ch_host, config->ch_port, config->db_name);

    return 0;
}

/*============================================================================
 *                              日志线程辅助函数
 *============================================================================*/

/** 将 debug 日志路径的 .log 替换为 .stats.log */
/*============================================================================
 *                              清理函数
 *============================================================================*/

/**
 * @brief 清理所有资源
 */
static void cleanup_all(void)
{
    TM_LOG_INFO("MAIN", "开始清理资源...");

    /* 停止插件命令处理线程 */
    stop_plugin_command_handler();

    /* 在停止日志线程前，投递 SHUTDOWN 事件到 events 日志 */
    {
        long up_sec = (g_start_time > 0) ? (long)(time(NULL) - g_start_time) : 0;
        engine_log_shutdown(g_shutdown_reason, up_sec, g_packet_count, g_drop_count);
    }

    engine_log_flush();

    /* 停止 events 日志文件线程 & HTTP flush 线程（等待 SHUTDOWN 块写完后 join）*/
    engine_log_stop();

    /* 停止异步日志线程（排空消息队列后 join）*/
    tm_log_close_async();

#ifdef CAPTURE_ENABLE_DPDK
    if (g_pipeline_mode) {
        /* 新架构：worker 直接轮询 NIC 队列，无 worker ring，无需排空 */

        /* 排空 storage ring 中残余 task */
        if (g_storage_ring) {
            storage_task_t *task;
            while (rte_ring_dequeue(g_storage_ring, (void **)&task) == 0) {
                if (g_storage_task_pool)
                    rte_mempool_put(g_storage_task_pool, task);
            }
            rte_ring_free(g_storage_ring);
            g_storage_ring = NULL;
        }
        /* g_storage_task_pool 由 EAL 随进程退出自动释放 */
        g_storage_task_pool = NULL;

        /* 销毁每 worker 资源 */
        for (int i = 0; i < g_nb_workers; i++) {
            if (g_worker_sessions[i]) {
                TM_LOG_INFO("MAIN", "Worker %d: 销毁会话表 (活跃会话: %u)",
                       i, session_get_count(g_worker_sessions[i]));
                session_manager_destroy(g_worker_sessions[i]);
                g_worker_sessions[i] = NULL;
            }
            if (g_worker_engines[i]) {
                engine_registry_destroy(g_worker_engines[i]);
                g_worker_engines[i] = NULL;
            }
        }
    }
#endif

    /* 停止存储并刷盘（storage lcore 已做过，此处为兜底）*/
    if (g_storage_engine) {
        TM_LOG_INFO("MAIN", "停止存储引擎...");
        TM_LOG_INFO("MAIN", "最后一次刷盘...");
        storage_engine_flush_all(g_storage_engine);
        storage_engine_print_stats(g_storage_engine);
        print_capacity_info(g_storage_engine->vc);
        storage_engine_destroy(g_storage_engine);
        g_storage_engine = NULL;
    }

    session_set_expire_callback(NULL, NULL);

    /* 汇报 verdict 写门控效果：被跳过写库的无信号裁决数 */
    if (g_verdict_skip_count > 0) {
        TM_LOG_INFO("MAIN", "verdict 无信号门控: 跳过写库 %" PRIu64 " 条",
                    g_verdict_skip_count);
    }

    if (g_ip_profile_engine) {
        student_profile_destroy(g_ip_profile_engine);
        g_ip_profile_engine = NULL;
    }

    if (g_verdict_engine) {
        verdict_engine_destroy(g_verdict_engine);
        g_verdict_engine = NULL;
    }

    /* 写入关闭操作日志到 ClickHouse（在 ch_storage_cleanup 前） */
    {
        long up_sec = (g_start_time > 0) ? (long)(time(NULL) - g_start_time) : 0;
        char detail[512];
        snprintf(detail, sizeof(detail),
                 "reason=%s uptime=%lds pkts=%llu drops=%llu",
                 g_shutdown_reason ? g_shutdown_reason : "normal",
                 up_sec,
                 (unsigned long long)g_packet_count,
                 (unsigned long long)g_drop_count);
        ch_insert_operation_log("shutdown", "collector", "system",
                                "", 0, detail, 1, "{}");
    }

    /* 释放插件存储连接（在引擎清理之后，确保所有 insert 已完成） */
    ch_storage_cleanup();
    mongo_storage_cleanup();

    /* 清理单线程模式的会话和引擎 */
    if (g_session_mgr) {
        TM_LOG_INFO("MAIN", "清理会话管理器 (活跃会话: %u)...",
               session_get_count(g_session_mgr));
        session_manager_destroy(g_session_mgr);
        g_session_mgr = NULL;
    }

    if (g_engine_registry) {
        TM_LOG_INFO("MAIN", "清理协议解析引擎...");
        engine_registry_destroy(g_engine_registry);
        g_engine_registry = NULL;
    }

    /* 关闭抓包 */
    if (g_capture) {
        TM_LOG_INFO("MAIN", "关闭抓包模块...");
        capture_print_stats(g_capture);
        capture_close(g_capture);
        capture_destroy(g_capture);
        g_capture = NULL;
    }

    TM_LOG_INFO("MAIN", "资源清理完成");
}

/*============================================================================
 *                              命令行参数
 *============================================================================*/

/**
 * @brief 打印使用说明
 */
static void print_usage(const char *prog)
{
    printf("用法: %s [选项]\n\n", prog);
    printf("选项:\n");
    printf("  -b, --backend <type>      抓包后端 (auto/dpdk/pcap，默认: auto)\n");
    printf("  -i, --interface <name>    网卡接口名 (默认: eth0)\n");
    printf("  -p, --promiscuous         启用混杂模式\n");
    printf("  -q, --quiet               等同于 --log-level error\n");
    printf("  -l, --log-level <level>   日志级别 (error/warn/info/debug，默认: info)\n");
    printf("  --log-file <path>         日志文件路径 (默认: /var/log/traffic_monitor.log)\n");
    printf("  -s, --snaplen <len>       捕获长度 (默认: 65535)\n");
    printf("  -f, --filter <expr>       BPF 过滤器表达式\n");
    printf("  -c, --count <num>         捕获包数量 (-1 表示无限，默认: -1)\n");
    printf("  -t, --timeout <sec>       会话超时时间 (默认: 300秒)\n");
    printf("  -S, --stats-interval <s>  统计间隔 (默认: 10秒)\n");
    printf("  --ch-host <host>          ClickHouse 主机地址 (启用数据库存储)\n");
    printf("  --ch-port <port>          ClickHouse HTTP 端口 (默认: 8123)\n");
    printf("  --ch-user <user>          ClickHouse 用户名 (默认: default)\n");
    printf("  --ch-password <pass>      ClickHouse 密码\n");
    printf("  -m, --mongodb <uri>       MongoDB URI (证书存储，默认: mongodb://localhost:27017)\n");
    printf("  -d, --database <name>     数据库名称 (默认: traffic_monitor)\n");
    printf("  -D, --daemon              后台运行模式\n");
    printf("  -h, --help                显示此帮助信息\n");
    printf("  -v, --version             显示版本信息\n");
    printf("  --http-port <port>        启动 HTTP 管理界面 (如 --http-port 8080)\n");
    printf("\n");
    printf("示例:\n");
    printf("  # 使用 libpcap 抓包，过滤 HTTP 流量\n");
    printf("  %s -b pcap -i eth0 -f \"tcp port 80\"\n\n", prog);
    printf("  # 使用 DPDK 高性能抓包\n");
    printf("  %s -b dpdk -i 0000:03:00.0\n\n", prog);
    printf("  # 启用 ClickHouse 数据库存储\n");
    printf("  %s --ch-host 127.0.0.1 --ch-password 123456 -d traffic_monitor\n\n", prog);
}

/**
 * @brief 解析命令行参数
 */
static int parse_args(int argc, char **argv, app_config_t *config)
{
    static struct option long_options[] = {
        {"backend",        required_argument, 0, 'b'},
        {"interface",      required_argument, 0, 'i'},
        {"promiscuous",    no_argument,       0, 'p'},
        {"quiet",          no_argument,       0, 'q'},
        {"snaplen",        required_argument, 0, 's'},
        {"filter",         required_argument, 0, 'f'},
        {"count",          required_argument, 0, 'c'},
        {"timeout",        required_argument, 0, 't'},
        {"stats-interval", required_argument, 0, 'S'},
        {"ch-host",        required_argument, 0, 1001},
        {"ch-port",        required_argument, 0, 1002},
        {"ch-user",        required_argument, 0, 1003},
        {"ch-password",    required_argument, 0, 1004},
        {"mongodb",        required_argument, 0, 'm'},
        {"database",       required_argument, 0, 'd'},
        {"daemon",         no_argument,       0, 'D'},
        {"help",           no_argument,       0, 'h'},
        {"version",        no_argument,       0, 'v'},
        {"lcore-map",      required_argument, 0, 'L'},
        {"socket-mem",     required_argument, 0, 'M'},
        {"workers",        required_argument, 0, 'w'},
        {"no-plugins",     no_argument,       0, 'P'},
        {"log-level",      required_argument, 0, 'l'},
        {"log-file",       required_argument, 0, 1005},
        {"http-port",      required_argument, 0, 1006},
        {0, 0, 0, 0}
    };

    int opt;
    int option_index = 0;

    while ((opt = getopt_long(argc, argv, "b:i:pql:s:f:c:t:S:m:d:DhvL:M:w:P",
                             long_options, &option_index)) != -1) {
        switch (opt) {
            case 'b':
                if (strcmp(optarg, "auto") == 0) {
                    config->backend = CAPTURE_BACKEND_AUTO;
                } else if (strcmp(optarg, "dpdk") == 0) {
                    config->backend = CAPTURE_BACKEND_DPDK;
                } else if (strcmp(optarg, "pcap") == 0) {
                    config->backend = CAPTURE_BACKEND_PCAP;
                } else {
                    fprintf(stderr, "无效的后端类型: %s\n", optarg);
                    return -1;
                }
                break;
                
            case 'i':
                strncpy(config->interface, optarg, sizeof(config->interface) - 1);
                break;
                
            case 'p':
                config->promiscuous = true;
                break;

            case 'q':
                g_log_level = TM_LOG_LVL_ERROR;
                break;

            case 'l':
                if (strcmp(optarg, "error") == 0)      g_log_level = TM_LOG_LVL_ERROR;
                else if (strcmp(optarg, "warn") == 0)   g_log_level = TM_LOG_LVL_WARN;
                else if (strcmp(optarg, "info") == 0)   g_log_level = TM_LOG_LVL_INFO;
                else if (strcmp(optarg, "debug") == 0)  g_log_level = TM_LOG_LVL_DEBUG;
                else {
                    fprintf(stderr, "无效的日志级别: %s (可选: error/warn/info/debug)\n", optarg);
                    return -1;
                }
                break;

            case 's':
                config->snaplen = atoi(optarg);
                break;
                
            case 'f':
                strncpy(config->bpf_filter, optarg, sizeof(config->bpf_filter) - 1);
                break;
                
            case 'c':
                config->packet_count = atoi(optarg);
                break;
                
            case 't':
                config->session_timeout = atoi(optarg);
                break;
                
            case 'S':
                config->stats_interval = atoi(optarg);
                break;
                
            case 1001:
                strncpy(config->ch_host, optarg, sizeof(config->ch_host) - 1);
                config->enable_db = true;
                break;

            case 1002:
                config->ch_port = atoi(optarg);
                break;

            case 1003:
                strncpy(config->ch_user, optarg, sizeof(config->ch_user) - 1);
                break;

            case 1004:
                strncpy(config->ch_password, optarg, sizeof(config->ch_password) - 1);
                break;

            case 1005:
                strncpy(config->log_file, optarg, sizeof(config->log_file) - 1);
                break;

            case 1006:
                config->http_port = atoi(optarg);
                break;

            case 'm':
                strncpy(config->mongodb_uri, optarg, sizeof(config->mongodb_uri) - 1);
                break;

            case 'd':
                strncpy(config->db_name, optarg, sizeof(config->db_name) - 1);
                break;
                
            case 'D':
                config->daemon_mode = true;
                break;
                
            case 'h':
                print_usage(argv[0]);
                exit(0);
                
            case 'v':
                printf("流量监测系统 v1.0\n");
                printf("抓包模块版本: %s\n", capture_version());
                exit(0);

            case 'L':
                strncpy(config->lcore_map, optarg, sizeof(config->lcore_map) - 1);
                break;

            case 'M':
                strncpy(config->socket_mem, optarg, sizeof(config->socket_mem) - 1);
                break;

            case 'w':
                config->nb_workers = atoi(optarg);
                if (config->nb_workers < 1 || config->nb_workers > 16)
                    config->nb_workers = DEFAULT_NB_WORKERS;
                break;

            case 'P':
                config->no_plugins = true;
                g_no_plugins = 1;
                TM_LOG_INFO("MAIN", "--no-plugins: 禁用所有协议解析插件");
                break;

            default:
                print_usage(argv[0]);
                return -1;
        }
    }
    
    return 0;
}

/*============================================================================
 *                              主函数
 *============================================================================*/

int main(int argc, char **argv)
{
    /* [PERF] stdout/stderr 设 1MB 全缓冲: 大量调试日志的同步 write 会抢 stdio 锁、
       拖死 DPDK worker(导致会话不过期、verdict/翻墙不写)。缓冲后批量刷, 不阻塞热路径。 */
    setvbuf(stdout, NULL, _IOFBF, 1<<20);
    setvbuf(stderr, NULL, _IOFBF, 1<<20);
    app_config_t config = {
        .backend = CAPTURE_BACKEND_AUTO,
        .interface = "eth0",
        .promiscuous = false,
        .snaplen = 65535,
        .bpf_filter = "",
        .session_table_size = 4194304,  /* 4M 桶：多队列模式下每 worker 约 1.4M 桶，支持百万级并发会话 */
        .session_timeout = 60,          /* 60s 超时：减少会话堆积，降低内存压力 */
        .ch_host = "",
        .ch_port = 8123,
        .ch_user = "default",
        .ch_password = "",
        .mongodb_uri = "mongodb://localhost:27017",
        .db_name = "traffic_monitor",
        .stats_interval = 10,
        .enable_db = false,
        .daemon_mode = false,
        .packet_count = -1,
        .nb_workers = DEFAULT_NB_WORKERS,
        .http_port = 0,
    };
    
    int ret = 0;
    
    /* 打印横幅 */
    printf("========================================\n");
    printf("  流量监测系统 v1.0\n");
    printf("  构建时间: %s %s\n", __DATE__, __TIME__);
    printf("========================================\n\n");
    
    /* 解析命令行参数 */
    if (parse_args(argc, argv, &config) < 0) {
        return 1;
    }

    /* 初始化日志系统 */
    {
        const char *log_path = config.log_file[0]
            ? config.log_file
            : "/var/log/traffic_monitor.log";
        if (tm_log_init_async(log_path, g_log_level, "traffic_monitor") < 0) {
            fprintf(stderr, "[MAIN] log file open failed: %s, syslog only\n", log_path);
            tm_log_init_async(NULL, g_log_level, "traffic_monitor");
        }
    }
    TM_LOG_INFO("MAIN", "流量监测系统 v1.0 启动, 构建时间: %s %s", __DATE__, __TIME__);

    /* 初始化 events 日志文件输出 */
    {
        const char *debug_log = config.log_file[0]
            ? config.log_file : "/var/log/traffic_monitor.log";
        char events_log_path[256];
        const char *dot = strrchr(debug_log, '.');
        if (dot) {
            snprintf(events_log_path, sizeof(events_log_path),
                     "%.*s-events%s", (int)(dot - debug_log), debug_log, dot);
        } else {
            snprintf(events_log_path, sizeof(events_log_path),
                     "%s-events.log", debug_log);
        }
        if (engine_log_file_init(events_log_path) == 0) {
            engine_log_startup("v1.0",
#ifdef CAPTURE_ENABLE_DPDK
                               "dpdk",
#else
                               "libpcap",
#endif
                               config.interface,
                               config.nb_workers,
                               config.ch_host,
                               debug_log);
            engine_registry_set_plugin_event_cb(engine_log_plugin_event);
        } else {
            TM_LOG_WARN("MAIN", "events log init failed, events disabled");
        }
    }

    /* 启动 HTTP 管理界面（可选）*/
    if (config.http_port > 0) {
        if (http_server_start((uint16_t)config.http_port,
                              collect_plugin_registries,
                              http_get_sys_stats,
                              http_plugin_control) == 0) {
            TM_LOG_INFO("MAIN", "HTTP 管理界面已启动: http://localhost:%d", config.http_port);
        } else {
            TM_LOG_ERROR("MAIN", "HTTP 管理界面启动失败");
        }
    }

    /* 同步 worker 数量（解析完参数后立即设置，供 init_capture 使用）*/
#ifdef CAPTURE_ENABLE_DPDK
    if (config.nb_workers >= 1 && config.nb_workers <= 16)
        g_nb_workers = config.nb_workers;
    else
        g_nb_workers = DEFAULT_NB_WORKERS;
#endif
    
    /* 初始化各模块 */
    if (init_capture(&config) < 0) {
        ret = 1;
        goto cleanup;
    }

    /* 在 DPDK EAL init 完成后重新安装信号处理函数，
     * 并显式解除主线程对 SIGINT/SIGTERM 的屏蔽。
     * DPDK rte_eal_init() 在创建 secondary lcore 线程期间会临时屏蔽所有信号，
     * 必须在其完成后重新设置，否则 SIGINT 无法唤醒主线程。 */
    if (setup_signals() < 0) {
        TM_LOG_ERROR("MAIN", "设置信号处理失败");
        ret = 1;
        goto cleanup;
    }
#ifdef CAPTURE_ENABLE_DPDK
    {
        sigset_t unblock;
        sigemptyset(&unblock);
        sigaddset(&unblock, SIGINT);
        sigaddset(&unblock, SIGTERM);
        pthread_sigmask(SIG_UNBLOCK, &unblock, NULL);
    }
#endif

    /* 初始化统计模块（两种模式共用）*/
    if (init_stats(&config) < 0) {
        ret = 1;
        goto cleanup;
    }

    /* 初始化 ClickHouse 存储（供 HTTP/HTTPS/TLS/JA3 插件共用）
     * 必须在 init_engine() 之前完成，因为引擎 init 回调可能立即使用存储模块 */
    if (config.enable_db) {
        char ch_url[512];
        snprintf(ch_url, sizeof(ch_url), "http://%s:%d", config.ch_host, config.ch_port);
        if (ch_storage_init(ch_url, config.db_name,
                            config.ch_user[0] ? config.ch_user : NULL,
                            config.ch_password[0] ? config.ch_password : NULL) < 0) {
            TM_LOG_ERROR("MAIN", "ClickHouse 初始化/表校验失败，终止启动以避免运行时静默丢数据");
            /* fail-fast：退出让 systemd Restart=on-failure 兜底重启 */
            exit(EXIT_FAILURE);
        }

        if (!g_capture || g_capture->backend != CAPTURE_BACKEND_DPDK) {
            engine_log_set_fallback_mode(1);
            engine_log_init(ch_url, config.db_name,
                            config.ch_user[0] ? config.ch_user : NULL,
                            config.ch_password[0] ? config.ch_password : NULL);
            engine_log_init_http();
        }
    }

    /* 初始化 MongoDB 存储（供 Certificate 插件使用）
     * 必须在 init_engine() 之前完成；未启用数据库时跳过 */
    if (config.enable_db) {
        /* 若命令行未用 -m 指定 MongoDB URI,则从环境变量读取(与 GeoIP 等一致)。
         * 生产 MongoDB 启用了认证;默认 mongodb://localhost:27017 无凭据时 ping 能通
         * (mongo 允许匿名 ping,g_ready 会被置为 true)但所有 insert/replace 被拒绝,
         * 导致证书/HTTP/IP画像 静默无法落库。必须传带认证的 URI。 */
        if (strcmp(config.mongodb_uri, "mongodb://localhost:27017") == 0) {
            const char *env_uri = getenv("TRAFFIC_MONITOR_MONGODB_URI");
            if (!env_uri || !env_uri[0]) env_uri = getenv("MONGODB_URI");
            if (env_uri && env_uri[0]) {
                strncpy(config.mongodb_uri, env_uri, sizeof(config.mongodb_uri) - 1);
                config.mongodb_uri[sizeof(config.mongodb_uri) - 1] = '\0';
                TM_LOG_INFO("MAIN", "MongoDB URI 取自环境变量(认证连接)");
            }
        }
        if (mongo_storage_init(config.mongodb_uri, config.db_name) < 0) {
            TM_LOG_ERROR("MAIN", "MongoDB 初始化失败，证书数据将无法写入数据库");
        }
    }

    /* 初始化时间戳 */
    g_start_time = time(NULL);
    g_last_stats_time = g_start_time;
    g_last_flush_time = g_start_time;
    g_last_session_flush = g_start_time;

    /* 写入启动操作日志到 ClickHouse */
    if (config.enable_db) {
        char detail[512];
        snprintf(detail, sizeof(detail),
                 "version=v1.0 backend=%s interface=%s workers=%d stats_interval=%ds",
                 (g_capture && g_capture->backend == CAPTURE_BACKEND_DPDK) ? "dpdk" : "pcap",
                 config.interface ? config.interface : "",
                 g_nb_workers, (int)config.stats_interval);
        ch_insert_operation_log("startup", "collector", "system",
                                "", 0, detail, 1, "{}");
    }

#ifdef CAPTURE_ENABLE_DPDK
    /* ---- DPDK 多核流水线模式 ---- */
    if (g_capture->backend == CAPTURE_BACKEND_DPDK) {
        TM_LOG_INFO("MAIN", "启动 DPDK 多队列流水线 (%d workers + 1 storage)...",
               g_nb_workers);
        TM_LOG_INFO("MAIN", "  lcore 0: 管理（当前线程）");
        TM_LOG_INFO("MAIN", "  lcore 1: 存储");
        for (int w = 0; w < g_nb_workers; w++) {
            TM_LOG_INFO("MAIN", "  lcore %d: worker %d（轮询 NIC queue %d）",
                   LCORE_WORKER_BASE + w, w, w);
        }

        if (init_pipeline_rings(&config) < 0) {
            TM_LOG_ERROR("MAIN", "流水线初始化失败");
            ret = 1;
            goto cleanup;
        }

        if (start_plugin_command_handler() < 0) {
            TM_LOG_WARN("MAIN", "启动插件命令处理器失败（非致命）");
        }

        /* 启动 lcore 1: 存储（原 lcore 4）*/
        if (rte_eal_remote_launch(storage_lcore_main, NULL, LCORE_STORAGE) != 0) {
            TM_LOG_ERROR("MAIN", "启动存储 lcore 失败");
            ret = 1; goto cleanup;
        }

        /* 启动 lcore 2..g_nb_workers+1: workers（每个直接轮询对应 NIC 队列）*/
        for (int w = 0; w < g_nb_workers; w++) {
            g_worker_args[w].worker_id = w;
            g_worker_args[w].queue_id  = (uint16_t)w;
            if (rte_eal_remote_launch(worker_lcore_main, &g_worker_args[w],
                                       LCORE_WORKER_BASE + w) != 0) {
                TM_LOG_ERROR("MAIN", "启动 worker lcore %d 失败", w);
                ret = 1; goto cleanup;
            }
        }

        /* lcore 0: 管理循环（阻塞至所有 lcore 退出）*/
        run_main_loop(config.stats_interval);

    } else {
#endif
    /* ---- 单线程模式（pcap 或 DPDK fallback）---- */
    if (init_session(&config) < 0) {
        ret = 1;
        goto cleanup;
    }

    if (init_engine() < 0) {
        ret = 1;
        goto cleanup;
    }

    if (start_plugin_command_handler() < 0) {
        TM_LOG_WARN("MAIN", "启动插件命令处理器失败");
    }

    if (config.enable_db && (!g_capture || g_capture->backend != CAPTURE_BACKEND_DPDK)) {
        engine_log_flush();
    }

    TM_LOG_INFO("MAIN", "所有模块初始化完成，开始捕获流量...");
    TM_LOG_INFO("MAIN", "按 Ctrl+C 停止");

    /* 主循环：捕获并处理数据包 */
    int processed = capture_loop(g_capture, config.packet_count,
                                 packet_handler, &config);

    if (processed < 0) {
        TM_LOG_ERROR("MAIN", "捕获循环出错: %s",
                capture_get_error(g_capture));
        ret = 1;
    } else {
        TM_LOG_INFO("MAIN", "捕获循环结束，共处理 %d 个数据包", processed);
    }

    /* 清理过期会话 */
    if (g_session_mgr) {
        int expired = session_flush_expired(g_session_mgr);
        TM_LOG_INFO("MAIN", "清理了 %d 个过期会话", expired);
    }
#ifdef CAPTURE_ENABLE_DPDK
    } /* end else (single-threaded) */
#endif
    
cleanup:
    http_server_stop();
    cleanup_all();

    TM_LOG_INFO("MAIN", "程序退出 (返回码: %d)", ret);
    tm_log_close_async();
    return ret;
}
