package io.dpdk.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** usage 解析矩阵与账本语义（对齐 usage.tokens_from_usage / UsageRecorder）。 */
class ModelUsageLedgerTest {

    private static Map<String, Object> usage(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------
    // tokens_from_usage 矩阵
    // ------------------------------------------------------------------

    @Test
    void nullUsageIsAllUnknown() {
        Map<String, Integer> tokens = ModelUsageLedger.tokensFromUsage(null);
        assertThat(tokens.get("prompt_tokens")).isNull();
        assertThat(tokens.get("completion_tokens")).isNull();
        assertThat(tokens.get("total_tokens")).isNull();
    }

    @Test
    void openAiKeysAreAccepted() {
        Map<String, Integer> tokens = ModelUsageLedger.tokensFromUsage(
                usage("prompt_tokens", 120, "completion_tokens", 30, "total_tokens", 150));
        assertThat(tokens).containsEntry("prompt_tokens", 120)
                .containsEntry("completion_tokens", 30)
                .containsEntry("total_tokens", 150);
    }

    @Test
    void langChainKeysAreAccepted() {
        Map<String, Integer> tokens = ModelUsageLedger.tokensFromUsage(
                usage("input_tokens", 7, "output_tokens", 3));
        assertThat(tokens.get("prompt_tokens")).isEqualTo(7);
        assertThat(tokens.get("completion_tokens")).isEqualTo(3);
        // 只给分项时 total = prompt + completion 是等价推导，不是估算。
        assertThat(tokens.get("total_tokens")).isEqualTo(10);
    }

    @Test
    void partialPartsDoNotDeriveTotal() {
        Map<String, Integer> tokens = ModelUsageLedger.tokensFromUsage(usage("input_tokens", 7));
        assertThat(tokens.get("total_tokens")).isNull();
    }

    @Test
    void malformedValuesStayUnknown() {
        // 布尔、负数、非整浮点、非数字串、小数串一律 null，不猜。
        Map<String, Integer> tokens = ModelUsageLedger.tokensFromUsage(usage(
                "prompt_tokens", true,
                "completion_tokens", -3,
                "total_tokens", 1.5));
        assertThat(tokens.get("prompt_tokens")).isNull();
        assertThat(tokens.get("completion_tokens")).isNull();
        assertThat(tokens.get("total_tokens")).isNull();
        assertThat(ModelUsageLedger.tokensFromUsage(usage("total_tokens", " 12 ")).get("total_tokens"))
                .isEqualTo(12);
        assertThat(ModelUsageLedger.tokensFromUsage(usage("total_tokens", "12.0")).get("total_tokens"))
                .isNull();
        assertThat(ModelUsageLedger.tokensFromUsage(usage("total_tokens", 20.0)).get("total_tokens"))
                .isEqualTo(20);
    }

    // ------------------------------------------------------------------
    // 账本
    // ------------------------------------------------------------------

    @Test
    void upstreamUsageWithAnyTokenIsUpstreamSource() {
        ModelUsageLedger ledger = ModelUsageLedger.create();
        ledger.recordLlm("rewrite", "m", usage("total_tokens", 5), 10L, ModelUsageLedger.KIND_LLM, 1);
        ModelUsageEntry entry = ledger.entries().get(0);
        assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UPSTREAM);
        assertThat(entry.usageKnown()).isTrue();
        assertThat(entry.priced()).isTrue();
    }

    @Test
    void missingUsageRecordsUnknownNotZero() {
        ModelUsageLedger ledger = ModelUsageLedger.create();
        ledger.recordLlm("rewrite", "m", null, 5L, ModelUsageLedger.KIND_LLM, 3);
        ModelUsageEntry entry = ledger.entries().get(0);
        assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
        assertThat(entry.promptTokens()).isNull();
        assertThat(entry.completionTokens()).isNull();
        assertThat(entry.totalTokens()).isNull();
        assertThat(entry.callCount()).isEqualTo(3); // 重试次数在账面可见
    }

    @Test
    void skippedRecordsZeroCallAuditTrail() {
        ModelUsageLedger ledger = ModelUsageLedger.create();
        ledger.recordSkipped("rewrite", ModelUsageLedger.SOURCE_CACHE_HIT, "m", ModelUsageLedger.KIND_LLM);
        ledger.recordSkipped("rewrite", "not_a_known_reason", "m", ModelUsageLedger.KIND_LLM);
        List<ModelUsageEntry> entries = ledger.entries();
        assertThat(entries.get(0).callCount()).isZero();
        assertThat(entries.get(0).usageSource()).isEqualTo(ModelUsageLedger.SOURCE_CACHE_HIT);
        // 未知 reason 降级为 unknown：不替调用方编造来源。
        assertThat(entries.get(1).usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
    }

    @Test
    void operationsAreNeverPriced() {
        ModelUsageLedger ledger = ModelUsageLedger.create();
        ledger.recordOperation(ModelUsageLedger.KIND_MCP, "grade", 42L, 1);
        ModelUsageEntry entry = ledger.entries().get(0);
        assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_OPERATION);
        assertThat(entry.priced()).isFalse();
        assertThat(entry.totalTokens()).isNull();
        assertThat(ModelUsageEntry.costMicros(null, null, new ModelUsageEntry.ModelPrice(
                new BigDecimal("0.001"), new BigDecimal("0.002"), "CNY"))).isNull();
    }

    @Test
    void totalsSeparateUnknownFromZeroCallAndKnownSums() {
        ModelUsageLedger ledger = ModelUsageLedger.create();
        ledger.recordLlm("answer", "m", usage("prompt_tokens", 10, "completion_tokens", 5,
                "total_tokens", 15), 10L, ModelUsageLedger.KIND_LLM, 1);
        ledger.recordLlm("planner", "m", null, 10L, ModelUsageLedger.KIND_LLM, 2);
        ledger.recordSkipped("rewrite", ModelUsageLedger.SOURCE_CACHE_HIT, "m", ModelUsageLedger.KIND_LLM);
        ledger.recordOperation(ModelUsageLedger.KIND_HARNESS, "grade", 1L, 1);

        Map<String, Object> totals = ledger.totals();
        assertThat(totals.get("entries")).isEqualTo(4);
        assertThat(totals.get("calls")).isEqualTo(4);
        assertThat(totals.get("prompt_tokens")).isEqualTo(10);
        assertThat(totals.get("total_tokens")).isEqualTo(15);
        assertThat(totals.get("total_tokens_entries")).isEqualTo(1);
        assertThat(totals.get("unknown_entries")).isEqualTo(1);
        assertThat(totals.get("zero_call_entries")).isEqualTo(1);
        assertThat((List<?>) totals.get("stages"))
                .isEqualTo(List.of("answer", "grade", "planner", "rewrite"));
    }

    @Test
    void costMicrosUsesDecimalHalfUpAndSkipsUnpriced() {
        ModelUsageEntry.ModelPrice price = new ModelUsageEntry.ModelPrice(
                new BigDecimal("0.0008"), new BigDecimal("0.002"), "CNY");
        // 1500 prompt + 500 completion：1500*0.0008/1000 + 500*0.002/1000 = 0.0022 → 2200 micros。
        assertThat(ModelUsageEntry.costMicros(1500, 500, price)).isEqualTo(2200);
        // 半上入：0.000001 的尾数按 HALF_UP 进位。
        assertThat(ModelUsageEntry.costMicros(1001, 0, price)).isEqualTo(801);
        assertThat(ModelUsageEntry.costMicros(120, null, price)).isEqualTo(96);
        assertThat(ModelUsageEntry.costMicros(null, null, null)).isNull();
    }
}
