package io.dpdk.assistant.session;

import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.admin.AdminClassScopeQueries;
import io.dpdk.assistant.admin.AdminUserRepository;
import io.dpdk.assistant.auth.AuthController;
import io.dpdk.assistant.auth.AuthPrincipalResolver;
import io.dpdk.assistant.auth.AuthServerSettings;
import io.dpdk.assistant.auth.AuthSettingsProvider;
import io.dpdk.assistant.auth.TestClock;
import io.dpdk.assistant.auth.UserAuthRepository;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.grading.query.GradingQueryController;
import io.dpdk.assistant.grading.query.GradingQueryRepository;
import io.dpdk.assistant.platform.errors.ApiExceptionHandler;
import io.dpdk.assistant.platform.migration.MigrationController;
import io.dpdk.assistant.platform.pg.TestPg;
import io.dpdk.assistant.platform.tx.SpringTransactionRunner;
import io.dpdk.assistant.source.SourceController;
import io.dpdk.assistant.source.SourceRepository;
import io.dpdk.assistant.template.TemplateController;
import io.dpdk.assistant.usage.UsageCounterStore;
import io.dpdk.assistant.usage.UsageLedgerStore;
import io.dpdk.assistant.usage.UsageQueryController;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * J2-05 测试基座：独立 PG 库 + B0015/V0014 迁移 + 真仓储/控制器 + MockMvc
 * standalone（auth 进程内状态用例间隔离，Controller 每次 newMvc 重建）。
 *
 * 时间口径：TestClock 墙钟固定 2026-10-24T04:00:00Z（golden today/since 基准）；
 * 种子行 created_at/updated_at 用显式微秒 Instant（对齐 oracle 观察序），
 * 渲染断言经 SessionTime.pythonStr 与 golden 逐字段比对。
 */
public final class J205TestHarness {

    public static final String FALLBACK_TOKEN = "f".repeat(32);
    public static final String MAINT_KEY = "m".repeat(32);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** golden 微秒基准（oracle 观察序列起点）。 */
    public static final Instant T0 = Instant.parse("2026-10-24T04:00:00Z");

    public final DataSource ds;
    public final String database;
    public final TestClock clock = new TestClock();
    public final AuthSettingsProvider settings;
    public final TransactionRunner runner;
    public final UserAuthRepository auth;
    public final AuthPrincipalResolver resolver;
    public final AdminUserRepository users;
    public final AdminClassScopeQueries classScope;
    public final SessionService sessions;
    public final SessionPortAdapter sessionPort;
    public final UsageCounterStore counters;
    public final UsageLedgerStore ledger;
    public final GradingQueryRepository grading;
    public final SourceRepository sources;
    private MaintenanceSettings maintenance = new MaintenanceSettings(MAINT_KEY, 30);
    private SessionService.ActiveReleaseSupplier activeReleaseOverride;

    private J205TestHarness(DataSource ds, String database, AuthServerSettings initial) {
        this.ds = ds;
        this.database = database;
        this.clock.setWall(T0);
        this.settings = AuthSettingsProvider.fixed(initial);
        this.runner = new SpringTransactionRunner(
                new TransactionTemplate(new DataSourceTransactionManager(ds)), ds);
        this.auth = new UserAuthRepository(runner, clock, settings);
        this.resolver = new AuthPrincipalResolver(settings, auth);
        this.users = new AdminUserRepository(runner, clock, settings, auth, auth);
        this.classScope = new AdminClassScopeQueries(settings, users);
        this.sessions = new SessionService(runner, clock, currentActiveRelease());
        this.sessionPort = new SessionPortAdapter(sessions);
        this.counters = new UsageCounterStore(runner, clock);
        this.ledger = new UsageLedgerStore(runner);
        this.grading = new GradingQueryRepository(runner);
        this.sources = new SourceRepository(runner, "li_knowledge_v1");
    }

    private SessionService.ActiveReleaseSupplier currentActiveRelease() {
        return () -> {
            SessionService.ActiveReleaseSupplier override = activeReleaseOverride;
            if (override != null) {
                return override.get();
            }
            try (Connection conn = ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT release_id FROM rag_index_state WHERE name='active'");
                 var rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
    }

    /** CAS 并发测试：整体替换 active release 解析（可挂 ThreadLocal 替身）。 */
    public void setActiveReleaseOverride(SessionService.ActiveReleaseSupplier supplier) {
        this.activeReleaseOverride = supplier;
    }

    public void setMaintenance(MaintenanceSettings value) {
        this.maintenance = value;
    }

    /** account 模式 + 静态兜底 token（admin）+ bcrypt 4 + 配额默认 10000（golden 树）。 */
    public static J205TestHarness accountMode(String tag) throws Exception {
        AuthServerSettings settings = AuthServerSettings.of("account",
                "{\"" + FALLBACK_TOKEN + "\":{\"subject\":\"emergency-admin\",\"roles\":[\"admin\"],"
                        + "\"daily_quota\":0}}",
                7, 8, 4, 10000);
        return create(tag, settings);
    }

    public static J205TestHarness localMode(String tag) throws Exception {
        return create(tag, AuthServerSettings.defaults());
    }

    public static J205TestHarness create(String tag, AuthServerSettings settings) throws Exception {
        String database = TestPg.createDatabase("j205" + tag, TestPg.COMMENT);
        DataSource ds = TestPg.dataSource(database);
        new MigrationController().run(ds, TestPg.identity(database), true);
        return new J205TestHarness(ds, database, settings);
    }

    public void drop() throws Exception {
        TestPg.dropDatabase(database);
    }

    /** 每用例新建 Controller（auth 限流/锁定进程内状态随之隔离）。 */
    public MockMvc newMvc() {
        return standaloneSetup(
                new AuthController(settings, auth, resolver, clock),
                new SessionController(settings, resolver, sessions, sources, maintenance),
                new SourceController(resolver, sources),
                new TemplateController(resolver),
                new UsageQueryController(settings, resolver, clock, counters, ledger, users,
                        classScope),
                new GradingQueryController(settings, resolver, grading, classScope))
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    /* ------------------------------------------------------------------ */
    /* 种子                                                                */
    /* ------------------------------------------------------------------ */

    public void seedUser(String id, String rolesJson, Integer dailyQuota, String createdBy)
            throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users(id, password_hash, roles, daily_quota, enabled, "
                             + "must_change_password, created_at, created_by) "
                             + "VALUES (?,?,?,?,true,true,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, BCrypt.hashpw(id, BCrypt.gensalt(4)));
            ps.setString(3, rolesJson);
            if (dailyQuota == null) {
                ps.setObject(4, null);
            } else {
                ps.setInt(4, dailyQuota);
            }
            ps.setObject(5, OffsetDateTime.ofInstant(T0, ZoneOffset.UTC));
            ps.setString(6, createdBy);
            ps.executeUpdate();
        }
    }

    public void insertSession(String id, String userId, String title, Instant createdAt,
                              Instant updatedAt) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO assistant_sessions(id, user_id, title, created_at, updated_at) "
                             + "VALUES (?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, userId);
            ps.setString(3, title);
            ps.setObject(4, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
            ps.setObject(5, OffsetDateTime.ofInstant(updatedAt, ZoneOffset.UTC));
            ps.executeUpdate();
        }
    }

    public void insertMessage(String id, String sessionId, String role, String content,
                              String metadataJson, Instant createdAt) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO assistant_messages(id, session_id, role, content, metadata_json, "
                             + "created_at) VALUES (?,?,?,?,?::jsonb,?)")) {
            ps.setString(1, id);
            ps.setString(2, sessionId);
            ps.setString(3, role);
            ps.setString(4, content);
            ps.setString(5, metadataJson == null ? "{}" : metadataJson);
            ps.setObject(6, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
            ps.executeUpdate();
        }
    }

    public void insertCitation(String id, String messageId, String chunkId, String engine,
                               double score, int rank) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO assistant_citations(id, message_id, chunk_id, citation_engine, "
                             + "score, rank) VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, messageId);
            ps.setString(3, chunkId);
            ps.setString(4, engine);
            ps.setDouble(5, score);
            ps.setInt(6, rank);
            ps.executeUpdate();
        }
    }

    /** 历史 legacy 引用来源（knowledge_documents + knowledge_chunks）。 */
    public void insertKnowledgeChunk(String docId, String title, String chunkId, String sourcePath,
                                     String section, String content, String metadataJson)
            throws Exception {
        try (Connection conn = ds.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO knowledge_documents(id, source_path, source_type, title, "
                            + "content_hash, git_commit, indexed_at) VALUES (?,?,?,?,?,?,?)")) {
                ps.setString(1, docId);
                ps.setString(2, sourcePath);
                ps.setString(3, "doc");
                ps.setString(4, title);
                ps.setString(5, "hash-" + docId);
                ps.setString(6, "");
                ps.setObject(7, OffsetDateTime.ofInstant(T0, ZoneOffset.UTC));
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO knowledge_chunks(id, document_id, source_path, section, content, "
                            + "metadata_json) VALUES (?,?,?,?,?,?::jsonb)")) {
                ps.setString(1, chunkId);
                ps.setString(2, docId);
                ps.setString(3, sourcePath);
                ps.setString(4, section);
                ps.setString(5, content);
                ps.setString(6, metadataJson == null ? "{}" : metadataJson);
                ps.executeUpdate();
            }
        }
    }

    /** li_ 版本化节点（data_li_knowledge_v1 物理行）。 */
    public void insertLiNode(String nodeId, String text, String sourcePath, String title,
                             String section) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO data_li_knowledge_v1(text, metadata_, node_id) "
                             + "VALUES (?,?::jsonb,?)")) {
            ps.setString(1, text);
            ps.setString(2, "{\"source_path\":\"" + sourcePath + "\",\"title\":\"" + title
                    + "\",\"section\":\"" + section + "\"}");
            ps.setString(3, nodeId);
            ps.executeUpdate();
        }
    }

    public void insertGradingSubmission(String id, String userId, String sessionId, String protocol,
                                        int score, int passed, int total, Instant createdAt)
            throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO grading_submissions(id, user_id, session_id, protocol, code_hash, "
                             + "score, passed, total, results_json, created_at) "
                             + "VALUES (?,?,?,?,?,?,?,?,'[]'::jsonb,?)")) {
            ps.setString(1, id);
            ps.setString(2, userId);
            ps.setString(3, sessionId);
            ps.setString(4, protocol);
            ps.setString(5, "hash-" + id);
            ps.setInt(6, score);
            ps.setInt(7, passed);
            ps.setInt(8, total);
            ps.setObject(9, OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
            ps.executeUpdate();
        }
    }

    public void insertUsageCounter(String subject, String day, int count, String kind)
            throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO usage_counters(subject, day, kind, chat_count) VALUES (?,?,?,?)")) {
            ps.setString(1, subject);
            ps.setString(2, day);
            ps.setString(3, kind);
            ps.setInt(4, count);
            ps.executeUpdate();
        }
    }

    public void insertLedgerRow(io.dpdk.assistant.usage.UsageLedgerRow row) throws Exception {
        ledger.recordRows(java.util.List.of(row));
    }

    /** 清空活动发布（rag_index_state.release_id=''：无发布等待首次绑定）。 */
    public void clearActiveRelease() throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE rag_index_state SET release_id='' WHERE name='active'")) {
            ps.executeUpdate();
        }
    }

    /** 发布活动版本（rag_releases + rag_index_state）。 */
    public void publishRelease(String releaseId) throws Exception {
        try (Connection conn = ds.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO rag_releases(id, configuration, created_at, node_count) "
                            + "VALUES (?,?,?,0) ON CONFLICT (id) DO NOTHING")) {
                ps.setString(1, releaseId);
                ps.setString(2, "{}");
                ps.setString(3, "2026-09-22T00:00:00+00:00");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE rag_index_state SET release_id=? WHERE name='active'")) {
                ps.setString(1, releaseId);
                ps.executeUpdate();
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 查询与登录                                                          */
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
        for (int i = 0; i < args.length; i++) {
            ps.setString(i + 1, args[i]);
        }
    }

    /** 经 /api/auth/login 换 token（限流随 mvc 实例隔离）。 */
    public String login(MockMvc mvc, String username) throws Exception {
        MvcResult result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/login")
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(Map.of(
                                "username", username, "password", username))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).path("token").asText();
    }

    public static JsonNode loadGolden() throws Exception {
        try (var in = J205TestHarness.class.getResourceAsStream(
                "/migration/J2-05/golden-j205.json")) {
            return new ObjectMapper().readTree(in);
        }
    }
}
