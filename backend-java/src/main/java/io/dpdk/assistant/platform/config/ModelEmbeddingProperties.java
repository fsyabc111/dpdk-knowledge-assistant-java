package io.dpdk.assistant.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 嵌入端点绑定（dpdk.model.embedding.*）；默认值与冻结 Python Settings 对齐。 */
@ConfigurationProperties(prefix = "dpdk.model.embedding")
public record ModelEmbeddingProperties(
        String baseUrl,
        String apiKey,
        String modelName,
        @DefaultValue("10") int batchSize,
        @DefaultValue("5") int maxRetries,
        @DefaultValue("1.0") double retryBaseSeconds,
        @DefaultValue("60") long requestTimeoutSeconds) {
}
