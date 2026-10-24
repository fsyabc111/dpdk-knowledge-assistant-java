package io.dpdk.assistant.contracts.model;

/** harness 回复；payload 形态由 J1-02 登记补全。 */
public record HarnessReply(SubmissionPhase submissionPhase, String payloadJson, String error) {

    /** NOT_SUBMITTED|SUBMITTED|UNKNOWN（契约 transaction-ports.json：model.HarnessPort）。 */
    public enum SubmissionPhase {NOT_SUBMITTED, SUBMITTED, UNKNOWN}
}
