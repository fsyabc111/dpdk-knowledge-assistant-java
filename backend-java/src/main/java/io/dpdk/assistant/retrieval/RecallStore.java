package io.dpdk.assistant.retrieval;

import java.util.List;
import java.util.Map;

/**
 * 候选召回存储端口（Python RetrievalStore Protocol 的 J1-03 投影）。
 * 生产实现 LiNodeRetrievalStore（PG）；测试以冻结腿结果构造替身。
 */
public interface RecallStore {

    List<String> fetchFtsCandidateIds(String matchQuery, int limit);

    List<String> fetchPathCandidateIds(List<String> fragments, int limit);

    List<String> fetchSemanticCandidateIds(double[] embedding, int limit);

    List<String> fetchFallbackCandidateIds();

    List<CandidateRow> fetchRowsByIds(List<String> ids);

    /** 单节点回读（li 引用展开）；缺行返回 null。 */
    default Map<String, Object> getChunk(String chunkId) {
        return null;
    }
}
