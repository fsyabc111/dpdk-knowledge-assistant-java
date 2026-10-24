package io.dpdk.assistant.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/**
 * 融合/重排/MMR 管线纯 Java 对照冻结 Python（无 PG）：
 * 替身 store 返回 legs.json 冻结的四腿结果与行回读（pg_row_order 顺序），
 * 逐阶段断言腿入参、融合序、rank、总分与最终 Top-K。容差 1e-12。
 */
class RetrievalPipelineGoldenTest {

    private static final Offset<Double> TOL = Offset.offset(1e-12);

    private final RetrievalSettings settings = RetrievalSettings.defaults();

    static final class FrozenStore implements RecallStore {

        final JsonNode caseLegs;
        final Map<String, CandidateRow> rowsByid = new LinkedHashMap<>();
        final List<String> rowOrder;
        final List<String> ftsCalls = new ArrayList<>();
        final List<Integer> ftsLimits = new ArrayList<>();
        List<String> lastLoadedIds;

        FrozenStore(JsonNode caseLegs, Map<String, CandidateRow> rows, List<String> rowOrder) {
            this.caseLegs = caseLegs;
            this.rowsByid.putAll(rows);
            this.rowOrder = rowOrder;
        }

        @Override
        public List<String> fetchFtsCandidateIds(String matchQuery, int limit) {
            ftsCalls.add(matchQuery);
            ftsLimits.add(limit);
            if (matchQuery.isEmpty()) {
                return List.of();
            }
            if (matchQuery.equals(caseLegs.get("fts_query_rewritten").asText())) {
                return jsonStrings(caseLegs.get("fts_rewritten_ids"));
            }
            if (matchQuery.equals(caseLegs.get("fts_query_original").asText())) {
                return jsonStrings(caseLegs.get("fts_original_ids"));
            }
            throw new AssertionError("未冻结的 FTS 查询串: " + matchQuery);
        }

        @Override
        public List<String> fetchPathCandidateIds(List<String> fragments, int limit) {
            if (fragments.isEmpty()) {
                return List.of();
            }
            assertThat(fragments)
                    .containsExactlyElementsOf(jsonStrings(caseLegs.get("path_fragments")));
            return jsonStrings(caseLegs.get("path_ids"));
        }

        @Override
        public List<String> fetchSemanticCandidateIds(double[] embedding, int limit) {
            if (embedding == null || embedding.length == 0) {
                return List.of();
            }
            return jsonStrings(caseLegs.get("semantic_ids"));
        }

        @Override
        public List<String> fetchFallbackCandidateIds() {
            return List.of();
        }

        @Override
        public List<CandidateRow> fetchRowsByIds(List<String> ids) {
            lastLoadedIds = new ArrayList<>(ids);
            List<CandidateRow> out = new ArrayList<>();
            for (String id : rowOrder) {
                if (ids.contains(id) && rowsByid.containsKey(id)) {
                    out.add(rowsByid.get(id));
                }
            }
            return out;
        }
    }

    @Test
    void pipelineMatchesFrozenPythonPerStage() {
        JsonNode golden = Fixtures.load("golden-retrieval.json");
        JsonNode legs = Fixtures.load("legs.json");
        JsonNode spec = Fixtures.load("fixture-spec.json");
        Map<String, JsonNode> nodes = Fixtures.nodesById(spec);

        Map<String, CandidateRow> rows = new LinkedHashMap<>();
        for (var e : Fixtures.nodesById(spec).entrySet()) {
            JsonNode n = e.getValue();
            rows.put(e.getKey(), new CandidateRow(
                    e.getKey(),
                    n.get("text").asText(),
                    n.get("metadata").get("source_path").asText(),
                    n.get("metadata").get("section").asText(),
                    n.get("metadata").get("title").asText(),
                    legs.path("metadata_text").path(e.getKey()).asText(),
                    legs.path("embedding_text").path(e.getKey()).asText()));
        }

        for (JsonNode c : golden.withArray("cases")) {
            if (!c.path("pinned").asBoolean(false)) {
                continue;
            }
            String caseId = c.get("case_id").asText();
            int limit = c.get("limit").asInt();
            int candidateLimit = Math.max(limit * 28, 160);
            int halfLimit = Math.max(candidateLimit / 2, 40);
            double[] vector = Fixtures.vectorOf(queryVectorSpec(spec, caseId));

            FrozenStore store = new FrozenStore(Fixtures.legCase(legs, caseId), rows,
                    jsonStrings(c.get("pg_row_order")));
            RetrievalPipeline.Result result =
                    new RetrievalPipeline(store, settings).retrieve(
                            c.get("query").asText(), c.get("rewritten").asText(), vector, limit);

            // 腿调用与限额：改写 FTS 用满额，其余三腿 half。
            assertThat(store.ftsLimits)
                    .as("%s FTS 腿限额", caseId)
                    .containsExactly(candidateLimit, halfLimit);

            // 行回读 = 融合序截断 candidate_limit（数据集小于限额时即融合序）。
            assertThat(store.lastLoadedIds)
                    .as("%s 融合候选序", caseId)
                    .containsExactlyElementsOf(jsonStrings(c.get("fused_candidate_ids")));

            // rank 表逐腿一致（0 = 未命中该腿）。
            Map<String, int[]> legRanks = result.trace().legRanks();
            JsonNode ranks = c.get("ranks");
            List<String> rankIds = new ArrayList<>();
            ranks.fieldNames().forEachRemaining(rankIds::add);
            assertThat(legRanks).as("%s rank 覆盖", caseId).containsOnlyKeys(rankIds);
            for (String id : rankIds) {
                JsonNode r = ranks.get(id);
                int[] actual = legRanks.get(id);
                assertThat(actual).as("%s/%s ranks", caseId, id).containsExactly(
                        r.get("fts_rewritten").isNumber() ? r.get("fts_rewritten").asInt() : 0,
                        r.get("fts_original").isNumber() ? r.get("fts_original").asInt() : 0,
                        r.get("path").isNumber() ? r.get("path").asInt() : 0,
                        r.get("semantic").isNumber() ? r.get("semantic").asInt() : 0);
                double rrfActual = CandidateRanks.rrfOf(actual[0], actual[1], actual[2], actual[3],
                        settings.rrfK());
                assertThat(rrfActual).as("%s/%s rrf", caseId, id)
                        .isCloseTo(r.get("rrf").asDouble(), TOL);
            }

            // 最终 Top-K：id 序与分数逐位一致。
            assertThat(result.nodes()).as("%s 最终片段数", caseId)
                    .hasSameSizeAs(c.withArray("selected"));
            JsonNode selected = c.get("selected");
            for (int i = 0; i < selected.size(); i++) {
                JsonNode exp = selected.get(i);
                assertThat(result.nodes().get(i).id())
                        .as("%s 选中#%d id", caseId, i)
                        .isEqualTo(exp.get("id").asText());
                assertThat(result.nodes().get(i).score())
                        .as("%s 选中#%d score", caseId, i)
                        .isCloseTo(exp.get("score").asDouble(), TOL);
            }

            // trace 计数。
            JsonNode legCounts = c.get("legs");
            assertThat(result.trace().ftsRewrittenCandidates())
                    .as("%s 改写FTS腿计数", caseId)
                    .isEqualTo(legCounts.get("fts_rewritten_ids").size());
            assertThat(result.trace().semanticCandidates())
                    .as("%s 语义腿计数", caseId)
                    .isEqualTo(legCounts.get("semantic_ids").size());
        }
    }

    static List<String> jsonStrings(JsonNode array) {
        if (array == null || array.isNull()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private JsonNode queryVectorSpec(JsonNode spec, String caseId) {
        for (JsonNode c : spec.withArray("cases")) {
            if (c.get("id").asText().equals(caseId)) {
                return c.get("query_vector");
            }
        }
        throw new IllegalArgumentException("case 不存在: " + caseId);
    }
}
