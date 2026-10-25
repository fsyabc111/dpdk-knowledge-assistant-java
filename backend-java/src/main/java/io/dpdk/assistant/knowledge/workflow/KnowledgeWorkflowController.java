package io.dpdk.assistant.knowledge.workflow;

import io.dpdk.assistant.auth.*;
import io.dpdk.assistant.contracts.identity.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** /api/rag 人工工作台与本人反馈；角色和 account 门禁保持不存在式 404。 */
@RestController
@RequestMapping("/api/rag")
public final class KnowledgeWorkflowController {
    private final KnowledgeWorkflowService workflow;
    private final AuthPrincipalResolver resolver;
    private final AuthSettingsProvider settings;

    public KnowledgeWorkflowController(KnowledgeWorkflowService workflow, AuthPrincipalResolver resolver,
            AuthSettingsProvider settings) {
        this.workflow = workflow;
        this.resolver = resolver;
        this.settings = settings;
    }

    @GetMapping("/issues")
    public Map<String, Object> issues(@RequestParam(defaultValue="") String status,
            @RequestParam(defaultValue="50") String limit, @RequestParam(defaultValue="0") String offset,
            HttpServletRequest request) {
        var principal = manager(request);
        int size = WorkflowBody.query(limit, "limit", 1, 100);
        int start = WorkflowBody.query(offset, "offset", 0, Integer.MAX_VALUE);
        return workflow.issues(principal, status, size, start);
    }

    @GetMapping("/issues/{id}")
    public Map<String, Object> issue(@PathVariable String id, HttpServletRequest request) {
        return workflow.issue(id, manager(request));
    }

    @PatchMapping("/issues/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody(required=false) String raw,
            HttpServletRequest request) {
        var principal = manager(request);
        var b = new WorkflowBody(raw);
        String status = b.string("status", null, false, 0, Integer.MAX_VALUE,
                "open", "in_progress", "awaiting_verification", "resolved", "ignored");
        String note = b.string("note", "", false, 0, 4000);
        boolean claim = b.bool("claim", false);
        String merged = b.string("merged_into", null, true, 0, Integer.MAX_VALUE);
        String knowledge = b.string("knowledge_id", null, true, 0, Integer.MAX_VALUE);
        b.finish();
        return workflow.update(id, principal, new KnowledgeWorkflowService.IssueUpdate(status,note,claim,merged,knowledge));
    }

    @PostMapping("/feedback")
    public Map<String, Object> feedback(@RequestBody(required=false) String raw, HttpServletRequest request) {
        var principal = authenticate(request);
        var b = new WorkflowBody(raw);
        String message = b.string("message_id", null, false, 0, Integer.MAX_VALUE);
        String reason = b.string("reason", null, false, 0, Integer.MAX_VALUE, "unresolved", "incorrect");
        String detail = b.string("detail", "", false, 0, 2000);
        b.finish();
        return Map.of("issue_id", workflow.feedback(message, principal, reason, detail));
    }

    @GetMapping("/knowledge")
    public Map<String, Object> knowledge(HttpServletRequest request) { return workflow.knowledge(manager(request)); }

    @PostMapping("/knowledge")
    public Map<String, Object> publish(@RequestBody(required=false) String raw, HttpServletRequest request) {
        var principal = manager(request);
        return workflow.writeKnowledge(null, principal, body(raw));
    }

    @PatchMapping("/knowledge/{id}")
    public Map<String, Object> revise(@PathVariable String id, @RequestBody(required=false) String raw,
            HttpServletRequest request) {
        var principal = manager(request);
        return workflow.writeKnowledge(id, principal, body(raw));
    }

    @GetMapping("/knowledge/{id}/revisions")
    public Map<String, Object> revisions(@PathVariable String id, HttpServletRequest request) {
        return workflow.revisions(id, manager(request));
    }

    @PostMapping("/issues/{id}/verify")
    public Map<String, Object> verify(@PathVariable String id, HttpServletRequest request) {
        var result = workflow.verify(id, manager(request));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", result.status()); out.put("answer", result.answer()); out.put("release_id", result.releaseId());
        return out;
    }

    private static KnowledgeWorkflowService.KnowledgeWrite body(String raw) {
        var b = new WorkflowBody(raw);
        String title = b.string("title", null, false, 1, 200);
        String body = b.string("body", null, false, 1, 20000);
        int revision = b.revision();
        String status = b.string("status", "published", false, 0, Integer.MAX_VALUE, "published", "withdrawn");
        boolean shared = b.bool("shared_confirmed", false);
        b.finish();
        return new KnowledgeWorkflowService.KnowledgeWrite(title, body, revision, status, shared);
    }

    private Principal manager(HttpServletRequest request) {
        var principal = authenticate(request);
        if (!settings.current().accountMode()) throw KnowledgeWorkflowService.error(404, "not found");
        KnowledgeWorkflowService.requireManager(principal);
        return principal;
    }

    private Principal authenticate(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        var names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) { String name = names.nextElement(); headers.put(name,request.getHeader(name)); }
        return resolver.resolve(new RequestHeaders(headers), new AuthSettings());
    }
}
