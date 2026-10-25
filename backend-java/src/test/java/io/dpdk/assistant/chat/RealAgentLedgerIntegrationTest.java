package io.dpdk.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dpdk.assistant.agent.AgentChatModel;
import io.dpdk.assistant.agent.AgentMessage;
import io.dpdk.assistant.agent.AgentSettings;
import io.dpdk.assistant.agent.ToolCallSpec;
import io.dpdk.assistant.agent.tools.AgentToolContext;
import io.dpdk.assistant.agent.tools.AgentTools;
import io.dpdk.assistant.agent.tools.HarnessCaller;
import io.dpdk.assistant.agent.tools.RetryableTaskFailure;
import io.dpdk.assistant.contracts.model.HarnessPort;
import io.dpdk.assistant.contracts.model.HarnessReply;
import io.dpdk.assistant.contracts.task.AdmissionInput;
import io.dpdk.assistant.contracts.task.RequestIdentity;
import io.dpdk.assistant.contracts.task.TaskTemporaryInput;
import io.dpdk.assistant.task.ManagedTask;
import io.dpdk.assistant.task.TaskExecutor;
import io.dpdk.assistant.task.TaskGradeLedger;
import io.dpdk.assistant.task.TaskInputHash;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 原AgentScenario/TaskLedger的关键恢复分支改以真实任务/账本/配额/成绩仓储验证。 */
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class RealAgentLedgerIntegrationTest {
    private static final String SOURCE = "/* SYNTHETIC_LEDGER_SOURCE_81 */\nint plugin_main(void){return 0;}\n";
    private static final String HASH = AgentTools.sha256Hex(SOURCE.getBytes(StandardCharsets.UTF_8));
    private static final AgentChatModel MODEL = (messages, names) -> {
        if (names.contains("grade_plugin") && messages.stream().noneMatch(m -> m.isTool() && "grade_plugin".equals(m.toolName())))
            return new AgentChatModel.AgentReply("", List.of(new ToolCallSpec("grade-fixed", "grade_plugin",
                    Map.of("protocol", "dns", "plugin_source", "模型复述不作为执行源"))), null);
        return AgentChatModel.AgentReply.text("评测全部通过。");
    };

    private record Admitted(String taskId, String sessionId, String requestId, String question) {
        String jobId() { return taskId + ":dns:" + HASH.substring(0, 16); }
    }

    private static Admitted admit(ChatTestHarness h) throws Exception {
        String session = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        h.task.seedSession(session, "student");
        String request = UUID.randomUUID().toString();
        String question = "请评测合成插件";
        var result = h.task.service.admit(new AdmissionInput(new RequestIdentity(request,
                TaskInputHash.compute(question), false, RequestIdentity.Purpose.chat), "student", session,
                question, "", "synthetic-model", question, 1000,
                new TaskTemporaryInput(null, null, SOURCE, "model_args")));
        return new Admitted(result.row().orElseThrow().taskId(), session, request, question);
    }

    private static AgentToolContext context(ChatTestHarness h, Admitted admitted, HarnessPort harness) {
        var ports = new ChatAgentPorts(h.task.store, h.task.runner, h.task.clock, h.task.counters);
        var grades = new TaskGradeLedger(h.task.store, h.task.guard, h.task.runner, h.task.clock);
        return AgentToolContext.builder().settings(AgentSettings.defaults()).userId("student")
                .userRoles(List.of("viewer")).sessionId(admitted.sessionId()).taskId(admitted.taskId())
                .taskLedger(ports).taskGrade(grades::recordTaskGrade).gradingQuota(ports)
                .gradingRecord(ports).gradingStats(ports)
                .fallbackSource(h.task.service.loadInputs(admitted.taskId()).orElseThrow().sourceText())
                .harness(new HarnessCaller(harness, AgentSettings.defaults(), null)).build();
    }

    private static Map<String, Object> grade(AgentToolContext context) throws Exception {
        return AgentTools.buildTools(context).stream().filter(t -> "grade_plugin".equals(t.name()))
                .findFirst().orElseThrow().invoke(Map.of("protocol", "dns", "plugin_source", "模型错误复述"));
    }

    private static HarnessReply reply(Map<String, Object> result) {
        return new HarnessReply(HarnessReply.SubmissionPhase.SUBMITTED, ChatAgentPorts.json(result), null);
    }

    private static Map<String, Object> result() {
        return Map.of("ok", true, "protocol", "dns", "score", 100, "passed", 1,
                "cases_total", 1, "results", List.of(Map.of("name", "synthetic-case", "result", "PASS")));
    }

    private static void reserve(ChatTestHarness h, Admitted admitted) {
        new ChatAgentPorts(h.task.store, h.task.runner, h.task.clock, h.task.counters)
                .reserveGradingJob(admitted.taskId(), admitted.jobId(), "dns", HASH, "student", 20);
    }

    private static int gradingCount(ChatTestHarness h) throws Exception {
        String value = h.task.columnValue("usage_counters", "chat_count", "subject='student' AND kind='grading'");
        return value == null ? 0 : Integer.parseInt(value);
    }

    private static ManagedTask runRealExecutor(ChatTestHarness h, Admitted a) {
        var source = h.task.service.loadInputs(a.taskId()).orElseThrow();
        return h.task.service.startTask("student", a.requestId(), a.sessionId(),
                new TaskExecutor.TaskExecutionContext(a.taskId(), a.requestId(), a.sessionId(), "student",
                        List.of("viewer"), "chat", a.question(), "", null, null,
                        source.sourceText(), source.sourceOrigin()), h.executor);
    }

    private static void assertUnknownTerminal(ChatTestHarness h, Admitted admitted) throws Exception {
        var row = h.task.service.getOwned(admitted.taskId(), "student").orElseThrow();
        assertThat(row.status()).isEqualTo("system_error");
        assertThat(row.reason()).isEqualTo("harness_result_unknown");
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        assertThat(json.readTree(h.task.columnValue("assistant_messages", "metadata_json", "id=?", row.messageId()))
                .path("failure_code").asText()).isEqualTo("harness_result_unknown");
        List<io.dpdk.assistant.contracts.chat.SseEvent> frames = new CopyOnWriteArrayList<>();
        CountDownLatch streamDone = new CountDownLatch(1);
        h.task.service.taskEvents(admitted.taskId(), "student").subscribe(new java.util.concurrent.Flow.Subscriber<>() {
            public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(io.dpdk.assistant.contracts.chat.SseEvent event) { frames.add(event); }
            public void onError(Throwable error) { streamDone.countDown(); }
            public void onComplete() { streamDone.countDown(); }
        });
        assertThat(streamDone.await(2, TimeUnit.SECONDS)).isTrue();
        var terminal = frames.stream().filter(e -> "done".equals(e.name())).findFirst().orElseThrow();
        assertThat(json.readTree(terminal.dataJson()).path("failure_code").asText()).isEqualTo("harness_result_unknown");
        assertThat(h.task.store.findJob(admitted.taskId(), "dns", HASH).orElseThrow().status()).isEqualTo("submitted");
        assertThat(h.task.service.loadInputs(admitted.taskId())).isEmpty();
        assertThat(h.task.countRows("grading_submissions", "task_job_id=?", admitted.jobId())).isZero();
        assertThat(gradingCount(h)).isEqualTo(1);
    }

    @Test
    void firstSubmissionUsesRealReservedIdentityAndFinishedRestartsReuseWithoutChargingOrSubmitting() throws Exception {
        List<String> operations = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> gradeArguments = new CopyOnWriteArrayList<>();
        HarnessPort harness = (tool, args, deadline) -> {
            operations.add(tool);
            if ("get_task_status".equals(tool)) return reply(Map.of("ok", true, "status", "unknown"));
            gradeArguments.add(args);
            return reply(result());
        };
        try (ChatTestHarness h = ChatTestHarness.create("ledgerfirst", MODEL, harness)) {
            var admitted = admit(h);
            assertThat(grade(context(h, admitted, harness)).get("ok")).isEqualTo(true);
            assertThat(gradeArguments).hasSize(1);
            assertThat(gradeArguments.getFirst().get("job_id")).isEqualTo(admitted.jobId());
            assertThat(gradeArguments.getFirst().get("plugin_source")).isEqualTo(SOURCE);
            assertThat(gradingCount(h)).isEqualTo(1);
            var saved = h.task.store.findJob(admitted.taskId(), "dns", HASH).orElseThrow();
            assertThat(saved.status()).isEqualTo("finished");
            assertThat(saved.submissionDone()).isTrue();
            assertThat(h.task.countRows("grading_submissions", "task_job_id=?", admitted.jobId())).isEqualTo(1);
            String savedResults = h.task.columnValue("grading_submissions", "results_json", "task_job_id=?", admitted.jobId());
            var actualResults = new com.fasterxml.jackson.databind.ObjectMapper().readTree(savedResults);
            assertThat(actualResults.path("source_origin").asText()).isEqualTo("model_args");
            assertThat(actualResults.path("results").isArray()).isTrue();
            assertThat(actualResults.path("results")).isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(result().get("results")));
            assertThat(savedResults).doesNotContain(SOURCE);
            int before = operations.size();
            for (int restart = 0; restart < 3; restart++) {
                var resumed = grade(context(h, admitted, harness));
                assertThat(resumed.get("ok")).isEqualTo(true);
                assertThat(((Map<?, ?>) resumed.get("data")).get("resumed")).isEqualTo(true);
            }
            assertThat(operations).hasSize(before);
            assertThat(gradingCount(h)).isEqualTo(1);
            assertThat(h.task.countRows("grading_submissions", "task_job_id=?", admitted.jobId())).isEqualTo(1);
        }
    }

    @Test
    void realAgentGradesPreserveEachCaseAndFailureNamesReachGradingOwnedProfile() throws Exception {
        List<Map<String, Object>> cases =
                List.of(
                        Map.of(
                                "name",
                                "dns-pass",
                                "result",
                                "PASS",
                                "expected",
                                "ok",
                                "actual",
                                "ok"),
                        Map.of(
                                "name",
                                "dns-failed-case",
                                "result",
                                "FAIL",
                                "expected",
                                "A",
                                "actual",
                                "B"));
        Map<String, Object> payload =
                Map.of(
                        "ok",
                        true,
                        "protocol",
                        "dns",
                        "score",
                        50,
                        "passed",
                        1,
                        "cases_total",
                        2,
                        "results",
                        cases);
        HarnessPort remote =
                (tool, args, deadline) ->
                        "grade_plugin".equals(tool)
                                ? reply(payload)
                                : reply(
                                        Map.of(
                                                "ok",
                                                true,
                                                "data",
                                                Map.of("reference_source", "synthetic reference")));
        try (ChatTestHarness h = ChatTestHarness.create("ledgerstructure", MODEL, remote)) {
            var admitted = admit(h);
            var managed = runRealExecutor(h, admitted);
            assertThat(managed.awaitDone(Duration.ofSeconds(5))).isTrue();
            assertThat(h.task.countRows("grading_submissions", "task_job_id=?", admitted.jobId()))
                    .isEqualTo(1);
            String saved =
                    h.task.columnValue(
                            "grading_submissions",
                            "results_json",
                            "task_job_id=?",
                            admitted.jobId());
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var document = json.readTree(saved);
            assertThat(document.path("source_origin").asText()).isEqualTo("model_args");
            assertThat(document.path("results").isArray()).isTrue();
            assertThat(document.path("results")).isEqualTo(json.valueToTree(cases));
            assertThat(document.path("results").size()).isEqualTo(2);
            assertThat(saved).doesNotContain(SOURCE);
            var ports =
                    new ChatAgentPorts(h.task.store, h.task.runner, h.task.clock, h.task.counters);
            var profile =
                    new io.dpdk.assistant.grading.query.AgentGradingStatsService(h.task.runner);
            assertThat(profile.recentFailCount("student", "dns", 10)).isEqualTo(1);
            assertThat(ports.recentFailCount("student", "dns", 10)).isEqualTo(1);
            assertThat(profile.learnerProfile("student"))
                    .contains("尝试1次", "最高50分", "常挂用例：dns-failed-case");
            assertThat(ports.learnerProfile("student"))
                    .isEqualTo(profile.learnerProfile("student"));
            assertThat(gradingCount(h)).isEqualTo(1);
        }
    }

    @Test
    void nonTaskGradeWriteIsExplicitlyRejectedWithoutBypassingAccountAndTaskLocks()
            throws Exception {
        try (ChatTestHarness h = ChatTestHarness.create("ledgerrequiretask", MODEL, null)) {
            var ports =
                    new ChatAgentPorts(h.task.store, h.task.runner, h.task.clock, h.task.counters);
            assertThatThrownBy(
                            () ->
                                    ports.recordSubmission(
                                            "student",
                                            null,
                                            "dns",
                                            HASH,
                                            50,
                                            0,
                                            1,
                                            List.of(Map.of("name", "case", "result", "FAIL")),
                                            "pasted"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("taskGrade");
            assertThat(h.task.countRows("grading_submissions", null)).isZero();
        }
    }

    @Test
    void concurrentFinishedRecoveryWritesOneGradeWithRealAccountAndTaskLocks() throws Exception {
        AtomicInteger remoteCalls = new AtomicInteger();
        HarnessPort remote = (tool, args, deadline) -> { remoteCalls.incrementAndGet(); return reply(result()); };
        try (ChatTestHarness h = ChatTestHarness.create("ledgerconcurrent", MODEL, remote)) {
            var admitted = admit(h);
            reserve(h, admitted);
            h.task.store.finishJob(admitted.jobId(), ChatAgentPorts.json(result()));
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<java.util.concurrent.Future<Map<String, Object>>> calls = new ArrayList<>();
                for (int i = 0; i < 8; i++) calls.add(pool.submit(() -> grade(context(h, admitted, remote))));
                for (var call : calls) assertThat(call.get(3, TimeUnit.SECONDS).get("ok")).isEqualTo(true);
            }
            assertThat(remoteCalls.get()).isZero();
            assertThat(gradingCount(h)).isEqualTo(1);
            assertThat(h.task.countRows("grading_submissions", "task_job_id=?", admitted.jobId())).isEqualTo(1);
            assertThat(h.task.store.findJob(admitted.taskId(), "dns", HASH).orElseThrow().submissionDone()).isTrue();
        }
    }

    @Test
    void runningRemoteJobIsOnlyQueriedUntilFinishedAndNeverResubmitted() throws Exception {
        AtomicInteger statusQueries = new AtomicInteger();
        AtomicInteger gradeSubmits = new AtomicInteger();
        HarnessPort remote = (tool, args, deadline) -> {
            if ("get_task_status".equals(tool)) {
                int n = statusQueries.incrementAndGet();
                return reply(n == 1 ? Map.of("ok", true, "status", "running")
                        : Map.of("ok", true, "status", "finished", "result", result()));
            }
            gradeSubmits.incrementAndGet();
            return reply(result());
        };
        try (ChatTestHarness h = ChatTestHarness.create("ledgerrunning", MODEL, remote)) {
            var admitted = admit(h);
            reserve(h, admitted);
            var managed = runRealExecutor(h, admitted);
            assertThat(managed.awaitDone(Duration.ofSeconds(5))).isTrue();
            assertThat(gradeSubmits.get()).isZero();
            assertThat(statusQueries.get()).isGreaterThanOrEqualTo(2);
            assertThat(gradingCount(h)).isEqualTo(1);
            assertThat(h.task.countRows("grading_submissions", "task_job_id=?", admitted.jobId())).isEqualTo(1);
            assertThat(h.task.service.getOwned(admitted.taskId(), "student").orElseThrow().status()).isNotEqualTo("pending");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"unknown", "query_failed", "error_payload"})
    void unknownSubmittedJobTerminatesSafelyRetainingLedgerAndClearingSource(String mode) throws Exception {
        AtomicInteger gradeSubmits = new AtomicInteger();
        HarnessPort remote = (tool, args, deadline) -> {
            if ("get_task_status".equals(tool)) {
                if ("query_failed".equals(mode)) throw new IllegalStateException("synthetic status outage");
                if ("error_payload".equals(mode)) return reply(Map.of("ok", false, "error", "status_unavailable"));
                return reply(Map.of("ok", true, "status", "unknown"));
            }
            gradeSubmits.incrementAndGet();
            return reply(result());
        };
        try (ChatTestHarness h = ChatTestHarness.create("ledgerunknown", MODEL, remote)) {
            var admitted = admit(h);
            reserve(h, admitted);
            var managed = runRealExecutor(h, admitted);
            assertThat(managed.awaitDone(Duration.ofSeconds(5))).isTrue();
            assertThat(gradeSubmits.get()).isZero();
            assertUnknownTerminal(h, admitted);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUBMITTED", "UNKNOWN"})
    void firstSubmissionResponseLostTerminatesSafelyWithoutReposting(String phase) throws Exception {
        AtomicInteger gradeSubmits = new AtomicInteger();
        HarnessPort remote = (tool, args, deadline) -> {
            if ("get_task_status".equals(tool)) return reply(Map.of("ok", true, "status", "unknown"));
            gradeSubmits.incrementAndGet();
            return new HarnessReply(HarnessReply.SubmissionPhase.valueOf(phase),
                    ChatAgentPorts.json(Map.of("ok", false, "error", "harness_unavailable",
                            "detail", "synthetic lost response")), "harness_unavailable");
        };
        try (ChatTestHarness h = ChatTestHarness.create("ledgerlostresponse", MODEL, remote)) {
            var admitted = admit(h);
            var managed = runRealExecutor(h, admitted);
            assertThat(managed.awaitDone(Duration.ofSeconds(5))).isTrue();
            assertThat(gradeSubmits.get()).isEqualTo(1);
            assertUnknownTerminal(h, admitted);
        }
    }

    @Test
    void cancellationWhileMcpIsInFlightRejectsLateGradeAndDoesNotRestoreTemporarySource() throws Exception {
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HarnessPort remote = (tool, args, deadline) -> {
            if ("get_task_status".equals(tool)) return reply(Map.of("ok", true, "status", "unknown"));
            submitted.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return reply(result());
        };
        try (ChatTestHarness h = ChatTestHarness.create("ledgercancel", MODEL, remote);
             var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var admitted = admit(h);
            var call = pool.submit(() -> grade(context(h, admitted, remote)));
            try {
                assertThat(submitted.await(2, TimeUnit.SECONDS)).isTrue();
                h.task.service.cancel(admitted.taskId(), "student");
                release.countDown();
                var response = call.get(3, TimeUnit.SECONDS);
                assertThat(response.get("ok")).isEqualTo(false);
                assertThat(response.get("error")).isEqualTo("task_cancelled");
                assertThat(h.task.countRows("grading_submissions", "task_job_id=?", admitted.jobId())).isZero();
                assertThat(h.task.service.getOwned(admitted.taskId(), "student").orElseThrow().status()).isEqualTo("cancelled");
                assertThat(h.task.service.loadInputs(admitted.taskId())).isEmpty();
                assertThat(gradingCount(h)).isEqualTo(1);
            } finally { release.countDown(); }
        }
    }

    @Test
    void reservationCounterFaultRollsBackJobAndQuotaBeforeAnyGradeSubmission() throws Exception {
        AtomicInteger gradeSubmits = new AtomicInteger();
        HarnessPort remote = (tool, args, deadline) -> {
            if ("get_task_status".equals(tool)) return reply(Map.of("ok", true, "status", "unknown"));
            gradeSubmits.incrementAndGet();
            return reply(result());
        };
        try (ChatTestHarness h = ChatTestHarness.create("ledgerreservefault", MODEL, remote)) {
            var admitted = admit(h);
            try (var c = h.task.ds.getConnection(); var statement = c.createStatement()) {
                statement.execute("ALTER TABLE usage_counters ADD CONSTRAINT synthetic_grading_fault CHECK(kind <> 'grading')");
            }
            assertThatThrownBy(() -> grade(context(h, admitted, remote))).isInstanceOf(RetryableTaskFailure.class);
            assertThat(gradeSubmits.get()).isZero();
            assertThat(h.task.countRows("rag_task_jobs", "task_id=?", admitted.taskId())).isZero();
            assertThat(gradingCount(h)).isZero();
            assertThat(h.task.countRows("grading_submissions", "session_id=?", admitted.sessionId())).isZero();
            assertThat(h.task.service.loadInputs(admitted.taskId())).isPresent();
            assertThat(h.task.service.getOwned(admitted.taskId(), "student").orElseThrow().status()).isEqualTo("pending");
        }
    }
}
