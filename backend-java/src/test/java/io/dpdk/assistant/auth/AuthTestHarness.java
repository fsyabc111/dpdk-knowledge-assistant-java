package io.dpdk.assistant.auth;

import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.platform.errors.ApiExceptionHandler;
import io.dpdk.assistant.platform.migration.MigrationController;
import io.dpdk.assistant.platform.pg.TestPg;
import io.dpdk.assistant.platform.tx.SpringTransactionRunner;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * J2-01 测试基座：独立 PG 库 + B0015 迁移 + 真 UserAuthRepository +
 * MockMvc standalone（Controller 每次新建，限流/锁定状态用例间隔离）。
 */
public final class AuthTestHarness {

    public static final String FALLBACK_TOKEN = "f".repeat(32);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public final DataSource ds;
    public final String database;
    public final TestClock clock = new TestClock();
    public final AuthSettingsProvider settings;
    public final TransactionRunner runner;
    public final UserAuthRepository users;
    public final AuthPrincipalResolver resolver;
    public final AuthAccountWriteGuard guard;

    private AuthTestHarness(DataSource ds, String database, AuthServerSettings initial) {
        this.ds = ds;
        this.database = database;
        this.settings = AuthSettingsProvider.fixed(initial);
        this.runner = new SpringTransactionRunner(
                new TransactionTemplate(new DataSourceTransactionManager(ds)), ds);
        this.users = new UserAuthRepository(runner, clock, settings);
        this.resolver = new AuthPrincipalResolver(settings, users);
        this.guard = new AuthAccountWriteGuard(settings);
    }

    /** account 模式 + 静态兜底 token + bcrypt cost 4（对齐 Python 测试环境）。 */
    public static AuthTestHarness accountMode(String tag) throws Exception {
        AuthServerSettings settings = AuthServerSettings.of("account",
                "{\"" + FALLBACK_TOKEN + "\":{\"subject\":\"emergency-admin\",\"roles\":[\"admin\"],\"daily_quota\":0}}",
                7, 8, 4, 200);
        return create(tag, settings);
    }

    public static AuthTestHarness create(String tag, AuthServerSettings settings) throws Exception {
        String database = TestPg.createDatabase("j201" + tag, TestPg.COMMENT);
        DataSource ds = TestPg.dataSource(database);
        new MigrationController().run(ds, TestPg.identity(database), true);
        return new AuthTestHarness(ds, database, settings);
    }

    public void drop() throws Exception {
        TestPg.dropDatabase(database);
    }

    /** 每用例新建 Controller（限流/锁定进程内状态随之隔离）。 */
    public MockMvc newMvc() {
        return newMvcWith(settings, resolver);
    }

    /** 用替换的认证设置/解析器装配 Controller（local/token 模式场景）。 */
    public MockMvc newMvcWith(AuthSettingsProvider provider, AuthPrincipalResolver principalResolver) {
        AuthController controller = new AuthController(provider, users, principalResolver, clock);
        return standaloneSetup(controller).setControllerAdvice(new ApiExceptionHandler()).build();
    }

    /* ------------------------------------------------------------------ */
    /* 种子                                                                 */
    /* ------------------------------------------------------------------ */

    /** 初始密码 = 学号（must_change_password=true），roles 为 JSON 文本。 */
    public void seedUser(String id, String rolesJson, Integer dailyQuota, boolean enabled,
                         boolean mustChange, String password) throws Exception {
        String hash = BCrypt.hashpw(password, BCrypt.gensalt(4));
        insertUserRaw(id, hash, rolesJson, dailyQuota, enabled, mustChange, null);
    }

    public void seedUserWithHash(String id, String passwordHash, String rolesJson, Integer dailyQuota,
                                 boolean enabled, boolean mustChange) throws Exception {
        insertUserRaw(id, passwordHash, rolesJson, dailyQuota, enabled, mustChange, null);
    }

    private void insertUserRaw(String id, String hash, String rolesJson, Integer dailyQuota,
                               boolean enabled, boolean mustChange, String createdBy) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users(id, password_hash, roles, daily_quota, enabled, "
                             + "must_change_password, created_at, created_by) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, hash);
            ps.setString(3, rolesJson);
            if (dailyQuota == null) {
                ps.setObject(4, null);
            } else {
                ps.setInt(4, dailyQuota);
            }
            ps.setBoolean(5, enabled);
            ps.setBoolean(6, mustChange);
            ps.setObject(7, java.time.OffsetDateTime.parse("2026-10-25T00:00:00+00:00"));
            ps.setString(8, createdBy);
            ps.executeUpdate();
        }
    }

    /** 直接落一行已知 token（过期/手工场景）。 */
    public void insertToken(String token, String userId, String issuedAt, String expiresAt) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO api_tokens(token_hash, user_id, issued_at, expires_at) VALUES (?,?,?,?)")) {
            ps.setString(1, TokenMint.digest(token));
            ps.setString(2, userId);
            ps.setObject(3, java.time.OffsetDateTime.parse(issuedAt));
            ps.setObject(4, java.time.OffsetDateTime.parse(expiresAt));
            ps.executeUpdate();
        }
    }

    /** 口令重置回学号并重新置位 must_change_password（隔离用例间口令变更）。 */
    public void resetToInitialPassword(String userId) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET password_hash=?, must_change_password=? WHERE id=?")) {
            ps.setString(1, BCrypt.hashpw(userId, BCrypt.gensalt(4)));
            ps.setBoolean(2, true);
            ps.setString(3, userId);
            ps.executeUpdate();
        }
    }

    public void setEnabled(String userId, boolean enabled) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement("UPDATE users SET enabled=? WHERE id=?")) {
            ps.setBoolean(1, enabled);
            ps.setString(2, userId);
            ps.executeUpdate();
        }
    }

    public String columnValue(String table, String column, String where, String arg) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + column + " AS v FROM " + table + " WHERE " + where)) {
            ps.setString(1, arg);
            var rs = ps.executeQuery();
            return rs.next() ? rs.getString("v") : null;
        }
    }

    public int countRows(String table, String where, String arg) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) AS c FROM " + table + (where == null ? "" : " WHERE " + where))) {
            if (where != null) {
                ps.setString(1, arg);
            }
            var rs = ps.executeQuery();
            rs.next();
            return rs.getInt("c");
        }
    }

    public static JsonNode json(Object value) throws Exception {
        return MAPPER.readTree(MAPPER.writeValueAsString(value));
    }

    public static Map<String, Object> parseObject(String content) throws Exception {
        return MAPPER.readValue(content, new com.fasterxml.jackson.core.type.TypeReference<
                LinkedHashMap<String, Object>>() {
        });
    }
}
