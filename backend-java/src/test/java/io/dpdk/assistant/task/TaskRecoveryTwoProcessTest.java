package io.dpdk.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;

import io.dpdk.assistant.contracts.chat.SseEvent;
import io.dpdk.assistant.contracts.task.AdmissionInput;
import io.dpdk.assistant.contracts.task.RequestIdentity;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 验收 5：双 Java 进程 + 合成 PG——活任务不抢、属主停止后一方认领、
 * 非属主返回 pending 保持非终态、并发认领恰好一次。
 *
 * 第二进程是真实 JVM（TaskTwoProcessHelper，同 classpath），与测试进程共享
 * 独立测试库；时钟两进程同为固定 T0 的可控 TestClock，「时间流逝」按基线测试
 * 惯例以 updated_at 显式拨旧模拟（grace 窗口的真值在库行与 cutoff 的文本比较）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class TaskRecoveryTwoProcessTest {

    private static TaskTestHarness h;
    private static String subject;

    @BeforeAll
    static void setup() throws Exception {
        h = TaskTestHarness.create("twoproc");
        subject = "U20260000051";
        h.seedUser(subject, "[\"viewer\"]", 10000);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    /** 启动第二进程并等待收尾标记。 */
    private static ProcessOutput spawnHelper(String mode, String taskId, Path releaseFile)
            throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder builder = new ProcessBuilder(javaBin, "-cp", classpath,
                TaskTwoProcessHelper.class.getName(), h.database, mode,
                taskId == null ? "-" : taskId,
                releaseFile == null ? "-" : releaseFile.toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        List<String> lines = new CopyOnWriteArrayList<>();
        AtomicBoolean finished = new AtomicBoolean();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader reader1 = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader1.readLine()) != null) {
                    lines.add(line);
                    if ("DONE".equals(line)) {
                        finished.set(true);
                    }
                }
            } catch (Exception ignored) {
                // 进程结束时的流关闭
            }
        });
        return new ProcessOutput(process, lines, finished, reader);
    }

    private record ProcessOutput(Process process, List<String> lines, AtomicBoolean finished,
                                 Thread reader) {

        String value(String key, long timeoutSeconds) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (System.nanoTime() < deadline) {
                for (String line : lines) {
                    if (line.startsWith(key + "=")) {
                        return line.substring(key.length() + 1);
                    }
                }
                if (!process.isAlive()) {
                    break;
                }
                Thread.sleep(50);
            }
            throw new AssertionError("第二进程未输出 " + key + "；lines=" + lines);
        }

        void await(long timeoutSeconds) throws Exception {
            assertThat(finished.get()).as("第二进程在时限内收尾").isTrue();
            process.waitFor(10, TimeUnit.SECONDS);
            reader.join(5000);
        }

        /** 等待进程输出 DONE 标记（或进程退出）。 */
        void awaitDone(long timeoutSeconds) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            while (!finished.get() && process.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertThat(finished.get()).as("第二进程在时限内收尾").isTrue();
        }
    }

    private static TaskAdmitOutcome admitOrphanAble(String question, String sessionId) {
        return h.service.admitDetail(new AdmissionInput(
                new RequestIdentity(UUID.randomUUID().toString(),
                        TaskInputHash.compute(question), false, RequestIdentity.Purpose.chat),
                subject, sessionId, question, "", "test-model", question, 0));
    }

    @Test
    void liveTaskNotStolenOwnerStopClaimedAndPendingNonTerminal() throws Exception {
        String sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12);
        h.seedSession(sessionId, subject);
        TaskAdmitOutcome admit = admitOrphanAble("双进程守护", sessionId);
        String taskId = admit.taskId();

        // 属主进程（本进程）持续心跳：行保持新鲜
        Thread heartbeat = Thread.ofVirtual().start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    h.service.heartbeatOwned();
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        try {
            // 拨旧到 grace 之前：若无心跳则必成孤儿；属主心跳把它拨回新鲜
            h.makeOrphan(taskId, Duration.ofSeconds(120));
            h.service.heartbeatOwned();   // 注册表无句柄 → 不拨；显式模拟属主拨新：
            h.store.heartbeat(List.of(taskId));
            String fresh = h.columnValue("rag_requests", "updated_at", "id=?", taskId);

            // 第二进程扫描：活任务（updated_at 新鲜）不抢
            ProcessOutput scanner = spawnHelper("scan", null, null);
            scanner.awaitDone(60);
            assertThat(scanner.value("RECOVERED", 60)).isEqualTo("0");
            assertThat(h.columnValue("rag_requests", "status", "id=?", taskId))
                    .isEqualTo("pending");
            assertThat(h.columnValue("rag_requests", "updated_at", "id=?", taskId))
                    .isEqualTo(fresh);

            // 非属主接续：pending 行太新（grace 内）→ 如实回报 pending 非终态帧
            CollectingFrames frames = new CollectingFrames();
            h.service.taskEvents(taskId, subject).subscribe(frames);
            frames.await(10);
            assertThat(frames.names()).containsExactly("start", "done");
            assertThat(frames.raw().get(1)).contains("\"status\":\"pending\"")
                    .contains("\"terminal\":false");
        } finally {
            heartbeat.interrupt();
        }

        // 属主停止（心跳停）+ 行老化过 grace → 第二进程认领并重驱动到终态
        Path release = Files.createTempFile("j301-release", ".flag");
        Files.deleteIfExists(release);
        h.makeOrphan(taskId, Duration.ofSeconds(120));
        ProcessOutput recoverer = spawnHelper("recover-hold", taskId, release);
        String recovered = recoverer.value("RECOVERED", 60);
        assertThat(recovered).isEqualTo("1");
        // 执行体被文件门控挂起：另一进程此刻扫描/接续 → pending 非终态（B 在跑，行新鲜）
        CollectingFrames duringHold = new CollectingFrames();
        h.service.taskEvents(taskId, subject).subscribe(duringHold);
        duringHold.await(10);
        assertThat(duringHold.names().get(duringHold.names().size() - 1)).isEqualTo("done");
        assertThat(duringHold.raw().get(duringHold.raw().size() - 1))
                .contains("\"status\":\"pending\"").contains("\"terminal\":false");

        // 放行第二进程执行体 → 终态落库（双进程下恰好一次终态）
        Files.writeString(release, "go");
        recoverer.awaitDone(120);
        assertThat(recoverer.value("STATUS", 90)).isEqualTo("answered");
        assertThat(h.columnValue("rag_requests", "status", "id=?", taskId))
                .isEqualTo("answered");
        assertThat(h.columnValue("rag_requests", "reason", "id=?", taskId))
                .isEqualTo("supported");
        assertThat(h.service.loadInputs(taskId)).isEmpty();

        // 迟到接续（本进程视角）：终态重建回放（terminal:true）
        CollectingFrames after = new CollectingFrames();
        h.service.taskEvents(taskId, subject).subscribe(after);
        after.await(10);
        assertThat(after.names()).containsExactly("start", "sources", "delta", "done");
        assertThat(after.raw().get(after.raw().size() - 1)).contains("\"terminal\":true");
    }

    @Test
    void concurrentClaimAcrossProcessesIsExactlyOnce() throws Exception {
        String sessionId = "sess_" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12);
        h.seedSession(sessionId, subject);
        TaskAdmitOutcome admit = admitOrphanAble("认领竞速", sessionId);
        h.makeOrphan(admit.taskId(), Duration.ofSeconds(120));

        // 两个第二进程同时扫描：原子认领闸保证恰好一方重驱动（两者都带文件门控执行体）
        Path release = Files.createTempFile("j301-race", ".flag");
        Files.deleteIfExists(release);
        ProcessOutput p1 = spawnHelper("recover-hold", admit.taskId(), release);
        ProcessOutput p2 = spawnHelper("recover-hold", admit.taskId(), release);
        int recovered1 = Integer.parseInt(p1.value("RECOVERED", 60));
        int recovered2 = Integer.parseInt(p2.value("RECOVERED", 60));
        assertThat(recovered1 + recovered2).as("两进程恰有一方认领").isEqualTo(1);
        assertThat(h.countRows("rag_requests", "id=? AND status='pending'", admit.taskId()))
                .isEqualTo(1);

        Files.writeString(release, "go");
        p1.awaitDone(120);
        p2.awaitDone(60);
        assertThat(h.columnValue("rag_requests", "status", "id=?", admit.taskId()))
                .isEqualTo("answered");
        assertThat(h.countRows("assistant_messages", "metadata_json::text LIKE ?",
                "%" + admit.taskId() + "%")).isGreaterThanOrEqualTo(1);
    }

    /** 轻量收集订阅者（帧名 + 原始 data）。 */
    static final class CollectingFrames implements Flow.Subscriber<SseEvent> {

        private final List<String> raw = new CopyOnWriteArrayList<>();
        private final java.util.concurrent.CountDownLatch completed =
                new java.util.concurrent.CountDownLatch(1);

        @Override
        public void onSubscribe(Flow.Subscription s) {
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(SseEvent item) {
            raw.add(item.name() + ":" + item.dataJson());
        }

        @Override
        public void onError(Throwable t) {
            completed.countDown();
        }

        @Override
        public void onComplete() {
            completed.countDown();
        }

        List<String> names() {
            return raw.stream().map(f -> f.substring(0, f.indexOf(':'))).toList();
        }

        List<String> raw() {
            return raw;
        }

        void await(long seconds) throws InterruptedException {
            assertThat(completed.await(seconds, TimeUnit.SECONDS)).isTrue();
        }
    }
}
