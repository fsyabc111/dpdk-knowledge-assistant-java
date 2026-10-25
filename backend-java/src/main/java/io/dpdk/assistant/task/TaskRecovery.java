package io.dpdk.assistant.task;

import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 重启 / 孤儿恢复（对齐 task_service.ensure_managed/_recover_row/recover_orphans/
 * heartbeat_loop）。
 *
 * - grace 窗口避开「受理已落库但任务尚未注册」的瞬时态；太新的行不抢跑；
 * - 属主心跳把在跑行 updated_at 拨新，本查询天然只命中真孤儿；
 * - 恢复口径：输入缺失 → interrupted；账号停用/缺失 → cancelled(account_disabled)；
 *   认领闸（原子条件 UPDATE）失败 → 放弃；占槽满 → 留待下一拍（绝不挤占
 *   实时受理的排队护栏）；
 * - _RECOVERING 集合防止并发接续/两拍扫描把同一 pending 行重驱动两次。
 */
public final class TaskRecovery {

    private static final Logger LOG = Logger.getLogger(TaskRecovery.class.getName());

    private final TaskStore store;
    private final TaskRunner runner;
    private final AdmissionGate gate;
    private final ClockPort clock;
    private final TaskSettings settings;
    private final TransactionRunner txRunner;
    private final AccountModeSupplier accountMode;

    /** 正在装配的恢复集合：prepare 有异步窗口，没有它同一行可能被重驱动两次。 */
    private final Set<String> recovering = ConcurrentHashMap.newKeySet();

    /** 认证模式（local 模式恢复主体与 require_auth 的本地 principal 同形）。 */
    @FunctionalInterface
    public interface AccountModeSupplier {
        boolean localMode();
    }

    public TaskRecovery(TaskStore store, TaskRunner runner, AdmissionGate gate, ClockPort clock,
                        TaskSettings settings, TransactionRunner txRunner,
                        AccountModeSupplier accountMode) {
        this.store = store;
        this.runner = runner;
        this.gate = gate;
        this.clock = clock;
        this.settings = settings;
        this.txRunner = txRunner;
        this.accountMode = accountMode;
    }

    /* ------------------------------------------------------------------ */
    /* 恢复扫描                                                             */
    /* ------------------------------------------------------------------ */

    /** 恢复扫描一拍：孤儿 pending 行重驱动；单行失败不拖垮整拍。返回恢复条数。 */
    public int recoverOrphans() {
        runner.sweepFinished();
        List<TaskRecordRow> rows = store.orphanedPending(settings.orphanGrace());
        int recovered = 0;
        for (TaskRecordRow row : rows) {
            try {
                if (recoverRow(row).isPresent()) {
                    recovered++;
                }
            } catch (RuntimeException exc) {
                LOG.warning("[task_recover_failed] task_id=" + row.id()
                        + " error_type=" + exc.getClass().getSimpleName());
            }
        }
        return recovered;
    }

    /**
     * ensure_managed：pending 行的执行体。注册表命中直接返回；过 grace 窗口的孤儿
     * 按恢复口径重驱动；太新的行返回 empty（不抢跑）。
     */
    public Optional<ManagedTask> ensureManaged(TaskRecordRow row) {
        ManagedTask managed = runner.registry().get(row.id());
        if (managed != null) {
            return Optional.of(managed);
        }
        if (rowAgeSeconds(row) < settings.orphanGrace().toSeconds()) {
            return Optional.empty();
        }
        return recoverRow(row);
    }

    private Optional<ManagedTask> recoverRow(TaskRecordRow row) {
        String taskId = row.id();
        ManagedTask existing = runner.registry().get(taskId);
        if (existing != null) {
            return Optional.of(existing);
        }
        if (!recovering.add(taskId)) {
            return Optional.empty();   // 另一处正在恢复同一行：接续方稍后重连即可
        }
        try {
            Optional<TaskInputRecord> inputs = store.loadInputs(taskId);
            if (inputs.isEmpty()) {
                // 输入缺失（24h TTL 清理或 schema 漂移）→ interrupted，terminal 即不再回收。
                store.markInterrupted(taskId, "interrupted");
                return Optional.empty();
            }
            Optional<EnabledUser> userOpt = loadEnabledUser(row.userId());
            if (userOpt.isEmpty()) {
                // 账号不存在或已停用：按取消语义终止（decisions §8），不回收结果回放。
                store.cancelTask(taskId, "account_disabled", "system");
                return Optional.empty();
            }
            EnabledUser user = userOpt.get();
            if (!store.claimRecovery(taskId, clock.nowUtc().minus(settings.orphanGrace()))) {
                // 行已被别处终态化/被另一副本抢先认领：放弃，接续方拿 pending 终帧。
                return Optional.empty();
            }
            if (!gate.tryAcquire()) {
                return Optional.empty();   // 占槽满：留待下一拍/下次接续
            }
            try {
                TaskExecutor.TaskExecutionContext context = new TaskExecutor.TaskExecutionContext(
                        taskId, row.requestId(), row.sessionId(), row.userId(),
                        user.roles(), row.purpose(), inputs.get().question(), row.contextText(),
                        inputs.get().attachmentName(), inputs.get().attachmentSuffix(),
                        inputs.get().sourceText(), inputs.get().sourceOrigin());
                ManagedTask managed = runner.startReserved(row.userId(), row.requestId(),
                        row.sessionId(), context, executor);
                LOG.info("[task_recovered] task_id=" + taskId);
                return Optional.of(managed);
            } catch (RuntimeException e) {
                gate.release();
                throw e;
            }
        } finally {
            recovering.remove(taskId);
        }
    }

    private volatile TaskExecutor executor;

    /** 执行体注入（Agent 未完成前由装配方给脚本化实现；J3-02 接真实循环）。 */
    public void setExecutor(TaskExecutor executor) {
        this.executor = executor;
    }

    /** users 表直查：不存在或 enabled=0 → null（恢复权限口径，decisions §8）。 */
    Optional<EnabledUser> loadEnabledUser(String userId) {
        if (accountMode.localMode()) {
            // local 模式没有账号体系（subject 恒为 "local"、users 表无对应行）。
            return Optional.of(new EnabledUser(List.of("admin"), 0));
        }
        if (userId == null || userId.isEmpty()) {
            return Optional.empty();
        }
        return txRunner.required(tx -> {
            // 注意：tx.connection() 是事务绑定连接，参与者不得关闭（契约 DbTx）。
            try (PreparedStatement ps = tx.connection().prepareStatement(
                    "SELECT roles, enabled, daily_quota FROM users WHERE id=?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !rs.getBoolean("enabled")) {
                        return Optional.<EnabledUser>empty();
                    }
                    List<String> roles = parseRoles(rs.getString("roles"));
                    int quota = rs.getObject("daily_quota") == null
                            ? 0 : rs.getInt("daily_quota");
                    return Optional.of(new EnabledUser(roles, quota));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("recovery user read failure", e);
            }
        });
    }

    public record EnabledUser(List<String> roles, int dailyQuota) {
    }

    private static List<String> parseRoles(String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of("viewer");
        }
        try {
            var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
            if (tree.isArray()) {
                List<String> roles = new java.util.ArrayList<>();
                tree.forEach(item -> roles.add(item.asText("viewer")));
                return roles;
            }
        } catch (Exception ignored) {
            // 损坏 JSON 按缺省处理（源 json.loads 失败分支同位）
        }
        return List.of("viewer");
    }

    /** 行龄（秒）；解析失败按最老处理（+∞）。 */
    double rowAgeSeconds(TaskRecordRow row) {
        try {
            OffsetDateTime updated = OffsetDateTime.parse(row.updatedAt());
            Duration age = Duration.between(updated.toInstant(), clock.nowUtc());
            return Math.max(0d, age.toMillis() / 1000d);
        } catch (RuntimeException parseFailure) {
            return Double.POSITIVE_INFINITY;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 心跳                                                                 */
    /* ------------------------------------------------------------------ */

    /** heartbeat_owned：把本进程在跑任务的 pending 行 updated_at 拨到当前时刻。 */
    public int heartbeatOwned() {
        List<String> ids = runner.registry().runningIds();
        if (ids.isEmpty()) {
            return 0;
        }
        return store.heartbeat(ids);
    }

    /** 周期心跳：单拍失败不致命，下一拍再来（daemon，随进程退出结束）。 */
    public Thread startHeartbeatLoop() {
        Thread loop = Thread.ofVirtual().name("task-heartbeat").start(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    heartbeatOwned();
                } catch (RuntimeException exc) {
                    LOG.warning("[task_heartbeat_failed] " + exc.getClass().getSimpleName());
                }
                try {
                    Thread.sleep(settings.heartbeatInterval().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        return loop;
    }
}
