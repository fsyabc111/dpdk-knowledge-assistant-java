package io.dpdk.assistant.contracts.session;

/** 追加用户消息输入；字段（role/content/metadata 等）由 J2-05 登记补全。 */
public record MessageInput(String content) {
}
