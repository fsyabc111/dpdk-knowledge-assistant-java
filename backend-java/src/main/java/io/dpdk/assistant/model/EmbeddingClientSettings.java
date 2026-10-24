package io.dpdk.assistant.model;

import java.util.function.DoubleSupplier;

/**
 * 嵌入端点显式配置（对应 EMBEDDING_* 设置；与聊天配置分离）。
 *
 * @param baseUrl               尾斜杠剥掉后拼 /embeddings
 * @param apiKey                Bearer 凭据
 * @param modelName             payload 的 model 字段
 * @param batchSize             每批文本数，使用时 clamp 到 [1,10]（源 embedding_batch_size）
 * @param maxRetries            失败后重试次数上限（不含首次；源 embedding_max_retries）
 * @param retryBaseSeconds      指数退避基数；Retry-After 头优先；指数退避加均匀抖动
 * @param requestTimeoutSeconds 单请求读超时（源 60.0）；连接超时固定 10s
 * @param jitter                抖动源 uniform(0,1)，默认可注入固定值以便确定性测试
 */
public record EmbeddingClientSettings(
        String baseUrl,
        String apiKey,
        String modelName,
        int batchSize,
        int maxRetries,
        double retryBaseSeconds,
        long requestTimeoutSeconds,
        DoubleSupplier jitter) {

    public EmbeddingClientSettings {
        ChatClientSettings.requireConfig(baseUrl, apiKey, "EMBEDDING");
        if (jitter == null) {
            jitter = Math::random;
        }
    }

    public String embeddingsUrl() {
        return ChatClientSettings.stripTrailingSlashes(baseUrl) + "/embeddings";
    }

    /** 源：max(1, min(int(embedding_batch_size), 10))。 */
    public int effectiveBatchSize() {
        return Math.max(1, Math.min(batchSize, 10));
    }
}
