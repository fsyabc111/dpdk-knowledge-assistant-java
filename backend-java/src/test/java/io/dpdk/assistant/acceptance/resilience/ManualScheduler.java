package io.dpdk.assistant.acceptance.resilience;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** 仅测试调度器：保留生产schedule(600s)，显式推进到期任务，不缩短业务配置。 */
public final class ManualScheduler extends AbstractExecutorService implements ScheduledExecutorService {
    private final PriorityQueue<Entry<?>> queue = new PriorityQueue<>();
    private long nanos;
    private long sequence;
    private boolean shutdown;

    public synchronized long nanos() { return nanos; }

    public void advance(Duration duration) {
        if (duration.isNegative()) throw new IllegalArgumentException("时钟不回拨");
        long target;
        synchronized (this) { target = Math.addExact(nanos, duration.toNanos()); }
        while (true) {
            Entry<?> next;
            synchronized (this) {
                next = queue.peek();
                if (next == null || next.deadline > target) { nanos = target; return; }
                queue.remove(); nanos = next.deadline;
            }
            next.run();
        }
    }

    @Override public synchronized <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        if (shutdown) throw new RejectedExecutionException();
        Entry<V> entry = new Entry<>(callable, Math.addExact(nanos, Math.max(0, unit.toNanos(delay))), sequence++);
        queue.add(entry);
        return entry;
    }
    @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        return schedule(Executors.callable(command, null), delay, unit);
    }
    @Override public void execute(Runnable command) { schedule(command, 0, TimeUnit.NANOSECONDS); }
    @Override public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initial, long period, TimeUnit unit) {
        throw new UnsupportedOperationException("测试只允许TaskRunner一次性看门狗");
    }
    @Override public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initial, long delay, TimeUnit unit) {
        throw new UnsupportedOperationException("测试不模拟自动循环");
    }
    @Override public synchronized void shutdown() { shutdown = true; }
    @Override public synchronized List<Runnable> shutdownNow() {
        shutdown = true;
        List<Runnable> pending = new ArrayList<>(queue);
        queue.clear(); pending.forEach(r -> ((Future<?>) r).cancel(false)); return pending;
    }
    @Override public synchronized boolean isShutdown() { return shutdown; }
    @Override public synchronized boolean isTerminated() { return shutdown && queue.isEmpty(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }

    private final class Entry<V> extends FutureTask<V> implements RunnableScheduledFuture<V> {
        private final long deadline;
        private final long order;
        Entry(Callable<V> action, long deadline, long order) { super(action); this.deadline = deadline; this.order = order; }
        @Override public long getDelay(TimeUnit unit) { return unit.convert(deadline - nanos(), TimeUnit.NANOSECONDS); }
        @Override public int compareTo(Delayed other) {
            if (other instanceof ManualScheduler.Entry<?> entry) {
                int c = Long.compare(deadline, entry.deadline);
                return c == 0 ? Long.compare(order, entry.order) : c;
            }
            return Long.compare(getDelay(TimeUnit.NANOSECONDS), other.getDelay(TimeUnit.NANOSECONDS));
        }
        @Override public boolean isPeriodic() { return false; }
    }
}
