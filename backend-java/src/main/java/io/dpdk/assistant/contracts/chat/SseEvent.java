package io.dpdk.assistant.contracts.chat;

/** SSE 事件（帧名与字段契约见 http-and-sse.md；由 J3-01/J3-03 冻结具体枚举）。 */
public record SseEvent(String name, String dataJson) {
}
