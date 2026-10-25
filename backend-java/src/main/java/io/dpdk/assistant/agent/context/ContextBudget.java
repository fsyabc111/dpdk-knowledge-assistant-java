package io.dpdk.assistant.agent.context;

import io.dpdk.assistant.agent.AgentMessage;
import io.dpdk.assistant.agent.ToolCallSpec;
import io.dpdk.assistant.retrieval.PythonJson;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 上下文预算准备（P0.1 分级收缩管线的 Java 移植，源 backend/app/agent/context.py）。
 *
 * <p>行为基线：planner 与 finalize 共用一条确定性的「准备 + 分级降级」管线，
 * 够用即停；只有连「系统提示词 + 当前请求」都放不下时才返回 overBudget=true，
 * 由调用方走安全降级（保留评分事实、不回显源码）。所有字符计量与切片按
 * Unicode 码点（{@link CodePoints}）。
 *
 * <p>配对不变式（测试锁死）：带 tool_calls 的 AI 消息与其全部 ToolMessage 是
 * 原子单元，要么一起留要么一起删；ToolMessage 只改写 content，AI 消息只改写
 * tool_calls 的参数，tool_call_id / 类型 / 顺序都不变。「已执行」的判据贯穿
 * 各级：tool_call 的 id 有配对 ToolMessage 才算已执行。
 *
 * <p>分级：0=原样（预算关闭）；1=压早轮（基线行为，无论是否超限都执行）；
 * 2=压全部工具结果；3=再压历史轮参数；4=再硬截断；5=再整单元丢弃。
 */
public final class ContextBudget {

    private static final Logger log = LoggerFactory.getLogger(ContextBudget.class);

    /** 单条 ToolMessage 在第 4 级（硬截断）保留的字符数。 */
    static final int MIN_TOOL_CHARS = 200;
    /** 历史轮 tool_call 参数中，长于该值的字符串实参被替换为占位符（第 3 级）。 */
    static final int ARG_COMPACT_THRESHOLD = 200;

    /** 工具结果摘要器（默认走 {@code ToolSummaries.toolContextSummary}）。 */
    public interface Summarizer {
        String summarize(String toolName, String content);
    }

    /**
     * 准备结果。messages 已含系统提示词，可直接交给模型；level 观测分级；
     * overBudget=true 表示连「系统提示词 + 当前请求」都放不下。
     */
    public record PreparedContext(
            List<AgentMessage> messages, int totalChars, int level, boolean overBudget,
            Map<String, Object> stats) {
    }

    private ContextBudget() {
    }

    /**
     * 模型实际可见的消息体积：content + tool_calls 的 name/id/参数。
     * grade_plugin 的工具参数携带完整 plugin_source（可达数 KB），只算 content
     * 会显著低估真实请求体积。
     */
    public static int messageChars(AgentMessage message) {
        int total = CodePoints.length(message.content());
        for (ToolCallSpec call : message.toolCalls()) {
            total += CodePoints.length(call.name());
            total += CodePoints.length(call.id());
            total += CodePoints.length(PythonJson.dumps(call.args(), false));
        }
        return total;
    }

    public static int messagesChars(List<AgentMessage> messages) {
        int total = 0;
        for (AgentMessage message : messages) {
            total += messageChars(message);
        }
        return total;
    }

    /**
     * 准备一次模型调用的消息列表，必要时逐级收缩到 maxChars 以内。
     * maxChars <= 0 表示关闭预算（AGENT_CONTEXT_TRIM_ENABLED=false 语义），完全 no-op。
     */
    public static PreparedContext prepare(String systemPrompt, List<AgentMessage> history,
                                          int maxChars, Summarizer summarizer) {
        List<AgentMessage> original = new ArrayList<>(history);
        List<AgentMessage> withSystem = new ArrayList<>();
        withSystem.add(AgentMessage.system(systemPrompt));
        withSystem.addAll(history);
        if (maxChars <= 0) {
            return new PreparedContext(List.copyOf(withSystem), messagesChars(withSystem), 0, false,
                    new LinkedHashMap<>());
        }
        Summarizer summaryFn = summarizer != null ? summarizer : ToolSummariesBridge.INSTANCE;
        List<int[]> slices = unitSlices(history);
        Map<String, Object> stats = new LinkedHashMap<>();

        // 第 1 级是「打开修剪」后的基线行为（M4a E5：早轮压摘要、最近一轮原文），
        // 无论是否超限都执行——它同时是这段历史的确定性上界。
        List<AgentMessage> baseline = compressOlder(history, summaryFn);
        if (fits(systemPrompt, baseline, maxChars)) {
            return result(systemPrompt, original, baseline, maxChars, 1, stats);
        }

        List<AgentMessage> working = compressAll(history, summaryFn);
        List<AgentMessage> level2 = working;
        working = compactHistoricalArgs(working);
        List<AgentMessage> level3 = working;
        working = truncateTools(working);
        List<AgentMessage> level4 = working;

        List<AgentMessage> candidate = level2;
        int level = 2;
        if (fits(systemPrompt, candidate, maxChars)) {
            return result(systemPrompt, original, candidate, maxChars, level, stats);
        }
        candidate = level3;
        level = 3;
        if (fits(systemPrompt, candidate, maxChars)) {
            return result(systemPrompt, original, candidate, maxChars, level, stats);
        }
        candidate = level4;
        level = 4;
        if (fits(systemPrompt, candidate, maxChars)) {
            return result(systemPrompt, original, candidate, maxChars, level, stats);
        }

        // 偏移删除建立在「各级变换保持条数/顺序不变」的隐含约定上；固化为显式断言。
        if (level4.size() != history.size()) {
            throw new IllegalStateException("context transforms must preserve message count");
        }
        int dropped = 0;
        for (List<AgentMessage> messages : dropUnits(level4, slices)) {
            dropped++;
            if (fits(systemPrompt, messages, maxChars)) {
                stats.put("dropped_units", dropped);
                return result(systemPrompt, original, messages, maxChars, 5, stats);
            }
        }
        stats.put("dropped_units", dropped);
        List<AgentMessage> floor = new ArrayList<>();
        floor.add(AgentMessage.system(systemPrompt));
        floor.addAll(level4);
        return new PreparedContext(List.copyOf(floor), messagesChars(floor), 5, true,
                withReason(stats, "floor_exceeded"));
    }

    private static boolean fits(String systemPrompt, List<AgentMessage> history, int maxChars) {
        return CodePoints.length(systemPrompt) + messagesChars(history) <= maxChars;
    }

    // ------------------------------------------------------------------
    // 单元划分（配对不变式的基础）
    // ------------------------------------------------------------------

    /**
     * 把消息切成原子单元，返回 (start, end) 半开区间列表。
     * 带 tool_calls 的 AI 消息与其后紧邻、id 匹配的 ToolMessage 组成一个单元。
     */
    public static List<int[]> unitSlices(List<AgentMessage> history) {
        List<int[]> slices = new ArrayList<>();
        int index = 0;
        while (index < history.size()) {
            AgentMessage message = history.get(index);
            Set<String> ids = new HashSet<>();
            for (ToolCallSpec call : message.toolCalls()) {
                ids.add(call.id());
            }
            if (ids.isEmpty()) {
                slices.add(new int[]{index, index + 1});
                index++;
                continue;
            }
            int cursor = index + 1;
            while (cursor < history.size()
                    && history.get(cursor).isTool()
                    && ids.contains(history.get(cursor).toolCallId())) {
                cursor++;
            }
            slices.add(new int[]{index, cursor});
            index = cursor;
        }
        return slices;
    }

    /** history 中「已执行」tool_call 的 id 集合 = 有配对 ToolMessage 的调用（空串不计）。 */
    static Set<String> answeredCallIds(List<AgentMessage> history) {
        Set<String> ids = new HashSet<>();
        for (AgentMessage message : history) {
            if (message.isTool() && !message.toolCallId().isEmpty()) {
                ids.add(message.toolCallId());
            }
        }
        return ids;
    }

    /** 最后一个「有工具结果」的工具轮起点；没有则 -1（必须是「有结果」的轮次）。 */
    static int lastAnsweredToolIndex(List<AgentMessage> history) {
        Set<String> answered = answeredCallIds(history);
        int best = -1;
        for (int index = 0; index < history.size(); index++) {
            for (ToolCallSpec call : history.get(index).toolCalls()) {
                if (answered.contains(call.id())) {
                    best = index;
                    break;
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 各级变换（纯函数，均从原始 history 出发，避免二次压缩）
    // ------------------------------------------------------------------

    /** 压缩单条工具结果；只接受确实变小的结果（空内容压缩后反而会变大）。 */
    private static String shrink(String content, Summarizer summarizer, String name) {
        String compressed = summarizer.summarize(name, content);
        return CodePoints.length(compressed) < CodePoints.length(content) ? compressed : content;
    }

    /** 第 1 级：压缩早于最后一个「有结果」工具轮的 ToolMessage（= 原有 E5 行为）。 */
    static List<AgentMessage> compressOlder(List<AgentMessage> history, Summarizer summarizer) {
        int lastToolAi = lastAnsweredToolIndex(history);
        List<AgentMessage> out = new ArrayList<>(history.size());
        for (int index = 0; index < history.size(); index++) {
            AgentMessage message = history.get(index);
            if (message.isTool() && index < lastToolAi) {
                out.add(message.withContent(shrink(message.content(), summarizer, message.toolName())));
            } else {
                out.add(message);
            }
        }
        return out;
    }

    /** 第 2 级：压缩全部 ToolMessage，含最近一轮。 */
    static List<AgentMessage> compressAll(List<AgentMessage> history, Summarizer summarizer) {
        List<AgentMessage> out = new ArrayList<>(history.size());
        for (AgentMessage message : history) {
            if (message.isTool()) {
                out.add(message.withContent(shrink(message.content(), summarizer, message.toolName())));
            } else {
                out.add(message);
            }
        }
        return out;
    }

    private static String compactArg(String value) {
        return CodePoints.length(value) > ARG_COMPACT_THRESHOLD
                ? "[已省略 " + CodePoints.length(value) + " 字]" : value;
    }

    /**
     * 第 3 级：把已执行 tool_calls 里的长实参压成占位符（按调用判定）。
     * 判据是 per-call 的「该调用 id 是否有配对 ToolMessage」：已执行完的最后单元
     * 同样要压；无配对 ToolMessage 的调用是模型刚发出的请求，参数原文保留。
     */
    public static List<AgentMessage> compactHistoricalArgs(List<AgentMessage> history) {
        Set<String> answered = answeredCallIds(history);
        List<AgentMessage> out = new ArrayList<>(history.size());
        for (AgentMessage message : history) {
            List<ToolCallSpec> calls = message.toolCalls();
            boolean anyAnswered = false;
            for (ToolCallSpec call : calls) {
                if (answered.contains(call.id())) {
                    anyAnswered = true;
                    break;
                }
            }
            if (calls.isEmpty() || !anyAnswered) {
                out.add(message);
                continue;
            }
            List<ToolCallSpec> compacted = new ArrayList<>(calls.size());
            for (ToolCallSpec call : calls) {
                if (answered.contains(call.id())) {
                    Map<String, Object> newArgs = new LinkedHashMap<>();
                    for (Map.Entry<String, Object> entry : call.args().entrySet()) {
                        Object value = entry.getValue();
                        newArgs.put(entry.getKey(),
                                value instanceof String s ? compactArg(s) : value);
                    }
                    compacted.add(new ToolCallSpec(call.id(), call.name(), newArgs));
                } else {
                    compacted.add(call);
                }
            }
            out.add(message.withToolCalls(compacted));
        }
        return out;
    }

    /** 第 4 级：把剩余 ToolMessage 内容硬截断到最小长度。 */
    static List<AgentMessage> truncateTools(List<AgentMessage> history) {
        List<AgentMessage> out = new ArrayList<>(history.size());
        for (AgentMessage message : history) {
            if (message.isTool() && CodePoints.length(message.content()) > MIN_TOOL_CHARS) {
                out.add(message.withContent(
                        CodePoints.truncate(message.content(), MIN_TOOL_CHARS) + "…[已截断]"));
            } else {
                out.add(message);
            }
        }
        return out;
    }

    /**
     * 第 5 级：从最旧开始整单元丢弃。受保护的单元：含最后一个「已执行」工具轮的
     * 单元，以及含最后一条用户消息的单元；末尾悬空的未执行调用单元不保护。
     */
    public static List<List<AgentMessage>> dropUnits(List<AgentMessage> history, List<int[]> slices) {
        Set<Integer> protectedIndex = new HashSet<>();
        if (!slices.isEmpty()) {
            int answeredStart = lastAnsweredToolIndex(history);
            int humanIndex = -1;
            for (int index = 0; index < history.size(); index++) {
                if (history.get(index).isHuman()) {
                    humanIndex = index;
                }
            }
            for (int position = 0; position < slices.size(); position++) {
                int start = slices.get(position)[0];
                int end = slices.get(position)[1];
                if (start <= answeredStart && answeredStart < end
                        || start <= humanIndex && humanIndex < end) {
                    protectedIndex.add(position);
                }
            }
        }
        List<List<AgentMessage>> candidates = new ArrayList<>();
        List<AgentMessage> working = new ArrayList<>(history);
        for (int position = 0; position < slices.size(); position++) {
            if (protectedIndex.contains(position)) {
                continue;
            }
            int start = slices.get(position)[0];
            int end = slices.get(position)[1];
            int offset = history.size() - working.size();
            working.subList(start - offset, end - offset).clear();
            candidates.add(new ArrayList<>(working));
        }
        return candidates;
    }

    private static Map<String, Object> withReason(Map<String, Object> stats, String reason) {
        Map<String, Object> out = new LinkedHashMap<>(stats);
        out.put("reason", reason);
        return out;
    }

    private static PreparedContext result(String systemPrompt, List<AgentMessage> original,
                                          List<AgentMessage> history, int maxChars, int level,
                                          Map<String, Object> stats) {
        List<AgentMessage> messages = new ArrayList<>();
        messages.add(AgentMessage.system(systemPrompt));
        messages.addAll(history);
        int total = messagesChars(messages);
        boolean applied = history.size() != original.size();
        if (!applied) {
            for (int i = 0; i < history.size(); i++) {
                if (history.get(i) != original.get(i)) {
                    applied = true;
                    break;
                }
            }
        }
        Map<String, Object> logStats = new LinkedHashMap<>(stats);
        logStats.put("event", "agent_context_trimmed");
        logStats.put("level", level);
        logStats.put("applied", applied);
        logStats.put("limit", maxChars);
        logStats.put("total_chars", total);
        log.warn(PythonJson.dumps(logStats, false));
        return new PreparedContext(List.copyOf(messages), total, level, false, stats);
    }

    /** 默认摘要桥（避免 context 包反向依赖 tools 包的编译环）。 */
    private static final class ToolSummariesBridge implements Summarizer {
        static final Summarizer INSTANCE = new ToolSummariesBridge();

        @Override
        public String summarize(String toolName, String content) {
            return io.dpdk.assistant.agent.tools.ToolSummaries.toolContextSummary(toolName, content);
        }
    }
}
