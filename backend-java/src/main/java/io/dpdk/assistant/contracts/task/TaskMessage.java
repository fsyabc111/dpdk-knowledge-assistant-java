package io.dpdk.assistant.contracts.task;

/** 已提交的回答正文和元数据，供幂等重放消费。 */
public record TaskMessage(String content, String metadataJson) {
}
