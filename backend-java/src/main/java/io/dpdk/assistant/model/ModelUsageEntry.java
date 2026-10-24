package io.dpdk.assistant.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 一次上游花费（或一次显式的「零调用」）账目条目；对齐 backend/app/core/usage.py
 * 的 UsageEntry。token 为 null 表示未知（上游没给），绝不以 0 顶替。
 */
public record ModelUsageEntry(
        String kind,
        String stage,
        String usageSource,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        int callCount,
        Long durationMs) {

    /** 计费 kind：harness/MCP 是外部算力，本仓不知其单价，显式不计费。 */
    public boolean priced() {
        return ModelUsageLedger.PRICED_KINDS.contains(kind);
    }

    public boolean usageKnown() {
        return totalTokens != null;
    }

    /**
     * 整数微单位成本（1e-6 币种单位）；任一前提缺失（未配价 / 两个 token 都未知）→ null。
     * 对齐 usage.cost_micros：金额不可用二进制浮点，用 BigDecimal 半上入。
     */
    public static Integer costMicros(
            Integer promptTokens, Integer completionTokens, ModelPrice price) {
        if (price == null) {
            return null;
        }
        if (promptTokens == null && completionTokens == null) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        if (promptTokens != null) {
            total = total.add(BigDecimal.valueOf(promptTokens)
                    .multiply(price.inputPer1k())
                    .divide(BigDecimal.valueOf(1000)));
        }
        if (completionTokens != null) {
            total = total.add(BigDecimal.valueOf(completionTokens)
                    .multiply(price.outputPer1k())
                    .divide(BigDecimal.valueOf(1000)));
        }
        return total.multiply(BigDecimal.valueOf(1_000_000))
                .setScale(0, RoundingMode.HALF_UP)
                .intValueExact();
    }

    /** 单模型单价（每 1000 token，字符串解析为 BigDecimal 保持精确）。 */
    public record ModelPrice(BigDecimal inputPer1k, BigDecimal outputPer1k, String currency) {
    }
}
