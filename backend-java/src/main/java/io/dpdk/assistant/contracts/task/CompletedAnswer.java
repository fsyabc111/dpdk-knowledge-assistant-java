package io.dpdk.assistant.contracts.task;

/** 终态完成结果；messageId 仅在事务提交成功后可见。 */
public record CompletedAnswer(String taskId, String status, String messageId) {
}
