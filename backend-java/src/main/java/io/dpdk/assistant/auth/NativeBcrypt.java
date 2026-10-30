package io.dpdk.assistant.auth;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 口令校验的 native 实现：pyca/bcrypt 4.3.0 同源 Rust bcrypt 0.17.0 经 JNI 调用（仅 checkpw）。
 *
 * Python 基线的 bcrypt.checkpw 本身就是这条 Rust 计算链，native 校验与之逐向量一致，且单次
 * cost12 计算比 Spring 纯 Java 实现快，用于满足冻结的登录并发延迟门槛。哈希生成仍走 Spring。
 *
 * 选择由环境变量 BCRYPT_PROVIDER 决定（进程级，首次使用时解析一次）：
 * - auto（默认）：显式路径或 classpath 中有当前平台库则用 native，否则用 Spring；
 * - native：必须加载成功，否则拒绝启动；
 * - spring：始终用 Spring BCrypt。
 * BCRYPT_NATIVE_LIBRARY 可指定库的绝对路径（容器 /tmp 为 noexec，不能从 jar 解出后加载）。
 * 库加载后必须通过 ABI 版本与 Python 生成向量的自检；任何失败都不会把错误当作口令通过。
 */
final class NativeBcrypt {

    static final int ABI_VERSION = 1;

    private static final Logger LOG = LoggerFactory.getLogger(NativeBcrypt.class);

    /** Python bcrypt 4.3.0 生成的 dummy 种子向量（FIX/J2-01/bcrypt-compat.json dummy-seed）。 */
    private static final String SELF_TEST_PASSWORD = "kimi-assistant-dummy";
    private static final String SELF_TEST_HASH = "$2b$04$J2b01syntheticsaltvaluB4da4B08gW8552KRiIvlYlBPUpelzUy";

    private static final String LIBRARY_BASE = "dpdk_bcrypt_jni";

    private static volatile Boolean active;

    private NativeBcrypt() {
    }

    private static native int abiVersion0();

    private static native int checkpw0(byte[] passwordUtf8, byte[] encodedHash);

    /** 当前进程是否使用 native 校验（首次调用时按环境变量解析并缓存）。 */
    static boolean active() {
        Boolean local = active;
        if (local == null) {
            synchronized (NativeBcrypt.class) {
                local = active;
                if (local == null) {
                    local = resolve(setting("BCRYPT_PROVIDER"), setting("BCRYPT_NATIVE_LIBRARY"));
                    active = local;
                }
            }
        }
        return local;
    }

    /**
     * pyca/bcrypt 4.3.0 checkpw 语义：口令 UTF-8 字节截前 72，完整编码串常数时间比较。
     * 格式错误的哈希抛 IllegalArgumentException（对应 Python ValueError，与 Spring 同类型）。
     */
    static boolean checkpw(String password, String hash) {
        int code = checkpw0(password.getBytes(StandardCharsets.UTF_8), hash.getBytes(StandardCharsets.UTF_8));
        return switch (code) {
            case 1 -> true;
            case 0 -> false;
            case -1 -> throw new IllegalArgumentException("Invalid salt");
            default -> throw new IllegalStateException("native bcrypt failure code " + code);
        };
    }

    static boolean resolve(String provider, String libraryPath) {
        String mode = provider == null || provider.isEmpty() ? "auto" : provider.toLowerCase(Locale.ROOT);
        switch (mode) {
            case "spring" -> {
                LOG.info("bcrypt provider: spring (BCRYPT_PROVIDER=spring)");
                return false;
            }
            case "native", "auto" -> {
                boolean required = mode.equals("native");
                String source = load(libraryPath, required);
                if (source == null) {
                    LOG.warn("bcrypt provider: spring (no native library for {}; BCRYPT_PROVIDER=auto)", platform());
                    return false;
                }
                selfTest();
                LOG.info("bcrypt provider: native ({})", source);
                return true;
            }
            default -> throw new IllegalStateException("BCRYPT_PROVIDER must be auto, native or spring");
        }
    }

    /** 加载库并返回来源描述；auto 模式下 classpath 无当前平台库时返回 null。 */
    private static String load(String libraryPath, boolean required) {
        if (libraryPath != null && !libraryPath.isEmpty()) {
            Path path = Path.of(libraryPath);
            if (!path.isAbsolute() || !Files.isRegularFile(path)) {
                throw new IllegalStateException("BCRYPT_NATIVE_LIBRARY must be an existing absolute file path");
            }
            System.load(path.toString());
            return path.toString();
        }
        String resource = "/native/" + platform() + "/" + System.mapLibraryName(LIBRARY_BASE);
        try (InputStream in = NativeBcrypt.class.getResourceAsStream(resource)) {
            if (in == null) {
                if (required) {
                    throw new IllegalStateException("BCRYPT_PROVIDER=native but classpath has no " + resource);
                }
                return null;
            }
            Path extracted = Files.createTempFile("dpdk-bcrypt-jni-", "-" + System.mapLibraryName(LIBRARY_BASE));
            extracted.toFile().deleteOnExit();
            Files.copy(in, extracted, StandardCopyOption.REPLACE_EXISTING);
            System.load(extracted.toString());
            return "classpath:" + resource;
        } catch (IOException e) {
            throw new IllegalStateException("cannot extract native bcrypt library " + resource, e);
        }
    }

    private static void selfTest() {
        int abi = abiVersion0();
        if (abi != ABI_VERSION) {
            throw new IllegalStateException("native bcrypt ABI " + abi + " != " + ABI_VERSION);
        }
        boolean ok;
        try {
            ok = checkpw(SELF_TEST_PASSWORD, SELF_TEST_HASH)
                    && !checkpw(SELF_TEST_PASSWORD + "x", SELF_TEST_HASH)
                    && rejectsMalformed();
        } catch (RuntimeException e) {
            throw new IllegalStateException("native bcrypt self-test failed", e);
        }
        if (!ok) {
            throw new IllegalStateException("native bcrypt self-test failed");
        }
    }

    private static boolean rejectsMalformed() {
        try {
            checkpw(SELF_TEST_PASSWORD, "$2b$04$");
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        String osKey = os.startsWith("mac") ? "darwin" : os.startsWith("linux") ? "linux" : os.replace(' ', '_');
        String archKey = switch (arch) {
            case "aarch64", "arm64" -> "aarch64";
            case "amd64", "x86_64" -> "x86_64";
            default -> arch;
        };
        return osKey + "-" + archKey;
    }

    private static String setting(String name) {
        String value = System.getProperty(name);
        return value != null ? value : System.getenv(name);
    }
}
