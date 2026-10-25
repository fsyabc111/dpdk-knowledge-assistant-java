package io.dpdk.assistant.assignment.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.dpdk.assistant.agent.AgentChatModel.AgentReply;
import io.dpdk.assistant.contracts.chat.SseEvent;
import io.dpdk.assistant.contracts.task.*;
import io.dpdk.assistant.task.TaskInputHash;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 真第二 JVM 恢复，以及讲解任务沿用普通任务的续传/取消/停号契约。 */
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class ExplainRecoveryIntegrationTest {

    @Test
    void separateJvmRebuildsTrustedContextAndCommitsOnceWithoutRegrading() throws Exception {
        try (var h = new ExplainFlowHarness("recovery", null, null)) {
            var assignment = h.assignment();
            String grade = h.grade(assignment, false), snapshot = h.gradeSnapshot(grade);
            var prepared = h.data.prepare(assignment.id(), grade, h.chat.student);
            h.data.ensureSession(prepared, "student");
            var admitted = h.chat.task.service.admitDetail(new AdmissionInput(
                    new RequestIdentity(prepared.identity().requestId(), TaskInputHash.compute(prepared.question()),
                            false, RequestIdentity.Purpose.explain), "student", prepared.identity().sessionId(),
                    prepared.question(), "客户端伪造context不能覆盖可信成绩", "fixed-model", prepared.question(), 10000));
            String task = admitted.taskId();
            assertThat(h.chat.task.registry.get(task)).isNull();
            h.catalog.updateAssignment(assignment.id(), "teacher", List.of("leader"), "恢复后可信标题", "",
                    assignment.deadline(), assignment.items());
            Path logs = Path.of(".migration-artifacts/j3-04");
            Files.createDirectories(logs);
            Path log = logs.resolve("recovery-child-" + task + ".log");
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("java.class.path"), ExplainRecoveryProcess.class.getName(),
                    h.chat.task.database, task).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            if (!process.waitFor(25, TimeUnit.SECONDS)) {process.destroyForcibly();throw new AssertionError("child recovery timed out");}
            String output = Files.readString(log, StandardCharsets.UTF_8);
            assertThat(process.exitValue()).isZero();
            assertThat(output).contains("RECOVERED_CONTEXT=true", "MODEL_CALLS=2", "GRADE_CALLS=0",
                    "REFERENCE_CALLS=1", "STATUS=answered");
            var row = h.chat.task.store.findOwned(task, "student").orElseThrow();
            assertThat(row.status()).isEqualTo("answered");
            assertThat(h.chat.task.service.loadInputs(task)).isEmpty();
            assertThat(h.chat.task.countRows("assistant_messages", "session_id=? AND role='assistant'", prepared.identity().sessionId())).isEqualTo(1);
            assertThat(h.chat.task.columnValue("usage_counters", "chat_count", "subject=? AND kind='chat'", "student")).isEqualTo("1");
            assertThat(h.chat.task.countRows("grading_submissions", "")).isEqualTo(1);
            assertThat(h.gradeSnapshot(grade)).isEqualTo(snapshot);
            var replay = h.explain(assignment.id(), grade, h.token);
            assertThat(h.body(replay).path("status").asText()).isEqualTo("replayed");
            assertThat(h.body(replay).path("task_id").asText()).isEqualTo(task);
            var events = collect(h.chat.task.service.taskEvents(task, "student"));
            assertThat(events.stream().map(SseEvent::name)).containsSubsequence("start", "sources", "delta", "done");
            assertThat(events.stream().filter(e -> "delta".equals(e.name())).findFirst().orElseThrow().dataJson())
                    .contains("独立进程已从可信成绩恢复讲解");
            assertThat(h.chat.modelCalls).hasValue(0);
        }
    }

    @Test
    void disconnectedExplainKeepsRunningAndCancelRejectsLateAnswer() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var h = new ExplainFlowHarness("cancel", (messages, tools) -> {
            entered.countDown();
            try {release.await(10, TimeUnit.SECONDS);} catch (InterruptedException e) {Thread.currentThread().interrupt();}
            return AgentReply.text("迟到的历史成绩讲解不能覆盖取消。");
        }, null)) {
            var assignment = h.assignment();
            String grade = h.grade(assignment, true), snapshot = h.gradeSnapshot(grade);
            var response = h.explain(assignment.id(), grade, h.token);
            String task = h.body(response).path("task_id").asText();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            CountDownLatch subscribed = new CountDownLatch(1);
            h.chat.task.service.taskEvents(task, "student").subscribe(new Flow.Subscriber<>() {
                public void onSubscribe(Flow.Subscription subscription) {subscription.request(1);subscription.cancel();subscribed.countDown();}
                public void onNext(SseEvent event) {}
                public void onError(Throwable error) {}
                public void onComplete() {}
            });
            assertThat(subscribed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(h.chat.task.service.isRunning(task)).isTrue();
            assertThat(h.chat.task.store.findOwned(task, "student").orElseThrow().status()).isEqualTo("pending");
            var managed = h.chat.task.registry.get(task);
            var cancelled = h.mvc.perform(post("/api/tasks/{id}/cancel", task).header("Authorization", "Bearer " + h.token)).andReturn();
            assertThat(cancelled.getResponse().getStatus()).isEqualTo(200);
            release.countDown();
            assertThat(managed.awaitDone(Duration.ofSeconds(10))).isTrue();
            assertThat(h.chat.task.store.findOwned(task, "student").orElseThrow().status()).isEqualTo("cancelled");
            assertThat(h.chat.task.service.loadInputs(task)).isEmpty();
            assertThat(h.gradeSnapshot(grade)).isEqualTo(snapshot);
            assertThat(h.chat.harnessCalls).hasValue(0);
            assertThat(collect(h.chat.task.service.taskEvents(task, "student")).stream()
                    .filter(e -> "done".equals(e.name())).findFirst().orElseThrow().dataJson()).contains("cancelled");
        } finally {release.countDown();}
    }

    @Test
    void disabledAccountCannotReplayExplainOrReadTask() throws Exception {
        try (var h = new ExplainFlowHarness("disable", (messages, tools) -> AgentReply.text("历史成绩全部通过。"), null)) {
            var assignment = h.assignment();
            String grade = h.grade(assignment, true);
            String task = h.body(h.explain(assignment.id(), grade, h.token)).path("task_id").asText();
            assertThat(h.chat.task.registry.get(task).awaitDone(Duration.ofSeconds(10))).isTrue();
            assertThat(h.resolver.resolve(new io.dpdk.assistant.contracts.identity.RequestHeaders(
                    Map.of("Authorization", "Bearer " + h.token)), new io.dpdk.assistant.contracts.identity.AuthSettings()).subject()).isEqualTo("student");
            var users = new io.dpdk.assistant.admin.AdminUserRepository(h.chat.task.runner, h.chat.task.clock,
                    h.chat.task.authSettings, h.auth, h.auth);
            users.setEnabled("student", false);
            assertThat(h.explain(assignment.id(), grade, h.token).getResponse().getStatus()).isEqualTo(401);
            var query = h.mvc.perform(get("/api/tasks/{id}", task).header("Authorization", "Bearer " + h.token)).andReturn();
            assertThat(query.getResponse().getStatus()).isEqualTo(401);
            assertThat(h.chat.harnessCalls).hasValue(0);
        }
    }

    @Test
    void ordinaryChatWithForgedExplainIdentityCannotSeedTrustedGrade() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean seeded = new java.util.concurrent.atomic.AtomicBoolean();
        try (var h = new ExplainFlowHarness("forgery", (messages, tools) -> {
            seeded.set(messages.getFirst().content().contains("作业评测讲解模式"));
            return AgentReply.text("普通聊天无证据。");
        }, null)) {
            var assignment = h.assignment();
            String grade = h.grade(assignment, true);
            var prepared = h.data.prepare(assignment.id(), grade, h.chat.student);
            h.data.ensureSession(prepared, "student");
            var started = h.chat.product.start(h.chat.student, new io.dpdk.assistant.chat.ChatInput(
                    prepared.identity().sessionId(), prepared.identity().requestId(), prepared.question(),
                    Map.of("purpose", "explain", "score", 999), null));
            assertThat(started.managed().awaitDone(Duration.ofSeconds(10))).isTrue();
            assertThat(h.chat.task.store.findOwned(started.taskId(), "student").orElseThrow().purpose()).isEqualTo("chat");
            assertThat(seeded).isFalse();
            assertThat(h.chat.harnessCalls).hasValue(0);
            assertThat(h.chat.task.countRows("grading_submissions", "")).isEqualTo(1);
        }
    }

    private static List<SseEvent> collect(Flow.Publisher<SseEvent> publisher) throws Exception {
        List<SseEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription subscription) {subscription.request(Long.MAX_VALUE);}
            public void onNext(SseEvent event) {events.add(event);}
            public void onError(Throwable error) {errors.add(error);done.countDown();}
            public void onComplete() {done.countDown();}
        });
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();
        return events;
    }
}
