package io.dpdk.assistant.operations;

import static org.junit.jupiter.api.Assertions.*;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class OperationsCliTest {
    @Test void helpAndInvalidArgumentsNeverStartBusinessContext() {
        AtomicInteger calls = new AtomicInteger();
        for (String[] args : new String[][] { {}, {"--help"}, {"help"} })
            assertEquals(0, OperationsCli.run(args, () -> { calls.incrementAndGet(); return null; }, writer(), writer()));
        for (String[] args : new String[][] {{"unknown"}, {"rollback"}, {"create-users", "--id", "secret"},
                {"purge-sessions", "--retention-days", "0"}, {"retrieve", "--query"},
                {"audit-grading-jobs", "--apply"}, {"ingest", "--manifest", "relative.json"},
                {"judge", "--cases", "/tmp/a", "--apply"}, {"purge-task-inputs", "--apply", "--apply"}})
            assertEquals(2, OperationsCli.run(args, () -> { calls.incrementAndGet(); return null; }, writer(), writer()));
        assertEquals(0, calls.get());
    }
    @Test void allMutationsDefaultToDryRunAndRequireExplicitApply() {
        SpyPort port = new SpyPort();
        for (String[] args : new String[][] {{"create-users", "--id", "U00000000001"}, {"purge-sessions"},
                {"purge-task-inputs"}, {"retain-issues"}, {"ingest", "--manifest", "/tmp/manifest.json"},
                {"rollback", "--release", "release-id"}}) {
            OperationsCli.execute(OperationsCli.parse(args), port);
            assertFalse(port.apply);
            String[] applied = java.util.Arrays.copyOf(args, args.length + 1); applied[args.length] = "--apply";
            OperationsCli.execute(OperationsCli.parse(applied), port);
            assertTrue(port.apply);
        }
        assertEquals(12, port.calls);
    }
    @Test void createsUsersWithExactOptionsAndCopiesCallerLists() {
        SpyPort port = new SpyPort();
        OperationsCli.execute(OperationsCli.parse("create-users", "--id", "U00000000001", "--id", "U00000000002",
                "--roles", "teacher,viewer", "--daily-quota", "0"), port);
        assertEquals(2, port.users.ids().size()); assertEquals(0, port.users.dailyQuota());
        assertEquals(java.util.List.of("teacher", "viewer"), port.users.roles());
        assertThrows(UnsupportedOperationException.class, () -> port.users.ids().add("anything"));
    }
    @Test void parametersRejectDuplicatesRangesAndMissingValues() {
        for (String[] args : new String[][] {{"create-users", "--id", "U00000000001", "--roles", "admin"},
                {"create-users", "--id", "U00000000001", "--roles", "viewer,"},
                {"create-users", "--id", "U00000000001", "--daily-quota", "-1"},
                {"audit-grading-jobs", "--limit", "10001"}, {"retrieve", "--query", "q", "--top-k", "0"},
                {"purge-sessions", "--retention-days", "1", "--retention-days", "2"},
                {"purge-task-inputs", "extra"}})
            assertThrows(IllegalArgumentException.class, () -> OperationsCli.parse(args));
    }
    @Test void readOnlyCommandsAreDispatchedWithoutApply() {
        SpyPort port = new SpyPort();
        OperationsCli.execute(OperationsCli.parse("audit-grading-jobs", "--limit", "10"), port);
        OperationsCli.execute(OperationsCli.parse("retrieve", "--query", "synthetic", "--top-k", "3"), port);
        OperationsCli.execute(OperationsCli.parse("judge", "--cases", "/tmp/cases.json"), port);
        OperationsCli.execute(OperationsCli.parse("retrieval-eval", "--cases", "/tmp/retrieval.json"), port);
        assertEquals(4, port.calls);
    }
    @Test void outputIsJsonAndFailureDoesNotLeakSecrets() {
        StringWriter out = new StringWriter(), err = new StringWriter();
        assertEquals(0, OperationsCli.run(new String[]{"purge-task-inputs"}, SpyPort::new, new PrintWriter(out), new PrintWriter(err)));
        assertTrue(out.toString().contains("\"apply\":false"));
        assertEquals(1, OperationsCli.run(new String[]{"purge-task-inputs"}, () -> { throw new IllegalStateException("PRIVATE_SECRET"); }, new PrintWriter(out), new PrintWriter(err)));
        assertFalse(err.toString().contains("PRIVATE_SECRET"));
    }
    @Test void startupForcesNoWebNoDdlNoBackgroundRecovery() {
        Map<String, Object> safety = OperationsMain.startupSafety();
        assertEquals("none", safety.get("spring.main.web-application-type"));
        assertEquals(false, safety.get("dpdk.migration.run-on-startup"));
        assertEquals(false, safety.get("dpdk.task.background-enabled"));
        assertEquals(false, safety.get("spring.flyway.enabled"));
        assertEquals("never", safety.get("spring.sql.init.mode"));
    }
    private PrintWriter writer() { return new PrintWriter(new StringWriter()); }
    static class SpyPort implements OperationsPort {
        boolean apply; int calls; CreateUsers users;
        Map<String,Object> record(boolean flag) { apply = flag; calls++; return Map.of("count", 0); }
        public Map<String,Object> createUsers(CreateUsers request, boolean flag) { users = request; return record(flag); }
        public Map<String,Object> purgeSessions(int days, boolean flag) { return record(flag); }
        public Map<String,Object> purgeTaskInputs(boolean flag) { return record(flag); }
        public Map<String,Object> retainIssues(int days, boolean flag) { return record(flag); }
        public Map<String,Object> ingest(Path manifest, boolean flag) { return record(flag); }
        public Map<String,Object> rollback(String release, boolean flag) { return record(flag); }
        public Map<String,Object> auditGradingJobs(int limit) { return record(false); }
        public Map<String,Object> retrieve(String query, int topK) { return record(false); }
        public Map<String,Object> judge(Path cases) { return record(false); }
        public Map<String,Object> evaluateRetrieval(Path cases) { return record(false); }
    }
}
