package io.dpdk.assistant.auth;

import io.dpdk.assistant.contracts.identity.AuthSettings;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.identity.PrincipalResolver;
import io.dpdk.assistant.contracts.identity.RequestHeaders;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * PrincipalResolver 实现（契约 transaction-ports.json：identity.PrincipalResolver）。
 *
 * 三模式：local 免凭证唯一 local/admin 主体；静态应急 token 优先（常量时间比较）；
 * account 模式静态未命中再查 api_tokens/users（60s 正向缓存，失效操作清空）。
 * 401 一律带 WWW-Authenticate: Bearer，detail 区分 missing/invalid。
 *
 * 契约注意（已向集成者登记接口变更请求）：契约 AuthSettings 目前是无字段占位
 * record（"字段由 J2-01 登记补全"），而 contracts/** 归集成者独占；因此 resolve 的
 * settings 参数当前被忽略，生效配置来自注入的 {@link AuthSettingsProvider}。
 * 集成者补全 AuthSettings 字段后本实现改读参数即可（单点替换）。
 */
@Component
public class AuthPrincipalResolver implements PrincipalResolver {

    private final AuthSettingsProvider settings;
    private final UserAuthRepository users;

    public AuthPrincipalResolver(AuthSettingsProvider settings, UserAuthRepository users) {
        this.settings = settings;
        this.users = users;
    }

    @Override
    public Principal resolve(RequestHeaders headers, AuthSettings contractSettingsIgnored) {
        AuthServerSettings current = settings.current();
        if ("local".equals(current.authMode())) {
            return new Principal("local", List.of("admin"), 0, false);
        }
        String token = BearerTokens.extract(headers);
        if (token.isEmpty()) {
            throw unauthorized("missing or malformed bearer token");
        }
        for (var entry : current.staticTokens().entrySet()) {
            if (BearerTokens.constantTimeEquals(token, entry.getKey())) {
                return entry.getValue();
            }
        }
        if (current.accountMode()) {
            Principal resolved = users.resolveToken(token).orElse(null);
            if (resolved != null) {
                return resolved;
            }
        }
        throw unauthorized("invalid token");
    }

    private static ApiDetailException unauthorized(String detail) {
        return new ApiDetailException(HttpStatus.UNAUTHORIZED, detail,
                Map.of("WWW-Authenticate", "Bearer"));
    }
}
