package io.dpdk.assistant.contracts.index;

/** 嵌入配置（model/api_base/dimensions=1024/chunker=bounded-lines-v2）。 */
public record EmbeddingConfig(String model, String apiBase, int dimensions, String chunker) {
}
