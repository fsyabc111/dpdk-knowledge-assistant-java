package io.dpdk.assistant.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import io.dpdk.assistant.contracts.index.IndexNode;
import io.dpdk.assistant.contracts.index.PreparedIndex;
import io.dpdk.assistant.contracts.model.Vector1024;
import io.dpdk.assistant.platform.migration.MigrationController;
import io.dpdk.assistant.platform.pg.TestPg;
import io.dpdk.assistant.platform.time.SystemClockAdapter;
import io.dpdk.assistant.platform.tx.SpringTransactionRunner;
import io.dpdk.assistant.retrieval.HeuristicQueryRewriter;
import io.dpdk.assistant.retrieval.IndexConfigurationFingerprint;
import io.dpdk.assistant.retrieval.PgReleaseReader;
import io.dpdk.assistant.retrieval.RetrievalSettings;
import io.dpdk.assistant.session.SessionService;
import io.dpdk.assistant.usage.UsageCore;
import io.dpdk.assistant.usage.UsageLedgerStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 显式身份守卫、单卡独立库、固定源与向量；无真实上游。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class PgIndexIntegrationTest {
    private static final EmbeddingConfig CONFIG = new EmbeddingConfig("合成-model", "http://127.0.0.1:1/v1", 1024, "bounded-lines-v2");
    private static final String FINGERPRINT = new IndexConfigurationFingerprint().pythonCompatibleConfiguration(CONFIG);
    private static final SystemClockAdapter CLOCK = new SystemClockAdapter(Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC));
    private String database;
    private DataSource ds;
    private SpringTransactionRunner transactions;
    private IndexService service;
    private AtomicInteger embeddings;
    private List<UsageCore.UsageEntry> flushed;
    @TempDir Path temp;

    @BeforeAll void setup() throws Exception {
        database = TestPg.createDatabase("j401", TestPg.COMMENT);
        ds = TestPg.dataSource(database);
        new MigrationController().run(ds, TestPg.identity(database), true);
        transactions = new SpringTransactionRunner(new TransactionTemplate(new DataSourceTransactionManager(ds)), ds);
    }
    @AfterAll void teardown() throws Exception { if (database != null) TestPg.dropDatabase(database); }
    @BeforeEach void reset() throws Exception {
        sql("TRUNCATE rag_release_nodes,rag_releases,data_li_knowledge_v1,rag_knowledge,upstream_usage_records,assistant_sessions CASCADE");
        sql("UPDATE rag_index_state SET release_id='' WHERE name='active'");
        embeddings = new AtomicInteger(); flushed = new ArrayList<>();
        service = newService((texts, usage) -> {
            embeddings.incrementAndGet();
            usage.recordLlm("ingest_embed", CONFIG.model(), Map.of("total_tokens", texts.size()), 1, UsageCore.KIND_EMBEDDING, 1);
            return texts.stream().map(t -> IndexServiceTest.vector()).toList();
        }, usage -> flushed.addAll(usage.entries()), 10);
    }
    private IndexService newService(IndexBatchEmbedding embedding, IndexUsageSink sink, int batch) {
        return new IndexService(ds, transactions, RetrievalSettings.defaults(), CONFIG, new IndexConfigurationFingerprint(), CLOCK, embedding, sink, batch);
    }
    private SourceSnapshot snapshot(String text) {
        return new SourceSnapshot(List.of(IndexServiceTest.node(null, text, "repo/a.md", 0)));
    }
    private String active() throws Exception { return scalar("SELECT release_id FROM rag_index_state WHERE name='active'"); }
    private int count(String table) throws Exception { return Integer.parseInt(scalar("SELECT count(*) FROM " + table)); }
    private String scalar(String query) throws Exception {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement(); ResultSet r = st.executeQuery(query)) { r.next(); return r.getString(1); }
    }
    private void sql(String query) throws Exception { try (Connection c = ds.getConnection(); Statement st = c.createStatement()) { st.execute(query); } }
    private SourceSettings sources(String content) throws Exception {
        Path root = Files.createTempDirectory(temp, "source-");
        Files.writeString(root.resolve("a.md"), content);
        Path yaml = root.resolve("sources.yml");
        Files.writeString(yaml, "sources:\n  - name: repo\n    root: ${DPDK_REPO_PATH}\n    include: [a.md]\n");
        return new SourceSettings(yaml, root, root, root);
    }

    @Test void publicIngestIdempotencyAndContentMetadataIncrement() throws Exception {
        SourceSettings source = sources("# A\n内容🙂");
        IngestResult first = service.ingest(source);
        assertThat(first.insertedOrUpdated()).isEqualTo(1);
        assertThat(service.ingest(source).releaseId()).isEqualTo(first.releaseId());
        assertThat(embeddings).hasValue(1); assertThat(count("rag_releases")).isEqualTo(1);
        String oldId = scalar("SELECT node_id FROM data_li_knowledge_v1");
        Files.writeString(source.dpdkRoot().resolve("a.md"), "# A\n更新内容");
        var second = service.ingest(source);
        assertThat(second.insertedOrUpdated()).isEqualTo(1); assertThat(second.deleted()).isEqualTo(1);
        assertThat(count("data_li_knowledge_v1")).isEqualTo(2);
        var reader = new PgReleaseReader(ds, RetrievalSettings.defaults(), CONFIG, new IndexConfigurationFingerprint(), new HeuristicQueryRewriter(), null);
        assertThat(reader.historical("llama_index", oldId).orElseThrow().content()).isEqualTo("# A\n内容🙂");
        assertThat(service.health(first.releaseId()).healthy()).isTrue();
        var scanned = service.scan(source);
        Map<String, Object> meta = new java.util.LinkedHashMap<>(scanned.nodes().getFirst().metadata());
        meta.put("title", "metadata update");
        var changedMetadata = service.ingest(service.plan(new SourceSnapshot(List.of(new IndexNode(null, scanned.nodes().getFirst().content(), meta, null)))));
        assertThat(changedMetadata.insertedOrUpdated()).isEqualTo(1);
        assertThat(embeddings).hasValue(3);
    }

    @Test void pureRemovalAndConfigurationDrift() throws Exception {
        var both = new SourceSnapshot(List.of(IndexServiceTest.node(null, "a", "a.md", 0), IndexServiceTest.node(null, "b", "b.md", 0)));
        var first = service.ingest(service.plan(both));
        var removal = service.ingest(service.plan(new SourceSnapshot(List.of(both.nodes().getFirst()))));
        assertThat(removal.deleted()).isEqualTo(1); assertThat(removal.insertedOrUpdated()).isZero();
        assertThat(embeddings).hasValue(1); assertThat(count("data_li_knowledge_v1")).isEqualTo(2);
        sql("UPDATE rag_releases SET configuration='drift' WHERE id='" + removal.releaseId() + "'");
        var reembedded = service.ingest(service.plan(new SourceSnapshot(List.of(both.nodes().getFirst()))));
        assertThat(reembedded.insertedOrUpdated()).isEqualTo(1); assertThat(embeddings).hasValue(2);
        assertThat(service.health(first.releaseId()).healthy()).isTrue();
    }

    @Test void publishedKnowledgeOnlyRevisionAndOrder() throws Exception {
        sql("INSERT INTO rag_knowledge(id,title,body,revision,status,updated_at) VALUES ('b','乙','正文',1,'published','now'),('a','甲','正文',2,'published','now'),('draft','草稿','不可见',1,'draft','now')");
        Path yaml = temp.resolve("empty-sources.yml"); Files.writeString(yaml, "sources: []\n");
        var settings = new SourceSettings(yaml, temp, temp, temp);
        var snapshot = service.scan(settings);
        assertThat(snapshot.nodes().stream().map(n -> n.metadata().get("knowledge_id"))).containsExactly("a", "b");
        assertThat(snapshot.nodes().getFirst().metadata()).containsEntry("revision", 2).containsEntry("source_key", "knowledge/published/a.md");
        service.ingest(settings);
        sql("UPDATE rag_knowledge SET revision=3 WHERE id='a'");
        assertThat(service.ingest(settings).insertedOrUpdated()).isEqualTo(1);
    }

    @Test void missingAndEmptySourceNeverReplaceActive() throws Exception {
        var first = service.ingest(service.plan(snapshot("original")));
        var settings = sources("");
        assertThatThrownBy(() -> service.ingest(settings)).hasMessageContaining("知识源为空");
        Files.delete(settings.dpdkRoot().resolve("a.md"));
        assertThatThrownBy(() -> service.ingest(settings)).hasMessageContaining("知识源读取失败");
        assertThat(active()).isEqualTo(first.releaseId()); assertThat(count("rag_releases")).isEqualTo(1);
    }

    @Test void sameExpectedActiveTwoConcurrentPublishersOnlyOneWins() throws Exception {
        service.ingest(service.plan(snapshot("original")));
        var left = service.plan(snapshot("left")); var right = service.plan(snapshot("right"));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger winners = new AtomicInteger(); AtomicInteger conflicts = new AtomicInteger();
        // 两个独立发布者使用相同旧 active；每个有独立的用量收口，无共享 ArrayList。
        var publisher = newService((texts, usage) -> texts.stream().map(t -> IndexServiceTest.vector()).toList(), usage -> {}, 10);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var futures = List.of(left, right).stream().map(plan -> executor.submit(() -> {
                start.await();
                try { publisher.ingest(plan); winners.incrementAndGet(); }
                catch (IllegalStateException failure) { conflicts.incrementAndGet(); }
                return null;
            })).toList();
            start.countDown(); for (var future : futures) future.get();
        }
        assertThat(winners).hasValue(1); assertThat(conflicts).hasValue(1);
        assertThat(count("rag_releases")).isEqualTo(2); assertThat(count("rag_release_nodes")).isEqualTo(2);
        assertThat(count("data_li_knowledge_v1")).isEqualTo(2); assertThat(service.health(active()).healthy()).isTrue();
    }

    @Test void releaseAndMembershipAndPhysicalFailureRollBackWholeTransaction() throws Exception {
        var original = service.ingest(service.plan(snapshot("original")));
        var planned = service.plan(snapshot("new")).prepared();
        IndexNode node = planned.changedNodes().getFirst();
        List<Float> vector = new ArrayList<>(java.util.Collections.nCopies(1024, 0f)); vector.set(0, 1f);
        IndexNode embedded = new IndexNode(node.id(), node.content(), node.metadata(), vector);
        var collision = new PreparedIndex(original.releaseId(), FINGERPRINT, planned.createdAt(), List.of(embedded), List.of(embedded));
        assertThatThrownBy(() -> service.publish(original.releaseId(), collision)).isInstanceOf(IllegalStateException.class);
        var duplicateMember = new PreparedIndex(planned.releaseId(), FINGERPRINT, planned.createdAt(), List.of(embedded, embedded), List.of(embedded));
        assertThatThrownBy(() -> service.publish(original.releaseId(), duplicateMember)).isInstanceOf(IllegalStateException.class);
        IndexNode invalid = new IndexNode(node.id(), null, node.metadata(), vector);
        var physicalFailure = new PreparedIndex(planned.releaseId(), FINGERPRINT, planned.createdAt(), List.of(invalid), List.of(invalid));
        assertThatThrownBy(() -> service.publish(original.releaseId(), physicalFailure)).isInstanceOf(IllegalStateException.class);
        assertThat(active()).isEqualTo(original.releaseId());
        assertThat(count("rag_releases")).isEqualTo(1); assertThat(count("rag_release_nodes")).isEqualTo(1); assertThat(count("data_li_knowledge_v1")).isEqualTo(1);
    }

    @Test void checkedRollbackRejectsUnknownConfigurationMembersAndPhysicalDamage() throws Exception {
        var first = service.ingest(service.plan(snapshot("first")));
        var second = service.ingest(service.plan(snapshot("second")));
        assertThat(service.health("missing")).isEqualTo(new IndexHealth(0, 0, 0, false));
        assertThatThrownBy(() -> service.rollbackChecked("missing")).hasMessage("版本不存在");
        sql("UPDATE rag_releases SET configuration='other' WHERE id='" + first.releaseId() + "'");
        assertThatThrownBy(() -> service.rollbackChecked(first.releaseId())).hasMessageContaining("配置不匹配");
        sql("UPDATE rag_releases SET configuration='" + FINGERPRINT.replace("'", "''") + "' WHERE id='" + first.releaseId() + "'");
        sql("UPDATE rag_releases SET node_count=2 WHERE id='" + first.releaseId() + "'");
        assertThatThrownBy(() -> service.rollbackChecked(first.releaseId())).hasMessageContaining("节点不完整");
        sql("UPDATE rag_releases SET node_count=1 WHERE id='" + first.releaseId() + "'");
        String id = scalar("SELECT node_id FROM rag_release_nodes WHERE release_id='" + first.releaseId() + "'");
        sql("DELETE FROM data_li_knowledge_v1 WHERE node_id='" + id + "'");
        assertThat(service.health(first.releaseId()).physical()).isZero();
        assertThatThrownBy(() -> service.rollbackChecked(first.releaseId())).hasMessageContaining("节点不完整");
        assertThat(active()).isEqualTo(second.releaseId());
    }

    @Test void legitimateRollbackOnlyChangesActiveAndSessionBindingDoesNotDrift() throws Exception {
        var first = service.ingest(service.plan(snapshot("first")));
        var sessions = new SessionService(transactions, CLOCK);
        var session = sessions.ensureOwned(Optional.empty(), "索引固定会话", "synthetic");
        assertThat(sessions.resolveRelease(session.value())).contains(first.releaseId());
        var second = service.ingest(service.plan(snapshot("second")));
        assertThat(sessions.resolveRelease(session.value())).contains(first.releaseId());
        int nodes = count("data_li_knowledge_v1");
        service.rollbackChecked(first.releaseId());
        assertThat(active()).isEqualTo(first.releaseId());
        assertThat(count("data_li_knowledge_v1")).isEqualTo(nodes); assertThat(count("rag_releases")).isEqualTo(2);
        service.rollbackChecked(second.releaseId());
        assertThat(sessions.resolveRelease(session.value())).contains(first.releaseId());
    }

    @Test void generatedFtsConfigurationMismatchFailsBeforeEmbedding() throws Exception {
        sql("ALTER TABLE data_li_knowledge_v1 DROP COLUMN text_search_tsv");
        sql("ALTER TABLE data_li_knowledge_v1 ADD COLUMN text_search_tsv tsvector GENERATED ALWAYS AS (to_tsvector('english'::regconfig,text)) STORED");
        try {
            assertThatThrownBy(() -> service.ingest(service.plan(snapshot("text"))))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("regconfig");
            assertThat(embeddings).hasValue(0); assertThat(active()).isEmpty();
        } finally {
            sql("ALTER TABLE data_li_knowledge_v1 DROP COLUMN text_search_tsv");
            sql("ALTER TABLE data_li_knowledge_v1 ADD COLUMN text_search_tsv tsvector GENERATED ALWAYS AS (to_tsvector('simple'::regconfig,text)) STORED");
        }
    }

    @Test void consumedUsagePersistsEvenWhenLaterBatchOrCasFailsAndLedgerFailureDegrades() throws Exception {
        UsageLedgerStore usageStore = new UsageLedgerStore(transactions);
        IndexUsageSink sink = usage -> usageStore.recordRows(UsageCore.entriesToRows(usage.entries(), Map.of(), "synthetic", null, null, null, CLOCK.nextSessionIso()));
        AtomicInteger calls = new AtomicInteger();
        var failureService = newService((texts, usage) -> {
            int call = calls.incrementAndGet();
            usage.recordLlm("ingest_embed", CONFIG.model(), call == 1 ? Map.of("total_tokens", 7) : null, 1, UsageCore.KIND_EMBEDDING, 1);
            if (call == 2) throw new IllegalStateException("synthetic failure");
            return texts.stream().map(t -> IndexServiceTest.vector()).toList();
        }, sink, 1);
        var two = new SourceSnapshot(List.of(IndexServiceTest.node(null, "a", "a", 0), IndexServiceTest.node(null, "b", "b", 0)));
        assertThatThrownBy(() -> failureService.ingest(failureService.plan(two))).isInstanceOf(IllegalStateException.class);
        assertThat(count("upstream_usage_records")).isEqualTo(2); assertThat(active()).isEmpty();
        assertThat(scalar("SELECT count(*) FROM upstream_usage_records WHERE total_tokens IS NULL AND usage_source='unknown'")).isEqualTo("1");
        var accounted = newService((texts, usage) -> {
            usage.recordLlm("ingest_embed", CONFIG.model(), Map.of("total_tokens", 9), 1, UsageCore.KIND_EMBEDDING, 1);
            return texts.stream().map(t -> IndexServiceTest.vector()).toList();
        }, sink, 10);
        var stale = accounted.plan(snapshot("stale"));
        service.ingest(service.plan(snapshot("winner")));
        assertThatThrownBy(() -> accounted.ingest(stale)).isInstanceOf(IllegalStateException.class);
        assertThat(count("upstream_usage_records")).isEqualTo(3);
        var brokenLedger = newService((texts, usage) -> List.of(IndexServiceTest.vector()), usage -> { throw new IllegalStateException("ledger unavailable"); }, 10);
        assertThat(brokenLedger.ingest(brokenLedger.plan(snapshot("still publishes"))).insertedOrUpdated()).isEqualTo(1);
        assertThat(service.health(active()).healthy()).isTrue();
    }
    @Test void completeSnapshotMatchesFrozenPythonContentMetadataConfigurationAndOrdering() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var fixture = json.readTree(getClass().getResourceAsStream("/migration/J4-01/snapshot-golden.json"));
        Path root = Files.createTempDirectory(temp, "snapshot-golden-");
        for (var it = fixture.get("files").fields(); it.hasNext();) {
            var entry = it.next(); Path file = root.resolve(entry.getKey());
            Files.createDirectories(file.getParent()); Files.writeString(file, entry.getValue().asText());
        }
        Path yaml = root.resolve("sources.yml"); Files.writeString(yaml, fixture.get("source_yaml").asText());
        try (Connection c = ds.getConnection(); PreparedStatement insert = c.prepareStatement(
                "INSERT INTO rag_knowledge(id,title,body,revision,status,updated_at) VALUES (?,?,?,?,'published','now')")) {
            for (var knowledge : fixture.get("published")) {
                insert.setString(1, knowledge.get("id").asText()); insert.setString(2, knowledge.get("title").asText());
                insert.setString(3, knowledge.get("body").asText()); insert.setInt(4, knowledge.get("revision").asInt()); insert.addBatch();
            }
            insert.executeBatch();
        }
        var snapshot = service.scan(new SourceSettings(yaml, root.resolve("repo"), root.resolve("assistant"), root));
        var actual = snapshot.nodes().stream().map(n -> Map.of("content", n.content(), "metadata", n.metadata())).toList();
        com.fasterxml.jackson.databind.JsonNode actualJson = json.valueToTree(actual);
        assertThat(actualJson).isEqualTo(fixture.get("expected"));
        assertThat(service.plan(snapshot).prepared().configuration()).isEqualTo(fixture.get("configuration").asText());
        assertThat(snapshot.nodes()).hasSize(10);
    }

    @Test void publicScanThenPlanDoesNotOverwritePublicationDuringScanWindow() throws Exception {
        var original = service.ingest(service.plan(snapshot("original")));
        SourceSnapshot scanned = service.scan(sources("# Captured\nsource"));
        var newer = service.ingest(service.plan(snapshot("newer")));
        var plan = service.plan(scanned);
        assertThat(plan.expectedActive()).isEqualTo(original.releaseId());
        assertThatThrownBy(() -> service.ingest(plan)).isInstanceOf(IllegalStateException.class);
        assertThat(active()).isEqualTo(newer.releaseId()); assertThat(count("rag_releases")).isEqualTo(2);
    }

    @Test void invalidEmbeddingsDoNotMutateActiveOrReleaseTables() throws Exception {
        var original = service.ingest(service.plan(snapshot("original")));
        for (String kind : List.of("zero", "nan", "infinity", "dimension", "count", "failure")) {
            var broken = newService((texts, usage) -> {
                float[] values = new float[1024];
                return switch (kind) {
                    case "zero" -> List.of(new Vector1024(values));
                    case "nan" -> { values[0] = Float.NaN; yield List.of(new Vector1024(values)); }
                    case "infinity" -> { values[0] = Float.POSITIVE_INFINITY; yield List.of(new Vector1024(values)); }
                    case "dimension" -> List.of(new Vector1024(new float[12]));
                    case "count" -> List.of();
                    default -> throw new IllegalStateException("synthetic failure");
                };
            }, usage -> {}, 10);
            assertThatThrownBy(() -> broken.ingest(broken.plan(snapshot("new")))).isInstanceOf(RuntimeException.class);
            assertThat(active()).isEqualTo(original.releaseId()); assertThat(count("rag_releases")).isEqualTo(1);
            assertThat(count("data_li_knowledge_v1")).isEqualTo(1);
        }
    }

}
