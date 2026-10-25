package io.dpdk.assistant.assignment.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.assignment.catalog.AssignmentJson;
import io.dpdk.assistant.auth.AuthPrincipalResolver;
import io.dpdk.assistant.auth.AuthSettingsProvider;
import io.dpdk.assistant.contracts.identity.AuthSettings;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.identity.RequestHeaders;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/assignments/{id}/grades 与 /export（对齐 assignments.py 成绩/导出路由）。
 *
 * 求值顺序：401（认证依赖）→ 404（非 account 模式或角色∉{operator,admin,leader}）
 * → 404（作业不存在）→ 业务。CSV：UTF-8 BOM、text/csv; charset=utf-8-sig、
 * Content-Disposition 安全文件名；汇总按当前作业项动态折算（验收 E 的汇总口径）。
 */
@RestController
public class AssignmentReportController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** assignments.GRADES_VIEWER_ROLES：成绩查看与导出角色。 */
    private static final List<String> GRADES_VIEWER_ROLES = List.of("operator", "admin", "leader");

    private final AuthSettingsProvider settings;
    private final AuthPrincipalResolver resolver;
    private final GradeReportService service;

    public AssignmentReportController(AuthSettingsProvider settings,
            AuthPrincipalResolver resolver, GradeReportService service) {
        this.settings = settings;
        this.resolver = resolver;
        this.service = service;
    }

    /* ------------------------------------------------------------------ */
    /* GET /api/assignments/{assignment_id}/grades                         */
    /* ------------------------------------------------------------------ */

    @GetMapping("/api/assignments/{assignment_id}/grades")
    public ResponseEntity<String> assignmentGrades(
            @PathVariable("assignment_id") String assignmentId, HttpServletRequest request)
            throws Exception {
        Principal principal = authenticate(request);
        requireGradesViewer(principal);
        GradeReportService.ScopedGrades grades = service.scopedGrades(assignmentId, principal);
        if (grades == null) {
            throw notFound();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assignment", AssignmentJson.assignmentInfo(grades.assignment()));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (GradeRow row : grades.rows()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("user_id", row.userId());
            item.put("class_teacher", row.classTeacher());
            item.put("scores", row.scores());
            item.put("total_earned", row.totalEarned());
            item.put("submission_count", row.submissionCount());
            item.put("last_submitted_at", row.lastSubmittedAt());
            rows.add(item);
        }
        body.put("rows", rows);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(MAPPER.writeValueAsString(body));
    }

    /* ------------------------------------------------------------------ */
    /* GET /api/assignments/{assignment_id}/export                         */
    /* ------------------------------------------------------------------ */

    @GetMapping("/api/assignments/{assignment_id}/export")
    public ResponseEntity<byte[]> exportAssignmentGrades(
            @PathVariable("assignment_id") String assignmentId, HttpServletRequest request) {
        Principal principal = authenticate(request);
        requireGradesViewer(principal);
        GradeReportService.ScopedGrades grades = service.scopedGrades(assignmentId, principal);
        if (grades == null) {
            throw notFound();
        }
        GradeReportService.CsvResult csv = service.exportCsv(grades, principal);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=utf-8-sig")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + csv.filename() + "\"")
                .body(csv.bytes());
    }

    /* ------------------------------------------------------------------ */
    /* 守卫                                                                 */
    /* ------------------------------------------------------------------ */

    private Principal authenticate(HttpServletRequest request) {
        return resolver.resolve(requestHeaders(request), contractSettings());
    }

    /** 成绩门禁：account 模式且 roles∩{operator,admin,leader}；否则 404。 */
    private void requireGradesViewer(Principal principal) {
        if (!settings.current().accountMode()) {
            throw notFound();
        }
        if (principal.roles().stream().noneMatch(GRADES_VIEWER_ROLES::contains)) {
            throw notFound();
        }
    }

    private static ApiDetailException notFound() {
        return new ApiDetailException(HttpStatus.NOT_FOUND, "not found");
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
