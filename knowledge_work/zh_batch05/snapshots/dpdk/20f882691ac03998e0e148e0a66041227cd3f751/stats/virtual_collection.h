/**
 * @file virtual_collection.h
 * @brief 虚拟集合管理 - 内存聚合层
 *
 * 参考: 存储架构升级方案.md 第2.2节
 * 实现内存中的数据聚合，支持实时合并相同实体的数据
 *
 * @version 2.0
 * @date 2026-02-06
 */

#ifndef VIRTUAL_COLLECTION_H
#define VIRTUAL_COLLECTION_H

#include "stats_types.h"
#include "hash_table.h"

/* ========== 虚拟集合创建与销毁 ========== */

/**
 * @brief 创建虚拟集合管理器
 *
 * @return 虚拟集合指针，失败返回 NULL
 */
virtual_collections_t* virtual_collections_create(void);

/**
 * @brief 销毁虚拟集合管理器
 *
 * @param vc 虚拟集合指针
 */
void virtual_collections_destroy(virtual_collections_t *vc);

/* ========== IP 流量虚拟集合 ========== */

/**
 * @brief 更新 IP 流量记录
 *
 * 参考: 存储架构升级方案.md 第2.3节
 *
 * @param vc 虚拟集合指针
 * @param doc IP 流量文档（会被合并到已存在的记录）
 * @return 0 成功，-1 失败
 *
 * @note 如果 IP 已存在，会合并数据；否则插入新记录
 * @note 线程安全（使用哈希表的写锁）
 */
int virtual_collection_update_ip(virtual_collections_t *vc, const ip_flow_doc_t *doc);

/**
 * @brief 查找 IP 流量记录
 *
 * @param vc 虚拟集合指针
 * @param ip IP 地址
 * @return IP 流量文档指针，未找到返回 NULL
 *
 * @note 返回的指针指向内部数据，不要修改或释放
 * @note 线程安全（使用哈希表的读锁）
 */
ip_flow_doc_t* virtual_collection_find_ip(virtual_collections_t *vc, const char *ip);

/**
 * @brief 删除 IP 流量记录
 *
 * @param vc 虚拟集合指针
 * @param ip IP 地址
 * @return 0 成功，-1 未找到
 */
int virtual_collection_remove_ip(virtual_collections_t *vc, const char *ip);

/* ========== 会话虚拟集合 ========== */

/**
 * @brief 更新会话记录
 *
 * 参考: 插件特征信息存储实现分析.md 第3.1节
 *
 * @param vc 虚拟集合指针
 * @param doc 会话文档（会被合并到已存在的记录）
 * @return 0 成功，-1 失败
 *
 * @note 如果会话已存在，会合并数据（包括 HTTP/DNS 扩展字段）
 * @note 线程安全
 */
int virtual_collection_update_session(virtual_collections_t *vc, const session_doc_t *doc);

/**
 * @brief 查找会话记录
 *
 * @param vc 虚拟集合指针
 * @param session_id 会话ID
 * @return 会话文档指针，未找到返回 NULL
 */
session_doc_t* virtual_collection_find_session(virtual_collections_t *vc, const char *session_id);

/**
 * @brief 删除会话记录
 *
 * @param vc 虚拟集合指针
 * @param session_id 会话ID
 * @return 0 成功，-1 未找到
 */
int virtual_collection_remove_session(virtual_collections_t *vc, const char *session_id);

/* ========== 协议统计虚拟集合 ========== */

/**
 * @brief 更新协议统计
 *
 * @param vc 虚拟集合指针
 * @param doc 协议统计文档（会被合并到已存在的记录）
 * @return 0 成功，-1 失败
 */
int virtual_collection_update_protocol(virtual_collections_t *vc, const protocol_doc_t *doc);

/**
 * @brief 查找协议统计
 *
 * @param vc 虚拟集合指针
 * @param protocol_name 协议名称
 * @return 协议统计文档指针，未找到返回 NULL
 */
protocol_doc_t* virtual_collection_find_protocol(virtual_collections_t *vc, const char *protocol_name);

/**
 * @brief 删除协议统计
 *
 * @param vc 虚拟集合指针
 * @param protocol_name 协议名称
 * @return 0 成功，-1 未找到
 */
int virtual_collection_remove_protocol(virtual_collections_t *vc, const char *protocol_name);

/* ========== 数据合并逻辑 ========== */

/**
 * @brief 合并 IP 流量文档
 *
 * 参考: 存储架构升级方案.md 第2.3节 merge_ip_doc()
 *
 * @param dst 目标文档（会被修改）
 * @param src 源文档（不会被修改）
 *
 * @note 累加流量、包数、协议统计
 * @note 合并端口统计（保留 TOP 16）
 * @note 合并时间序列（按小时合并）
 */
void merge_ip_doc(ip_flow_doc_t *dst, const ip_flow_doc_t *src);

/**
 * @brief 合并会话文档
 *
 * 参考: 插件特征信息存储实现分析.md 第3.1节
 *
 * @param dst 目标文档（会被修改）
 * @param src 源文档（不会被修改）
 *
 * @note 累加流量、包数
 * @note 更新时间范围
 * @note 合并扩展字段（HTTP/DNS 特征）
 * @note 合并连接片段
 */
void merge_session_doc(session_doc_t *dst, const session_doc_t *src);

/**
 * @brief 合并协议统计文档
 *
 * @param dst 目标文档（会被修改）
 * @param src 源文档（不会被修改）
 *
 * @note 累加统计数据
 * @note 合并时间序列
 */
void merge_protocol_doc(protocol_doc_t *dst, const protocol_doc_t *src);

/* ========== 辅助函数 ========== */

/**
 * @brief 合并端口统计（保留 TOP N）
 *
 * @param dst_ports 目标端口数组
 * @param dst_count 目标端口数量（会被修改）
 * @param src_ports 源端口数组
 * @param src_count 源端口数量
 * @param max_count 最大保留数量
 */
void merge_top_ports(port_stat_t *dst_ports, int *dst_count,
                     const port_stat_t *src_ports, int src_count,
                     int max_count);

/**
 * @brief 合并小时流量（按小时合并）
 *
 * @param dst_flow 目标流量数组
 * @param dst_count 目标流量数量（会被修改）
 * @param src_flow 源流量数组
 * @param src_count 源流量数量
 */
void merge_hourly_flow(hourly_flow_t *dst_flow, int *dst_count,
                       const hourly_flow_t *src_flow, int src_count);

/**
 * @brief 合并小时统计（按小时合并）
 *
 * @param dst_stats 目标统计数组
 * @param dst_count 目标统计数量（会被修改）
 * @param src_stats 源统计数组
 * @param src_count 源统计数量
 */
void merge_hourly_stats(hourly_stat_t *dst_stats, int *dst_count,
                        const hourly_stat_t *src_stats, int src_count);

/**
 * @brief 合并连接片段
 *
 * @param dst_segments 目标片段数组
 * @param dst_count 目标片段数量（会被修改）
 * @param src_segments 源片段数组
 * @param src_count 源片段数量
 * @param max_count 最大保留数量
 */
void merge_segments(session_segment_t *dst_segments, int *dst_count,
                    const session_segment_t *src_segments, int src_count,
                    int max_count);

/* ========== 统计信息 ========== */

/**
 * @brief 获取虚拟集合统计信息
 *
 * @param vc 虚拟集合指针
 * @param ip_count 输出 IP 集合大小
 * @param session_count 输出会话集合大小
 * @param protocol_count 输出协议集合大小
 */
void virtual_collection_get_stats(virtual_collections_t *vc,
                                   size_t *ip_count,
                                   size_t *session_count,
                                   size_t *protocol_count);

/**
 * @brief 打印虚拟集合统计信息
 *
 * @param vc 虚拟集合指针
 */
void virtual_collection_print_stats(virtual_collections_t *vc);

/**
 * @brief 获取存储指标
 *
 * @param vc 虚拟集合指针
 * @param metrics 输出存储指标
 */
void virtual_collection_get_metrics(virtual_collections_t *vc, storage_metrics_t *metrics);

/* ========== 批量操作 ========== */

/**
 * @brief 获取所有待写入的 IP 列表
 *
 * 参考: 存储架构升级方案.md 第2.3节 save_ip_collection_to_db()
 *
 * @param vc 虚拟集合指针
 * @param keys 输出 IP 列表（调用者需要释放）
 * @param count 输出 IP 数量
 * @return 0 成功，-1 失败
 *
 * @note 返回所有 new_data=true 的 IP
 * @note 调用者需要释放 keys 数组及其中的字符串
 */
int virtual_collection_get_pending_ips(virtual_collections_t *vc, char ***keys, size_t *count);

/**
 * @brief 获取所有待写入的会话列表
 *
 * @param vc 虚拟集合指针
 * @param keys 输出会话ID列表（调用者需要释放）
 * @param count 输出会话数量
 * @return 0 成功，-1 失败
 */
int virtual_collection_get_pending_sessions(virtual_collections_t *vc, char ***keys, size_t *count);

/**
 * @brief 获取所有待写入的协议列表
 *
 * @param vc 虚拟集合指针
 * @param keys 输出协议名列表（调用者需要释放）
 * @param count 输出协议数量
 * @return 0 成功，-1 失败
 */
int virtual_collection_get_pending_protocols(virtual_collections_t *vc, char ***keys, size_t *count);

/**
 * @brief 获取所有待写入的裁决列表
 *
 * @param vc 虚拟集合指针
 * @param keys 输出 session_id 列表（调用者需要释放）
 * @param count 输出裁决数量
 * @return 0 成功，-1 失败
 */
int virtual_collection_get_pending_verdicts(virtual_collections_t *vc, char ***keys, size_t *count);

/**
 * @brief 获取所有待写入的 IP画像列表
 *
 * @param vc 虚拟集合指针
 * @param keys 输出 profile_key 列表（调用者需要释放）
 * @param count 输出画像数量
 * @return 0 成功，-1 失败
 */
int virtual_collection_get_pending_students(virtual_collections_t *vc, char ***keys, size_t *count);

/**
 * @brief 标记 IP 已写入数据库
 *
 * @param vc 虚拟集合指针
 * @param ip IP 地址
 */
void virtual_collection_mark_ip_synced(virtual_collections_t *vc, const char *ip);

/**
 * @brief 标记会话已写入数据库
 *
 * @param vc 虚拟集合指针
 * @param session_id 会话ID
 */
void virtual_collection_mark_session_synced(virtual_collections_t *vc, const char *session_id);

/**
 * @brief 标记协议已写入数据库
 *
 * @param vc 虚拟集合指针
 * @param protocol_name 协议名称
 */
void virtual_collection_mark_protocol_synced(virtual_collections_t *vc, const char *protocol_name);

/* ========== 裁决虚拟集合 ========== */

/**
 * @brief 更新翻墙裁决记录（F3.5）
 *
 * @param vc 虚拟集合指针
 * @param doc 裁决文档
 * @return 0 成功，-1 失败
 * @note session_id 仅作为流标识，集合内部按追加键存多条历史记录
 */
int virtual_collection_update_verdict(virtual_collections_t *vc, const verdict_doc_t *doc);

/**
 * @brief 查找裁决记录
 *
 * @param vc 虚拟集合指针
 * @param session_id 会话ID
 * @return 裁决文档指针，未找到返回 NULL
 */
verdict_doc_t* virtual_collection_find_verdict(virtual_collections_t *vc, const char *session_id);

/**
 * @brief 标记裁决已写入数据库
 *
 * @param vc 虚拟集合指针
 * @param session_id 会话ID
 */
void virtual_collection_mark_verdict_synced(virtual_collections_t *vc, const char *session_id);

int virtual_collection_update_tunnel_signal(virtual_collections_t *vc, const tunnel_signal_doc_t *doc);
tunnel_signal_doc_t* virtual_collection_find_tunnel_signal(virtual_collections_t *vc, const char *session_id);
int virtual_collection_get_pending_tunnel_signals(virtual_collections_t *vc, char ***keys, size_t *count);
void virtual_collection_mark_tunnel_signal_synced(virtual_collections_t *vc, const char *session_id);

int virtual_collection_update_auxiliary_signal(virtual_collections_t *vc, const auxiliary_signal_doc_t *doc);
auxiliary_signal_doc_t* virtual_collection_find_auxiliary_signal(virtual_collections_t *vc, const char *session_id);
int virtual_collection_get_pending_auxiliary_signals(virtual_collections_t *vc, char ***keys, size_t *count);
void virtual_collection_mark_auxiliary_signal_synced(virtual_collections_t *vc, const char *session_id);

/* ========== IP画像虚拟集合 ========== */

/**
 * @brief 更新 IP画像记录（F4.1）
 *
 * @param vc 虚拟集合指针
 * @param doc IP画像文档
 * @return 0 成功，-1 失败
 * @note 以 profile_key=src_ip+date 为键，合并当天流量/时长等累计数据
 */
int virtual_collection_update_student(virtual_collections_t *vc, const student_profile_doc_t *doc);

/**
 * @brief 查找 IP画像
 *
 * @param vc 虚拟集合指针
 * @param profile_key 画像键
 * @return IP画像文档指针，未找到返回 NULL
 */
student_profile_doc_t* virtual_collection_find_student(virtual_collections_t *vc, const char *profile_key);

/**
 * @brief 标记 IP画像已同步
 *
 * @param vc 虚拟集合指针
 * @param profile_key 画像键
 */
void virtual_collection_mark_student_synced(virtual_collections_t *vc, const char *profile_key);
int virtual_collection_copy_and_mark_student(virtual_collections_t *vc, const char *profile_key, student_profile_doc_t *out);

/* 新命名兼容 */
#define virtual_collection_update_ip_profile virtual_collection_update_student
#define virtual_collection_find_ip_profile virtual_collection_find_student
#define virtual_collection_mark_ip_profile_synced virtual_collection_mark_student_synced

#endif /* VIRTUAL_COLLECTION_H */
