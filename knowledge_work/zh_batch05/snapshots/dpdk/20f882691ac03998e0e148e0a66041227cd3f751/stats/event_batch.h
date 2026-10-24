/**
 * @file event_batch.h
 * @brief 协议事件批量写入 ClickHouse —— storage lcore 消费侧 API
 *
 * 只在 storage lcore（单消费者）上调用，无需加锁。
 * 使用持久 curl handle（HTTP keep-alive），避免每次 connect/disconnect。
 */

#ifndef EVENT_BATCH_H
#define EVENT_BATCH_H

#include "../plugin/plugins/ch_event.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 初始化批量写入模块（持久 curl handle）。
 * 在 storage_lcore_main 启动时调用一次。
 *
 * @param ch_url      ClickHouse HTTP 地址，如 "http://127.0.0.1:8123"
 * @param db_name     数据库名
 * @param ch_user     用户名
 * @param ch_password 密码
 * @return 0 成功，-1 失败
 */
int event_batch_init(const char *ch_url, const char *db_name,
                     const char *ch_user, const char *ch_password);

/**
 * 销毁批量写入模块（关闭 curl handle）。
 * 在 storage_lcore_main 退出前调用。
 */
void event_batch_cleanup(void);

/**
 * 批量写入一组事件到 ClickHouse。
 * 按 event_type_t 分桶，每桶拼一条 INSERT ... FORMAT JSONEachRow，
 * 一次 HTTP POST 发送，减少请求次数。
 *
 * @param evs   从 event ring 出队的 event_task_t 指针数组
 * @param nb_ev 数组元素数
 */
void event_batch_flush(event_task_t **evs, unsigned nb_ev);

#ifdef __cplusplus
}
#endif

#endif /* EVENT_BATCH_H */
