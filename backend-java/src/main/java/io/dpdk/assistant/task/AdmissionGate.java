package io.dpdk.assistant.task;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 进程级受理占槽（对齐 backend/app/services/rag_product_service.py:Admission）。
 *
 * 单 worker 的 asyncio.Condition 在 Java 侧为显式锁 + 条件队列；active/waiting
 * 界限、排队超时与非排队占槽（恢复扫描专用）语义同位：
 * - {@link #acquire}：满员时按 waiting 上限排队，等待超过 queueTimeout 抛 429
 *   「等待超时」；连排队名额都没有时直接抛 429「服务繁忙」；
 * - {@link #tryAcquire}：非排队占槽——满员即 false，绝不挤占实时受理的排队护栏；
 * - {@link #release}：守卫负槽（多余释放记录告警并忽略）。
 */
public final class AdmissionGate {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition released = lock.newCondition();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger waiting = new AtomicInteger();
    private final int maxActive;
    private final int maxWaiting;
    private final long queueTimeoutNanos;

    public AdmissionGate(int maxActive, int maxWaiting, java.time.Duration queueTimeout) {
        this.maxActive = maxActive;
        this.maxWaiting = maxWaiting;
        this.queueTimeoutNanos = queueTimeout.toNanos();
    }

    /** acquire：占槽或排队等待；两处 429 与源文案逐字一致。 */
    public void acquire() {
        lock.lock();
        try {
            if (active.get() >= maxActive) {
                if (waiting.get() >= maxWaiting) {
                    throw new TaskExceptions.TaskAdmissionBusyException("服务繁忙，请稍后重试");
                }
                waiting.incrementAndGet();
                long remaining = queueTimeoutNanos;
                try {
                    while (active.get() >= maxActive) {
                        if (remaining <= 0) {
                            throw new TaskExceptions.TaskAdmissionBusyException("等待超时，请稍后重试");
                        }
                        remaining = released.awaitNanos(remaining);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new TaskExceptions.TaskAdmissionBusyException("等待超时，请稍后重试");
                } finally {
                    waiting.decrementAndGet();
                }
            }
            active.incrementAndGet();
        } finally {
            lock.unlock();
        }
    }

    /** try_acquire：非排队占槽（恢复重驱动用）；满员返回 false。 */
    public boolean tryAcquire() {
        lock.lock();
        try {
            if (active.get() >= maxActive) {
                return false;
            }
            active.incrementAndGet();
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** release：终态/失败路径归还槽位；负槽守卫（不抛出，保持收口路径稳定）。 */
    public void release() {
        lock.lock();
        try {
            if (active.get() <= 0) {
                return;
            }
            active.decrementAndGet();
            released.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public int active() {
        return active.get();
    }

    public int waiting() {
        return waiting.get();
    }
}
