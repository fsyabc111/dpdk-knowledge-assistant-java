package io.dpdk.assistant.admin;

import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.auth.AuthController;
import io.dpdk.assistant.auth.AuthPrincipalResolver;
import io.dpdk.assistant.auth.AuthServerSettings;
import io.dpdk.assistant.auth.AuthSettingsProvider;
import io.dpdk.assistant.auth.AuthAccountWriteGuard;
import io.dpdk.assistant.auth.TestClock;
import io.dpdk.assistant.auth.UserAuthRepository;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.platform.errors.ApiExceptionHandler;
import io.dpdk.assistant.platform.migration.MigrationController;
import io.dpdk.assistant.platform.pg.TestPg;
import io.dpdk.assistant.platform.tx.SpringTransactionRunner;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * J2-02 测试基座：独立 PG 库 + B0015/V0014 迁移 + 真 auth/admin 仓储与控制器 +
 * MockMvc standalone（Controller 每次新建，auth 限流/锁定状态用例间隔离）。
 * taskCancellation 记录桩用于断言停号→取消端口的调用时序（真实执行体 integration_pending）。
 */
public final class AdminTestHarness {

    public static final String FALLBACK_TOKEN = "f".repeat(32);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public final DataSource ds;
    public final String database;
    public final TestClock clock = new TestClock();
    public final AuthSettingsProvider settings;
    public final TransactionRunner runner;
    public final UserAuthRepository auth;
    public final AuthPrincipalResolver resolver;
    public final AuthAccountWriteGuard guard;
    public final AdminUserRepository users;
    public final AdminClassScopeQueries scopeQueries;
    public final List<String> taskCancels = Collections.synchronizedList(new ArrayList<>());
    public final LocalTaskCancellationPort taskCancellation = taskCancels::add;

    private AdminTestHarness(DataSource ds, String database, AuthServerSettings initial) {
        this.ds = ds;
        this.database = database;
        this.settings = AuthSettingsProvider.fixed(initial);
        this.runner = new SpringTransactionRunner(
                new TransactionTemplate(new DataSourceTransactionManager(ds)), ds);
        this.auth = new UserAuthRepository(runner, clock, settings);
        this.resolver = new AuthPrincipalResolver(settings, auth);
        this.guard = new AuthAccountWriteGuard(settings);
        this.users = new AdminUserRepository(runner, clock, settings, auth, auth);
        this.scopeQueries = new AdminClassScopeQueries(settings, users);
    }

    /** account 模式 + 静态兜底 token（roles=admin）+ bcrypt cost 4（对齐 Python 测试环境）。 */
    public static AdminTestHarness accountMode(String tag) throws Exception {
        AuthServerSettings settings = AuthServerSettings.of("account",
                "{\"" + FALLBACK_TOKEN + "\":{\"subject\":\"emergency-admin\",\"roles\":[\"admin\"],\"daily_quota\":0}}",
                7, 8, 4, 200);
        return create(tag, settings);
    }

    public static AdminTestHarness create(String tag, AuthServerSettings settings) throws Exception {
        String database = TestPg.createDatabase("j202" + tag, TestPg.COMMENT);
        DataSource ds = TestPg.dataSource(database);
        new MigrationController().run(ds, TestPg.identity(database), true);
        return new AdminTestHarness(ds, database, settings);
    }

    public void drop() throws Exception {
        TestPg.dropDatabase(database);
    }

    /** 每用例新建 Controller（auth 限流/锁定进程内状态随之隔离）。 */
    public MockMvc newMvc() {
        AuthController authController =
                new AuthController(settings, auth, resolver, clock);
        AdminController adminController = new AdminController(
                settings, resolver, users, auth, auth, taskCancellation);
        return standaloneSetup(authController, adminController)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /* ------------------------------------------------------------------ */
    /* 种子                                                                 */
    /* ------------------------------------------------------------------ */

    /** 全字段可控（created_at/created_by 供 golden 字节级断言与树构造）。 */
    public void seedUser(String id, String rolesJson, Integer dailyQuota, boolean enabled,
                         boolean mustChange, String password, String createdBy,
                         Instant createdAt) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users(id, password_hash, roles, daily_quota, enabled, "
                             + "must_change_password, created_at, created_by) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, BCrypt.hashpw(password, BCrypt.gensalt(4)));
            ps.setString(3, rolesJson);
            if (dailyQuota == null) {
                ps.setObject(4, null);
            } else {
                ps.setInt(4, dailyQuota);
            }
            ps.setBoolean(5, enabled);
            ps.setBoolean(6, mustChange);
            ps.setObject(7, java.time.OffsetDateTime.ofInstant(createdAt,
                    java.time.ZoneOffset.UTC));
            ps.setString(8, createdBy);
            ps.executeUpdate();
        }
    }

    /** 便捷重载：初始密码=学号、must_change=true。 */
    public void seedUser(String id, String rolesJson, Integer dailyQuota, boolean enabled,
                         boolean mustChange, String password, String createdBy) throws Exception {
        seedUser(id, rolesJson, dailyQuota, enabled, mustChange, password, createdBy,
                Instant.parse("2026-10-25T00:00:00Z"));
    }

    public void insertUsageCounter(String subject, String day, int count) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO usage_counters(subject, day, chat_count, kind) VALUES (?,?,?,'chat')")) {
            ps.setString(1, subject);
            ps.setString(2, day);
            ps.setInt(3, count);
            ps.executeUpdate();
        }
    }

    public void insertPendingResetRequest(String id, String userId, String createdAt)
            throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO password_reset_requests(id, user_id, status, created_at) "
                             + "VALUES (?,?,'pending',?)")) {
            ps.setString(1, id);
            ps.setString(2, userId);
            ps.setObject(3, java.time.OffsetDateTime.parse(createdAt));
            ps.executeUpdate();
        }
    }

    /** 会话 + 两条消息 + 一条引用（删除级联断言用），返回 session id。 */
    public String insertSessionWithContent(String userId, String sessionId) throws Exception {
        try (Connection conn = ds.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO assistant_sessions(id, user_id, title, created_at, updated_at) "
                            + "VALUES (?,?,?,now(),now())")) {
                ps.setString(1, sessionId);
                ps.setString(2, userId);
                ps.setString(3, "t");
                ps.executeUpdate();
            }
            insertMessage(conn, sessionId, "m1", "user", "hello");
            insertMessage(conn, sessionId, "m2", "assistant", "hi");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO assistant_citations(id, message_id, chunk_id, score, rank) "
                            + "VALUES (?,?,?,?,?)")) {
                ps.setString(1, "c1");
                ps.setString(2, "m1");
                ps.setString(3, "chunk-1");
                ps.setDouble(4, 0.5);
                ps.setInt(5, 1);
                ps.executeUpdate();
            }
        }
        return sessionId;
    }

    private static void insertMessage(Connection conn, String sessionId, String messageId,
                                      String role, String content) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO assistant_messages(id, session_id, role, content, created_at) "
                        + "VALUES (?,?,?,?,now())")) {
            ps.setString(1, messageId);
            ps.setString(2, sessionId);
            ps.setString(3, role);
            ps.setString(4, content);
            ps.executeUpdate();
        }
    }

    /** 会话关联成绩（删除后应保留且 session_id 置 NULL）。 */
    public void insertGradingSubmission(String id, String userId, String sessionId,
                                        String taskJobId) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO grading_submissions(id, user_id, session_id, protocol, code_hash, "
                             + "score, passed, total, results_json, created_at, task_job_id) "
                             + "VALUES (?,?,?,?,?,?,?,?,'[]'::jsonb,now(),?)")) {
            ps.setString(1, id);
            ps.setString(2, userId);
            ps.setString(3, sessionId);
            ps.setString(4, "http");
            ps.setString(5, "hash-" + id);
            ps.setInt(6, 80);
            ps.setInt(7, 8);
            ps.setInt(8, 10);
            ps.setString(9, taskJobId);
            ps.executeUpdate();
        }
    }

    /** pending rag_request + 任务输入 + 任务 job（停号级联断言用）。 */
    public void insertPendingRagTask(String taskId, String userId) throws Exception {
        try (Connection conn = ds.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO rag_requests(id, question, status, created_at, updated_at, user_id) "
                            + "VALUES (?, 'q', 'pending', '2026-10-25T00:00:00+00:00', "
                            + "'2026-10-25T00:00:00+00:00', ?)")) {
                ps.setString(1, taskId);
                ps.setString(2, userId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO rag_task_inputs(task_id, question, created_at, expires_at) "
                            + "VALUES (?, 'q', '2026-10-25T00:00:00+00:00', '2026-10-26T00:00:00+00:00')")) {
                ps.setString(1, taskId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO rag_task_jobs(id, task_id, job_id, tool, status, created_at, updated_at) "
                            + "VALUES (?,?,?,'submit_source','running',"
                            + "'2026-10-25T00:00:00+00:00','2026-10-25T00:00:00+00:00')")) {
                ps.setString(1, "job-row-" + taskId);
                ps.setString(2, taskId);
                ps.setString(3, "job-" + taskId);
                ps.executeUpdate();
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 查询与登录                                                           */
    /* ------------------------------------------------------------------ */

    public int countRows(String table, String where, String... args) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) AS c FROM " + table + (where == null ? "" : " WHERE " + where))) {
            bind(ps, args);
            var rs = ps.executeQuery();
            rs.next();
            return rs.getInt("c");
        }
    }

    public String columnValue(String table, String column, String where, String... args)
            throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + column + " AS v FROM " + table + " WHERE " + where)) {
            bind(ps, args);
            var rs = ps.executeQuery();
            return rs.next() ? rs.getString("v") : null;
        }
    }

    private static void bind(PreparedStatement ps, String... args) throws Exception {
        if (args == null) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            ps.setString(i + 1, args[i]);
        }
    }

    /** 经 /api/auth/login 换 token（限流随 mvc 实例隔离）。 */
    public String login(MockMvc mvc, String username, String password) throws Exception {
        MvcResult result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/login")
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(Map.of(
                                "username", username, "password", password))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    public static JsonNode loadGolden() throws Exception {
        try (var in = AdminTestHarness.class.getResourceAsStream(
                "/migration/J2-02/golden-admin.json")) {
            return new ObjectMapper().readTree(in);
        }
    }
}
