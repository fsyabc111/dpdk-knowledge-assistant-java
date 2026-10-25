package io.dpdk.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dpdk.assistant.contracts.chat.SseEvent;
import io.dpdk.assistant.contracts.task.AdmissionInput;
import io.dpdk.assistant.contracts.task.RequestIdentity;
import io.dpdk.assistant.task.TaskExceptions.TaskStillRunningException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 验收 3+4：多线程并发启动/快照订阅/终态取消无双执行、无遗漏终帧、无负槽；
 * 时钟可控（时钟仅在 grace/TTL/认领面参与，这里用真实短延迟替代 sleep 语义）；
 * 断开所有 SSE 订阅仍完成；重连先回放再实时；显式取消不被迟到完成覆盖。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class TaskRunnerConcurrencyTest {

    private static TaskTestHarness h;
    private static String subject;

    @BeforeAll
    static void setup() throws Exception {
        h = TaskTestHarness.create("runner");
        subject = "U20260000031";
        h.seedUser(subject, "[\"viewer\"]", 10000);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    private static String newSession() throws Exception {
        String id = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        h.seedSession(id, subject);
        return id;
    }

    private static TaskAdmitOutcome admit(String question, String sessionId) {
        return h.service.admitDetail(new AdmissionInput(
                new RequestIdentity(UUID.randomUUID().toString(),
                        TaskInputHash.compute(question), false, RequestIdentity.Purpose.chat),
                subject, sessionId, question, "", "test-model", question, 0));
    }

    /** 脚本化执行体：可暂停（可中断闩）、计数、发事件、返回固定结论。 */
    static final class ScriptedExecutor implements TaskExecutor {

        final AtomicInteger executions = new AtomicInteger();
        final CountDownLatch pause = new CountDownLatch(1);
        final CountDownLatch entered = new CountDownLatch(1);
        volatile boolean emitBurst = true;

        @Override
        public TaskExecutionResult run(TaskExecutionContext context, Consumer<TaskEvent> emit)
                throws Exception {
            executions.incrementAndGet();
            entered.countDown();
            if (emitBurst) {
                for (int i = 0; i < 8; i++) {
                    emit.accept(TaskEvent.of("status", Map.of("stage", "step-" + i)));
                }
            }
            pause.await(30, TimeUnit.SECONDS);
            emit.accept(TaskEvent.of("status", Map.of("stage", "final")));
            return new TaskExecutionResult("脚本答案", "answered", "supported",
                    List.of(new CitationData("li_script", "docs/s.md", "§2")), "release-9");
        }

        void resume() {
            pause.countDown();
        }
    }

    /** 收集订阅者：request(MAX) 后逐帧累积，记录完成/错误。 */
    static final class CollectingSubscriber implements Flow.Subscriber<SseEvent> {

        final List<String> frames = new CopyOnWriteArrayList<>();
        final CountDownLatch completed = new CountDownLatch(1);
        volatile Throwable error;
        private Flow.Subscription subscription;

        @Override
        public void onSubscribe(Flow.Subscription s) {
            this.subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(SseEvent item) {
            frames.add(item.name() + ":" + item.dataJson());
        }

        @Override
        public void onError(Throwable t) {
            error = t;
            completed.countDown();
        }

        @Override
        public void onComplete() {
            completed.countDown();
        }

        void cancel() {
            if (subscription != null) {
                subscription.cancel();
            }
        }

        List<String> await(long seconds) throws InterruptedException {
            assertThat(completed.await(seconds, TimeUnit.SECONDS))
                    .as("订阅在时限内收口").isTrue();
            return frames;
        }

        List<String> names() {
            return frames.stream().map(f -> f.substring(0, f.indexOf(':'))).toList();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 验收 3：并发启动无双执行 / 无负槽                                      */
    /* ------------------------------------------------------------------ */

    @Test
    void concurrentStartSingleExecutionAndNoNegativeSlots() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("并发起步", sessionId);
        ScriptedExecutor executor = new ScriptedExecutor();
        h.setRecoveryExecutor(executor);
        TaskExecutor.TaskExecutionContext context = new TaskExecutor.TaskExecutionContext(
                admit.taskId(), "req-cs", sessionId, subject, List.of("viewer"), "chat",
                "并发起步", "", null, null, null, null);

        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<ManagedTask> handles = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    handles.add(h.service.startTask(subject, "req-cs", sessionId, context,
                            executor));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        assertThat(executor.entered.await(5, TimeUnit.SECONDS)).isTrue();

        // 全部拿到同一句柄；只有一个执行
        assertThat(handles).hasSize(threads);
        assertThat(handles.stream().map(m -> m.taskId).distinct()).hasSize(1);
        assertThat(executor.executions.get()).isEqualTo(1);
        assertThat(h.gate.active()).isEqualTo(1); // 唯一在跑任务占一槽

        executor.resume();
        assertThat(handles.get(0).awaitDone(Duration.ofSeconds(10))).isTrue();
        assertThat(handles.get(0).isTerminal()).isTrue();
        // 收口后槽位恰好释放一次；重复 release 不产生负槽
        assertThat(h.gate.active()).isZero();
        h.gate.release();
        h.gate.release();
        assertThat(h.gate.active()).isZero();
        assertThat(h.columnValue("rag_requests", "status", "id=?", admit.taskId()))
                .isEqualTo("answered");
    }

    @Test
    void admissionGateBoundsWaitingAndTryAcquire() throws Exception {
        AdmissionGate gate = new AdmissionGate(2, 1, Duration.ofMillis(200));
        CountDownLatch block = new CountDownLatch(1);
        CountDownLatch occupied = new CountDownLatch(2);
        AtomicInteger busyOrTimeout = new AtomicInteger();
        List<Thread> threads = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 2; i++) {
            Thread t = Thread.ofVirtual().start(() -> {
                try {
                    gate.acquire();
                    occupied.countDown();
                    block.await(10, TimeUnit.SECONDS);
                    gate.release();
                } catch (InterruptedException ignored) {
                    // no-op
                }
            });
            threads.add(t);
        }
        // 第 3 个线程：占最后一个排队名额
        Thread waiter = Thread.ofVirtual().start(() -> {
            try {
                gate.acquire();
                block.await(10, TimeUnit.SECONDS);
                gate.release();
            } catch (TaskExceptions.TaskAdmissionBusyException e) {
                busyOrTimeout.incrementAndGet();
            } catch (InterruptedException ignored) {
                // no-op
            }
        });
        threads.add(waiter);
        assertThat(occupied.await(5, TimeUnit.SECONDS)).isTrue();
        // 等待者进入条件队列（轮询到 waiting==1）
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (gate.waiting() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertThat(gate.active()).isEqualTo(2);
        assertThat(gate.waiting()).isEqualTo(1);
        assertThat(busyOrTimeout.get()).isZero();
        // 排队名额满：第 4 个 acquire 直接 429（服务繁忙）
        assertThatThrownBy(gate::acquire)
                .isInstanceOf(TaskExceptions.TaskAdmissionBusyException.class)
                .hasMessage("服务繁忙，请稍后重试");
        // tryAcquire 非排队：满员 false，不占等待名额
        assertThat(gate.tryAcquire()).isFalse();
        assertThat(gate.waiting()).isEqualTo(1);
        block.countDown();
        for (Thread t : threads) {
            t.join(5000);
            assertThat(t.isAlive()).isFalse();
        }
        assertThat(gate.active()).isZero();
        assertThat(gate.waiting()).isZero();

        // 排队超时分支：主线程占满 2 槽后第 3 个 acquire 等待 200ms 超时（等待超时文案）
        gate.acquire();
        gate.acquire();
        assertThat(gate.active()).isEqualTo(2);
        long begin = System.nanoTime();
        assertThatThrownBy(gate::acquire)
                .isInstanceOf(TaskExceptions.TaskAdmissionBusyException.class)
                .hasMessage("等待超时，请稍后重试");
        assertThat((System.nanoTime() - begin) / 1_000_000).isGreaterThanOrEqualTo(150);
        gate.release();
        gate.release();
        assertThat(gate.active()).isZero();
    }

    /* ------------------------------------------------------------------ */
    /* 验收 3：并发订阅无遗漏；终态帧完备                                     */
    /* ------------------------------------------------------------------ */

    @Test
    void concurrentSubscribersSeeAllFramesIncludingTerminal() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("并发订阅", sessionId);
        ScriptedExecutor executor = new ScriptedExecutor();
        TaskExecutor.TaskExecutionContext context = new TaskExecutor.TaskExecutionContext(
                admit.taskId(), "req-sub", sessionId, subject, List.of("viewer"), "chat",
                "并发订阅", "", null, null, null, null);
        ManagedTask managed = h.service.startTask(subject, "req-sub", sessionId, context, executor);
        assertThat(executor.entered.await(5, TimeUnit.SECONDS)).isTrue();

        List<CollectingSubscriber> collectors = new CopyOnWriteArrayList<>();
        for (int i = 0; i < 6; i++) {
            CollectingSubscriber collector = new CollectingSubscriber();
            collectors.add(collector);
            h.service.taskEvents(admit.taskId(), subject).subscribe(collector);
        }
        Thread.sleep(50);   // 让一半订阅者在 burst 帧之后注册（快照路径）
        executor.resume();
        for (CollectingSubscriber collector : collectors) {
            List<String> frames = collector.await(10);
            assertThat(collector.names().get(0)).isEqualTo("start");
            assertThat(collector.frames).anyMatch(f -> f.startsWith("status:") && f.contains("step-0"));
            assertThat(collector.frames).anyMatch(f -> f.startsWith("status:") && f.contains("step-7"));
            assertThat(collector.names().subList(collector.names().size() - 3,
                    collector.names().size())).containsExactly("sources", "delta", "done");
            // 终帧 done 数据字段完备（result 视图：无 terminal 键，answer_status 在）
            assertThat(frames.get(frames.size() - 1))
                    .contains("\"answer_status\":\"answered\"")
                    .contains("\"task_id\":\"" + admit.taskId() + "\"");
            // 无重复终帧、无帧丢失（start + 至少 9 个事件帧 + 终段 3 帧）
            assertThat(frames.size()).isGreaterThanOrEqualTo(13);
        }
        assertThat(h.gate.active()).isZero();
    }

    /* ------------------------------------------------------------------ */
    /* 验收 4：断开仍完成；重连先回放再实时；取消不被迟到完成覆盖               */
    /* ------------------------------------------------------------------ */

    @Test
    void disconnectDoesNotCancelAndTerminalRebuildsFromDb() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("断线续跑", sessionId);
        ScriptedExecutor executor = new ScriptedExecutor();
        TaskExecutor.TaskExecutionContext context = new TaskExecutor.TaskExecutionContext(
                admit.taskId(), "req-dc", sessionId, subject, List.of("viewer"), "chat",
                "断线续跑", "", null, null, null, null);
        ManagedTask managed = h.service.startTask(subject, "req-dc", sessionId, context, executor);
        assertThat(executor.entered.await(5, TimeUnit.SECONDS)).isTrue();

        CollectingSubscriber early = new CollectingSubscriber();
        h.service.taskEvents(admit.taskId(), subject).subscribe(early);
        early.cancel();   // 订阅方断开：绝不是取消（B13 修复点）
        Thread.sleep(50);

        executor.resume();
        assertThat(managed.awaitDone(Duration.ofSeconds(10))).isTrue();
        assertThat(managed.result()).isNotNull();
        assertThat(h.columnValue("rag_requests", "status", "id=?", admit.taskId()))
                .isEqualTo("answered");
        assertThat(h.columnValue("rag_requests", "message_id", "id=?", admit.taskId()))
                .isNotBlank();
        assertThat(h.service.loadInputs(admit.taskId())).isEmpty();
        assertThat(h.gate.active()).isZero();

        // 路径一：注册表条目 TTL 内 → 回放（含执行期事件日志）
        CollectingSubscriber replay = new CollectingSubscriber();
        h.service.taskEvents(admit.taskId(), subject).subscribe(replay);
        List<String> frames = replay.await(10);
        assertThat(replay.names()).containsExactly("start", "status", "status", "status",
                "status", "status", "status", "status", "status", "status",
                "sources", "delta", "done");
        assertThat(frames.get(frames.size() - 1)).contains("\"answer_status\":\"answered\"");

        // 路径二：条目清出注册表（TTL 后）→ 按终态行重建（done 带 status/terminal）
        h.clock.advanceMonoNanos(Duration.ofSeconds(400).toNanos());
        h.registry.sweepFinished();
        assertThat(h.registry.get(admit.taskId())).isNull();
        CollectingSubscriber rebuilt = new CollectingSubscriber();
        h.service.taskEvents(admit.taskId(), subject).subscribe(rebuilt);
        List<String> rebuiltFrames = rebuilt.await(10);
        assertThat(rebuilt.names()).containsExactly("start", "sources", "delta", "done");
        assertThat(rebuiltFrames.get(rebuiltFrames.size() - 1))
                .contains("\"terminal\":true")
                .contains("\"status\":\"answered\"")
                .contains("\"answer_status\":\"answered\"");
    }

    @Test
    void reconnectMidRunReplaysSnapshotThenLive() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("中途重连", sessionId);
        ScriptedExecutor executor = new ScriptedExecutor();
        TaskExecutor.TaskExecutionContext context = new TaskExecutor.TaskExecutionContext(
                admit.taskId(), "req-rc", sessionId, subject, List.of("viewer"), "chat",
                "中途重连", "", null, null, null, null);
        ManagedTask managed = h.service.startTask(subject, "req-rc", sessionId, context, executor);
        assertThat(executor.entered.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(30);   // burst 帧已入回放日志

        CollectingSubscriber late = new CollectingSubscriber();
        h.service.taskEvents(admit.taskId(), subject).subscribe(late);
        executor.resume();
        List<String> frames = late.await(10);
        // 快照先于实时：start → status×8（burst）→ final → 终段
        assertThat(frames.get(0)).startsWith("start:");
        List<String> names = late.names();
        int firstFinal = names.indexOf("status") == -1 ? -1 : names.lastIndexOf("status");
        assertThat(names.subList(1, firstFinal + 1)).hasSize(9);
        assertThat(names.subList(names.size() - 3, names.size()))
                .containsExactly("sources", "delta", "done");
        // status 帧序无重复（快照 8 帧 + 实时 1 帧）
        assertThat(names.stream().filter(n -> n.equals("status")).count()).isEqualTo(9);
    }

    @Test
    void explicitCancelIsNotOverwrittenByLateCompletion() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("取消竞速", sessionId);
        ScriptedExecutor executor = new ScriptedExecutor();
        TaskExecutor.TaskExecutionContext context = new TaskExecutor.TaskExecutionContext(
                admit.taskId(), "req-ca", sessionId, subject, List.of("viewer"), "chat",
                "取消竞速", "", null, null, null, null);
        ManagedTask managed = h.service.startTask(subject, "req-ca", sessionId, context, executor);
        assertThat(executor.entered.await(5, TimeUnit.SECONDS)).isTrue();

        // 取消：库行先终态化，再中断执行线程
        RagTaskService.CancelOutcome cancelled = h.service.cancelDetail(admit.taskId(), subject,
                "user_cancelled");
        assertThat(cancelled.changed()).isTrue();
        assertThat(cancelled.row().status()).isEqualTo("cancelled");
        executor.resume();   // 迟到的"完成"：finish 将被 pending 条件更新拒绝

        assertThat(managed.awaitDone(Duration.ofSeconds(10))).isTrue();
        assertThat(h.columnValue("rag_requests", "status", "id=?", admit.taskId()))
                .isEqualTo("cancelled");
        assertThat(h.columnValue("rag_requests", "reason", "id=?", admit.taskId()))
                .isEqualTo("user_cancelled");
        assertThat(h.service.loadInputs(admit.taskId())).isEmpty();
        assertThat(h.gate.active()).isZero();

        // 迟到订阅者读到的终帧是 cancelled（非 answered）
        CollectingSubscriber late = new CollectingSubscriber();
        h.service.taskEvents(admit.taskId(), subject).subscribe(late);
        List<String> frames = late.await(10);
        assertThat(frames.get(frames.size() - 1)).contains("\"status\":\"cancelled\"")
                .contains("\"terminal\":true");
    }

    /* ------------------------------------------------------------------ */
    /* 执行墙 / 非流式等待                                                    */
    /* ------------------------------------------------------------------ */

    @Test
    void deadlineTimeoutMarksSystemErrorAndKeepsSlotsClean() throws Exception {
        TaskTestHarness tight = TaskTestHarness.create("timeout",
                new TaskSettings(Duration.ofMillis(60), Duration.ofSeconds(1), 4, 4,
                        Duration.ofSeconds(10), Duration.ofSeconds(30),
                        Duration.ofSeconds(300), 500, Duration.ofHours(24)));
        try {
            String viewer = "U20260000032";
            tight.seedUser(viewer, "[\"viewer\"]", 10000);
            String sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "")
                    .substring(0, 12);
            tight.seedSession(sessionId, viewer);
            TaskAdmitOutcome admit = tight.service.admitDetail(new AdmissionInput(
                    new RequestIdentity(UUID.randomUUID().toString(),
                            TaskInputHash.compute("超时问题"), false, RequestIdentity.Purpose.chat),
                    viewer, sessionId, "超时问题", "", "test-model", "超时问题", 0));
            tight.runner.required(tx -> {
                try {
                    tight.store.storeInputs(tx, admit.taskId(), "超时问题", null, null, null, null);
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
                return null;
            });
            assertThat(tight.service.loadInputs(admit.taskId())).isPresent();

            ScriptedExecutor executor = new ScriptedExecutor();
            TaskExecutor.TaskExecutionContext context = new TaskExecutor.TaskExecutionContext(
                    admit.taskId(), "req-to", sessionId, viewer, List.of("viewer"), "chat",
                    "超时问题", "", null, null, null, null);
            ManagedTask managed = tight.service.startTask(viewer, "req-to", sessionId, context,
                    executor);
            assertThat(managed.awaitDone(Duration.ofSeconds(10))).isTrue();
            assertThat(managed.result()).isNull();

            assertThat(tight.columnValue("rag_requests", "status", "id=?", admit.taskId()))
                    .isEqualTo("system_error");
            assertThat(tight.columnValue("rag_requests", "reason", "id=?", admit.taskId()))
                    .isEqualTo("timeout");
            assertThat(tight.service.loadInputs(admit.taskId())).isEmpty();
            assertThat(tight.gate.active()).isZero();

            // 迟到订阅拿 system_error 终帧（pending 行已被判死 → DB 重建路径）
            CollectingSubscriber late = new CollectingSubscriber();
            tight.service.taskEvents(admit.taskId(), viewer).subscribe(late);
            List<String> frames = late.await(10);
            assertThat(frames.get(frames.size() - 1)).contains("\"status\":\"system_error\"")
                    .contains("\"terminal\":true");
        } finally {
            tight.taskRunner.shutdownWatchdog();
        }
    }

    @Test
    void waitResultTimesOutWithTaskIdDetail() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("等待超时", sessionId);
        ScriptedExecutor executor = new ScriptedExecutor();
        TaskExecutor.TaskExecutionContext context = new TaskExecutor.TaskExecutionContext(
                admit.taskId(), "req-wr", sessionId, subject, List.of("viewer"), "chat",
                "等待超时", "", null, null, null, null);
        ManagedTask managed = h.service.startTask(subject, "req-wr", sessionId, context, executor);
        assertThat(executor.entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> h.service.waitResult(managed, Duration.ofMillis(100)))
                .isInstanceOf(TaskStillRunningException.class)
                .hasMessage("任务仍在执行，可通过 /api/tasks/{task_id} 查询或续传")
                .satisfies(e -> assertThat(((TaskStillRunningException) e).taskId)
                        .isEqualTo(admit.taskId()));
        executor.resume();
        assertThat(managed.awaitDone(Duration.ofSeconds(10))).isTrue();
    }
}
