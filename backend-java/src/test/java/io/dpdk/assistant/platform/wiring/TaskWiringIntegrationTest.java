package io.dpdk.assistant.platform.wiring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.dpdk.assistant.admin.LocalTaskCancellationPort;
import io.dpdk.assistant.admin.NoOpLocalTaskCancellation;
import io.dpdk.assistant.assignment.submission.SubmissionSettings;
import io.dpdk.assistant.auth.AuthSettingsProvider;
import io.dpdk.assistant.contracts.identity.AccountWriteGuard;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.contracts.session.MessageInput;
import io.dpdk.assistant.contracts.session.SessionPort;
import io.dpdk.assistant.contracts.task.AdmissionInput;
import io.dpdk.assistant.contracts.task.AnswerCompletionInput;
import io.dpdk.assistant.contracts.task.RequestIdentity;
import io.dpdk.assistant.contracts.task.TaskTemporaryInput;
import io.dpdk.assistant.contracts.usage.QuotaParticipant;
import io.dpdk.assistant.session.SessionPortAdapter;
import io.dpdk.assistant.session.SessionService;
import io.dpdk.assistant.task.AdmissionGate;
import io.dpdk.assistant.task.ManagedTask;
import io.dpdk.assistant.task.RagTaskService;
import io.dpdk.assistant.task.TaskExecutor;
import io.dpdk.assistant.task.TaskInputHash;
import io.dpdk.assistant.task.TaskLocalCancellation;
import io.dpdk.assistant.task.TaskTestHarness;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 真实PG/事务/权限/配额/任务，加载实际TaskWiring；执行体是唯一脚本化外部边界。 */
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class TaskWiringIntegrationTest {
    private static TaskTestHarness h;
    private static final String USER = "U20260000081";

    @BeforeAll
    static void setup() throws Exception {
        h = TaskTestHarness.create("springwiring"); // TestPg先预检维护实例，再CREATE/迁移。
        h.seedUser(USER, "[\"viewer\"]", 1000);
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (h != null) {
            h.taskRunner.shutdownWatchdog();
            h.drop();
        }
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(TaskWiring.class)
                .withBean(SubmissionSettings.class, () -> SubmissionSettings.DEFAULTS)
                .withBean(TransactionRunner.class, () -> h.runner)
                .withBean(ClockPort.class, () -> h.clock)
                .withBean(SessionService.class, () -> h.sessions)
                .withBean(AccountWriteGuard.class, () -> h.guard)
                .withBean(QuotaParticipant.class, () -> h.counters)
                .withBean(AuthSettingsProvider.class, () -> h.authSettings);
    }

    private static String session(String owner) throws Exception {
        String id = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        h.seedSession(id, owner);
        return id;
    }

    private static AdmissionInput input(String user, String session, String question) {
        return new AdmissionInput(new RequestIdentity(UUID.randomUUID().toString(),
                TaskInputHash.compute(question), false, RequestIdentity.Purpose.chat),
                user, session, question, "可信上下文", "synthetic-model", question, 1000,
                new TaskTemporaryInput("sample.c", ".c", "SYNTHETIC_TEMP_SOURCE_81", "upload"));
    }

    private static TaskExecutor.TaskExecutionContext execution(String task, AdmissionInput input) {
        return new TaskExecutor.TaskExecutionContext(task, input.identity().requestId(), input.sessionId(),
                input.subject(), List.of("viewer"), "chat", input.rawTemporaryInput(), input.context(),
                input.temporaryInput().attachmentName(), input.temporaryInput().attachmentSuffix(),
                input.temporaryInput().sourceText(), input.temporaryInput().sourceOrigin());
    }

    @Test
    void springUsesOneRealCancellationAndSessionAdapterParticipatesInCallerTransaction() throws Exception {
        String session = session(USER);
        context().run(ctx -> {
            assertThat(ctx).hasSingleBean(LocalTaskCancellationPort.class);
            assertThat(ctx.getBean(LocalTaskCancellationPort.class)).isInstanceOf(TaskLocalCancellation.class);
            assertThat(ctx).doesNotHaveBean(NoOpLocalTaskCancellation.class);
            SessionPort adapter = ctx.getBean(SessionPort.class);
            assertThat(adapter).isInstanceOf(SessionPortAdapter.class);
            assertThatThrownBy(() -> h.runner.required(tx -> {
                adapter.appendUser(tx, new MessageInput("应回滚", session, "user", Map.of("request_id", "rollback")));
                throw new IllegalStateException("synthetic rollback after append");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(h.countRows("assistant_messages", "session_id=?", session)).isZero();
            h.runner.required(tx -> {
                adapter.appendUser(tx, new MessageInput("成功提交", session, "user", Map.of("request_id", "committed")));
                return null;
            });
            assertThat(h.countRows("assistant_messages", "session_id=? AND role='user'", session)).isEqualTo(1);
            assertThat(h.columnValue("assistant_messages", "metadata_json", "session_id=?", session))
                    .contains("committed");
        });
    }

    @Test
    void attachmentAndRichSnapshotsSurviveAdmissionAndCompletionReturnsCommittedMessageId() throws Exception {
        String session = session(USER);
        context().run(ctx -> {
            RagTaskService tasks = ctx.getBean(RagTaskService.class);
            AdmissionInput input = input(USER, session, "请解释合成知识");
            assertThat(tasks.probe(input.identity(), USER, Optional.of(session)).state()).isEqualTo("miss");
            var admitted = tasks.admit(input);
            assertThat(admitted.outcome()).isEqualTo("created");
            var row = admitted.row().orElseThrow();
            assertThat(row.requestId()).isEqualTo(input.identity().requestId());
            assertThat(row.inputHash()).isEqualTo(input.identity().inputHash());
            assertThat(row.userId()).isEqualTo(USER);
            assertThat(row.sessionId()).isEqualTo(session);
            assertThat(row.contextText()).isEqualTo("可信上下文");
            assertThat(row.modelVersion()).isEqualTo("synthetic-model");
            assertThat(row.purpose()).isEqualTo("chat");
            var temporary = tasks.loadInputs(row.taskId()).orElseThrow();
            assertThat(temporary.attachmentName()).isEqualTo("sample.c");
            assertThat(temporary.attachmentSuffix()).isEqualTo(".c");
            assertThat(temporary.sourceText()).isEqualTo("SYNTHETIC_TEMP_SOURCE_81");
            assertThat(temporary.sourceOrigin()).isEqualTo("upload");
            var probe = tasks.probe(input.identity(), USER, Optional.of(session));
            assertThat(probe.state()).isEqualTo("in_progress");
            assertThat(probe.row()).contains(row);
            var attached = tasks.admit(input);
            assertThat(attached.outcome()).isEqualTo("attached");
            assertThat(attached.row()).contains(row);
            assertThat(h.countRows("assistant_messages", "session_id=? AND role='user'", session)).isEqualTo(1);
            var done = tasks.finish(new AnswerCompletionInput(row.taskId(), session, "合成答案",
                    List.of(), "answered", "supported", null, 11));
            assertThat(done.messageId()).isNotBlank();
            assertThat(h.columnValue("assistant_messages", "content", "id=?", done.messageId())).isEqualTo("合成答案");
            assertThat(tasks.loadInputs(row.taskId())).isEmpty();
            var replay = tasks.probe(input.identity(), USER, Optional.of(session));
            assertThat(replay.state()).isEqualTo("replay");
            assertThat(replay.row().orElseThrow().messageId()).isEqualTo(done.messageId());
            assertThat(replay.message().orElseThrow().content()).isEqualTo("合成答案");
            var readmitted = tasks.admit(input);
            assertThat(readmitted.outcome()).isEqualTo("replayed");
            assertThat(readmitted.row()).isEqualTo(replay.row());
            assertThat(readmitted.message()).isEqualTo(replay.message());
            assertThat(h.countRows("assistant_messages", "session_id=?", session)).isEqualTo(2);
        });
    }

    @Test
    void disablingAccountThenCallingSpringCancellationInterruptsTaskAndClearsInputsAndSlot() throws Exception {
        String disabled = "U20260000082";
        h.seedUser(disabled, "[\"viewer\"]", 1000);
        String session = session(disabled);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        context().run(ctx -> {
            RagTaskService tasks = ctx.getBean(RagTaskService.class);
            var input = input(disabled, session, "正在执行的合成任务");
            String id = tasks.admit(input).row().orElseThrow().taskId();
            ManagedTask managed = tasks.startTask(disabled, input.identity().requestId(), session,
                    execution(id, input), (c, emit) -> {
                        entered.countDown();
                        try { release.await(5, TimeUnit.SECONDS); }
                        catch (InterruptedException e) { interrupted.set(true); throw e; }
                        return new TaskExecutor.TaskExecutionResult("迟到答案", "answered", "supported", List.of(), null);
                    });
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(ctx.getBean(AdmissionGate.class).active()).isEqualTo(1);
                assertThat(h.adminUsers().setEnabled(disabled, false)).isTrue();
                ctx.getBean(LocalTaskCancellationPort.class).cancelLocalForUser(disabled);
                assertThat(managed.awaitDone(Duration.ofSeconds(2))).isTrue();
                assertThat(interrupted.get()).isTrue();
                assertThat(ctx.getBean(AdmissionGate.class).active()).isZero();
                assertThat(tasks.loadInputs(id)).isEmpty();
                assertThat(tasks.getOwned(id, disabled).orElseThrow().status()).isEqualTo("cancelled");
                assertThat(tasks.getOwned(id, disabled).orElseThrow().reason()).isEqualTo("account_disabled");
                assertThat(h.countRows("assistant_messages", "session_id=? AND content='迟到答案'", session)).isZero();
            } finally { release.countDown(); }
        });
    }

    @Test
    void readyEventStartsHeartbeatWithExecutorAndContextCloseStopsIt() throws Exception {
        String session = session(USER);
        AtomicReference<TaskWiring.TaskBackground> background = new AtomicReference<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TaskExecutor executor = (c, emit) -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new TaskExecutor.TaskExecutionResult("答案", "answered", "supported", List.of(), null);
        };
        context().withBean(TaskExecutor.class, () -> executor).run(ctx -> {
            var bg = ctx.getBean(TaskWiring.TaskBackground.class);
            background.set(bg);
            assertThat(bg.running()).isFalse();
            var tasks = ctx.getBean(RagTaskService.class);
            var input = input(USER, session, "心跳任务");
            String id = tasks.admit(input).row().orElseThrow().taskId();
            var managed = tasks.startTask(USER, input.identity().requestId(), session, execution(id, input), executor);
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                h.makeOrphan(id, Duration.ofSeconds(60));
                String oldTimestamp = h.columnValue("rag_requests", "updated_at", "id=?", id);
                ctx.publishEvent(new ApplicationReadyEvent(new SpringApplication(TaskWiring.class),
                        new String[0], ctx.getSourceApplicationContext(), Duration.ZERO));
                assertThat(bg.running()).isTrue();
                await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(10)).untilAsserted(() ->
                        assertThat(h.columnValue("rag_requests", "updated_at", "id=?", id)).isNotEqualTo(oldTimestamp));
                tasks.cancel(id, USER);
                assertThat(managed.awaitDone(Duration.ofSeconds(2))).isTrue();
            } finally { release.countDown(); }
        });
        await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(10))
                .until(() -> !background.get().running());
    }

    @Test
    void readyEventWithoutExecutorDoesNotStartBackground() {
        context().run(ctx -> {
            var bg = ctx.getBean(TaskWiring.TaskBackground.class);
            ctx.publishEvent(new ApplicationReadyEvent(new SpringApplication(TaskWiring.class),
                    new String[0], ctx.getSourceApplicationContext(), Duration.ZERO));
            assertThat(bg.running()).isFalse();
        });
    }
}
