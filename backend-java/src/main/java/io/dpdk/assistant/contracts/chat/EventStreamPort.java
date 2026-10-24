package io.dpdk.assistant.contracts.chat;

import java.util.concurrent.Flow;

/**
 * 事件流订阅端口（owner J3-01；契约 transaction-ports.json：chat.EventStreamPort）。
 *
 * 断线仅移除订阅者，不取消 task；terminal 重建不依赖内存日志。
 */
public interface EventStreamPort {

    Flow.Publisher<SseEvent> subscribeOwned(String taskId, String subject);
}
