package io.dpdk.assistant.assignment.catalog;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 学生视角作业视图（对齐 schemas.StudentAssignment 与 `_student_view` 装配）。
 *
 * W4 口径：无提交记录的协议 earned=null（前端统一显示「未提交」），与「提交了但
 * 最高 0 分」的 0.0 区分；total_earned 聚合 null 按 0 计。
 * JSON 字段序按 schema 定义：id, title, description, created_by, deadline, status,
 * items, total_points, total_earned, submission_count（不含 scope/created_at/updated_at）。
 */
public record StudentAssignmentView(
        String id,
        String title,
        String description,
        String createdBy,
        Instant deadline,
        String status,
        List<StudentItemGrade> items,
        int totalPoints,
        double totalEarned,
        int submissionCount) {

    /** 逐协议成绩（StudentItemGrade）。 */
    public record StudentItemGrade(String protocol, int points, Integer bestScore, Double earned,
            int submissionCount) {
    }

    /** `_student_view` 装配：mine = protocol → {best, cnt}（仅当前学生）。 */
    static StudentAssignmentView assemble(AssignmentRow assignment,
            Map<String, AssignmentCatalogRepository.ProtocolStat> mine) {
        List<StudentItemGrade> items = new ArrayList<>();
        double totalEarned = 0.0;
        int submissionCount = 0;
        int totalPoints = 0;
        for (AssignmentRow.ItemRow item : assignment.items()) {
            AssignmentCatalogRepository.ProtocolStat stats = mine.get(item.protocol());
            Integer best = stats == null ? null : stats.bestScore();
            Double earned = stats == null ? null
                    : PythonScore.calcEarned(best, item.points());
            int count = stats == null ? 0 : stats.submissionCount();
            if (earned != null) {
                totalEarned += earned;
            }
            submissionCount += count;
            totalPoints += item.points();
            items.add(new StudentItemGrade(item.protocol(), item.points(), best, earned, count));
        }
        return new StudentAssignmentView(assignment.id(), assignment.title(),
                assignment.description(), assignment.createdBy(), assignment.deadline(),
                assignment.status(), List.copyOf(items), totalPoints,
                PythonScore.round1(totalEarned), submissionCount);
    }
}
