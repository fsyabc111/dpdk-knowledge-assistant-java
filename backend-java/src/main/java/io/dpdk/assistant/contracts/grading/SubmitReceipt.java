package io.dpdk.assistant.contracts.grading;

/** 提交回执（submission_id/成绩等）；字段由 J2-04 登记补全。 */
public record SubmitReceipt(String submissionId, int score) {
}
