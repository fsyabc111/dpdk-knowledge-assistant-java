package io.dpdk.assistant.auth;

/**
 * Python json.loads 兼容校验器（fastapi 0.115.6 的 json_invalid 错误以
 * json.JSONDecodeError 的 msg/pos 为准；语义按冻结 venv 实测探针固化，
 * 探针记录见 evidence/J2-01/pydantic-probe.md）。
 *
 * 只校验不构造值（合法文档由 Jackson 读出）；返回 null 表示合法。
 * 关键固化点：'{' → Expecting property name@1；'{"a"1}' → Expecting ':'@4；
 * '{"a":1,}' → Expecting property name@7；'01'/'1.'/'1e' → Extra data@数字合法前缀后；
 * '-x' → Expecting value@数字起点；串内控制字符/坏转义按字符位置报。
 * 已知差异（登记）：NaN/Infinity 字面量 Python 接受、Jackson 拒绝。
 */
final class PythonJsonScanner {

    record DecodeError(String msg, int pos) {
    }

    /** 内部错误信号：msg/pos 已按 Python c 扫描器语义定位。 */
    private static final class Fail extends RuntimeException {

        final String msg;

        final int pos;

        Fail(String msg, int pos) {
            super(msg + "@" + pos);
            this.msg = msg;
            this.pos = pos;
        }
    }

    private PythonJsonScanner() {
    }

    static DecodeError validate(String doc) {
        try {
            int i = skipWs(doc, 0);
            if (i >= doc.length()) {
                throw new Fail("Expecting value", i);
            }
            int end = parseValue(doc, i);
            int after = skipWs(doc, end);
            if (after < doc.length()) {
                throw new Fail("Extra data", after);
            }
            return null;
        } catch (Fail f) {
            return new DecodeError(f.msg, f.pos);
        }
    }

    private static int parseValue(String doc, int i) {
        char c = doc.charAt(i);
        switch (c) {
            case '{':
                return parseObject(doc, i);
            case '[':
                return parseArray(doc, i);
            case '"':
                return parseString(doc, i);
            case 't':
                return literal(doc, i, "true");
            case 'f':
                return literal(doc, i, "false");
            case 'n':
                return literal(doc, i, "null");
            case 'N':
                return literal(doc, i, "NaN");
            case 'I':
                return literal(doc, i, "Infinity");
            default:
                if (c == '-' || isDigit(c)) {
                    return parseNumber(doc, i);
                }
                throw new Fail("Expecting value", i);
        }
    }

    private static int parseObject(String doc, int i) {
        int p = skipWs(doc, i + 1);
        if (p >= doc.length()) {
            throw new Fail("Expecting property name enclosed in double quotes", p);
        }
        if (doc.charAt(p) == '}') {
            return p + 1;
        }
        while (true) {
            if (doc.charAt(p) != '"') {
                throw new Fail("Expecting property name enclosed in double quotes", p);
            }
            int keyEnd = parseString(doc, p);
            int colon = skipWs(doc, keyEnd);
            if (colon >= doc.length() || doc.charAt(colon) != ':') {
                throw new Fail("Expecting ':' delimiter", Math.min(colon, doc.length()));
            }
            int valueStart = skipWs(doc, colon + 1);
            if (valueStart >= doc.length()) {
                throw new Fail("Expecting value", valueStart);
            }
            int valueEnd = parseValue(doc, valueStart);
            int sep = skipWs(doc, valueEnd);
            if (sep >= doc.length()) {
                throw new Fail("Expecting ',' delimiter", sep);
            }
            char s = doc.charAt(sep);
            if (s == '}') {
                return sep + 1;
            }
            if (s != ',') {
                throw new Fail("Expecting ',' delimiter", sep);
            }
            p = skipWs(doc, sep + 1);
            if (p >= doc.length()) {
                throw new Fail("Expecting property name enclosed in double quotes", p);
            }
        }
    }

    private static int parseArray(String doc, int i) {
        int p = skipWs(doc, i + 1);
        if (p >= doc.length()) {
            throw new Fail("Expecting value", p);
        }
        if (doc.charAt(p) != ']') {
            while (true) {
                int valueEnd = parseValue(doc, p);
                int sep = skipWs(doc, valueEnd);
                if (sep >= doc.length()) {
                    throw new Fail("Expecting ',' delimiter", sep);
                }
                char s = doc.charAt(sep);
                if (s == ']') {
                    return sep + 1;
                }
                if (s != ',') {
                    throw new Fail("Expecting ',' delimiter", sep);
                }
                p = skipWs(doc, sep + 1);
                if (p >= doc.length()) {
                    throw new Fail("Expecting value", p);
                }
            }
        }
        return p + 1;
    }

    private static int parseString(String doc, int i) {
        int p = i + 1;
        while (p < doc.length()) {
            char c = doc.charAt(p);
            if (c == '"') {
                return p + 1;
            }
            if (c == '\\') {
                if (p + 1 >= doc.length()) {
                    throw new Fail("Unterminated string starting at", i);
                }
                char esc = doc.charAt(p + 1);
                if (esc == 'u') {
                    for (int k = 0; k < 4; k++) {
                        int idx = p + 2 + k;
                        if (idx >= doc.length() || "0123456789abcdefABCDEF".indexOf(doc.charAt(idx)) < 0) {
                            throw new Fail("Invalid \\uXXXX escape", p + 1);
                        }
                    }
                    p += 6;
                    continue;
                }
                if ("\"\\/bfnrt".indexOf(esc) < 0) {
                    throw new Fail("Invalid \\escape", p + 1);
                }
                p += 2;
                continue;
            }
            if (c < 0x20) {
                throw new Fail("Invalid control character at", p);
            }
            p++;
        }
        throw new Fail("Unterminated string starting at", i);
    }

    private static int literal(String doc, int i, String literal) {
        if (doc.regionMatches(i, literal, 0, literal.length())) {
            return i + literal.length();
        }
        throw new Fail("Expecting value", i);
    }

    /**
     * 数值：Python 扫描器在首个不构成合法数字延续的字符处停住（'01'/'1.'/'1e'
     * 的尾部交由外层报 Extra data）；'-' 后无数字/无 Infinity → Expecting value 于数字起点。
     */
    private static int parseNumber(String doc, int i) {
        int p = i;
        if (doc.charAt(p) == '-') {
            p++;
            if (p < doc.length() && doc.charAt(p) == 'I') {
                if (doc.regionMatches(p, "Infinity", 0, 8)) {
                    return p + 8;
                }
                throw new Fail("Expecting value", i);
            }
            if (p >= doc.length() || !isDigit(doc.charAt(p))) {
                throw new Fail("Expecting value", i);
            }
        }
        if (doc.charAt(p) == '0') {
            p++;
        } else {
            while (p < doc.length() && isDigit(doc.charAt(p))) {
                p++;
            }
        }
        if (p < doc.length() && doc.charAt(p) == '.' && p + 1 < doc.length() && isDigit(doc.charAt(p + 1))) {
            p += 2;
            while (p < doc.length() && isDigit(doc.charAt(p))) {
                p++;
            }
        }
        if (p < doc.length() && (doc.charAt(p) == 'e' || doc.charAt(p) == 'E')) {
            int q = p + 1;
            if (q < doc.length() && (doc.charAt(q) == '+' || doc.charAt(q) == '-')) {
                q++;
            }
            if (q < doc.length() && isDigit(doc.charAt(q))) {
                p = q + 1;
                while (p < doc.length() && isDigit(doc.charAt(p))) {
                    p++;
                }
            }
        }
        return p;
    }

    private static int skipWs(String doc, int i) {
        while (i < doc.length() && isWs(doc.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isWs(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
