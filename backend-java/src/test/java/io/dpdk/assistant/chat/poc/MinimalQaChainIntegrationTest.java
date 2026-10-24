package io.dpdk.assistant.chat.poc;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.dpdk.assistant.contracts.model.ChatInput;
import io.dpdk.assistant.contracts.model.ChatModelPort;
import io.dpdk.assistant.contracts.model.ChatResult;
import io.dpdk.assistant.contracts.model.Vector1024;
import io.dpdk.assistant.platform.migration.MigrationController;
import io.dpdk.assistant.platform.pg.TestPg;
import io.dpdk.assistant.retrieval.HeuristicQueryRewriter;
import io.dpdk.assistant.retrieval.IndexConfigurationFingerprint;
import io.dpdk.assistant.retrieval.PgReleaseReader;
import io.dpdk.assistant.retrieval.RetrievalSettings;
import io.dpdk.assistant.retrieval.VectorMath;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * J1-04 最小问答全链路集成测试：真实 Java 检索（合成 PG + 冻结 fixture）→
 * 固定响应模型替身 → 裁决与引用绑定。四联样本（固定输入/检索中间值/模型响应/最终引用）
 * 全部来自 J1-03 冻结 golden（生成链可复现），不运行推导期望值。
 *
 * 用例：q2（limit6，6 片段有序引用+round4 分数）；q7（零证据→no_evidence）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class MinimalQaChainIntegrationTest {

    private static DataSource ds;
    private static String database;
    private static JsonNode spec;
    private static JsonNode golden;
    private static MinimalQaSlice slice;
    private static MinimalQaSlice noRefSlice;

    @BeforeAll
    static void setup() throws Exception {
        spec = io.dpdk.assistant.retrieval.Fixtures.load("fixture-spec.json");
        golden = io.dpdk.assistant.retrieval.Fixtures.load("golden-retrieval.json");

        database = TestPg.createDatabase("j104", TestPg.COMMENT);
        ds = TestPg.dataSource(database);
        new MigrationController().run(ds, TestPg.identity(database), true);
        seedRelease(ds);

        String fingerprint = golden.get("fingerprint").asText();
        io.dpdk.assistant.contracts.index.EmbeddingConfig embeddingConfig =
                new io.dpdk.assistant.contracts.index.EmbeddingConfig(
                        "text-embedding-v4", "", 1024, "bounded-lines-v2");
        PgReleaseReader reader = new PgReleaseReader(ds, RetrievalSettings.defaults(),
                embeddingConfig,
                new IndexConfigurationFingerprint(),
                new HeuristicQueryRewriter(),
                null);
        slice = new MinimalQaSlice(reader, fixedModel("[1][6] 依据见证据片段。"));
        noRefSlice = new MinimalQaSlice(reader, fixedModel("没有可引用的证据。"));
    }

    private static ChatModelPort fixedModel(String content) {
        return new ChatModelPort() {
            @Override
            public ChatResult complete(ChatInput input) {
                return new ChatResult(content, null);
            }

            @Override
            public java.util.concurrent.Flow.Publisher<io.dpdk.assistant.contracts.model.ModelDelta> stream(
                    ChatInput input) {
                throw new UnsupportedOperationException();
            }
        };
    }

    @AfterAll
    static void teardown() throws SQLException {
        if (database != null) {
            TestPg.dropDatabase(database);
        }
    }

    private static JsonNode caseById(String id) {
        for (JsonNode c : golden.withArray("cases")) {
            if (id.equals(c.get("case_id").asText())) {
                return c;
            }
        }
        throw new IllegalArgumentException("case 不存在: " + id);
    }

    /** 灌入 fixture 节点/releases/active 指针（与 J1-03 集成测试同源同法）。 */
    private static void seedRelease(DataSource ds) throws Exception {
        String table = "\"data_" + spec.get("table_configured").asText() + "\"";
        String fingerprint = golden.get("fingerprint").asText();
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            for (JsonNode node : spec.withArray("nodes")) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO " + table + "(node_id, text, metadata_, embedding) VALUES (?,?,?::jsonb,?::vector)")) {
                    ps.setString(1, node.get("node_id").asText());
                    ps.setString(2, node.get("text").asText());
                    ps.setString(3, io.dpdk.assistant.retrieval.Fixtures.mapper().writeValueAsString(
                            io.dpdk.assistant.retrieval.Fixtures.mapper().convertValue(
                                    node.get("metadata"), Object.class)));
                    ps.setString(4, VectorMath.pgVectorLiteral(
                            io.dpdk.assistant.retrieval.Fixtures.vectorOf(node.get("nonzero"))));
                    ps.executeUpdate();
                }
            }
            for (var it = spec.get("releases").fields(); it.hasNext(); ) {
                var entry = it.next();
                String configuration = entry.getValue().get("configuration").asText();
                if ("expected-fingerprint".equals(configuration)) {
                    configuration = fingerprint;
                }
                st.execute("INSERT INTO rag_releases(id, configuration, created_at, node_count) VALUES ('"
                        + entry.getKey() + "', '" + configuration.replace("'", "''")
                        + "', '2026-10-25T00:00:00+00:00', "
                        + entry.getValue().withArray("node_ids").size() + ")");
                for (JsonNode nid : entry.getValue().withArray("node_ids")) {
                    st.execute("INSERT INTO rag_release_nodes(release_id, node_id) VALUES ('"
                            + entry.getKey() + "', '" + nid.asText() + "')");
                }
            }
            st.execute("UPDATE rag_index_state SET release_id='"
                    + spec.get("active_release").asText() + "' WHERE name='active'");
        }
    }

    private static Vector1024 vectorOf(JsonNode c) {
        JsonNode literalNode = c.get("query_vector_literal");
        if (literalNode == null || literalNode.isNull()) {
            return null; // 零证据等用例无固定向量（语义腿自然空）
        }
        String literal = literalNode.asText(); // "[1.0,0.0,...]"
        String[] parts = literal.substring(1, literal.length() - 1).split(",");
        float[] v = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            v[i] = Float.parseFloat(parts[i].trim());
        }
        return new Vector1024(v);
    }

    @Test
    void q2AnsweredWithGoldenOrderedCitationsAndRound4Scores() {
        JsonNode c = caseById("q2-filename-ascii");
        PocAnswer out = slice.ask(c.get("release").asText(), c.get("query").asText(), vectorOf(c));

        assertThat(out.answerStatus()).isEqualTo("answered");
        List<String> expectedIds = new ArrayList<>();
        c.withArray("expected_chunk_ids").forEach(n -> expectedIds.add(n.asText()));
        assertThat(out.citations()).hasSize(expectedIds.size());
        for (int i = 0; i < expectedIds.size(); i++) {
            var citation = out.citations().get(i);
            assertThat(citation.chunkId()).as("rank %d", i + 1).isEqualTo(expectedIds.get(i));
            assertThat(citation.rank()).isEqualTo(i + 1);
        }
        // 分数=Python round4(golden selected.score)；golden 分数由冻结链生成。
        JsonNode selected = c.get("selected");
        for (int i = 0; i < expectedIds.size(); i++) {
            double goldenScore = selected.get(i).get("score").asDouble();
            assertThat(out.citations().get(i).score())
                    .as("score[%d]", i)
                    .isEqualTo(OrderedCitationBinder.pythonRound4(goldenScore));
        }
    }

    @Test
    void q7ZeroEvidenceYieldsNoEvidenceWhenModelCitesNothing() {
        JsonNode c = caseById("q7-zero-evidence");
        // 模型无引用时：precheck 通过（无 refs），postcheck 判 no_evidence。
        PocAnswer out = noRefSlice.ask(c.get("release").asText(), c.get("query").asText(), vectorOf(c));
        assertThat(out.answerStatus()).isEqualTo("unanswered");
        assertThat(out.failureCode()).isEqualTo("no_evidence");
        assertThat(out.citations()).isEmpty();
    }

    @Test
    void q7ZeroEvidenceWithBogusRefsIsInvalidCitation() {
        // Python 顺序语义：precheck 的 invalid_citation 先于 postcheck 的 no_evidence。
        JsonNode c = caseById("q7-zero-evidence");
        PocAnswer out = slice.ask(c.get("release").asText(), c.get("query").asText(), vectorOf(c));
        assertThat(out.failureCode()).isEqualTo("invalid_citation");
    }
}
