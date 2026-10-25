package io.dpdk.assistant.session;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.DbTx;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.contracts.session.SessionId;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话存储（对齐 backend/app/services/session_service.py 的 J2-05 范围：
 * 生命周期/历史/引用/release 绑定/维护端点）。
 *
 * 事务形状与源一致：
 * - ensureOwned/deleteSession/purge/storage 为独立提交的会话操作（每方法一笔事务）；
 * - appendMessage/touch 参与调用方事务（受理编排 J3-01 组合使用）；
 * - resolveRelease 首次绑定走「UPDATE 条件置值 + 回读赢家」CAS：并发首查唯一赢家，
 *   跨请求/重启不漂移（值在库），删除重建后是新行、重新绑定；
 * - discardEmpty 只清理自己刚建的空会话（无消息且无 rag_requests），不触碰赢家会话。
 *
 * 会话/消息 id：sess_/msg_/cit_ + 12hex；时间统一经 ClockPort.nextSessionIso()
 * （进程内严格递增微秒，对齐 Python now_iso）。
 */
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TransactionRunner runner;
    private final ClockPort clock;
    private final ActiveReleaseSupplier activeRelease;

    /** 活动发布解析（rag_index_state.name='active'）；独立函数便于 CAS 并发测试替身。 */
    @FunctionalInterface
    public interface ActiveReleaseSupplier {

        String get();
    }

    public SessionService(TransactionRunner runner, ClockPort clock) {
        this(runner, clock, null);
    }

    public SessionService(TransactionRunner runner, ClockPort clock,
                          ActiveReleaseSupplier activeRelease) {
        this.runner = runner;
        this.clock = clock;
        this.activeRelease = activeRelease == null ? this::defaultActiveRelease : activeRelease;
    }

    private String defaultActiveRelease() {
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT release_id FROM rag_index_state WHERE name='active'");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        });
    }

    @FunctionalInterface
    private interface SqlWork<T> {

        T apply(Connection connection) throws SQLException;
    }

    private <T> T inTx(SqlWork<T> work) {
        return runner.required((DbTx tx) -> {
            try {
                return work.apply(tx.connection());
            } catch (SQLException e) {
                throw new IllegalStateException("session storage failure", e);
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* 生命周期                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * _ensure_session_sync：指定 id 归属本人 → touch 后原 id 返回；缺失/他人所有 →
     * 新建 sess_ id（title 截 80）。归属判定 COALESCE(user_id,'anonymous')=subject。
     */
    public SessionId ensureOwned(Optional<String> requestedId, String title, String subject) {
        String requested = requestedId == null ? null : requestedId.orElse(null);
        if (requested != null && !requested.isEmpty()) {
            String existing = inTx(conn -> {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id FROM assistant_sessions WHERE id = ? "
                                + "AND COALESCE(user_id, 'anonymous') = ?")) {
                    ps.setString(1, requested);
                    ps.setString(2, subject);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? requested : null;
                    }
                }
            });
            if (existing != null) {
                inTx(conn -> {
                    touchInTx(conn, requested, clock.nextSessionIso());
                    return null;
                });
                return new SessionId(requested);
            }
        }
        String newId = "sess_" + hex12();
        inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO assistant_sessions(id, user_id, title, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?)")) {
                ps.setString(1, newId);
                ps.setString(2, subject);
                ps.setString(3, truncate(title, 80));
                ps.setObject(4, OffsetDateTime.parse(clock.nextSessionIso()));
                ps.setObject(5, OffsetDateTime.parse(clock.nextSessionIso()));
                ps.executeUpdate();
            }
            return null;
        });
        return new SessionId(newId);
    }

    /** _add_message_sync 的 tx 参与版：插入消息并 touch 会话，均随 caller 事务提交。 */
    public String appendMessage(DbTx tx, String sessionId, String role, String content,
                                Map<String, Object> metadata) throws SQLException {
        String msgId = "msg_" + hex12();
        Connection conn = tx.connection();
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO assistant_messages(id, session_id, role, content, metadata_json, created_at) "
                        + "VALUES (?,?,?,?,?::jsonb,?)")) {
            ps.setString(1, msgId);
            ps.setString(2, sessionId);
            ps.setString(3, role);
            ps.setString(4, content);
            ps.setString(5, toJson(metadata));
            ps.setObject(6, OffsetDateTime.parse(clock.nextSessionIso()));
            ps.executeUpdate();
        }
        touchInTx(conn, sessionId, clock.nextSessionIso());
        return msgId;
    }

    /** touch(updated_at)：参与 caller 事务。 */
    public void touch(DbTx tx, String sessionId, String iso) throws SQLException {
        touchInTx(tx.connection(), sessionId, iso);
    }

    private static void touchInTx(Connection conn, String sessionId, String iso) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE assistant_sessions SET updated_at = ? WHERE id = ?")) {
            ps.setObject(1, OffsetDateTime.parse(iso));
            ps.setString(2, sessionId);
            ps.executeUpdate();
        }
    }

    /** _discard_empty_session_sync：并发首问败方清理自己的空会话（无消息且无 rag_requests）。 */
    public void discardEmpty(String sessionId, String subject) {
        inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM assistant_sessions WHERE id = ? AND COALESCE(user_id, 'anonymous') = ? "
                            + "AND NOT EXISTS (SELECT 1 FROM assistant_messages WHERE session_id = ?) "
                            + "AND NOT EXISTS (SELECT 1 FROM rag_requests WHERE session_id = ?)")) {
                ps.setString(1, sessionId);
                ps.setString(2, subject);
                ps.setString(3, sessionId);
                ps.setString(4, sessionId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /* ------------------------------------------------------------------ */
    /* 读取                                                                */
    /* ------------------------------------------------------------------ */

    public Optional<SessionRows.SessionRow> getSession(String sessionId) {
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, user_id, title, created_at, updated_at FROM assistant_sessions "
                            + "WHERE id = ?")) {
                ps.setString(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<SessionRows.SessionRow>empty();
                    }
                    return Optional.of(SessionRows.SessionRow.of(rs.getString("id"),
                            rs.getString("user_id"), rs.getString("title"),
                            SessionTime.utc(rs.getObject("created_at", OffsetDateTime.class)),
                            SessionTime.utc(rs.getObject("updated_at", OffsetDateTime.class))));
                }
            }
        });
    }

    /** _list_sessions_sync：本人会话，updated_at DESC, id DESC。 */
    public List<SessionRows.SessionRow> listSessions(String userId, int limit, int offset) {
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, user_id, title, created_at, updated_at FROM assistant_sessions "
                            + "WHERE COALESCE(user_id, 'anonymous') = ? "
                            + "ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?")) {
                ps.setString(1, userId);
                ps.setInt(2, limit);
                ps.setInt(3, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    List<SessionRows.SessionRow> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(SessionRows.SessionRow.of(rs.getString("id"), rs.getString("user_id"),
                                rs.getString("title"),
                                SessionTime.utc(rs.getObject("created_at", OffsetDateTime.class)),
                                SessionTime.utc(rs.getObject("updated_at", OffsetDateTime.class))));
                    }
                    return List.copyOf(out);
                }
            }
        });
    }

    /**
     * _get_messages_sync：created_at ASC, id ASC 稳定序；includeCancelled=false 时
     * 过滤 assistant + stream_status=cancelled（会话记忆口径；历史展示路径传 true）。
     */
    public List<SessionRows.MessageRow> getMessages(String sessionId, boolean includeCancelled) {
        List<SessionRows.MessageRow> messages = inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, role, content, metadata_json, created_at FROM assistant_messages "
                            + "WHERE session_id = ? ORDER BY created_at ASC, id ASC")) {
                ps.setString(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<SessionRows.MessageRow> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new SessionRows.MessageRow(rs.getString("id"), rs.getString("role"),
                                rs.getString("content"),
                                parseMetadata(rs.getString("metadata_json")),
                                SessionTime.utc(rs.getObject("created_at", OffsetDateTime.class))));
                    }
                    return out;
                }
            }
        });
        if (includeCancelled) {
            return messages;
        }
        return messages.stream()
                .filter(m -> !(("assistant".equals(m.role()))
                        && "cancelled".equals(m.metadata().get("stream_status"))))
                .toList();
    }

    /** _get_citations_sync：message_id ASC, rank ASC 分组（保持历史引用顺序与编号）。 */
    public Map<String, List<SessionRows.CitationRow>> getCitations(List<String> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = repeat("?", messageIds.size());
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT message_id, chunk_id, citation_engine, score, rank "
                            + "FROM assistant_citations WHERE message_id IN (" + placeholders + ") "
                            + "ORDER BY message_id ASC, rank ASC")) {
                for (int i = 0; i < messageIds.size(); i++) {
                    ps.setString(i + 1, messageIds.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    Map<String, List<SessionRows.CitationRow>> grouped = new LinkedHashMap<>();
                    while (rs.next()) {
                        grouped.computeIfAbsent(rs.getString("message_id"), k -> new ArrayList<>())
                                .add(SessionRows.CitationRow.of(rs.getString("message_id"),
                                        rs.getString("chunk_id"), rs.getString("citation_engine"),
                                        rs.getDouble("score"), rs.getInt("rank")));
                    }
                    return grouped;
                }
            }
        });
    }

    /** _add_citation_sync：独立提交的引用写入（历史 fixture/补录用）。 */
    public String addCitation(String messageId, String chunkId, double score, int rank, String engine) {
        String citId = "cit_" + hex12();
        inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO assistant_citations(id, message_id, chunk_id, citation_engine, score, rank) "
                            + "VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, citId);
                ps.setString(2, messageId);
                ps.setString(3, chunkId);
                ps.setString(4, engine == null ? "legacy" : engine);
                ps.setDouble(5, score);
                ps.setInt(6, rank);
                ps.executeUpdate();
            }
            return null;
        });
        return citId;
    }

    /* ------------------------------------------------------------------ */
    /* release 首次绑定                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * resolve_release：已有绑定直接返回；无绑定时以 active release 候选做条件 UPDATE
     * （CAS），随后回读——并发同启只有唯一赢家，败方读到赢家值；active 为空 → empty；
     * 会话不存在/中途被删 → IllegalStateException（源为 ValueError，内部错误路径）。
     */
    public Optional<String> resolveRelease(String sessionId) {
        String bound = inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT rag_release_id FROM assistant_sessions WHERE id = ?")) {
                ps.setString(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("会话不存在，无法绑定索引版本");
                    }
                    return rs.getString(1);
                }
            }
        });
        if (bound != null && !bound.isEmpty()) {
            return Optional.of(bound);
        }
        String candidate = activeRelease.get();
        if (candidate == null || candidate.isEmpty()) {
            return Optional.empty();
        }
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE assistant_sessions SET rag_release_id = ? "
                            + "WHERE id = ? AND rag_release_id IS NULL")) {
                ps.setString(1, candidate);
                ps.setString(2, sessionId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT rag_release_id FROM assistant_sessions WHERE id = ?")) {
                ps.setString(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalStateException("会话已删除，无法绑定索引版本");
                    }
                    String winner = rs.getString(1);
                    return Optional.ofNullable(winner == null || winner.isEmpty() ? null : winner);
                }
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* 删除 / 维护                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * _delete_session_sync：单事务显式级联（citations→messages→grading 脱钩→session）。
     * subject=null 跳过归属检查（local 模式口径）；他人/缺失统一 false（路由 404）。
     */
    public boolean deleteSession(String sessionId, String subject) {
        boolean deleted = inTx(conn -> {
            String owner;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT user_id FROM assistant_sessions WHERE id = ?")) {
                ps.setString(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    owner = rs.getString(1);
                }
            }
            if (subject != null && (owner == null || owner.isEmpty() ? "anonymous" : owner)
                    .equals(subject) == false) {
                return false;
            }
            List<String> messageIds = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM assistant_messages WHERE session_id = ?")) {
                ps.setString(1, sessionId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        messageIds.add(rs.getString(1));
                    }
                }
            }
            if (!messageIds.isEmpty()) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM assistant_citations WHERE message_id IN ("
                                + repeat("?", messageIds.size()) + ")")) {
                    for (int i = 0; i < messageIds.size(); i++) {
                        ps.setString(i + 1, messageIds.get(i));
                    }
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM assistant_messages WHERE session_id = ?")) {
                ps.setString(1, sessionId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE grading_submissions SET session_id = NULL WHERE session_id = ?")) {
                ps.setString(1, sessionId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM assistant_sessions WHERE id = ?")) {
                ps.setString(1, sessionId);
                ps.executeUpdate();
            }
            return true;
        });
        if (deleted) {
            log.info("{{\"event\":\"session_deleted\",\"session_id\":\"{}\",\"subject\":\"{}\"}}",
                    sessionId, subject == null ? "local" : subject);
        }
        return deleted;
    }

    /** _purge_expired_sessions_sync：idle 超 cutoff 的会话计数（dry_run）或显式删除。 */
    public Map<String, Object> purgeExpired(int retentionDays, boolean dryRun) {
        String cutoff = SessionTime.iso(clock.nowUtc().minus(Duration.ofDays(retentionDays)));
        return inTx(conn -> {
            List<String> expired = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM assistant_sessions WHERE updated_at < ?")) {
                ps.setObject(1, OffsetDateTime.parse(cutoff));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        expired.add(rs.getString(1));
                    }
                }
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("dry_run", dryRun);
            result.put("retention_days", retentionDays);
            result.put("cutoff", cutoff);
            result.put("sessions", expired.size());
            result.put("messages", 0);
            result.put("citations", 0);
            if (expired.isEmpty()) {
                return result;
            }
            String sessionMarks = repeat("?", expired.size());
            List<String> messageIds = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM assistant_messages WHERE session_id IN (" + sessionMarks + ")")) {
                for (int i = 0; i < expired.size(); i++) {
                    ps.setString(i + 1, expired.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        messageIds.add(rs.getString(1));
                    }
                }
            }
            result.put("messages", messageIds.size());
            if (!messageIds.isEmpty()) {
                String messageMarks = repeat("?", messageIds.size());
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT count(*) FROM assistant_citations WHERE message_id IN ("
                                + messageMarks + ")")) {
                    for (int i = 0; i < messageIds.size(); i++) {
                        ps.setString(i + 1, messageIds.get(i));
                    }
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        result.put("citations", rs.getInt(1));
                    }
                }
                if (!dryRun) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM assistant_citations WHERE message_id IN (" + messageMarks + ")")) {
                        for (int i = 0; i < messageIds.size(); i++) {
                            ps.setString(i + 1, messageIds.get(i));
                        }
                        ps.executeUpdate();
                    }
                }
            }
            if (!dryRun) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM assistant_messages WHERE session_id IN (" + sessionMarks + ")")) {
                    for (int i = 0; i < expired.size(); i++) {
                        ps.setString(i + 1, expired.get(i));
                    }
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM assistant_sessions WHERE id IN (" + sessionMarks + ")")) {
                    for (int i = 0; i < expired.size(); i++) {
                        ps.setString(i + 1, expired.get(i));
                    }
                    ps.executeUpdate();
                }
                log.info("{{\"event\":\"session_purge\",\"retention_days\":{},\"cutoff\":\"{}\","
                                + "\"sessions\":{},\"messages\":{},\"citations\":{}}}",
                        retentionDays, cutoff, expired.size(), messageIds.size(), result.get("citations"));
            }
            return result;
        });
    }

    /** _get_storage_usage_sync（postgres 分支）：三表物理大小 + 会话数（只读维护）。 */
    public Map<String, Object> storageUsage() {
        return inTx(conn -> {
            long bytes;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COALESCE(SUM(pg_total_relation_size(c.oid)), 0) "
                            + "FROM pg_class AS c JOIN pg_namespace AS n ON n.oid = c.relnamespace "
                            + "WHERE n.nspname = current_schema() AND c.relname IN "
                            + "('assistant_sessions', 'assistant_messages', 'assistant_citations')");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                bytes = rs.getLong(1);
            }
            int sessionCount;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT count(*) FROM assistant_sessions");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                sessionCount = rs.getInt(1);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("storage_bytes", bytes);
            out.put("session_count", sessionCount);
            out.put("database_backend", "postgres");
            return out;
        });
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                                */
    /* ------------------------------------------------------------------ */

    private static Map<String, Object> parseMetadata(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("metadata_json 不是 JSON 对象: " + raw, e);
        }
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("session json serialization failure", e);
        }
    }

    private static String truncate(String value, int max) {
        String v = value == null ? "" : value;
        return v.codePointCount(0, v.length()) <= max ? v
                : v.substring(0, v.offsetByCodePoints(0, max));
    }

    private static String repeat(String mark, int count) {
        StringBuilder sb = new StringBuilder(mark);
        for (int i = 1; i < count; i++) {
            sb.append(',').append(mark);
        }
        return sb.toString();
    }

    static String hex12() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
