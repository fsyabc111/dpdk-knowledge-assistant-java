package io.dpdk.assistant.task;

import io.dpdk.assistant.contracts.grading.TaskGradeInput;
import io.dpdk.assistant.contracts.grading.TaskGradeParticipant;
import io.dpdk.assistant.contracts.grading.TaskGradeResult;
import io.dpdk.assistant.contracts.identity.AccountWriteGuard;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * contracts.grading.TaskGradeParticipant 实现（J2-04 契约、J3-01 接线；对齐
 * backend/app/services/grading_service.py:_record_task_job_submission_sync）。
 *
 * grading 事务内按序锁 enabled user、pending task 和 job；成绩 insert 与
 * submission_done 同提交；task_job_id 部分唯一索引防重复；0014 以前已标完成
 * 无关联成绩返回 legacy_unlinked，不猜测补写。
 *
 * 输入契约缺 user_id/session_id/source_origin 三字段（登记在交接）：它们取自
 * 任务行（user_id/session_id）与任务临时输入（source_origin）——与 Python 调用点
 * 传入值同源，账本层不猜测业务语义。
 */
public class TaskGradeLedger implements TaskGradeParticipant {

    private final TaskStore store;
    private final AccountWriteGuard guard;
    private final TransactionRunner runner;
    private final io.dpdk.assistant.contracts.platform.ClockPort clock;

    public TaskGradeLedger(TaskStore store, AccountWriteGuard guard, TransactionRunner runner,
                           io.dpdk.assistant.contracts.platform.ClockPort clock) {
        this.store = store;
        this.guard = guard;
        this.runner = runner;
        this.clock = clock;
    }

    /** 仅账本形态（无账号锁场景由测试装配 guard=恒 true）。 */
    public TaskGradeLedger(TaskStore store, io.dpdk.assistant.contracts.platform.ClockPort clock) {
        this(store, (tx, subject) -> true, null, clock);
    }

    @Override
    public TaskGradeResult recordTaskGrade(TaskGradeInput input) {
        return runner.required(tx -> {
            try {
                Connection conn = tx.connection();
                Optional<TaskRecordRow> task = store.requestById(conn, input.taskId());
                if (task.isEmpty()) {
                    return TaskGradeResult.cancelled;
                }
                String userId = task.get().userId();
                String sessionId = task.get().sessionId();
                if (userId == null || !guard.lockEnabled(tx, userId)) {
                    return TaskGradeResult.cancelled;
                }
                // 条件 UPDATE 锁 pending task 行（SET status=status：只锁不写）。
                boolean pending;
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE rag_requests SET status=status WHERE id=? AND user_id=? "
                                + "AND status='pending' RETURNING id")) {
                    ps.setString(1, input.taskId());
                    ps.setString(2, userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        pending = rs.next();
                    }
                }
                if (!pending) {
                    return TaskGradeResult.cancelled;
                }
                // 条件 UPDATE 锁 job 行并读 submission_done 闸。
                boolean jobPresent;
                boolean submissionDone;
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE rag_task_jobs SET updated_at=updated_at WHERE task_id=? AND job_id=? "
                                + "RETURNING submission_done")) {
                    ps.setString(1, input.taskId());
                    ps.setString(2, input.jobId());
                    try (ResultSet rs = ps.executeQuery()) {
                        jobPresent = rs.next();
                        submissionDone = jobPresent && rs.getInt(1) == 1;
                    }
                }
                if (!jobPresent) {
                    throw new IllegalStateException("评测作业账本缺行，拒绝无账本记成绩");
                }
                boolean existing;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id FROM grading_submissions WHERE task_job_id=?")) {
                    ps.setString(1, input.jobId());
                    try (ResultSet rs = ps.executeQuery()) {
                        existing = rs.next();
                    }
                }
                if (existing) {
                    return TaskGradeResult.persisted;
                }
                if (submissionDone) {
                    // 0014 以前的成绩没有 job 身份，不能靠协议/hash 猜测补写。
                    return TaskGradeResult.legacy_unlinked;
                }
                String sourceOrigin = store.inputsById(conn, input.taskId())
                        .map(TaskInputRecord::sourceOrigin).orElse(null);
                insertSubmission(conn, userId, sessionId, input, sourceOrigin);
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE rag_task_jobs SET submission_done=1,updated_at=? WHERE job_id=?")) {
                    ps.setString(1, clockIso());
                    ps.setString(2, input.jobId());
                    ps.executeUpdate();
                }
                return TaskGradeResult.persisted;
            } catch (SQLException e) {
                throw new IllegalStateException("task grade record failure", e);
            }
        });
    }

    private void insertSubmission(Connection conn, String userId, String sessionId,
                                  TaskGradeInput input, String sourceOrigin) throws SQLException {
        Map<String, Object> resultsDoc = new LinkedHashMap<>();
        resultsDoc.put("source_origin", sourceOrigin);
        resultsDoc.put("results", parseResults(input.resultsJson()));
        String submissionId = "grade_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO grading_submissions"
                        + "(id,user_id,session_id,protocol,code_hash,score,passed,total,results_json,"
                        + "assignment_id,task_job_id,created_at) VALUES (?,?,?,?,?,?,?,?,?::jsonb,NULL,?,?)")) {
            ps.setString(1, submissionId);
            ps.setString(2, userId);
            ps.setString(3, sessionId);
            ps.setString(4, input.protocol());
            ps.setString(5, input.codeHash());
            ps.setInt(6, input.score());
            ps.setInt(7, input.passed());
            ps.setInt(8, input.total());
            ps.setString(9, TaskStore.toJson(resultsDoc));
            ps.setString(10, input.jobId());
            ps.setObject(11, java.time.OffsetDateTime.parse(clockIso()));
            ps.executeUpdate();
        }
    }

    private static Object parseResults(String resultsJson) {
        if (resultsJson == null || resultsJson.isEmpty()) {
            return java.util.List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(resultsJson);
        } catch (Exception e) {
            throw new IllegalStateException("task grade results_json 不是合法 JSON", e);
        }
    }

    private String clockIso() {
        return io.dpdk.assistant.platform.time.SystemClockAdapter.pythonIso(
                clock.nowUtc().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }
}
