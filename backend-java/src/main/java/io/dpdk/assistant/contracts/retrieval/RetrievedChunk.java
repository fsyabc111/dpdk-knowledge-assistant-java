package io.dpdk.assistant.contracts.retrieval;

/** 检索片段（node_id/text/score 等）；字段由 J1-03 登记补全。 */
public record RetrievedChunk(String chunkId, String text, double score) {
}
