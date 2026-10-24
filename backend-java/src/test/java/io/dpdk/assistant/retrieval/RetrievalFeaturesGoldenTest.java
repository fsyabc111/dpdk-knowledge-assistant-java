package io.dpdk.assistant.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.Test;

/**
 * 五组重排信号逐值对照冻结 Python（golden.breakdown 由真实特征函数生成；
 * metadata 采用 legs.json 冻结的 PG jsonb 规范文本，保证 keyword_score
 * 的 haystack 与生产同源）。容差 1e-12（运行前冻结）。
 */
class RetrievalFeaturesGoldenTest {

    private static final Offset<Double> TOL = Offset.offset(1e-12);

    private final RetrievalSettings settings = RetrievalSettings.defaults();

    @Test
    void signalsMatchFrozenPythonBreakdown() {
        JsonNode golden = Fixtures.load("golden-retrieval.json");
        JsonNode legs = Fixtures.load("legs.json");
        JsonNode spec = Fixtures.load("fixture-spec.json");
        Map<String, JsonNode> nodes = Fixtures.nodesById(spec);

        for (JsonNode c : golden.withArray("cases")) {
            if (!c.path("pinned").asBoolean(false)) {
                continue;
            }
            String caseId = c.get("case_id").asText();
            String query = c.get("query").asText();
            String rewritten = c.get("rewritten").asText();
            double[] queryVector = Fixtures.vectorOf(queryVectorSpec(spec, caseId));

            for (JsonNode b : c.withArray("breakdown")) {
                String id = b.get("id").asText();
                JsonNode node = nodes.get(id);
                String content = node.get("text").asText();
                JsonNode meta = node.get("metadata");
                String sourcePath = meta.get("source_path").asText();
                String section = meta.get("section").asText();
                Map<String, Object> metadata = PythonJson.parseObject(
                        legs.path("metadata_text").path(id).asText());

                double lexical = Math.max(
                        RetrievalFeatures.keywordScore(rewritten, content, metadata),
                        RetrievalFeatures.keywordScore(query, content, metadata));
                assertThat(lexical).as("%s/%s lexical", caseId, id)
                        .isCloseTo(b.get("lexical").asDouble(), TOL);

                double ident = Math.max(
                        RetrievalFeatures.identifierScore(query, sourcePath, section, content, settings),
                        RetrievalFeatures.identifierScore(rewritten, sourcePath, section, content, settings));
                assertThat(ident).as("%s/%s ident", caseId, id)
                        .isCloseTo(b.get("ident").asDouble(), TOL);

                double fileBoost = Math.max(
                        RetrievalFeatures.filenameBoost(query, sourcePath, settings),
                        RetrievalFeatures.filenameBoost(rewritten, sourcePath, settings));
                assertThat(fileBoost).as("%s/%s file_boost", caseId, id)
                        .isCloseTo(b.get("file_boost").asDouble(), TOL);

                double topic = Math.max(
                        RetrievalFeatures.topicBoost(query, sourcePath, settings),
                        RetrievalFeatures.topicBoost(rewritten, sourcePath, settings));
                assertThat(topic).as("%s/%s topic", caseId, id)
                        .isCloseTo(b.get("topic").asDouble(), TOL);

                assertThat(RetrievalFeatures.definitionBoost(content, settings))
                        .as("%s/%s structural", caseId, id)
                        .isCloseTo(b.get("structural").asDouble(), TOL);

                if (queryVector.length > 0) {
                    double[] embedding = Fixtures.vectorOf(node.get("nonzero"));
                    assertThat(VectorMath.cosineSimilarity(queryVector, embedding))
                            .as("%s/%s semantic", caseId, id)
                            .isCloseTo(b.get("semantic").asDouble(), TOL);
                }

                double rrf = b.get("rrf").asDouble();
                double prior = Math.min(rrf * settings.rrfPriorScale(), settings.rrfPriorCap());
                assertThat(prior).as("%s/%s source_prior", caseId, id)
                        .isCloseTo(b.get("source_prior").asDouble(), TOL);

                // 分支判定与总分（semantic==0 → 无语义分支）。
                double pathBoost = Math.max(fileBoost, topic);
                double score;
                if (b.get("semantic").asDouble() != 0.0) {
                    score = b.get("semantic").asDouble() * settings.wSemantic()
                            + lexical * settings.wLexical() + ident * settings.wIdent()
                            + prior + pathBoost + b.get("structural").asDouble();
                } else {
                    score = lexical * settings.wLexicalNoSemantic()
                            + ident * settings.wIdentNoSemantic()
                            + prior + pathBoost + b.get("structural").asDouble();
                }
                assertThat(score).as("%s/%s score", caseId, id)
                        .isCloseTo(b.get("score").asDouble(), TOL);
            }
        }
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
