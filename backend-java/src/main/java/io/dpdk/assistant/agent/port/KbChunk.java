package io.dpdk.assistant.agent.port;

/**
 * kb_search 命中的知识片段（对位 Python rag.types.RetrievedChunk 的消费面字段）。
 *
 * <p>集成期由 J1-03 的 ReleaseReader/检索管线适配产出；chunkId 即引用身份。
 */
public record KbChunk(String chunkId, String sourcePath, String section, String title,
                      String content, double score) {

    public KbChunk {
        chunkId = chunkId == null ? "" : chunkId;
        sourcePath = sourcePath == null ? "" : sourcePath;
        section = section == null ? "" : section;
        title = title == null ? "" : title;
        content = content == null ? "" : content;
    }
}
