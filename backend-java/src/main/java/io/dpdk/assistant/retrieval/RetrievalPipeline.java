package io.dpdk.assistant.retrieval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 固定 release 内的融合/重排/MMR 管线（逐行移植 RetrievalService 的
 * _fetch_candidate_rows / _retrieve_sync / _select_diverse_chunks）。
 *
 * - candidate_limit = max(limit*28, 160)；改写 FTS 腿用满额，其余三腿
 *   max(candidate_limit//2, 40)；
 * - 单腿异常按源的 recall() 包装降级（结构化日志 + trace fallback 标记），
 *   不影响其他腿；语义腿缺向量（空）时整腿为空；
 * - RRF 融合 stable 排序（并列保持首次出现序）；行回读截断 candidate_limit；
 * - 重排五信号 + RRF 先验（封顶）；semantic==0 走无语义分支；
 *   score&lt;=0 丢弃；全部为空时按源给 rows[:limit] 记 0.01 分；
 * - MMR：per-file 上限、冗余（token Jaccard 最大值）与同文件惩罚，
 *   严格大于才替换（首见优先）。
 */
public final class RetrievalPipeline {

    private static final Logger log = LoggerFactory.getLogger(RetrievalPipeline.class);

    private final RecallStore store;
    private final RetrievalSettings settings;

    public RetrievalPipeline(RecallStore store, RetrievalSettings settings) {
        this.store = store;
        this.settings = settings;
    }

    /** 完整同步检索（对齐 search→_retrieve_sync 的同步部分）。 */
    public Result retrieve(String query, String rewritten, double[] queryEmbedding, int limit) {
        int candidateLimit = Math.max(limit * 28, 160);
        List<String> fallbacks = new ArrayList<>();

        List<String> fragments = RetrievalText.pathFragments(query, rewritten);
        String rewrittenFts = orEmpty(RetrievalText.buildFtsQuery(rewritten));
        String originalFts = orEmpty(RetrievalText.buildFtsQuery(query));

        List<String> rewrittenIds = recall("fetch_fts_candidate_ids(rewritten)",
                () -> store.fetchFtsCandidateIds(rewrittenFts, candidateLimit), fallbacks);
        List<String> originalIds = recall("fetch_fts_candidate_ids(original)",
                () -> store.fetchFtsCandidateIds(originalFts, halfLegLimit(candidateLimit)), fallbacks);
        List<String> pathIds = recall("fetch_path_candidate_ids",
                () -> store.fetchPathCandidateIds(fragments, halfLegLimit(candidateLimit)), fallbacks);
        List<String> semanticIds = recall("fetch_semantic_candidate_ids",
                () -> store.fetchSemanticCandidateIds(queryEmbedding, halfLegLimit(candidateLimit)), fallbacks);

        LinkedHashMap<String, CandidateRanks> ranks = new LinkedHashMap<>();
        recordRanks(ranks, rewrittenIds, "fts_rewritten");
        recordRanks(ranks, originalIds, "fts_original");
        recordRanks(ranks, pathIds, "path");
        recordRanks(ranks, semanticIds, "semantic");

        List<String> candidateIds = new ArrayList<>(ranks.keySet());
        candidateIds.sort((a, b) -> Double.compare(
                ranks.get(b).rrfScore(settings.rrfK()),
                ranks.get(a).rrfScore(settings.rrfK())));

        if (candidateIds.size() < Math.max(12, candidateLimit / 3)) {
            // 保守 fallback（当前实现恒为空：无证据时不补任意候选）。
            for (String chunkId : store.fetchFallbackCandidateIds()) {
                if (!candidateIds.contains(chunkId)) {
                    candidateIds.add(chunkId);
                    ranks.putIfAbsent(chunkId, new CandidateRanks());
                }
            }
        }

        List<String> loadIds = candidateIds.subList(0, Math.min(candidateIds.size(), candidateLimit));
        List<CandidateRow> rows = store.fetchRowsByIds(loadIds);

        List<RetrievedNode> chunks = new ArrayList<>();
        for (CandidateRow row : rows) {
            Map<String, Object> metadata = PythonJson.parseObject(row.metadataJson());
            double[] embedding = PythonJson.parseVectorText(row.embeddingJson());
            double semantic = queryEmbedding != null && queryEmbedding.length > 0
                    && embedding.length > 0
                    ? VectorMath.cosineSimilarity(queryEmbedding, embedding) : 0.0;
            double lexical = Math.max(
                    RetrievalFeatures.keywordScore(rewritten, row.content(), metadata),
                    RetrievalFeatures.keywordScore(query, row.content(), metadata));
            double ident = Math.max(
                    RetrievalFeatures.identifierScore(query, row.sourcePath(),
                            orEmpty(row.section()), row.content(), settings),
                    RetrievalFeatures.identifierScore(rewritten, row.sourcePath(),
                            orEmpty(row.section()), row.content(), settings));
            double fileBoost = Math.max(
                    RetrievalFeatures.filenameBoost(query, row.sourcePath(), settings),
                    RetrievalFeatures.filenameBoost(rewritten, row.sourcePath(), settings));
            double topic = Math.max(
                    RetrievalFeatures.topicBoost(query, row.sourcePath(), settings),
                    RetrievalFeatures.topicBoost(rewritten, row.sourcePath(), settings));
            double pathBoost = Math.max(fileBoost, topic);
            double rrf = ranks.getOrDefault(row.id(), new CandidateRanks()).rrfScore(settings.rrfK());
            double sourcePrior = Math.min(rrf * settings.rrfPriorScale(), settings.rrfPriorCap());
            double structural = RetrievalFeatures.definitionBoost(row.content(), settings);
            double score;
            if (semantic != 0.0) {
                score = semantic * settings.wSemantic()
                        + lexical * settings.wLexical()
                        + ident * settings.wIdent()
                        + sourcePrior + pathBoost + structural;
            } else {
                score = lexical * settings.wLexicalNoSemantic()
                        + ident * settings.wIdentNoSemantic()
                        + sourcePrior + pathBoost + structural;
            }
            if (score <= 0) {
                continue;
            }
            chunks.add(new RetrievedNode(
                    row.id(),
                    row.sourcePath(),
                    orDefault(row.title(), row.sourcePath()),
                    orEmpty(row.section()),
                    row.content(),
                    metadata,
                    score));
        }
        if (chunks.isEmpty()) {
            for (CandidateRow row : rows.subList(0, Math.min(rows.size(), limit))) {
                Map<String, Object> metadata = PythonJson.parseObject(row.metadataJson());
                chunks.add(new RetrievedNode(
                        row.id(),
                        row.sourcePath(),
                        orDefault(row.title(), row.sourcePath()),
                        orEmpty(row.section()),
                        row.content(),
                        metadata,
                        0.01));
            }
        }
        chunks.sort((a, b) -> Double.compare(b.score(), a.score()));

        List<RetrievedNode> selected = selectDiverseChunks(chunks, limit);

        Map<String, int[]> legRanks = new LinkedHashMap<>();
        for (var entry : ranks.entrySet()) {
            CandidateRanks r = entry.getValue();
            legRanks.put(entry.getKey(), new int[]{
                    r.ftsRewritten() == null ? 0 : r.ftsRewritten(),
                    r.ftsOriginal() == null ? 0 : r.ftsOriginal(),
                    r.path() == null ? 0 : r.path(),
                    r.semantic() == null ? 0 : r.semantic()});
        }

        return new Result(selected, new RetrievalTrace(
                null, null, query, rewritten, null,
                queryEmbedding == null ? 0 : queryEmbedding.length,
                fallbacks,
                rewrittenIds.size(), originalIds.size(), pathIds.size(), semanticIds.size(),
                candidateIds.size(), candidateLimit,
                chunks.size(), selected.size(), selected, legRanks));
    }

    /** _select_diverse_chunks。 */
    List<RetrievedNode> selectDiverseChunks(List<RetrievedNode> chunks, int limit) {
        List<RetrievedNode> selected = new ArrayList<>();
        Map<String, Integer> perFile = new HashMap<>();
        Map<String, java.util.Set<String>> tokenCache = new LinkedHashMap<>();
        for (RetrievedNode c : chunks) {
            tokenCache.put(c.id(), RetrievalText.textTokens(c.content()));
        }
        List<RetrievedNode> remaining = new ArrayList<>(chunks);

        while (!remaining.isEmpty() && selected.size() < limit) {
            int bestIndex = 0;
            double bestScore = Double.NEGATIVE_INFINITY;
            for (int index = 0; index < remaining.size(); index++) {
                RetrievedNode chunk = remaining.get(index);
                if (perFile.getOrDefault(chunk.sourcePath(), 0) >= settings.mmrPerFileCap()) {
                    continue;
                }
                java.util.Set<String> chunkTokens = tokenCache.get(chunk.id());
                double redundancy = 0.0;
                for (RetrievedNode chosen : selected) {
                    java.util.Set<String> chosenTokens = tokenCache.get(chosen.id());
                    if (chunkTokens.isEmpty() || chosenTokens.isEmpty()) {
                        continue;
                    }
                    double jaccard = RetrievalText.tokenJaccard(chunkTokens, chosenTokens);
                    if (jaccard > redundancy) {
                        redundancy = jaccard;
                    }
                }
                double diversityPenalty = settings.mmrRedundancyPenalty() * redundancy
                        + settings.mmrPerFilePenalty() * perFile.getOrDefault(chunk.sourcePath(), 0);
                double score = chunk.score() - diversityPenalty;
                if (score > bestScore) {
                    bestScore = score;
                    bestIndex = index;
                }
            }
            if (bestScore == Double.NEGATIVE_INFINITY) {
                break;
            }
            RetrievedNode chosen = remaining.remove(bestIndex);
            selected.add(chosen);
            perFile.merge(chosen.sourcePath(), 1, Integer::sum);
        }
        return selected;
    }

    private List<String> recall(String channel, Leg leg, List<String> fallbacks) {
        try {
            return leg.call();
        } catch (Exception e) {
            log.warn("召回通道失败 channel={} error_type={}", channel, e.getClass().getSimpleName());
            fallbacks.add(channel + " failed");
            return List.of();
        }
    }

    private static void recordRanks(LinkedHashMap<String, CandidateRanks> ranks,
                                    List<String> chunkIds, String field) {
        for (int i = 0; i < chunkIds.size(); i++) {
            ranks.computeIfAbsent(chunkIds.get(i), k -> new CandidateRanks()).record(field, i + 1);
        }
    }

    private static int halfLegLimit(int candidateLimit) {
        return Math.max(candidateLimit / 2, 40);
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    @FunctionalInterface
    private interface Leg {

        List<String> call();
    }

    /** 管线输出：最终片段 + trace（release 字段由 PgReleaseReader 填充）。 */
    public record Result(List<RetrievedNode> nodes, RetrievalTrace trace) {

        public Result {
            nodes = List.copyOf(nodes);
        }
    }
}
