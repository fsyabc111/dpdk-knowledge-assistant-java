package io.dpdk.assistant.task;

/**
 * rag_task_inputs 行（任务执行输入的临时保管，§8 仅归属用户任务 scope）。
 */
public record TaskInputRecord(
        String taskId,
        String question,
        String attachmentName,
        String attachmentSuffix,
        String sourceText,
        String sourceOrigin,
        String createdAt,
        String expiresAt) {
}
