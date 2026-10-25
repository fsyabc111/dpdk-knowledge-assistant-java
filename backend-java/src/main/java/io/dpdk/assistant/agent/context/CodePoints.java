package io.dpdk.assistant.agent.context;

import java.util.ArrayList;
import java.util.List;

/**
 * Unicode 码点口径的字符串工具（任务卡：字符上限按 Unicode 码点对齐）。
 *
 * <p>Python {@code len(s) / s[a:b]} 按 Unicode 码点计量与切片；Java
 * {@code String.length()/substring()} 按 UTF-16 单元。上下文预算、围栏抽取、
 * 摘要截断全部经本工具换算，保证非 BMP 字符（如 U+20000 增补表意文字、emoji）
 * 计 1 个字符且截断不劈开代理对。
 */
public final class CodePoints {

    private CodePoints() {
    }

    /** Python len(s) 等价：码点数。 */
    public static int length(String s) {
        return s.codePointCount(0, s.length());
    }

    /** Python s[start:end]（码点下标，支持 end 越界截齐；负数不支持）。 */
    public static String substring(String s, int start, int end) {
        int len = s.length();
        int i = offsetByCodePoints(s, start);
        int j = offsetByCodePoints(s, end);
        if (i > len) {
            i = len;
        }
        if (j > len) {
            j = len;
        }
        if (i >= j) {
            return "";
        }
        return s.substring(i, j);
    }

    /** Python s[:max]：码点截断；不劈开代理对。 */
    public static String truncate(String s, int max) {
        if (max < 0) {
            max = 0;
        }
        if (length(s) <= max) {
            return s;
        }
        return substring(s, 0, max);
    }

    private static int offsetByCodePoints(String s, int codePointIndex) {
        if (codePointIndex <= 0) {
            return 0;
        }
        if (codePointIndex >= length(s)) {
            return s.length();
        }
        int index = 0;
        for (int k = 0; k < codePointIndex; k++) {
            index += Character.charCount(s.codePointAt(index));
        }
        return index;
    }

    /** Python s.strip("\n") 等价：只剥首尾的换行符（不剥空格）。 */
    public static String stripNewlines(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '\n') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '\n') {
            end--;
        }
        return s.substring(start, end);
    }

    /**
     * Python str.splitlines() 等价：按 \\n、\\r、\\r\\n、\\v、\\f、\\x1c、\\x1d、
     * \\x1e、\\x85、\\u2028、\\u2029 切分；结尾边界不产生尾空串；空串返回空列表。
     */
    public static List<String> splitLines(String s) {
        List<String> out = new ArrayList<>();
        int n = s.length();
        int start = 0;
        int i = 0;
        while (i < n) {
            char c = s.charAt(i);
            int skip = lineBreakSkip(s, i, c);
            if (skip > 0) {
                out.add(s.substring(start, i));
                i += skip;
                start = i;
            } else {
                i++;
            }
        }
        if (start < n) {
            out.add(s.substring(start));
        }
        return out;
    }

    private static int lineBreakSkip(String s, int i, char c) {
        switch (c) {
            case '\n', '\u000B', '\u000C', '\u001C', '\u001D', '\u001E', '\u0085', '\u2028', '\u2029':
                return 1;
            case '\r':
                return i + 1 < s.length() && s.charAt(i + 1) == '\n' ? 2 : 1;
            default:
                return 0;
        }
    }
}
