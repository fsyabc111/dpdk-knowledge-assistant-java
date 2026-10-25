package io.dpdk.assistant.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.admin.AdminPydanticBody.Field;
import io.dpdk.assistant.admin.AdminPydanticBody.Kind;
import io.dpdk.assistant.admin.AdminUserRepository.UserRow;
import io.dpdk.assistant.auth.AuthPrincipalResolver;
import io.dpdk.assistant.auth.AuthSettingsProvider;
import io.dpdk.assistant.auth.CredentialInvalidationPort;
import io.dpdk.assistant.auth.PasswordResetApprovalPort;
import io.dpdk.assistant.auth.PasswordResetApprovalPort.ResetRequestRow;
import io.dpdk.assistant.contracts.identity.AuthSettings;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.identity.RequestHeaders;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.OffsetDateTime;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/admin/* 端点（对齐 backend/app/api/admin.py）。
 *
 * 求值顺序（evidence/J2-02/permission-matrix.md）：认证依赖 401 → 请求体 422 →
 * 管理门禁 404（非 account 模式或非 admin/leader 一律 404，不泄露端点存在性）→
 * 端点内自保护/树隔离/业务分支。错误一律 ApiDetailException 精确渲染。
 *
 * 与 auth 包协作：改密/停号经 CredentialInvalidationPort（token 立即失效）；
 * 重置审批经 PasswordResetApprovalPort；停号提交后经 LocalTaskCancellationPort
 * best-effort 取消本进程任务（真实执行体 integration_pending，J3-01 接线）。
 */
@RestController
public class AdminController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<Field> CREATE_USERS_FIELDS = List.of(
            new Field("ids", Kind.STRING_LIST, true, 1, 500, null),
            new Field("roles", Kind.STRING_LIST, false, null, null, null),
            new Field("daily_quota", Kind.INT, false, null, null, 0));
    private static final List<Field> UPDATE_USER_FIELDS = List.of(
            new Field("roles", Kind.STRING_LIST, false, null, null, null),
            new Field("daily_quota", Kind.INT, false, null, null, 0),
            new Field("created_by", Kind.STRING, false, 1, 64, null));
    private static final List<Field> STATUS_FIELDS = List.of(
            new Field("enabled", Kind.BOOL, true, null, null, null));
    private static final List<Field> SET_PASSWORD_FIELDS = List.of(
            new Field("new_password", Kind.STRING, true, 1, 256, null));
    private static final List<Field> DELETE_USERS_FIELDS = List.of(
            new Field("ids", Kind.STRING_LIST, true, 1, 500, null),
            new Field("password", Kind.STRING, true, 1, 256, null));
    private static final List<Field> ASSIGN_FIELDS = List.of(
            new Field("ids", Kind.STRING_LIST, true, 1, 500, null),
            new Field("created_by", Kind.STRING, true, 1, 64, null));

    private final AuthSettingsProvider settings;
    private final AuthPrincipalResolver resolver;
    private final AdminUserRepository users;
    private final CredentialInvalidationPort invalidation;
    private final PasswordResetApprovalPort approvals;
    private final LocalTaskCancellationPort taskCancellation;

    public AdminController(AuthSettingsProvider settings, AuthPrincipalResolver resolver,
                           AdminUserRepository users, CredentialInvalidationPort invalidation,
                           PasswordResetApprovalPort approvals,
                           LocalTaskCancellationPort taskCancellation) {
        this.settings = settings;
        this.resolver = resolver;
        this.users = users;
        this.invalidation = invalidation;
        this.approvals = approvals;
        this.taskCancellation = taskCancellation;
    }

    /* ------------------------------------------------------------------ */
    /* GET /api/admin/users                                                */
    /* ------------------------------------------------------------------ */

    @GetMapping("/api/admin/users")
    public ResponseEntity<String> listUsers(HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        requireManager(principal);
        String scope = AdminTreeRules.scopeCreator(settings, principal);
        List<UserRow> rows = new ArrayList<>();
        if (scope == null) {
            rows.addAll(users.listUsers(null));
        } else {
            // 老师视角：本人行在前 + 自建学生。
            users.getUser(principal.subject()).ifPresent(rows::add);
            rows.addAll(users.listUsers(scope));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("users", rows.stream().map(AdminController::userInfo).toList());
        return json(body);
    }

    /* ------------------------------------------------------------------ */
    /* POST /api/admin/users                                               */
    /* ------------------------------------------------------------------ */

    @PostMapping("/api/admin/users")
    public ResponseEntity<String> createUsers(@RequestBody(required = false) String rawBody,
                                              HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        Map<String, Object> f = AdminPydanticBody.parse(rawBody, CREATE_USERS_FIELDS);
        requireManager(principal);
        @SuppressWarnings("unchecked")
        List<String> requested = (List<String>) f.get("roles");
        // Python `req.roles or ["viewer"]`：null 或空列表都回落 viewer。
        List<String> roles = requested == null || requested.isEmpty()
                ? List.of("viewer") : requested;
        if (AdminTreeRules.isLeaderLike(settings, principal)) {
            // 防提权：leader 只能由 CLI 引导创建，API 不开放。
            if (roles.contains("leader")) {
                throw badRequest("role 'leader' can only be granted via the CLI bootstrap");
            }
        } else if (roles.stream().anyMatch(r -> !"viewer".equals(r))) {
            // 老师只能创建学生。
            throw badRequest("teachers can only create viewer accounts");
        }
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) f.get("ids");
        Integer quota = (Integer) f.get("daily_quota");
        AdminUserRepository.IdsResult result;
        try {
            result = users.createUsers(ids, roles, quota, principal.subject());
        } catch (AdminRuleException e) {
            throw badRequest(e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("created", result.affected());
        body.put("skipped", result.skipped());
        return json(body);
    }

    /* ------------------------------------------------------------------ */
    /* POST /api/admin/users/{user_id}/reset-password                      */
    /* ------------------------------------------------------------------ */

    @PostMapping("/api/admin/users/{user_id}/reset-password")
    public ResponseEntity<Void> resetPassword(@PathVariable("user_id") String userId,
                                              HttpServletRequest request) {
        Principal principal = authenticate(request);
        requireManager(principal);
        requireManageable(principal, userId);
        if (!invalidation.resetPasswordToInitial(userId)) {
            throw notFound();
        }
        return ResponseEntity.noContent().build();
    }

    /* ------------------------------------------------------------------ */
    /* POST /api/admin/users/{user_id}/set-password                        */
    /* ------------------------------------------------------------------ */

    @PostMapping("/api/admin/users/{user_id}/set-password")
    public ResponseEntity<Void> setPassword(@PathVariable("user_id") String userId,
                                            @RequestBody(required = false) String rawBody,
                                            HttpServletRequest request) {
        Principal principal = authenticate(request);
        Map<String, Object> f = AdminPydanticBody.parse(rawBody, SET_PASSWORD_FIELDS);
        requireManager(principal);
        requireManageable(principal, userId);
        boolean ok;
        try {
            ok = invalidation.setPasswordByAdmin(userId, (String) f.get("new_password"));
        } catch (RuntimeException e) {
            // 端口文档约定抛 PasswordRuleException（J2-01 转 400 detail）；该类型在 auth
            // 包内非 public（接口变更请求已登记 handoff），按类名识别并把文案原样透传。
            if ("PasswordRuleException".equals(e.getClass().getSimpleName())) {
                throw badRequest(e.getMessage());
            }
            throw e;
        }
        if (!ok) {
            throw notFound();
        }
        return ResponseEntity.noContent().build();
    }

    /* ------------------------------------------------------------------ */
    /* GET /api/admin/password-reset-requests                              */
    /* ------------------------------------------------------------------ */

    @GetMapping("/api/admin/password-reset-requests")
    public ResponseEntity<String> listResetRequests(
            @RequestParam(value = "status", required = false, defaultValue = "pending") String status,
            HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        requireManager(principal);
        String scope = AdminTreeRules.scopeCreator(settings, principal);
        List<Map<String, Object>> requests = new ArrayList<>();
        for (ResetRequestRow row : approvals.listResetRequests(status, scope)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("user_id", row.userId());
            // 港口返回 isoformat（T 分隔）；HTTP 渲染为 Python str(datetime)（空格分隔）。
            item.put("created_at", AdminTime.pythonStr(
                    OffsetDateTime.parse(row.createdAt()).toInstant()));
            requests.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requests", requests);
        return json(body);
    }

    /* ------------------------------------------------------------------ */
    /* POST /api/admin/password-reset-requests/{request_id}/approve        */
    /* ------------------------------------------------------------------ */

    @PostMapping("/api/admin/password-reset-requests/{request_id}/approve")
    public ResponseEntity<String> approveResetRequest(
            @PathVariable("request_id") String requestId, HttpServletRequest request)
            throws Exception {
        Principal principal = authenticate(request);
        requireManager(principal);
        String scope = AdminTreeRules.scopeCreator(settings, principal);
        var issue = approvals.approveResetRequest(requestId, principal.subject(), scope);
        if (issue.isEmpty()) {
            throw notFound();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reset_token", issue.get().token());
        body.put("expires_at", issue.get().expiresAtIso());
        return json(body);
    }

    /* ------------------------------------------------------------------ */
    /* POST /api/admin/password-reset-requests/{request_id}/reject         */
    /* ------------------------------------------------------------------ */

    @PostMapping("/api/admin/password-reset-requests/{request_id}/reject")
    public ResponseEntity<Void> rejectResetRequest(@PathVariable("request_id") String requestId,
                                                   HttpServletRequest request) {
        Principal principal = authenticate(request);
        requireManager(principal);
        String scope = AdminTreeRules.scopeCreator(settings, principal);
        if (!approvals.rejectResetRequest(requestId, principal.subject(), scope)) {
            throw notFound();
        }
        return ResponseEntity.noContent().build();
    }

    /* ------------------------------------------------------------------ */
    /* POST /api/admin/users/{user_id}/status                              */
    /* ------------------------------------------------------------------ */

    @PostMapping("/api/admin/users/{user_id}/status")
    public ResponseEntity<Void> setUserStatus(@PathVariable("user_id") String userId,
                                              @RequestBody(required = false) String rawBody,
                                              HttpServletRequest request) {
        Principal principal = authenticate(request);
        Map<String, Object> f = AdminPydanticBody.parse(rawBody, STATUS_FIELDS);
        requireManager(principal);
        boolean enabled = (Boolean) f.get("enabled");
        if (userId.equals(principal.subject()) && !enabled) {
            throw badRequest("cannot disable your own account");
        }
        requireManageable(principal, userId);
        boolean changed = users.setEnabled(userId, enabled);
        if (!changed) {
            throw notFound();
        }
        if (!enabled) {
            // 库行/成绩已在事务内终态化；此处只 best-effort 取消本进程执行协程
            // （异常吞掉，与源一致；真实执行体见 LocalTaskCancellationPort 登记）。
            try {
                taskCancellation.cancelLocalForUser(userId);
            } catch (RuntimeException e) {
                // 有意吞掉：停号已提交，取消失败不影响终态（integration_pending 项下记录）。
            }
        }
        return ResponseEntity.noContent().build();
    }

    /* ------------------------------------------------------------------ */
    /* PATCH /api/admin/users/{user_id}                                    */
    /* ------------------------------------------------------------------ */

    @PatchMapping("/api/admin/users/{user_id}")
    public ResponseEntity<String> updateUser(@PathVariable("user_id") String userId,
                                             @RequestBody(required = false) String rawBody,
                                             HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        Map<String, Object> f = AdminPydanticBody.parse(rawBody, UPDATE_USER_FIELDS);
        requireManager(principal);
        boolean leaderLike = AdminTreeRules.isLeaderLike(settings, principal);
        @SuppressWarnings("unchecked")
        List<String> roles = (List<String>) f.get("roles");
        String createdBy = (String) f.get("created_by");
        if (!leaderLike && (roles != null || createdBy != null)) {
            // 老师只能调配额，不能改角色与归属。
            throw badRequest("only leaders can change roles or ownership");
        }
        if (roles != null && roles.contains("leader")) {
            throw badRequest("role 'leader' can only be granted via the CLI bootstrap");
        }
        if (userId.equals(principal.subject()) && roles != null) {
            throw badRequest("cannot change your own roles");
        }
        requireManageable(principal, userId);
        Integer quota = (Integer) f.get("daily_quota");
        UserRow updated;
        try {
            updated = users.updateUser(userId, roles, quota, createdBy).orElse(null);
        } catch (AdminRuleException e) {
            throw badRequest(e.getMessage());
        }
        if (updated == null) {
            throw notFound();
        }
        return json(userInfo(updated));
    }

    /* ------------------------------------------------------------------ */
    /* POST /api/admin/users/delete                                        */
    /* ------------------------------------------------------------------ */

    @PostMapping("/api/admin/users/delete")
    public ResponseEntity<String> deleteUsers(@RequestBody(required = false) String rawBody,
                                              HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        Map<String, Object> f = AdminPydanticBody.parse(rawBody, DELETE_USERS_FIELDS);
        requireManager(principal);
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) f.get("ids");
        AdminUserRepository.IdsResult result;
        try {
            result = users.deleteUsers(principal, ids, (String) f.get("password"));
        } catch (ActorPasswordException e) {
            throw new ApiDetailException(HttpStatus.FORBIDDEN, "password verification failed");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("deleted", result.affected());
        body.put("skipped", result.skipped());
        return json(body);
    }

    /* ------------------------------------------------------------------ */
    /* POST /api/admin/users/assign                                        */
    /* ------------------------------------------------------------------ */

    @PostMapping("/api/admin/users/assign")
    public ResponseEntity<String> assignStudents(@RequestBody(required = false) String rawBody,
                                                 HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        Map<String, Object> f = AdminPydanticBody.parse(rawBody, ASSIGN_FIELDS);
        requireManager(principal);
        if (!AdminTreeRules.isLeaderLike(settings, principal)) {
            throw badRequest("only leaders can change ownership");
        }
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) f.get("ids");
        AdminUserRepository.IdsResult result;
        try {
            result = users.assignStudents(ids, (String) f.get("created_by"));
        } catch (AdminRuleException e) {
            throw badRequest(e.getMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("assigned", result.affected());
        body.put("skipped", result.skipped());
        return json(body);
    }

    /* ------------------------------------------------------------------ */
    /* GET /api/admin/audit-log                                            */
    /* ------------------------------------------------------------------ */

    @GetMapping("/api/admin/audit-log")
    public ResponseEntity<String> listAuditLog(HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        requireManager(principal);
        String scope = AdminTreeRules.isLeaderLike(settings, principal)
                ? null : principal.subject();
        List<Map<String, Object>> entries = new ArrayList<>();
        for (AdminUserRepository.AuditRow row : users.listAuditLog(scope)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("actor", row.actor());
            item.put("action", row.action());
            item.put("targets", row.targets());
            item.put("created_at", AdminTime.pythonStr(row.createdAt()));
            entries.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("entries", entries);
        return json(body);
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                                 */
    /* ------------------------------------------------------------------ */

    /** 认证依赖（401 由 resolver 抛出，先于请求体 422 与门禁 404）。 */
    private Principal authenticate(HttpServletRequest request) {
        return resolver.resolve(requestHeaders(request), contractSettings());
    }

    /** 管理门禁：非 account 模式或非 admin/leader → 404（不泄露端点存在性）。 */
    private void requireManager(Principal principal) {
        if (!AdminTreeRules.isManager(settings, principal)) {
            throw notFound();
        }
    }

    /** 取目标用户并强制树隔离；不可见/不可管一律 404（admin.py _get_manageable_target）。 */
    private void requireManageable(Principal principal, String userId) {
        UserRow target = users.getUser(userId).orElse(null);
        if (target == null) {
            throw notFound();
        }
        if (!AdminTreeRules.isLeaderLike(settings, principal)
                && !AdminTreeRules.canManage(principal, target.createdBy(), target.roles())) {
            throw notFound();
        }
    }

    private static Map<String, Object> userInfo(UserRow row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", row.id());
        out.put("roles", row.roles());
        out.put("daily_quota", row.dailyQuota());
        out.put("enabled", row.enabled());
        out.put("must_change_password", row.mustChangePassword());
        out.put("created_at", AdminTime.pythonStr(row.createdAt()));
        out.put("created_by", row.createdBy());
        return out;
    }

    private static ApiDetailException badRequest(String detail) {
        return new ApiDetailException(HttpStatus.BAD_REQUEST, detail);
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

    /** 契约 AuthSettings 仍为占位（见 AuthPrincipalResolver 注释），实际配置走注入 Provider。 */
    private static AuthSettings contractSettings() {
        return new AuthSettings();
    }
}
