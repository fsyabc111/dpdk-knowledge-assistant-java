package io.dpdk.assistant.auth;

import org.springframework.security.crypto.bcrypt.BCrypt;

/**
 * 口令哈希（Python bcrypt 兼容）。
 *
 * Python 侧 `bcrypt.hashpw/gensalt/checkpw`；Java 侧 spring-security-crypto 的 BCrypt
 * 与 crypt_blowfish 同算法：`$2b$04$...` 前缀可直接 checkpw（$2a/$2y/$2b 对
 * &lt;255 字节口令互验等价，见 FIX/J2-01/bcrypt-compat.json 的 Python 生成向量）。
 * 用户不存在/停用时仍执行一次 dummy 比对（等价工作，防时序侧信道，验收 F）。
 */
final class PasswordHasher {

    /** Python user_service._dummy_hash 的固定种子口令。 */
    private static final String DUMMY_SEED = "kimi-assistant-dummy";

    private final int rounds;
    private volatile String dummyHash;

    PasswordHasher(int rounds) {
        this.rounds = rounds;
    }

    String hash(String password) {
        return BCrypt.hashpw(password, BCrypt.gensalt(rounds));
    }

    boolean check(String password, String hash) {
        return BCrypt.checkpw(password, hash);
    }

    /**
     * 用户不存在/停用路径的等价 bcrypt 工作：与真实校验同为一次 checkpw，
     * dummy 哈希按 rounds 惰性生成一次（对齐 Python _dummy_hash 缓存语义）。
     */
    boolean dummyCheck(String password) {
        return check(password, dummyHash());
    }

    private String dummyHash() {
        String local = dummyHash;
        if (local == null) {
            synchronized (this) {
                local = dummyHash;
                if (local == null) {
                    local = BCrypt.hashpw(DUMMY_SEED, BCrypt.gensalt(rounds));
                    dummyHash = local;
                }
            }
        }
        return local;
    }
}
