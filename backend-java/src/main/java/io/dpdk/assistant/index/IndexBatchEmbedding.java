package io.dpdk.assistant.index;

import io.dpdk.assistant.contracts.model.Vector1024;
import io.dpdk.assistant.usage.UsageCore;
import java.util.List;

/** 批量嵌入边界；适配器记录 ingest_embed 用量，包括失败前已消耗的调用。 */
@FunctionalInterface
public interface IndexBatchEmbedding {
    List<Vector1024> embed(List<String> texts, UsageCore.UsageRecorder usage);
}
