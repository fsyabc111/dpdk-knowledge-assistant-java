package io.dpdk.assistant.assignment.report;

/**
 * 成绩汇总行（对齐 schemas.AssignmentGradeRow 与 `_grades_for_assignment_sync`）。
 *
 * scores 仅含当前协议项内且已有提交的协议（未提交协议不在字典，前端显示「—」）；
 * submission_count 计入该作业全部提交（含已不在当前协议项内的协议）；
 * last_submittedAt 为 Python str(datetime) 形态（渲染后的字符串，供 CSV 与 JSON）。
 */
public record GradeRow(
        String userId,
        /** users.created_by（老师学号）；账号已删/未分配为 null（CSV 归「未分配」）。 */
        String classTeacher,
        java.util.Map<String, Double> scores,
        double totalEarned,
        int submissionCount,
        String lastSubmittedAt) {
}
