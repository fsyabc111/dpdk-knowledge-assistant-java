package io.dpdk.assistant.task;

/**
 * 任务域异常集合：HTTP 语义载体（由消费方端点层映射状态码），本包不依赖 Web 层。
 *
 * 消息文案与 Python 基线逐字一致（409/403/404/429 detail 同位）。
 */
public final class TaskExceptions {

    private TaskExceptions() {
    }

    /** 404：不存在或越权同口径（不泄露任务存在性）。 */
    public static final class TaskNotFoundException extends RuntimeException {
        public TaskNotFoundException(String message) {
            super(message);
        }
    }

    /** 409：request_id 身份不匹配 / 输入 hash 不符 / 会话已删除等冲突。 */
    public static final class TaskConflictException extends RuntimeException {
        public TaskConflictException(String message) {
            super(message);
        }
    }

    /** 403：账号已停用等受理拒绝。 */
    public static final class TaskForbiddenException extends RuntimeException {
        public TaskForbiddenException(String message) {
            super(message);
        }
    }

    /** 409：任务仍在执行的非流式等待超时（detail 携带 task_id 供轮询/续传）。 */
    public static final class TaskStillRunningException extends RuntimeException {
        public final String taskId;

        public TaskStillRunningException(String taskId, String message) {
            super(message);
            this.taskId = taskId;
        }
    }

    /** 409：任务等待重驱动（pending 行暂不可恢复），detail 同源形态。 */
    public static final class TaskPendingException extends RuntimeException {
        public final String taskId;

        public TaskPendingException(String taskId, String message) {
            super(message);
            this.taskId = taskId;
        }
    }

    /** 429：受理占槽满/排队超时（Retry-After=10，与源一致）。 */
    public static final class TaskAdmissionBusyException extends RuntimeException {
        public final String retryAfterSeconds;

        public TaskAdmissionBusyException(String message) {
            super(message);
            this.retryAfterSeconds = "10";
        }
    }
}
