package io.dpdk.assistant.task;

import io.dpdk.assistant.admin.AdminUserRepository;
import io.dpdk.assistant.auth.AuthAccountWriteGuard;
import io.dpdk.assistant.auth.AuthServerSettings;
import io.dpdk.assistant.auth.AuthSettingsProvider;
import io.dpdk.assistant.auth.TestClock;
import io.dpdk.assistant.auth.UserAuthRepository;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.contracts.session.SessionPort;
import io.dpdk.assistant.platform.migration.MigrationController;
import io.dpdk.assistant.platform.pg.TestPg;
import io.dpdk.assistant.platform.tx.SpringTransactionRunner;
import io.dpdk.assistant.session.SessionService;
import io.dpdk.assistant.usage.UsageCounterStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * J3-01 测试基座：独立 PG 库 + B0015 迁移 + 真实仓储/端口装配 + 可控时钟。
 *
 * 替身边界纪律：身份/权限/任务/事务/配额/持久化全真实实现；唯一可替换的是
 * TaskExecutor（模型/Agent 外部边界，J3-02 前为脚本化替身）。
 */
public final class TaskTestHarness {

    public static final Instant T0 = Instant.parse("2026-10-25T04:00:00Z");

    public final DataSource ds;
    public final String database;
    public final TestClock clock = new TestClock();
    public final TransactionRunner runner;
    public final AuthSettingsProvider authSettings;
    public final AuthAccountWriteGuard guard;
    public final SessionService sessions;
    public final SessionPort sessionPort;
    public final UsageCounterStore counters;
    public final TaskStore store;
    public final AdmissionGate gate;
    public final ManagedTaskRegistry registry;
    public final TaskRunner taskRunner;
    public final TaskRecovery recovery;
    public final TaskEventStreams streams;
    public final RagTaskService service;
    public final TaskSettings settings;
    /** 恢复/起步用执行体（默认拒绝：各测试显式注入脚本化替身）。 */
    public volatile TaskExecutor recoveryExecutor;

    private final java.util.concurrent.atomic.AtomicReference<RagTaskService> serviceRef =
            new java.util.concurrent.atomic.AtomicReference<>();

    private TaskTestHarness(DataSource ds, String database, AuthServerSettings initial,
                            TaskSettings settings) {
        this.ds = ds;
        this.database = database;
        this.settings = settings;
        this.clock.setWall(T0);
        this.authSettings = AuthSettingsProvider.fixed(initial);
        this.runner = new SpringTransactionRunner(
                new TransactionTemplate(new DataSourceTransactionManager(ds)), ds);
        this.guard = new AuthAccountWriteGuard(authSettings);
        this.sessions = new SessionService(runner, clock);
        this.sessionPort = new TaskSessionPortBridge(sessions);
        this.counters = new UsageCounterStore(runner, clock);
        this.store = new TaskStore(runner, clock, counters, settings.inputTtl());
        this.gate = new AdmissionGate(settings.maxActiveRequests(), settings.maxWaitingRequests(),
                settings.admissionQueueTimeout());
        this.registry = new ManagedTaskRegistry(clock, settings.finishedTtl());
        // finisher 回填到后构造的 RagTaskService（AnswerCompletion 语义同一事务面）。
        TaskRunner.TaskFinisher finisher = (taskId, sessionId, answer, citations, status, reason,
                                            releaseId, elapsedMs) ->
                serviceRef.get().finishDetail(taskId, sessionId, answer, citations, status,
                        reason, releaseId, elapsedMs).messageId();
        this.taskRunner = TaskRunner.withDefaults(store, finisher, gate, registry, clock, settings);
        this.recovery = new TaskRecovery(store, taskRunner, gate, clock, settings, runner,
                () -> !initial.accountMode());
        this.recovery.setExecutor((context, emit) -> {
            throw new IllegalStateException("测试未注入执行体");
        });
        this.streams = new TaskEventStreams(store, taskRunner, recovery, clock);
        this.service = new RagTaskService(store, taskRunner, recovery, streams, guard, sessionPort,
                counters, clock, runner, settings);
        serviceRef.set(service);
    }

    public static TaskTestHarness create(String tag) throws Exception {
        return create(tag, TaskSettings.defaults());
    }

    public static TaskTestHarness create(String tag, TaskSettings settings) throws Exception {
        AuthServerSettings initial = AuthServerSettings.of("account", "{}", 7, 8, 4, 10000);
        String database = TestPg.createDatabase("j301" + tag, TestPg.COMMENT);
        DataSource ds = TestPg.dataSource(database);
        new MigrationController().run(ds, TestPg.identity(database), true);
        return new TaskTestHarness(ds, database, initial, settings);
    }

    public void setRecoveryExecutor(TaskExecutor executor) {
        this.recoveryExecutor = executor;
        this.recovery.setExecutor(executor);
    }

    public void drop() throws Exception {
        TestPg.dropDatabase(database);
    }

    /* ------------------------------------------------------------------ */
    /* 种子 / 检查                                                          */
    /* ------------------------------------------------------------------ */

    public void seedUser(String id, String rolesJson, Integer dailyQuota) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users(id, password_hash, roles, daily_quota, enabled, "
                             + "must_change_password, created_at, created_by) "
                             + "VALUES (?,?,?,?,true,true,?,NULL)")) {
            ps.setString(1, id);
            ps.setString(2, BCrypt.hashpw(id, BCrypt.gensalt(4)));
            ps.setString(3, rolesJson);
            if (dailyQuota == null) {
                ps.setObject(4, null);
            } else {
                ps.setInt(4, dailyQuota);
            }
            ps.setObject(5, OffsetDateTime.ofInstant(T0, ZoneOffset.UTC));
            ps.executeUpdate();
        }
    }

    public void seedSession(String id, String userId) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO assistant_sessions(id, user_id, title, created_at, updated_at) "
                             + "VALUES (?,?,?,?,?)")) {
            ps.setString(1, id);
            ps.setString(2, userId);
            ps.setString(3, "t");
            ps.setObject(4, OffsetDateTime.ofInstant(T0, ZoneOffset.UTC));
            ps.setObject(5, OffsetDateTime.ofInstant(T0, ZoneOffset.UTC));
            ps.executeUpdate();
        }
    }

    public int countRows(String table, String where, Object... args) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) AS c FROM " + table
                             + (where == null || where.isEmpty() ? "" : " WHERE " + where))) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            var rs = ps.executeQuery();
            rs.next();
            return rs.getInt("c");
        }
    }

    public String columnValue(String table, String column, String where, Object... args)
            throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT " + column + " AS v FROM " + table + " WHERE " + where)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            var rs = ps.executeQuery();
            return rs.next() ? rs.getString("v") : null;
        }
    }

    /** 把 updated_at 拨到 grace 窗口之前（孤儿/接续判定都认这一列）。 */
    public void makeOrphan(String taskId, java.time.Duration age) throws Exception {
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE rag_requests SET updated_at=? WHERE id=?")) {
            ps.setString(1, io.dpdk.assistant.platform.time.SystemClockAdapter.pythonIso(
                    clock.nowUtc().minus(age)));
            ps.setString(2, taskId);
            ps.executeUpdate();
        }
    }

    /** AdminUserRepository（停号事务路径，IP-1 组合验证用）。 */
    public AdminUserRepository adminUsers() {
        UserAuthRepository auth = new UserAuthRepository(runner, clock, authSettings);
        return new AdminUserRepository(runner, clock, authSettings, auth, auth);
    }
}
