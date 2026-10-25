package io.dpdk.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dpdk.assistant.contracts.chat.SseEvent;
import io.dpdk.assistant.contracts.task.AdmissionInput;
import io.dpdk.assistant.contracts.task.RequestIdentity;
import io.dpdk.assistant.task.TaskExceptions.TaskConflictException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 验收 6 + integration_pending 闭环：
 * - IP-1：停号 → 库行取消（admin 事务）+ 本进程执行体取消（TaskLocalCancellation
 *   真实实现）+ 吞错语义；
 * - IP-J205-2：活动会话删除 × 执行中任务——删除照常成功（不因 pending 任务失败），
 *   执行收口在已删会话上 FK 失败 → 行保持 pending、输入保留、订阅者拿 pending
 *   非终态帧（Python 基线行为的忠实复现，缺陷不静默修复）；
 * - 恢复分支矩阵：停用账号取消 / 输入缺失判死 / 占槽满留待下拍 / 重复恢复单执行。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class TaskIpClosureTest {

    private static TaskTestHarness h;
    private static String subject;

    @BeforeAll
    static void setup() throws Exception {
        h = TaskTestHarness.create("ipclosure");
        subject = "U20260000061";
        h.seedUser(subject, "[\"viewer\"]", 10000);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    private static String newSession(String owner) throws Exception {
        String id = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        h.seedSession(id, owner);
        return id;
    }

    private static TaskAdmitOutcome admit(String owner, String question, String sessionId) {
        return h.service.admitDetail(new AdmissionInput(
                new RequestIdentity(UUID.randomUUID().toString(),
                        TaskInputHash.compute(question), false, RequestIdentity.Purpose.chat),
                owner, sessionId, question, "", "test-model", question, 0));
    }

    /** 挂起执行体：latch 门控 + 执行计数。 */
    static final class HangingExecutor implements TaskExecutor {

        final AtomicInteger executions = new AtomicInteger();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean interruptObserved;

        @Override
        public TaskExecutionResult run(TaskExecutionContext context, Consumer<TaskEvent> emit)
                throws Exception {
            executions.incrementAndGet();
            entered.countDown();
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                interruptObserved = true;
                throw e;
            }
            return new TaskExecutionResult("终态答案", "answered", "supported",
                    List.of(new CitationData("li_ip", "docs/ip.md", "§1")), "release-ip");
        }

        void resume() {
            release.countDown();
        }
    }

    private static TaskExecutor.TaskExecutionContext contextOf(TaskAdmitOutcome admit,
                                                               String sessionId,
                                                               String question) {
        return new TaskExecutor.TaskExecutionContext(admit.taskId(), "req-ip", sessionId,
                admit.row().orElseThrow().userId(), List.of("viewer"), "chat", question, "",
                null, null, null, null);
    }

    /* ------------------------------------------------------------------ */
    /* IP-1：停号 → 任务取消（库行 + 本进程执行体）                           */
    /* ------------------------------------------------------------------ */

    @Test
    void ip1AccountDisableCancelsRunningAndIdlePendingTasks() throws Exception {
        String user = "U20260000062";
        h.seedUser(user, "[\"viewer\"]", 10000);
        String sessionId = newSession(user);
        TaskAdmitOutcome admit = admit(user, "在跑问题", sessionId);
        HangingExecutor executor = new HangingExecutor();
        ManagedTask managed = h.service.startTask(user, "req-ip", sessionId,
                contextOf(admit, sessionId, "在跑问题"), executor);
        assertThat(executor.entered.await(5, TimeUnit.SECONDS)).isTrue();

        // 未注册的 pending 行（只受理未起步）
        TaskAdmitOutcome idle = admit(user, "未起步问题", sessionId);
        h.store.recordJob(idle.taskId(), "job-ip1", "grade_plugin", "dns", "hash-ip1");

        // 停号：admin 事务先终态化（库行 + 输入 + 任务关联成绩），随后提交后段
        // 调 LocalTaskCancellationPort（真实 TaskLocalCancellation 实现）
        TaskLocalCancellation cancellation = new TaskLocalCancellation(h.service);
        assertThat(h.adminUsers().setEnabled(user, false)).isTrue();
        assertThatCode(() -> cancellation.cancelLocalForUser(user)).doesNotThrowAnyException();

        // 在跑任务：中断传达执行体 + 行已 cancelled + 槽位释放 + 输入清理
        assertThat(executor.interruptObserved).isTrue();
        assertThat(managed.awaitDone(Duration.ofSeconds(10))).isTrue();
        assertThat(managed.isTerminal()).isTrue();
        assertThat(h.gate.active()).isZero();
        for (String taskId : List.of(admit.taskId(), idle.taskId())) {
            assertThat(h.columnValue("rag_requests", "status", "id=?", taskId))
                    .isEqualTo("cancelled");
            assertThat(h.columnValue("rag_requests", "reason", "id=?", taskId))
                    .isEqualTo("account_disabled");
            assertThat(h.service.loadInputs(taskId)).isEmpty();
        }
        // 任务关联成绩被停号事务撤销（job 已登记、成绩从未落）
        assertThat(h.countRows("rag_task_jobs", "task_id=?", idle.taskId())).isEqualTo(1);

        // 吞错语义：取消段抛错不影响停号结果（已提交的终态不受影响）
        RagTaskService throwing = org.mockito.Mockito.mock(RagTaskService.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("synthetic local cancel failure"))
                .when(throwing).cancelLocalForUser(user);
        TaskLocalCancellation swallowing = new TaskLocalCancellation(throwing);
        assertThatCode(() -> swallowing.cancelLocalForUser(user)).doesNotThrowAnyException();
    }

    /* ------------------------------------------------------------------ */
    /* IP-J205-2：活动会话删除 × 执行中任务                                   */
    /* ------------------------------------------------------------------ */

    @Test
    void ipj205_2SessionDeleteWhileTaskRunningKeepsBaselineBehavior() throws Exception {
        String sessionId = newSession(subject);
        TaskAdmitOutcome admit = admit(subject, "会话删除组合", sessionId);
        HangingExecutor executor = new HangingExecutor();
        ManagedTask managed = h.service.startTask(subject, "req-ip2", sessionId,
                contextOf(admit, sessionId, "会话删除组合"), executor);
        assertThat(executor.entered.await(5, TimeUnit.SECONDS)).isTrue();

        // 订阅者在跑（断开再重连的回放面在并发测试覆盖；这里验证删除后的收口帧）
        List<String> frames = new CopyOnWriteArrayList<>();
        CountDownLatch completed = new CountDownLatch(1);
        h.service.taskEvents(admit.taskId(), subject).subscribe(new java.util.concurrent.Flow.Subscriber<SseEvent>() {
            @Override
            public void onSubscribe(java.util.concurrent.Flow.Subscription s) {
                s.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(SseEvent item) {
                frames.add(item.name() + ":" + item.dataJson());
            }

            @Override
            public void onError(Throwable t) {
                completed.countDown();
            }

            @Override
            public void onComplete() {
                completed.countDown();
            }
        });

        // 删除活动会话：不因存在 pending rag_request 而失败（J2-05 已固定语义）
        assertThat(h.sessions.deleteSession(sessionId, subject)).isTrue();

        // 执行收口：finish 落库在已删会话上 FK 失败 → 行保持 pending、输入保留
        executor.resume();
        assertThat(managed.awaitDone(Duration.ofSeconds(10))).isTrue();
        assertThat(managed.result()).isNull();
        assertThat(h.columnValue("rag_requests", "status", "id=?", admit.taskId()))
                .isEqualTo("pending");
        assertThat(h.service.loadInputs(admit.taskId())).isPresent();
        assertThat(h.gate.active()).isZero();
        assertThat(completed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(frames.get(frames.size() - 1)).startsWith("done:")
                .contains("\"status\":\"pending\"").contains("\"terminal\":false");

        // 取消同样被 FK 拒绝（Python 基线同形：cancel_task 写占位消息 FK 失败整笔回滚）
        assertThatThrownBy(() -> h.service.cancelDetail(admit.taskId(), subject, "user_cancelled"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(h.columnValue("rag_requests", "status", "id=?", admit.taskId()))
                .isEqualTo("pending");

        // 恢复重驱动：再跑一次、再在已删会话上失败——输入持续保留（可恢复性优先）
        h.makeOrphan(admit.taskId(), Duration.ofSeconds(120));
        HangingExecutor second = new HangingExecutor();
        h.setRecoveryExecutor(second);
        assertThat(h.service.recoverOrphans()).isEqualTo(1);
        second.resume();
        ManagedTask recovered = h.registry.get(admit.taskId());
        if (recovered == null) {
            // 注册表已随 pending 清出；经 ensureManaged 再驱动一次观察
            Optional<ManagedTask> ensured = h.recovery.ensureManaged(
                    h.service.getOwnedDetail(admit.taskId(), subject).orElseThrow());
            assertThat(ensured).isPresent();
            recovered = ensured.get();
        }
        assertThat(recovered.awaitDone(Duration.ofSeconds(10))).isTrue();
        assertThat(second.executions.get()).isEqualTo(1);
        assertThat(h.columnValue("rag_requests", "status", "id=?", admit.taskId()))
                .isEqualTo("pending");
        assertThat(h.service.loadInputs(admit.taskId())).isPresent();
        // 清理：行保持 pending（基线语义）但拨回新鲜，避免污染其他用例的孤儿扫描
        h.makeFresh(admit.taskId());
    }

    /* ------------------------------------------------------------------ */
    /* 恢复分支矩阵                                                          */
    /* ------------------------------------------------------------------ */

    @Test
    void recoveryBranchDisabledAccountCancelsWithAccountDisabled() throws Exception {
        String user = "U20260000063";
        h.seedUser(user, "[\"viewer\"]", 10000);
        String sessionId = newSession(user);
        TaskAdmitOutcome admit = admit(user, "停号恢复", sessionId);
        HangingExecutor executor = new HangingExecutor();
        h.setRecoveryExecutor(executor);
        h.makeOrphan(admit.taskId(), Duration.ofSeconds(120));
        h.adminUsers().setEnabled(user, false);
        assertThat(h.service.recoverOrphans()).isZero();
        assertThat(h.columnValue("rag_requests", "status", "id=?", admit.taskId()))
                .isEqualTo("cancelled");
        assertThat(h.columnValue("rag_requests", "reason", "id=?", admit.taskId()))
                .isEqualTo("account_disabled");
        assertThat(executor.executions.get()).isZero();
    }

    @Test
    void recoveryBranchMissingInputsMarksInterrupted() throws Exception {
        String sessionId = newSession(subject);
        TaskAdmitOutcome admit = admit(subject, "输入缺失恢复", sessionId);
        HangingExecutor executor = new HangingExecutor();
        h.setRecoveryExecutor(executor);
        h.store.dropInputs(admit.taskId());
        h.makeOrphan(admit.taskId(), Duration.ofSeconds(120));
        assertThat(h.service.recoverOrphans()).isZero();
        assertThat(h.columnValue("rag_requests", "status", "id=?", admit.taskId()))
                .isEqualTo("system_error");
        assertThat(h.columnValue("rag_requests", "reason", "id=?", admit.taskId()))
                .isEqualTo("interrupted");
        assertThat(executor.executions.get()).isZero();
    }

    @Test
    void recoveryBranchSlotFullLeavesPendingForNextBeat() throws Exception {
        String sessionId = newSession(subject);
        TaskAdmitOutcome admit = admit(subject, "占槽满恢复", sessionId);
        HangingExecutor executor = new HangingExecutor();
        h.setRecoveryExecutor(executor);
        h.makeOrphan(admit.taskId(), Duration.ofSeconds(120));
        // 使用实际冻结设置占满全部槽位；显式确认释放，避免污染其他场景。
        int capacity = h.settings.maxActiveRequests();
        CountDownLatch occupied = new CountDownLatch(capacity);
        CountDownLatch released = new CountDownLatch(capacity);
        CountDownLatch block = new CountDownLatch(1);
        for (int i = 0; i < capacity; i++) {
            Thread.ofVirtual().start(() -> {
                boolean acquired = false;
                try {
                    h.gate.acquire();
                    acquired = true;
                    occupied.countDown();
                    block.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    if (acquired) { h.gate.release(); }
                    released.countDown();
                }
            });
        }
        assertThat(occupied.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(h.gate.active()).isEqualTo(capacity);
            assertThat(h.service.recoverOrphans()).isZero();
            assertThat(h.columnValue("rag_requests", "status", "id=?", admit.taskId()))
                    .isEqualTo("pending");
            assertThat(h.service.loadInputs(admit.taskId())).isPresent();
            assertThat(executor.executions.get()).isZero();
        } finally {
            block.countDown();
            assertThat(released.await(10, TimeUnit.SECONDS)).isTrue();
        }
        // 槽位释放后下一拍照常恢复。注意基线顺序：认领闸先于占槽——占槽失败的行
        // 已被拨新，需再次过 grace 窗口才会重新入选孤儿扫描（与源行为同位）。
        h.makeOrphan(admit.taskId(), Duration.ofSeconds(120));
        assertThat(h.service.recoverOrphans()).isEqualTo(1);
        executor.resume();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!"answered".equals(h.columnValue("rag_requests", "status", "id=?",
                admit.taskId())) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(h.columnValue("rag_requests", "status", "id=?", admit.taskId()))
                .isEqualTo("answered");
    }

    @Test
    void concurrentRecoveryDrivesExactlyOnce() throws Exception {
        String sessionId = newSession(subject);
        TaskAdmitOutcome admit = admit(subject, "重复恢复", sessionId);
        HangingExecutor executor = new HangingExecutor();
        h.setRecoveryExecutor(executor);
        h.makeOrphan(admit.taskId(), Duration.ofSeconds(120));

        int threads = 6;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger recoveredTotal = new AtomicInteger();
        var recoveryFailures = new CopyOnWriteArrayList<Exception>();
        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    recoveredTotal.addAndGet(h.service.recoverOrphans());
                } catch (Exception e) {
                    recoveryFailures.add(e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        // 注册完成不等于异步执行体已获调度；用执行入口闩确认后保留恰好一次断言。
        try {
            assertThat(recoveryFailures).isEmpty();
            assertThat(executor.entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(executor.executions.get()).isEqualTo(1);
            assertThat(recoveredTotal.get()).isEqualTo(1);
        } finally { executor.resume(); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!"answered".equals(h.columnValue("rag_requests", "status", "id=?",
                admit.taskId())) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(h.columnValue("rag_requests", "status", "id=?", admit.taskId()))
                .isEqualTo("answered");
        assertThat(h.countRows("assistant_messages", "metadata_json::text LIKE ?",
                "%" + admit.taskId() + "%")).isGreaterThanOrEqualTo(1);
    }

    /* ------------------------------------------------------------------ */
    /* probe 分支补充：跨会话同 request 隔离                                  */
    /* ------------------------------------------------------------------ */

    @Test
    void sameRequestIdAcrossSessionsAndDistinctRequestsCoexist() {
        String sessionA = "sess_a_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 8);
        String sessionB = "sess_b_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 8);
        assertThatCode(() -> {
            h.seedSession(sessionA, subject);
            h.seedSession(sessionB, subject);
        }).doesNotThrowAnyException();
        String requestId = UUID.randomUUID().toString();
        TaskAdmitOutcome a = h.service.admitDetail(new AdmissionInput(
                new RequestIdentity(requestId, TaskInputHash.compute("同键跨会话"), false,
                        RequestIdentity.Purpose.chat),
                subject, sessionA, "同键跨会话", "", "test-model", "同键跨会话", 0));
        TaskAdmitOutcome b = h.service.admitDetail(new AdmissionInput(
                new RequestIdentity(requestId, TaskInputHash.compute("同键跨会话"), false,
                        RequestIdentity.Purpose.chat),
                subject, sessionB, "同键跨会话", "", "test-model", "同键跨会话", 0));
        assertThat(a.taskId()).isNotEqualTo(b.taskId());
        // 同会话同键（异内容）：唯一键冲突 → 冲突接续路径 probe → hash 不匹配 409
        assertThatThrownBy(() -> h.service.admitDetail(new AdmissionInput(
                new RequestIdentity(requestId, TaskInputHash.compute("另一份内容"), false,
                        RequestIdentity.Purpose.chat),
                subject, sessionA, "另一份内容", "", "test-model", "另一份内容", 0)))
                .isInstanceOf(TaskConflictException.class)
                .hasMessage("request_id 输入身份不匹配或不可验证，请更换 request_id");
    }
}
