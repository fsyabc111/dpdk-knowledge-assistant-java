package io.dpdk.assistant.task;

/**
 * rag_task_jobs 行（harness 作业账本：重启后按账本接续/复用结果）。
 */
public record TaskJobRecord(
        String id,
        String taskId,
        String jobId,
        String tool,
        String protocol,
        String codeHash,
        String status,
        String resultJson,
        boolean submissionDone,
        String createdAt,
        String updatedAt) {
}
