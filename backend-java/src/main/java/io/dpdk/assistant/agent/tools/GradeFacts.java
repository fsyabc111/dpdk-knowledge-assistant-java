package io.dpdk.assistant.agent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * grade_plugin 评分事实抽取（源 backend/app/agent/tools.py:grade_result_facts /
 * grade_facts_lines）。
 *
 * <p>同时接受工具返回形态（{"ok": true, "data": {...}}）与 harness 原始载荷
 * （AgentToolContext.grading_results 的条目，字段在顶层），让压缩摘要与降级
 * 答案共用同一套解析，避免两处口径漂移。输出有界：最多 5 个失败用例、
 * 单个失败描述字段 120 字符。
 */
public final class GradeFacts {

    /** grade_plugin 压缩形态的边界：保留的失败用例数。 */
    static final int MAX_CASES = 5;
    /** 单个失败描述字段长度。 */
    static final int FIELD_CHARS = 120;

    private GradeFacts() {
    }

    /**
     * 从 grade_plugin 载荷提取教学反馈所需的评分事实；无法识别时返回 null。
     */
    public static Map<String, Object> facts(Object payload) {
        if (!(payload instanceof Map<?, ?> raw)) {
            return null;
        }
        Object dataRaw = ((Map<?, ?>) payload).get("data");
        Map<?, ?> data = dataRaw instanceof Map<?, ?> m ? m : raw;
        List<Map<?, ?>> results = new ArrayList<>();
        Object resultsRaw = data.get("results");
        if (resultsRaw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    results.add(m);
                }
            }
        }
        if (data.get("cases_total") == null && data.get("score") == null && results.isEmpty()) {
            return null;
        }
        List<Map<?, ?>> failing = new ArrayList<>();
        for (Map<?, ?> item : results) {
            if (!"PASS".equals(item.get("result"))) {
                failing.add(item);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("protocol", data.get("protocol"));
        out.put("score", data.get("score"));
        out.put("passed", data.get("passed"));
        out.put("cases_total", data.get("cases_total") != null ? data.get("cases_total") : results.size());
        out.put("failing_total", failing.size());
        out.put("failing", failing);
        return out;
    }

    /** 把 failures[] 渲染成「描述；期望 X；实际 Y」短行（逐字段截断）。 */
    static List<String> failureLines(Object failures, int limit) {
        List<String> lines = new ArrayList<>();
        if (!(failures instanceof List<?> list)) {
            return lines;
        }
        // Python 对 (failures or [])[:limit] 先切片再逐项跳过非 dict。
        int bound = Math.min(limit, list.size());
        for (int i = 0; i < bound; i++) {
            if (!(list.get(i) instanceof Map<?, ?> item)) {
                continue;
            }
            List<String> parts = new ArrayList<>();
            String desc = str(item.get("desc")).trim();
            if (!desc.isEmpty()) {
                parts.add(desc);
            }
            String expected = str(item.get("expected")).trim();
            if (!expected.isEmpty()) {
                parts.add("期望 " + expected);
            }
            String actual = str(item.get("actual")).trim();
            if (!actual.isEmpty()) {
                parts.add("实际 " + actual);
            }
            String text = String.join("；", parts);
            if (!text.isEmpty()) {
                lines.add(io.dpdk.assistant.agent.context.CodePoints.truncate(text, FIELD_CHARS));
            }
        }
        return lines;
    }

    /** 评分事实的可读文本行；压缩摘要与降级答案共用，输出有界。 */
    public static List<String> factsLines(Map<String, Object> facts) {
        Object score = facts.get("score");
        Object passed = facts.get("passed");
        Object total = facts.get("cases_total");
        List<String> lines = new ArrayList<>();
        lines.add("通过 " + passed + "/" + total + "，得分 " + score);
        List<Map<?, ?>> failing = new ArrayList<>();
        if (facts.get("failing") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> m) {
                    failing.add(m);
                }
            }
        }
        int shown = 0;
        for (Map<?, ?> item : failing) {
            if (shown >= MAX_CASES) {
                break;
            }
            shown++;
            List<String> detail = failureLines(item.get("failures"), 2);
            lines.add(item.get("name") + "："
                    + (detail.isEmpty() ? "未通过" : String.join("；", detail)));
        }
        int remaining = intOf(facts.get("failing_total"), 0) - shown;
        if (remaining > 0) {
            lines.add("另有 " + remaining + " 个未通过用例");
        }
        return lines;
    }

    public static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    public static int intOf(Object value, int fallback) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
