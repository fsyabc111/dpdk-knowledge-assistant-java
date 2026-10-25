package io.dpdk.assistant.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 登录/重置令牌铸造与摘要（Python secrets.token_urlsafe(32) + sha256 hex 兼容）。
 *
 * 43 字符 base64url 无填充（32 字节熵），与 Python token_urlsafe(32) 同格式同熵；
 * 库内只存 sha256 hex（高熵随机串不需要慢哈希）。
 */
final class TokenMint {

    private static final SecureRandom RANDOM = new SecureRandom();

    private TokenMint() {
    }

    /** 与 secrets.token_urlsafe(32) 等价：43 字符 base64url 无填充。 */
    static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 与 user_service._token_digest 一致：sha256(token utf-8) 的 hex（小写）。 */
    static String digest(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
