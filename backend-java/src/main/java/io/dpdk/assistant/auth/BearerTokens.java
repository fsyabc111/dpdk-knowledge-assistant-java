package io.dpdk.assistant.auth;

import io.dpdk.assistant.contracts.identity.RequestHeaders;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;

/**
 * Bearer 解析（对齐 core/auth.py::extract_bearer_token）。
 *
 * 取 Authorization 头（大小写不敏感），按第一个空格切 scheme；scheme 小写不等于
 * "bearer" → 空串；token 取余部并 strip()。返回空串表示缺失/畸形。
 */
final class BearerTokens {

    private BearerTokens() {
    }

    static String extract(HttpServletRequest request) {
        return extractValue(request == null ? null : request.getHeader("Authorization"));
    }

    static String extract(RequestHeaders headers) {
        if (headers == null || headers.headers() == null) {
            return "";
        }
        String value = null;
        for (var entry : headers.headers().entrySet()) {
            if ("authorization".equalsIgnoreCase(entry.getKey())) {
                value = entry.getValue();
                break;
            }
        }
        return extractValue(value);
    }

    private static String extractValue(String authorization) {
        if (authorization == null) {
            return "";
        }
        int space = authorization.indexOf(' ');
        String scheme = space >= 0 ? authorization.substring(0, space) : authorization;
        if (!"bearer".equalsIgnoreCase(scheme)) {
            return "";
        }
        String token = space >= 0 ? authorization.substring(space + 1) : "";
        // Python str.strip()；Java String.strip() 对 U+00A0 等不可见空白集合略有差异（登记于 evidence）。
        return token.strip();
    }

    /** 常量时间字节比较（对齐 secrets.compare_digest）。 */
    static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
