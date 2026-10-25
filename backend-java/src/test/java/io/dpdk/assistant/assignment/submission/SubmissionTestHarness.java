package io.dpdk.assistant.assignment.submission;

import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.admin.AdminClassScopeQueries;
import io.dpdk.assistant.admin.AdminUserRepository;
import io.dpdk.assistant.assignment.catalog.AssignmentCatalogController;
import io.dpdk.assistant.assignment.catalog.AssignmentCatalogRepository;
import io.dpdk.assistant.assignment.catalog.AssignmentCatalogService;
import io.dpdk.assistant.assignment.catalog.AssignmentCatalogPort;
import io.dpdk.assistant.assignment.report.AssignmentReportController;
import io.dpdk.assistant.assignment.report.GradeReportRepository;
import io.dpdk.assistant.assignment.report.GradeReportService;
import io.dpdk.assistant.auth.AuthAccountWriteGuard;
import io.dpdk.assistant.auth.AuthController;
import io.dpdk.assistant.auth.AuthPrincipalResolver;
import io.dpdk.assistant.auth.AuthServerSettings;
import io.dpdk.assistant.auth.AuthSettingsProvider;
import io.dpdk.assistant.auth.UserAuthRepository;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.model.Deadline;
import io.dpdk.assistant.contracts.model.HarnessPort;
import io.dpdk.assistant.contracts.model.HarnessReply;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.platform.errors.ApiExceptionHandler;
import io.dpdk.assistant.platform.migration.MigrationController;
import io.dpdk.assistant.platform.pg.TestPg;
import io.dpdk.assistant.platform.tx.SpringTransactionRunner;
import io.dpdk.assistant.usage.UsageCounterStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * J2-04 测试基座：独立 PG 库 + B0015 迁移 + 真 auth/admin/catalog/report/usage
 * 装配 + MockMvc standalone。harness 以脚本化 HarnessPort 替身注入（契约测试），
 * 真实 StreamableHttpHarnessClient + JDK HttpServer fake MCP 另配
 * （GradingQuotaIntegrationTest）。敏感纪律：替身只记调用计数与参数指纹，
 * 不落源码文本、不打印请求体。
 */
public final class SubmissionTestHarness {

    public static final String TOKEN_VIEWER = "v".repeat(32);
    public static final String TOKEN_OPERATOR = "o".repeat(32);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public final DataSource ds;
    public final String database;
    public final SubmissionClock clock = new SubmissionClock();
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
    public final UsageCounterStore counters;
    public final SubmissionGradeWriter writer;
    public final SubmissionRecordReader reader;
    public final ScriptedHarness harness = new ScriptedHarness();

    /** 提交链路的 harness 端口（quota 集成测试切到真实 StreamableHttpHarnessClient）。 */
    public volatile HarnessPort submitHarness = harness;
    /** 提交链路评测设置（用例可临时覆盖后重建 MockMvc）。 */
    public volatile SubmissionSettings gradingSettings = SubmissionSettings.DEFAULTS;

    private final AuthAccountWriteGuard writeGuard;

    private SubmissionTestHarness(DataSource ds, String database, AuthServerSettings initial) {
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
        this.counters = new UsageCounterStore(runner, clock);
        this.writeGuard = new AuthAccountWriteGuard(settings);
        this.writer = new SubmissionGradeWriter(runner, writeGuard, clock);
        this.reader = new SubmissionRecordReader(runner);
    }

    /** account 模式 + 静态 viewer/operator token + bcrypt cost 4。 */
    public static SubmissionTestHarness accountMode(String tag) throws Exception {
        AuthServerSettings settings = AuthServerSettings.of("account",
                "{\"" + TOKEN_VIEWER + "\":{\"subject\":\"t-viewer\",\"roles\":[\"viewer\"],\"daily_quota\":0},"
                        + "\"" + TOKEN_OPERATOR + "\":{\"subject\":\"t-operator\",\"roles\":[\"operator\"],\"daily_quota\":0}}",
                7, 8, 4, 200);
        String database = TestPg.createDatabase("j204" + tag, TestPg.COMMENT);
        DataSource ds = TestPg.dataSource(database);
        new MigrationController().run(ds, TestPg.identity(database), true);
        return new SubmissionTestHarness(ds, database, settings);
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
        GradingInvoker invoker = new GradingInvoker(submitHarness, counters, clock,
                gradingSettings);
        AssignmentSubmissionService service = new AssignmentSubmissionService(
                catalog, users, invoker, writer, clock);
        AssignmentSubmissionController submitController = new AssignmentSubmissionController(
                settings, resolver, service, gradingSettings);
        return standaloneSetup(authController, catalogController, reportController,
                        submitController)
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /** 静态 operator token 主体（豁免角色分支）。 */
    public Principal operatorPrincipal() {
        return new Principal("t-operator", List.of("operator"), 0, false);
    }

    /* ------------------------------------------------------------------ */
    /* 种子                                                                 */
    /* ------------------------------------------------------------------ */

    public void seedUser(String id, String rolesJson, Integer dailyQuota, boolean enabled,
            String password, String createdBy) throws Exception {
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
            ps.setBoolean(6, false);
            ps.setString(7, createdBy);
            ps.executeUpdate();
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

    public int countRows(String table, String where, String... args) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) AS c FROM " + table
                             + (where == null ? "" : " WHERE " + where))) {
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

    /** 直接写 usage_counters（quota 前置态）。 */
    public void seedCounter(String subject, String day, String kind, int count) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO usage_counters(subject, day, kind, chat_count) VALUES (?,?,?,?)"
                             + " ON CONFLICT(subject, day, kind) DO UPDATE SET chat_count = ?")) {
            ps.setString(1, subject);
            ps.setString(2, day);
            ps.setString(3, kind);
            ps.setInt(4, count);
            ps.setInt(5, count);
            ps.executeUpdate();
        }
    }

    public int counterValue(String subject, String kind) throws Exception {
        String v = columnValue("usage_counters", "chat_count",
                "subject=? AND kind=?", subject, kind);
        return v == null ? 0 : Integer.parseInt(v);
    }

    /* ------------------------------------------------------------------ */
    /* 时钟：固定墙钟 + 真单调（真 harness 客户端按 System.nanoTime 判截止）  */
    /* ------------------------------------------------------------------ */

    /** 固定 UTC 墙钟（golden 动态字段映射）+ 系统单调钟。 */
    public static final class SubmissionClock implements ClockPort {

        private volatile Instant wall = Instant.parse("2026-10-25T04:00:00Z");
        private Instant lastStamp = Instant.EPOCH;

        public void setWall(Instant instant) {
            this.wall = instant;
        }

        @Override
        public Instant nowUtc() {
            return wall;
        }

        @Override
        public long monotonicNanos() {
            return System.nanoTime();
        }

        @Override
        public synchronized String nextSessionIso() {
            Instant now = wall.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            if (!now.isAfter(lastStamp)) {
                now = lastStamp.plusNanos(1_000);
            }
            lastStamp = now;
            return io.dpdk.assistant.platform.time.SystemClockAdapter.pythonIso(now);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 脚本化 harness 替身（只计数，不落/不打印源码）                        */
    /* ------------------------------------------------------------------ */

    /**
     * 脚本化 HarnessPort：grade_plugin 按脚本返回；调用只记计数与参数指纹
     * （protocol + plugin_source 长度），绝不保存源码文本。
     */
    public static final class ScriptedHarness implements HarnessPort {

        /** 脚本动作：success(payloadJson) / notSubmitted(error) / unknown(error)。 */
        public enum Mode {
            SUCCESS, NOT_SUBMITTED_UNAVAILABLE, UNKNOWN_UNAVAILABLE, DEADLINE_EXCEEDED
        }

        /** 一次调用指纹（不含源码）。 */
        public record CallFingerprint(String tool, String protocol, int pluginSourceLength) {
        }

        volatile Mode mode = Mode.SUCCESS;
        volatile String successPayloadJson = defaultGradePayload("dns", 80, 4, 5, "["
                + "{\"name\":\"synthetic-case\",\"result\":\"FAIL\",\"category\":\"basic\","
                + "\"failures\":[{\"desc\":\"synthetic field\",\"expected\":\"x\",\"actual\":\"y\"}]}]");
        volatile String notSubmittedError = "harness_unavailable";
        final List<CallFingerprint> fingerprints =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        final AtomicInteger gradeCalls = new AtomicInteger();
        volatile Runnable onCall;

        @Override
        public HarnessReply call(String tool, Map<String, Object> args, Deadline deadline) {
            if (!"grade_plugin".equals(tool)) {
                throw new IllegalArgumentException("J2-04 替身仅支持 grade_plugin: " + tool);
            }
            gradeCalls.incrementAndGet();
            fingerprints.add(new CallFingerprint(tool, String.valueOf(args.get("protocol")),
                    String.valueOf(args.get("plugin_source")).length()));
            Runnable hook = onCall;
            if (hook != null) {
                hook.run();
            }
            return switch (mode) {
                case SUCCESS -> new HarnessReply(HarnessReply.SubmissionPhase.SUBMITTED,
                        successPayloadJson, null);
                case NOT_SUBMITTED_UNAVAILABLE -> new HarnessReply(
                        HarnessReply.SubmissionPhase.NOT_SUBMITTED, errorPayload(notSubmittedError),
                        notSubmittedError);
                case UNKNOWN_UNAVAILABLE -> new HarnessReply(HarnessReply.SubmissionPhase.UNKNOWN,
                        errorPayload(notSubmittedError), notSubmittedError);
                case DEADLINE_EXCEEDED -> new HarnessReply(
                        HarnessReply.SubmissionPhase.NOT_SUBMITTED,
                        errorPayload("harness_deadline_exceeded"), "harness_deadline_exceeded");
            };
        }

        public void success() {
            this.mode = Mode.SUCCESS;
        }

        public void unknown(String error) {
            this.mode = Mode.UNKNOWN_UNAVAILABLE;
            this.notSubmittedError = error;
        }

        public void notSubmitted(String error) {
            this.mode = Mode.NOT_SUBMITTED_UNAVAILABLE;
            this.notSubmittedError = error;
        }

        public void deadlineExceeded() {
            this.mode = Mode.DEADLINE_EXCEEDED;
        }

        /** 切换成功 payload（协议/分数/用例数/结果列表）。 */
        public void setSuccessPayload(String protocol, int score, int passed, int total,
                String resultsJsonArray) {
            this.successPayloadJson = defaultGradePayload(protocol, score, passed, total,
                    resultsJsonArray);
        }

        public int gradeCallCount() {
            return gradeCalls.get();
        }

        /** 重置调用计数（用例起点断言 MCP 恰 N 次）。 */
        public void resetCounters() {
            gradeCalls.set(0);
            fingerprints.clear();
        }

        public static String errorPayload(String error) {
            return "{\"ok\":false,\"error\":\"" + error + "\",\"detail\":\"评测服务不可达："
                    + "Synthetic\"}";
        }
    }

    /** oracle ports.grade_payload 同形（协议参数化）。 */
    public static String defaultGradePayload(String protocol, int score, int passed, int total,
            String resultsJsonArray) {
        return "{\"ok\":true,\"protocol\":\"" + protocol + "\",\"score\":" + score
                + ",\"passed\":" + passed + ",\"cases_total\":" + total
                + ",\"failed\":1,\"crashed\":0,\"timedout\":0,\"cheat\":0,"
                + "\"results\":" + resultsJsonArray + "}";
    }

    /* ------------------------------------------------------------------ */
    /* 身份（golden runtime source 由 BASELINE 常量程序化构造，不落证据文本） */
    /* ------------------------------------------------------------------ */

    public static final String GOLDEN_BASELINE = "80b29135faddd16d5e39fdbf0633291ea6a20489";

    /** oracle route_cases 的 runtime source（仅测试内存构造；证据只出现其 sha256）。 */
    public static String goldenRuntimeSource() {
        String marker = sha256Hex((GOLDEN_BASELINE + ":p0-v2-runtime-source")
                .getBytes(StandardCharsets.UTF_8)).substring(0, 32);
        return "/* P0 " + marker + " */\nint plugin_main(void){return 0;}\n";
    }

    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest(data)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode loadGolden() throws Exception {
        try (var in = SubmissionTestHarness.class.getResourceAsStream(
                "/migration/J2-04/golden-submit.json")) {
            return new ObjectMapper().readTree(in);
        }
    }
}
