package io.dpdk.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 同 key 单飞合并 + TTL/LRU/失败语义（对齐 AsyncTTLCache）。 */
class SingleFlightTtlCacheTest {

    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private static CompletableFuture<String> supplyAsync(ExecutorService pool, java.util.function.Supplier<String> supplier) {
        return CompletableFuture.supplyAsync(supplier, pool);
    }

    @Test
    void concurrentSameKeyRunsFactoryOnce() throws Exception {
        SingleFlightTtlCache<String, String> cache = new SingleFlightTtlCache<>(60_000_000_000L, 16);
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        AtomicInteger runs = new AtomicInteger();

        var first = cache.getOrCreate("q", key -> {
            runs.incrementAndGet();
            return supplyAsync(pool, () -> {
                factoryEntered.countDown();
                try {
                    releaseFactory.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "结果";
            });
        });
        // 等 factory 真正启动后再并发第二个调用方。
        assertThat(factoryEntered.await(5, TimeUnit.SECONDS)).isTrue();
        var second = cache.getOrCreate("q", key -> {
            runs.incrementAndGet();
            return CompletableFuture.completedFuture("不该走到这里");
        });

        releaseFactory.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo("结果");
        assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo("结果");
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void cachedValueIsReusedUntilTtlExpires() throws Exception {
        long[] clock = {1_000_000_000L};
        SingleFlightTtlCache<String, String> cache =
                new SingleFlightTtlCache<>(1_000_000_000L, 16, () -> clock[0]);
        AtomicInteger runs = new AtomicInteger();

        cache.getOrCreate("k", key -> CompletableFuture.completedFuture("v" + runs.incrementAndGet())).get();
        String again = cache.getOrCreate("k", key -> CompletableFuture.completedFuture("v" + runs.incrementAndGet())).get();
        assertThat(again).isEqualTo("v1"); // TTL 内复用
        assertThat(runs.get()).isEqualTo(1);

        clock[0] += 1_000_000_000L; // 过期
        String renewed = cache.getOrCreate("k", key -> CompletableFuture.completedFuture("v" + runs.incrementAndGet())).get();
        assertThat(renewed).isEqualTo("v2");
        assertThat(runs.get()).isEqualTo(2);
    }

    @Test
    void failureIsNotCachedAndInflightCleared() throws Exception {
        SingleFlightTtlCache<String, String> cache = new SingleFlightTtlCache<>(60_000_000_000L, 16);
        AtomicInteger attempts = new AtomicInteger();

        var failing = cache.getOrCreate("k", key -> supplyAsync(pool, () -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("上游失败");
        }));
        assertThatThrownBy(() -> failing.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);

        // 失败后 inflight 已清：下一次调用重新执行 factory 并成功。
        var ok = cache.getOrCreate("k", key -> CompletableFuture.completedFuture("恢复"));
        assertThat(ok.get(5, TimeUnit.SECONDS)).isEqualTo("恢复");
        assertThat(attempts.get()).isEqualTo(1);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void callerCancelDoesNotCancelSharedWork() throws Exception {
        SingleFlightTtlCache<String, String> cache = new SingleFlightTtlCache<>(60_000_000_000L, 16);
        CountDownLatch factoryDone = new CountDownLatch(1);

        CompletableFuture<String> view = cache.getOrCreate("k", key -> supplyAsync(pool, () -> {
            try {
                Thread.sleep(150);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            String v = "共享结果";
            factoryDone.countDown();
            return v;
        }));
        view.cancel(true); // 调用方取消自己的等待

        assertThat(factoryDone.await(5, TimeUnit.SECONDS)).isTrue(); // 共享装载照常完成
        // 后来的调用方拿到已缓存的值，factory 不再执行。
        String cached = cache.getOrCreate("k", key -> CompletableFuture.completedFuture("重新装载")).get();
        assertThat(cached).isEqualTo("共享结果");
    }

    @Test
    void maxsizeEvictsEldestEntry() throws Exception {
        long[] clock = {0L};
        SingleFlightTtlCache<Integer, String> cache =
                new SingleFlightTtlCache<>(1_000_000_000L, 2, () -> clock[0]);
        clock[0] = 1L;
        cache.getOrCreate(1, k -> CompletableFuture.completedFuture("a")).get();
        clock[0] = 2L;
        cache.getOrCreate(2, k -> CompletableFuture.completedFuture("b")).get();
        clock[0] = 3L;
        cache.getOrCreate(3, k -> CompletableFuture.completedFuture("c")).get(); // 触发淘汰 a
        assertThat(cache.size()).isEqualTo(2);
        clock[0] = 4L;
        String afterEviction = cache.getOrCreate(1, k -> CompletableFuture.completedFuture("a2")).get();
        assertThat(afterEviction).isEqualTo("a2"); // a 已被淘汰，重新装载
        String stillCached = cache.getOrCreate(3, k -> CompletableFuture.completedFuture("c2")).get();
        assertThat(stillCached).isEqualTo("c"); // c 仍在
    }
}
