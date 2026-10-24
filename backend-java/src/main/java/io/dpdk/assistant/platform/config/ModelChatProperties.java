package io.dpdk.assistant.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 聊天端点绑定（dpdk.model.chat.*）；默认值与冻结 Python Settings 对齐。 */
@ConfigurationProperties(prefix = "dpdk.model.chat")
public record ModelChatProperties(
        String baseUrl,
        String apiKey,
        String modelName,
        @DefaultValue("2048") int maxTokens,
        @DefaultValue("3") int maxRetries,
        @DefaultValue("0.5") double retryBaseSeconds,
        @DefaultValue("90") long requestTimeoutSeconds,
        @DefaultValue("true") boolean streamUsageEnabled) {
}
