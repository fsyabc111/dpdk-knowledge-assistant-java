package io.dpdk.assistant.platform.wiring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.dpdk.assistant.platform.config.UsageRecordingPolicy;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class UsageRecordingPolicyTest {
    @Test void validPriceAndDisabledRecordingPreserveConfiguredVersion() {
        var policy = UsageRecordingPolicy.parse(false,
                "{\"fixed-model\":{\"input_per_1k\":0.01,\"output_per_1k\":0.02,\"currency\":\"CNY\"}}", "test-price-v1");
        assertThat(policy.enabled()).isFalse();
        assertThat(policy.version()).isEqualTo("test-price-v1");
        assertThat(policy.prices().get("fixed-model").inputPer1k()).isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(policy.prices()).doesNotContainKey("unknown-model");
    }
    @Test void invalidPriceIsRejectedRatherThanSilentlyLosingCosts() {
        for (String raw : new String[]{"[]", "{} {}", "broken", "{\"\":{}}",
                "{\"model\":{\"input_per_1k\":-1,\"currency\":\"CNY\"}}", "{\"model\":{}}"}) {
            assertThatThrownBy(() -> UsageRecordingPolicy.parse(true, raw, "v"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
