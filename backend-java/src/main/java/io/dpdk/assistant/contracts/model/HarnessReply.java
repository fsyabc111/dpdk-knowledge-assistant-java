package io.dpdk.assistant.contracts.model;

/**
 * harness 回复（J1-02 登记固化，见 migration/contracts/interface-changes.md）：
 * payloadJson 恒为规范化 JSON（成功=工具结果；失败={"ok":false,"error":...,"detail":...}）；
 * error 为错误码或 null，错误码含 harness_bad_request/harness_deadline_exceeded（均 NOT_SUBMITTED）。
 */
public record HarnessReply(SubmissionPhase submissionPhase, String payloadJson, String error) {

    /** NOT_SUBMITTED|SUBMITTED|UNKNOWN（契约 transaction-ports.json：model.HarnessPort）。 */
    public enum SubmissionPhase {NOT_SUBMITTED, SUBMITTED, UNKNOWN}
}
