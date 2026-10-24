package io.dpdk.assistant.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 请求级用量累加器（对齐 backend/app/core/usage.py 的 UsageRecorder）。只采集，
 * 不落库；三条锁死语义保持不变：
 *
 * <ol>
 *   <li>未知不是 0：上游没返回 usage 时 token 列一律 null；</li>
 *   <li>零调用要留痕：缓存命中/幂等重放/本地规则记 callCount=0 条目；</li>
 *   <li>未定价不出价：未配价或非计费 kind 不产生 cost_micros。</li>
 * </ol>
 */
public final class ModelUsageLedger {

    // 用量来源
    public static final String SOURCE_UPSTREAM = "upstream";
    public static final String SOURCE_UNKNOWN = "unknown";
    public static final String SOURCE_CACHE_HIT = "cache_hit";
    public static final String SOURCE_REPLAY = "replay";
    public static final String SOURCE_LOCAL = "local";
    public static final String SOURCE_OPERATION = "operation";

    public static final Set<String> ZERO_CALL_SOURCES = Set.of(SOURCE_CACHE_HIT, SOURCE_REPLAY, SOURCE_LOCAL);

    // 用量种类
    public static final String KIND_LLM = "llm";
    public static final String KIND_EMBEDDING = "embedding";
    public static final String KIND_HARNESS = "harness";
    public static final String KIND_MCP = "mcp";

    public static final Set<String> PRICED_KINDS = Set.of(KIND_LLM, KIND_EMBEDDING);

    private final List<ModelUsageEntry> entries = new ArrayList<>();

    /** 记一次真实上游调用；usage 为 null 或字段缺失 → 该字段未知。 */
    public synchronized ModelUsageEntry recordLlm(
            String stage, String model, Map<String, Object> rawUsage, Long durationMs, String kind, int calls) {
        Map<String, Integer> tokens = tokensFromUsage(rawUsage);
        String source = SOURCE_UPSTREAM;
        if (tokens.get("prompt_tokens") == null
                && tokens.get("completion_tokens") == null
                && tokens.get("total_tokens") == null) {
            source = SOURCE_UNKNOWN;
        }
        ModelUsageEntry entry = new ModelUsageEntry(
                kind, stage, source, model,
                tokens.get("prompt_tokens"), tokens.get("completion_tokens"), tokens.get("total_tokens"),
                Math.max(calls, 0), durationMs);
        entries.add(entry);
        return entry;
    }

    /** 缓存命中 / 幂等重放 / 本地规则出结果：零上游调用，留 callCount=0 的审计条目。 */
    public synchronized ModelUsageEntry recordSkipped(String stage, String reason, String model, String kind) {
        String source = ZERO_CALL_SOURCES.contains(reason) ? reason : SOURCE_UNKNOWN;
        ModelUsageEntry entry = new ModelUsageEntry(kind, stage, source, model, null, null, null, 0, null);
        entries.add(entry);
        return entry;
    }

    /** 非 token 用量（harness 评测、MCP 调用）：只记次数与耗时，不计费。 */
    public synchronized ModelUsageEntry recordOperation(String kind, String stage, Long durationMs, int calls) {
        ModelUsageEntry entry = new ModelUsageEntry(
                kind, stage, SOURCE_OPERATION, null, null, null, null, Math.max(calls, 0), durationMs);
        entries.add(entry);
        return entry;
    }

    public synchronized List<ModelUsageEntry> entries() {
        return List.copyOf(entries);
    }

    /** 紧凑汇总（对齐 UsageRecorder.totals 的键）。 */
    public synchronized Map<String, Object> totals() {
        List<ModelUsageEntry> priced = entries.stream().filter(ModelUsageEntry::priced).toList();

        Integer promptSum = sumOf(priced, "prompt");
        Integer completionSum = sumOf(priced, "completion");
        Integer totalSum = sumOf(priced, "total");
        int totalEntries = countOf(priced, "total");

        long calls = entries.stream().mapToLong(ModelUsageEntry::callCount).sum();
        long unknownEntries = entries.stream()
                .filter(e -> SOURCE_UNKNOWN.equals(e.usageSource())).count();
        long zeroCallEntries = entries.stream()
                .filter(e -> ZERO_CALL_SOURCES.contains(e.usageSource())).count();
        TreeSet<String> stages = new TreeSet<>();
        entries.forEach(e -> stages.add(e.stage()));

        // 计数列统一用 Integer（与 Python totals 的 int 形态对齐，避免 long/int 断言歧义）。
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", entries.size());
        out.put("calls", Math.toIntExact(calls));
        out.put("prompt_tokens", promptSum);
        out.put("completion_tokens", completionSum);
        out.put("total_tokens", totalSum);
        out.put("total_tokens_entries", totalEntries);
        out.put("unknown_entries", Math.toIntExact(unknownEntries));
        out.put("zero_call_entries", Math.toIntExact(zeroCallEntries));
        out.put("stages", new ArrayList<>(stages));
        return out;
    }

    private static Integer sumOf(List<ModelUsageEntry> priced, String field) {
        int sum = 0;
        boolean any = false;
        for (ModelUsageEntry e : priced) {
            Integer v = tokenField(e, field);
            if (v != null) {
                sum += v;
                any = true;
            }
        }
        return any ? sum : null;
    }

    private static int countOf(List<ModelUsageEntry> priced, String field) {
        int n = 0;
        for (ModelUsageEntry e : priced) {
            if (tokenField(e, field) != null) {
                n++;
            }
        }
        return n;
    }

    private static Integer tokenField(ModelUsageEntry e, String field) {
        return switch (field) {
            case "prompt" -> e.promptTokens();
            case "completion" -> e.completionTokens();
            default -> e.totalTokens();
        };
    }

    /**
     * 解析上游 usage（对齐 tokens_from_usage）：同时接受 prompt_tokens/completion_tokens
     * 与 input_tokens/output_tokens；只有分项时才推导 total=prompt+completion；任何异常
     * 形态（布尔、负数、非整浮点、非纯数字串）一律 null，不猜。
     */
    public static Map<String, Integer> tokensFromUsage(Map<String, Object> usage) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (usage == null) {
            out.put("prompt_tokens", null);
            out.put("completion_tokens", null);
            out.put("total_tokens", null);
            return out;
        }
        Integer prompt = intOrNull(firstNonNull(usage.get("prompt_tokens"), usage.get("input_tokens")));
        Integer completion = intOrNull(firstNonNull(usage.get("completion_tokens"), usage.get("output_tokens")));
        Integer total = intOrNull(usage.get("total_tokens"));
        if (total == null && prompt != null && completion != null) {
            total = prompt + completion;
        }
        out.put("prompt_tokens", prompt);
        out.put("completion_tokens", completion);
        out.put("total_tokens", total);
        return out;
    }

    private static Object firstNonNull(Object a, Object b) {
        return a != null ? a : b;
    }

    /** 对齐 _int_or_none：非负整数才收，其余 null。 */
    static Integer intOrNull(Object value) {
        if (value instanceof Boolean || value == null) {
            return null;
        }
        if (value instanceof Integer i) {
            return i >= 0 ? i : null;
        }
        if (value instanceof Long l) {
            return l >= 0 && l <= Integer.MAX_VALUE ? l.intValue() : null;
        }
        if (value instanceof Double d) {
            return d >= 0 && d == Math.rint(d) && d <= Integer.MAX_VALUE ? d.intValue() : null;
        }
        if (value instanceof Float f) {
            return f >= 0 && f == Math.rint(f) && f <= Integer.MAX_VALUE ? f.intValue() : null;
        }
        if (value instanceof BigDecimal bd) {
            try {
                return bd.signum() >= 0 ? bd.intValueExact() : null;
            } catch (ArithmeticException e) {
                return null;
            }
        }
        if (value instanceof String s) {
            String stripped = s.strip();
            if (!stripped.isEmpty() && stripped.chars().allMatch(c -> c >= '0' && c <= '9')) {
                try {
                    return Integer.parseInt(stripped);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        if (value instanceof Number n) {
            try {
                int i = n.intValue();
                return i >= 0 && n.longValue() == i ? i : null;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    // 说明：LLM_PRICES_JSON 价格表解析与落库由后续计费切片负责，本切片只保留
    // PRICED_KINDS 与 costMicros 的计费边界语义。

    private ModelUsageLedger() {
    }

    /** 工厂：每个请求一个累加器。 */
    public static ModelUsageLedger create() {
        return new ModelUsageLedger();
    }
}
