package io.dpdk.assistant.operations.adapter;

import io.dpdk.assistant.operations.OperationsMetricsPort;
import io.dpdk.assistant.task.TaskStore;
import io.dpdk.assistant.usage.UsageCore;
import io.dpdk.assistant.usage.UsageLedgerStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 指标为当前持久账本 gauge，留存清理会下降；不是进程单调 counter。未知值不折零。 */
public final class OperationsDomainMetricsAdapter implements OperationsMetricsPort {
    private final TaskStore tasks;
    private final UsageLedgerStore usage;
    private record Key(String name, Map<String, String> labels) { }
    /** 未持久化的域观测不能由任务状态、默认零或调用数冒充。 */
    public static final List<String> UNAVAILABLE = List.of(
            "assistant_llm_requests_total", "assistant_llm_request_duration_seconds",
            "assistant_chat_retrieval_duration_seconds", "assistant_chat_stage_duration_seconds",
            "assistant_chat_fallbacks_total", "assistant_rate_limit_hits_total", "assistant_rag_duration_seconds",
            "assistant_rag_validation_duration_seconds", "assistant_agent_tool_calls_total",
            "assistant_agent_tool_duration_seconds", "assistant_agent_tool_result_bytes", "assistant_agent_request_tokens");

    public OperationsDomainMetricsAdapter(TaskStore tasks, UsageLedgerStore usage) {
        this.tasks = Objects.requireNonNull(tasks); this.usage = Objects.requireNonNull(usage);
    }
    @Override public Snapshot snapshot() {
        Map<Key, Double> values = new LinkedHashMap<>();
        var persisted = tasks.metricsSnapshot();
        for (var row : persisted.outcomes()) {
            Map<String, String> labels = Map.of("status", label(row.status()), "reason", label(row.reason()));
            add(values, "assistant_java_persisted_task_records", labels, row.count());
            if (!"pending".equals(row.status())) {
                add(values, "assistant_rag_outcomes_total", labels, row.count());
                add(values, "assistant_java_persisted_task_elapsed_seconds_sum", labels, row.elapsedMs()/1000.0);
            }
        }
        for (var row : persisted.jobs())
            add(values, "assistant_java_persisted_job_records", Map.of("tool", label(row.tool()), "status", label(row.status())), row.count());
        for (var row : usage.metricsSnapshot()) {
            Map<String, String> base = Map.of("stage", label(row.stage()), "kind", label(row.kind()));
            Map<String, String> source = new LinkedHashMap<>(base); source.put("source", label(row.source()));
            add(values, "assistant_java_persisted_usage_records", source, row.rows());
            add(values, "assistant_java_persisted_usage_calls", source, row.calls());
            if (row.tokens() != null) add(values, "assistant_upstream_usage_tokens_total", source, row.tokens());
            // 真正的零调用与未知上游用量保持分开。
            if (UsageCore.SOURCE_UNKNOWN.equals(row.source()))
                add(values, "assistant_upstream_usage_unknown_total", base, row.rows());
            if (row.calls() > 0 && row.unknownTokens() > 0)
                add(values, "assistant_java_usage_token_unknown_records", source, row.unknownTokens());
            if (row.unknownCosts() > 0)
                add(values, "assistant_java_usage_cost_unknown_records", source, row.unknownCosts());
            if (row.costMicros() != null && row.currency() != null && !row.currency().isBlank()) {
                Map<String, String> cost = new LinkedHashMap<>(base);
                cost.put("model", label(row.model())); cost.put("currency", row.currency());
                add(values, "assistant_upstream_usage_cost_micros_total", cost, row.costMicros());
            }
            if (row.durationMs() != null)
                add(values, "assistant_java_persisted_usage_duration_seconds_sum", source, row.durationMs()/1000.0);
        }
        List<Sample> samples = new ArrayList<>();
        values.forEach((key, value) -> samples.add(new Sample(key.name(), key.labels(), value)));
        return new Snapshot(samples, UNAVAILABLE);
    }
    private static String label(String value) { return value == null || value.isBlank() ? "unknown" : value; }
    private static void add(Map<Key, Double> values, String name, Map<String, String> labels, double value) {
        values.merge(new Key(name, Map.copyOf(labels)), value, Double::sum);
    }
}
