package io.dpdk.assistant.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Python json.dumps 兼容序列化（J1-03 配置指纹与 keyword_score 依赖）。
 *
 * 行为基线 backend/app/services/rag_index_service.py:index_configuration 与
 * retrieval_service.keyword_score：默认分隔符 ", " / ": "；键序由调用方保证
 * （LinkedHashMap 保持插入序）；ensure_ascii=true 时非 ASCII 及控制字符与 DEL
 * 按 Python ESCAPE_ASCII 规则转 4 位十六进制 u 转义（n/t/r/b/f 走短转义）。
 */
public final class PythonJson {

    private PythonJson() {
    }

    /** 序列化任意 Map/List/String/Number/Boolean/null 结构。 */
    public static String dumps(Object value, boolean ensureAscii) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, ensureAscii);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object value, boolean ensureAscii) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s, ensureAscii);
        } else if (value instanceof Boolean b) {
            sb.append(b ? "true" : "false");
        } else if (value instanceof Double d) {
            sb.append(pythonFloat(d));
        } else if (value instanceof Float f) {
            sb.append(pythonFloat(f.doubleValue()));
        } else if (value instanceof Number n) {
            // Python json 对 int 输出十进制整数；本迁移面内 metadata 值为 int/long。
            sb.append(n.longValue());
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()), ensureAscii);
                sb.append(": ");
                write(sb, e.getValue(), ensureAscii);
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                write(sb, item, ensureAscii);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("不支持的 JSON 值类型: " + value.getClass());
        }
    }

    /** Python json.dumps 的 float repr（float.__repr__ = repr 最短往返）。 */
    static String pythonFloat(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("Python json 拒绝 NaN/Infinity");
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e16) {
            // Python 对整值 float 输出 "1.0" 形态。
            long asLong = (long) value;
            return asLong + ".0";
        }
        String repr = Double.toString(value);
        // Java "1.0E-5" → Python "1e-05"；去掉多余 ".0" 并小写 e、补两位指数。
        int e = repr.indexOf('E');
        if (e < 0) {
            return repr;
        }
        String mantissa = repr.substring(0, e);
        if (mantissa.endsWith(".0")) {
            mantissa = mantissa.substring(0, mantissa.length() - 2);
        }
        String signAndExp = repr.substring(e + 1);
        char sign = signAndExp.charAt(0);
        String exp = signAndExp.substring(sign == '+' || sign == '-' ? 1 : 0);
        if (sign != '+' && sign != '-') {
            exp = signAndExp;
            sign = '+';
        }
        if (exp.length() < 2) {
            exp = "0" + exp;
        }
        return mantissa + "e" + sign + exp;
    }

    private static void writeString(StringBuilder sb, String s, boolean ensureAscii) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> sb.append("\\r");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (ensureAscii ? (c < 0x20 || c > 0x7e) : (c < 0x20)) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** 便捷：默认 ensure_ascii=True（Python json.dumps 默认）。 */
    public static String dumps(Object value) {
        return dumps(value, true);
    }

    /** 供 keyword_score 使用：metadata 结构转 ensure_ascii=False 文本。 */
    public static String dumpsUnicode(Object value) {
        return dumps(value, false);
    }

    /** 解析 PG 回读的 jsonb 文本为保持键序的 Map（Jackson 依赖由 web starter 提供）。 */
    public static Map<String, Object> parseObject(String json) {
        if (json == null || json.isBlank()) {
            return new java.util.LinkedHashMap<>();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<
                            java.util.LinkedHashMap<String, Object>>() {
                    });
        } catch (Exception e) {
            throw new IllegalArgumentException("jsonb 文本解析失败", e);
        }
    }

    /** 解析 PG vector::text（如 "[1,0.5,0]"）为 double[]；null/空文本返回空数组。 */
    public static double[] parseVectorText(String text) {
        if (text == null || text.isBlank()) {
            return new double[0];
        }
        String body = text.trim();
        if (body.startsWith("[")) {
            body = body.substring(1);
        }
        if (body.endsWith("]")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isBlank()) {
            return new double[0];
        }
        List<Double> out = new ArrayList<>();
        for (String part : body.split(",")) {
            out.add(Double.parseDouble(part.trim()));
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }
}
