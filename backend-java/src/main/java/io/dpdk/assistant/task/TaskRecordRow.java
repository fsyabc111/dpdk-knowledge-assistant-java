package io.dpdk.assistant.task;

/**
 * rag_requests 行（服务端任务身份；字段与 B0015 基线同位）。
 *
 * 时间列在库里是 ISO 文本（Python 基线按字符串字典序比较，这里保持原样）。
 */
public record TaskRecordRow(
        String id,
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

    /** 冻结端口快照（不暴露 task 仓储内部类型）。 */
    public io.dpdk.assistant.contracts.task.TaskRow toContract() {
        return new io.dpdk.assistant.contracts.task.TaskRow(id, requestId, inputHash,
                initialRequest, purpose, userId, sessionId, messageId, question, contextText,
                status, reason, releaseId, evidenceJson, modelVersion, elapsedMs, createdAt, updatedAt);
    }

    /** 终态判定：status != pending。 */
    public boolean isTerminal() {
        return !"pending".equals(status);
    }
}
