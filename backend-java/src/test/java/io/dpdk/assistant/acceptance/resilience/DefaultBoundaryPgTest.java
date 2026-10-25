package io.dpdk.assistant.acceptance.resilience;

import static org.assertj.core.api.Assertions.*;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.task.*;
import io.dpdk.assistant.platform.config.AppProperties;
import io.dpdk.assistant.platform.migration.MigrationController;
import io.dpdk.assistant.platform.pg.DatabaseIdentityGuard;
import io.dpdk.assistant.platform.pg.TestPg;
import io.dpdk.assistant.task.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 显式J5 owned库；不建/删库，所有恢复资产保留，由Python manifest owner管理。 */
@EnabledIfEnvironmentVariable(named = "J5_BOUNDARY_DB_NAME", matches = "p5_resilience_[a-z0-9_]+")
class DefaultBoundaryPgTest {
    private TaskTestHarness h;
    private String subject;
    @BeforeEach void attach() throws Exception {
        String database = System.getenv("J5_BOUNDARY_DB_NAME");
        String comment = System.getenv("J5_BOUNDARY_DB_COMMENT");
        if (comment == null || !comment.startsWith("p5-owned:")) throw new IllegalStateException("缺少本轮精确comment");
        var ds = TestPg.dataSource(database);
        var identity = new AppProperties.DatabaseIdentitySpec(database, TestPg.user(), comment, System.getenv("DPDK_TEST_PG_DATA_DIR"));
        try (var conn = ds.getConnection()) { DatabaseIdentityGuard.verify(conn, identity); }
        new MigrationController().run(ds, identity, true);
        h = TaskTestHarness.attach(database);
        subject = "U2088" + String.format("%07d", Math.abs(System.nanoTime()%10000000));
        h.seedUser(subject, "[\"viewer\"]", 100000);
    }
    @AfterEach void stopWatchdog() { if (h != null) h.taskRunner.shutdownWatchdog(); }

    private TaskAdmitOutcome admit(String source) throws Exception {
        String session = "sess_" + UUID.randomUUID().toString().replace("-", "");
        h.seedSession(session, subject);
        return h.service.admitDetail(new AdmissionInput(new RequestIdentity(UUID.randomUUID().toString(),
                TaskInputHash.compute("合成边界"), false, RequestIdentity.Purpose.chat), subject, session,
                "合成边界", "", "j5-fixed-model", "合成边界", 100000,
                new TaskTemporaryInput("plugin_dns.c", ".c", source, "upload")));
    }

    @Test void realTaskRunnerDefault600WithControlledSchedulerRejectsLateFinish() throws Exception {
        var admitted = admit("/* J5 default600 synthetic */ int plugin_main(void){return 0;}");
        var scheduler = new ManualScheduler();
        var entered = new CountDownLatch(1);
        var released = new CountDownLatch(1);
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        var runner = new TaskRunner(h.store, h.store::finish, h.gate, h.registry, h.clock,
                TaskSettings.defaults(), workers, scheduler);
        var row = h.store.findOwned(admitted.taskId(), subject).orElseThrow();
        var inputs = h.store.loadInputs(row.id()).orElseThrow();
        var context = new TaskExecutor.TaskExecutionContext(row.id(), row.requestId(), row.sessionId(), subject,
                List.of("viewer"), "chat", inputs.question(), "", inputs.attachmentName(), inputs.attachmentSuffix(), inputs.sourceText(), inputs.sourceOrigin());
        var managed = runner.startTask(subject, row.requestId(), row.sessionId(), context, (c, emit) -> {
            entered.countDown(); released.await();
            return new TaskExecutor.TaskExecutionResult("迟到答案", "answered", "supported", List.of(), "");
        });
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        h.clock.advanceMonoSeconds(599); scheduler.advance(Duration.ofSeconds(599));
        assertThat(h.store.findOwned(row.id(), subject).orElseThrow().status()).isEqualTo("pending");
        assertThat(managed.isTerminal()).isFalse();
        h.clock.advanceMonoSeconds(1.5); scheduler.advance(Duration.ofMillis(1500));
        assertThat(managed.awaitDone(Duration.ofSeconds(10))).isTrue();
        assertThat(h.store.findOwned(row.id(), subject).orElseThrow().status()).isEqualTo("system_error");
        assertThat(h.store.findOwned(row.id(), subject).orElseThrow().reason()).isEqualTo("timeout");
        assertThat(h.service.loadInputs(row.id())).isEmpty();
        assertThat(h.gate.active()).isZero();
        assertThatThrownBy(() -> h.store.finish(row.id(), row.sessionId(), "迟到", List.of(), "answered", "supported", "", 600500))
                .isInstanceOf(IllegalStateException.class);
        assertThat(h.store.findOwned(row.id(), subject).orElseThrow().reason()).isEqualTo("timeout");
        released.countDown(); workers.shutdownNow(); scheduler.shutdownNow();
    }

    @Test void realAdmissionHas24HourTtlAndBoundaryIsClosedOnRead() throws Exception {
        var admitted = admit("/* J5 ttl synthetic */ int plugin_main(void){return 0;}");
        var inputs = h.service.loadInputs(admitted.taskId()).orElseThrow();
        Instant created = OffsetDateTime.parse(inputs.createdAt()).toInstant();
        Instant expiry = OffsetDateTime.parse(inputs.expiresAt()).toInstant();
        assertThat(expiry).isEqualTo(h.clock.nowUtc().plus(Duration.ofHours(24)));
        assertThat(Duration.between(created, expiry)).isBetween(Duration.ofHours(24).minusMillis(1), Duration.ofHours(24));
        h.clock.setWall(expiry.minusNanos(1000));
        assertThat(h.service.loadInputs(admitted.taskId())).isPresent();
        h.clock.setWall(expiry);
        assertThat(h.service.loadInputs(admitted.taskId())).isEmpty();
        // 输入已过期：实际恢复读取无有效源，判安全interrupted，不会再交外部执行。
        h.clock.advanceWall(Duration.ofSeconds(31));
        assertThat(h.service.recovery().ensureManaged(h.store.findOwned(admitted.taskId(), subject).orElseThrow())).isEmpty();
        assertThat(h.store.findOwned(admitted.taskId(), subject).orElseThrow().status()).isEqualTo("system_error");
    }

    @Test void terminalIsImmediateAndRegistryTtl300ReplaysWithoutExecution() throws Exception {
        var admitted = admit("/* J5 terminal synthetic */ int plugin_main(void){return 0;}");
        var row = h.store.findOwned(admitted.taskId(), subject).orElseThrow();
        var context = new TaskExecutor.TaskExecutionContext(row.id(), row.requestId(), row.sessionId(), subject,
                List.of("viewer"), "chat", "边界", "", null, null, null, null);
        var managed = h.service.startTask(subject, row.requestId(), row.sessionId(), context,
                (c, emit) -> new TaskExecutor.TaskExecutionResult("合成终态", "answered", "supported", List.of(), ""));
        assertThat(managed.awaitDone(Duration.ofSeconds(10))).isTrue();
        assertThat(h.service.loadInputs(row.id())).isEmpty();
        h.clock.advanceMonoSeconds(299); h.registry.sweepFinished();
        assertThat(h.registry.get(row.id())).isSameAs(managed);
        h.clock.advanceMonoSeconds(1); h.registry.sweepFinished();
        assertThat(h.registry.get(row.id())).isNull();
        assertThat(h.service.probeDetail(new RequestIdentity(row.requestId(), row.inputHash(), false, RequestIdentity.Purpose.chat),
                subject, Optional.of(row.sessionId())).state()).isEqualTo("replay");
        assertThat(h.countRows("assistant_messages", "session_id=? AND role='assistant'", row.sessionId())).isEqualTo(1);
        assertThat(h.columnValue("usage_counters", "chat_count", "subject=? AND kind='chat'", subject)).isEqualTo("1");
    }
}
