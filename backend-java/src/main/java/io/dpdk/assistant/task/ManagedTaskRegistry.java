package io.dpdk.assistant.task;

import io.dpdk.assistant.contracts.platform.ClockPort;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 进程内任务注册表（对齐 task_service.TASKS + sweep_finished）。
 *
 * 注册与启动的原子性由 {@code registrationLock}（本对象锁）+ putIfAbsent 组合：
 * startTask 的「查-占槽-再查-注册」各段不允许另一个线程在再查与注册之间插入
 * 双执行（Python 靠事件循环单线程语义，Java 需要显式原子段）。
 * 终态条目 TTL 内保留供迟到重连读终态帧，由 sweepFinished 惰性清理。
 */
public final class ManagedTaskRegistry {

    private final Map<String, ManagedTask> tasks = new ConcurrentHashMap<>();
    private final ClockPort clock;
    private final java.time.Duration finishedTtl;

    public ManagedTaskRegistry(ClockPort clock, java.time.Duration finishedTtl) {
        this.clock = clock;
        this.finishedTtl = finishedTtl;
    }

    /** 注册表命中且未收口（对外展示 running 的口径）。 */
    public boolean isRunning(String taskId) {
        ManagedTask managed = tasks.get(taskId);
        return managed != null && !managed.isTerminal();
    }

    public ManagedTask get(String taskId) {
        return tasks.get(taskId);
    }

    /** 命中即返回；absent 时在注册锁内执行 spawn，注册竞态败方拿到既有句柄。 */
    public ManagedTask registerIfAbsent(String taskId, java.util.function.Supplier<ManagedTask> spawn) {
        synchronized (this) {
            ManagedTask existing = tasks.get(taskId);
            if (existing != null) {
                return existing;
            }
            ManagedTask created = spawn.get();
            ManagedTask winner = tasks.putIfAbsent(taskId, created);
            if (winner != null) {
                return winner; // 理论不可达（注册锁内）；防御返回既有句柄
            }
            return created;
        }
    }

    /** start_reserved_task：调用方已占槽且已原子受理；重复注册是编程错误。 */
    public ManagedTask registerReserved(ManagedTask managed) {
        synchronized (this) {
            ManagedTask existing = tasks.get(managed.taskId);
            if (existing != null) {
                throw new IllegalStateException("任务未受理或已启动");
            }
            tasks.put(managed.taskId, managed);
            return managed;
        }
    }

    public void remove(String taskId) {
        tasks.remove(taskId);
    }

    /** 本进程在跑（非 terminal）任务 id，心跳与停号取消的枚举口径。 */
    public List<String> runningIds() {
        return tasks.values().stream()
                .filter(managed -> !managed.isTerminal())
                .map(managed -> managed.taskId)
                .collect(Collectors.toList());
    }

    /** 该主体的在跑句柄（停号本地取消枚举口径）。 */
    public Collection<ManagedTask> runningForSubject(String subject) {
        return tasks.values().stream()
                .filter(managed -> subject.equals(managed.subject) && !managed.isTerminal())
                .collect(Collectors.toList());
    }

    /** 惰性清理终态条目（TTL 内保留供迟到重连读终态帧）。 */
    public void sweepFinished() {
        long cutoff = clock.monotonicNanos() - finishedTtl.toNanos();
        for (Map.Entry<String, ManagedTask> entry : tasks.entrySet()) {
            ManagedTask managed = entry.getValue();
            if (managed.isTerminal() && managed.finishedAtNanos() <= cutoff) {
                tasks.remove(entry.getKey(), managed);
            }
        }
    }

    public int size() {
        return tasks.size();
    }
}
