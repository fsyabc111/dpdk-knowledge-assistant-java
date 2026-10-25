package io.dpdk.assistant.admin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.auth.AuthSettingsProvider;
import io.dpdk.assistant.auth.CredentialInvalidationPort;
import io.dpdk.assistant.auth.UserAuthRepository;
import org.springframework.security.crypto.bcrypt.BCrypt;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.DbTx;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 管理员用户仓储：users / admin_audit_log / 停号级联 / 删除级联（对齐
 * backend/app/services/user_service.py 的管理子集）。
 *
 * 事务形状与源一致：每个公开方法一笔事务（删除=逐 id 级联+审计单事务）；
 * 停号在同一事务内撤 token、取消 pending rag_requests、删任务输入与任务关联
 * 成绩；删除老师时学生 created_by 置 NULL 归未分配；成绩行保留、session_id 脱钩。
 *
 * 与 auth 包协作：操作者密码校验复用 UserAuthRepository.verifyCredentials
 * （含 dummy 等价工作）；token 撤销经 CredentialInvalidationPort（停号路径先在
 * 本事务内执行同形 UPDATE，再调端口清 resolve 缓存——Python clear_token_cache
 * 同为提交后清缓存）。
 */
public class AdminUserRepository {

    static final Pattern USER_ID_PATTERN = Pattern.compile("^U\\d{11}$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TransactionRunner runner;
    private final ClockPort clock;
    private final AuthSettingsProvider settings;
    private final UserAuthRepository users;
    private final CredentialInvalidationPort invalidation;

    /** 测试故障注入探针（验收 G：真实 PG 事务中段失败回滚）。 */
    @FunctionalInterface
    interface SqlProbe {

        void check(String step) throws SQLException;
    }

    private volatile SqlProbe probe = step -> {
    };

    void setProbe(SqlProbe probe) {
        this.probe = probe == null ? step -> {
        } : probe;
    }

    public AdminUserRepository(TransactionRunner runner, ClockPort clock,
                               AuthSettingsProvider settings, UserAuthRepository users,
                               CredentialInvalidationPort invalidation) {
        this.runner = runner;
        this.clock = clock;
        this.settings = settings;
        this.users = users;
        this.invalidation = invalidation;
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
                throw new IllegalStateException("admin storage failure", e);
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* 行类型                                                               */
    /* ------------------------------------------------------------------ */

    /** users 行（roles 已解析；created_at 存 Instant，渲染层做 Python str() 格式化）。 */
    public record UserRow(String id, List<String> roles, Integer dailyQuota, boolean enabled,
                          boolean mustChangePassword, java.time.Instant createdAt,
                          String createdBy) {
    }

    /** 批量操作结果：affected=created/deleted/assigned（按操作语义命名由调用方渲染）。 */
    public record IdsResult(List<String> affected, List<String> skipped) {
    }

    /** admin_audit_log 行。 */
    public record AuditRow(String id, String actor, String action, List<String> targets,
                           java.time.Instant createdAt) {
    }

    /* ------------------------------------------------------------------ */
    /* 查询                                                                 */
    /* ------------------------------------------------------------------ */

    private static final String USER_COLUMNS =
            "id, roles, daily_quota, enabled, must_change_password, created_at, created_by";

    public Optional<UserRow> getUser(String userId) {
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + USER_COLUMNS + " FROM users WHERE id = ?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(userRow(rs)) : Optional.<UserRow>empty();
                }
            }
        });
    }

    /** created_by=null → 全部（id ASC）；否则只列该创建者的用户（id ASC）。 */
    public List<UserRow> listUsers(String createdBy) {
        return inTx(conn -> {
            String sql = createdBy == null
                    ? "SELECT " + USER_COLUMNS + " FROM users ORDER BY id ASC"
                    : "SELECT " + USER_COLUMNS + " FROM users WHERE created_by = ? ORDER BY id ASC";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                if (createdBy != null) {
                    ps.setString(1, createdBy);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<UserRow> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(userRow(rs));
                    }
                    return List.copyOf(out);
                }
            }
        });
    }

    private static UserRow userRow(ResultSet rs) throws SQLException {
        Integer quota = rs.getObject("daily_quota") == null ? null : rs.getInt("daily_quota");
        return new UserRow(rs.getString("id"), parseRoles(rs.getString("roles")), quota,
                rs.getBoolean("enabled"), rs.getBoolean("must_change_password"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getString("created_by"));
    }

    static List<String> parseRoles(String rolesJson) {
        try {
            return MAPPER.readValue(rolesJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("users.roles 不是 JSON 数组: " + rolesJson, e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 批量创建                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * 批量创建（_create_users_sync）：初始密码=学号、must_change=true、enabled=true。
     * roles 子集/配额非负校验在事务前（ValueError → AdminRuleException → 400）；
     * 格式不符/已存在的 id 进 skipped（同请求内重复 id 第二次因已存在跳过）。
     */
    public IdsResult createUsers(List<String> ids, List<String> roles, Integer dailyQuota,
                                 String createdBy) {
        List<String> effectiveRoles = roles == null ? List.of("viewer") : roles;
        if (effectiveRoles.isEmpty()
                || !AdminTreeRules.ALLOWED_ROLES.containsAll(effectiveRoles)) {
            throw new AdminRuleException(
                    "roles must be a non-empty subset of " + AdminTreeRules.ALLOWED_ROLES_SORTED);
        }
        if (dailyQuota != null && dailyQuota < 0) {
            throw new AdminRuleException("daily_quota must be a non-negative integer or None");
        }
        int rounds = settings.current().bcryptRounds();
        IdsResult result = inTx(conn -> {
            List<String> created = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (String userId : ids) {
                if (!USER_ID_PATTERN.matcher(userId).matches()) {
                    skipped.add(userId);
                    continue;
                }
                boolean exists;
                try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM users WHERE id = ?")) {
                    ps.setString(1, userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        exists = rs.next();
                    }
                }
                if (exists) {
                    skipped.add(userId);
                    continue;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO users(id, password_hash, roles, daily_quota, enabled, "
                                + "must_change_password, created_at, created_by) "
                                + "VALUES (?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, userId);
                    ps.setString(2, BCrypt.hashpw(userId, BCrypt.gensalt(rounds)));
                    ps.setString(3, toJson(effectiveRoles));
                    if (dailyQuota == null) {
                        ps.setObject(4, null);
                    } else {
                        ps.setInt(4, dailyQuota);
                    }
                    ps.setBoolean(5, true);
                    ps.setBoolean(6, true);
                    ps.setObject(7, OffsetDateTime.parse(clock.nextSessionIso()));
                    ps.setString(8, createdBy);
                    ps.executeUpdate();
                }
                created.add(userId);
            }
            return new IdsResult(List.copyOf(created), List.copyOf(skipped));
        });
        return result;
    }

    /* ------------------------------------------------------------------ */
    /* 资料修改（PATCH）                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * roles/daily_quota/created_by 修补（_update_user_sync）。行不存在 → empty（404）；
     * created_by 规则在事务内校验（自指/不存在均回滚本次全部修改，与源一致）。
     */
    public Optional<UserRow> updateUser(String userId, List<String> roles, Integer dailyQuota,
                                        String createdBy) {
        // 与 _update_user_sync 一致：规则校验在开事务之前（ValueError → 400 且无副作用）。
        if (roles != null && (roles.isEmpty() || !AdminTreeRules.ALLOWED_ROLES.containsAll(roles))) {
            throw new AdminRuleException(
                    "roles must be a non-empty subset of " + AdminTreeRules.ALLOWED_ROLES_SORTED);
        }
        if (dailyQuota != null && dailyQuota < 0) {
            throw new AdminRuleException("daily_quota must be a non-negative integer or None");
        }
        return inTx(conn -> {
            boolean exists;
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM users WHERE id = ?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    exists = rs.next();
                }
            }
            if (!exists) {
                return Optional.<UserRow>empty();
            }
            if (roles != null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE users SET roles = ? WHERE id = ?")) {
                    ps.setString(1, toJson(roles));
                    ps.setString(2, userId);
                    ps.executeUpdate();
                }
            }
            if (dailyQuota != null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE users SET daily_quota = ? WHERE id = ?")) {
                    ps.setInt(1, dailyQuota);
                    ps.setString(2, userId);
                    ps.executeUpdate();
                }
            }
            if (createdBy != null) {
                if (createdBy.equals(userId)) {
                    throw new AdminRuleException("created_by must differ from the user id");
                }
                boolean creatorExists;
                try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM users WHERE id = ?")) {
                    ps.setString(1, createdBy);
                    try (ResultSet rs = ps.executeQuery()) {
                        creatorExists = rs.next();
                    }
                }
                if (!creatorExists) {
                    throw new AdminRuleException("created_by user does not exist");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE users SET created_by = ? WHERE id = ?")) {
                    ps.setString(1, createdBy);
                    ps.setString(2, userId);
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT " + USER_COLUMNS + " FROM users WHERE id = ?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(userRow(rs)) : Optional.<UserRow>empty();
                }
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* 启停（含停号级联）                                                     */
    /* ------------------------------------------------------------------ */

    /**
     * 停用=单事务：UPDATE users + 撤销全部 token + pending rag_requests 置
     * cancelled/account_disabled + 删 rag_task_inputs + 删任务关联 grading_submissions
     * （_set_enabled_sync 的库内部分）。行不存在返回 false。
     * 进程内任务取消（task_service.cancel_local_for_user）属提交后的 best-effort
     * 动作，由调用方经 {@link LocalTaskCancellationPort} 触发（integration_pending）。
     */
    public boolean setEnabled(String userId, boolean enabled) {
        boolean changed = inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET enabled = ? WHERE id = ?")) {
                ps.setBoolean(1, enabled);
                ps.setString(2, userId);
                if (ps.executeUpdate() == 0) {
                    return false;
                }
            }
            if (!enabled) {
                revokeUserTokensInTx(conn, userId);
                List<String> ids = new ArrayList<>();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id FROM rag_requests WHERE user_id = ? AND status = 'pending'")) {
                    ps.setString(1, userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            ids.add(rs.getString("id"));
                        }
                    }
                }
                if (!ids.isEmpty()) {
                    String marks = repeat("?", ids.size());
                    String now = clock.nextSessionIso();
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE rag_requests SET status = 'cancelled', "
                                    + "reason = 'account_disabled', updated_at = ? "
                                    + "WHERE id IN (" + marks + ") AND status = 'pending'")) {
                        ps.setObject(1, OffsetDateTime.parse(now));
                        for (int i = 0; i < ids.size(); i++) {
                            ps.setString(2 + i, ids.get(i));
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
                    // 已完成工具评测但问答仍在生成的任务：停号撤销其练习成绩；
                    // 只经 0014 的稳定 job 身份关联（不猜测历史 NULL 关联行）。
                    try (PreparedStatement ps = conn.prepareStatement(
                            "DELETE FROM grading_submissions WHERE task_job_id IN "
                                    + "(SELECT job_id FROM rag_task_jobs WHERE task_id IN ("
                                    + marks + "))")) {
                        for (int i = 0; i < ids.size(); i++) {
                            ps.setString(1 + i, ids.get(i));
                        }
                        ps.executeUpdate();
                    }
                }
            }
            return true;
        });
        if (changed && !enabled) {
            // 提交后清 resolve 缓存（等价 Python clear_token_cache；UPDATE 已在事务内完成，
            // 此调用幂等，仅借端口清缓存使吊销立即生效）。
            invalidation.revokeUserTokens(userId);
        }
        return changed;
    }

    /* ------------------------------------------------------------------ */
    /* 批量删除（单事务级联 + 审计）                                          */
    /* ------------------------------------------------------------------ */

    /**
     * 批量删除（_delete_users_sync）：先校验操作者自己的密码（独立连接；失败抛
     * {@link ActorPasswordException} → 403；静态 token 主体无 users 行必然失败）。
     * 自己/不存在/不可管（canDelete 只认 actor.roles 的 leader）→ skipped；
     * deleted 非空时同一事务写一行 admin_audit_log，提交后经端口清 token 缓存。
     */
    public IdsResult deleteUsers(Principal actor, List<String> ids, String password) {
        if (!users.verifyCredentials(actor.subject(), password)) {
            throw new ActorPasswordException();
        }
        IdsResult result = inTx(conn -> {
            List<String> deleted = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (String userId : ids) {
                if (userId.equals(actor.subject())) {
                    skipped.add(userId);
                    continue;
                }
                String rolesJson;
                String createdBy;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT roles, created_by FROM users WHERE id = ?")) {
                    ps.setString(1, userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            skipped.add(userId);
                            continue;
                        }
                        rolesJson = rs.getString("roles");
                        createdBy = rs.getString("created_by");
                    }
                }
                if (!AdminTreeRules.canDelete(actor, createdBy, parseRoles(rolesJson))) {
                    skipped.add(userId);
                    continue;
                }
                probe.check("delete-user-rows:" + userId);
                deleteUserRows(conn, userId);
                deleted.add(userId);
            }
            if (!deleted.isEmpty()) {
                probe.check("audit-insert");
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO admin_audit_log(id, actor, action, targets_json, created_at) "
                                + "VALUES (?,?,?,?::jsonb,?)")) {
                    ps.setString(1, "aud_" + UUID.randomUUID().toString().replace("-", "")
                            .substring(0, 12));
                    ps.setString(2, actor.subject());
                    ps.setString(3, "delete_users");
                    ps.setString(4, toJson(deleted));
                    ps.setObject(5, OffsetDateTime.parse(clock.nextSessionIso()));
                    ps.executeUpdate();
                }
            }
            return new IdsResult(List.copyOf(deleted), List.copyOf(skipped));
        });
        if (!result.affected().isEmpty()) {
            // api_tokens 行已在事务内删除；此调用幂等，仅借端口清 resolve 缓存。
            for (String userId : result.affected()) {
                invalidation.revokeUserTokens(userId);
            }
        }
        return result;
    }

    /**
     * 单账号级联删除（_delete_user_rows_sync，顺序受 FK 约束）：
     * token 先删；会话三表显式级联；成绩保留、session_id 置 NULL 脱钩；
     * 名下学生 created_by 置 NULL 归未分配；最后删 users 行。
     */
    private void deleteUserRows(Connection conn, String userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM api_tokens WHERE user_id = ?")) {
            ps.setString(1, userId);
            ps.executeUpdate();
        }
        List<String> sessionIds = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM assistant_sessions WHERE user_id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sessionIds.add(rs.getString("id"));
                }
            }
        }
        if (!sessionIds.isEmpty()) {
            String sessionMarks = repeat("?", sessionIds.size());
            List<String> messageIds = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM assistant_messages WHERE session_id IN (" + sessionMarks + ")")) {
                for (int i = 0; i < sessionIds.size(); i++) {
                    ps.setString(1 + i, sessionIds.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        messageIds.add(rs.getString("id"));
                    }
                }
            }
            if (!messageIds.isEmpty()) {
                String messageMarks = repeat("?", messageIds.size());
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM assistant_citations WHERE message_id IN (" + messageMarks + ")")) {
                    for (int i = 0; i < messageIds.size(); i++) {
                        ps.setString(1 + i, messageIds.get(i));
                    }
                    ps.executeUpdate();
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM assistant_messages WHERE session_id IN (" + sessionMarks + ")")) {
                for (int i = 0; i < sessionIds.size(); i++) {
                    ps.setString(1 + i, sessionIds.get(i));
                }
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE grading_submissions SET session_id = NULL WHERE session_id IN ("
                            + sessionMarks + ")")) {
                for (int i = 0; i < sessionIds.size(); i++) {
                    ps.setString(1 + i, sessionIds.get(i));
                }
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM assistant_sessions WHERE id IN (" + sessionMarks + ")")) {
                for (int i = 0; i < sessionIds.size(); i++) {
                    ps.setString(1 + i, sessionIds.get(i));
                }
                ps.executeUpdate();
            }
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM password_reset_requests WHERE user_id = ?")) {
            ps.setString(1, userId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM usage_counters WHERE subject = ?")) {
            ps.setString(1, userId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET created_by = NULL WHERE created_by = ?")) {
            ps.setString(1, userId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM users WHERE id = ?")) {
            ps.setString(1, userId);
            ps.executeUpdate();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 分配与审计                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * 组长把（纯 viewer）学生批量指派给某位启用中的老师/组长
     * （_assign_students_sync）。目标不合规 → AdminRuleException（400）；
     * 不存在/非纯 viewer 的 id → skipped。源允许改已归属学生（不强制未分配）。
     */
    public IdsResult assignStudents(List<String> ids, String createdBy) {
        return inTx(conn -> {
            String rolesJson;
            boolean ownerEnabled;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT roles, enabled FROM users WHERE id = ?")) {
                ps.setString(1, createdBy);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new AdminRuleException("assign target must be an enabled teacher account");
                    }
                    rolesJson = rs.getString("roles");
                    ownerEnabled = rs.getBoolean("enabled");
                }
            }
            if (!ownerEnabled || AdminTreeRules.parseManagerRoles(rolesJson).isEmpty()) {
                throw new AdminRuleException("assign target must be an enabled teacher account");
            }
            List<String> assigned = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (String userId : ids) {
                String targetRolesJson = null;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT roles FROM users WHERE id = ?")) {
                    ps.setString(1, userId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            targetRolesJson = rs.getString("roles");
                        }
                    }
                }
                List<String> targetRoles =
                        targetRolesJson == null ? List.of() : parseRoles(targetRolesJson);
                if (targetRolesJson == null || targetRoles.isEmpty()
                        || !targetRoles.stream().allMatch("viewer"::equals)) {
                    skipped.add(userId);
                    continue;
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE users SET created_by = ? WHERE id = ?")) {
                    ps.setString(1, createdBy);
                    ps.setString(2, userId);
                    ps.executeUpdate();
                }
                assigned.add(userId);
            }
            return new IdsResult(List.copyOf(assigned), List.copyOf(skipped));
        });
    }

    /** 最近审计记录，新的在前（created_at DESC, id DESC，LIMIT 100；scopeActor=null 全部）。 */
    public List<AuditRow> listAuditLog(String scopeActor) {
        return listAuditLog(scopeActor, 100);
    }

    public List<AuditRow> listAuditLog(String scopeActor, int limit) {
        return inTx(conn -> {
            String sql = scopeActor == null
                    ? "SELECT id, actor, action, targets_json, created_at FROM admin_audit_log "
                            + "ORDER BY created_at DESC, id DESC LIMIT ?"
                    : "SELECT id, actor, action, targets_json, created_at FROM admin_audit_log "
                            + "WHERE actor = ? ORDER BY created_at DESC, id DESC LIMIT ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int idx = 1;
                if (scopeActor != null) {
                    ps.setString(idx++, scopeActor);
                }
                ps.setInt(idx, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    List<AuditRow> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new AuditRow(rs.getString("id"), rs.getString("actor"),
                                rs.getString("action"), parseTargets(rs.getString("targets_json")),
                                rs.getObject("created_at", OffsetDateTime.class).toInstant()));
                    }
                    return List.copyOf(out);
                }
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                                 */
    /* ------------------------------------------------------------------ */

    private void revokeUserTokensInTx(Connection conn, String userId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE api_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL")) {
            ps.setObject(1, OffsetDateTime.parse(clock.nextSessionIso()));
            ps.setString(2, userId);
            ps.executeUpdate();
        }
    }

    private static List<String> parseTargets(String targetsJson) {
        if (targetsJson == null) {
            return List.of();
        }
        try {
            return MAPPER.readValue(targetsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("admin_audit_log.targets_json 不是 JSON 数组", e);
        }
    }

    private static String repeat(String mark, int count) {
        StringBuilder sb = new StringBuilder(mark);
        for (int i = 1; i < count; i++) {
            sb.append(',').append(mark);
        }
        return sb.toString();
    }

    /** Jackson 序列化（字符串列表恒可序列化；失败视为内部错误）。 */
    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("admin json serialization failure", e);
        }
    }
}
