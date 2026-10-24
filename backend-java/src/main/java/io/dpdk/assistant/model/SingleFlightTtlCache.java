package io.dpdk.assistant.model;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * TTL 缓存 + 同 key 单飞合并（对齐 backend/app/core/async_cache.py 的 AsyncTTLCache）。
 *
 * 语义要点（源注释锁死）：
 * <ul>
 *   <li>并发同 key 调用只触发一次 factory，其余等待共享结果；</li>
 *   <li>单个调用方取消自己的视图不得取消共享工作（asyncio.shield 对位：视图 future
 *       与源 future 解耦，取消视图不影响源）；</li>
 *   <li>factory 失败：从 inflight 移除并透传给所有等待者，不缓存失败值；</li>
 *   <li>TTL 过期后移除；命中刷新 LRU 位；超过 maxsize 淘汰最老条目。</li>
 * </ul>
 *
 * @param <K> 键
 * @param <V> 值
 */
public final class SingleFlightTtlCache<K, V> {

    private record Cached<V>(long expiresAtNanos, V value) {
    }

    private final long ttlNanos;
    private final int maxsize;
    private final LongSupplier clockNanos;
    private final Map<K, Cached<V>> values = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<K, CompletableFuture<V>> inflight = new LinkedHashMap<>();

    /** 可注入单调时钟（默认 System.nanoTime），便于确定性测试。 */
    @FunctionalInterface
    public interface LongSupplier {
        long get();
    }

    public SingleFlightTtlCache(long ttlNanos, int maxsize) {
        this(ttlNanos, maxsize, System::nanoTime);
    }

    public SingleFlightTtlCache(long ttlNanos, int maxsize, LongSupplier clockNanos) {
        if (ttlNanos <= 0 || maxsize <= 0) {
            throw new IllegalArgumentException("ttl 与 maxsize 必须为正");
        }
        this.ttlNanos = ttlNanos;
        this.maxsize = maxsize;
        this.clockNanos = clockNanos;
    }

    /**
     * 取值或发起一次共享装载。factory 必须返回「已启动」的 future（对位
     * asyncio.create_task）；返回的视图 future 可被调用方安全取消而不影响共享装载。
     */
    public CompletableFuture<V> getOrCreate(K key, Function<K, CompletableFuture<V>> factory) {
        CompletableFuture<V> source;
        synchronized (this) {
            long now = clockNanos.get();
            Cached<V> cached = values.get(key);
            if (cached != null && cached.expiresAtNanos() > now) {
                return CompletableFuture.completedFuture(cached.value()); // get 已按访问序刷新 LRU
            }
            if (cached != null) {
                values.remove(key);
            }
            source = inflight.get(key);
            if (source == null) {
                source = factory.apply(key);
                CompletableFuture<V> shared = source;
                inflight.put(key, shared);
                shared.whenComplete((value, error) -> onPopulated(key, shared, value, error));
            }
        }
        return shieldedView(source);
    }

    private void onPopulated(K key, CompletableFuture<V> source, V value, Throwable error) {
        synchronized (this) {
            if (inflight.get(key) == source) {
                inflight.remove(key);
            }
            if (error == null) {
                values.put(key, new Cached<>(clockNanos.get() + ttlNanos, value));
                while (values.size() > maxsize) {
                    Iterator<K> it = values.keySet().iterator();
                    if (it.hasNext()) {
                        it.next();
                        it.remove();
                    } else {
                        break;
                    }
                }
            }
        }
    }

    /** 调用方视图：取消视图不触碰源 future（对位 asyncio.shield）。 */
    private static <V> CompletableFuture<V> shieldedView(CompletableFuture<V> source) {
        CompletableFuture<V> view = new CompletableFuture<>();
        source.whenComplete((value, error) -> {
            if (error != null) {
                view.completeExceptionally(error);
            } else {
                view.complete(value);
            }
        });
        return view;
    }

    public synchronized void clear() {
        values.clear();
        inflight.clear();
    }

    public synchronized int size() {
        return values.size();
    }
}
