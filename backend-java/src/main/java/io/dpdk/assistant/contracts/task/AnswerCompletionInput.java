package io.dpdk.assistant.contracts.task;

import java.util.List;

/** 终态答案（契约 transaction-ports.json：task.AnswerCompletion.data_fields）。 */
public record AnswerCompletionInput(
        String taskId,
        String sessionId,
        String answer,
        List<Object> orderedCitations,
        String verdict,
        String reason,
        String releaseId,
        long elapsedMs) {
}
