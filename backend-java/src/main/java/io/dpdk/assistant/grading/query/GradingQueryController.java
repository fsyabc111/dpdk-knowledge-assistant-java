package io.dpdk.assistant.grading.query;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.admin.AdminClassScopePort;
import io.dpdk.assistant.admin.AdminTreeRules;
import io.dpdk.assistant.auth.AuthPrincipalResolver;
import io.dpdk.assistant.auth.AuthSettingsProvider;
import io.dpdk.assistant.contracts.identity.AuthSettings;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.identity.RequestHeaders;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import io.dpdk.assistant.session.PydanticQuery;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 教师成绩只读视图（对齐 backend/app/api/grading.py）。
 *
 * 仅 AUTH_MODE=account 下对 operator/admin/leader 开放；其他模式与无权限角色一律
 * 404（隐藏存在性）。树隔离：老师（admin、非 leader-like）只见自己创建的学生
 * （不含本人）；leader/operator/静态 token 主体不限。
 * 通用成绩视图与作业汇总（J2-03）不混用过滤逻辑：本端点不识别 assignment 参数。
 * 求值序：认证 401 → 查询参数 422 → 门禁 404。
 */
@RestController
public class GradingQueryController {

    /** GRADING_VIEWER_ROLES（与 user_service 角色白名单对应；viewer 是学生视角）。 */
    private static final List<String> VIEWER_ROLES = List.of("operator", "admin", "leader");
    private static final int MAX_LIMIT = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuthSettingsProvider settings;
    private final AuthPrincipalResolver resolver;
    private final GradingQueryRepository grading;
    private final AdminClassScopePort classScope;

    public GradingQueryController(AuthSettingsProvider settings, AuthPrincipalResolver resolver,
                                  GradingQueryRepository grading, AdminClassScopePort classScope) {
        this.settings = settings;
        this.resolver = resolver;
        this.grading = grading;
        this.classScope = classScope;
    }

    /* ------------------------------------------------------------------ */
    /* GET /api/grading/submissions                                        */
    /* ------------------------------------------------------------------ */

    @GetMapping("/api/grading/submissions")
    public ResponseEntity<String> listSubmissions(
            @RequestParam(value = "user_id", required = false) String userId,
            @RequestParam(value = "protocol", required = false) String protocol,
            @RequestParam(value = "limit", required = false) String limitRaw,
            @RequestParam(value = "offset", required = false) String offsetRaw,
            HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        int limit = PydanticQuery.intParam("limit", limitRaw, 200, 1, null);
        int offset = PydanticQuery.intParam("offset", offsetRaw, 0, 0, null);
        requireGradingViewer(principal);
        List<Map<String, Object>> submissions = new ArrayList<>();
        for (GradingQueryRepository.SubmissionRow row : grading.listSubmissions(userId, protocol,
                Math.min(limit, MAX_LIMIT), offset, scopeUserIds(principal))) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("user_id", row.userId());
            item.put("protocol", row.protocol());
            item.put("score", row.score());
            item.put("passed", row.passed());
            item.put("total", row.total());
            item.put("created_at", row.createdAt());
            submissions.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("submissions", submissions);
        return json(body);
    }

    /* ------------------------------------------------------------------ */
    /* GET /api/grading/summary                                            */
    /* ------------------------------------------------------------------ */

    @GetMapping("/api/grading/summary")
    public ResponseEntity<String> gradingSummary(HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        requireGradingViewer(principal);
        List<String> scope = scopeUserIds(principal);
        List<Map<String, Object>> byUser = new ArrayList<>();
        for (GradingQueryRepository.UserSummaryRow row : grading.aggregateByUser(scope)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("user_id", row.userId());
            item.put("submission_count", row.submissionCount());
            item.put("avg_score", row.avgScore());
            item.put("best_score", row.bestScore());
            item.put("last_submitted_at", row.lastSubmittedAt());
            byUser.add(item);
        }
        List<Map<String, Object>> byProtocol = new ArrayList<>();
        for (GradingQueryRepository.ProtocolSummaryRow row : grading.aggregateByProtocol(scope)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("protocol", row.protocol());
            item.put("submission_count", row.submissionCount());
            item.put("avg_score", row.avgScore());
            item.put("passed_total", row.passedTotal());
            item.put("total_total", row.totalTotal());
            item.put("student_count", row.studentCount());
            byProtocol.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("by_user", byUser);
        body.put("by_protocol", byProtocol);
        return json(body);
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                                */
    /* ------------------------------------------------------------------ */

    private Principal authenticate(HttpServletRequest request) {
        return resolver.resolve(requestHeaders(request), contractSettings());
    }

    /** 严格风格守卫：非 account 模式或无权限角色都不泄露该组端点的存在性。 */
    private void requireGradingViewer(Principal principal) {
        if (!settings.current().accountMode()
                || principal.roles().stream().noneMatch(VIEWER_ROLES::contains)) {
            throw new ApiDetailException(HttpStatus.NOT_FOUND, "not found");
        }
    }

    /**
     * _scope_user_ids：leader/operator/静态 token 主体 → null（不限）；
     * 老师（admin）→ 自建学生 id 集（不含本人，与 usage 的可见主体口径不同）。
     */
    private List<String> scopeUserIds(Principal principal) {
        if (principal.roles().contains("leader") || principal.roles().contains("operator")
                || AdminTreeRules.isLeaderLike(settings, principal)) {
            return null;
        }
        return classScope.studentIds(principal.subject());
    }

    private static ResponseEntity<String> json(Map<String, Object> body) throws Exception {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(MAPPER.writeValueAsString(body));
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

    private static AuthSettings contractSettings() {
        return new AuthSettings();
    }
}
