package io.dpdk.assistant.assignment.submission;

import java.nio.ByteBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Python UTF-8 编解码替换语义（验收 F：Java/Python 对编码替换后文本的 SHA-256 一致）。
 *
 * <p>解码（{@code bytes.decode("utf-8", errors="replace")}）不能直接用
 * {@code new String(bytes, UTF_8)}：JVM 对「形似合法但落进代理区」的三字节序列
 * （ED A0 BF）按整段记一次 malformed（1 个 U+FFFD），而 CPython 逐字节拒绝
 * （3 个 U+FFFD）。本实现按 CPython utf8_decode 的字节机复刻：
 * <ul>
 *   <li>起始字节 0x80..0xC1 立即判非法（含 C0/C1 过长编码首字节），逐字节记 U+FFFD；</li>
 *   <li>E0 首续字节限 A0..BF、ED 首续字节限 80..9F（代理区按首续字节拒绝——与
 *       CPython 一致，与 JVM「整段一个 FFFD」不同）、F0 首续 90..BF、F4 首续 80..8F；
 *       其余 lead 按标准 80..BF；</li>
 *   <li>续字节非法：为已累积前缀记 1 个 U+FFFD，并把当前字节从起始态重新处理；</li>
 *   <li>输入在多字节序列中途结束：整段前缀记 1 个 U+FFFD。</li>
 * </ul>
 *
 * <p>编码（{@code str.encode("utf-8", errors="replace")}）：非代理文本与
 * {@link String#getBytes(StandardCharsets#UTF_8)} 逐字节一致；孤立代理两侧都替换为
 * {@code '?'}（0x3F），无需特殊处理。
 */
public final class PythonUtf8 {

    private static final char REPLACEMENT = '\uFFFD';

    private PythonUtf8() {
    }

    /** CPython `bytes.decode("utf-8", errors="replace")` 逐字节等价实现。 */
    public static String replaceDecode(byte[] data) {
        StringBuilder out = new StringBuilder(data.length);
        int i = 0;
        int len = data.length;
        while (i < len) {
            int b0 = data[i] & 0xFF;
            if (b0 < 0x80) {
                out.append((char) b0);
                i++;
                continue;
            }
            if (b0 < 0xC2 || b0 > 0xF4) {
                // 0x80..0xC1（含 C0/C1 过长编码）与 0xF5..0xFF：起始即非法。
                out.append(REPLACEMENT);
                i++;
                continue;
            }
            int expected;   // lead 之后的续字节数
            int minCont;    // 首续字节允许下界
            int maxCont;    // 首续字节允许上界
            if (b0 <= 0xDF) {
                expected = 1;
                minCont = 0x80;
                maxCont = 0xBF;
            } else if (b0 <= 0xEF) {
                expected = 2;
                if (b0 == 0xE0) {
                    minCont = 0xA0;
                    maxCont = 0xBF;
                } else if (b0 == 0xED) {
                    minCont = 0x80;     // 代理区按首续字节拒绝（80..9F 之外非法）
                    maxCont = 0x9F;
                } else {
                    minCont = 0x80;
                    maxCont = 0xBF;
                }
            } else {
                expected = 3;
                if (b0 == 0xF0) {
                    minCont = 0x90;
                    maxCont = 0xBF;
                } else if (b0 == 0xF4) {
                    minCont = 0x80;
                    maxCont = 0x8F;
                } else {
                    minCont = 0x80;
                    maxCont = 0xBF;
                }
            }
            if (i + 1 >= len) {
                // 只有 lead、续字节缺失：整段前缀 1 个 U+FFFD。
                out.append(REPLACEMENT);
                break;
            }
            boolean complete = true;
            int consumed = expected;
            for (int k = 1; k <= expected; k++) {
                if (i + k >= len) {
                    // 序列在输入中途结束（后续字节缺失）：整段前缀 1 个 U+FFFD。
                    out.append(REPLACEMENT);
                    complete = false;
                    consumed = len - i;
                    break;
                }
                int cont = data[i + k] & 0xFF;
                int lo = k == 1 ? minCont : 0x80;
                int hi = k == 1 ? maxCont : 0xBF;
                if (cont < lo || cont > hi) {
                    // 已累积前缀（lead + 前续字节）记 1 个 U+FFFD；失败字节从起始态重新处理。
                    out.append(REPLACEMENT);
                    complete = false;
                    consumed = k;
                    break;
                }
            }
            if (!complete) {
                i += consumed;
                continue;
            }
            int codePoint = switch (expected) {
                case 1 -> ((b0 & 0x1F) << 6) | (data[i + 1] & 0x3F);
                case 2 -> ((b0 & 0x0F) << 12) | ((data[i + 1] & 0x3F) << 6) | (data[i + 2] & 0x3F);
                default -> ((b0 & 0x07) << 18) | ((data[i + 1] & 0x3F) << 12)
                        | ((data[i + 2] & 0x3F) << 6) | (data[i + 3] & 0x3F);
            };
            out.appendCodePoint(codePoint);
            i += expected + 1;
        }
        return out.toString();
    }

    /** Python `str.encode("utf-8", errors="replace")`：孤立代理替换 '?'，其余同 JVM。 */
    public static byte[] encodeReplace(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 严格 UTF-8 解码（Python request.body 的 UnicodeDecodeError 面）。 */
    public static String strictDecode(byte[] data) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(data)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("strict utf-8 decode failure", e);
        }
    }

    /** hashlib.sha256 的 hexdigest。 */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest.digest(data)) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("sha-256 unavailable", e);
        }
    }
}
