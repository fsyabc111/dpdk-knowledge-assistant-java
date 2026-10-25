package io.dpdk.assistant.assignment;

import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.admin.AdminClassScopeQueries;
import io.dpdk.assistant.admin.AdminUserRepository;
import io.dpdk.assistant.assignment.catalog.AssignmentCatalogController;
import io.dpdk.assistant.assignment.catalog.AssignmentCatalogRepository;
import io.dpdk.assistant.assignment.catalog.AssignmentCatalogService;
import io.dpdk.assistant.assignment.report.AssignmentReportController;
import io.dpdk.assistant.assignment.report.GradeReportRepository;
import io.dpdk.assistant.assignment.report.GradeReportService;
import io.dpdk.assistant.auth.AuthAccountWriteGuard;
import io.dpdk.assistant.auth.AuthController;
import io.dpdk.assistant.auth.AuthPrincipalResolver;
import io.dpdk.assistant.auth.AuthServerSettings;
import io.dpdk.assistant.auth.AuthSettingsProvider;
import io.dpdk.assistant.auth.TestClock;
import io.dpdk.assistant.auth.UserAuthRepository;
import io.dpdk.assistant.contracts.model.Deadline;
import io.dpdk.assistant.contracts.model.HarnessPort;
import io.dpdk.assistant.contracts.model.HarnessReply;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.platform.errors.ApiExceptionHandler;
import io.dpdk.assistant.platform.migration.MigrationController;
import io.dpdk.assistant.platform.pg.TestPg;
import io.dpdk.assistant.platform.tx.SpringTransactionRunner;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * J2-03 测试基座：独立 PG 库 + B0015 迁移 + 真 auth/admin/assignment 装配 +
 * MockMvc standalone。静态 viewer/operator token 对齐 Python 测试的 API_TOKENS_JSON；
 * harness 以可控 HarnessPort 替身注入（脚本化 list_protocols 成功/失败）。
 */
public final class AssignmentTestHarness {

    public static final String TOKEN_VIEWER = "v".repeat(32);
    public static final String TOKEN_OPERATOR = "o".repeat(32);
    /** 静态 admin token（老师树口径用）。 */
    public static final String TOKEN_TEACHER_STATIC = "t".repeat(32);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public final DataSource ds;
    public final String database;
    public final TestClock clock = new TestClock();
    public final AuthSettingsProvider settings;
    public final TransactionRunner runner;
    public final UserAuthRepository auth;
    public final AuthPrincipalResolver resolver;
    public final AdminUserRepository users;
    public final AdminClassScopeQueries scopeQueries;
    public final AssignmentCatalogRepository catalogRepo;
    public final AssignmentCatalogService catalog;
    public final GradeReportRepository reportRepo;
    public final GradeReportService report;
    public final FakeHarness harness = new FakeHarness();

    private AssignmentTestHarness(DataSource ds, String database, AuthServerSettings initial) {
        this.ds = ds;
        this.database = database;
        this.settings = AuthSettingsProvider.fixed(initial);
        this.runner = new SpringTransactionRunner(
                new TransactionTemplate(new DataSourceTransactionManager(ds)), ds);
        this.auth = new UserAuthRepository(runner, clock, settings);
        this.resolver = new AuthPrincipalResolver(settings, auth);
        this.users = new AdminUserRepository(runner, clock, settings, auth, auth);
        this.scopeQueries = new AdminClassScopeQueries(settings, users);
        this.catalogRepo = new AssignmentCatalogRepository(runner, clock);
        this.catalog = new AssignmentCatalogService(catalogRepo);
        this.reportRepo = new GradeReportRepository(runner);
        this.report = new GradeReportService(catalog, reportRepo, scopeQueries);
    }

    /** account 模式 + 静态 viewer/operator token + bcrypt cost 4。 */
    public static AssignmentTestHarness accountMode(String tag) throws Exception {
        AuthServerSettings settings = AuthServerSettings.of("account",
                "{\"" + TOKEN_VIEWER + "\":{\"subject\":\"t-viewer\",\"roles\":[\"viewer\"],\"daily_quota\":0},"
                        + "\"" + TOKEN_OPERATOR + "\":{\"subject\":\"t-operator\",\"roles\":[\"operator\"],\"daily_quota\":0},"
                        + "\"" + TOKEN_TEACHER_STATIC + "\":{\"subject\":\"t-teacher\",\"roles\":[\"admin\"],\"daily_quota\":0}}",
                7, 8, 4, 200);
        return create(tag, settings);
    }

    public static AssignmentTestHarness create(String tag, AuthServerSettings settings)
            throws Exception {
        String database = TestPg.createDatabase("j203" + tag, TestPg.COMMENT);
        DataSource ds = TestPg.dataSource(database);
        new MigrationController().run(ds, TestPg.identity(database), true);
        return new AssignmentTestHarness(ds, database, settings);
    }

    public void drop() throws Exception {
        TestPg.dropDatabase(database);
    }

    /** 每用例新建 Controller（auth 限流/锁定进程内状态随之隔离）。 */
    public MockMvc newMvc() {
        AuthController authController = new AuthController(settings, auth, resolver, clock);
        AssignmentCatalogController catalogController = new AssignmentCatalogController(
                settings, resolver, users, catalog, harness, clock);
        AssignmentReportController reportController = new AssignmentReportController(
                settings, resolver, report);
        return standaloneSetup(authController, catalogController, reportController)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /* ------------------------------------------------------------------ */
    /* 种子                                                                 */
    /* ------------------------------------------------------------------ */

    public void seedUser(String id, String rolesJson, Integer dailyQuota, boolean enabled,
                         boolean mustChange, String password, String createdBy) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users(id, password_hash, roles, daily_quota, enabled, "
                             + "must_change_password, created_at, created_by) VALUES (?,?,?,?,?,?,now(),?)")) {
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
            ps.setString(7, createdBy);
            ps.executeUpdate();
        }
    }

    /** 成绩行种子（J2-03 用合成已落库成绩验收读取；acceptedAssignmentJson 可空）。 */
    public void seedSubmission(String id, String userId, String protocol, int score,
            String assignmentId, Instant createdAt, String acceptedAssignmentJson)
            throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO grading_submissions(id, user_id, session_id, protocol, code_hash, "
                             + "score, passed, total, results_json, created_at, assignment_id) "
                             + "VALUES (?,?,NULL,?,?,?,?,?,'[]'::jsonb,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, userId);
            ps.setString(3, protocol);
            ps.setString(4, "hash-" + id);
            ps.setInt(5, score);
            ps.setInt(6, score / 20);
            ps.setInt(7, 5);
            ps.setTimestamp(8, Timestamp.from(createdAt));
            if (assignmentId == null) {
                ps.setObject(9, null);
            } else {
                ps.setString(9, assignmentId);
            }
            ps.executeUpdate();
        }
        if (acceptedAssignmentJson != null) {
            // 把受理快照嵌进结果 JSON（对位 accepted_assignment 列现状：嵌在 results_json）。
            try (Connection conn = ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE grading_submissions SET results_json = ?::jsonb WHERE id = ?")) {
                ps.setString(1, "{\"accepted_assignment\":" + acceptedAssignmentJson + "}");
                ps.setString(2, id);
                ps.executeUpdate();
            }
        }
    }

    /** 删除账号行（先删其 api_tokens，等价真实删除级联的终态：成绩保留、token 消失）。 */
    public void deleteUser(String id) throws Exception {
        try (Connection conn = ds.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM api_tokens WHERE user_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM users WHERE id = ?")) {
                ps.setString(1, id);
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
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
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
            for (int i = 0; i < args.length; i++) {
                ps.setString(i + 1, args[i]);
            }
            var rs = ps.executeQuery();
            return rs.next() ? rs.getString("v") : null;
        }
    }

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
        try (var in = AssignmentTestHarness.class.getResourceAsStream(
                "/migration/J2-03/golden-assignments.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    /* ------------------------------------------------------------------ */
    /* harness 替身（脚本化 list_protocols）                                 */
    /* ------------------------------------------------------------------ */

    /** 默认脚本：dns/http 两协议（对位 Python 测试假实现；golden 流程另设 dns-only）。 */
    public static final String DEFAULT_PROTOCOLS_JSON = "{\"ok\":true,\"protocols\":["
            + "{\"name\":\"dns\",\"cases\":5,\"categories\":{\"basic\":5}},"
            + "{\"name\":\"http\",\"cases\":5,\"categories\":{\"basic\":5}}]}";

    /** 可控 HarnessPort：脚本化 list_protocols。 */
    public static final class FakeHarness implements HarnessPort {

        volatile String listProtocolsJson = DEFAULT_PROTOCOLS_JSON;
        final AtomicInteger listProtocolCalls = new AtomicInteger();

        @Override
        public HarnessReply call(String tool, Map<String, Object> args, Deadline deadline) {
            if ("list_protocols".equals(tool)) {
                listProtocolCalls.incrementAndGet();
                return new HarnessReply(HarnessReply.SubmissionPhase.SUBMITTED,
                        listProtocolsJson, null);
            }
            throw new IllegalArgumentException("J2-03 替身仅支持 list_protocols: " + tool);
        }

        /** 切换脚本（成功或 {"ok":false,...} 降级形态）。 */
        public void setProtocolsJson(String json) {
            this.listProtocolsJson = json;
        }
    }
}
