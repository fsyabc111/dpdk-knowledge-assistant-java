package io.dpdk.assistant.assignment.explanation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.auth.AuthPrincipalResolver;
import io.dpdk.assistant.auth.AuthSettingsProvider;
import io.dpdk.assistant.chat.ProductChatService;
import io.dpdk.assistant.contracts.identity.AuthSettings;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.identity.RequestHeaders;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** 已有正式成绩的按需讲解：复用产品任务入口，不发起新评测或修改成绩。 */
@RestController
public class SubmissionExplainController {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final AuthSettingsProvider settings;
    private final AuthPrincipalResolver resolver;
    private final ExplainDataService data;
    private final ProductChatService product;

    public SubmissionExplainController(AuthSettingsProvider settings, AuthPrincipalResolver resolver,
            ExplainDataService data, ProductChatService product) {
        this.settings = settings;
        this.resolver = resolver;
        this.data = data;
        this.product = product;
    }

    @PostMapping("/api/assignments/{assignmentId}/submissions/{submissionId}/explain")
    public ResponseEntity<String> explain(@PathVariable String assignmentId,
            @PathVariable String submissionId, HttpServletRequest request) throws Exception {
        Principal principal = resolver.resolve(headers(request), new AuthSettings());
        if (!settings.current().accountMode()) {
            throw new ApiDetailException(HttpStatus.NOT_FOUND, "not found");
        }
        product.checkRateLimit(principal.subject());
        ExplainDataService.Prepared prepared = data.prepare(assignmentId, submissionId, principal);
        var result = product.startExplain(principal, prepared.identity().sessionId(),
                prepared.identity().requestId(), prepared.question(), prepared.context(),
                () -> data.ensureSession(prepared, principal.subject()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", result.sessionId());
        body.put("task_id", result.taskId());
        body.put("status", result.status());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(body));
    }

    private static RequestHeaders headers(HttpServletRequest request) {
        Map<String, String> values = new LinkedHashMap<>();
        var names = request.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            values.put(name, request.getHeader(name));
        }
        return new RequestHeaders(values);
    }
}
