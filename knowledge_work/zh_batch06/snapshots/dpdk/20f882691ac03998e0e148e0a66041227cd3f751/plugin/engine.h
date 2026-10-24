/**
 * 协议解析引擎 - 接口定义
 * 
 * 本模块定义了协议解析引擎的统一接口和注册中心机制
 */

#ifndef ENGINE_H
#define ENGINE_H

#include <stdint.h>
#include <stdbool.h>
#include <time.h>
#include <pthread.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ========== 常量定义 ========== */

#define ENGINE_MAX_ENGINES      128     // 最大引擎数量
#define ENGINE_NAME_MAX_LEN     64      // 引擎名称最大长度

/* ========== 返回值定义 ========== */

typedef enum {
    ENGINE_OK = 0,              // 成功
    ENGINE_ERROR = -1,          // 错误
    ENGINE_HANDLED = 1,         // 已处理，停止链式处理
    ENGINE_CONTINUE = 2,        // 继续下一个引擎
    ENGINE_SKIP = 3             // 跳过当前引擎
} engine_result_t;

/* ========== 前向声明 ========== */

typedef struct engine_ctx engine_ctx_t;
typedef struct engine_ops engine_ops_t;
typedef struct engine_entry engine_entry_t;
typedef struct engine_registry engine_registry_t;
typedef struct parsed_packet parsed_packet_t;
typedef struct session_entry session_entry_t;

/* ========== 数据包结构 (简化版) ========== */

typedef struct parsed_packet {
    // L2
    uint16_t eth_type;

    // L3
    uint8_t  ip_version;          // IP 版本：4 or 6, 0=unknown
    bool     is_ipv4;             // IPv4 标志（兼容旧插件）
    uint32_t src_ip;              // IPv4 源地址（网络字节序）
    uint32_t dst_ip;              // IPv4 目的地址（网络字节序）
    uint8_t  src_ip6[16];         // IPv6 源地址（网络字节序）
    uint8_t  dst_ip6[16];         // IPv6 目的地址（网络字节序）
    uint8_t  ttl;                 // IPv4 TTL / IPv6 Hop Limit

    // L4
    uint8_t protocol;           // TCP=6, UDP=17
    uint16_t src_port;
    uint16_t dst_port;
    uint16_t tcp_flags;

    // Payload
    const uint8_t *payload;
    uint32_t payload_len;

    // 时间戳
    int64_t timestamp;
} parsed_packet_t;

/* ========== 引擎统计 ========== */

typedef struct engine_stats {
    uint64_t packets_processed;     // 处理的包数
    uint64_t packets_matched;       // 匹配的包数
    uint64_t sessions_created;      // 创建的会话数
    uint64_t errors;                // 错误数
} engine_stats_t;

/* ========== 引擎上下文 ========== */

struct engine_ctx {
    void *private_data;             // 引擎私有数据
    engine_stats_t stats;           // 统计信息
};

/* ========== 引擎接口定义 ========== */

struct engine_ops {
    /* 基本信息 */
    const char *name;               // 引擎名称 (如 "HTTP", "HTTPS", "DNS")
    uint32_t protocol_id;           // 协议标识符
    uint32_t priority;              // 优先级 (越小越先执行)
    
    /* 核心接口 */
    
    /**
     * 初始化引擎
     * @param ctx 引擎上下文
     * @return ENGINE_OK 成功, ENGINE_ERROR 失败
     */
    int (*init)(engine_ctx_t *ctx);
    
    /**
     * 检查是否能处理该数据包
     * @param ctx 引擎上下文
     * @param session 会话指针 (可为 NULL)
     * @param pkt 解析后的数据包
     * @return true 可以处理, false 不能处理
     */
    bool (*can_handle)(engine_ctx_t *ctx, 
                       const session_entry_t *session,
                       const parsed_packet_t *pkt);
    
    /**
     * 处理数据包
     * @param ctx 引擎上下文
     * @param session 会话指针
     * @param pkt 解析后的数据包
     * @return ENGINE_HANDLED/CONTINUE/ERROR
     */
    int (*process)(engine_ctx_t *ctx,
                   session_entry_t *session,
                   const parsed_packet_t *pkt);
    
    /**
     * 清理引擎资源
     * @param ctx 引擎上下文
     */
    void (*cleanup)(engine_ctx_t *ctx);
    
    /* 可选接口 */
    
    /**
     * 获取统计信息
     * @param ctx 引擎上下文
     * @param stats 输出统计信息
     * @return ENGINE_OK 成功
     */
    int (*get_stats)(engine_ctx_t *ctx, engine_stats_t *stats);
    
    /**
     * 重置统计信息
     * @param ctx 引擎上下文
     */
    void (*reset_stats)(engine_ctx_t *ctx);
};

/* ========== 引擎条目 ========== */

struct engine_entry {
    const engine_ops_t *ops;        // 引擎操作接口
    engine_ctx_t ctx;               // 引擎上下文
    engine_entry_t *next;           // 链表指针 (用于链式处理)
};

/* ========== 引擎注册中心 ========== */

/* 前向声明：插件句柄（定义在 engine_registry.c 内部）*/
struct plugin_handle;

struct engine_registry {
    engine_entry_t *engines[ENGINE_MAX_ENGINES];    // 引擎数组
    uint32_t engine_count;                          // 引擎数量
    engine_entry_t *chain_head;                     // 引擎链表头 (按优先级排序)
    pthread_rwlock_t registry_lock;                 // 读写锁：worker 读，管理线程写
    struct plugin_handle *plugin_list;              // 本 registry 加载的插件链表
};

/* ========== API 函数 ========== */

/**
 * 创建引擎注册中心
 * @return 注册中心指针, 失败返回 NULL
 */
engine_registry_t *engine_registry_create(void);

/**
 * 注册引擎
 * @param registry 注册中心
 * @param ops 引擎操作接口
 * @return ENGINE_OK 成功, ENGINE_ERROR 失败
 */
int engine_registry_register(engine_registry_t *registry, 
                              const engine_ops_t *ops);

/**
 * 注销引擎
 * @param registry 注册中心
 * @param name 引擎名称
 * @return ENGINE_OK 成功, ENGINE_ERROR 失败
 */
int engine_registry_unregister(engine_registry_t *registry, 
                                const char *name);

/**
 * 查找引擎
 * @param registry 注册中心
 * @param name 引擎名称
 * @return 引擎条目指针, 未找到返回 NULL
 */
engine_entry_t *engine_registry_find(engine_registry_t *registry, 
                                     const char *name);

/**
 * 处理数据包 (遍历引擎链)
 * @param registry 注册中心
 * @param session 会话指针
 * @param pkt 解析后的数据包
 * @return ENGINE_OK 成功, ENGINE_ERROR 失败
 */
int engine_process(engine_registry_t *registry,
                   session_entry_t *session,
                   const parsed_packet_t *pkt);

/**
 * 获取所有引擎统计
 * @param registry 注册中心
 * @param stats 输出统计数组
 * @param max_count 最大数量
 * @return 实际引擎数量
 */
int engine_get_all_stats(engine_registry_t *registry,
                         engine_stats_t *stats,
                         uint32_t max_count);

/**
 * 销毁引擎注册中心
 * @param registry 注册中心
 */
void engine_registry_destroy(engine_registry_t *registry);

/* ========== 动态插件加载 API ========== */

/**
 * 从动态库加载引擎插件
 * @param registry 注册中心
 * @param plugin_path 插件路径 (如 "./plugins/engine_dns.so")
 * @return ENGINE_OK 成功, ENGINE_ERROR 失败
 */
int engine_registry_load_plugin(engine_registry_t *registry,
                                 const char *plugin_path);

/**
 * 卸载引擎插件
 * @param registry 注册中心
 * @param name 引擎名称
 * @return ENGINE_OK 成功, ENGINE_ERROR 失败
 */
int engine_registry_unload_plugin(engine_registry_t *registry,
                                   const char *name);

/* ========== 插件导出接口 ========== */

/**
 * 插件必须导出此函数以提供引擎操作表
 * @return 引擎操作接口指针
 */
typedef const engine_ops_t* (*engine_get_ops_func_t)(void);

/**
 * 注册插件事件回调。
 * cb 在插件加载/卸载完成后（锁释放后）被调用，可安全调用 engine_log_plugin_event。
 * 传 NULL 可注销回调。
 * 线程安全：仅应在主线程 worker 启动前调用一次。
 */
void engine_registry_set_plugin_event_cb(
    void (*cb)(const char *name, int loaded));

#ifdef __cplusplus
}
#endif

#endif /* ENGINE_H */
