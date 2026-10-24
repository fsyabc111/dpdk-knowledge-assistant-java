package io.dpdk.assistant.retrieval;

import java.util.List;
import java.util.Map;

/**
 * 检索 trace（确定性字段；不含耗时）。
 *
 * SearchResult.trace 为 Object，本类型即其载体；nodes 携带完整富字段片段，
 * 供 J1-04 在契约 RetrievedChunk 字段补全前使用。
 *
 * @param releaseId         本次检索绑定的 release（fail-closed 时为空串）
 * @param blockedReason     fail-closed 原因（null = 正常检索）
 * @param query             原问题
 * @param rewrittenQuery    改写后查询
 * @param embeddingQuery    语义腿实际嵌入文本（original+rewritten 规则）
 * @param embeddingDim      查询向量维度（0 = 无语义腿）
 * @param fallbacks         降级标记（召回腿失败/嵌入失败等）
 * @param ftsRewrittenCandidates / ftsOriginalCandidates / pathCandidates / semanticCandidates 四腿命中数
 * @param fusedCandidates   RRF 融合后的候选数
 * @param candidateLimit    候选上限（max(limit*28,160)）
 * @param rerankedChunks    重排后片段数
 * @param selectedChunks    MMR 最终选中数
 * @param nodes             最终片段（富字段）
 */
public record RetrievalTrace(
        String releaseId,
        String blockedReason,
        String query,
        String rewrittenQuery,
        String embeddingQuery,
        int embeddingDim,
        List<String> fallbacks,
        int ftsRewrittenCandidates,
        int ftsOriginalCandidates,
        int pathCandidates,
        int semanticCandidates,
        int fusedCandidates,
        int candidateLimit,
        int rerankedChunks,
        int selectedChunks,
        List<RetrievedNode> nodes,
        Map<String, int[]> legRanks) {

    public RetrievalTrace {
        fallbacks = fallbacks == null ? List.of() : List.copyOf(fallbacks);
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }
}
