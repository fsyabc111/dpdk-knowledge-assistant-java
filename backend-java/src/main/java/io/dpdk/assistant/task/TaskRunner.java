package io.dpdk.assistant.task;

import io.dpdk.assistant.contracts.platform.ClockPort;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 执行体运行层：占槽 → 注册 → 起线程 → 终态收口（对齐 task_service.start_task/
 * _run_with_deadline/_spawn）。
 *
 * - 注册与启动在同一注册锁原子段内完成（Java 侧显式补齐 Python 的事件循环
 *   单线程语义）；占槽前后再查一次注册表，幂等起步；
 * - 执行墙（ragTaskMaxSeconds）与受理排队墙分开：超时由看门狗中断执行线程，
 *   行仍 pending 时判 ``system_error/timeout``；
 * - 收口总在 finally：查行判 pending → 仅终态行才可丢执行输入（finish/取消失败
 *   仍 pending 的行保留恢复输入）→ 恰好一次释放槽位 → terminalize（同一临界区
 *   置终态 + 哨兵扇出 + 退订）→ pending 行移出注册表。
 * - 显式取消先库行后中断：迟到的执行完成被 finish 的 pending 条件更新拒绝，
 *   不能覆盖 cancelled。
 */
public final class TaskRunner {

    private static final Logger LOG = Logger.getLogger(TaskRunner.class.getName());

    /** 执行成功后的持久化协调（AnswerCompletion 同一事务面）。 */
    @FunctionalInterface
    public interface TaskFinisher {
        String finish(String taskId, String sessionId, String answer, List<CitationData> citations,
                      String status, String reason, String releaseId, long elapsedMs);
    }

    private final TaskStore store;
    private final TaskFinisher finisher;
    private final AdmissionGate gate;
    private final ManagedTaskRegistry registry;
    private final ClockPort clock;
    private final TaskSettings settings;
    private final ExecutorService workers;
    private final ScheduledExecutorService watchdog;

    public TaskRunner(TaskStore store, TaskFinisher finisher, AdmissionGate gate,
                      ManagedTaskRegistry registry, ClockPort clock, TaskSettings settings,
                      ExecutorService workers, ScheduledExecutorService watchdog) {
        this.store = store;
        this.finisher = finisher;
        this.gate = gate;
        this.registry = registry;
        this.clock = clock;
        this.settings = settings;
        this.workers = workers;
        this.watchdog = watchdog;
    }

    public static TaskRunner withDefaults(TaskStore store, TaskFinisher finisher, AdmissionGate gate,
                                          ManagedTaskRegistry registry, ClockPort clock,
                                          TaskSettings settings) {
        ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
        ScheduledExecutorService watchdog =
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "task-runner-watchdog");
                    t.setDaemon(true);
                    return t;
                });
        return new TaskRunner(store, finisher, gate, registry, clock, settings, workers, watchdog);
    }

    /* ------------------------------------------------------------------ */
    /* 起步                                                                 */
    /* ------------------------------------------------------------------ */

    /**
     * start_task：幂等起步。注册表命中直接返回；否则占槽（429 原样抛出）后再查，
     * 注册与 spawn 在注册锁内完成；spawn 失败回吐槽位。
     */
    public ManagedTask startTask(String subject, String requestId, String sessionId,
                                 TaskExecutor.TaskExecutionContext context, TaskExecutor executor) {
        ManagedTask existing = registry.get(context.taskId());
        if (existing != null) {
            return existing;
        }
        gate.acquire();
        existing = registry.get(context.taskId());
        if (existing != null) {
            gate.release();
            return existing;
        }
        try {
            return spawn(subject, requestId, sessionId, context, executor);
        } catch (RuntimeException e) {
            gate.release();
            throw e;
        }
    }

    /** start_reserved_task：调用方已占 admission 槽且已原子受理；只注册并启动。 */
    public ManagedTask startReserved(String subject, String requestId, String sessionId,
                                     TaskExecutor.TaskExecutionContext context, TaskExecutor executor) {
        if (context.taskId() == null || registry.get(context.taskId()) != null) {
            throw new IllegalStateException("任务未受理或已启动");
        }
        return spawn(subject, requestId, sessionId, context, executor);
    }

    private ManagedTask spawn(String subject, String requestId, String sessionId,
                              TaskExecutor.TaskExecutionContext context, TaskExecutor executor) {
        return registry.registerIfAbsent(context.taskId(), () -> {
            ManagedTask managed = new ManagedTask(context.taskId(), subject, requestId, sessionId,
                    context, settings.eventLogCap());
            long startedNanos = clock.monotonicNanos();
            java.util.concurrent.Future<?> future = workers.submit(
                    () -> runWithDeadline(managed, executor, startedNanos));
            managed.setFuture(future);
            scheduleDeadlineWatch(managed, startedNanos);
            return managed;
        });
    }

    private void scheduleDeadlineWatch(ManagedTask managed, long startedNanos) {
        long delayMs = Math.max(1, settings.ragTaskMaxSeconds().toMillis());
        watchdog.schedule(() -> {
            if (managed.isTerminal()) {
                return;
            }
            if (clock.monotonicNanos() - startedNanos >= settings.ragTaskMaxSeconds().toNanos()) {
                managed.setDeadlineFired();
                java.util.concurrent.Future<?> future = managed.future();
                if (future != null) {
                    future.cancel(true);
                }
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /* ------------------------------------------------------------------ */
    /* 执行收口总管                                                          */
    /* ------------------------------------------------------------------ */

    private void runWithDeadline(ManagedTask managed, TaskExecutor executor, long startedNanos) {
        TaskExecutor.TaskExecutionResult result = null;
        Throwable error = null;
        try {
            result = executor.run(managed.context, managed::emit);
            String messageId = finisher.finish(managed.taskId, managed.sessionId,
                    result.answer(), result.citations(), result.status(), result.reason(),
                    result.releaseId(), (clock.monotonicNanos() - startedNanos) / 1_000_000);
            managed.setMessageId(messageId);
            // finish 的 issue 登记条件同位：状态非 answered/not_applicable 记 issue。
            if (!"answered".equals(result.status()) && !"not_applicable".equals(result.status())) {
                managed.setIssueId(managed.taskId);
            }
        } catch (InterruptedException cancelled) {
            // 取消路径先落库再中断；这里不吞也不重复写终态。
            error = cancelled;
            Thread.currentThread().interrupt();
        } catch (Exception exc) {
            error = exc;
            if (managed.isDeadlineFired()) {
                try {
                    store.markInterrupted(managed.taskId, "timeout");
                } catch (RuntimeException markFailure) {
                    LOG.warning("[task_mark_timeout_failed] task_id=" + managed.taskId);
                }
            } else {
                LOG.warning("[task_run_failed] task_id=" + managed.taskId
                        + " error_type=" + exc.getClass().getSimpleName());
            }
        } finally {
            boolean pending = true;
            try {
                var row = store.findOwned(managed.taskId, managed.subject);
                pending = row.isEmpty() || "pending".equals(row.get().status());
                if (!pending) {
                    store.dropInputs(managed.taskId);
                }
            } catch (RuntimeException checkFailure) {
                LOG.warning("[task_terminal_check_failed] task_id=" + managed.taskId);
            }
            if (managed.releaseSlotOnce()) {
                gate.release();
            }
            managed.terminalize(result, error, clock.monotonicNanos());
            if (pending) {
                // finish 的 DB 故障可能留下 pending：保留输入使孤儿扫描可重驱动。
                registry.remove(managed.taskId);
            }
        }
    }

    /** 等待收口并返回执行结果；超时抛 409 语义（detail 带 task_id 供轮询/续传）。 */
    public TaskExecutor.TaskExecutionResult waitResult(ManagedTask managed, Duration timeout)
            throws InterruptedException {
        if (!managed.awaitDoneImmediately() && !managed.awaitDone(timeout)) {
            throw new TaskExceptions.TaskStillRunningException(managed.taskId,
                    "任务仍在执行，可通过 /api/tasks/{task_id} 查询或续传");
        }
        return managed.result();
    }

    /** 显式取消的本地段：中断执行线程并等收口（库行已在调用方先终态化）。 */
    public void cancelLocal(ManagedTask managed) {
        managed.markCancelledByUser();
        java.util.concurrent.Future<?> future = managed.future();
        if (future != null) {
            future.cancel(true);
        }
        try {
            managed.awaitDone(java.time.Duration.ofMinutes(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 非终态行 + 注册表在跑 → 对外展示 running。 */
    public boolean isRunning(String taskId) {
        return registry.isRunning(taskId);
    }

    /** 终态条目惰性清理（TTL 内保留供迟到重连读终态帧）。 */
    public void sweepFinished() {
        registry.sweepFinished();
    }

    public ManagedTaskRegistry registry() {
        return registry;
    }

    /** 结束看门狗（测试收尾用；不中断在跑任务）。 */
    public void shutdownWatchdog() {
        watchdog.shutdownNow();
        workers.shutdownNow();
    }
}
