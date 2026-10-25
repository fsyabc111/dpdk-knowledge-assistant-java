package io.dpdk.assistant.usage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * upstream_usage_records 账本行（USAGE_COLUMNS 全集；token 三列 null=未知，0=确实没花）。
 */
public record UsageLedgerRow(
        String id,
        String requestId,
        String subject,
        String sessionId,
        String kind,
        String stage,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        String usageSource,
        int callCount,
        Integer durationMs,
        Integer costMicros,
        String currency,
        String priceVersion,
        String createdAt) {

    /** USAGE_COLUMNS（落库列序）。 */
    public static final List<String> COLUMNS = List.of(
            "id", "request_id", "subject", "session_id", "kind", "stage", "model",
            "prompt_tokens", "completion_tokens", "total_tokens", "usage_source",
            "call_count", "duration_ms", "cost_micros", "currency", "price_version",
            "created_at");

    /** VALUES 绑定序（含 NOT NULL 兜底的行值）。 */
    List<Object> values() {
        List<Object> out = new ArrayList<>(17);
        out.add(id);
        out.add(requestId);
        out.add(subject);
        out.add(sessionId);
        out.add(kind);
        out.add(stage);
        out.add(model);
        out.add(promptTokens);
        out.add(completionTokens);
        out.add(totalTokens);
        out.add(usageSource);
        out.add(callCount);
        out.add(durationMs);
        out.add(costMicros);
        out.add(currency);
        out.add(priceVersion);
        out.add(createdAt);
        return out;
    }

    /** 聚合视角的只读 Map（字段名与列一致；聚合纯函数入口）。 */
    public Map<String, Object> asMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("request_id", requestId);
        out.put("subject", subject);
        out.put("session_id", sessionId);
        out.put("kind", kind);
        out.put("stage", stage);
        out.put("model", model);
        out.put("prompt_tokens", promptTokens);
        out.put("completion_tokens", completionTokens);
        out.put("total_tokens", totalTokens);
        out.put("usage_source", usageSource);
        out.put("call_count", callCount);
        out.put("duration_ms", durationMs);
        out.put("cost_micros", costMicros);
        out.put("currency", currency);
        out.put("price_version", priceVersion);
        out.put("created_at", createdAt);
        return out;
    }
}
