package io.dpdk.assistant.chat;

import io.dpdk.assistant.auth.AuthPrincipalResolver;
import io.dpdk.assistant.task.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.*;

/** 查询、刷新找回、显式取消与续传；跨用户与缺失均为同形404。 */
@RestController
public class TaskController {
    private final RagTaskService tasks;
    private final AuthPrincipalResolver auth;

    public TaskController(RagTaskService tasks, AuthPrincipalResolver auth) {
        this.tasks = tasks;
        this.auth = auth;
    }

    @GetMapping("/api/tasks/active")
    public Map<String, Object> active(
            @RequestParam(value = "session_id", required = false) String session,
            HttpServletRequest request) {
        var principal = ChatHttp.authenticate(auth, request);
        if (session == null || session.isEmpty()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("type", session == null ? "missing" : "string_too_short");
            e.put("loc", List.of("query", "session_id"));
            e.put(
                    "msg",
                    session == null ? "Field required" : "String should have at least 1 character");
            e.put("input", session);
            if (session != null) e.put("ctx", Map.of("min_length", 1));
            throw ChatRequestParser.error(422, List.of(e));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(
                "task",
                tasks.activeTaskForSession(session, principal.subject())
                        .map(r -> ChatHttp.status(r, tasks))
                        .orElse(null));
        return out;
    }

    @GetMapping("/api/tasks/{task_id}")
    public Map<String, Object> status(
            @PathVariable("task_id") String id, HttpServletRequest request) {
        var p = ChatHttp.authenticate(auth, request);
        return ChatHttp.status(owned(id, p.subject()), tasks);
    }

    @PostMapping("/api/tasks/{task_id}/cancel")
    public Map<String, Object> cancel(
            @PathVariable("task_id") String id, HttpServletRequest request) {
        var p = ChatHttp.authenticate(auth, request);
        return ChatHttp.status(tasks.cancelDetail(id, p.subject(), "user_cancelled").row(), tasks);
    }

    @GetMapping("/api/tasks/{task_id}/events")
    public ResponseEntity<StreamingResponseBody> events(
            @PathVariable("task_id") String id, HttpServletRequest request) {
        var p = ChatHttp.authenticate(auth, request);
        owned(id, p.subject());
        return ChatHttp.stream(tasks.taskEvents(id, p.subject()));
    }

    private TaskRecordRow owned(String id, String user) {
        return tasks.getOwnedDetail(id, user)
                .orElseThrow(() -> ChatRequestParser.error(404, "task not found"));
    }
}
