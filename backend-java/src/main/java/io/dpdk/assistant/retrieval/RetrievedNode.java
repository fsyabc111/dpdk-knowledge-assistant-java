package io.dpdk.assistant.retrieval;

import java.util.Map;

/**
 * 检索片段（结构等价 Python rag.types.RetrievedChunk）。
 *
 * 契约 RetrievedChunk(chunkId,text,score) 是其投影；本类型携带 J1-04 引用
 * 所需的 source_path/section/title/metadata（契约字段补全请求见 handoff）。
 */
public record RetrievedNode(
        String id,
        String sourcePath,
        String title,
        String section,
        String content,
        Map<String, Object> metadata,
        double score) {

    public RetrievedNode {
        if (id == null) {
            throw new IllegalArgumentException("id 不能为空");
        }
    }
}
