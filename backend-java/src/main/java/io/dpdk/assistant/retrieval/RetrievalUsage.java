package io.dpdk.assistant.retrieval;

import io.dpdk.assistant.model.ModelUsageLedger;
import io.dpdk.assistant.model.SingleFlightTtlCache;
import io.dpdk.assistant.usage.UsageCore;
import java.util.LinkedHashMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/** 检索显式请求计量桥；缓存只存值，不存recorder，不依赖线程上下文。 */
final class RetrievalUsage {
    private static final ExecutorService LOADERS = Executors.newVirtualThreadPerTaskExecutor();
    private RetrievalUsage() { }

    static <K, V> V cached(SingleFlightTtlCache<K, V> cache, K key, Supplier<V> loader,
            UsageCore.UsageRecorder recorder, String stage, String model, String kind) {
        AtomicBoolean ran = new AtomicBoolean();
        var future = cache.getOrCreate(key, ignored -> {
            ran.set(true); // 仅创建真实loader的请求拥有其调用账；同key等待者记零调用。
            return CompletableFuture.supplyAsync(loader, LOADERS);
        });
        V value;
        try { value = future.get(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // 仅取消调用方等待，不取消其他请求共享的外部调用（Python asyncio.shield）。
            throw new CancellationException("检索缓存等待被取消");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("检索外部调用失败", cause);
        }
        if (!ran.get()) skipped(recorder, stage, UsageCore.SOURCE_CACHE_HIT, model, kind);
        return value;
    }

    static <V> V tracked(UsageCore.UsageRecorder recorder, Function<ModelUsageLedger, V> call) {
        ModelUsageLedger ledger = recorder == null ? null : ModelUsageLedger.create();
        try { return call.apply(ledger); }
        finally {
            if (recorder != null) {
                synchronized (recorder) {
                    for (var entry : ledger.entries()) {
                        if (ModelUsageLedger.ZERO_CALL_SOURCES.contains(entry.usageSource())) {
                            recorder.recordSkipped(entry.stage(), entry.usageSource(), entry.model(), entry.kind());
                        } else if (ModelUsageLedger.SOURCE_OPERATION.equals(entry.usageSource())) {
                            recorder.recordOperation(entry.kind(), entry.stage(), millis(entry.durationMs()), entry.callCount());
                        } else {
                            var tokens = new LinkedHashMap<String, Object>();
                            tokens.put("prompt_tokens", entry.promptTokens());
                            tokens.put("completion_tokens", entry.completionTokens());
                            tokens.put("total_tokens", entry.totalTokens());
                            recorder.recordLlm(entry.stage(), entry.model(), tokens,
                                    millis(entry.durationMs()), entry.kind(), entry.callCount());
                        }
                    }
                }
            }
        }
    }

    static void skipped(UsageCore.UsageRecorder recorder, String stage, String source, String model, String kind) {
        if (recorder != null) {
            synchronized (recorder) { recorder.recordSkipped(stage, source, model, kind); }
        }
    }

    private static Integer millis(Long value) {
        return value == null ? null : (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
    }
}
