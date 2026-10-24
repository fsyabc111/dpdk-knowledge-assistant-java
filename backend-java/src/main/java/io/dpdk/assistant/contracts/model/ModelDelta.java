package io.dpdk.assistant.contracts.model;

/** 流式增量（流读边界 ≠ SSE 边界，契约 transaction-ports.json：model.ChatAndEmbeddingPort）。 */
public record ModelDelta(String text) {
}
