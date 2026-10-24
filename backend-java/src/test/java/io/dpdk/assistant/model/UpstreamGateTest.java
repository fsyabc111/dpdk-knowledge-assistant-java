package io.dpdk.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 上游并发门：上限、异常释放、等待中断释放。 */
class UpstreamGateTest {

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    @Test
    void gateCapsConcurrentUpstreamAttempts() throws Exception {
        UpstreamGate gate = new UpstreamGate(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger current = new AtomicInteger();

        List<Future<Void>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            futures.add(pool.submit(() -> {
                try (UpstreamGate.Permit ignored = gate.acquire()) {
                    int now = current.incrementAndGet();
                    maxConcurrent.accumulateAndGet(now, Math::max);
                    release.await();
                    current.decrementAndGet();
                }
                return null;
            }));
        }
        Thread.sleep(200); // 让 4 个任务都进入竞争
        release.countDown();
        for (Future<Void> f : futures) {
            f.get(5, TimeUnit.SECONDS);
        }
        assertThat(maxConcurrent.get()).isEqualTo(2);
        assertThat(gate.availablePermits()).isEqualTo(2);
    }

    @Test
    void permitIsReleasedOnFailure() throws Exception {
        UpstreamGate gate = new UpstreamGate(1);
        assertThatThrownBy(() -> {
            try (UpstreamGate.Permit ignored = gate.acquire()) {
                throw new ModelHttpException(500, "", null);
            }
        }).isInstanceOf(ModelHttpException.class);
        assertThat(gate.availablePermits()).isEqualTo(1);
    }

    @Test
    void interruptedWaitConsumesNoPermit() throws Exception {
        UpstreamGate gate = new UpstreamGate(1);
        try (UpstreamGate.Permit held = gate.acquire()) {
            Thread waiter = new Thread(() -> {
                try {
                    gate.acquire();
                } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                }
            });
            waiter.start();
            Thread.sleep(150);
            waiter.interrupt();
            waiter.join(5000);
            assertThat(waiter.isInterrupted()).isTrue();
        }
        // 被中断的等待没有偷走许可。
        assertThat(gate.availablePermits()).isEqualTo(1);
    }
}
