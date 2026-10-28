package io.dpdk.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 消费者终态回调内重入；闩锁禁止producer回调提前退栈，不靠sleep放大时序。 */
class SingleFlightCompletionOrderTest {
    @Test
    void failedViewCanImmediatelyRetryFreshInsideItsTerminalCallback() throws Exception {
        var cache=new SingleFlightTtlCache<String,String>(Duration.ofMinutes(1).toNanos(),4);
        var raw=new CompletableFuture<String>();
        var factories=new AtomicInteger();
        var failed=cache.getOrCreate("key",key->{factories.incrementAndGet();return raw;});
        var retry=new AtomicReference<CompletableFuture<String>>();
        var consumerEntered=new CountDownLatch(1);var releaseConsumer=new CountDownLatch(1);
        var callback=failed.whenComplete((value,error)->{
            retry.set(cache.getOrCreate("key",key->{
                factories.incrementAndGet();return CompletableFuture.completedFuture("fresh result");
            }));
            consumerEntered.countDown();
            awaitRelease(releaseConsumer);
        });
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var completed=workers.submit(()->raw.completeExceptionally(new IllegalStateException("synthetic first failure")));
            try {
                assertThat(consumerEntered.await(2,TimeUnit.SECONDS)).isTrue();
                assertThat(factories.get()).as("首个失败被观察到前必须已移除inflight，重入立即新factory").isEqualTo(2);
                assertThat(retry.get().get(2,TimeUnit.SECONDS)).isEqualTo("fresh result");
                assertThat(cache.size()).isEqualTo(1);
            } finally {releaseConsumer.countDown();}
            completed.get(2,TimeUnit.SECONDS);
        } finally {releaseConsumer.countDown();}
        assertThat(callback.isCompletedExceptionally()).isTrue();
    }

    @Test
    void successfulViewAlreadyHasCachedValueInsideItsTerminalCallback() throws Exception {
        var cache=new SingleFlightTtlCache<String,String>(Duration.ofMinutes(1).toNanos(),4);
        var raw=new CompletableFuture<String>();
        var first=cache.getOrCreate("key",key->raw);
        var cachedSize=new AtomicInteger(-1);var unexpectedFactory=new AtomicInteger();
        var joined=new AtomicReference<CompletableFuture<String>>();
        var consumerEntered=new CountDownLatch(1);var releaseConsumer=new CountDownLatch(1);
        first.thenAccept(value->{
            cachedSize.set(cache.size());
            joined.set(cache.getOrCreate("key",key->{
                unexpectedFactory.incrementAndGet();return CompletableFuture.completedFuture("wrong replacement");
            }));
            consumerEntered.countDown();awaitRelease(releaseConsumer);
        });
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var completed=workers.submit(()->raw.complete("published value"));
            try {
                assertThat(consumerEntered.await(2,TimeUnit.SECONDS)).isTrue();
                assertThat(cachedSize.get()).as("成功终态可见之前必须已写缓存").isEqualTo(1);
                assertThat(joined.get().get(2,TimeUnit.SECONDS)).isEqualTo("published value");
                assertThat(unexpectedFactory.get()).isZero();
            } finally {releaseConsumer.countDown();}
            completed.get(2,TimeUnit.SECONDS);
        } finally {releaseConsumer.countDown();}
    }

    @Test
    void synchronousFactoryCompletionClosesStateBeforeReturningView() throws Exception {
        var cache=new SingleFlightTtlCache<String,String>(Duration.ofMinutes(1).toNanos(),4);
        var failure=new IllegalStateException("synchronous failure");
        var first=cache.getOrCreate("failed",key->CompletableFuture.failedFuture(failure));
        assertThat(first.isCompletedExceptionally()).isTrue();
        assertThat(cache.getOrCreate("failed",key->CompletableFuture.completedFuture("recovered")).get())
                .isEqualTo("recovered");
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.getOrCreate("success",key->CompletableFuture.completedFuture("value")).get()).isEqualTo("value");
        assertThat(cache.size()).isEqualTo(2);
    }

    @Test
    void cancellingOneViewDoesNotCancelRawLoaderOrSiblingView() throws Exception {
        var cache=new SingleFlightTtlCache<String,String>(Duration.ofMinutes(1).toNanos(),4);
        var raw=new CompletableFuture<String>();
        var first=cache.getOrCreate("key",key->raw);
        var sibling=cache.getOrCreate("key",key->{throw new AssertionError("must join existing loader");});
        assertThat(first.cancel(true)).isTrue();
        assertThat(raw.isDone()).isFalse();
        assertThat(sibling.isDone()).isFalse();
        raw.complete("shared result");
        assertThat(sibling.get(2,TimeUnit.SECONDS)).isEqualTo("shared result");
        assertThat(cache.size()).isEqualTo(1);
        assertThat(cache.getOrCreate("key",key->{throw new AssertionError("must hit completed cache");}).get())
                .isEqualTo("shared result");
    }

    private static void awaitRelease(CountDownLatch release) {
        try {
            if(!release.await(5,TimeUnit.SECONDS))throw new IllegalStateException("test consumer gate timeout");
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);
        }
    }
}
