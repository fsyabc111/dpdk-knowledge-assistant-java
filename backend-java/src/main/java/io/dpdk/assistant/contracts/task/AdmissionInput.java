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
        int dailyQuota,
        TaskTemporaryInput temporaryInput) {

    public AdmissionInput {
        temporaryInput = temporaryInput == null ? TaskTemporaryInput.empty() : temporaryInput;
    }

    /** 兼容无附件消费者。 */
    public AdmissionInput(RequestIdentity identity, String subject, String sessionId,
                          String redactedQuestion, String context, String model,
                          String rawTemporaryInput, int dailyQuota) {
        this(identity, subject, sessionId, redactedQuestion, context, model,
                rawTemporaryInput, dailyQuota, TaskTemporaryInput.empty());
    }
}
