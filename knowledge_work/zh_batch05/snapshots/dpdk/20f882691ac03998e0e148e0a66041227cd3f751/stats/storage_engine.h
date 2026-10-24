/**
 * @file storage_engine.h
 * @brief 批量存储引擎 - ClickHouse HTTP 写入
 *
 * 使用 ClickHouse HTTP API (port 8123) 替代 MongoDB
 * 虚拟集合内存聚合不变，flush 时直接 INSERT（ReplacingMergeTree 负责去重）
 *
 * @version 3.0
 * @date 2026-03-04
 */

#ifndef STORAGE_ENGINE_H
#define STORAGE_ENGINE_H

#include "stats_types.h"
#include "virtual_collection.h"
#include "stats.h"
#include "../session/session.h"

/* ========== 存储引擎结构 ========== */

/**
 * @brief 存储引擎
 */
typedef struct storage_engine {
    /* ClickHouse 连接信息 */
    char ch_host[256];
    int  ch_port;
    char ch_user[64];
    char ch_password[64];

    /* 虚拟集合 */
    virtual_collections_t *vc;

    /* 配置 */
    char db_name[128];
    char table_prefix[64];
    int  current_year;

    /* 性能指标 */
    storage_metrics_t metrics;
    pthread_mutex_t   metrics_lock;

} storage_engine_t;

/* ========== 存储引擎创建与销毁 ========== */

/**
 * @brief 创建存储引擎
 *
 * @param ch_host    ClickHouse 主机地址
 * @param ch_port    ClickHouse HTTP 端口（默认 8123）
 * @param ch_user    用户名（默认 "default"）
 * @param ch_password 密码
 * @param db_name    数据库名称
 * @param table_prefix 表名前缀（如 "stats"）
 * @return 存储引擎指针，失败返回 NULL
 */
storage_engine_t* storage_engine_create(const char *ch_host, int ch_port,
                                         const char *ch_user, const char *ch_password,
                                         const char *db_name,
                                         const char *table_prefix);

/**
 * @brief 销毁存储引擎
 */
void storage_engine_destroy(storage_engine_t *engine);

/* ========== 批量保存 ========== */

/**
 * @brief 批量保存 IP 集合到 ClickHouse
 */
int storage_engine_flush_ip_collection(storage_engine_t *engine);

/**
 * @brief 批量保存会话集合到 ClickHouse
 */
int storage_engine_flush_session_collection(storage_engine_t *engine);

/**
 * @brief 批量保存协议集合到 ClickHouse
 */
int storage_engine_flush_protocol_collection(storage_engine_t *engine);
int storage_engine_flush_verdict_collection(storage_engine_t *engine);
int storage_engine_flush_tunnel_signal_collection(storage_engine_t *engine);
int storage_engine_flush_auxiliary_signal_collection(storage_engine_t *engine);

/**
 * @brief 批量保存所有集合到 ClickHouse
 */
int storage_engine_flush_all(storage_engine_t *engine);

/* ========== 兼容接口 ========== */

/**
 * @brief 提交系统统计（直接写入 ClickHouse）
 */
int storage_engine_submit_system(storage_engine_t *engine, const system_stats_t *stats);

/**
 * @brief 提交协议统计（更新虚拟集合）
 */
int storage_engine_submit_protocol_stats(storage_engine_t *engine,
                                          const protocol_stats_t *stats,
                                          uint32_t count);

/**
 * @brief 提交会话记录（更新虚拟集合）
 */
int storage_engine_submit_session(storage_engine_t *engine, const session_record_t *record);

/**
 * @brief 提交翻墙裁决结果（更新虚拟集合，F3.5）
 */
int storage_engine_submit_verdict(storage_engine_t *engine, const verdict_doc_t *doc);
int storage_engine_submit_tunnel_signal(storage_engine_t *engine, const tunnel_signal_doc_t *doc);
int storage_engine_submit_auxiliary_signal(storage_engine_t *engine, const auxiliary_signal_doc_t *doc);

/* 会话过期时: 若该会话存在隐蔽通道/异常TLS/伪装/心跳/情报命中信号,
 * 同时落库 tunnel_signals + auxiliary_signals 两表(可疑流量页数据源)。
 * 无信号或无可疑因子则不写, 避免空行洪泛。 */
int storage_engine_submit_session_signals(storage_engine_t *engine, const session_entry_t *session);

/**
 * @brief 提交 IP画像数据（更新虚拟集合，F4.1）
 */
int storage_engine_submit_student(storage_engine_t *engine, const student_profile_doc_t *doc);
int storage_engine_submit_ip_profile(storage_engine_t *engine, const ip_profile_doc_t *doc);
int storage_engine_build_tunnel_signal_doc(const session_entry_t *session, tunnel_signal_doc_t *doc);
int storage_engine_build_auxiliary_signal_doc(const session_entry_t *session, auxiliary_signal_doc_t *doc);

/* ========== 统计信息 ========== */

void storage_engine_get_metrics(storage_engine_t *engine, storage_metrics_t *metrics);
void storage_engine_print_stats(storage_engine_t *engine);

/**
 * @brief 建表（ClickHouse 版本：创建 IF NOT EXISTS 表）
 * 与 MongoDB 索引接口同名，保持 main.c 兼容
 */
int storage_engine_create_indexes(storage_engine_t *engine);

#endif /* STORAGE_ENGINE_H */
