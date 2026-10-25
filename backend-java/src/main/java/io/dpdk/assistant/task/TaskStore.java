package io.dpdk.assistant.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.DbTx;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.contracts.usage.QuotaParticipant;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 任务账本存储（rag_requests / rag_task_inputs / rag_task_jobs；对齐
 * backend/app/services/rag_issue_service.py 的 J3-01 范围）。
 *
 * 事务形状与源一致：
 * - 受理各步骤（insertRequest/storeInputs/...）只接收 {@link DbTx}，由编排方
 *   （RagTaskService.admit）组合进一笔 caller 事务；任一步失败整笔回滚；
 * - probe/getTask/activeTaskForSession/loadTaskInputs/claimRecovery/orphanedPending/
 *   heartbeat 等单语句读改为独立事务（源每方法一个 get_conn 连接块）；
 * - finish/cancelTask/markInterrupted/cancelPendingForUser/reserveGradingJob 是
 *   多语句原子段，方法内自带一笔事务。
 *
 * 时间写入统一走 ClockPort.nextSessionIso()（进程内严格递增微秒，格式与 Python
 * datetime.now(timezone.utc).isoformat() 一致）；updated_at 条件比较沿用 ISO 文本
 * 字典序（B0015 保持 text 列）。
 */
public class TaskStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TransactionRunner runner;
    private final ClockPort clock;
    private final QuotaParticipant counters;
    private final Duration inputTtl;

    public TaskStore(TransactionRunner runner, ClockPort clock, QuotaParticipant counters,
                     Duration inputTtl) {
        this.runner = runner;
        this.clock = clock;
        this.counters = counters;
        this.inputTtl = inputTtl;
    }

    /* ------------------------------------------------------------------ */
    /* rag_requests：受理原子段的各步骤（全部参与 caller 事务）             */
    /* ------------------------------------------------------------------ */

    /** begin/begin_admitted 的 INSERT ... ON CONFLICT DO NOTHING；false=唯一键冲突。 */
    public boolean insertRequest(DbTx tx, String taskId, String requestId, String inputHash,
                                 boolean initialRequest, String purpose, String userId,
                                 String sessionId, String redactedQuestion, String context,
                                 String model) throws SQLException {
        Connection conn = tx.connection();
        String created = clock.nextSessionIso();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO rag_requests(id,request_id,input_hash,initial_request,purpose,user_id,"
                        + "session_id,question,context_text,model_version,created_at,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING")) {
            ps.setString(1, taskId);
            ps.setString(2, requestId);
            ps.setString(3, inputHash);
            ps.setInt(4, initialRequest ? 1 : 0);
            ps.setString(5, purpose);
            ps.setString(6, userId);
            ps.setString(7, sessionId);
            ps.setString(8, truncateFirst(redactedQuestion, 12000));
            // Python redact(context)[-2000:]：取末尾 2000 码点。
            ps.setString(9, truncateLast(context == null ? "" : context, 2000));
            ps.setString(10, model == null ? "" : model);
            ps.setString(11, created);
            ps.setString(12, created);
            return ps.executeUpdate() == 1;
        }
    }

    /** _store_task_inputs_in_conn：寄存执行输入；同 task 重复寄存是覆盖而非第二行。 */
    public void storeInputs(DbTx tx, String taskId, String question, String attachmentName,
                            String attachmentSuffix, String sourceText, String sourceOrigin)
            throws SQLException {
        String created = clock.nextSessionIso();
        String expires = pythonIso(clock.nowUtc().plus(inputTtl));
        Connection conn = tx.connection();
        String[] columns = {question, attachmentName, attachmentSuffix, sourceText, sourceOrigin,
                created, expires};
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO rag_task_inputs(task_id,question,attachment_name,attachment_suffix,"
                        + "source_text,source_origin,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT(task_id) DO UPDATE SET question=?,attachment_name=?,"
                        + "attachment_suffix=?,source_text=?,source_origin=?,created_at=?,expires_at=?")) {
            for (int round = 0; round < 2; round++) {
                ps.setString(1, taskId);
                for (int i = 0; i < columns.length; i++) {
                    ps.setString(2 + round * columns.length + i, columns[i]);
                }
            }
            ps.executeUpdate();
        }
    }

    /** load_task_inputs：过期行视为不存在并顺手删除（独立事务，源 get_conn 块）。 */
    public Optional<TaskInputRecord> loadInputs(String taskId) {
        return runner.required(tx -> {
            try {
                Connection conn = tx.connection();
                TaskInputRecord row = selectInputs(conn, taskId);
                if (row == null) {
                    return Optional.<TaskInputRecord>empty();
                }
                if (row.expiresAt().compareTo(pythonIso(clock.nowUtc())) <= 0) {
                    deleteInputs(conn, taskId);
                    return Optional.<TaskInputRecord>empty();
                }
                return Optional.of(row);
            } catch (SQLException e) {
                throw new IllegalStateException("task inputs read failure", e);
            }
        });
    }

    /** update_task_source：grade_plugin 首评路径回填执行源两列。 */
    public void updateTaskSource(String taskId, String sourceText, String sourceOrigin) {
        runner.required(tx -> {
            try (PreparedStatement ps = tx.connection().prepareStatement(
                    "UPDATE rag_task_inputs SET source_text=?,source_origin=? WHERE task_id=?")) {
                ps.setString(1, sourceText);
                ps.setString(2, sourceOrigin);
                ps.setString(3, taskId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("task source update failure", e);
            }
            return null;
        });
    }

    /** drop_task_inputs：终态即清（幂等）。 */
    public void dropInputs(String taskId) {
        runner.required(tx -> {
            try {
                deleteInputs(tx.connection(), taskId);
            } catch (SQLException e) {
                throw new IllegalStateException("task inputs drop failure", e);
            }
            return null;
        });
    }

    /* ------------------------------------------------------------------ */
    /* rag_requests：读取                                                  */
    /* ------------------------------------------------------------------ */

    /** probe 查询：session 形态 (request_id, session_id)。 */
    public Optional<TaskRecordRow> findByRequestAndSession(DbTx tx, String requestId, String sessionId)
            throws SQLException {
        return selectOne(tx, "SELECT " + REQUEST_COLUMNS + " FROM rag_requests "
                + "WHERE request_id=? AND session_id=?", ps -> {
            ps.setString(1, requestId);
            ps.setString(2, sessionId);
        });
    }

    /** probe 查询：initial_request 形态 (request_id, user_id, initial_request=1)。 */
    public Optional<TaskRecordRow> findByInitialRequest(DbTx tx, String requestId, String userId)
            throws SQLException {
        return selectOne(tx, "SELECT " + REQUEST_COLUMNS + " FROM rag_requests "
                + "WHERE request_id=? AND user_id=? AND initial_request=1", ps -> {
            ps.setString(1, requestId);
            ps.setString(2, userId);
        });
    }

    /** get_task：不存在或 user_id 不匹配都返回 empty（404 语义，不区分）。 */
    public Optional<TaskRecordRow> findOwned(String taskId, String userId) {
        return runner.required(tx -> {
            try {
                return selectOne(tx, "SELECT " + REQUEST_COLUMNS + " FROM rag_requests WHERE id=?",
                        ps -> ps.setString(1, taskId));
            } catch (SQLException e) {
                throw new IllegalStateException("task read failure", e);
            }
        }).filter(row -> row.userId() != null && row.userId().equals(userId));
    }

    /** active_task_for_session：会话内最近一条 pending（updated_at 倒序）。 */
    public Optional<TaskRecordRow> activeTaskForSession(String sessionId, String userId) {
        return runner.required(tx -> {
            try {
                return selectOne(tx, "SELECT " + REQUEST_COLUMNS + " FROM rag_requests "
                        + "WHERE session_id=? AND user_id=? AND status='pending' "
                        + "ORDER BY updated_at DESC LIMIT 1", ps -> {
                    ps.setString(1, sessionId);
                    ps.setString(2, userId);
                });
            } catch (SQLException e) {
                throw new IllegalStateException("active task read failure", e);
            }
        });
    }

    /** orphaned_pending：pending 且 updated_at 早于 now-grace（最旧在前）。 */
    public List<TaskRecordRow> orphanedPending(Duration grace) {
        String cutoff = pythonIso(clock.nowUtc().minus(grace));
        return runner.required(tx -> {
            try (PreparedStatement ps = tx.connection().prepareStatement(
                    "SELECT " + REQUEST_COLUMNS + " FROM rag_requests "
                            + "WHERE status='pending' AND updated_at<? ORDER BY updated_at")) {
                ps.setString(1, cutoff);
                try (ResultSet rs = ps.executeQuery()) {
                    List<TaskRecordRow> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(requestRow(rs));
                    }
                    return rows;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("orphan scan failure", e);
            }
        });
    }

    /** 终态行正文（含取消占位消息）；无 message_id → empty（独立事务）。 */
    public Optional<MessageRecord> loadRowMessage(String messageId) {
        return runner.required(tx -> {
            try {
                return loadRowMessageInTx(tx, messageId);
            } catch (SQLException e) {
                throw new IllegalStateException("row message read failure", e);
            }
        });
    }

    /** probe 事务内读取消息（参与 caller 事务）。 */
    public Optional<MessageRecord> loadRowMessageInTx(DbTx tx, String messageId) throws SQLException {
        try (PreparedStatement ps = tx.connection().prepareStatement(
                "SELECT content, metadata_json FROM assistant_messages WHERE id=?")) {
            ps.setString(1, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                return Optional.of(new MessageRecord(rs.getString("content"),
                        rs.getString("metadata_json")));
            }
        }
    }

    public record MessageRecord(String content, String metadataJson) {
    }

    /* ------------------------------------------------------------------ */
    /* rag_requests：条件更新原子段                                          */
    /* ------------------------------------------------------------------ */

    /** cancel_task：pending→cancelled + 占位消息 + 输入清理同事务；已非 pending 返回 false。 */
    public boolean cancelTask(String taskId, String reason, String actorId) {
        return runner.required(tx -> {
            try {
                Connection conn = tx.connection();
                int changed;
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE rag_requests SET status='cancelled',reason=?,updated_at=? "
                                + "WHERE id=? AND status='pending'")) {
                    ps.setString(1, reason);
                    ps.setString(2, clock.nextSessionIso());
                    ps.setString(3, taskId);
                    changed = ps.executeUpdate();
                }
                if (changed != 1) {
                    return false;
                }
                String sessionId;
                String requestId;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT session_id, request_id FROM rag_requests WHERE id=?")) {
                    ps.setString(1, taskId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return false;
                        }
                        sessionId = rs.getString(1);
                        requestId = rs.getString(2);
                    }
                }
                // 会话已被清理（session_id 为 NULL）时跳过占位消息，行保持 terminal 无正文。
                if (sessionId != null && !sessionId.isEmpty()) {
                    String messageId = UUID.randomUUID().toString().replace("-", "");
                    Map<String, Object> metadata = new LinkedHashMap<>();
                    metadata.put("stream_status", "cancelled");
                    metadata.put("task_id", taskId);
                    metadata.put("request_id", requestId);
                    metadata.put("actor_id", actorId);
                    metadata.put("answer_status", "cancelled");
                    metadata.put("answer_reason", reason);
                    insertMessage(conn, messageId, sessionId, "assistant", "（请求已取消）", metadata);
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE rag_requests SET message_id=? WHERE id=?")) {
                        ps.setString(1, messageId);
                        ps.setString(2, taskId);
                        ps.executeUpdate();
                    }
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE assistant_sessions SET updated_at=? WHERE id=?")) {
                        ps.setString(1, clock.nextSessionIso());
                        ps.setString(2, sessionId);
                        ps.executeUpdate();
                    }
                }
                deleteInputs(conn, taskId);
                return true;
            } catch (SQLException e) {
                throw new IllegalStateException("task cancel failure", e);
            }
        });
    }

    /** mark_interrupted：pending→system_error/reason，登记 rag_issues 并清理临时输入。 */
    public boolean markInterrupted(String taskId, String reason) {
        return runner.required(tx -> {
            try {
                Connection conn = tx.connection();
                int changed;
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE rag_requests SET status='system_error',reason=?,updated_at=? "
                                + "WHERE id=? AND status='pending'")) {
                    ps.setString(1, reason);
                    ps.setString(2, clock.nextSessionIso());
                    ps.setString(3, taskId);
                    changed = ps.executeUpdate();
                }
                if (changed != 1) {
                    return false;
                }
                insertIssue(conn, taskId);
                deleteInputs(conn, taskId);
                return true;
            } catch (SQLException e) {
                throw new IllegalStateException("task interrupt failure", e);
            }
        });
    }

    /** cancel_pending_for_user：批量 pending→cancelled 并清输入；不写占位消息（源差异位）。 */
    public int cancelPendingForUser(String userId, String reason) {
        return runner.required(tx -> {
            try {
                Connection conn = tx.connection();
                List<String> ids = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id FROM rag_requests WHERE user_id=? AND status='pending'")) {
                    ps.setString(1, userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            ids.add(rs.getString(1));
                        }
                    }
                }
                if (ids.isEmpty()) {
                    return 0;
                }
                String marks = repeat("?", ids.size());
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE rag_requests SET status='cancelled',reason=?,updated_at=? "
                                + "WHERE id IN (" + marks + ")")) {
                    ps.setString(1, reason);
                    ps.setString(2, clock.nextSessionIso());
                    for (int i = 0; i < ids.size(); i++) {
                        ps.setString(3 + i, ids.get(i));
                    }
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM rag_task_inputs WHERE task_id IN (" + marks + ")")) {
                    for (int i = 0; i < ids.size(); i++) {
                        ps.setString(1 + i, ids.get(i));
                    }
                    ps.executeUpdate();
                }
                return ids.size();
            } catch (SQLException e) {
                throw new IllegalStateException("batch cancel failure", e);
            }
        });
    }

    /** claim_recovery：pending 且仍陈旧才拨新 updated_at；否则 rowcount=0 拒绝。 */
    public boolean claimRecovery(String taskId, Instant staleCutoff) {
        return runner.required(tx -> {
            try (PreparedStatement ps = tx.connection().prepareStatement(
                    "UPDATE rag_requests SET updated_at=? "
                            + "WHERE id=? AND status='pending' AND updated_at<?")) {
                ps.setString(1, clock.nextSessionIso());
                ps.setString(2, taskId);
                ps.setString(3, pythonIso(staleCutoff));
                return ps.executeUpdate() == 1;
            } catch (SQLException e) {
                throw new IllegalStateException("claim recovery failure", e);
            }
        });
    }

    /** heartbeat_owned：在跑任务的 pending 行 updated_at 拨到当前时刻。 */
    public int heartbeat(List<String> ownedIds) {
        if (ownedIds.isEmpty()) {
            return 0;
        }
        return runner.required(tx -> {
            try (PreparedStatement ps = tx.connection().prepareStatement(
                    "UPDATE rag_requests SET updated_at=? WHERE status='pending' AND id IN ("
                            + repeat("?", ownedIds.size()) + ")")) {
                ps.setString(1, clock.nextSessionIso());
                for (int i = 0; i < ownedIds.size(); i++) {
                    ps.setString(2 + i, ownedIds.get(i));
                }
                return ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("heartbeat failure", e);
            }
        });
    }

    /**
     * finish：pending→终态条件更新、assistant message、ranked citations、issue(若需要)、
     * session touch、删除临时输入同一事务。重复终态（条件更新 0 行）拒绝。
     */
    public String finish(String taskId, String sessionId, String answer,
                         List<CitationData> citations, String status, String reason,
                         String releaseId, long elapsedMs) {
        return runner.required(tx -> {
            try {
                Connection conn = tx.connection();
                String requestId = null;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT request_id FROM rag_requests WHERE id=?")) {
                    ps.setString(1, taskId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            requestId = rs.getString(1);
                        }
                    }
                }
                String messageId = UUID.randomUUID().toString().replace("-", "");
                int changed;
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE rag_requests SET status=?,reason=?,release_id=?,evidence_json=?,"
                                + "elapsed_ms=?,message_id=?,updated_at=? WHERE id=? AND status='pending'")) {
                    ps.setString(1, status);
                    ps.setString(2, reason);
                    ps.setString(3, releaseId == null ? "" : releaseId);
                    ps.setString(4, evidenceJson(citations));
                    ps.setInt(5, (int) Math.min(elapsedMs, Integer.MAX_VALUE));
                    ps.setString(6, messageId);
                    ps.setString(7, clock.nextSessionIso());
                    ps.setString(8, taskId);
                    changed = ps.executeUpdate();
                }
                if (changed != 1) {
                    throw new IllegalStateException("请求已结束，禁止重复保存回答");
                }
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("task_id", taskId);
                metadata.put("confidence", "answered".equals(status) ? "high" : "low");
                metadata.put("answer_status", status);
                metadata.put("answer_reason", reason);
                metadata.put("request_id", requestId);
                metadata.put("stream_status", "completed");
                if (!"answered".equals(status) && !"not_applicable".equals(status)) {
                    insertIssue(conn, taskId);
                    metadata.put("issue_id", taskId);
                }
                insertMessage(conn, messageId, sessionId, "assistant", answer, metadata);
                for (int rank = 0; rank < citations.size(); rank++) {
                    CitationData citation = citations.get(rank);
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO assistant_citations(id,message_id,chunk_id,citation_engine,score,rank) "
                                    + "VALUES (?,?,?,?,?,?)")) {
                        ps.setString(1, UUID.randomUUID().toString().replace("-", ""));
                        ps.setString(2, messageId);
                        ps.setString(3, citation.chunkId());
                        ps.setString(4, citation.chunkId() != null && citation.chunkId().startsWith("li_")
                                ? "llama_index" : "legacy");
                        ps.setDouble(5, citation.score());
                        ps.setInt(6, rank + 1);
                        ps.executeUpdate();
                    }
                }
                if (sessionId != null && !sessionId.isEmpty()) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE assistant_sessions SET updated_at=? WHERE id=?")) {
                        ps.setString(1, clock.nextSessionIso());
                        ps.setString(2, sessionId);
                        ps.executeUpdate();
                    }
                }
                // 任务到达终态即清理临时输入（§8：24h 只是兜底上限）。
                deleteInputs(conn, taskId);
                return messageId;
            } catch (SQLException e) {
                throw new IllegalStateException("task finish failure", e);
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* rag_task_jobs：作业账本                                              */
    /* ------------------------------------------------------------------ */

    /** insert_job：同 (task_id, job_id) 幂等；返回是否新增（created）。 */
    private boolean insertJob(Connection conn, String taskId, String jobId, String tool,
                              String protocol, String codeHash) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO rag_task_jobs(id,task_id,job_id,tool,protocol,code_hash,status,"
                        + "created_at,updated_at) VALUES (?,?,?,?,?,?,'submitted',?,?) "
                        + "ON CONFLICT(task_id, job_id) DO NOTHING")) {
            ps.setString(1, UUID.randomUUID().toString().replace("-", ""));
            ps.setString(2, taskId);
            ps.setString(3, jobId);
            ps.setString(4, tool);
            ps.setString(5, protocol);
            ps.setString(6, codeHash);
            ps.setString(7, clock.nextSessionIso());
            ps.setString(8, clock.nextSessionIso());
            return ps.executeUpdate() == 1;
        }
    }

    /** record_job：同 (task_id, job_id) 幂等，重复登记不产生第二行。 */
    public void recordJob(String taskId, String jobId, String tool, String protocol, String codeHash) {
        runner.required(tx -> {
            try {
                insertJob(tx.connection(), taskId, jobId, tool, protocol, codeHash);
            } catch (SQLException e) {
                throw new IllegalStateException("job record failure", e);
            }
            return null;
        });
    }

    /** reserve_grading_job：首次登记 job 与评测日配额同事务；已登记不再扣第二次。 */
    public boolean reserveGradingJob(String taskId, String jobId, String protocol, String codeHash,
                                     String userId, int quota, String utcDay) {
        return runner.required(tx -> {
            try {
                boolean created = insertJob(tx.connection(), taskId, jobId, "grade_plugin",
                        protocol, codeHash);
                if (created) {
                    counters.countAndCheck(tx, userId, java.time.LocalDate.parse(utcDay), "grading", quota);
                }
                return created;
            } catch (SQLException e) {
                throw new IllegalStateException("job reserve failure", e);
            }
        });
    }

    /** finish_job：结果 JSON 写入（submitted→finished）。 */
    public void finishJob(String jobId, String resultJson) {
        runner.required(tx -> {
            try (PreparedStatement ps = tx.connection().prepareStatement(
                    "UPDATE rag_task_jobs SET status='finished',result_json=?,updated_at=? "
                            + "WHERE job_id=?")) {
                ps.setString(1, resultJson);
                ps.setString(2, clock.nextSessionIso());
                ps.setString(3, jobId);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("job finish failure", e);
            }
            return null;
        });
    }

    /** find_job：protocol/code_hash 为 null 时按 IS NULL 匹配（确定性 job_id 检索）。 */
    public Optional<TaskJobRecord> findJob(String taskId, String protocol, String codeHash) {
        return runner.required(tx -> {
            try {
                List<String> clauses = new ArrayList<>(List.of("task_id=?"));
                List<String> params = new ArrayList<>(List.of(taskId));
                for (String[] columnValue : new String[][]{{"protocol", protocol}, {"code_hash", codeHash}}) {
                    if (columnValue[1] == null) {
                        clauses.add(columnValue[0] + " IS NULL");
                    } else {
                        clauses.add(columnValue[0] + "=?");
                        params.add(columnValue[1]);
                    }
                }
                try (PreparedStatement ps = tx.connection().prepareStatement(
                        "SELECT " + JOB_COLUMNS + " FROM rag_task_jobs WHERE "
                                + String.join(" AND ", clauses))) {
                    for (int i = 0; i < params.size(); i++) {
                        ps.setString(i + 1, params.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? Optional.of(jobRow(rs)) : Optional.<TaskJobRecord>empty();
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("job find failure", e);
            }
        });
    }

    /** 按 (task_id, job_id) 精确读取（TaskGradeParticipant 用）。 */
    public Optional<TaskJobRecord> jobByTaskAndJobId(Connection conn, String taskId, String jobId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + JOB_COLUMNS + " FROM rag_task_jobs WHERE task_id=? AND job_id=?")) {
            ps.setString(1, taskId);
            ps.setString(2, jobId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(jobRow(rs)) : Optional.<TaskJobRecord>empty();
            }
        }
    }

    /** 按 task_id 读行（TaskGradeParticipant 需要 user_id/session_id）。 */
    public Optional<TaskRecordRow> requestById(Connection conn, String taskId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT " + REQUEST_COLUMNS + " FROM rag_requests WHERE id=?")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(requestRow(rs)) : Optional.<TaskRecordRow>empty();
            }
        }
    }

    public Optional<TaskInputRecord> inputsById(Connection conn, String taskId) throws SQLException {
        return Optional.ofNullable(selectInputs(conn, taskId));
    }

    /* ------------------------------------------------------------------ */
    /* 内部工具                                                            */
    /* ------------------------------------------------------------------ */

    private static final String REQUEST_COLUMNS = "id,request_id,input_hash,initial_request,purpose,"
            + "user_id,session_id,message_id,question,context_text,status,reason,release_id,"
            + "evidence_json,model_version,elapsed_ms,created_at,updated_at";
    private static final String JOB_COLUMNS = "id,task_id,job_id,tool,protocol,code_hash,status,"
            + "result_json,submission_done,created_at,updated_at";

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private Optional<TaskRecordRow> selectOne(DbTx tx, String sql, Binder binder) throws SQLException {
        try (PreparedStatement ps = tx.connection().prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(requestRow(rs)) : Optional.<TaskRecordRow>empty();
            }
        }
    }

    private static TaskRecordRow requestRow(ResultSet rs) throws SQLException {
        return new TaskRecordRow(
                rs.getString("id"), rs.getString("request_id"), rs.getString("input_hash"),
                rs.getInt("initial_request") == 1, rs.getString("purpose"), rs.getString("user_id"),
                rs.getString("session_id"), rs.getString("message_id"), rs.getString("question"),
                rs.getString("context_text"), rs.getString("status"), rs.getString("reason"),
                rs.getString("release_id"), rs.getString("evidence_json"),
                rs.getString("model_version"), rs.getInt("elapsed_ms"),
                rs.getString("created_at"), rs.getString("updated_at"));
    }

    private static TaskJobRecord jobRow(ResultSet rs) throws SQLException {
        return new TaskJobRecord(rs.getString("id"), rs.getString("task_id"), rs.getString("job_id"),
                rs.getString("tool"), rs.getString("protocol"), rs.getString("code_hash"),
                rs.getString("status"), rs.getString("result_json"),
                rs.getInt("submission_done") == 1, rs.getString("created_at"),
                rs.getString("updated_at"));
    }

    private TaskInputRecord selectInputs(Connection conn, String taskId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT task_id,question,attachment_name,attachment_suffix,source_text,source_origin,"
                        + "created_at,expires_at FROM rag_task_inputs WHERE task_id=?")) {
            ps.setString(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new TaskInputRecord(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getString(8));
            }
        }
    }

    private static void deleteInputs(Connection conn, String taskId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM rag_task_inputs WHERE task_id=?")) {
            ps.setString(1, taskId);
            ps.executeUpdate();
        }
    }

    /** _issue：登记 rag_issues（ON CONFLICT(request_id) DO NOTHING；updated_at 为 text 列）。 */
    private void insertIssue(Connection conn, String taskId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO rag_issues(id,request_id,updated_at) VALUES (?,?,?) "
                        + "ON CONFLICT(request_id) DO NOTHING")) {
            ps.setString(1, UUID.randomUUID().toString().replace("-", ""));
            ps.setString(2, taskId);
            ps.setString(3, pythonIso(clock.nowUtc()));
            ps.executeUpdate();
        }
    }

    /** assistant_messages 写入（受理/终态/取消路径共用；metadata 走 jsonb cast）。 */
    private void insertMessage(Connection conn, String messageId, String sessionId, String role,
                               String content, Map<String, Object> metadata) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO assistant_messages(id,session_id,role,content,metadata_json,created_at) "
                        + "VALUES (?,?,?,?,?::jsonb,?)")) {
            ps.setString(1, messageId);
            ps.setString(2, sessionId);
            ps.setString(3, role);
            ps.setString(4, content);
            ps.setString(5, toJson(metadata));
            ps.setObject(6, java.time.OffsetDateTime.parse(clock.nextSessionIso()));
            ps.executeUpdate();
        }
    }

    private static String evidenceJson(List<CitationData> citations) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        for (CitationData citation : citations) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunk_id", citation.chunkId());
            item.put("source_path", citation.sourcePath());
            item.put("section", citation.section());
            evidence.add(item);
        }
        return toJson(evidence);
    }

    static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("task json serialization failure", e);
        }
    }

    /** Python datetime.isoformat 同形时间串（ClockPort 语义注释所指的 +00:00 形态）。 */
    private static String pythonIso(Instant instant) {
        return io.dpdk.assistant.platform.time.SystemClockAdapter.pythonIso(
                instant.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    /** 按码点取前 max 个（Python 字符串切片按码点）。 */
    static String truncateFirst(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.codePointCount(0, value.length()) <= max ? value
                : value.substring(0, value.offsetByCodePoints(0, max));
    }

    /** 按码点取末尾 max 个（redact(context)[-2000:] 同位）。 */
    static String truncateLast(String value, int max) {
        if (value == null) {
            return "";
        }
        int total = value.codePointCount(0, value.length());
        return total <= max ? value
                : value.substring(value.offsetByCodePoints(0, total - max));
    }

    static String repeat(String mark, int count) {
        StringBuilder sb = new StringBuilder(mark);
        for (int i = 1; i < count; i++) {
            sb.append(',').append(mark);
        }
        return sb.toString();
    }
}
