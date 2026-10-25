package io.dpdk.assistant.agent.tools;

/**
 * 任务级可重驱动异常（对位 services/task_errors.RetryableTaskFailure）。
 *
 * <p>账本/成绩持久化失败抛出：输入与 job 账本保留，等待孤儿恢复；不得把请求
 * 收成正常终态。普通工具业务失败仍返回结构化结果，不抛异常。
 */
public class RetryableTaskFailure extends RuntimeException {

    public RetryableTaskFailure(String message) {
        super(message);
    }

    public RetryableTaskFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
