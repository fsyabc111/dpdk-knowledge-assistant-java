/**
 * 统计与存储服务模块 - 接口定义
 */

#ifndef STATS_H
#define STATS_H

#include <stdint.h>
#include <stdbool.h>
#include <time.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ========== 常量定义 ========== */

#define STATS_MAX_PROTOCOLS     32
#define STATS_BUFFER_SIZE       1000

/* ========== 系统统计数据 ========== */

typedef struct system_stats {
    time_t timestamp;
    
    // 抓包统计
    uint64_t rx_packets;
    uint64_t rx_bytes;
    uint64_t rx_dropped;
    uint64_t rx_errors;
    
    // 性能指标
    double pps;                 // 每秒包数
    double bps;                 // 每秒字节数
    double drop_rate;           // 丢包率
    
    // 会话统计
    uint32_t active_sessions;
    uint64_t total_sessions_created;
    uint64_t total_sessions_expired;
    double session_create_rate;
    
    // 系统信息
    char backend[20];
    char interface[64];
} system_stats_t;

/* ========== 协议统计数据 ========== */

typedef struct protocol_stats {
    time_t timestamp;
    char protocol_name[32];
    uint32_t protocol_id;
    
    uint64_t packets_processed;
    uint64_t packets_matched;
    uint64_t bytes_processed;
    uint64_t sessions_created;
    uint64_t errors;
    
    double traffic_percentage;
} protocol_stats_t;

/* ========== 会话记录数据 ========== */

typedef struct session_record {
    char session_id[64];
    
    // 五元组
    char src_ip[46];
    char dst_ip[46];
    uint16_t src_port;
    uint16_t dst_port;
    uint8_t protocol;
    
    // 时间
    time_t create_time;
    time_t last_seen;
    uint32_t duration;
    
    // 流量
    uint64_t packets_ab;
    uint64_t packets_ba;
    uint64_t bytes_ab;
    uint64_t bytes_ba;
    
    // 协议
    char detected_protocol[32];
    char state[32];
} session_record_t;

/* ========== 统计管理器 ========== */

typedef struct stats_manager stats_manager_t;

/* ========== API 函数 ========== */

/**
 * 创建统计管理器
 * @param ch_host    ClickHouse 主机地址
 * @param ch_port    ClickHouse HTTP 端口（默认 8123）
 * @param ch_user    用户名（默认 "default"）
 * @param ch_password 密码
 * @param db_name    数据库名称
 * @param interval   收集间隔(秒)
 * @return 管理器指针
 */
stats_manager_t* stats_manager_create(const char *ch_host, int ch_port,
                                      const char *ch_user, const char *ch_password,
                                      const char *db_name,
                                      uint32_t interval);

/**
 * 启动统计收集
 * @param mgr 管理器
 * @param capture_handle 抓包句柄
 * @param session_mgr 会话管理器
 * @param engine_registry 引擎注册中心
 * @return 0成功
 */
int stats_manager_start(stats_manager_t *mgr,
                        void *capture_handle,
                        void *session_mgr,
                        void *engine_registry);

/**
 * 停止统计收集
 * @param mgr 管理器
 */
void stats_manager_stop(stats_manager_t *mgr);

/**
 * 提交系统统计
 * @param mgr 管理器
 * @param stats 统计数据
 * @return 0成功
 */
int stats_submit_system(stats_manager_t *mgr, const system_stats_t *stats);

/**
 * 提交协议统计
 * @param mgr 管理器
 * @param stats 统计数据数组
 * @param count 数量
 * @return 0成功
 */
int stats_submit_protocol(stats_manager_t *mgr, 
                          const protocol_stats_t *stats,
                          uint32_t count);

/**
 * 提交会话记录
 * @param mgr 管理器
 * @param record 会话记录
 * @return 0成功
 */
int stats_submit_session(stats_manager_t *mgr, const session_record_t *record);

/**
 * 打印统计信息
 * @param mgr 管理器
 */
void stats_manager_print(stats_manager_t *mgr);

/**
 * 销毁统计管理器
 * @param mgr 管理器
 */
void stats_manager_destroy(stats_manager_t *mgr);

#ifdef __cplusplus
}
#endif

#endif /* STATS_H */
