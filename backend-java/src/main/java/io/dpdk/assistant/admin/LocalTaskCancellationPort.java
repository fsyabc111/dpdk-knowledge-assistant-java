package io.dpdk.assistant.admin;

/**
 * 停号后的本进程任务取消端口（冻结语义，对应 Python
 * user_service.set_enabled 内 `task_service.cancel_local_for_user` 的提交后
 * best-effort 调用：异常吞掉只留警告日志，不影响已提交的停号终态）。
 *
 * integration_pending：真实取消执行体归 J3-01（任务执行器）接线；在此之前默认
 * 装配 {@link NoOpLocalTaskCancellation}，仅保证调用时序与吞错语义正确，
 * 不得视为停号-任务取消集成已通过（见 evidence/J2-02/integration-pending.md）。
 */
public interface LocalTaskCancellationPort {

    /**
     * 取消该主体在本进程执行中的任务。实现必须自吞异常（最多留日志），
     * 不得向停号调用方传播失败。
     */
    void cancelLocalForUser(String userId);
}
