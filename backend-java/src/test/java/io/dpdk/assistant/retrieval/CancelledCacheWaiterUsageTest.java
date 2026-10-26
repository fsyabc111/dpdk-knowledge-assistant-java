package io.dpdk.assistant.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import io.dpdk.assistant.model.SingleFlightTtlCache;
import io.dpdk.assistant.usage.UsageCore;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 缓存等待者取消没有执行权；不能消失，也不能伪记上游调用一次。 */
class CancelledCacheWaiterUsageTest {
    @Test
    void cancelledWaiterRecordsZeroWithoutCancellingSharedLoader() throws Exception {
        var cache=new SingleFlightTtlCache<String,String>(Duration.ofMinutes(1).toNanos(),4);
        var source=new CompletableFuture<String>();
        var ownerView=cache.getOrCreate("shared",ignored->source);
        var usage=new UsageCore.UsageRecorder("waiter","session");
        var started=new CountDownLatch(1);var finished=new CountDownLatch(1);
        var error=new AtomicReference<Throwable>();
        Thread waiter=Thread.ofVirtual().start(()->{
            started.countDown();
            try {
                RetrievalUsage.cached(cache,"shared",()->{throw new AssertionError("cache waiter cannot own loader");},
                        usage,"query_embed","synthetic-model",UsageCore.KIND_EMBEDDING);
            } catch(Throwable failure) { error.set(failure); }
            finally { finished.countDown(); }
        });
        assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
        waiter.interrupt();
        assertThat(finished.await(2,TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isInstanceOf(CancellationException.class);
        assertThat(source.isCancelled()).isFalse();
        assertThat(ownerView.isDone()).isFalse();
        source.complete("shared result");
        assertThat(ownerView.get(2,TimeUnit.SECONDS)).isEqualTo("shared result");
        assertThat(usage.entries()).hasSize(1);
        assertThat(usage.entries().getFirst().usageSource()).isEqualTo("cache_hit");
        assertThat(usage.entries().getFirst().callCount()).isZero();
        assertThat(usage.entries().getFirst().totalTokens()).isNull();
    }
}
