package io.dpdk.assistant.chat;

import io.dpdk.assistant.task.TaskExceptions;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** 任务域错误到冻结 HTTP detail 的适配，不依赖框架默认异常形状。 */
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(
        basePackages = {"io.dpdk.assistant.chat", "io.dpdk.assistant.assignment.explanation"})
public class ChatErrors {
    @ExceptionHandler(TaskExceptions.TaskNotFoundException.class)
    ResponseEntity<?> missing(RuntimeException e) {
        return ResponseEntity.status(404).body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(TaskExceptions.TaskConflictException.class)
    ResponseEntity<?> conflict(RuntimeException e) {
        return ResponseEntity.status(409).body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(TaskExceptions.TaskForbiddenException.class)
    ResponseEntity<?> forbidden(RuntimeException e) {
        return ResponseEntity.status(403).body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(TaskExceptions.TaskAdmissionBusyException.class)
    ResponseEntity<?> busy(TaskExceptions.TaskAdmissionBusyException e) {
        return ResponseEntity.status(429)
                .header("Retry-After", e.retryAfterSeconds)
                .body(Map.of("detail", e.getMessage()));
    }

    @ExceptionHandler(TaskExceptions.TaskStillRunningException.class)
    ResponseEntity<?> running(TaskExceptions.TaskStillRunningException e) {
        return pending(e.taskId, e.getMessage());
    }

    @ExceptionHandler(TaskExceptions.TaskPendingException.class)
    ResponseEntity<?> pending(TaskExceptions.TaskPendingException e) {
        return pending(e.taskId, e.getMessage());
    }

    @ExceptionHandler(io.dpdk.assistant.usage.QuotaExceededException.class)
    ResponseEntity<?> quota(io.dpdk.assistant.usage.QuotaExceededException e) {
        return ResponseEntity.status(429)
                .header("Retry-After", String.valueOf(e.retryAfterSeconds()))
                .body(Map.of("detail", e.getMessage()));
    }

    private ResponseEntity<?> pending(String id, String message) {
        return ResponseEntity.status(409)
                .body(Map.of("detail", Map.of("task_id", id, "message", message)));
    }
}
