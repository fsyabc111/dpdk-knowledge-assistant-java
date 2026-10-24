package io.dpdk.assistant.contracts.retrieval;

import java.util.List;

/** 检索结果（候选/trace/排序载体）；字段由 J1-03 登记补全。 */
public record SearchResult(List<RetrievedChunk> chunks, Object trace) {
}
