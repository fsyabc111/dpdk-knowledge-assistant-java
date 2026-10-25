package io.dpdk.assistant.task;

/**
 * 引用数据（finish 落库与 SSE sources 帧共用的最小集，字段与 Citation schema 同位）。
 */
public record CitationData(String chunkId, String sourcePath, String title, String section, double score) {

    /** 终态落库形态：evidence_json 只存 chunk_id/source_path/section 三字段。 */
    public CitationData(String chunkId, String sourcePath, String section) {
        this(chunkId, sourcePath, "", section, 0d);
    }
}
