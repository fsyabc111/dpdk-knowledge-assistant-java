package io.dpdk.assistant.assignment.report;

import java.util.ArrayList;
import java.util.List;

/**
 * Python csv.writer（excel 方言）字节兼容输出：逗号分隔、CRLF 行结束、
 * QUOTE_MINIMAL（字段含 , " \r \n 时以双引号包裹、内部引号加倍）。
 * 空行（分组间隔）= 单个 CRLF。数值由调用方先转 Python str() 形态字符串。
 */
final class PythonCsv {

    private final StringBuilder sb = new StringBuilder();

    PythonCsv() {
    }

    void writeRow(List<String> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(cells.get(i)));
        }
        sb.append("\r\n");
    }

    /** 分组间空行（writerow([])）。 */
    void writeBlank() {
        sb.append("\r\n");
    }

    /** 完整响应字节：UTF-8 BOM + 内容（Excel 按 UTF-8 打开）。 */
    byte[] toUtf8BytesWithBom() {
        StringBuilder out = new StringBuilder("\ufeff");
        out.append(sb);
        return out.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** QUOTE_MINIMAL。 */
    static String escape(String field) {
        if (field == null) {
            return "";
        }
        boolean need = false;
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (c == ',' || c == '"' || c == '\r' || c == '\n') {
                need = true;
                break;
            }
        }
        if (!need) {
            return field;
        }
        return '"' + field.replace("\"", "\"\"") + '"';
    }

    /** 辅助：一行快速构造。 */
    static List<String> row(Object... cells) {
        List<String> out = new ArrayList<>(cells.length);
        for (Object cell : cells) {
            if (cell == null) {
                out.add("");
            } else if (cell instanceof Double d) {
                out.add(pythonFloatStr(d));
            } else {
                out.add(String.valueOf(cell));
            }
        }
        return out;
    }

    /**
     * Python str(float)：JDK19+ Double.toString 为最短往返十进制，与 Python repr
     * 一致；本域（round1 后的 [0,1000] 一位小数、0.0）两种实现逐字节相同。
     */
    static String pythonFloatStr(double value) {
        return Double.toString(value);
    }
}
