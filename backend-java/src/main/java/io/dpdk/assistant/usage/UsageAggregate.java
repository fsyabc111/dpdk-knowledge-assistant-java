package io.dpdk.assistant.usage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 账本聚合纯函数（对齐 services/usage_service.py aggregate_usage 及其内部助手）。
 *
 * 三条不可动摇语义（与记录层一致，测试锁死）：
 * 1. 未知不是 0：token 合计只对有值行求和并给出 known_entries 分母；
 *    没有任何有值行 → null（不是 0）；unknown 条数单列。
 * 2. 不跨币种求和：成本按币种分列（cost_micros_by_currency）。
 * 3. 非 token 操作单列：operations 只报次数/耗时并显式 priced=false。
 */
public final class UsageAggregate {

    /** 计费 kind（llm/embedding）；harness/MCP 显式不计费。 */
    public static final Set<String> PRICED_KINDS = Set.of("llm", "embedding");
    public static final String SOURCE_UNKNOWN = "unknown";

    private UsageAggregate() {
    }

    /** aggregate_usage：rows 为空返回全空/零骨架（空账而非全量账）。 */
    public static Map<String, Object> aggregateUsage(List<UsageLedgerRow> rows) {
        List<UsageLedgerRow> priced = new ArrayList<>();
        List<UsageLedgerRow> operations = new ArrayList<>();
        for (UsageLedgerRow row : rows) {
            (PRICED_KINDS.contains(row.kind()) ? priced : operations).add(row);
        }

        Map<String, Object> totals = bucket(priced);
        Map<String, Integer> costByCurrency = costByCurrency(priced);
        totals.put("cost_micros_by_currency", costByCurrency);
        List<String> unpriced = unpricedModels(priced);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totals", totals);
        out.put("operations", operationBucket(operations));
        out.put("by_stage", group(rows, "stage"));
        out.put("by_model", group(priced, "model"));
        out.put("by_day", groupByDay(rows));
        out.put("unpriced_models", unpriced);
        out.put("unknown_entries", totals.get("unknown_entries"));
        out.put("cost_currency_mixed", costByCurrency.size() > 1);
        return out;
    }

    /* ------------------------------------------------------------------ */

    private static Map<String, Object> bucket(List<UsageLedgerRow> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", null);
        out.put("entries", rows.size());
        out.put("calls", rows.stream().mapToInt(r -> r.callCount()).sum());
        out.put("prompt_tokens", sumField(rows, UsageLedgerRow::promptTokens));
        out.put("completion_tokens", sumField(rows, UsageLedgerRow::completionTokens));
        out.put("total_tokens", sumField(rows, UsageLedgerRow::totalTokens));
        out.put("known_entries", countPresent(rows, UsageLedgerRow::totalTokens));
        out.put("unknown_entries", rows.stream()
                .filter(r -> SOURCE_UNKNOWN.equals(r.usageSource())).count());
        out.put("duration_ms", sumField(rows, UsageLedgerRow::durationMs));
        return out;
    }

    private static Map<String, Object> operationBucket(List<UsageLedgerRow> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entries", rows.size());
        out.put("calls", rows.stream().mapToInt(r -> r.callCount()).sum());
        out.put("duration_ms", sumField(rows, UsageLedgerRow::durationMs));
        out.put("priced", false);
        return out;
    }

    private static List<Map<String, Object>> group(List<UsageLedgerRow> rows, String field) {
        Map<String, List<UsageLedgerRow>> groups = new LinkedHashMap<>();
        for (UsageLedgerRow row : rows) {
            String key = fieldValue(row, field);
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : new TreeMap<>(groups).keySet()) {
            Map<String, Object> b = bucket(groups.get(key));
            b.put("key", key);
            b.put("cost_micros_by_currency", costByCurrency(groups.get(key)));
            out.add(b);
        }
        return out;
    }

    private static List<Map<String, Object>> groupByDay(List<UsageLedgerRow> rows) {
        Map<String, List<UsageLedgerRow>> groups = new LinkedHashMap<>();
        for (UsageLedgerRow row : rows) {
            String created = row.createdAt();
            String day = created != null && created.length() >= 10 ? created.substring(0, 10)
                    : "unknown";
            groups.computeIfAbsent(day, k -> new ArrayList<>()).add(row);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (String key : new TreeMap<>(groups).keySet()) {
            Map<String, Object> b = bucket(groups.get(key));
            b.put("key", key);
            b.put("cost_micros_by_currency", costByCurrency(groups.get(key)));
            out.add(b);
        }
        return out;
    }

    private static String fieldValue(UsageLedgerRow row, String field) {
        String value = switch (field) {
            case "stage" -> row.stage();
            case "model" -> row.model();
            default -> throw new IllegalArgumentException("unknown group field " + field);
        };
        return value == null || value.isEmpty() ? "unknown" : value;
    }

    /** 有 token 用量却没算出成本的模型（缓存命中等零调用行无价格缺口）。 */
    private static List<String> unpricedModels(List<UsageLedgerRow> rows) {
        Set<String> names = new LinkedHashSet<>();
        for (UsageLedgerRow row : rows) {
            if (row.model() == null || row.model().isEmpty()) {
                continue;
            }
            if (row.totalTokens() == null) {
                continue;
            }
            if (row.costMicros() == null) {
                names.add(row.model());
            }
        }
        return new ArrayList<>(names);
    }

    private static Map<String, Integer> costByCurrency(List<UsageLedgerRow> rows) {
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (UsageLedgerRow row : rows) {
            if (row.costMicros() == null || row.currency() == null || row.currency().isEmpty()) {
                continue;
            }
            totals.merge(row.currency(), row.costMicros(), Integer::sum);
        }
        return totals;
    }

    private static Integer sumField(List<UsageLedgerRow> rows,
                                    java.util.function.Function<UsageLedgerRow, Integer> field) {
        List<Integer> present = new ArrayList<>();
        for (UsageLedgerRow row : rows) {
            Integer value = field.apply(row);
            if (value != null) {
                present.add(value);
            }
        }
        return present.isEmpty() ? null : present.stream().mapToInt(Integer::intValue).sum();
    }

    private static int countPresent(List<UsageLedgerRow> rows,
                                    java.util.function.Function<UsageLedgerRow, Integer> field) {
        return (int) rows.stream().filter(r -> field.apply(r) != null).count();
    }
}
