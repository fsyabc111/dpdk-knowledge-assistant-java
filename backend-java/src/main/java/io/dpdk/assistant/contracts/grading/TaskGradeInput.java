package io.dpdk.assistant.contracts.grading;

/** 任务评测成绩输入；resultsJson为原始results数组JSON，来源由成绩参与者单点包装。 */
public record TaskGradeInput(String taskId, String jobId, String protocol, String codeHash, int score,
                             int passed, int total, String resultsJson) {
}
