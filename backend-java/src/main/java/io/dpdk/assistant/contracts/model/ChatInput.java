package io.dpdk.assistant.contracts.model;

/** 聊天输入；字段（messages/参数/超时等）由 J1-02 按源实现登记补全。 */
public record ChatInput(String model, String jsonPayload) {
}
