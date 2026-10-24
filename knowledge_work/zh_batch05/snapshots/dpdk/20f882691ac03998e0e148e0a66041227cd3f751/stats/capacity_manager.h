/**
 * @file capacity_manager.h
 * @brief 容量管理 - LRU 清理策略
 *
 * 参考: 存储架构升级方案.md 第2.3节 arrange_ip_collection()
 * 实现虚拟集合的容量控制和自动清理
 *
 * @version 2.0
 * @date 2026-02-06
 */

#ifndef CAPACITY_MANAGER_H
#define CAPACITY_MANAGER_H

#include "virtual_collection.h"

/* ========== 容量管理 ========== */

/**
 * @brief 整理 IP 虚拟集合（LRU 清理）
 *
 * 参考: 存储架构升级方案.md 第2.3节
 *
 * 当 IP 集合超过容量时，删除 last_time 最旧的一半记录
 *
 * @param vc 虚拟集合指针
 * @return 删除的记录数，失败返回 -1
 */
int arrange_ip_collection(virtual_collections_t *vc);

/**
 * @brief 整理会话虚拟集合（LRU 清理）
 *
 * @param vc 虚拟集合指针
 * @return 删除的记录数，失败返回 -1
 */
int arrange_session_collection(virtual_collections_t *vc);

/**
 * @brief 整理协议虚拟集合（LRU 清理）
 *
 * @param vc 虚拟集合指针
 * @return 删除的记录数，失败返回 -1
 */
int arrange_protocol_collection(virtual_collections_t *vc);

/**
 * @brief 整理所有虚拟集合
 *
 * @param vc 虚拟集合指针
 * @return 0 成功，-1 失败
 */
int arrange_all_collections(virtual_collections_t *vc);

/**
 * @brief 检查是否需要整理
 *
 * @param vc 虚拟集合指针
 * @param ip_need 输出是否需要整理 IP 集合
 * @param session_need 输出是否需要整理会话集合
 * @param protocol_need 输出是否需要整理协议集合
 */
void check_capacity_need_arrange(virtual_collections_t *vc,
                                  bool *ip_need,
                                  bool *session_need,
                                  bool *protocol_need);

/**
 * @brief 获取容量使用率
 *
 * @param vc 虚拟集合指针
 * @param ip_usage 输出 IP 集合使用率 (0.0-1.0)
 * @param session_usage 输出会话集合使用率 (0.0-1.0)
 * @param protocol_usage 输出协议集合使用率 (0.0-1.0)
 */
void get_capacity_usage(virtual_collections_t *vc,
                        double *ip_usage,
                        double *session_usage,
                        double *protocol_usage);

/**
 * @brief 打印容量信息
 *
 * @param vc 虚拟集合指针
 */
void print_capacity_info(virtual_collections_t *vc);

#endif /* CAPACITY_MANAGER_H */
