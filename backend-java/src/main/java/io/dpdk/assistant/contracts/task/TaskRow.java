package io.dpdk.assistant.contracts.task;

/** 完整任务快照；输入源码只在临时表，不属于本行。 */
public record TaskRow(
        String taskId,
        String requestId,
        String inputHash,
        boolean initialRequest,
        String purpose,
        String userId,
        String sessionId,
        String messageId,
        String question,
        String contextText,
        String status,
        String reason,
        String releaseId,
        String evidenceJson,
        String modelVersion,
        int elapsedMs,
        String createdAt,
        String updatedAt) {
}
