package io.dpdk.assistant.platform.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.usage.UsageCore;
import java.util.Map;

/** 运行期用量策略快照；价格配置在启动期从严验证，未知模型仍不虚构成本。 */
public record UsageRecordingPolicy(boolean enabled, Map<String, UsageCore.ModelPrice> prices, String version) {
    public static UsageRecordingPolicy parse(boolean enabled, String raw, String version) {
        String text = raw == null ? "" : raw.strip();
        if (text.isEmpty()) { return new UsageRecordingPolicy(enabled, Map.of(), version); }
        try {
            var root = new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
            if (!root.isObject()) { throw new IllegalArgumentException("LLM_PRICES_JSON必须是对象"); }
            Map<String, UsageCore.ModelPrice> parsed = UsageCore.parsePrices(text);
            var names = root.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                if (name.isBlank() || !root.get(name).isObject() || !parsed.containsKey(name)) {
                    throw new IllegalArgumentException("LLM_PRICES_JSON包含无效价格配置");
                }
            }
            return new UsageRecordingPolicy(enabled, Map.copyOf(parsed), version);
        } catch (java.io.IOException invalid) {
            throw new IllegalArgumentException("LLM_PRICES_JSON不是合法JSON", invalid);
        }
    }
}
