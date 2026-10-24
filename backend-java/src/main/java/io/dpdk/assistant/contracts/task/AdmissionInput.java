package io.dpdk.assistant.contracts.task;

/** 受理输入（契约 transaction-ports.json：task.AdmissionPort.data_fields.AdmissionInput）。 */
public record AdmissionInput(
        RequestIdentity identity,
        String subject,
        String sessionId,
        String redactedQuestion,
        String context,
        String model,
        String rawTemporaryInput,
        int dailyQuota) {
}
