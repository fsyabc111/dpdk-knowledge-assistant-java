package io.dpdk.assistant.contracts.grading;

/** 任务评测成绩输入；字段由 J2-04 登记补全。 */
public record TaskGradeInput(String taskId, String jobId, String protocol, String codeHash, int score,
                             int passed, int total, String resultsJson) {
}
