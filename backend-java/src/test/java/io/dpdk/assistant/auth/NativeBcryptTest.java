package io.dpdk.assistant.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCrypt;

/**
 * native bcrypt 校验与 Python bcrypt 4.3.0 的一致性，以及 BCRYPT_PROVIDER 解析。
 *
 * native 用例需要 native/bcrypt-jni/build.sh 产出的当前平台库且 native 已启用；否则跳过（跳过不计通过）。
 * 期望值来自 Python 4.3.0：生成向量见 FIX/J2-01/bcrypt-compat.json，格式错误类别见
 * evidence/J5-02/bcrypt-native-20261030（Python 抛 ValueError 的哈希在此须抛 IllegalArgumentException）。
 */
class NativeBcryptTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void pythonVectorsVerifyThroughNative() throws Exception {
        assumeTrue(nativeActive(), "native bcrypt not active on " + NativeBcrypt.platform());
        PasswordHasher hasher = new PasswordHasher(4, true);
        JsonNode fixture;
        try (var in = NativeBcryptTest.class.getResourceAsStream("/migration/J2-01/bcrypt-compat.json")) {
            fixture = MAPPER.readTree(in);
        }
        for (JsonNode vector : fixture.withArray("vectors")) {
            String password = vector.path("password").asText();
            String hash = vector.path("hash").asText();
            assertThat(hasher.check(password, hash)).as("vector %s", vector.path("id").asText()).isTrue();
            assertThat(hasher.check(password + "x", hash)).isFalse();
        }
        // 哈希生成仍走 Spring（$2a$），native 必须能校验；dummy 路径同为一次校验。
        assertThat(hasher.check("spring-made", hasher.hash("spring-made"))).isTrue();
        assertThat(hasher.dummyCheck("not-the-seed")).isFalse();
    }

    @Test
    void nativeFollowsPython430ByteSemantics() {
        assumeTrue(nativeActive(), "native bcrypt not active on " + NativeBcrypt.platform());
        String salt = "$2b$04$J2b01syntheticsaltvalu";
        // 72 字节截断（含 UTF-8 多字节字符在第 72 字节处被切开）、嵌入 NUL、空口令。
        assertMatches("x".repeat(72) + "y", springHash(("x".repeat(72)).getBytes(StandardCharsets.UTF_8), salt));
        String cut = "x".repeat(71) + "é";
        assertMatches(cut, springHash(cut.getBytes(StandardCharsets.UTF_8), salt));
        assertMatches("ab\u0000cd", springHash("ab\u0000cd".getBytes(StandardCharsets.UTF_8), salt));
        assertThat(NativeBcrypt.checkpw("ab\u0000ce", springHash("ab\u0000cd".getBytes(StandardCharsets.UTF_8), salt)))
                .isFalse();
        assertMatches("", springHash(new byte[0], salt));
        for (String prefix : new String[] {"2a", "2y"}) {
            assertMatches("alpha", springHash("alpha".getBytes(StandardCharsets.UTF_8), salt).replace("$2b$", "$" + prefix + "$"));
        }
    }

    @Test
    void malformedHashesRaiseLikePythonValueError() {
        assumeTrue(nativeActive(), "native bcrypt not active on " + NativeBcrypt.platform());
        String good = springHash("alpha".getBytes(StandardCharsets.UTF_8), "$2b$04$J2b01syntheticsaltvalu");
        String[] malformed = {
            good.replace("$2b$", "$2z$"),
            good.replace("$04$", "$03$"),
            good.replace("$04$", "$32$"),
            good.substring(0, 7) + "!" + good.substring(8),
            // 盐第 22 字符低位非零：Python 4.3.0 ValueError，Spring 返回 false（已登记差异）。
            good.substring(0, 28) + "/" + good.substring(29),
            // 旧 $2$ 前缀：Python ValueError，Spring 返回 false（已登记差异）。
            good.replace("$2b$", "$2$"),
            "$2b$04$",
        };
        for (String hash : malformed) {
            assertThatThrownBy(() -> NativeBcrypt.checkpw("alpha", hash)).as(hash)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        // 尾部被截断/延长/非规范：Python 返回 False，不抛错。
        assertThat(NativeBcrypt.checkpw("alpha", good.substring(0, 29))).isFalse();
        assertThat(NativeBcrypt.checkpw("alpha", good + "extra")).isFalse();
    }

    @Test
    void providerSelectionRejectsInvalidConfiguration() {
        assertThat(NativeBcrypt.resolve("spring", null)).isFalse();
        assertThat(NativeBcrypt.resolve("SPRING", "/ignored")).isFalse();
        assertThatThrownBy(() -> NativeBcrypt.resolve("fast", null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> NativeBcrypt.resolve("native", "relative/libdpdk_bcrypt_jni.so"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> NativeBcrypt.resolve("auto", "/nonexistent/libdpdk_bcrypt_jni.so"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void springModeKeepsSpringVerification() {
        PasswordHasher hasher = new PasswordHasher(4, false);
        String hash = hasher.hash("U20260000001");
        assertThat(hasher.check("U20260000001", hash)).isTrue();
        assertThat(hasher.check("U20260000001x", hash)).isFalse();
    }

    private static void assertMatches(String password, String hash) {
        assertThat(NativeBcrypt.checkpw(password, hash)).as(hash).isTrue();
    }

    private static String springHash(byte[] password, String salt) {
        return BCrypt.hashpw(Arrays.copyOf(password, Math.min(72, password.length)), salt);
    }

    /** 当前平台库已构建且未被 BCRYPT_PROVIDER=spring 关闭；库存在时 auto 加载失败会直接抛错而非跳过。 */
    private static boolean nativeActive() {
        boolean built = NativeBcrypt.class.getResource(
                "/native/" + NativeBcrypt.platform() + "/" + System.mapLibraryName("dpdk_bcrypt_jni")) != null;
        return built && NativeBcrypt.active();
    }
}
