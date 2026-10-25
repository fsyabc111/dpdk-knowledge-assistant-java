package io.dpdk.assistant.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.identity.AuthSettings;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.identity.RequestHeaders;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/auth/* 端点（对齐 backend/app/api/auth.py）。
 *
 * 顺序契约（evidence/J2-01/endpoints-matrix.md §0）：请求体 422 → 认证依赖 401
 * （logout/change-password）→ 非 account 模式 404 → 每 IP 限流 429 → 学号锁定 429
 * （密码验证之前）→ 业务 400/401 → 2xx。错误一律 ApiDetailException（{"detail":...}
 * 精确渲染，Retry-After / WWW-Authenticate 由抛出方指定）。
 */
@RestController
public class AuthController {

    /** 学号格式：U + 4 位年份 + 7 位序号（user_service.USER_ID_PATTERN）。 */
    static final Pattern USER_ID_PATTERN = Pattern.compile("^U\\d{11}$");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<PydanticBody.StringField> LOGIN_FIELDS = List.of(
            new PydanticBody.StringField("username", 1, 64),
            new PydanticBody.StringField("password", 1, 256));
    private static final List<PydanticBody.StringField> CHANGE_PASSWORD_FIELDS = List.of(
            new PydanticBody.StringField("old_password", 1, 256),
            new PydanticBody.StringField("new_password", 1, 256));
    private static final List<PydanticBody.StringField> RESET_REQUEST_FIELDS = List.of(
            new PydanticBody.StringField("username", 1, 64));
    private static final List<PydanticBody.StringField> RESET_CONFIRM_FIELDS = List.of(
            new PydanticBody.StringField("token", 1, 256),
            new PydanticBody.StringField("new_password", 1, 256));

    private final AuthSettingsProvider settings;
    private final UserAuthRepository users;
    private final AuthPrincipalResolver resolver;
    private final SlidingWindowRateLimiter loginRateLimiter;
    private final SlidingWindowRateLimiter resetRateLimiter;
    private final LoginLockout lockout;

    public AuthController(AuthSettingsProvider settings, UserAuthRepository users,
                          AuthPrincipalResolver resolver, io.dpdk.assistant.contracts.platform.ClockPort clock) {
        this.settings = settings;
        this.users = users;
        this.resolver = resolver;
        this.loginRateLimiter = new SlidingWindowRateLimiter(
                AuthServerSettings.LOGIN_RATE_LIMIT_PER_MINUTE, "login", clock);
        this.resetRateLimiter = new SlidingWindowRateLimiter(
                AuthServerSettings.RESET_REQUEST_RATE_LIMIT_PER_MINUTE, "password_reset", clock);
        this.lockout = new LoginLockout(clock);
    }

    /* ------------------------------------------------------------------ */
    /* POST /api/auth/login                                                */
    /* ------------------------------------------------------------------ */

    @PostMapping("/api/auth/login")
    public ResponseEntity<String> login(@RequestBody(required = false) String rawBody,
                                        HttpServletRequest request) throws Exception {
        Map<String, String> f = PydanticBody.parseStringObject(rawBody, LOGIN_FIELDS);
        requireAccountMode();
        loginRateLimiter.check("login:" + clientIp(request));
        // 锁定检查在密码验证之前：锁定期内存在/不存在的学号应答完全一致（验收 A/B）。
        lockout.check(f.get("username"));
        if (!users.verifyCredentials(f.get("username"), f.get("password"))) {
            lockout.recordFailure(f.get("username"));
            throw new ApiDetailException(HttpStatus.UNAUTHORIZED, "invalid username or password");
        }
        lockout.recordSuccess(f.get("username"));
        UserAuthRepository.IssuedToken issued = users.issueToken(f.get("username"));
        UserAuthRepository.UserAuthRow user = users.getUser(f.get("username")).orElseThrow(
                () -> new IllegalStateException("verified user disappeared: " + f.get("username")));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", issued.token());
        body.put("subject", f.get("username"));
        body.put("roles", user.roles());
        body.put("expires_at", issued.expiresAtIso());
        body.put("must_change_password", user.mustChangePassword());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(MAPPER.writeValueAsString(body));
    }

    /* ------------------------------------------------------------------ */
    /* POST /api/auth/logout                                               */
    /* ------------------------------------------------------------------ */

    @PostMapping("/api/auth/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        resolver.resolve(requestHeaders(request), contractSettings());
        requireAccountMode();
        String token = BearerTokens.extract(request);
        if (!token.isEmpty()) {
            // 静态兜底 token 不在 api_tokens 表中，吊销对其为空操作。
            users.revokeToken(token);
        }
        return ResponseEntity.noContent().build();
    }

    /* ------------------------------------------------------------------ */
    /* POST /api/auth/change-password                                      */
    /* ------------------------------------------------------------------ */

    @PostMapping("/api/auth/change-password")
    public ResponseEntity<Void> changePassword(@RequestBody(required = false) String rawBody,
                                               HttpServletRequest request) {
        // FastAPI 依赖(require_auth)先于请求体校验：401 → 422 → 404（golden
        // change-password::unauthorized 以空体 {} 得 401 而非 422 为准）。
        Principal principal = resolver.resolve(requestHeaders(request), contractSettings());
        Map<String, String> f = PydanticBody.parseStringObject(rawBody, CHANGE_PASSWORD_FIELDS);
        requireAccountMode();
        String keepToken = BearerTokens.extract(request);
        boolean ok;
        try {
            ok = users.changePassword(principal.subject(), f.get("old_password"), f.get("new_password"),
                    keepToken.isEmpty() ? null : keepToken);
        } catch (PasswordRuleException e) {
            throw new ApiDetailException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (!ok) {
            // 旧密码错误（或静态兜底 token 的 subject 不是账号）。
            throw new ApiDetailException(HttpStatus.BAD_REQUEST, "old password is incorrect");
        }
        return ResponseEntity.noContent().build();
    }

    /* ------------------------------------------------------------------ */
    /* POST /api/auth/password-reset-requests                              */
    /* ------------------------------------------------------------------ */

    @PostMapping("/api/auth/password-reset-requests")
    public ResponseEntity<Void> createPasswordResetRequest(
            @RequestBody(required = false) String rawBody, HttpServletRequest request) {
        Map<String, String> f = PydanticBody.parseStringObject(rawBody, RESET_REQUEST_FIELDS);
        requireAccountMode();
        resetRateLimiter.check("reset-request:" + clientIp(request));
        if (!USER_ID_PATTERN.matcher(f.get("username")).matches()) {
            // 只校验格式，不泄露账号是否存在。
            throw new ApiDetailException(HttpStatus.BAD_REQUEST, "invalid username format");
        }
        users.createResetRequest(f.get("username"));
        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    /* ------------------------------------------------------------------ */
    /* POST /api/auth/password-reset                                       */
    /* ------------------------------------------------------------------ */

    @PostMapping("/api/auth/password-reset")
    public ResponseEntity<Void> confirmPasswordReset(
            @RequestBody(required = false) String rawBody, HttpServletRequest request) {
        Map<String, String> f = PydanticBody.parseStringObject(rawBody, RESET_CONFIRM_FIELDS);
        requireAccountMode();
        resetRateLimiter.check("reset-confirm:" + clientIp(request));
        if (!users.consumeResetToken(f.get("token"), f.get("new_password"))) {
            // 无效/过期/已用/新密不合规统一 400，不区分原因（验收 D）。
            throw new ApiDetailException(HttpStatus.BAD_REQUEST, "invalid or expired reset token");
        }
        return ResponseEntity.noContent().build();
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                                 */
    /* ------------------------------------------------------------------ */

    /** 非 account 模式隐藏端点组（404 不暴露存在性）。 */
    private void requireAccountMode() {
        if (!settings.current().accountMode()) {
            throw new ApiDetailException(HttpStatus.NOT_FOUND, "not found");
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String ip = request == null ? null : request.getRemoteAddr();
        return ip == null ? "unknown" : ip;
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

    /**
     * 契约 AuthSettings 目前是无字段占位（见 AuthPrincipalResolver 注释）；resolver
     * 实际读取注入的 AuthSettingsProvider。集成者补全字段后此参数才有承载意义。
     */
    private static AuthSettings contractSettings() {
        return new AuthSettings();
    }
}
