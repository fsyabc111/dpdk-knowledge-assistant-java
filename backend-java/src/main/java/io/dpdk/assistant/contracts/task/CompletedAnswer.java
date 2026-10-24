package io.dpdk.assistant.contracts.task;

/** 终态完成结果；字段由 J3-01 登记补全。 */
public record CompletedAnswer(String taskId, String status) {
}
