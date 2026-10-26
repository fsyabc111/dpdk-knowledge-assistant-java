package io.dpdk.assistant.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.DbTx;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.platform.time.SystemClockAdapter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 账号凭据仓储：users/api_tokens/password_reset_requests 的存储与校验。
 *
 * 行为对齐 backend/app/services/user_service.py（认证相关子集）：
 * - 口令 bcrypt；不存在/停用账号执行 dummy 比对（等价工作，验收 F）；
 * - 登录 token 只存 sha256 hex；resolve 60s 正向缓存，任何失效操作清空；
 * - 改密保留 keep_token、撤销其他；管理员重置/设密撤销全部；
 * - 重置申请幂等落库（不校验账号存在）；一次性令牌消费（approved + 未过期 + 守卫置 used）。
 *
 * 差异（已登记 evidence/J2-01/endpoints-matrix.md §6）：Python 消费重置令牌的
 * `UPDATE ... WHERE id=?` 无 status 守卫，READ COMMITTED 并发下可双消费；本实现按
 * `WHERE id=? AND status='approved'` + rowcount 守卫保证并发严格一次（验收 D 要求），
 * 顺序流行为与 Python 一致。
 */
@Repository
public class UserAuthRepository implements CredentialInvalidationPort, PasswordResetApprovalPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TransactionRunner runner;
    private final ClockPort clock;
    private final AuthSettingsProvider settings;
    private final PasswordHasher hasher;
    private final TokenPrincipalCache cache;

    public UserAuthRepository(TransactionRunner runner, ClockPort clock, AuthSettingsProvider settings) {
        this.runner = runner;
        this.clock = clock;
        this.settings = settings;
        this.hasher = new PasswordHasher(settings.current().bcryptRounds());
        this.cache = new TokenPrincipalCache(clock);
    }

    /** JDBC 工作单元（checked SQLException 统一翻译为运行时异常，属内部错误）。 */
    @FunctionalInterface
    private interface SqlWork<T> {

        T apply(Connection connection) throws SQLException;
    }

    private <T> T inTx(SqlWork<T> work) {
        return runner.required((DbTx tx) -> {
            try {
                return work.apply(tx.connection());
            } catch (SQLException e) {
                throw new IllegalStateException("auth storage failure", e);
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* 查询                                                                 */
    /* ------------------------------------------------------------------ */

    public record UserAuthRow(String id, List<String> roles, Integer dailyQuota, boolean enabled,
                              boolean mustChangePassword) {
    }

    public Optional<UserAuthRow> getUser(String userId) {
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, roles, daily_quota, enabled, must_change_password FROM users WHERE id = ?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(userRow(rs)) : Optional.<UserAuthRow>empty();
                }
            }
        });
    }

    private static UserAuthRow userRow(ResultSet rs) throws SQLException {
        Integer quota = rs.getObject("daily_quota") == null ? null : rs.getInt("daily_quota");
        return new UserAuthRow(rs.getString("id"), parseRoles(rs.getString("roles")), quota,
                rs.getBoolean("enabled"), rs.getBoolean("must_change_password"));
    }

    private static List<String> parseRoles(String rolesJson) {
        try {
            return MAPPER.readValue(rolesJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("users.roles 不是 JSON 数组: " + rolesJson, e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 凭据校验                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * 用户不存在 / 已停用 / 密码错误 → false；不存在与停用也执行一次 dummy
     * bcrypt 比对（等价工作，防时序侧信道；验收 F 以代码路径断言，不以耗时证明）。
     */
    public boolean verifyCredentials(String userId, String password) {
        String hash = inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT password_hash, enabled FROM users WHERE id = ?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getBoolean("enabled")) {
                        return rs.getString("password_hash");
                    }
                    return null;
                }
            }
        });
        if (hash == null) {
            hasher.dummyCheck(password);
            return false;
        }
        return hasher.check(password, hash);
    }

    /* ------------------------------------------------------------------ */
    /* 登录 token                                                           */
    /* ------------------------------------------------------------------ */

    public record IssuedToken(String token, String expiresAtIso) {
    }

    /** 签发登录 token：明文仅此返回；库里只存 sha256；expires = 签发 + token_ttl_days。 */
    public IssuedToken issueToken(String userId) {
        String token = TokenMint.newToken();
        String issuedIso = clock.nextSessionIso();
        Instant expires = OffsetDateTime.parse(issuedIso).toInstant()
                .plus(Duration.ofDays(settings.current().tokenTtlDays()));
        String expiresIso = SystemClockAdapter.pythonIso(expires);
        inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO api_tokens(token_hash, user_id, issued_at, expires_at) VALUES (?,?,?,?)")) {
                ps.setString(1, TokenMint.digest(token));
                ps.setString(2, userId);
                ps.setObject(3, OffsetDateTime.parse(issuedIso));
                ps.setObject(4, OffsetDateTime.parse(expiresIso));
                ps.executeUpdate();
                return null;
            }
        });
        return new IssuedToken(token, expiresIso);
    }

    /**
     * Bearer token → Principal；无效/撤销/停用/过期 → empty。
     * 正向结果缓存 60s（单调时钟）；失效操作由各写路径清空整缓存。
     */
    public Optional<Principal> resolveToken(String token) {
        String digest = TokenMint.digest(token);
        Optional<Principal> cached = cache.get(digest);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<Principal> principal = inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT t.expires_at, t.revoked_at, u.id, u.roles, u.daily_quota, u.enabled, "
                            + "u.must_change_password FROM api_tokens AS t "
                            + "JOIN users AS u ON u.id = t.user_id WHERE t.token_hash = ?")) {
                ps.setString(1, digest);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getTimestamp("revoked_at") != null || !rs.getBoolean("enabled")) {
                        return Optional.<Principal>empty();
                    }
                    Timestamp expiresAt = rs.getTimestamp("expires_at");
                    if (!expiresAt.toInstant().isAfter(clock.nowUtc())) {
                        return Optional.<Principal>empty();
                    }
                    Integer quota = rs.getObject("daily_quota") == null ? null : rs.getInt("daily_quota");
                    return Optional.of(new Principal(rs.getString("id"), parseRoles(rs.getString("roles")),
                            quota == null ? settings.current().llmDailyQuotaDefault() : quota,
                            rs.getBoolean("must_change_password")));
                }
            }
        });
        principal.ifPresent(p -> cache.put(digest, p));
        return principal;
    }

    /** 撤销单个 token（digest 不在 api_tokens 时为空操作，如静态兜底 token）。 */
    public void revokeToken(String token) {
        inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE api_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL")) {
                ps.setObject(1, OffsetDateTime.parse(clock.nextSessionIso()));
                ps.setString(2, TokenMint.digest(token));
                ps.executeUpdate();
                return null;
            }
        });
        cache.clear();
    }

    @Override
    public void revokeUserTokens(String userId) {
        inTx(conn -> {
            revokeUserTokensInTx(conn, userId, null);
            return null;
        });
        cache.clear();
    }

    /* ------------------------------------------------------------------ */
    /* 改密                                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * 自己改密：旧密错（含静态兜底主体无账号行）返回 false；新密规则不满足抛
     * {@link PasswordRuleException}；成功撤销其他 token、保留 keep_token（验收 C）。
     */
    public boolean changePassword(String userId, String oldPassword, String newPassword, String keepToken) {
        if (!verifyCredentials(userId, oldPassword)) {
            return false;
        }
        String keepHash = keepToken == null || keepToken.isEmpty() ? null : TokenMint.digest(keepToken);
        inTx(conn -> {
            validateNewPassword(conn, userId, newPassword);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET password_hash = ?, must_change_password = ? WHERE id = ?")) {
                ps.setString(1, hasher.hash(newPassword));
                ps.setBoolean(2, false);
                ps.setString(3, userId);
                ps.executeUpdate();
            }
            revokeUserTokensInTx(conn, userId, keepHash);
            return null;
        });
        cache.clear();
        return true;
    }

    @Override
    public boolean resetPasswordToInitial(String userId) {
        boolean changed = inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET password_hash = ?, must_change_password = ? WHERE id = ?")) {
                ps.setString(1, hasher.hash(userId));
                ps.setBoolean(2, true);
                ps.setString(3, userId);
                if (ps.executeUpdate() == 0) {
                    return false;
                }
            }
            revokeUserTokensInTx(conn, userId, null);
            return true;
        });
        if (changed) {
            cache.clear();
        }
        return changed;
    }

    @Override
    public boolean setPasswordByAdmin(String userId, String newPassword) {
        boolean changed = inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM users WHERE id = ?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                }
            }
            validateNewPassword(conn, userId, newPassword);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET password_hash = ?, must_change_password = ? WHERE id = ?")) {
                ps.setString(1, hasher.hash(newPassword));
                ps.setBoolean(2, true);
                ps.setString(3, userId);
                ps.executeUpdate();
            }
            revokeUserTokensInTx(conn, userId, null);
            return true;
        });
        if (changed) {
            cache.clear();
        }
        return changed;
    }

    /**
     * 新口令规则（三处改密共用；文案逐字对齐 Python ValueError）：
     * 长度达标、不得等于学号、不得等于旧密码。
     */
    private void validateNewPassword(Connection conn, String userId, String newPassword) throws SQLException {
        int min = settings.current().passwordMinLength();
        // Python len() 按码点计数；这里用 codePointCount 对齐。
        if (newPassword.codePointCount(0, newPassword.length()) < min) {
            throw new PasswordRuleException("new password must be at least " + min + " characters");
        }
        if (newPassword.equals(userId)) {
            throw new PasswordRuleException("new password must differ from the user id");
        }
        try (PreparedStatement ps = conn.prepareStatement("SELECT password_hash FROM users WHERE id = ?")) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && hasher.check(newPassword, rs.getString("password_hash"))) {
                    throw new PasswordRuleException("new password must differ from the old password");
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 忘记密码                                                             */
    /* ------------------------------------------------------------------ */

    /** 匿名提交申请：同学号仅一行 pending（幂等）；不校验账号存在（防枚举）。 */
    public void createResetRequest(String userId) {
        inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM password_reset_requests WHERE user_id = ? AND status = 'pending'")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return null;
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO password_reset_requests(id, user_id, status, created_at) "
                            + "VALUES (?, ?, 'pending', ?)")) {
                ps.setString(1, UUID.randomUUID().toString().replace("-", ""));
                ps.setString(2, userId);
                ps.setObject(3, OffsetDateTime.parse(clock.nextSessionIso()));
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public List<ResetRequestRow> listResetRequests(String status, String scopeCreator) {
        return inTx(conn -> {
            String sql = scopeCreator == null
                    ? "SELECT id, user_id, created_at FROM password_reset_requests WHERE status = ? "
                            + "ORDER BY created_at ASC"
                    : "SELECT r.id, r.user_id, r.created_at FROM password_reset_requests AS r "
                            + "JOIN users AS u ON u.id = r.user_id "
                            + "WHERE r.status = ? AND u.created_by = ? ORDER BY r.created_at ASC";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, status);
                if (scopeCreator != null) {
                    ps.setString(2, scopeCreator);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<ResetRequestRow> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new ResetRequestRow(rs.getString("id"), rs.getString("user_id"),
                                SystemClockAdapter.pythonIso(rs.getTimestamp("created_at").toInstant())));
                    }
                    return List.copyOf(out);
                }
            }
        });
    }

    @Override
    public Optional<ResetTokenIssue> approveResetRequest(String requestId, String adminId, String scopeCreator) {
        return inTx(conn -> {
            String userId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, user_id, status FROM password_reset_requests WHERE id = ?")) {
                ps.setString(1, requestId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !"pending".equals(rs.getString("status"))) {
                        return Optional.<ResetTokenIssue>empty();
                    }
                    userId = rs.getString("user_id");
                }
            }
            String createdBy;
            try (PreparedStatement ps = conn.prepareStatement("SELECT created_by FROM users WHERE id = ?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<ResetTokenIssue>empty();
                    }
                    createdBy = rs.getString("created_by");
                }
            }
            if (scopeCreator != null && !scopeCreator.equals(createdBy)) {
                return Optional.<ResetTokenIssue>empty();
            }
            String token = TokenMint.newToken();
            Instant expires = clock.nowUtc()
                    .plus(Duration.ofMinutes(AuthServerSettings.RESET_TOKEN_TTL_MINUTES));
            String expiresIso = SystemClockAdapter.pythonIso(expires);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE password_reset_requests SET status = 'approved', reset_token_hash = ?, "
                            + "reset_expires_at = ?, decided_at = ?, decided_by = ? WHERE id = ?")) {
                ps.setString(1, TokenMint.digest(token));
                ps.setObject(2, OffsetDateTime.parse(expiresIso));
                ps.setObject(3, OffsetDateTime.parse(clock.nextSessionIso()));
                ps.setString(4, adminId);
                ps.setString(5, requestId);
                ps.executeUpdate();
            }
            return Optional.of(new ResetTokenIssue(token, expiresIso));
        });
    }

    @Override
    public boolean rejectResetRequest(String requestId, String adminId, String scopeCreator) {
        return inTx(conn -> {
            if (scopeCreator != null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT u.created_by FROM password_reset_requests AS r "
                                + "JOIN users AS u ON u.id = r.user_id WHERE r.id = ?")) {
                    ps.setString(1, requestId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next() || !scopeCreator.equals(rs.getString("created_by"))) {
                            return false;
                        }
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE password_reset_requests SET status = 'rejected', decided_at = ?, decided_by = ? "
                            + "WHERE id = ? AND status = 'pending'")) {
                ps.setObject(1, OffsetDateTime.parse(clock.nextSessionIso()));
                ps.setString(2, adminId);
                ps.setString(3, requestId);
                return ps.executeUpdate() > 0;
            }
        });
    }

    /**
     * 一次性重置令牌消费：无效/过期/已用/用户不存在/新密不合规 → false（端点统一 400，
     * 不区分原因）。status='approved' 守卫置 used 保证并发严格一次（差异已登记）。
     */
    public boolean consumeResetToken(String token, String newPassword) {
        String digest = TokenMint.digest(token);
        boolean consumed = inTx(conn -> {
            String rowId;
            String userId;
            Timestamp expiresAt;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, user_id, reset_expires_at FROM password_reset_requests "
                            + "WHERE reset_token_hash = ? AND status = 'approved'")) {
                ps.setString(1, digest);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    rowId = rs.getString("id");
                    userId = rs.getString("user_id");
                    expiresAt = rs.getTimestamp("reset_expires_at");
                }
            }
            if (expiresAt == null || !expiresAt.toInstant().isAfter(clock.nowUtc())) {
                return false;
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM users WHERE id = ?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                }
            }
            try {
                validateNewPassword(conn, userId, newPassword);
            } catch (PasswordRuleException e) {
                return false;
            }
            // 差异登记：Python 无 status 守卫（READ COMMITTED 可双消费）；本守卫保证并发严格一次。
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE password_reset_requests SET status = 'used' WHERE id = ? AND status = 'approved'")) {
                ps.setString(1, rowId);
                if (ps.executeUpdate() == 0) {
                    return false;
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET password_hash = ?, must_change_password = ? WHERE id = ?")) {
                ps.setString(1, hasher.hash(newPassword));
                ps.setBoolean(2, false);
                ps.setString(3, userId);
                ps.executeUpdate();
            }
            revokeUserTokensInTx(conn, userId, null);
            return true;
        });
        if (consumed) {
            cache.clear();
        }
        return consumed;
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                                 */
    /* ------------------------------------------------------------------ */

    private void revokeUserTokensInTx(Connection conn, String userId, String exceptTokenHash)
            throws SQLException {
        String now = clock.nextSessionIso();
        if (exceptTokenHash == null) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE api_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL")) {
                ps.setObject(1, OffsetDateTime.parse(now));
                ps.setString(2, userId);
                ps.executeUpdate();
            }
        } else {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE api_tokens SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL "
                            + "AND token_hash <> ?")) {
                ps.setObject(1, OffsetDateTime.parse(now));
                ps.setString(2, userId);
                ps.setString(3, exceptTokenHash);
                ps.executeUpdate();
            }
        }
    }

    /** 已提交管理写入的纯缓存失效端口（等价 Python clear_token_cache），不访问数据库或时钟。 */
    @Override
    public void clearTokenCache() {
        cache.clear();
    }
}
