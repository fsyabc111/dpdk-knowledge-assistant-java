package io.dpdk.assistant.assignment.submission;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.assignment.catalog.AssignmentCatalogPort;
import io.dpdk.assistant.assignment.submission.AssignmentSubmissionService.SubmitOutcome;
import io.dpdk.assistant.assignment.submission.SubmitRequestParser.Parsed;
import io.dpdk.assistant.auth.AuthPrincipalResolver;
import io.dpdk.assistant.auth.AuthSettingsProvider;
import io.dpdk.assistant.contracts.grading.SubmittedSource;
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

/**
 * POST /api/assignments/{assignment_id}/submit（学生端，登录即可；无角色限制）。
 *
 * <p>求值序（account_principal 依赖形式，照源）：401（认证）→ 404（非 account
 * 模式整组隐藏）→ 请求解析 422/400/413（multipart 先于快照；JSON 的 413 在
 * grade_code 内，晚于快照/截止/协议判定）→ 快照 404 → 截止 400 → 协议 400
 * → 评测 413/429/504/502 → 落库 403（停号）→ 200。
 *
 * <p>响应为受理口径：score/passed/total/results 来自评测 payload，points/
 * earned/best_earned 按受理快照折算（汇总口径的动态折算归 J2-03 report）。
 */
@RestController
public class AssignmentSubmissionController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuthSettingsProvider settings;
    private final AuthPrincipalResolver resolver;
    private final AssignmentSubmissionService service;
    private final SubmissionSettings gradingSettings;

    public AssignmentSubmissionController(AuthSettingsProvider settings,
            AuthPrincipalResolver resolver, AssignmentSubmissionService service,
            SubmissionSettings gradingSettings) {
        this.settings = settings;
        this.resolver = resolver;
        this.service = service;
        this.gradingSettings = gradingSettings;
    }

    @PostMapping("/api/assignments/{assignmentId}/submit")
    public ResponseEntity<String> submit(@PathVariable String assignmentId,
            HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        requireAccountMode();
        Parsed parsed = SubmitRequestParser.parse(request,
                gradingSettings.gradingMaxCodeBytes());
        SubmittedSource source = new SubmittedSource(parsed.protocol(), parsed.code(),
                parsed.fileName(), null);
        SubmitOutcome outcome = service.submitFull(assignmentId, principal, source);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("score", outcome.score());
        body.put("passed", outcome.passed());
        body.put("total", outcome.total());
        body.put("results", outcome.results());
        body.put("points", outcome.points());
        body.put("earned", outcome.earned());
        body.put("best_earned", outcome.bestEarned());
        body.put("submission_id", outcome.submissionId());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(MAPPER.writeValueAsString(body));
    }

    /* ------------------------------------------------------------------ */
    /* 守卫与装配                                                           */
    /* ------------------------------------------------------------------ */

    /** 认证依赖（401 由 resolver 抛出；先于模式门禁与请求解析）。 */
    private Principal authenticate(HttpServletRequest request) {
        return resolver.resolve(requestHeaders(request), contractSettings());
    }

    /** 非 account 模式整组 404（不泄露端点存在性）。 */
    private void requireAccountMode() {
        if (!settings.current().accountMode()) {
            throw new ApiDetailException(HttpStatus.NOT_FOUND, "not found");
        }
    }

    private static RequestHeaders requestHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (request != null) {
            var names = request.getHeaderNames();
            while (names != null && names.hasMoreElements()) {
                String name = names.nextElement();
                headers.put(name, request.getHeader(name));
            }
        }
        return new RequestHeaders(headers);
    }

    /** 契约 AuthSettings 仍为占位（见 AuthPrincipalResolver 注释）。 */
    private static AuthSettings contractSettings() {
        return new AuthSettings();
    }
}
