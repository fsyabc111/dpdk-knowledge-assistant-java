package io.dpdk.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 对齐Python populate identity：clear淘汰旧flight的写权限，不取消其已存在waiter。 */
class SingleFlightClearIdentityTest {
    @Test
    void clearedOldLoaderFinishesItsWaitersWithoutRefillingCache() throws Exception {
        var cache=new SingleFlightTtlCache<String,String>(Duration.ofMinutes(1).toNanos(),4);
        var oldRaw=new CompletableFuture<String>();
        var oldView=cache.getOrCreate("key",key->oldRaw);
        var sibling=cache.getOrCreate("key",key->{throw new AssertionError("must share old loader");});
        cache.clear();
        oldRaw.complete("old result");
        assertThat(oldView.get(2,TimeUnit.SECONDS)).isEqualTo("old result");
        assertThat(sibling.get(2,TimeUnit.SECONDS)).isEqualTo("old result");
        assertThat(cache.size()).as("clear后的旧loader没有回填权限").isZero();
        assertThat(cache.getOrCreate("key",key->CompletableFuture.completedFuture("fresh result")).get(2,TimeUnit.SECONDS))
                .isEqualTo("fresh result");
    }

    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void oldSuccessNeverWritesOverOrDetachesNewFlight(boolean newCompletesFirst) throws Exception {
        var cache=new SingleFlightTtlCache<String,String>(Duration.ofMinutes(1).toNanos(),4);
        var oldRaw=new CompletableFuture<String>();var newRaw=new CompletableFuture<String>();
        var factories=new AtomicInteger();
        var oldView=cache.getOrCreate("key",key->{factories.incrementAndGet();return oldRaw;});
        cache.clear();
        var current=cache.getOrCreate("key",key->{factories.incrementAndGet();return newRaw;});
        if(newCompletesFirst) {
            newRaw.complete("new result");
            assertThat(current.get(2,TimeUnit.SECONDS)).isEqualTo("new result");
        }
        oldRaw.complete("old result");
        assertThat(oldView.get(2,TimeUnit.SECONDS)).isEqualTo("old result");
        var joined=cache.getOrCreate("key",key->{
            factories.incrementAndGet();return CompletableFuture.completedFuture("wrong third load");
        });
        if(!newCompletesFirst) {
            assertThat(cache.size()).as("新flight未完成，旧结果不能冒充cache hit").isZero();
            assertThat(joined.isDone()).isFalse();
            newRaw.complete("new result");
        }
        assertThat(current.get(2,TimeUnit.SECONDS)).isEqualTo("new result");
        assertThat(joined.get(2,TimeUnit.SECONDS)).as("旧成功不覆盖新缓存，也不移除新inflight").isEqualTo("new result");
        assertThat(factories.get()).isEqualTo(2);
        assertThat(cache.size()).isEqualTo(1);
    }

    @Test
    void oldFailureFinishesOldWaiterWithoutRemovingNewFlight() throws Exception {
        var cache=new SingleFlightTtlCache<String,String>(Duration.ofMinutes(1).toNanos(),4);
        var oldRaw=new CompletableFuture<String>();var newRaw=new CompletableFuture<String>();
        var oldView=cache.getOrCreate("key",key->oldRaw);
        cache.clear();
        var current=cache.getOrCreate("key",key->newRaw);
        oldRaw.completeExceptionally(new IllegalStateException("old synthetic failure"));
        assertThatThrownBy(()->oldView.get(2,TimeUnit.SECONDS)).hasCauseInstanceOf(IllegalStateException.class);
        var joined=cache.getOrCreate("key",key->{throw new AssertionError("old completion must not detach new flight");});
        assertThat(joined.isDone()).isFalse();
        assertThat(newRaw.isCancelled()).isFalse();
        newRaw.complete("new result");
        assertThat(current.get(2,TimeUnit.SECONDS)).isEqualTo("new result");
        assertThat(joined.get(2,TimeUnit.SECONDS)).isEqualTo("new result");
        assertThat(cache.size()).isEqualTo(1);
    }
}
