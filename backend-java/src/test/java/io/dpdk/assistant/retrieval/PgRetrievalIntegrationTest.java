package io.dpdk.assistant.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.dpdk.assistant.contracts.index.ConfigurationFingerprint;
import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import io.dpdk.assistant.contracts.model.EmbeddingInput;
import io.dpdk.assistant.contracts.model.EmbeddingPort;
import io.dpdk.assistant.contracts.model.Vector1024;
import io.dpdk.assistant.contracts.retrieval.ReleaseSnapshot;
import io.dpdk.assistant.contracts.retrieval.RetrievedChunk;
import io.dpdk.assistant.contracts.retrieval.SearchInput;
import io.dpdk.assistant.contracts.retrieval.SearchResult;
import io.dpdk.assistant.contracts.retrieval.SourceChunk;
import io.dpdk.assistant.platform.migration.MigrationController;
import io.dpdk.assistant.platform.pg.TestPg;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * J1-03 合成 PG 集成测试：在动态独立库上灌入冻结 fixture，验证
 * 1) 四条召回腿 SQL（release JOIN 隔离、ILIKE 转义、FTS tie-break、语义序）；
 * 2) 完整检索逐阶段对照冻结 Python golden（ids/score 容差 1e-12）；
 * 3) 无活动 release / 配置失配 / release 缺失 fail-closed；
 * 4) FTS regconfig 错配只降级 FTS 腿；
 * 5) 历史引用（li 与 legacy）可读且不受新 release 影响。
 * 需显式 DPDK_TEST_PG_* 环境，否则整类跳过。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class PgRetrievalIntegrationTest {

    private static final Offset<Double> TOL = Offset.offset(1e-12);

    private static DataSource ds;
    private static String database;
    private static JsonNode spec;
    private static JsonNode legs;
    private static JsonNode golden;
    private static PgReleaseReader reader;

    @BeforeAll
    static void setup() throws Exception {
        spec = Fixtures.load("fixture-spec.json");
        legs = Fixtures.load("legs.json");
        golden = Fixtures.load("golden-retrieval.json");

        database = TestPg.createDatabase("j103", TestPg.COMMENT);
        ds = TestPg.dataSource(database);
        new MigrationController().run(ds, TestPg.identity(database), true);
        seedFixture(ds, spec);

        reader = new PgReleaseReader(ds, RetrievalSettings.defaults(),
                new EmbeddingConfig("text-embedding-v4", "", 1024, "bounded-lines-v2"),
                new IndexConfigurationFingerprint(),
                new HeuristicQueryRewriter(),
                null);
    }

    @AfterAll
    static void teardown() throws SQLException {
        if (database != null) {
            TestPg.dropDatabase(database);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 种子                                                                 */
    /* ------------------------------------------------------------------ */

    static void seedFixture(DataSource ds, JsonNode spec) throws Exception {
        String table = "\"data_" + spec.get("table_configured").asText() + "\"";
        // 哨兵值替换为真实配置指纹（与 oracle/golden 的 expected-fingerprint 约定一致）。
        String fingerprint = golden.get("fingerprint").asText();
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            for (JsonNode node : spec.withArray("nodes")) {
                String sql = "INSERT INTO " + table + "(node_id, text, metadata_, embedding) VALUES (?,?,?::jsonb,?::vector)";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, node.get("node_id").asText());
                    ps.setString(2, node.get("text").asText());
                    ps.setString(3, Fixtures.mapper().writeValueAsString(
                            Fixtures.mapper().convertValue(node.get("metadata"), Object.class)));
                    ps.setString(4, VectorMath.pgVectorLiteral(Fixtures.vectorOf(node.get("nonzero"))));
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
                        + entry.getKey() + "', '"
                        + configuration.replace("'", "''")
                        + "', '2026-10-25T00:00:00+00:00', "
                        + entry.getValue().withArray("node_ids").size() + ")");
                for (JsonNode nid : entry.getValue().withArray("node_ids")) {
                    st.execute("INSERT INTO rag_release_nodes(release_id, node_id) VALUES ('"
                            + entry.getKey() + "', '" + nid.asText() + "')");
                }
            }
            st.execute("UPDATE rag_index_state SET release_id='"
                    + spec.get("active_release").asText() + "' WHERE name='active'");
            for (JsonNode doc : spec.at("/legacy/documents")) {
                st.execute("INSERT INTO knowledge_documents(id, source_path, source_type, title,"
                        + " content_hash, git_commit, indexed_at) VALUES ('"
                        + doc.get("id").asText() + "', '" + doc.get("source_path").asText() + "', '"
                        + doc.get("source_type").asText() + "', '" + doc.get("title").asText() + "', '"
                        + doc.get("content_hash").asText() + "', NULL, '"
                        + doc.get("indexed_at").asText() + "'::timestamptz)");
            }
            for (JsonNode chunk : spec.at("/legacy/chunks")) {
                st.execute("INSERT INTO knowledge_chunks(id, document_id, source_path, section, content, metadata_json)"
                        + " VALUES ('" + chunk.get("id").asText() + "', '" + chunk.get("document_id").asText()
                        + "', '" + chunk.get("source_path").asText() + "', '" + chunk.get("section").asText()
                        + "', '" + chunk.get("content").asText().replace("'", "''") + "', '"
                        + Fixtures.mapper().writeValueAsString(Fixtures.mapper()
                                .convertValue(chunk.get("metadata_json"), Object.class)) + "'::jsonb)");
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 1) 四条腿 SQL 逐 case 对照冻结结果                                     */
    /* ------------------------------------------------------------------ */

    @Test
    void sqlLegsMatchFrozenLegs() {
        for (JsonNode c : golden.withArray("cases")) {
            if (!c.path("pinned").asBoolean(false)) {
                continue;
            }
            String caseId = c.get("case_id").asText();
            JsonNode leg = Fixtures.legCase(legs, caseId);
            String release = c.get("release").asText();
            JsonNode queryVector = queryVectorSpec(caseId);
            // 无冻结向量 → 空数组（Python query_embedding=[] 时语义腿短路）。
            double[] vector = queryVector == null || queryVector.isNull()
                    ? new double[0] : Fixtures.vectorOf(queryVector);
            LiNodeRetrievalStore store = new LiNodeRetrievalStore(ds, RetrievalSettings.defaults(), release);

            List<String> ftsRewritten = store.fetchFtsCandidateIds(
                    leg.get("fts_query_rewritten").asText(), leg.get("leg_limits").get("fts_rewritten").asInt());
            List<String> ftsOriginal = store.fetchFtsCandidateIds(
                    leg.get("fts_query_original").asText(), leg.get("leg_limits").get("fts_original").asInt());
            List<String> path = store.fetchPathCandidateIds(
                    RetrievalPipelineGoldenTest.jsonStrings(leg.get("path_fragments")),
                    leg.get("leg_limits").get("path").asInt());
            List<String> semantic = store.fetchSemanticCandidateIds(
                    vector, leg.get("leg_limits").get("semantic").asInt());

            assertThat(ftsRewritten).as("%s FTS改写腿", caseId)
                    .containsExactlyElementsOf(RetrievalPipelineGoldenTest.jsonStrings(leg.get("fts_rewritten_ids")));
            assertThat(ftsOriginal).as("%s FTS原问腿", caseId)
                    .containsExactlyElementsOf(RetrievalPipelineGoldenTest.jsonStrings(leg.get("fts_original_ids")));
            assertThat(path).as("%s 路径腿", caseId)
                    .containsExactlyElementsOf(RetrievalPipelineGoldenTest.jsonStrings(leg.get("path_ids")));
            assertThat(semantic).as("%s 语义腿", caseId)
                    .containsExactlyElementsOf(RetrievalPipelineGoldenTest.jsonStrings(leg.get("semantic_ids")));
        }
    }

    /* ------------------------------------------------------------------ */
    /* 2) 完整链路（含内部改写）逐阶段对照 golden                             */
    /* ------------------------------------------------------------------ */

    @Test
    void fullSearchMatchesGolden() {
        for (JsonNode c : golden.withArray("cases")) {
            String caseId = c.get("case_id").asText();
            ReleaseSnapshot snapshot = reader.pin(c.get("release").asText());
            Vector1024 vector = vector1024Of(queryVectorSpec(caseId));
            SearchInput input = new SearchInput(c.get("query").asText(), vector);
            SearchResult result = reader.searchPinned(snapshot, input, c.get("limit").asInt());
            RetrievalTrace trace = (RetrievalTrace) result.trace();

            List<String> expectedIds = RetrievalPipelineGoldenTest.jsonStrings(c.get("expected_chunk_ids"));
            List<String> actualIds = result.chunks().stream().map(RetrievedChunk::chunkId).toList();
            assertThat(actualIds).as("%s 最终 Top-K 序", caseId).containsExactlyElementsOf(expectedIds);

            JsonNode selected = c.get("selected");
            if (selected != null) {
                for (int i = 0; i < selected.size(); i++) {
                    assertThat(result.chunks().get(i).score())
                            .as("%s 选中#%d score", caseId, i)
                            .isCloseTo(selected.get(i).get("score").asDouble(), TOL);
                }
            }

            if (c.path("pinned").asBoolean(false)) {
                assertThat(trace.blockedReason()).as("%s 不应 fail-closed", caseId).isNull();
                assertThat(trace.rewrittenQuery()).as("%s 改写串", caseId)
                        .isEqualTo(c.get("rewritten").asText());
                JsonNode legCounts = c.get("legs");
                assertThat(trace.ftsRewrittenCandidates())
                        .isEqualTo(legCounts.get("fts_rewritten_ids").size());
                assertThat(trace.ftsOriginalCandidates())
                        .isEqualTo(legCounts.get("fts_original_ids").size());
                assertThat(trace.pathCandidates()).isEqualTo(legCounts.get("path_ids").size());
                assertThat(trace.semanticCandidates()).isEqualTo(legCounts.get("semantic_ids").size());
                assertThat(trace.fusedCandidates()).isEqualTo(c.get("fused_candidate_ids").size());
                assertThat(trace.candidateLimit())
                        .isEqualTo(Math.max(c.get("limit").asInt() * 28, 160));
            } else {
                assertThat(result.chunks()).isEmpty();
                assertThat(trace.blockedReason()).isEqualTo("config_mismatch");
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 3) pin / 无活动 release / 失配 fail-closed                            */
    /* ------------------------------------------------------------------ */

    @Test
    void pinResolvesActiveReleaseAndFailsClosedWithoutOne() throws SQLException {
        ReleaseSnapshot pinned = reader.pin("");
        assertThat(pinned.releaseId()).isEqualTo("rel-alpha");
        SearchResult ok = reader.searchPinned(pinned,
                new SearchInput("session.h 流表结构定义", null), 4);
        assertThat(ok.chunks()).isNotEmpty();

        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("UPDATE rag_index_state SET release_id='' WHERE name='active'");
        }
        try {
            ReleaseSnapshot none = reader.pin("");
            assertThat(none.releaseId()).isEmpty();
            SearchResult empty = reader.searchPinned(none, new SearchInput("smtp state machine", null), 3);
            assertThat(empty.chunks()).isEmpty();
            assertThat(((RetrievalTrace) empty.trace()).blockedReason()).isEqualTo("no_active_release");
        } finally {
            try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
                st.execute("UPDATE rag_index_state SET release_id='rel-alpha' WHERE name='active'");
            }
        }
    }

    @Test
    void missingReleaseFailsClosed() {
        ReleaseSnapshot missing = reader.pin("rel-nonexistent");
        assertThat(missing.releaseId()).isEqualTo("rel-nonexistent");
        SearchResult empty = reader.searchPinned(missing, new SearchInput("session.h 流表结构定义", null), 4);
        assertThat(empty.chunks()).isEmpty();
        assertThat(((RetrievalTrace) empty.trace()).blockedReason()).isEqualTo("config_mismatch");
    }

    /* ------------------------------------------------------------------ */
    /* 4) FTS regconfig 错配只降级 FTS 腿                                    */
    /* ------------------------------------------------------------------ */

    @Test
    void ftsRegconfigMismatchDegradesOnlyFtsLeg() throws SQLException {
        LiNodeRetrievalStore.resetProcessStateForTest();
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE data_li_knowledge_v1 DROP COLUMN text_search_tsv");
            st.execute("ALTER TABLE data_li_knowledge_v1 ADD COLUMN text_search_tsv tsvector "
                    + "GENERATED ALWAYS AS (to_tsvector('english'::regconfig, (text)::text)) STORED");
        }
        try {
            LiNodeRetrievalStore store =
                    new LiNodeRetrievalStore(ds, RetrievalSettings.defaults(), "rel-alpha");
            assertThat(store.liveFtsRegconfig()).isEqualTo("english");
            assertThat(store.fetchFtsCandidateIds("\"session\"", 40)).isEmpty();
            assertThat(LiNodeRetrievalStore.warnedFor("\"data_li_knowledge_v1\"")).isTrue();
            // 其他腿不受影响（本 release 语义上无 smtp 成员，用 rel-alpha 内的路径/向量验证）。
            assertThat(store.fetchPathCandidateIds(List.of("session.h"), 40))
                    .containsExactly("li_a1");
            assertThat(store.fetchSemanticCandidateIds(Fixtures.vectorOf(queryVectorSpec("q1-chinese-filename-topic")), 40))
                    .isNotEmpty();
        } finally {
            try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE data_li_knowledge_v1 DROP COLUMN text_search_tsv");
                st.execute("ALTER TABLE data_li_knowledge_v1 ADD COLUMN text_search_tsv tsvector "
                        + "GENERATED ALWAYS AS (to_tsvector('simple'::regconfig, (text)::text)) STORED");
            }
            LiNodeRetrievalStore.resetProcessStateForTest();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 5) LIKE 通配符不扩大匹配；路径腿大小写不敏感                           */
    /* ------------------------------------------------------------------ */

    @Test
    void likeEscapingDoesNotExpandWildcards() {
        LiNodeRetrievalStore store =
                new LiNodeRetrievalStore(ds, RetrievalSettings.defaults(), "rel-alpha");
        // % 不作为通配符：a%b.c 不匹配 a_b.c / axb.c。
        assertThat(store.fetchPathCandidateIds(List.of("a%b.c"), 40)).isEmpty();
        // _ 转义：a_b.c 只匹配字面 a_b.c，不匹配 axb.c。
        assertThat(store.fetchPathCandidateIds(List.of("a_b.c"), 40)).containsExactly("li_a6");
        assertThat(store.fetchPathCandidateIds(List.of("axb.c"), 40)).containsExactly("li_a7");
        // ILIKE 大小写不敏感。
        assertThat(store.fetchPathCandidateIds(List.of("A_B.C"), 40)).containsExactly("li_a6");
        // 反斜杠转义。
        assertThat(store.fetchPathCandidateIds(List.of("a\\b"), 40)).isEmpty();
        // 空片段短路（无 SQL）。
        assertThat(store.fetchPathCandidateIds(List.of(), 40)).isEmpty();
    }

    /* ------------------------------------------------------------------ */
    /* 6) HNSW 事务级参数（开启时同连接 set_config，结果与精确检索一致）       */
    /* ------------------------------------------------------------------ */

    @Test
    void hnswEnabledRunsSameOrder() {
        RetrievalSettings hnsw = new RetrievalSettings(
                RetrievalSettings.defaults().tableName(),
                RetrievalSettings.defaults().ftsConfig(),
                RetrievalSettings.defaults().retrievalStrategy(),
                true,
                55,
                RetrievalSettings.defaults().wSemantic(),
                RetrievalSettings.defaults().wLexical(),
                RetrievalSettings.defaults().wIdent(),
                RetrievalSettings.defaults().wLexicalNoSemantic(),
                RetrievalSettings.defaults().wIdentNoSemantic(),
                RetrievalSettings.defaults().rrfK(),
                RetrievalSettings.defaults().rrfPriorScale(),
                RetrievalSettings.defaults().rrfPriorCap(),
                RetrievalSettings.defaults().mmrRedundancyPenalty(),
                RetrievalSettings.defaults().mmrPerFilePenalty(),
                RetrievalSettings.defaults().mmrPerFileCap(),
                RetrievalSettings.defaults().filenameBoostExact(),
                RetrievalSettings.defaults().filenameBoostPartial(),
                RetrievalSettings.defaults().topicBoostSpecific(),
                RetrievalSettings.defaults().topicBoostGeneric(),
                RetrievalSettings.defaults().topicBoostSpecificBare(),
                RetrievalSettings.defaults().topicBoostGenericBare(),
                RetrievalSettings.defaults().topicBoostSpecificInfix(),
                RetrievalSettings.defaults().topicBoostGenericInfix(),
                RetrievalSettings.defaults().identHeaderScore(),
                RetrievalSettings.defaults().identBodyScore(),
                RetrievalSettings.defaults().definitionBoostTypedef(),
                RetrievalSettings.defaults().definitionBoostFuncsig());
        LiNodeRetrievalStore store = new LiNodeRetrievalStore(ds, hnsw, "rel-alpha");
        double[] vector = Fixtures.vectorOf(queryVectorSpec("q1-chinese-filename-topic"));
        assertThat(store.fetchSemanticCandidateIds(vector, 84))
                .containsExactly("li_a1", "li_a2", "li_a3", "li_a4", "li_a5", "li_a6", "li_a7");
    }

    /* ------------------------------------------------------------------ */
    /* 7) 历史引用（li 与 legacy）与新 release 隔离                          */
    /* ------------------------------------------------------------------ */

    @Test
    void historicalReadsLiAndLegacyAndSurvivesNewRelease() throws SQLException {
        Optional<SourceChunk> li = reader.historical("llama_index", "li_a1");
        assertThat(li).isPresent();
        assertThat(li.get().chunkId()).isEqualTo("li_a1");
        assertThat(li.get().sourcePath()).isEqualTo("src/session.h");
        assertThat(li.get().section()).isEqualTo("session_entry");
        assertThat(li.get().content()).contains("typedef struct session_entry");

        // 非 li_ 前缀 → legacy knowledge_chunks（title 回退逻辑在富读取器中核对）。
        Optional<SourceChunk> legacy = reader.historical("legacy", "kc_legacy_1");
        assertThat(legacy).isPresent();
        assertThat(legacy.get().sourcePath()).isEqualTo("legacy/old_engine.c");
        assertThat(legacy.get().section()).isEqualTo("legacy_section");
        assertThat(legacy.get().content()).contains("旧引擎历史片段");
        PgReleaseReader.LegacyChunk rich = reader.legacyChunk("kc_legacy_1");
        assertThat(rich.title()).isEqualTo("旧引擎文档");

        assertThat(reader.historical("legacy", "kc_missing")).isEmpty();
        assertThat(reader.historical(null, "li_missing")).isEmpty();

        // 发布新 release 复用既有节点：li_a1 内容不受影响；新节点可读。
        try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
            st.execute("INSERT INTO rag_releases(id, configuration, created_at, node_count) "
                    + "VALUES ('rel-gamma', 'not-a-match', '2026-10-25T00:00:00+00:00', 2)");
            st.execute("INSERT INTO rag_release_nodes(release_id, node_id) VALUES ('rel-gamma','li_a1')");
            st.execute("INSERT INTO rag_release_nodes(release_id, node_id) VALUES ('rel-gamma','li_b1')");
        }
        try {
            Optional<SourceChunk> after = reader.historical("llama_index", "li_a1");
            assertThat(after).isEqualTo(li);
            assertThat(reader.historical("legacy", "kc_legacy_1")).isEqualTo(legacy);
        } finally {
            try (Connection conn = ds.getConnection(); Statement st = conn.createStatement()) {
                st.execute("DELETE FROM rag_release_nodes WHERE release_id='rel-gamma'");
                st.execute("DELETE FROM rag_releases WHERE id='rel-gamma'");
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 8) 行回读列、jsonb 规范文本与 release JOIN 隔离                        */
    /* ------------------------------------------------------------------ */

    @Test
    void rowLoadColumnsAndJsonbCanonicalText() {
        LiNodeRetrievalStore store =
                new LiNodeRetrievalStore(ds, RetrievalSettings.defaults(), "rel-alpha");
        List<CandidateRow> rows = store.fetchRowsByIds(
                List.of("li_a1", "li_ghost", "li_b1"));
        // rel-alpha 成员 li_ghost 无物理行 → 被跳过；li_b1 不是 rel-alpha 成员 → 被过滤。
        assertThat(rows).extracting(CandidateRow::id).containsExactly("li_a1");
        CandidateRow row = rows.get(0);
        assertThat(row.sourcePath()).isEqualTo("src/session.h");
        assertThat(row.section()).isEqualTo("session_entry");
        assertThat(row.title()).isEqualTo("session_entry");
        // jsonb 规范键序文本与冻结 legs.json 一致（长度后字节序）。
        assertThat(row.metadataJson()).isEqualTo(legs.path("metadata_text").path("li_a1").asText());
        assertThat(row.embeddingJson()).isEqualTo(legs.path("embedding_text").path("li_a1").asText());
    }

    /* ------------------------------------------------------------------ */
    /* 9) EmbeddingPort 注入路径（queryVector 为空时嵌入 original+rewritten） */
    /* ------------------------------------------------------------------ */

    @Test
    void embeddingPortReceivesCombinedQuery() {
        List<String> embeddedTexts = new ArrayList<>();
        float[] values = new float[1024];
        values[0] = 1.0f;
        EmbeddingPort port = input -> {
            embeddedTexts.add(input.input());
            return List.of(new Vector1024(values));
        };
        PgReleaseReader withEmbedding = new PgReleaseReader(ds, RetrievalSettings.defaults(),
                new EmbeddingConfig("text-embedding-v4", "", 1024, "bounded-lines-v2"),
                new IndexConfigurationFingerprint(),
                new HeuristicQueryRewriter(),
                port);
        // q1：改写不同于原文 → 嵌入 "original rewritten"。
        withEmbedding.searchPinned(new ReleaseSnapshot("rel-alpha"),
                new SearchInput("session.h 流表结构定义", null), 4);
        assertThat(embeddedTexts).hasSize(1);
        String goldenRewritten = goldenRewrittenOf("q1-chinese-filename-topic");
        assertThat(embeddedTexts.get(0))
                .isEqualTo("session.h 流表结构定义 " + goldenRewritten);
        // q9：短 ASCII 直通 → 嵌入原文本身。
        withEmbedding.searchPinned(new ReleaseSnapshot("rel-beta"),
                new SearchInput("smtp state machine", null), 3);
        assertThat(embeddedTexts.get(1)).isEqualTo("smtp state machine");
    }

    /* ------------------------------------------------------------------ */

    private String goldenRewrittenOf(String caseId) {
        for (JsonNode c : golden.withArray("cases")) {
            if (c.get("case_id").asText().equals(caseId)) {
                return c.get("rewritten").asText();
            }
        }
        throw new IllegalArgumentException("case 不存在: " + caseId);
    }

    private JsonNode queryVectorSpec(String caseId) {
        for (JsonNode c : spec.withArray("cases")) {
            if (c.get("id").asText().equals(caseId)) {
                return c.get("query_vector");
            }
        }
        throw new IllegalArgumentException("case 不存在: " + caseId);
    }

    private Vector1024 vector1024Of(JsonNode queryVector) {
        if (queryVector == null || queryVector.isNull()) {
            return null;
        }
        float[] values = new float[1024];
        for (JsonNode pair : queryVector) {
            values[pair.get(0).asInt()] = pair.get(1).floatValue();
        }
        return new Vector1024(values);
    }
}
