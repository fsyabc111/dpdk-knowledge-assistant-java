package io.dpdk.assistant.auth;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.identity.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 认证服务端设置快照（对应 Python Settings 的认证相关字段与默认值）。
 *
 * 默认值与 backend/app/config.py 一致：auth_mode=local、api_tokens_json={}、
 * auth_token_ttl_days=7、password_min_length=8、bcrypt_rounds=12、
 * llm_daily_quota_default=200。静态 token 解析保持 JSON 插入序，并复刻
 * config.py 的静态 token 校验（≥32 字符、roles 非空子集、daily_quota 非负整数）。
 */
public record AuthServerSettings(
        String authMode,
        Map<String, Principal> staticTokens,
        int tokenTtlDays,
        int passwordMinLength,
        int bcryptRounds,
        int llmDailyQuotaDefault) {

    /** 与 user_service.ALLOWED_ROLES / config.py allowed_roles 一致。 */
    public static final Set<String> ALLOWED_ROLES = Set.of("viewer", "operator", "admin", "leader");

    public static final int LOGIN_RATE_LIMIT_PER_MINUTE = 10;
    public static final int RESET_REQUEST_RATE_LIMIT_PER_MINUTE = 5;
    public static final int LOGIN_MAX_FAILURES = 5;
    public static final int LOGIN_LOCKOUT_MINUTES = 15;
    public static final int RESET_TOKEN_TTL_MINUTES = 30;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static AuthServerSettings defaults() {
        return new AuthServerSettings("local", new LinkedHashMap<>(), 7, 8, 12, 200);
    }

    public boolean accountMode() {
        return "account".equals(authMode);
    }

    /** 复刻 Python core.auth.parse_tokens + config.py 静态 token 校验（错误时抛 IllegalStateException）。 */
    public static AuthServerSettings of(String authMode, String apiTokensJson, int tokenTtlDays,
                                        int passwordMinLength, int bcryptRounds, int llmDailyQuotaDefault) {
        Map<String, Principal> tokens = parseStaticTokens(apiTokensJson, llmDailyQuotaDefault);
        if ("token".equals(authMode) && tokens.isEmpty()) {
            throw new IllegalStateException("AUTH_MODE=token requires at least one entry in API_TOKENS_JSON");
        }
        return new AuthServerSettings(authMode, tokens, tokenTtlDays, passwordMinLength,
                bcryptRounds, llmDailyQuotaDefault);
    }

    static Map<String, Principal> parseStaticTokens(String apiTokensJson, int dailyQuotaDefault) {
        Map<String, Object> raw;
        try {
            String source = apiTokensJson == null || apiTokensJson.isEmpty() ? "{}" : apiTokensJson;
            raw = MAPPER.readValue(source, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("API_TOKENS_JSON is not valid JSON: " + e.getMessage(), e);
        }
        Map<String, Principal> tokens = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : raw.entrySet()) {
            String token = entry.getKey();
            if (token.length() < 32) {
                throw new IllegalStateException(
                        "API_TOKENS_JSON keys (tokens) must be strings of at least 32 characters");
            }
            if (!(entry.getValue() instanceof Map<?, ?> spec)
                    || !(spec.get("subject") instanceof String)) {
                throw new IllegalStateException("token spec for subject must be a dict with a string 'subject' (token ending ..."
                        + token.substring(token.length() - 4) + ")");
            }
            Object rolesRaw = spec.get("roles");
            if (!(rolesRaw instanceof List<?> roles) || roles.isEmpty()
                    || !roles.stream().allMatch(r -> r instanceof String s && ALLOWED_ROLES.contains(s))) {
                throw new IllegalStateException("token roles must be a non-empty list subset of "
                        + List.of("admin", "leader", "operator", "viewer")
                        + " (token ending ..." + token.substring(token.length() - 4) + ")");
            }
            Object quotaRaw = spec.containsKey("daily_quota") ? spec.get("daily_quota") : dailyQuotaDefault;
            if (!(quotaRaw instanceof Integer quota) || quota < 0) {
                throw new IllegalStateException(
                        "token daily_quota must be a non-negative integer (token ending ..."
                                + token.substring(token.length() - 4) + ")");
            }
            tokens.put(token, new Principal((String) spec.get("subject"),
                    roles.stream().map(String.class::cast).toList(), quota, false));
        }
        return tokens;
    }
}
