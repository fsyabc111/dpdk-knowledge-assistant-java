package io.dpdk.assistant.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.platform.pg.TestPg;
import io.dpdk.assistant.platform.time.SystemClockAdapter;
import io.dpdk.assistant.task.TaskInputHash;
import io.dpdk.assistant.task.TaskTestHarness;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 非默认测试命名：仅显式-Dtest执行，验收真实已package的Boot jar和新JVM。 */
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class OperationsBootJarAcceptance {
    private static final ObjectMapper JSON = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final AtomicInteger RUNS = new AtomicInteger();
    private static final String EXISTING = "U00000000901", CREATED = "U00000000902";
    private static final String SOURCE_SENTINEL = "SYNTHETIC_BOOT_CLI_INPUT_SENTINEL";
    private static final String RESULT_SENTINEL = "SYNTHETIC_BOOT_CLI_RAW_RESULT_MUST_NOT_EMIT";

    private static Path jar() throws Exception {
        String value = System.getProperty("dpdk.acceptance.jar");
        assertThat(value).as("必须显式提供本轮已构建jar绝对路径").isNotBlank();
        Path jar = Path.of(value);
        assertThat(jar.isAbsolute()).isTrue();
        assertThat(Files.isRegularFile(jar)).isTrue();
        return jar;
    }
    private static Path logRoot() throws Exception {
        Path root = Path.of(System.getProperty("dpdk.acceptance.logs", ".migration-artifacts/boot-cli")).toAbsolutePath();
        Files.createDirectories(root); return root;
    }
    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void assertJarMatchesCurrentOperationsClasses() throws Exception {
        try (JarFile file = new JarFile(jar().toFile())) {
            for (String name : List.of("io/dpdk/assistant/operations/OperationsMain.class",
                    "io/dpdk/assistant/operations/OperationsCli.class",
                    "io/dpdk/assistant/operations/adapter/OperationsBusinessAdapter.class",
                    "io/dpdk/assistant/task/TaskStore.class", "io/dpdk/assistant/platform/wiring/TaskWiring.class")) {
                var entry = file.getJarEntry("BOOT-INF/classes/"+name);
                assertThat(entry).as("jar必须包含本轮完整实现").isNotNull();
                try (var binary = file.getInputStream(entry);
                     var current = OperationsBootJarAcceptance.class.getClassLoader().getResourceAsStream(name)) {
                    assertThat(current).isNotNull();
                    assertThat(digest(binary.readAllBytes())).as(name).isEqualTo(digest(current.readAllBytes()));
                }
            }
        }
        Files.writeString(logRoot().resolve("jar-sha256.txt"), digest(Files.readAllBytes(jar()))+"\n");
    }

    private record Execution(int exit, String stdout, String stderr) {
        JsonNode result(String command, boolean apply) throws Exception {
            assertThat(exit).isZero();
            JsonNode json = JSON.readTree(stdout);
            assertThat(json.isObject()).isTrue();
            assertThat(json.path("command").asText()).isEqualTo(command);
            assertThat(json.path("apply").asBoolean()).isEqualTo(apply);
            assertThat(json.path("result").isObject()).isTrue();
            assertThat(stdout).doesNotContain(SOURCE_SENTINEL, RESULT_SENTINEL);
            return json.path("result");
        }
    }

    private static Execution run(TaskTestHarness h, Map<String,String> overrides, String... args) throws Exception {
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        List<String> command = new ArrayList<>(List.of(java.toString(),
                "-Dloader.main=io.dpdk.assistant.operations.OperationsMain", "-cp", jar().toString(),
                "org.springframework.boot.loader.launch.PropertiesLauncher"));
        command.addAll(List.of(args));
        int serial = RUNS.incrementAndGet();
        Path out = logRoot().resolve(String.format("%02d-stdout.log", serial));
        Path err = logRoot().resolve(String.format("%02d-stderr.log", serial));
        ProcessBuilder builder = new ProcessBuilder(command).redirectOutput(out.toFile()).redirectError(err.toFile());
        // 不继承任何模型/embedding/harness端点、JAVA_TOOL_OPTIONS或旧Spring配置。
        builder.environment().clear();
        builder.environment().put("PATH", java.getParent()+":/usr/bin:/bin");
        builder.environment().put("LANG", "en_US.UTF-8");
        builder.environment().put("AUTH_MODE", "account");
        if (h != null) {
            Map<String,String> env = builder.environment();
            env.put("DPDK_JAVA_DB_URL", TestPg.url().substring(0,TestPg.url().lastIndexOf('/')+1)+h.database);
            env.put("DPDK_JAVA_DB_USER", TestPg.user()); env.put("DPDK_JAVA_DB_PASSWORD", TestPg.password());
            env.put("DPDK_JAVA_DB_NAME", h.database); env.put("DPDK_JAVA_DB_COMMENT", TestPg.COMMENT);
            env.put("DPDK_JAVA_DB_DATA_DIR", System.getenv("DPDK_TEST_PG_DATA_DIR"));
            // 显式尝试环境放开开关，OperationsMain更高优先级安全设置必须保持禁用。
            env.put("DPDK_TASK_BACKGROUND_ENABLED", "true"); env.put("TASK_BACKGROUND_ENABLED", "true");
            env.put("SPRING_FLYWAY_ENABLED", "true"); env.put("SPRING_SQL_INIT_MODE", "always");
            env.put("DPDK_MIGRATION_RUN_ON_STARTUP", "true");
        }
        builder.environment().putAll(overrides);
        Process child = builder.start();
        boolean finished = child.waitFor(30, TimeUnit.SECONDS);
        if (!finished) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS); }
        assertThat(finished).as("Boot CLI子进程必须按时退出").isTrue();
        Files.writeString(logRoot().resolve("commands.jsonl"), JSON.writeValueAsString(Map.of(
                "serial",serial,"args",List.of(args),"exit",child.exitValue(),
                "stdout_sha256",digest(Files.readAllBytes(out)),"stderr_sha256",digest(Files.readAllBytes(err))))+"\n",
                java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND);
        return new Execution(child.exitValue(), Files.readString(out), Files.readString(err));
    }

    private static void sql(TaskTestHarness h, String text, Object... args) throws Exception {
        try (var c=h.ds.getConnection(); var ps=c.prepareStatement(text)) {
            for(int i=0;i<args.length;i++) ps.setObject(i+1,args[i]); ps.executeUpdate();
        }
    }
    private static Map<String,String> snapshot(TaskTestHarness h) throws Exception {
        Map<String,String> result = new TreeMap<>();
        try(var c=h.ds.getConnection(); var s=c.createStatement()) {
            List<String> tables = new ArrayList<>();
            try(var r=s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename")) {
                while(r.next()) tables.add(r.getString(1));
            }
            for(String table:tables) {
                String quoted='"'+table.replace("\"","\"\"")+'"';
                try(var r=s.executeQuery("SELECT coalesce(jsonb_agg(x ORDER BY x::text),'[]'::jsonb)::text FROM (SELECT to_jsonb(t) AS x FROM "+quoted+" t) q")) {
                    r.next(); result.put(table,digest(r.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                }
            }
            try(var r=s.executeQuery("SELECT coalesce(jsonb_agg(x ORDER BY x::text),'[]'::jsonb)::text FROM ("
                    +"SELECT table_name||':'||column_name||':'||data_type||':'||coalesce(column_default,'') AS x FROM information_schema.columns WHERE table_schema='public' "
                    +"UNION ALL SELECT indexdef AS x FROM pg_indexes WHERE schemaname='public' "
                    +"UNION ALL SELECT conrelid::regclass::text||':'||pg_get_constraintdef(oid) AS x FROM pg_constraint WHERE connamespace='public'::regnamespace) q")) {
                r.next(); result.put("__schema__",digest(r.getString(1).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
        return result;
    }
    private static String iso(Instant value) { return SystemClockAdapter.pythonIso(value); }
    private static void request(TaskTestHarness h, String id, String status, Instant created, Instant updated) throws Exception {
        sql(h,"INSERT INTO rag_requests(id,question,status,created_at,updated_at) VALUES (?,'synthetic CLI question',?,?,?)",
                id,status,iso(created),iso(updated));
    }
    private static void temporary(TaskTestHarness h, String task, Instant expiry) throws Exception {
        sql(h,"INSERT INTO rag_task_inputs(task_id,question,source_text,created_at,expires_at) VALUES (?,'synthetic temporary question',?,?,?)",
                task,SOURCE_SENTINEL,iso(Instant.now()),iso(expiry));
    }

    @Test
    void helpAndMalformedArgumentsUseActualJarWithoutOpeningDatabase() throws Exception {
        assertJarMatchesCurrentOperationsClasses();
        var help = run(null,Map.of(),"--help");
        assertThat(help.exit()).isZero();
        assertThat(help.stdout()).startsWith("Java 独立运维 CLI").contains("retain-issues [--retention-days 180]","--apply");
        assertThat(help.stderr()).isEmpty();
        for(String[] args : List.of(new String[]{"not-a-command"}, new String[]{"create-users","--id","bad"},
                new String[]{"retain-issues","--retention-days","0"})) {
            var invalid = run(null,Map.of(),args);
            assertThat(invalid.exit()).isEqualTo(2);
            assertThat(invalid.stdout()).isEmpty();
            assertThat(invalid.stderr()).isNotBlank().doesNotContain("password",SOURCE_SENTINEL);
        }
    }

    @Test
    void realJarCommandsHonorDryRunBackgroundSafetyIdempotencyRetentionAndTargetIdentity() throws Exception {
        assertJarMatchesCurrentOperationsClasses();
        TaskTestHarness h = TaskTestHarness.create("bootcli");
        try {
            Instant now = Instant.now();
            h.seedUser(EXISTING,"[\"viewer\"]",7); h.seedSession("cli-recovery-session",EXISTING);
            request(h,"cli-recoverable","pending",now.minusSeconds(3600),now.minusSeconds(3600));
            sql(h,"UPDATE rag_requests SET user_id=?,session_id='cli-recovery-session',request_id='cli-recovery-request',input_hash=?,purpose='chat' WHERE id='cli-recoverable'",
                    EXISTING,TaskInputHash.compute("synthetic temporary question"));
            temporary(h,"cli-recoverable",now.plus(Duration.ofDays(1)));
            String pendingUpdated=h.columnValue("rag_requests","updated_at","id='cli-recoverable'");
            var before=snapshot(h);
            var preview=run(h,Map.of(),"create-users","--id",CREATED,"--daily-quota","7").result("create-users",false);
            assertThat(preview.path("dry_run").asBoolean()).isTrue();
            assertThat(preview.path("would_create").toString()).isEqualTo("[\""+CREATED+"\"]");
            assertThat(snapshot(h)).isEqualTo(before);
            assertThat(h.columnValue("rag_requests","updated_at","id='cli-recoverable'")).isEqualTo(pendingUpdated);
            var applied=run(h,Map.of(),"create-users","--id",CREATED,"--daily-quota","7","--apply").result("create-users",true);
            assertThat(applied.path("created").toString()).isEqualTo("[\""+CREATED+"\"]");
            assertThat(h.countRows("users","id=?",CREATED)).isEqualTo(1);
            assertThat(h.countRows("users",null)).isEqualTo(2);
            assertThat(h.columnValue("users","daily_quota","id=?",CREATED)).isEqualTo("7");
            var after=snapshot(h); before.remove("users"); var afterOther=new TreeMap<>(after); afterOther.remove("users");
            assertThat(afterOther).isEqualTo(before);
            var repeated=run(h,Map.of(),"create-users","--id",CREATED,"--daily-quota","7","--apply").result("create-users",true);
            assertThat(repeated.path("created").isEmpty()).isTrue();
            assertThat(repeated.path("skipped").toString()).isEqualTo("[\""+CREATED+"\"]");
            assertThat(snapshot(h)).isEqualTo(after);

            h.store.recordJob("cli-recoverable","cli-audit-job","grade_plugin","synthetic",null);
            h.store.finishJob("cli-audit-job","{\"raw\":\""+RESULT_SENTINEL+"\"}");
            before=snapshot(h);
            var audited=run(h,Map.of(),"audit-grading-jobs","--limit","20").result("audit-grading-jobs",false);
            assertThat(audited.path("read_only").asBoolean()).isTrue();
            assertThat(audited.path("re_evaluated").asBoolean()).isFalse();
            assertThat(audited.path("jobs")).hasSize(1);
            assertThat(audited.path("jobs").get(0).path("job_id").asText()).isEqualTo("cli-audit-job");
            assertThat(audited.path("jobs").get(0).path("has_result").asBoolean()).isTrue();
            assertThat(snapshot(h)).isEqualTo(before);

            request(h,"cli-terminal","answered",now,now); temporary(h,"cli-terminal",now.plusSeconds(3600));
            request(h,"cli-expired-input","pending",now.minus(Duration.ofDays(1)),now.minus(Duration.ofDays(1)));
            temporary(h,"cli-expired-input",now.minusSeconds(3600));
            before=snapshot(h);
            assertThat(run(h,Map.of(),"purge-task-inputs").result("purge-task-inputs",false).path("inputs").asInt()).isEqualTo(2);
            assertThat(snapshot(h)).isEqualTo(before);
            assertThat(run(h,Map.of(),"purge-task-inputs","--apply").result("purge-task-inputs",true).path("inputs").asInt()).isEqualTo(2);
            assertThat(h.countRows("rag_task_inputs",null)).isEqualTo(1);
            assertThat(h.countRows("rag_task_inputs","task_id='cli-recoverable'")).isEqualTo(1);

            h.seedSession("cli-old-session",EXISTING);
            sql(h,"UPDATE assistant_sessions SET updated_at=? WHERE id='cli-old-session'",OffsetDateTime.ofInstant(now.minus(Duration.ofDays(31)),ZoneOffset.UTC));
            before=snapshot(h);
            assertThat(run(h,Map.of(),"purge-sessions").result("purge-sessions",false).path("sessions").asInt()).isEqualTo(1);
            assertThat(snapshot(h)).isEqualTo(before);
            assertThat(run(h,Map.of(),"purge-sessions","--apply").result("purge-sessions",true).path("sessions").asInt()).isEqualTo(1);
            assertThat(h.countRows("assistant_sessions","id='cli-old-session'")).isZero();

            request(h,"cli-old-181","answered",now.minus(Duration.ofDays(181)),now);
            request(h,"cli-middle-60","answered",now.minus(Duration.ofDays(60)),now);
            sql(h,"INSERT INTO rag_issues(id,request_id,updated_at) VALUES ('cli-old-181','cli-old-181',?)",iso(now));
            sql(h,"INSERT INTO rag_issue_events(id,issue_id,action,detail,created_at) VALUES ('cli-old-event','cli-old-181','feedback','synthetic',?)",iso(now));
            before=snapshot(h);
            var retainedPreview=run(h,Map.of(),"retain-issues").result("retain-issues",false);
            assertThat(retainedPreview.path("retention_days").asInt()).isEqualTo(180);
            assertThat(retainedPreview.path("expired").asInt()).isEqualTo(1);
            assertThat(snapshot(h)).isEqualTo(before);
            var retained=run(h,Map.of(),"retain-issues","--apply").result("retain-issues",true);
            assertThat(retained.path("retention_days").asInt()).isEqualTo(180);
            assertThat(retained.path("expired").asInt()).isEqualTo(1);
            assertThat(retained.path("workflow").path("events").asInt()).isEqualTo(1);
            assertThat(h.countRows("rag_requests","id='cli-old-181'")).isZero();
            assertThat(h.countRows("rag_issues","id='cli-old-181'")).isZero();
            assertThat(h.countRows("rag_issue_events","id='cli-old-event'")).isZero();
            assertThat(h.countRows("rag_requests","id='cli-middle-60'")).isEqualTo(1);
            assertThat(h.columnValue("rag_requests","status","id='cli-recoverable'")).isEqualTo("pending");
            assertThat(h.columnValue("rag_requests","updated_at","id='cli-recoverable'")).isEqualTo(pendingUpdated);
            assertThat(h.countRows("rag_task_inputs","task_id='cli-recoverable'")).isEqualTo(1);

            before=snapshot(h);
            var rejected=run(h,Map.of("DPDK_JAVA_DB_NAME","synthetic-wrong-target"),
                    "create-users","--id","U00000000903","--apply");
            assertThat(rejected.exit()).isEqualTo(1);
            assertThat(rejected.stdout()).isEmpty();
            assertThat(rejected.stderr()).contains("操作失败").doesNotContain(TestPg.password(),SOURCE_SENTINEL);
            assertThat(snapshot(h)).isEqualTo(before);
            assertThat(h.countRows("users","id='U00000000903'")).isZero();
        } finally { h.taskRunner.shutdownWatchdog(); h.drop(); }
    }
}
