package io.dpdk.assistant.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import io.dpdk.assistant.contracts.knowledge.PublishedKnowledgeReader;
import io.dpdk.assistant.contracts.index.IndexNode;
import io.dpdk.assistant.contracts.model.Vector1024;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.platform.time.SystemClockAdapter;
import io.dpdk.assistant.retrieval.IndexConfigurationFingerprint;
import io.dpdk.assistant.retrieval.RetrievalSettings;
import io.dpdk.assistant.usage.UsageCore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

class IndexServiceTest {
    private static final EmbeddingConfig CONFIG = new EmbeddingConfig("合成-model", "http://127.0.0.1:1/v1", 1024, "bounded-lines-v2");
    private static final String FINGERPRINT = new IndexConfigurationFingerprint().pythonCompatibleConfiguration(CONFIG);
    private TransactionRunner runner;
    private List<UsageCore.UsageEntry> flushed = new ArrayList<>();

    static IndexNode node(String id, String text, String source, int ordinal) {
        return new IndexNode(id, text, Map.of("source_key", source, "source_path", source, "ordinal", ordinal), null);
    }
    static Vector1024 vector() { float[] values = new float[1024]; values[0] = 1; return new Vector1024(values); }

    private IndexService service(List<IndexNode> existing, String oldConfig, IndexBatchEmbedding embedding, int batch) throws Exception {
        return service(existing, oldConfig, embedding, batch, () -> List.of());
    }

    private IndexService service(List<IndexNode> existing, String oldConfig, IndexBatchEmbedding embedding,
                                 int batch, PublishedKnowledgeReader knowledgeReader) throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection c = mock(Connection.class);
        when(ds.getConnection()).thenReturn(c);
        when(c.prepareStatement(anyString())).thenAnswer(call -> {
            String sql = call.getArgument(0);
            List<List<String>> rows = new ArrayList<>();
            if (sql.startsWith("SELECT release_id")) rows.add(List.of("old"));
            if (sql.startsWith("SELECT configuration") && oldConfig != null) rows.add(List.of(oldConfig));
            if (sql.startsWith("SELECT c.node_id")) for (IndexNode n : existing) rows.add(List.of(n.id(), n.content(), new ObjectMapper().writeValueAsString(n.metadata())));
            PreparedStatement st = mock(PreparedStatement.class);
            ResultSet rs = mock(ResultSet.class);
            AtomicInteger position = new AtomicInteger(-1);
            when(rs.next()).thenAnswer(a -> position.incrementAndGet() < rows.size());
            when(rs.getString(anyInt())).thenAnswer(a -> rows.get(position.get()).get((int) a.getArgument(0) - 1));
            when(st.executeQuery()).thenReturn(rs);
            return st;
        });
        runner = mock(TransactionRunner.class);
        AtomicInteger sequence = new AtomicInteger();
        return new IndexService(ds, runner, RetrievalSettings.defaults(), CONFIG, new IndexConfigurationFingerprint(),
                new SystemClockAdapter(Clock.fixed(Instant.parse("2026-10-25T00:00:00Z"), ZoneOffset.UTC)), embedding,
                usage -> flushed.addAll(usage.entries()), batch, knowledgeReader, () -> "%032x".formatted(sequence.incrementAndGet()));
    }

    @Test void identicalMetadataReusesIdsAndSkipsPublishEmbeddingAndUsage() throws Exception {
        IndexBatchEmbedding embed = mock(IndexBatchEmbedding.class);
        var old = List.of(node("li_old", "内容", "repo/a.md", 0));
        var service = service(old, FINGERPRINT, embed, 10);
        var plan = service.plan(new SourceSnapshot(old));
        assertThat(plan.prepared().changedNodes()).isEmpty();
        assertThat(plan.prepared().desiredNodes().getFirst().id()).isEqualTo("li_old");
        assertThat(service.ingest(plan).asMap()).containsEntry("release_id", "old").containsEntry("unchanged", 1);
        verifyNoInteractions(embed, runner);
        assertThat(flushed).isEmpty();
    }

    @Test void bodyMetadataConfigurationAndPureDeletionPlans() throws Exception {
        var old = List.of(node("li_a", "a", "repo/a.md", 0), node("li_b", "b", "repo/b.md", 0));
        var service = service(old, FINGERPRINT, (texts, usage) -> List.of(vector()), 10);
        var body = service.plan(new SourceSnapshot(List.of(old.getFirst(), node(null, "更新", "repo/b.md", 0))));
        assertThat(body.prepared().changedNodes()).hasSize(1);
        assertThat(body.prepared().desiredNodes().getFirst().id()).isEqualTo("li_a");
        // removed 对照 Python：更新换 id 也会使原 id 不再是新 release 成员。
        assertThat(body.removed()).isEqualTo(1);
        var metadata = service.plan(new SourceSnapshot(List.of(node(null, "a", "renamed", 0), old.get(1))));
        assertThat(metadata.prepared().changedNodes()).hasSize(1);
        var removal = service.plan(new SourceSnapshot(List.of(old.getFirst())));
        assertThat(removal.prepared().changedNodes()).isEmpty(); assertThat(removal.removed()).isEqualTo(1);
        var drift = service(old, "different", (texts, usage) -> List.of(vector()), 10)
                .plan(new SourceSnapshot(old));
        assertThat(drift.prepared().changedNodes()).hasSize(2);
        assertThat(drift.prepared().desiredNodes()).allMatch(n -> n.id().matches("li_[0-9a-f]{32}"));
    }

    @TestFactory Stream<DynamicTest> invalidEmbeddingNeverPublishesAndFlushesUsage() {
        return Stream.of("empty", "null", "zero", "nan", "infinity", "dimension", "throw").map(kind -> DynamicTest.dynamicTest(kind, () -> {
            flushed = new ArrayList<>();
            var service = service(List.of(), "", (texts, usage) -> {
                usage.recordLlm("ingest_embed", CONFIG.model(), null, 1, UsageCore.KIND_EMBEDDING, 1);
                float[] values = new float[1024]; values[0] = 1;
                return switch (kind) {
                    case "empty" -> List.of();
                    case "null" -> null;
                    case "zero" -> List.of(new Vector1024(new float[1024]));
                    case "nan" -> { values[0] = Float.NaN; yield List.of(new Vector1024(values)); }
                    case "infinity" -> { values[0] = Float.POSITIVE_INFINITY; yield List.of(new Vector1024(values)); }
                    case "dimension" -> List.of(new Vector1024(new float[3]));
                    default -> throw new IllegalStateException("synthetic upstream failure");
                };
            }, 10);
            var plan = service.plan(new SourceSnapshot(List.of(node(null, "a", "a.md", 0))));
            assertThatThrownBy(() -> service.ingest(plan)).isInstanceOf(RuntimeException.class);
            verifyNoInteractions(runner);
            assertThat(flushed).hasSize(1);
            assertThat(flushed.getFirst().usageSource()).isEqualTo("unknown");
            assertThat(flushed.getFirst().totalTokens()).isNull();
        }));
    }

    @Test void previousSuccessfulBatchSurvivesLaterFailureAndBatchClamps() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var service = service(List.of(), "", (texts, usage) -> {
            int call = calls.incrementAndGet();
            assertThat(texts).hasSize(call == 1 ? 10 : 1);
            usage.recordLlm("ingest_embed", CONFIG.model(), call == 1 ? Map.of("total_tokens", 10) : null,
                    1, UsageCore.KIND_EMBEDDING, 1);
            if (call == 2) throw new IllegalStateException("later batch fails");
            return texts.stream().map(t -> vector()).toList();
        }, 100);
        var nodes = java.util.stream.IntStream.range(0, 11).mapToObj(i -> node(null, "a" + i, "a.md", i)).toList();
        assertThatThrownBy(() -> service.ingest(service.plan(new SourceSnapshot(nodes)))).isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(2); assertThat(flushed).hasSize(2);
        assertThat(flushed.getFirst().totalTokens()).isEqualTo(10);
        verifyNoInteractions(runner);
    }

    @Test void emptySourceRefusedBeforeDatabase() throws Exception {
        var service = service(List.of(), "", (texts, usage) -> List.of(), 10);
        assertThatThrownBy(() -> service.plan(new SourceSnapshot(List.of()))).hasMessageContaining("知识源为空");
        verifyNoInteractions(runner);
    }
    @Test void publishedKnowledgeReaderIsRequiredAndConsumedExplicitly(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        assertThatThrownBy(() -> service(List.of(), "", (texts, usage) -> List.of(), 10, null))
                .isInstanceOf(NullPointerException.class).hasMessage("knowledgeReader");
        PublishedKnowledgeReader reader = mock(PublishedKnowledgeReader.class);
        when(reader.published()).thenReturn(List.of(
                new PublishedKnowledgeReader.Entry("a", "甲", "正文甲", 2),
                new PublishedKnowledgeReader.Entry("b", "乙", "正文乙", 1)));
        var service = service(List.of(), "", (texts, usage) -> List.of(), 10, reader);
        var yaml = directory.resolve("sources.yml"); java.nio.file.Files.writeString(yaml, "sources: []\n");
        var snapshot = service.scan(new SourceSettings(yaml, directory, directory, directory));
        assertThat(snapshot.nodes().stream().map(n -> n.metadata().get("knowledge_id"))).containsExactly("a", "b");
        assertThat(snapshot.nodes().getFirst().metadata()).containsEntry("revision", 2);
        verify(reader, times(1)).published();
    }

}
