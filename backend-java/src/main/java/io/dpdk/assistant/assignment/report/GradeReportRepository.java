package io.dpdk.assistant.assignment.report;

import io.dpdk.assistant.assignment.catalog.AssignmentRow;
import io.dpdk.assistant.assignment.catalog.PythonScore;
import io.dpdk.assistant.contracts.platform.DbTx;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * 成绩汇总仓储（对齐 `_grades_for_assignment_sync` / `_class_teachers_sync`）。
 *
 * 每协议取 MAX(score) 按当前分值折算 earned；同一 (user, assignment) 的全部提交
 * （含已不在当前协议项内的协议）计入提交次数与最近提交时间；行按 user_id 升序。
 */
@Repository
public class GradeReportRepository {

    private final TransactionRunner runner;

    public GradeReportRepository(TransactionRunner runner) {
        this.runner = runner;
    }

    /** 按当前作业项汇总（不含树隔离与班级归属，路由层再过滤/补 class_teacher）。 */
    public List<GradeRow> gradesForAssignment(AssignmentRow assignment) {
        Map<String, Integer> pointsMap = new HashMap<>();
        for (AssignmentRow.ItemRow item : assignment.items()) {
            pointsMap.put(item.protocol(), item.points());
        }
        return runner.required((DbTx tx) -> {
            try {
                return aggregate(tx.connection(), assignment.id(), pointsMap);
            } catch (SQLException e) {
                throw new IllegalStateException("assignment storage failure", e);
            }
        });
    }

    private static List<GradeRow> aggregate(Connection conn, String assignmentId,
            Map<String, Integer> pointsMap) throws SQLException {
        Map<String, UserAgg> perUser = new LinkedHashMap<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT user_id, protocol, MAX(score) AS best_score, COUNT(*) AS cnt,"
                        + " MAX(created_at) AS last_at FROM grading_submissions"
                        + " WHERE assignment_id = ? GROUP BY user_id, protocol")) {
            ps.setString(1, assignmentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String userId = rs.getString("user_id");
                    String protocol = rs.getString("protocol");
                    UserAgg entry = perUser.computeIfAbsent(userId,
                            k -> new UserAgg(new LinkedHashMap<>(), 0, null));
                    if (pointsMap.containsKey(protocol)) {
                        entry.scores.put(protocol, PythonScore.calcEarned(
                                rs.getInt("best_score"), pointsMap.get(protocol)));
                    }
                    entry.submissionCount += rs.getInt("cnt");
                    String lastAt = PythonDateTimeStr.of(
                            rs.getObject("last_at", OffsetDateTime.class).toInstant());
                    // Python 按渲染后的 ISO 字符串比较取最大（同格式下与时间序一致）。
                    if (entry.lastAt == null || lastAt.compareTo(entry.lastAt) > 0) {
                        entry.lastAt = lastAt;
                    }
                }
            }
        }
        List<GradeRow> out = new ArrayList<>();
        for (Map.Entry<String, UserAgg> e : new java.util.TreeMap<>(perUser).entrySet()) {
            UserAgg agg = e.getValue();
            out.add(new GradeRow(e.getKey(), null, agg.scores,
                    PythonScore.round1(agg.scores.values().stream().mapToDouble(Double::doubleValue)
                            .sum()),
                    agg.submissionCount, agg.lastAt));
        }
        return out;
    }

    /** 批量查班级归属（users.created_by）；账号已删的不出现在结果（→ null）。 */
    public Map<String, String> classTeachers(List<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return runner.required((DbTx tx) -> {
            try {
                Map<String, String> out = new LinkedHashMap<>();
                StringBuilder marks = new StringBuilder();
                for (int i = 0; i < userIds.size(); i++) {
                    if (i > 0) {
                        marks.append(", ");
                    }
                    marks.append('?');
                }
                try (PreparedStatement ps = tx.connection().prepareStatement(
                        "SELECT id, created_by FROM users WHERE id IN (" + marks + ")")) {
                    for (int i = 0; i < userIds.size(); i++) {
                        ps.setString(i + 1, userIds.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.put(rs.getString("id"), rs.getString("created_by"));
                        }
                    }
                }
                return out;
            } catch (SQLException e) {
                throw new IllegalStateException("assignment storage failure", e);
            }
        });
    }

    private static final class UserAgg {

        final Map<String, Double> scores;
        int submissionCount;
        String lastAt;

        UserAgg(Map<String, Double> scores, int submissionCount, String lastAt) {
            this.scores = scores;
            this.submissionCount = submissionCount;
            this.lastAt = lastAt;
        }
    }
}
