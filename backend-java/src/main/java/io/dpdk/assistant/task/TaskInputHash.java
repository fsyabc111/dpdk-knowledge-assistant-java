package io.dpdk.assistant.task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;

/**
 * 请求输入身份哈希（对齐 backend/app/services/rag_issue_service.py:input_hash）。
 *
 * 归一化只做 NFC 与 Python ``str.strip()`` 边界（两侧仅去空白；空格/制表符/全角
 * 空格/NBSP 等 Python isspace 字符集，Java 的 String.strip 对 00A0/2007/202F/0085
 * 不剥，这里按 Python 语义补齐）。数组化 JSON 分帧避免拼接歧义：
 * ``json.dumps([question, filename, digest], ensure_ascii=False, separators=(",", ":"))``
 * 后按 UTF-8 做 SHA-256。附件身份用上传原字节的 SHA-256（原始字节参与，不是
 * 解码后的文本）；附件缺字节身份直接拒绝（fail-closed）。
 */
public final class TaskInputHash {

    private TaskInputHash() {
    }

    /** 无附件形态：digest 数组第 2/3 位为 null。 */
    public static String compute(String question) {
        return compute(question, null, null);
    }

    /**
     * 完整形态。attachmentContentHash 必须是上传原字节的 SHA-256 十六进制
     * （调用方在接收上传时计算）；传 null 且 filename 非 null 视为缺身份，拒绝。
     */
    public static String compute(String question, String attachmentFilename, String attachmentContentHash) {
        String filename = attachmentFilename == null ? null : pythonStrip(normalizeNfc(attachmentFilename));
        if (attachmentFilename != null && (attachmentContentHash == null || attachmentContentHash.isEmpty())) {
            throw new IllegalArgumentException("附件缺少原始字节身份");
        }
        String encoded = "[" + jsonString(pythonStrip(normalizeNfc(question == null ? "" : question)))
                + "," + (filename == null ? "null" : jsonString(filename))
                + "," + (attachmentContentHash == null ? "null" : jsonString(attachmentContentHash))
                + "]";
        return sha256Hex(encoded.getBytes(StandardCharsets.UTF_8));
    }

    /** 上传原字节的 SHA-256 十六进制（小写）。 */
    public static String sha256Hex(byte[] bytes) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : digest.digest(bytes)) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    /** NFC 归一化（身份只做这一层 Unicode 归一，不做大小写/空白压缩）。 */
    public static String normalizeNfc(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC);
    }

    /**
     * Python ``str.strip()`` 边界：两侧剥除 Python isspace 为真的字符。
     * Java Character.isWhitespace 与 Python 的差异集合（00A0/2007/202F/0085）
     * 在这里显式补上；内部空白不动（``a  b`` 与 ``a b`` 身份不同）。
     */
    public static String pythonStrip(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isPythonSpace(value.charAt(start))) {
            start++;
        }
        while (end > start && isPythonSpace(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private static boolean isPythonSpace(char c) {
        return Character.isWhitespace(c) || c == 0x00A0 || c == 0x2007 || c == 0x202F || c == 0x0085;
    }

    /**
     * Python json.dumps 字符串编码（ensure_ascii=False）：仅转义引号、反斜杠与
     * 控制字符（短转义按 Python 规则，其余控制符走反斜杠+u 的 4 位小写十六进制
     * 形式）；非 ASCII 原样。孤立代理项在 Python 里 encode('utf-8') 直接报错，
     * 这里同样拒绝（fail-closed）。
     */
    static String jsonString(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        sb.append('"');
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
                    throw new IllegalArgumentException("孤立代理项不能做 UTF-8 编码");
                }
                sb.append(c).append(value.charAt(i + 1));
                i += 2;
                continue;
            }
            if (Character.isLowSurrogate(c)) {
                throw new IllegalArgumentException("孤立代理项不能做 UTF-8 编码");
            }
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append('\\').append('u');
                        sb.append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
            i++;
        }
        sb.append('"');
        return sb.toString();
    }
}
