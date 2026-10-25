package io.dpdk.assistant.task;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一条在执行/已收口任务的进程内句柄（对齐 task_service.ManagedTask）。
 *
 * 并发约定（验收 3 的锁/原子段）：
 * - {@link #lock} 保护 events/subscribers/terminal/result 的全部读写——回放快照
 *   与订阅注册、完成与退订、事件追加与扇出各自在同一临界区内完成，不能只用
 *   ConcurrentHashMap 拼装；
 * - 订阅者队列无界（detached 订阅者不得反压执行，源 asyncio.Queue 同口径）；
 * - done 闩在 lock 外 countDown，订阅者可等待收口而不持锁阻塞。
 */
public final class ManagedTask {

    /** 订阅者收口哨兵（poll 到即进入终帧段）。 */
    public static final TaskEvent DONE_SENTINEL = new TaskEvent("__done__", java.util.Map.of());

    /** 每个活跃订阅者一个无界队列（泵线程据此 poll；detached 不反压执行）。 */
    public static final class Subscriber {
        public final java.util.concurrent.BlockingQueue<Object> queue =
                new java.util.concurrent.LinkedBlockingQueue<>();
        public final long registeredAtNanos;
        public boolean detached;

        Subscriber(long registeredAtNanos) {
            this.registeredAtNanos = registeredAtNanos;
        }
    }

    public final String taskId;
    public final String subject;
    public final String requestId;
    public final String sessionId;
    public final TaskExecutor.TaskExecutionContext context;

    private final ReentrantLock lock = new ReentrantLock();
    private final List<TaskEvent> events = new ArrayList<>();
    private final List<Subscriber> subscribers = new ArrayList<>();
    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicBoolean slotReleased = new AtomicBoolean(false);
    private final int eventLogCap;

    private TaskExecutor.TaskExecutionResult result;
    private Throwable error;
    private boolean terminal;
    private boolean cancelledByUser;
    private boolean deadlineFired;
    private String messageId;
    private String issueId;
    private long finishedAtNanos;

    public ManagedTask(String taskId, String subject, String requestId, String sessionId,
                       TaskExecutor.TaskExecutionContext context, int eventLogCap) {
        this.taskId = taskId;
        this.subject = subject;
        this.requestId = requestId;
        this.sessionId = sessionId;
        this.context = context;
        this.eventLogCap = eventLogCap;
    }

    /* ---------------- 执行体事件入口 ---------------- */

    /** 回放日志追加（cap 500 丢最旧）+ 扇出到全部订阅者；同一临界区。 */
    public void emit(TaskEvent event) {
        lock.lock();
        try {
            events.add(event);
            if (events.size() > eventLogCap) {
                events.subList(0, events.size() - eventLogCap).clear();
            }
            for (Subscriber subscriber : subscribers) {
                subscriber.queue.add(event);
            }
        } finally {
            lock.unlock();
        }
    }

    /* ---------------- 订阅 ---------------- */

    /**
     * 快照与订阅注册同一临界区：先取回放快照再登记队列，之间不可能漏帧
     * （Python subscribe 的「无 await 原子段」同位）。
     */
    public SubscriptionView subscribeView(long nowNanos) {
        lock.lock();
        try {
            List<TaskEvent> snapshot = List.copyOf(events);
            boolean live = !terminal;
            Subscriber subscriber = new Subscriber(nowNanos);
            if (live) {
                subscribers.add(subscriber);
            }
            return new SubscriptionView(snapshot, live, subscriber);
        } finally {
            lock.unlock();
        }
    }

    public record SubscriptionView(List<TaskEvent> snapshot, boolean live, Subscriber subscriber) {
    }

    /** 断开只移除订阅者，绝不取消任务（B13 修复点）。 */
    public void removeSubscriber(Subscriber subscriber) {
        lock.lock();
        try {
            subscriber.detached = true;
            subscribers.remove(subscriber);
        } finally {
            lock.unlock();
        }
    }

    /* ---------------- 收口 ---------------- */

    /** 终态置位 + 哨兵扇出 + 退订：同一临界区，订阅者观察到的状态变化是原子的。 */
    public void terminalize(TaskExecutor.TaskExecutionResult result, Throwable error,
                            long nowNanos) {
        lock.lock();
        try {
            if (terminal) {
                return;
            }
            this.result = result;
            this.error = error;
            this.terminal = true;
            this.finishedAtNanos = nowNanos;
            for (Subscriber subscriber : subscribers) {
                subscriber.queue.add(DONE_SENTINEL);
            }
            subscribers.clear();
        } finally {
            lock.unlock();
        }
        done.countDown();
    }

    /* ---------------- 状态读取 ---------------- */

    public boolean isTerminal() {
        lock.lock();
        try {
            return terminal;
        } finally {
            lock.unlock();
        }
    }

    public TaskExecutor.TaskExecutionResult result() {
        lock.lock();
        try {
            return result;
        } finally {
            lock.unlock();
        }
    }

    public Throwable error() {
        lock.lock();
        try {
            return error;
        } finally {
            lock.unlock();
        }
    }

    public boolean isCancelledByUser() {
        lock.lock();
        try {
            return cancelledByUser;
        } finally {
            lock.unlock();
        }
    }

    public void markCancelledByUser() {
        lock.lock();
        try {
            cancelledByUser = true;
        } finally {
            lock.unlock();
        }
    }

    /** 看门狗在超时墙到点后置位（随中断一起传达超时归因）。 */
    public void setDeadlineFired() {
        lock.lock();
        try {
            deadlineFired = true;
        } finally {
            lock.unlock();
        }
    }

    public boolean isDeadlineFired() {
        lock.lock();
        try {
            return deadlineFired;
        } finally {
            lock.unlock();
        }
    }

    /** finish 返回的消息 id（终帧 done 需要）。 */
    public void setMessageId(String messageId) {
        lock.lock();
        try {
            this.messageId = messageId;
        } finally {
            lock.unlock();
        }
    }

    public String messageId() {
        lock.lock();
        try {
            return messageId;
        } finally {
            lock.unlock();
        }
    }

    /** issue_id（finish 的 issue 登记条件同位：状态非 answered/not_applicable）。 */
    public void setIssueId(String issueId) {
        lock.lock();
        try {
            this.issueId = issueId;
        } finally {
            lock.unlock();
        }
    }

    public String issueId() {
        lock.lock();
        try {
            return issueId;
        } finally {
            lock.unlock();
        }
    }

    public long finishedAtNanos() {
        lock.lock();
        try {
            return finishedAtNanos;
        } finally {
            lock.unlock();
        }
    }

    /** 等待收口；返回是否在超时内收口（latch 语义）。 */
    public boolean awaitDone(java.time.Duration timeout) throws InterruptedException {
        return done.await(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    public boolean awaitDoneImmediately() throws InterruptedException {
        return done.await(0, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /** 槽位恰好释放一次的守卫标记（防双释放导致的负槽）。 */
    public boolean releaseSlotOnce() {
        return slotReleased.compareAndSet(false, true);
    }

    public void setFuture(java.util.concurrent.Future<?> future) {
        // volatile write via lock-free assignment on final field holder
        this.futureRef = future;
    }

    public java.util.concurrent.Future<?> future() {
        return futureRef;
    }

    private volatile java.util.concurrent.Future<?> futureRef;
}
