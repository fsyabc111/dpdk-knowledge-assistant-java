package io.dpdk.assistant.grading.query;

import io.dpdk.assistant.contracts.platform.DbTx;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.session.SessionTime;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * grading_submissions 只读查询（对齐 services/grading_service.py 的教师视图子集：
 * list_submissions / aggregate_by_user / aggregate_by_protocol）。
 *
 * 明细只返回最小字段集（不含 results_json/code_hash）；user_ids 为树隔离范围
 * （老师=自己学生，不含本人；null=不限；空集合=不可见任何行）。
 * ORDER BY created_at DESC, id DESC 兜底同时间戳稳定序。
 */
@Repository
public class GradingQueryRepository {

    private final TransactionRunner runner;

    public record SubmissionRow(String id, String userId, String protocol, int score, int passed,
                                int total, String createdAt) {
    }

    public record UserSummaryRow(String userId, int submissionCount, double avgScore, int bestScore,
                                 String lastSubmittedAt) {
    }

    public record ProtocolSummaryRow(String protocol, int submissionCount, double avgScore,
                                     int passedTotal, int totalTotal, int studentCount) {
    }

    public GradingQueryRepository(TransactionRunner runner) {
        this.runner = runner;
    }

    /** 明细（newest-first；user_id/protocol 等值过滤可叠加；limit/offset 分页）。 */
    public List<SubmissionRow> listSubmissions(String userId, String protocol, int limit,
                                               int offset, List<String> scopeUserIds) {
        if (scopeUserIds != null && scopeUserIds.isEmpty()) {
            return List.of();
        }
        return runner.required((DbTx tx) -> {
            List<String> clauses = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            if (userId != null) {
                clauses.add("user_id = ?");
                params.add(userId);
            }
            if (protocol != null) {
                clauses.add("protocol = ?");
                params.add(protocol);
            }
            if (scopeUserIds != null) {
                clauses.add("user_id IN (" + "?" + ", ?".repeat(scopeUserIds.size() - 1) + ")");
                params.addAll(scopeUserIds);
            }
            String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
            String sql = "SELECT id, user_id, protocol, score, passed, total, created_at "
                    + "FROM grading_submissions" + where
                    + " ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
            try (PreparedStatement ps = tx.connection().prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                ps.setInt(params.size() + 1, limit);
                ps.setInt(params.size() + 2, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    List<SubmissionRow> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new SubmissionRow(rs.getString("id"), rs.getString("user_id"),
                                rs.getString("protocol"), rs.getInt("score"), rs.getInt("passed"),
                                rs.getInt("total"),
                                SessionTime.pythonStr(SessionTime.utc(
                                        rs.getObject("created_at", OffsetDateTime.class)))));
                    }
                    return List.copyOf(out);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("grading query failure", e);
            }
        });
    }

    /** 按学生聚合（提交数/平均分/最高分/最近提交时间；user_id 升序）。 */
    public List<UserSummaryRow> aggregateByUser(List<String> scopeUserIds) {
        if (scopeUserIds != null && scopeUserIds.isEmpty()) {
            return List.of();
        }
        return aggregate(scopeUserIds, "user_id", "SELECT user_id, COUNT(*) AS n, "
                + "AVG(score) AS avg_score, MAX(score) AS best_score, "
                + "MAX(created_at) AS last_at FROM grading_submissions", rs -> new UserSummaryRow(
                rs.getString("user_id"), rs.getInt("n"), rs.getDouble("avg_score"),
                rs.getInt("best_score"),
                SessionTime.pythonStr(SessionTime.utc(rs.getObject("last_at", OffsetDateTime.class)))));
    }

    /** 按协议聚合（分子分母分别聚合，避免「各次比例的平均」失真；protocol 升序）。 */
    public List<ProtocolSummaryRow> aggregateByProtocol(List<String> scopeUserIds) {
        if (scopeUserIds != null && scopeUserIds.isEmpty()) {
            return List.of();
        }
        return aggregate(scopeUserIds, "protocol", "SELECT protocol, COUNT(*) AS n, "
                + "AVG(score) AS avg_score, SUM(passed) AS passed_total, "
                + "SUM(total) AS total_total, COUNT(DISTINCT user_id) AS student_count "
                + "FROM grading_submissions", rs -> new ProtocolSummaryRow(
                rs.getString("protocol"), rs.getInt("n"), rs.getDouble("avg_score"),
                rs.getInt("passed_total"), rs.getInt("total_total"), rs.getInt("student_count")));
    }

    private <T> List<T> aggregate(List<String> scopeUserIds, String groupField, String baseSql,
                                  RowMapper<T> mapper) {
        return runner.required((DbTx tx) -> {
            String where = "";
            List<Object> params = new ArrayList<>();
            if (scopeUserIds != null) {
                where = " WHERE user_id IN (" + "?" + ", ?".repeat(scopeUserIds.size() - 1) + ")";
                params.addAll(scopeUserIds);
            }
            String sql = baseSql + where + " GROUP BY " + groupField + " ORDER BY " + groupField;
            try (PreparedStatement ps = tx.connection().prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<T> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapper.map(rs));
                    }
                    return List.copyOf(out);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("grading aggregate failure", e);
            }
        });
    }

    @FunctionalInterface
    private interface RowMapper<T> {

        T map(ResultSet rs) throws SQLException;
    }

    /** 预留：作业汇总等值过滤（J2-03 消费时启用；本卡不暴露查询参数）。 */
    public List<SubmissionRow> listSubmissionsForAssignment(String assignmentId, int limit,
                                                            List<String> scopeUserIds) {
        if (scopeUserIds != null && scopeUserIds.isEmpty()) {
            return List.of();
        }
        Map<String, Object> unused = Map.of();
        return runner.required((DbTx tx) -> {
            List<Object> params = new ArrayList<>();
            String scopeClause = "";
            if (scopeUserIds != null) {
                scopeClause = " AND user_id IN (" + "?" + ", ?".repeat(scopeUserIds.size() - 1) + ")";
                params.addAll(scopeUserIds);
            }
            String sql = "SELECT id, user_id, protocol, score, passed, total, created_at "
                    + "FROM grading_submissions WHERE assignment_id = ?" + scopeClause
                    + " ORDER BY created_at DESC, id DESC LIMIT ?";
            try (PreparedStatement ps = tx.connection().prepareStatement(sql)) {
                ps.setString(1, assignmentId);
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(2 + i, params.get(i));
                }
                ps.setInt(2 + params.size(), limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<SubmissionRow> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new SubmissionRow(rs.getString("id"), rs.getString("user_id"),
                                rs.getString("protocol"), rs.getInt("score"), rs.getInt("passed"),
                                rs.getInt("total"),
                                SessionTime.pythonStr(SessionTime.utc(
                                        rs.getObject("created_at", OffsetDateTime.class)))));
                    }
                    return List.copyOf(out);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("grading assignment query failure", e);
            }
        });
    }
}
