package io.dpdk.assistant.usage;

import static org.assertj.core.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/** checkpoint与增量订阅在同一monitor切分，锁外交付，ack显式等待所有已登记生产者。 */
class UsageReceiptCheckpointTest {
    @Test
    void checkpointAndLateReceiptsAreDisjointAndCompletionWaitsForProducerAndSink() throws Exception {
        var usage=new UsageCore.UsageRecorder("owner","session");
        var producer=usage.retainProducer();
        usage.recordLlm("planner","model",Map.of("total_tokens",3));
        var late=new CopyOnWriteArrayList<UsageCore.UsageEntry>();
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var checkpoint=usage.checkpointAndSubscribe(entry->{
            assertThat(Thread.holdsLock(usage)).isFalse();
            late.add(entry);entered.countDown();
            try { if(!release.await(2,TimeUnit.SECONDS)) throw new IllegalStateException("test sink gate"); }
            catch(InterruptedException e) { Thread.currentThread().interrupt();throw new IllegalStateException(e); }
        });
        var completion=checkpoint.completion().toCompletableFuture();
        AtomicBoolean completionUnderLock=new AtomicBoolean();
        checkpoint.completion().thenRun(()->completionUnderLock.set(Thread.holdsLock(usage)));
        assertThat(checkpoint.entries()).hasSize(1);
        checkpoint.acknowledge();
        assertThat(completion.isDone()).isFalse();
        try(var workers=Executors.newVirtualThreadPerTaskExecutor()) {
            var appended=workers.submit(()->usage.recordLlm("query_embed","embedding",Map.of("total_tokens",9)));
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            producer.close();producer.close(); // lease只关闭一次。
            assertThat(completion.isDone()).isFalse();
            release.countDown();appended.get(2,TimeUnit.SECONDS);
        } finally { release.countDown();producer.close(); }
        completion.get(2,TimeUnit.SECONDS);
        assertThat(checkpoint.entries()).hasSize(1);
        assertThat(late).hasSize(1);
        assertThat(late.getFirst().stage()).isEqualTo("query_embed");
        assertThat(usage.entries()).hasSize(2);
        assertThat(completionUnderLock.get()).isFalse();
        assertThatThrownBy(usage::retainProducer).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->usage.recordOperation("mcp","late-unleased",1,1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void concurrentCheckpointNeverLosesOrDuplicatesAnyReceipt() throws Exception {
        var usage=new UsageCore.UsageRecorder();
        var producer=usage.retainProducer();
        var start=new CountDownLatch(1);
        var incremental=new CopyOnWriteArrayList<UsageCore.UsageEntry>();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> writers=new java.util.ArrayList<>();
            for(int i=0;i<32;i++) {
                final int number=i;
                writers.add(pool.submit(()->{
                    start.await();usage.recordLlm("stage-"+number,"model",Map.of("total_tokens",number));return null;
                }));
            }
            start.countDown();
            var checkpoint=usage.checkpointAndSubscribe(incremental::add);
            for(var writer:writers) writer.get(2,TimeUnit.SECONDS);
            producer.close();checkpoint.acknowledge();
            checkpoint.completion().toCompletableFuture().get(2,TimeUnit.SECONDS);
            var received=new java.util.ArrayList<>(checkpoint.entries());received.addAll(incremental);
            assertThat(received).hasSize(32);
            assertThat(received.stream().map(UsageCore.UsageEntry::stage).distinct().count()).isEqualTo(32);
        } finally { producer.close(); }
    }

    @Test
    void failedLatePersistenceFailsAckWithoutThrowingIntoBusinessCall() throws Exception {
        var usage=new UsageCore.UsageRecorder();var producer=usage.retainProducer();
        var checkpoint=usage.checkpointAndSubscribe(entry->{throw new IllegalStateException("synthetic sink failure");});
        checkpoint.acknowledge();
        assertThatCode(()->usage.recordLlm("query_embed","model",null)).doesNotThrowAnyException();
        producer.close();
        assertThatThrownBy(()->checkpoint.completion().toCompletableFuture().get(2,TimeUnit.SECONDS))
                .hasCauseInstanceOf(IllegalStateException.class);
    }
}
