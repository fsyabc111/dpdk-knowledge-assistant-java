package io.dpdk.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dpdk.assistant.contracts.identity.AccountWriteGuard;
import io.dpdk.assistant.contracts.platform.DbTx;
import io.dpdk.assistant.contracts.session.MessageInput;
import io.dpdk.assistant.contracts.session.SessionPort;
import io.dpdk.assistant.contracts.task.AdmissionInput;
import io.dpdk.assistant.contracts.task.RequestIdentity;
import io.dpdk.assistant.contracts.usage.QuotaParticipant;
import io.dpdk.assistant.task.TaskExceptions.TaskForbiddenException;
import io.dpdk.assistant.usage.QuotaExceededException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 验收 1：同 request 并发受理只产生一任务/一用户消息/一次扣额；注入任一步 DB
 * 失败，全事务回滚（真实 PG 事务，不是 mock）。
 *
 * 注入方式：在受理事务的各步骤协作端口上挂抛出装饰器（账号锁/临时输入寄存/
 * 用户消息/会话 touch/配额），失败点之后的写入与之前的写入一并回滚；
 * 唯一任务步骤本身由并发唯一性与冲突接续用例覆盖。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class TaskAdmissionTransactionTest {

    private static final LocalDate DAY = LocalDate.parse("2026-10-25");

    private static TaskTestHarness h;
    private static String subject;

    @BeforeAll
    static void setup() throws Exception {
        h = TaskTestHarness.create("admit");
        subject = "U20260000001";
        h.seedUser(subject, "[\"viewer\"]", 10000);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    private static AdmissionInput input(String requestId, String question, String sessionId) {
        return new AdmissionInput(
                new RequestIdentity(requestId, TaskInputHash.compute(question), false,
                        RequestIdentity.Purpose.chat),
                subject, sessionId, question, "上下文", "test-model", question, 200);
    }

    private static AdmissionInput newInput(String question, String sessionId) {
        return input(UUID.randomUUID().toString(), question, sessionId);
    }

    /** 每用例独立会话（用户消息计数不被其他用例污染）。 */
    private static String newSession() throws Exception {
        String id = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        h.seedSession(id, subject);
        return id;
    }

    /** 当前主体 chat 计数（日配额增量断言基准）。 */
    private int chatCount() throws Exception {
        String value = h.columnValue("usage_counters", "chat_count",
                "subject=? AND kind='chat'", subject);
        return value == null ? 0 : Integer.parseInt(value);
    }

    /* ------------------------------------------------------------------ */
    /* 并发唯一性                                                            */
    /* ------------------------------------------------------------------ */

    @Test
    void concurrentAdmitProducesExactlyOneTaskMessageAndCharge() throws Exception {
        String sessionId = newSession();
        int chatBefore = chatCount();
        String requestId = UUID.randomUUID().toString();
        AdmissionInput shared = input(requestId, "并发受理问题", sessionId);
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<TaskAdmitOutcome> outcomes = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    outcomes.add(h.service.admitDetail(shared));
                } catch (Exception e) {
                    outcomes.add(new TaskAdmitOutcome("error:" + e.getClass().getSimpleName(),
                            Optional.empty(), Optional.empty()));
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        long created = outcomes.stream().filter(TaskAdmitOutcome::created).count();
        long attachedOrReplayed = outcomes.stream()
                .filter(o -> TaskAdmitOutcome.ATTACHED.equals(o.outcome())
                        || TaskAdmitOutcome.REPLAYED.equals(o.outcome()))
                .count();
        assertThat(created).isEqualTo(1);
        assertThat(created + attachedOrReplayed).isEqualTo(threads);
        String winnerTaskId = outcomes.stream().filter(TaskAdmitOutcome::created)
                .findFirst().orElseThrow().taskId();

        // 一任务 / 一用户消息 / 一次扣额（冲突接续不重扣）
        assertThat(h.countRows("rag_requests", "request_id=?", requestId)).isEqualTo(1);
        assertThat(h.countRows("assistant_messages", "session_id=? AND role='user'",
                sessionId)).isEqualTo(1);
        assertThat(chatCount()).isEqualTo(chatBefore + 1);
        assertThat(h.countRows("rag_task_inputs", "task_id=?", winnerTaskId)).isEqualTo(1);
        // 用户消息 metadata 指向同一任务
        assertThat(h.columnValue("assistant_messages", "metadata_json",
                "session_id=? AND role='user'", sessionId)).contains(winnerTaskId);
    }

    /* ------------------------------------------------------------------ */
    /* 步骤失败注入 → 全回滚                                                  */
    /* ------------------------------------------------------------------ */

    @Test
    void accountLockFailureRollsBackEverything() throws Exception {
        String sessionId = newSession();
        int chatBefore = chatCount();
        AccountWriteGuard denying = (tx, sub) -> false;
        RagTaskService failing = newFailingService(denying, null, null, null);
        AdmissionInput in = newInput("锁失败问题", sessionId);
        assertThatThrownBy(() -> failing.admitDetail(in))
                .isInstanceOf(TaskForbiddenException.class)
                .hasMessage("账号已停用，不能受理新任务");
        assertThat(h.countRows("rag_requests", "request_id=?", in.identity().requestId()))
                .isZero();
        assertThat(h.countRows("assistant_messages", "session_id=?", sessionId)).isZero();
        assertThat(chatCount()).isEqualTo(chatBefore);
    }

    @Test
    void inputsStoreFailureRollsBackRequestAndEverythingAfter() throws Exception {
        // 注入点：临时输入寄存失败（唯一任务已写入）——全笔回滚不留 pending 行。
        String sessionId = newSession();
        int chatBefore = chatCount();
        TaskStore failingStore = new TaskStore(h.runner, h.clock, h.counters,
                h.settings.inputTtl()) {
            @Override
            public void storeInputs(DbTx tx, String taskId, String question, String attachmentName,
                                    String attachmentSuffix, String sourceText, String sourceOrigin)
                    throws java.sql.SQLException {
                throw new IllegalStateException("synthetic inputs failure");
            }
        };
        RagTaskService failing = newFailingService(null, failingStore, null, null);
        AdmissionInput in = newInput("寄存失败问题", sessionId);
        assertThatThrownBy(() -> failing.admitDetail(in))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("synthetic inputs failure");
        assertThat(h.countRows("rag_requests", "request_id=?", in.identity().requestId()))
                .isZero();
        assertThat(h.countRows("assistant_messages", "session_id=?", sessionId)).isZero();
        assertThat(chatCount()).isEqualTo(chatBefore);
    }

    @Test
    void messageAppendFailureRollsBackTaskAndInputs() throws Exception {
        String sessionId = newSession();
        int chatBefore = chatCount();
        SessionPort failingAppend = new SessionPort() {
            @Override
            public io.dpdk.assistant.contracts.session.SessionId ensureOwned(
                    Optional<String> requestedId, String title, String sub) {
                return h.sessionPort.ensureOwned(requestedId, title, sub);
            }

            @Override
            public void appendUser(DbTx tx, MessageInput msg) {
                throw new IllegalStateException("synthetic message failure");
            }

            @Override
            public void touch(DbTx tx, String sid, String iso) {
                h.sessionPort.touch(tx, sid, iso);
            }

            @Override
            public void discardEmpty(String sid, String sub) {
                h.sessionPort.discardEmpty(sid, sub);
            }
        };
        RagTaskService failing = newFailingService(null, null, failingAppend, null);
        AdmissionInput in = newInput("消息失败问题", sessionId);
        assertThatThrownBy(() -> failing.admitDetail(in))
                .hasMessageContaining("synthetic message failure");
        assertThat(h.countRows("rag_requests", "request_id=?", in.identity().requestId()))
                .isZero();
        assertThat(h.countRows("assistant_messages", "session_id=?", sessionId)).isZero();
        assertThat(chatCount()).isEqualTo(chatBefore);
    }

    @Test
    void sessionTouchFailureRollsBackEverything() throws Exception {
        String sessionId = newSession();
        int chatBefore = chatCount();
        SessionPort failingTouch = new SessionPort() {
            @Override
            public io.dpdk.assistant.contracts.session.SessionId ensureOwned(
                    Optional<String> requestedId, String title, String sub) {
                return h.sessionPort.ensureOwned(requestedId, title, sub);
            }

            @Override
            public void appendUser(DbTx tx, MessageInput msg) {
                h.sessionPort.appendUser(tx, msg);
            }

            @Override
            public void touch(DbTx tx, String sid, String iso) {
                throw new IllegalStateException("synthetic touch failure");
            }

            @Override
            public void discardEmpty(String sid, String sub) {
                h.sessionPort.discardEmpty(sid, sub);
            }
        };
        RagTaskService failing = newFailingService(null, null, failingTouch, null);
        AdmissionInput in = newInput("touch失败问题", sessionId);
        assertThatThrownBy(() -> failing.admitDetail(in))
                .hasMessageContaining("synthetic touch failure");
        // 消息在 touch 之前：同样必须回滚（嵌套参与事务不先提交）。
        assertThat(h.countRows("assistant_messages", "session_id=?", sessionId)).isZero();
        assertThat(h.countRows("rag_requests", "request_id=?", in.identity().requestId()))
                .isZero();
        assertThat(chatCount()).isEqualTo(chatBefore);
    }

    @Test
    void quotaExceededRollsBackWholeAdmission() throws Exception {
        String sessionId = newSession();
        int chatBefore = chatCount();
        QuotaParticipant throwing = new QuotaParticipant() {
            @Override
            public void countAndCheck(DbTx tx, String sub, LocalDate utcDay, String kind,
                                      int quota) {
                throw new QuotaExceededException(kind, 1, 60);
            }
        };
        RagTaskService failing = newFailingService(null, null, null, throwing);
        AdmissionInput in = newInput("配额失败问题", sessionId);
        assertThatThrownBy(() -> failing.admitDetail(in))
                .isInstanceOf(QuotaExceededException.class);
        assertThat(h.countRows("rag_requests", "request_id=?", in.identity().requestId()))
                .isZero();
        assertThat(h.countRows("assistant_messages", "session_id=?", sessionId)).isZero();
        assertThat(chatCount()).isEqualTo(chatBefore);
    }

    /* ------------------------------------------------------------------ */
    /* 冲突接续不重扣                                                         */
    /* ------------------------------------------------------------------ */

    @Test
    void conflictAttachDoesNotDoubleChargeOrDuplicateMessage() throws Exception {
        String sessionId = newSession();
        int chatBefore = chatCount();
        AdmissionInput first = newInput("原始问题", sessionId);
        TaskAdmitOutcome won = h.service.admitDetail(first);
        assertThat(won.created()).isTrue();

        AdmissionInput loser = input(first.identity().requestId(), "原始问题", sessionId);
        TaskAdmitOutcome attached = h.service.admitDetail(loser);
        assertThat(attached.outcome()).isEqualTo(TaskAdmitOutcome.ATTACHED);
        assertThat(attached.taskId()).isEqualTo(won.taskId());

        assertThat(h.countRows("rag_requests", "request_id=?",
                first.identity().requestId())).isEqualTo(1);
        assertThat(h.countRows("assistant_messages", "session_id=? AND role='user'",
                sessionId)).isEqualTo(1);
        assertThat(chatCount()).isEqualTo(chatBefore + 1);
    }

    /* ------------------------------------------------------------------ */
    /* 装配工具                                                              */
    /* ------------------------------------------------------------------ */

    /** 组装局部失败的服务：非 null 的装饰器替换对应协作端口，其余用真实实现。 */
    private static RagTaskService newFailingService(AccountWriteGuard guardOverride,
                                                    TaskStore storeOverride,
                                                    SessionPort sessionOverride,
                                                    QuotaParticipant quotaOverride) {
        return new RagTaskService(
                storeOverride != null ? storeOverride : h.store,
                h.taskRunner, h.recovery, h.streams,
                guardOverride != null ? guardOverride : h.guard,
                sessionOverride != null ? sessionOverride : h.sessionPort,
                quotaOverride != null ? quotaOverride : h.counters,
                h.clock, h.runner, h.settings, new RagTaskService.IdGenerator() {
                    private final AtomicInteger n = new AtomicInteger();

                    @Override
                    public String hex() {
                        return String.format("%08x%s", n.incrementAndGet(),
                                UUID.randomUUID().toString().replace("-", "").substring(0, 24));
                    }
                });
    }
}
