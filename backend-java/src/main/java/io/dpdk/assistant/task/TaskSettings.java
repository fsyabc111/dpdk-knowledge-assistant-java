package io.dpdk.assistant.task;

import java.time.Duration;

/**
 * 任务运行时设置（对齐 Python Settings 的任务相关项，取值与源默认一致）。
 *
 * - ragTaskMaxSeconds：执行体总墙（timeout 判死；源 RAG_TASK_MAX_SECONDS=600）；
 * - admissionQueueTimeout：受理占槽排队等待上限（源 RAG_QUEUE_TIMEOUT_SEC=30）；
 * - 受理/执行两堵墙分开：排队 429 与执行 timeout 归因互不混淆；
 * - 心跳/孤儿宽限/TTL/事件日志上限为进程内运行句柄参数（源常量同位）。
 */
public record TaskSettings(
        Duration ragTaskMaxSeconds,
        Duration admissionQueueTimeout,
        int maxActiveRequests,
        int maxWaitingRequests,
        Duration heartbeatInterval,
        Duration orphanGrace,
        Duration finishedTtl,
        int eventLogCap,
        Duration inputTtl) {

    /** 源默认值（Settings 与 task_service 常量同位）。 */
    public static TaskSettings defaults() {
        return new TaskSettings(
                Duration.ofSeconds(600),
                Duration.ofSeconds(30),
                8,
                32,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
                Duration.ofSeconds(300),
                500,
                Duration.ofHours(24));
    }
}
