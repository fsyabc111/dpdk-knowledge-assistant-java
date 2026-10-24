package io.dpdk.assistant.retrieval;

/**
 * 候选四路 rank（CandidateRanks）：rank 从 1 起；缺席为 null。
 */
public final class CandidateRanks {

    private Integer ftsRewritten;
    private Integer ftsOriginal;
    private Integer path;
    private Integer semantic;

    /** 命中腿记 rank（首个 rank 生效，与 _record_ranks 的 None 检查一致）。 */
    void record(String field, int rank) {
        switch (field) {
            case "fts_rewritten" -> {
                if (ftsRewritten == null) {
                    ftsRewritten = rank;
                }
            }
            case "fts_original" -> {
                if (ftsOriginal == null) {
                    ftsOriginal = rank;
                }
            }
            case "path" -> {
                if (path == null) {
                    path = rank;
                }
            }
            case "semantic" -> {
                if (semantic == null) {
                    semantic = rank;
                }
            }
            default -> throw new IllegalArgumentException("未知召回腿: " + field);
        }
    }

    /** rrf_score(k)：各在场腿 1/(k+rank) 求和（固定腿顺序求和，与源一致）。 */
    public double rrfScore(int k) {
        double score = 0.0;
        for (Integer rank : new Integer[]{ftsRewritten, ftsOriginal, path, semantic}) {
            if (rank != null) {
                score += 1.0 / (k + rank);
            }
        }
        return score;
    }

    /** 由四腿 rank（0=缺席）计算 rrf（测试与诊断用）。 */
    public static double rrfOf(int ftsRewrittenRank, int ftsOriginalRank, int pathRank,
                               int semanticRank, int k) {
        CandidateRanks r = new CandidateRanks();
        if (ftsRewrittenRank > 0) {
            r.record("fts_rewritten", ftsRewrittenRank);
        }
        if (ftsOriginalRank > 0) {
            r.record("fts_original", ftsOriginalRank);
        }
        if (pathRank > 0) {
            r.record("path", pathRank);
        }
        if (semanticRank > 0) {
            r.record("semantic", semanticRank);
        }
        return r.rrfScore(k);
    }

    public Integer ftsRewritten() {
        return ftsRewritten;
    }

    public Integer ftsOriginal() {
        return ftsOriginal;
    }

    public Integer path() {
        return path;
    }

    public Integer semantic() {
        return semantic;
    }
}
