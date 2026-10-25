package io.dpdk.assistant.acceptance.load;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.Application;
import io.dpdk.assistant.admin.AdminUserRepository;
import io.dpdk.assistant.agent.*;
import io.dpdk.assistant.assignment.catalog.*;
import io.dpdk.assistant.auth.UserAuthRepository;
import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import io.dpdk.assistant.contracts.model.*;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.contracts.identity.AccountWriteGuard;
import io.dpdk.assistant.retrieval.*;
import io.dpdk.assistant.task.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.bind.annotation.*;

/** J5 合成TCP服务：真实Application与业务；仅模型/向量/MCP为进程内外部边界。 */
public final class CapacityServer {
    public static final ObjectMapper JSON = new ObjectMapper();
    public static final List<String> STUDENTS = java.util.stream.IntStream.rangeClosed(1, 60)
            .mapToObj(i -> "U2099000%04d".formatted(i)).toList();
    public static final String LEADER = "U20990000999";
    public static final String EMBEDDING_BASE = "http://127.0.0.1:9/v1";
    public static final String CONTENT = "合成 DNS 标签按长度编码。";
    public static final RuntimeState STATE = new RuntimeState();
    public static volatile ConfigurableApplicationContext application;

    private CapacityServer() { }

    public static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("缺少显式配置:" + key);
        return value;
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(required("J5_MANIFEST"));
        if (Files.exists(output)) throw new IllegalStateException("拒绝覆盖运行manifest");
        String database = required("J5_DB_NAME");
        if (!database.matches("p5_(capacity|resilience)_[a-z0-9_]+") || !required("J5_DB_COMMENT").startsWith("p5-owned:"))
            throw new IllegalArgumentException("J5库名或comment不符合隔离边界");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", System.getenv().getOrDefault("J5_PORT", "0"));
        properties.put("server.forward-headers-strategy", "native");
        properties.put("server.tomcat.remoteip.internal-proxies", "127\\.0\\.0\\.1");
        properties.put("server.tomcat.remoteip.remote-ip-header", "x-forwarded-for");
        properties.put("AUTH_MODE", "account");
        properties.put("BCRYPT_ROUNDS", 12);
        properties.put("dpdk.datasource.url", required("J5_JDBC_URL"));
        properties.put("dpdk.datasource.username", required("J5_DB_USER"));
        properties.put("dpdk.datasource.password", System.getenv().getOrDefault("J5_DB_PASSWORD", ""));
        properties.put("dpdk.database-identity.expected-database", database);
        properties.put("dpdk.database-identity.expected-user", required("J5_DB_USER"));
        properties.put("dpdk.database-identity.expected-comment", required("J5_DB_COMMENT"));
        properties.put("dpdk.database-identity.expected-data-dir", required("J5_DB_DATA_DIR"));
        properties.put("dpdk.harness.url", "false");
        properties.put("dpdk.model.chat.model-name", "j5-fixed-model");
        properties.put("dpdk.grading.daily-quota", 20);
        properties.put("dpdk.rate-limit-chat-per-minute", 20);
        properties.put("logging.level.root", "ERROR");
        properties.put("logging.level.io.dpdk", "ERROR");
        application = new SpringApplicationBuilder(Application.class, Boundaries.class, Control.class).profiles("j5-capacity")
                .initializers(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new org.springframework.core.env.MapPropertySource("j5-explicit", properties))).run();
        DataSource ds = application.getBean(DataSource.class);
        // 仅测试装配对齐冻结32；不修改生产DataSourceConfig的默认值。
        if (ds instanceof com.zaxxer.hikari.HikariDataSource pool) pool.setMaximumPoolSize(32);
        int port = ((WebServerApplicationContext) application).getWebServer().getPort();
        Map<String, Object> manifest;
        if ("true".equals(System.getenv("J5_ATTACH"))) {
            manifest = new LinkedHashMap<>();
        } else {
            manifest = seed(application);
        }
        manifest.put("pid", ProcessHandle.current().pid());
        manifest.put("base_url", "http://127.0.0.1:" + port);
        manifest.put("comparison_processes", List.of(ProcessHandle.current().pid()));
        manifest.put("control_key", required("J5_CONTROL_KEY"));
        manifest.put("db_name", database);
        manifest.put("runtime", "java");
        manifest.put("settings", Map.of("pool", 32, "bcrypt", 12, "model_delay_seconds", STATE.modelDelay,
                "retrieval_delay_seconds", STATE.retrievalDelay, "mcp_delay_seconds", STATE.mcpDelay,
                "active", 20, "waiting", 20, "queue_seconds", 10, "task_seconds", 600, "input_ttl_hours", 24));
        manifest.put("java", Map.of("version", System.getProperty("java.version"), "vm", System.getProperty("java.vm.name"),
                "arguments", java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, JSON.writeValueAsString(manifest), StandardOpenOption.CREATE_NEW);
        try { Files.setPosixFilePermissions(output, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); }
        catch (UnsupportedOperationException ignored) { }
        System.out.println("J5_READY port=" + port + " pid=" + ProcessHandle.current().pid());
    }

    public static Map<String, Object> seed(ConfigurableApplicationContext ctx) throws Exception {
        AdminUserRepository users = ctx.getBean(AdminUserRepository.class);
        if (users.createUsers(List.of(LEADER), List.of("leader"), 100000, null).affected().size() != 1)
            throw new IllegalStateException("J5 seed必须为空库");
        if (users.createUsers(STUDENTS, List.of("viewer"), 100000, LEADER).affected().size() != 60)
            throw new IllegalStateException("J5合成账号数错误");
        var assignment = ctx.getBean(AssignmentCatalogService.class).createAssignment(LEADER, List.of("leader"),
                "P0合成性能作业", "运行时合成", Instant.now().plus(Duration.ofDays(1)),
                List.of(new AssignmentRow.ItemRow("dns", 100)));
        var auth = ctx.getBean(UserAuthRepository.class);
        List<String> tokens = STUDENTS.stream().map(s -> auth.issueToken(s).token()).toList();
        DataSource ds = ctx.getBean(DataSource.class);
        var config = new EmbeddingConfig("j5-fixed-embedding", EMBEDDING_BASE, 1024, "bounded-lines-v2");
        try (var conn = ds.getConnection()) {
            try (var ps = conn.prepareStatement("INSERT INTO data_li_knowledge_v1(node_id,text,metadata_,embedding) VALUES (?,?,?::jsonb,?::vector)")) {
                ps.setString(1, "j5-perf-kb-0001"); ps.setString(2, CONTENT);
                ps.setString(3, "{\"source_path\":\"synthetic/dns.md\",\"title\":\"合成DNS\",\"section\":\"标签\"}");
                ps.setString(4, "[1," + String.join(",", Collections.nCopies(1023, "0")) + "]"); ps.executeUpdate();
            }
            try (var ps = conn.prepareStatement("INSERT INTO rag_releases(id,configuration,created_at,node_count) VALUES (?,?,?,1)")) {
                ps.setString(1, "j5-fixed-release");
                ps.setString(2, new IndexConfigurationFingerprint().pythonCompatibleConfiguration(config));
                ps.setString(3, ctx.getBean(ClockPort.class).nextSessionIso()); ps.executeUpdate();
            }
            try (var statement = conn.createStatement()) {
                statement.executeUpdate("INSERT INTO rag_release_nodes(release_id,node_id) VALUES ('j5-fixed-release','j5-perf-kb-0001')");
                statement.executeUpdate("UPDATE rag_index_state SET release_id='j5-fixed-release' WHERE name='active'");
            }
        }
        return new LinkedHashMap<>(Map.of("students", STUDENTS, "tokens", tokens, "assignment_id", assignment.id()));
    }

    public static Map<String, Object> grade() {
        return Map.of("ok", true, "protocol", "dns", "score", 100, "passed", 2, "cases_total", 2,
                "failed", 0, "crashed", 0, "timedout", 0, "cheat", 0,
                "results", List.of(Map.of("name", "synthetic_1", "result", "PASS"), Map.of("name", "synthetic_2", "result", "PASS")));
    }

    public static final class RuntimeState {
        public final ConcurrentMap<String, AtomicLong> calls = new ConcurrentHashMap<>();
        public volatile boolean hold;
        public volatile String status = System.getenv().getOrDefault("J5_STATUS", "finished");
        public volatile boolean ignoreModelInterrupt;
        public final double modelDelay = Double.parseDouble(System.getenv().getOrDefault("J5_MODEL_DELAY", "1"));
        public final double retrievalDelay = Double.parseDouble(System.getenv().getOrDefault("J5_RETRIEVAL_DELAY", ".05"));
        public final double mcpDelay = Double.parseDouble(System.getenv().getOrDefault("J5_MCP_DELAY", ".2"));
        public void count(String key) { calls.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet(); }
        public Map<String, Long> counts() {
            Map<String, Long> out = new TreeMap<>(); calls.forEach((k, v) -> out.put(k, v.get())); return out;
        }
        public void pause(double seconds) {
            try { Thread.sleep(Math.max(0, (long) (seconds * 1000))); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException(); }
        }
        public void point(String stage, Map<String, Object> data) {
            if (!stage.equals(System.getenv("J5_FAULT"))) return;
            try {
                Path marker = Path.of(required("J5_MARKER"));
                Files.writeString(marker, JSON.writeValueAsString(Map.of("stage", stage, "data", data)), StandardOpenOption.CREATE_NEW);
                while (true) Thread.sleep(1000);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException(); }
            catch (Exception e) { throw new IllegalStateException("故障门栓失败", e); }
        }
        public synchronized void externalEvent(String tool, String job) {
            String path = System.getenv("J5_EXTERNAL_LEDGER");
            if (path == null) return;
            // 仅工具名/job身份，无源码/模型原文。fsync后才进入故障闩，跨SIGKILL留存。
            try (var channel = FileChannel.open(Path.of(path), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                channel.write(StandardCharsets.UTF_8.encode(JSON.writeValueAsString(Map.of("tool", tool, "job_id", job)) + "\n"));
                channel.force(true);
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Boundaries {
        @Bean @Primary TaskGradeLedger faultGradeLedger(TaskStore store, AccountWriteGuard guard,
                TransactionRunner tx, ClockPort clock) {
            return new TaskGradeLedger(store, guard, tx, clock) {
                @Override public io.dpdk.assistant.contracts.grading.TaskGradeResult recordTaskGrade(
                        io.dpdk.assistant.contracts.grading.TaskGradeInput input) {
                    STATE.point("grade_before", Map.of("task_id", input.taskId()));
                    var result = super.recordTaskGrade(input);
                    STATE.point("grade_after", Map.of("task_id", input.taskId()));
                    return result;
                }
            };
        }
        @Bean(destroyMethod = "shutdownWatchdog") @Primary
        TaskRunner faultTaskRunner(TaskStore store, AdmissionGate gate, ManagedTaskRegistry registry,
                ClockPort clock, TaskSettings settings) {
            return TaskRunner.withDefaults(store, (taskId, sessionId, answer, citations, status, reason, release, elapsed) -> {
                STATE.point("finish_before", Map.of("task_id", taskId));
                String message = store.finish(taskId, sessionId, answer, citations, status, reason, release, elapsed);
                STATE.point("finish_after", Map.of("task_id", taskId));
                return message;
            }, gate, registry, clock, settings);
        }
        @Bean @Primary AgentChatModel capacityModel() {
            return (messages, tools) -> {
                STATE.count("model");
                boolean grade = messages.stream().anyMatch(m -> m.isHuman() && m.content().contains("__grade__"));
                AgentMessage last = messages.getLast();
                String stage = last.isTool() ? "model_second_before" : "model_first_before";
                STATE.point(stage, Map.of());
                while (STATE.hold) {
                    try { STATE.pause(.01); }
                    catch (CancellationException e) {
                        if (!STATE.ignoreModelInterrupt) throw e;
                        Thread.interrupted();
                    }
                }
                STATE.pause(STATE.modelDelay);
                STATE.point(last.isTool() ? "model_second_after" : "model_first_after", Map.of());
                if (last.isTool()) return new AgentChatModel.AgentReply(
                        "grade_plugin".equals(last.toolName()) ? "合成评测已完成。" : "合成 DNS 标签按长度编码[1]。",
                        List.of(), new AgentUsage(32, 12, 44));
                String tool = grade ? "grade_plugin" : "kb_search";
                Map<String, Object> arguments = grade ? Map.of("protocol", "dns", "plugin_source", required("J5_SOURCE")) : Map.of("query", "合成DNS标签");
                return new AgentChatModel.AgentReply("", List.of(new ToolCallSpec("j5-call-1", tool, arguments)), new AgentUsage(32, 12, 44));
            };
        }
        @Bean @Primary PgReleaseReader capacityReader(DataSource ds) {
            return new PgReleaseReader(ds, RetrievalSettings.defaults(),
                    new EmbeddingConfig("j5-fixed-embedding", EMBEDDING_BASE, 1024, "bounded-lines-v2"),
                    new IndexConfigurationFingerprint(), new HeuristicQueryRewriter(), input -> {
                        STATE.count("embedding"); STATE.pause(STATE.retrievalDelay);
                        float[] values = new float[1024]; values[0] = 1;
                        return List.of(new Vector1024(values));
                    });
        }
        @Bean @Primary HarnessPort capacityHarness() {
            return (tool, arguments, deadline) -> {
                STATE.count("mcp:" + tool);
                String job = String.valueOf(arguments.getOrDefault("job_id", "formal-without-id"));
                if ("grade_plugin".equals(tool)) STATE.point("mcp_before", Map.of("job_id", job));
                STATE.externalEvent(tool, job);
                if ("grade_plugin".equals(tool)) STATE.point("mcp_submitted", Map.of("job_id", job));
                STATE.pause(STATE.mcpDelay);
                Map<String, Object> result;
                if ("get_task_status".equals(tool)) {
                    String mode = STATE.status;
                    if ("query_failed".equals(mode)) throw new IllegalStateException("合成状态查询失败");
                    result = "finished".equals(mode) ? Map.of("ok", true, "job_id", job, "status", mode, "result", grade())
                            : Map.of("ok", true, "job_id", job, "status", mode);
                } else if ("list_protocols".equals(tool)) {
                    result = Map.of("ok", true, "protocols", List.of(Map.of("name", "dns", "cases", 2, "categories", Map.of("basic", 2))));
                } else if ("get_reference_answer".equals(tool)) {
                    result = Map.of("ok", false, "error", "no_reference_in_synthetic");
                } else { result = grade(); }
                if ("grade_plugin".equals(tool)) STATE.point("mcp_after", Map.of("job_id", job));
                try { return new HarnessReply(HarnessReply.SubmissionPhase.SUBMITTED, JSON.writeValueAsString(result), null); }
                catch (Exception e) { throw new IllegalStateException(e); }
            };
        }
    }

    /** 只在显式测试服务装配；控制key不入日志/可提交证据。 */
    @TestConfiguration(proxyBeanMethods = false)
    public static class Control {
        @Bean ControlApi capacityControlApi() { return new ControlApi(); }
    }

    @RestController
    @org.springframework.context.annotation.Profile("j5-capacity")
    public static class ControlApi {
        @Autowired AdmissionGate gate;
        @Autowired TaskRecovery recovery;
        @Autowired TaskStore store;
        @Autowired RagTaskService tasks;
        @Autowired AdminUserRepository users;
        private void authorize(String key) {
            if (!java.security.MessageDigest.isEqual(required("J5_CONTROL_KEY").getBytes(StandardCharsets.UTF_8),
                    key.getBytes(StandardCharsets.UTF_8))) throw new SecurityException("测试控制未授权");
        }
        @GetMapping("/__j5/state") Map<String, Object> state(@RequestHeader("X-J5-Control") String key) {
            authorize(key); return Map.of("active", gate.active(), "waiting", gate.waiting(), "calls", STATE.counts());
        }
        @PostMapping("/__j5/control") Map<String, Object> control(@RequestHeader("X-J5-Control") String key,
                @RequestBody Map<String, Object> input) throws Exception {
            authorize(key);
            if (input.containsKey("hold")) STATE.hold = Boolean.TRUE.equals(input.get("hold"));
            if (input.containsKey("ignore_model_interrupt")) STATE.ignoreModelInterrupt = Boolean.TRUE.equals(input.get("ignore_model_interrupt"));
            if (input.containsKey("status")) STATE.status = String.valueOf(input.get("status"));
            if (input.containsKey("disable")) {
                String subject = String.valueOf(input.get("disable"));
                users.setEnabled(subject, false);
                tasks.cancelLocalForUser(subject);
            }
            if (input.containsKey("recover")) {
                String id = String.valueOf(input.get("recover"));
                var row = store.findOwned(id, String.valueOf(input.get("subject"))).orElseThrow();
                long start = System.nanoTime();
                var managed = recovery.ensureManaged(row);
                if (managed.isPresent() && !managed.get().awaitDone(Duration.ofSeconds(15))) throw new IllegalStateException("恢复未收口");
                return Map.of("execution_seconds", (System.nanoTime() - start) / 1e9,
                        "status", store.findOwned(id, row.userId()).orElseThrow().status());
            }
            return state(key);
        }
    }
}
