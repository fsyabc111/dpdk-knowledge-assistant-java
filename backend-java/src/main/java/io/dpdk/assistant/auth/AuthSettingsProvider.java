package io.dpdk.assistant.auth;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 认证设置的进程内提供者。
 *
 * 生产装配从 Spring Environment 读取 Python 同名环境变量（AUTH_MODE 等）并做
 * config.py 同款启动校验（失败即拒绝启动）；测试与模式切换场景用 {@link #fixed}
 * 直接持有快照。current() 返回的快照不可变，切换只整替引用。
 */
@Component
public class AuthSettingsProvider {

    private volatile AuthServerSettings current;

    public AuthSettingsProvider(Environment environment) {
        this.current = AuthServerSettings.of(
                value(environment, "AUTH_MODE", "local"),
                value(environment, "API_TOKENS_JSON", "{}"),
                Integer.parseInt(value(environment, "AUTH_TOKEN_TTL_DAYS", "7")),
                Integer.parseInt(value(environment, "PASSWORD_MIN_LENGTH", "8")),
                Integer.parseInt(value(environment, "BCRYPT_ROUNDS", "12")),
                Integer.parseInt(value(environment, "LLM_DAILY_QUOTA_DEFAULT", "200")));
    }

    private AuthSettingsProvider(AuthServerSettings fixed) {
        this.current = fixed;
    }

    public static AuthSettingsProvider fixed(AuthServerSettings settings) {
        return new AuthSettingsProvider(settings);
    }

    public AuthServerSettings current() {
        return current;
    }

    /** 整替当前快照（测试/受控场景切换；生产启动后不调用）。 */
    public void replace(AuthServerSettings settings) {
        this.current = settings;
    }

    private static String value(Environment environment, String name, String fallback) {
        String v = environment.getProperty(name);
        return v == null || v.isEmpty() ? fallback : v;
    }
}
