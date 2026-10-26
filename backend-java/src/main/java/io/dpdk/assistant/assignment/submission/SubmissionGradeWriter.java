package io.dpdk.assistant.assignment.submission;

import io.dpdk.assistant.contracts.identity.AccountWriteGuard;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.DbTx;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Repository;

/**
 * 成绩写入（冻结 services/grading_service.py 的 record_submission，单事务）：
 * AccountWriteGuard 锁 enabled 账号行（UPDATE users SET enabled=enabled ... RETURNING，
 * 与停号事务按提交顺序裁决——先提交者决定成绩能否落库）→ INSERT grading_submissions。
 * 最小化落库：只存代码 sha256 + 结果 JSON + 受理依据，不存源码全文；
 * session_id/task_job_id 恒 NULL（正式提交无任务账本；聊天任务路径归 J3-01）。
 */
@Repository
public class SubmissionGradeWriter {

    /** 一条待落成绩（resultsJson 为已序列化的 results_json 全文）。 */
    public record RecordInput(String userId, String protocol, String codeHash, int score,
            int passed, int total, String resultsJson, String assignmentId) {
    }

    private final TransactionRunner runner;
    private final AccountWriteGuard writeGuard;
    private final ClockPort clock;
    private final io.dpdk.assistant.contracts.platform.UuidPort ids;

    public SubmissionGradeWriter(TransactionRunner runner, AccountWriteGuard writeGuard,
            ClockPort clock) {
        this(runner, writeGuard, clock, UUID::randomUUID);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SubmissionGradeWriter(TransactionRunner runner, AccountWriteGuard writeGuard,
            ClockPort clock, io.dpdk.assistant.contracts.platform.UuidPort ids) {
        this.runner = runner;
        this.writeGuard = writeGuard;
        this.clock = clock;
        this.ids = java.util.Objects.requireNonNull(ids);
    }

    /** 落库并返回 grade_* 成绩行 id；账号停用抛 403（PermissionError 同义）。 */
    public String recordSubmission(RecordInput input) {
        return runner.required(tx -> recordInTx(tx, input));
    }

    private String recordInTx(DbTx tx, RecordInput input) {
        if (!writeGuard.lockEnabled(tx, input.userId())) {
            throw new ApiDetailException(HttpStatus.FORBIDDEN, "账号已停用，本次评测不记成绩");
        }
        String submissionId = "grade_" + ids.next().toString().replace("-", "")
                .substring(0, 16);
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "INSERT INTO grading_submissions"
                        + "(id,user_id,session_id,protocol,code_hash,score,passed,total,"
                        + "results_json,assignment_id,task_job_id,created_at)"
                        + " VALUES (?,?,NULL,?,?,?,?,?,?::jsonb,?,NULL,?)")) {
            ps.setString(1, submissionId);
            ps.setString(2, input.userId());
            ps.setString(3, input.protocol());
            ps.setString(4, input.codeHash());
            ps.setInt(5, input.score());
            ps.setInt(6, input.passed());
            ps.setInt(7, input.total());
            ps.setString(8, input.resultsJson());
            if (input.assignmentId() == null) {
                ps.setObject(9, null);
            } else {
                ps.setString(9, input.assignmentId());
            }
            // now_iso() 为 ISO 文本（T 分隔 + 微秒），按 timestamptz 写入。
            ps.setObject(10, OffsetDateTime.parse(clock.nextSessionIso()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("grading submission storage failure", e);
        }
        return submissionId;
    }
}
