package io.dpdk.assistant.assignment.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.admin.AdminUserRepository;
import io.dpdk.assistant.auth.AuthPrincipalResolver;
import io.dpdk.assistant.auth.AuthSettingsProvider;
import io.dpdk.assistant.contracts.identity.AuthSettings;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.identity.RequestHeaders;
import io.dpdk.assistant.contracts.model.Deadline;
import io.dpdk.assistant.contracts.model.HarnessPort;
import io.dpdk.assistant.contracts.model.HarnessReply;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/assignments 的定义侧端点（对齐 backend/app/api/assignments.py 的
 * protocols/CRUD/撤回/恢复；成绩与导出在 report.AssignmentReportController）。
 *
 * 求值顺序（与 admin.py 不同，源注释显式声明）：守卫以依赖形式挂在端点上，
 * 依赖求值先于请求体校验——401 → 404（非 account 模式或角色不符）→ 422（体）
 * → 业务（403 越权 / 400 规则 / 404 不存在）。带 body 的管理端点对无权限角色
 * 返回 404 而非 422（不泄露端点存在性）。
 *
 * MANAGER_ROLES={admin,leader}（发布/修改/撤回）；成绩/导出 viewer 角色归 report；
 * 学生列表/详情仅要求 account 模式登录。树隔离：老师（admin）学生视角只看
 * created_by 名下；scope 服务端按发布者角色产生（leader→all，admin→class）。
 */
@RestController
public class AssignmentCatalogController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** assignments.MANAGER_ROLES。 */
    private static final List<String> MANAGER_ROLES = List.of("admin", "leader");

    /** assignments.GRADES_VIEWER_ROLES（列表/详情的 manager 分支判定）。 */
    private static final List<String> GRADES_VIEWER_ROLES = List.of("operator", "admin", "leader");

    private static final Duration HARNESS_CALL_TIMEOUT = Duration.ofSeconds(30);

    private final AuthSettingsProvider settings;
    private final AuthPrincipalResolver resolver;
    private final AdminUserRepository users;
    private final AssignmentCatalogService service;
    private final HarnessPort harness;
    private final ClockPort clock;

    public AssignmentCatalogController(AuthSettingsProvider settings,
            AuthPrincipalResolver resolver, AdminUserRepository users,
            AssignmentCatalogService service, HarnessPort harness, ClockPort clock) {
        this.settings = settings;
        this.resolver = resolver;
        this.users = users;
        this.service = service;
        this.harness = harness;
        this.clock = clock;
    }

    /* ------------------------------------------------------------------ */
    /* GET /api/assignments/protocols（发布表单下拉）                       */
    /* ------------------------------------------------------------------ */

    @GetMapping("/api/assignments/protocols")
    public ResponseEntity<String> listProtocols(HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        requireManager(principal);
        // harness_grading.list_protocols：包装 MCP 调用，失败结构化降级（不 500、
        // 不把故障当作有效空清单——error 字段必须带回错误码）。
        Deadline deadline = new Deadline(clock.nowUtc().plus(HARNESS_CALL_TIMEOUT),
                clock.monotonicNanos() + HARNESS_CALL_TIMEOUT.toNanos());
        HarnessReply reply = harness.call("list_protocols", Map.of(), deadline);
        JsonNode payload = MAPPER.readTree(reply.payloadJson());
        boolean ok = payload.isObject() && payload.path("ok").asBoolean(false);
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, Object>> protocols = new ArrayList<>();
        if (!ok) {
            String error = payload.path("error").asText("");
            body.put("protocols", protocols);
            body.put("error", error.isEmpty() ? "list_protocols_failed" : error);
        } else {
            // Python：for item in (payload["protocols"] or []) if isinstance(item, dict) and item.get("name")
            JsonNode list = payload.path("protocols");
            if (list.isArray()) {
                for (JsonNode item : list) {
                    String name = item.path("name").asText("");
                    if (!item.isObject() || name.isEmpty()) {
                        continue;
                    }
                    Map<String, Object> protocol = new LinkedHashMap<>();
                    protocol.put("name", name);
                    protocol.put("cases", intValueOrZero(item.path("cases")));
                    protocol.put("categories", objectValueOrEmpty(item.path("categories")));
                    protocols.add(protocol);
                }
            }
            body.put("protocols", protocols);
            body.put("error", null);
        }
        return json(body);
    }

    /* ------------------------------------------------------------------ */
    /* POST /api/assignments（创建）                                        */
    /* ------------------------------------------------------------------ */

    @PostMapping("/api/assignments")
    public ResponseEntity<String> createAssignment(@RequestBody(required = false) String rawBody,
            HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        requireManager(principal);
        AssignmentPydanticBody.CreateFields f = AssignmentPydanticBody.parseCreate(rawBody);
        AssignmentRow assignment = service.createAssignment(principal.subject(),
                principal.roles(), f.title(), f.description() == null ? "" : f.description(),
                f.deadline(), f.items());
        return ResponseEntity.status(HttpStatus.CREATED)
                .contentType(MediaType.APPLICATION_JSON)
                .body(MAPPER.writeValueAsString(AssignmentJson.assignmentInfo(assignment)));
    }

    /* ------------------------------------------------------------------ */
    /* GET /api/assignments（manager 列表 / 学生列表）                      */
    /* ------------------------------------------------------------------ */

    @GetMapping("/api/assignments")
    public ResponseEntity<String> listAssignments(HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        requireAccountMode();
        Map<String, Object> body = new LinkedHashMap<>();
        if (principal.roles().stream().anyMatch(GRADES_VIEWER_ROLES::contains)) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (AssignmentCatalogRepository.ManagerListRow row
                    : service.listForManager(principal.subject(), principal.roles())) {
                Map<String, Object> item = AssignmentJson.assignmentInfo(row.assignment());
                item.put("submission_count", row.submissionCount());
                item.put("student_count", row.studentCount());
                rows.add(item);
            }
            body.put("assignments", rows);
        } else {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (StudentAssignmentView view : service.listVisibleForStudent(
                    principal.subject(), studentCreatedBy(principal))) {
                rows.add(AssignmentJson.studentAssignment(view));
            }
            body.put("assignments", rows);
        }
        return json(body);
    }

    /* ------------------------------------------------------------------ */
    /* GET /api/assignments/{assignment_id}（详情）                          */
    /* ------------------------------------------------------------------ */

    @GetMapping("/api/assignments/{assignment_id}")
    public ResponseEntity<String> getAssignment(@PathVariable("assignment_id") String assignmentId,
            HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        requireAccountMode();
        if (principal.roles().stream().anyMatch(GRADES_VIEWER_ROLES::contains)) {
            // admin 只能看自己创建的作业；operator/leader 不受限。
            AssignmentRow assignment = service.getAssignment(assignmentId).orElse(null);
            boolean manageable = assignment != null
                    && (canManage(assignment, principal)
                            || principal.roles().contains("operator"));
            if (!manageable) {
                throw notFound();
            }
            return json(AssignmentJson.assignmentInfo(assignment));
        }
        StudentAssignmentView view = service.getVisibleAssignment(assignmentId,
                principal.subject(), studentCreatedBy(principal)).orElse(null);
        if (view == null) {
            throw notFound();
        }
        return json(AssignmentJson.studentAssignment(view));
    }

    /* ------------------------------------------------------------------ */
    /* PATCH /api/assignments/{assignment_id}（修改）                        */
    /* ------------------------------------------------------------------ */

    @PatchMapping("/api/assignments/{assignment_id}")
    public ResponseEntity<String> updateAssignment(
            @PathVariable("assignment_id") String assignmentId,
            @RequestBody(required = false) String rawBody, HttpServletRequest request)
            throws Exception {
        Principal principal = authenticate(request);
        requireManager(principal);
        AssignmentPydanticBody.UpdateFields f = AssignmentPydanticBody.parseUpdate(rawBody);
        AssignmentRow assignment;
        try {
            assignment = service.updateAssignment(assignmentId, principal.subject(),
                    principal.roles(), f.title(), f.description(), f.deadline(), f.items())
                    .orElse(null);
        } catch (AssignmentPermissionException e) {
            throw new ApiDetailException(HttpStatus.FORBIDDEN, e.getMessage());
        }
        if (assignment == null) {
            throw notFound();
        }
        return json(AssignmentJson.assignmentInfo(assignment));
    }

    /* ------------------------------------------------------------------ */
    /* POST /{assignment_id}/withdraw 与 /reactivate                        */
    /* ------------------------------------------------------------------ */

    @PostMapping("/api/assignments/{assignment_id}/withdraw")
    public ResponseEntity<String> withdrawAssignment(
            @PathVariable("assignment_id") String assignmentId, HttpServletRequest request)
            throws Exception {
        return setStatus(assignmentId, AssignmentCatalogRepository.STATUS_WITHDRAWN, request);
    }

    @PostMapping("/api/assignments/{assignment_id}/reactivate")
    public ResponseEntity<String> reactivateAssignment(
            @PathVariable("assignment_id") String assignmentId, HttpServletRequest request)
            throws Exception {
        return setStatus(assignmentId, AssignmentCatalogRepository.STATUS_ACTIVE, request);
    }

    private ResponseEntity<String> setStatus(String assignmentId, String status,
            HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        requireManager(principal);
        AssignmentRow assignment;
        try {
            assignment = service.setStatus(assignmentId, status, principal.subject(),
                    principal.roles()).orElse(null);
        } catch (AssignmentPermissionException e) {
            throw new ApiDetailException(HttpStatus.FORBIDDEN, e.getMessage());
        }
        if (assignment == null) {
            throw notFound();
        }
        return json(AssignmentJson.assignmentInfo(assignment));
    }

    /* ------------------------------------------------------------------ */
    /* 守卫与装配                                                           */
    /* ------------------------------------------------------------------ */

    /** 认证依赖（401 由 resolver 抛出；先于角色门禁与请求体校验）。 */
    private Principal authenticate(HttpServletRequest request) {
        return resolver.resolve(requestHeaders(request), contractSettings());
    }

    /** 非 account 模式整组 404（不泄露端点存在性）。 */
    private void requireAccountMode() {
        if (!settings.current().accountMode()) {
            throw notFound();
        }
    }

    /** 管理门禁：account 模式且 roles∩{admin,leader}；否则 404。 */
    private void requireManager(Principal principal) {
        requireAccountMode();
        if (principal.roles().stream().noneMatch(MANAGER_ROLES::contains)) {
            throw notFound();
        }
    }

    /** 学生的班级归属（users.created_by）；静态 token/已删账号为 null（未分配）。 */
    private String studentCreatedBy(Principal principal) {
        return users.getUser(principal.subject()).map(AdminUserRepository.UserRow::createdBy)
                .orElse(null);
    }

    /** 修改/撤回权限 = 创建者本人或 leader（_can_manage）。 */
    private static boolean canManage(AssignmentRow assignment, Principal principal) {
        return assignment.createdBy().equals(principal.subject())
                || principal.roles().contains("leader");
    }

    private static int intValueOrZero(JsonNode node) {
        if (node == null) {
            return 0;
        }
        return node.isNumber() ? node.asInt() : 0;
    }

    private static Map<String, Object> objectValueOrEmpty(JsonNode node) {
        if (node != null && node.isObject()) {
            return MAPPER.convertValue(node,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        }
        return new LinkedHashMap<>();
    }

    private static ApiDetailException notFound() {
        return new ApiDetailException(HttpStatus.NOT_FOUND, "not found");
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

    /** 契约 AuthSettings 仍为占位（见 AuthPrincipalResolver 注释）。 */
    private static AuthSettings contractSettings() {
        return new AuthSettings();
    }
}
