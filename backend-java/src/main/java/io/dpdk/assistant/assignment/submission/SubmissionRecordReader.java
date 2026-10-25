package io.dpdk.assistant.assignment.submission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.stereotype.Repository;

/**
 * 成绩行只读查询端口（实现步骤 4：供 J2-03/J3-04 与「结果讲解」按
 * submission_id 关联成绩行；对齐 grading_service.get_submission 的整行读取，
 * results_json 反序列化返回）。查询不存在返回 empty，调用方决定 404 口径。
 */
@Repository
public class SubmissionRecordReader {

    /** 一条成绩行（resultsJson 为解析后的 jsonb）。 */
    public record SubmissionRecord(String id, String userId, String assignmentId,
            String taskJobId, String protocol, String codeHash, int score, int passed,
            int total, JsonNode resultsJson, OffsetDateTime createdAt) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TransactionRunner runner;

    public SubmissionRecordReader(TransactionRunner runner) {
        this.runner = runner;
    }

    public Optional<SubmissionRecord> get(String submissionId) {
        return runner.required(tx -> {
            try (PreparedStatement ps = tx.connection().prepareStatement(
                    "SELECT id, user_id, assignment_id, task_job_id, protocol, code_hash,"
                            + " score, passed, total, results_json, created_at"
                            + " FROM grading_submissions WHERE id = ?")) {
                ps.setString(1, submissionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<SubmissionRecord>empty();
                    }
                    String resultsJson = rs.getString("results_json");
                    JsonNode parsed;
                    try {
                        parsed = resultsJson == null ? null : MAPPER.readTree(resultsJson);
                    } catch (Exception e) {
                        throw new IllegalStateException("results_json is not valid JSON", e);
                    }
                    return Optional.of(new SubmissionRecord(rs.getString("id"),
                            rs.getString("user_id"), rs.getString("assignment_id"),
                            rs.getString("task_job_id"), rs.getString("protocol"),
                            rs.getString("code_hash"), rs.getInt("score"), rs.getInt("passed"),
                            rs.getInt("total"), parsed,
                            rs.getObject("created_at", OffsetDateTime.class)));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("grading submission read failure", e);
            }
        });
    }
}
