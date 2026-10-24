package io.dpdk.assistant.platform.ids;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Python `uuid.uuid5(uuid.NAMESPACE_URL, name)` 兼容实现（RFC 4122 version 5）。
 * V0015 讲解身份回填必须与 Python 产生的确定性 UUID 逐位一致。
 */
public final class PythonUuid5 {

    /** uuid.NAMESPACE_URL = 6ba7b811-9dad-11d1-80b4-00c04fd430c8 */
    private static final byte[] NAMESPACE_URL = {
            0x6b, (byte) 0xa7, (byte) 0xb8, 0x11,
            (byte) 0x9d, (byte) 0xad, 0x11, (byte) 0xd1,
            (byte) 0x80, (byte) 0xb4, 0x00, (byte) 0xc0,
            0x4f, (byte) 0xd4, 0x30, (byte) 0xc8
    };

    private PythonUuid5() {
    }

    public static String uuid5Url(String name) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        MessageDigest sha1;
        try {
            sha1 = MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 不可用", e);
        }
        sha1.update(NAMESPACE_URL);
        byte[] hash = sha1.digest(nameBytes);
        byte[] b = new byte[16];
        System.arraycopy(hash, 0, b, 0, 16);
        b[6] = (byte) ((b[6] & 0x0f) | 0x50); // version 5
        b[8] = (byte) ((b[8] & 0x3f) | 0x80); // RFC 4122 variant
        StringBuilder sb = new StringBuilder(36);
        for (int i = 0; i < 16; i++) {
            if (i == 4 || i == 6 || i == 8 || i == 10) {
                sb.append('-');
            }
            sb.append(String.format("%02x", b[i]));
        }
        return sb.toString();
    }
}
