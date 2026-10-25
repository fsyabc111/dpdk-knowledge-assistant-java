package io.dpdk.assistant.agent.tools;

import io.dpdk.assistant.agent.context.CodePoints;
import io.dpdk.assistant.retrieval.PythonJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具结果的上下文压缩形态与事件摘要（源 backend/app/agent/tools.py 底部三件）。
 *
 * <p>tool_context_summary（M4a E5）：替代进入历史较久的 ToolMessage 原文。
 * kb_search 保留 top-3 片段的「覆盖式」摘要（头/尾/关键行，含检索词或声明行的
 * 优先，位置不决定去留）；grade_plugin 保留得分与失败用例的期望/实际差异
 * （教学反馈的事实基础）；其余工具退为 tool_event_summary 的一句话摘要。
 *
 * <p>tool_event_label / tool_event_summary：前端工具卡片标题与 tool_end 事件体。
 */
public final class ToolSummaries {

    // 上下文修剪（E5）时 kb_search 摘要的形态：头部 + 中段关键行 + 尾部。
    static final int KB_TOP_CHUNKS = 3;
    static final int KB_CHUNK_CHARS = 320;
    static final int KB_HEAD_CHARS = 120;
    static final int KB_TAIL_CHARS = 60;
    // 中段（头尾之外）的关键行额度；CHUNK_CHARS = HEAD + SIGNAL + TAIL。
    static final int KB_SIGNAL_CHARS = 140;
    // 单条关键行保留长度（签名行通常短于此，够定位即可）。
    static final int KB_LINE_CHARS = 64;
    // 形如声明/签名/字段/宏的行：C 源码与头文件里的函数签名、结构体字段、宏定义。
    private static final List<String> KB_DECL_MARKS = List.of("(", "_", ":", ";", "{");
    // 纯散文中段的兜底字符窗口宽度。
    static final int KB_WINDOW_CHARS = 40;

    private static final Pattern QUERY_TERM_RE = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}");

    /**
     * 解析工具结果 JSON；与 Python json.loads 不同，任何解析失败一律返回 null
     * （调用方按「格式异常」降级，不抛穿）。保持键序。
     */
    private static Object tryParse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<Object>() {
                    });
        } catch (Exception e) {
            return null;
        }
    }

    private ToolSummaries() {
    }

    // ------------------------------------------------------------------
    // kb_search 覆盖式摘要
    // ------------------------------------------------------------------

    /** 查询里的标识符式词元（小写、去重保序、长度 >= 3）。 */
    static List<String> kbQueryTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        Matcher matcher = QUERY_TERM_RE.matcher(query == null ? "" : query);
        while (matcher.find()) {
            terms.add(matcher.group().toLowerCase());
        }
        return new ArrayList<>(terms);
    }

    /** 在 budget 内保留 lines；放不下时按等距步长抽（避免位置偏置）。 */
    static List<String> kbPickLines(List<String> lines, int budget) {
        if (lines.isEmpty()) {
            return List.of();
        }
        int total = 0;
        for (String line : lines) {
            total += CodePoints.length(line) + 1;
        }
        if (total <= budget) {
            return new ArrayList<>(lines);
        }
        int average = Math.max(total / lines.size(), 1);
        int count = Math.max(1, budget / average);
        int step = Math.max(1, lines.size() / count);
        List<String> picked = new ArrayList<>();
        int used = 0;
        for (int index = 0; index < lines.size(); index += step) {
            String line = lines.get(index);
            int cost = CodePoints.length(line) + 1;
            if (used + cost > budget) {
                continue;
            }
            picked.add(line);
            used += cost;
        }
        return picked;
    }

    /** 中段没有可识别结构（纯散文）时的兜底：等距取若干字符窗口。 */
    static List<String> kbStrideWindows(String text, int budget) {
        int width = KB_WINDOW_CHARS;
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        int len = CodePoints.length(text);
        if (len <= budget) {
            return List.of(text);
        }
        int count = Math.max(1, budget / (width + 1));
        if (count <= 1) {
            return List.of(CodePoints.truncate(text, width));
        }
        int span = len - width;
        int step = Math.max(1, span / (count - 1));
        List<String> windows = new ArrayList<>();
        for (int index = 0; index <= span && windows.size() < count; index += step) {
            windows.add(CodePoints.substring(text, index, index + width));
        }
        return windows;
    }

    /**
     * 单个片段的覆盖式摘要：头 + 中段关键行 + 尾，总长以 CHUNK_CHARS 为界。
     * 中段先取含检索词的行，再取形如声明/签名/字段的行，都没有时退回等距窗口。
     */
    static String kbChunkDigest(String content, List<String> terms) {
        if (CodePoints.length(content) <= KB_CHUNK_CHARS) {
            return content;
        }
        String head = CodePoints.truncate(content, KB_HEAD_CHARS);
        int contentLen = CodePoints.length(content);
        String tail = CodePoints.substring(content, contentLen - KB_TAIL_CHARS, contentLen);
        String middle = CodePoints.substring(content, KB_HEAD_CHARS, contentLen - KB_TAIL_CHARS);

        List<String> lines = new ArrayList<>();
        for (String rawLine : CodePoints.splitLines(middle)) {
            String line = CodePoints.truncate(rawLine.trim(), KB_LINE_CHARS);
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }

        List<String> matched = new ArrayList<>();
        for (String line : lines) {
            String lowered = line.toLowerCase();
            for (String term : terms) {
                if (lowered.contains(term)) {
                    matched.add(line);
                    break;
                }
            }
        }
        List<String> picked = kbPickLines(matched, KB_SIGNAL_CHARS);
        if (picked.isEmpty()) {
            List<String> decls = new ArrayList<>();
            for (String line : lines) {
                for (String mark : KB_DECL_MARKS) {
                    if (line.contains(mark)) {
                        decls.add(line);
                        break;
                    }
                }
            }
            picked = kbPickLines(decls, KB_SIGNAL_CHARS);
        }
        if (picked.isEmpty()) {
            picked = kbStrideWindows(middle, KB_SIGNAL_CHARS);
        }

        boolean dropped = picked.size() < lines.size();
        List<String> body = new ArrayList<>();
        if (dropped && !picked.isEmpty()) {
            body.add("…");
        }
        body.addAll(picked);
        if (dropped && !picked.isEmpty()) {
            body.add("…");
        }
        StringBuilder sb = new StringBuilder(head);
        for (String part : body) {
            if (!part.isEmpty()) {
                sb.append('\n').append(part);
            }
        }
        sb.append('\n').append(tail);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /** 工具结果的上下文压缩形态（M4a E5）；输入为 ToolMessage 原文 JSON。 */
    public static String toolContextSummary(String toolName, String resultJson) {
        Object parsed;
        try {
            parsed = tryParse(resultJson);
        } catch (RuntimeException e) {
            parsed = null;
        }
        if (!(parsed instanceof Map<?, ?> result) || !Boolean.TRUE.equals(result.get("ok"))) {
            Map<String, Object> degraded = new LinkedHashMap<>();
            degraded.put("tool", toolName);
            degraded.put("ok", false);
            degraded.put("summary", toolEventSummary(toolName, resultJson).get("summary"));
            return PythonJson.dumps(degraded, false);
        }
        if ("grade_plugin".equals(toolName)) {
            Map<String, Object> facts = GradeFacts.facts(result);
            if (facts != null) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("tool", "grade_plugin");
                out.put("ok", true);
                out.put("protocol", facts.get("protocol"));
                out.put("score", facts.get("score"));
                out.put("passed", facts.get("passed"));
                out.put("cases_total", facts.get("cases_total"));
                out.put("failing_total", facts.get("failing_total"));
                out.put("facts", GradeFacts.factsLines(facts));
                out.put("note", "评测结果已压缩为评分事实；需要完整逐用例结果可重新评测");
                return PythonJson.dumps(out, false);
            }
            Map<String, Object> degraded = new LinkedHashMap<>();
            degraded.put("tool", toolName);
            degraded.put("ok", false);
            degraded.put("summary", toolEventSummary(toolName, resultJson).get("summary"));
            return PythonJson.dumps(degraded, false);
        }
        if (!"kb_search".equals(toolName)) {
            Map<String, Object> degraded = new LinkedHashMap<>();
            degraded.put("tool", toolName);
            degraded.put("ok", false);
            degraded.put("summary", toolEventSummary(toolName, resultJson).get("summary"));
            return PythonJson.dumps(degraded, false);
        }
        Object dataRaw = result.get("data");
        Map<?, ?> data = dataRaw instanceof Map<?, ?> m ? m : Map.of();
        String query = GradeFacts.str(data.get("query"));
        List<String> terms = kbQueryTerms(query);
        List<Map<String, Object>> kept = new ArrayList<>();
        Object chunksRaw = data.get("chunks");
        int keptCount = 0;
        if (chunksRaw instanceof List<?> list) {
            for (Object item : list) {
                if (keptCount >= KB_TOP_CHUNKS) {
                    break;
                }
                if (item instanceof Map<?, ?> chunk) {
                    keptCount++;
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("ref", chunk.get("ref"));
                    entry.put("source_path", chunk.get("source_path"));
                    entry.put("section", chunk.get("section"));
                    entry.put("title", chunk.get("title"));
                    entry.put("content", kbChunkDigest(GradeFacts.str(chunk.get("content")), terms));
                    kept.add(entry);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", "kb_search");
        out.put("ok", true);
        out.put("query", query);
        out.put("total", data.get("count") != null ? data.get("count") : 0);
        out.put("top_chunks", kept);
        out.put("note", "早期检索结果已压缩：保留前 " + KB_TOP_CHUNKS + " 条片段的"
                + "头部、尾部与关键行；需要完整内容可重新检索");
        return PythonJson.dumps(out, false);
    }

    // ------------------------------------------------------------------
    // 事件摘要与标签
    // ------------------------------------------------------------------

    /** 解析 ToolMessage 载荷为 tool_end 事件体（ok + summary）。 */
    public static Map<String, Object> toolEventSummary(String toolName, String resultJson) {
        Object parsed;
        try {
            parsed = tryParse(resultJson);
        } catch (RuntimeException e) {
            parsed = null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tool", toolName);
        boolean ok = parsed instanceof Map<?, ?> result && Boolean.TRUE.equals(result.get("ok"));
        out.put("ok", ok);
        if (!ok) {
            if (parsed == null) {
                // JSON 解析失败：与 Python 分支一致，区别于业务失败的「未知错误」。
                out.put("summary", "工具返回格式异常");
                return out;
            }
            String detail = "未知错误";
            Map<?, ?> result = (Map<?, ?>) parsed;
            Object d = result.get("detail") != null ? result.get("detail") : result.get("error");
            if (d != null) {
                detail = GradeFacts.str(d);
            }
            out.put("summary", CodePoints.truncate(detail, 120));
            return out;
        }
        Map<?, ?> data = parsed instanceof Map<?, ?> result && result.get("data") instanceof Map<?, ?> m
                ? m : Map.of();
        switch (toolName) {
            case "grade_plugin" -> out.put("summary",
                    GradeFacts.intOf(data.get("passed"), 0) + "/" + GradeFacts.intOf(data.get("cases_total"), 0)
                            + " 通过，得分 " + GradeFacts.intOf(data.get("score"), 0));
            case "kb_search" -> {
                String query = GradeFacts.str(data.get("query")).trim();
                String prefix = query.isEmpty() ? ""
                        : "「" + CodePoints.truncate(query, 30) + "」";
                out.put("summary", prefix + "命中 " + GradeFacts.intOf(data.get("count"), 0) + " 条资料");
            }
            case "list_protocols" -> out.put("summary", GradeFacts.intOf(data.get("count"), 0) + " 个协议");
            case "list_cases" -> out.put("summary", GradeFacts.intOf(data.get("count"), 0) + " 个用例");
            case "plugin_template" -> out.put("summary", "已生成骨架");
            default -> out.put("summary", "完成");
        }
        return out;
    }

    /** Human-readable tool_start 标签（§4.4，前端工具卡片标题）。 */
    public static String toolEventLabel(String toolName, Map<String, Object> args) {
        switch (toolName) {
            case "grade_plugin" -> {
                String protocol = GradeFacts.str(args.get("protocol")).trim().toUpperCase();
                return "正在评测 " + (protocol.isEmpty() ? "插件" : protocol) + " 插件…";
            }
            case "kb_search" -> {
                String query = GradeFacts.str(args.get("query")).trim();
                return query.isEmpty() ? "正在检索知识库…" : "正在检索：" + CodePoints.truncate(query, 30);
            }
            case "list_protocols" -> {
                return "正在查询协议列表…";
            }
            case "list_cases" -> {
                String protocol = GradeFacts.str(args.get("protocol")).trim().toUpperCase();
                return protocol.isEmpty() ? "正在查询协议用例…" : "正在查询 " + protocol + " 的用例…";
            }
            case "plugin_template" -> {
                String protocol = GradeFacts.str(args.get("protocol")).trim().toUpperCase();
                return protocol.isEmpty() ? "正在生成插件骨架…" : "正在生成 " + protocol + " 插件骨架…";
            }
            default -> {
                return "正在调用工具 " + toolName + "…";
            }
        }
    }
}
