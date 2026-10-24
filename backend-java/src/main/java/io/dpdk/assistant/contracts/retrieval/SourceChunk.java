package io.dpdk.assistant.contracts.retrieval;

/** 历史来源片段（legacy knowledge_chunks 回读）。 */
public record SourceChunk(String chunkId, String sourcePath, String section, String content) {
}
