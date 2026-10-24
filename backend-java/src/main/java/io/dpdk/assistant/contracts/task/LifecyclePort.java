package io.dpdk.assistant.contracts.task;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/**
 * 任务生命周期端口（owner J3-01；契约 transaction-ports.json：task.LifecyclePort）。
 *
 * owner 缺失/越权 404；cancel pending 条件更新+取消占位消息+输入清理同事务；
 * claim 只更新时间条件，无 fencing token（单副本范围；心跳不是 exactly-once）。
 */
public interface LifecyclePort {

    Optional<TaskRow> getOwned(String taskId, String subject);

    TaskRow cancel(String taskId, String subject);

    boolean claimRecovery(String taskId, Instant staleCutoff);

    void heartbeat(Collection<String> ownedIds);
}
