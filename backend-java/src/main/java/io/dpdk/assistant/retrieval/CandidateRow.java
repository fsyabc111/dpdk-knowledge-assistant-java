package io.dpdk.assistant.retrieval;

/**
 * 候选行（fetch_rows_by_ids 的回读结构，列名与 PG 别名一致）。
 */
public record CandidateRow(
        String id,
        String content,
        String sourcePath,
        String section,
        String title,
        String metadataJson,
        String embeddingJson) {
}
