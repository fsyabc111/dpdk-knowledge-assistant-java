package io.dpdk.assistant.index;

import io.dpdk.assistant.contracts.index.ConfigurationFingerprint;
import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import io.dpdk.assistant.contracts.index.IndexNode;
import io.dpdk.assistant.contracts.index.PreparedIndex;
import io.dpdk.assistant.contracts.index.PublishPort;
import io.dpdk.assistant.contracts.index.PublishReceipt;
import io.dpdk.assistant.contracts.model.Vector1024;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.platform.time.SystemClockAdapter;
import io.dpdk.assistant.retrieval.RetrievalSettings;
import io.dpdk.assistant.usage.UsageCore;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 索引摄取唯一业务入口：完整扫描、逐项计划、事务外嵌入、CAS 发布与受校验回滚。 */
public final class IndexService implements PublishPort {
    private static final Logger LOG = LoggerFactory.getLogger(IndexService.class);
    private final JdbcIndexStore store;
    private final RetrievalSettings settings;
    private final EmbeddingConfig config;
    private final ConfigurationFingerprint fingerprint;
    private final ClockPort clock;
    private final IndexBatchEmbedding embedding;
    private final IndexUsageSink usageSink;
    private final int batchSize;
    private final Supplier<String> ids;
    private record Position(Object source, Object ordinal) {}

    public IndexService(DataSource dataSource, TransactionRunner transactions, RetrievalSettings settings,
                        EmbeddingConfig config, ConfigurationFingerprint fingerprint, ClockPort clock,
                        IndexBatchEmbedding embedding, IndexUsageSink usageSink, int embeddingBatchSize) {
        this(dataSource, transactions, settings, config, fingerprint, clock, embedding, usageSink,
                embeddingBatchSize, () -> UUID.randomUUID().toString().replace("-", ""));
    }

    IndexService(DataSource dataSource, TransactionRunner transactions, RetrievalSettings settings,
                 EmbeddingConfig config, ConfigurationFingerprint fingerprint, ClockPort clock,
                 IndexBatchEmbedding embedding, IndexUsageSink usageSink, int batchSize, Supplier<String> ids) {
        if (config.dimensions() != 1024 || !"bounded-lines-v2".equals(config.chunker())) {
            throw new IllegalArgumentException("索引维度/切块器必须使用冻结配置");
        }
        this.store = new JdbcIndexStore(dataSource, transactions, settings);
        this.settings = settings;
        this.config = config;
        this.fingerprint = fingerprint;
        this.clock = clock;
        this.embedding = Objects.requireNonNull(embedding, "embedding");
        this.usageSink = Objects.requireNonNull(usageSink, "usageSink");
        this.batchSize = Math.max(1, Math.min(batchSize, 10));
        this.ids = ids;
    }

    public SourceSnapshot scan(SourceSettings sources) {
        String expectedActive = store.active();
        List<IndexNode> desired = new ArrayList<>();
        for (var document : new YamlKnowledgeSource().scan(sources)) {
            desired.add(new IndexNode(null, document.content(), document.metadata(), null));
        }
        BoundedLinesChunker chunker = new BoundedLinesChunker();
        for (var knowledge : store.published()) {
            String path = "knowledge/published/" + knowledge.id() + ".md";
            var chunks = chunker.chunk("# " + knowledge.title() + "\n\n" + knowledge.body(), path);
            for (int ordinal = 0; ordinal < chunks.size(); ordinal++) {
                var chunk = chunks.get(ordinal);
                Map<String, Object> meta = new LinkedHashMap<>(chunk.metadata());
                meta.put("source_key", path); meta.put("source_path", path);
                meta.put("section", chunk.section()); meta.put("title", knowledge.title());
                meta.put("ordinal", ordinal); meta.put("knowledge_id", knowledge.id());
                meta.put("revision", knowledge.revision());
                desired.add(new IndexNode(null, chunk.content(), meta, null));
            }
        }
        requireSource(desired);
        return new SourceSnapshot(expectedActive, desired);
    }

    public IndexPlan plan(SourceSnapshot snapshot) {
        return planAgainst(snapshot, snapshot.expectedActive() == null ? store.active() : snapshot.expectedActive());
    }

    private IndexPlan planAgainst(SourceSnapshot snapshot, String previous) {
        requireSource(snapshot.nodes());
        var existing = store.existing(previous);
        String configuration = fingerprint.pythonCompatibleConfiguration(config);
        List<IndexNode> desired = new ArrayList<>();
        List<IndexNode> changed = new ArrayList<>();
        Map<Position, IndexNode> byPosition = new HashMap<>();
        for (IndexNode old : existing.nodes()) byPosition.put(position(old), old);
        for (IndexNode node : snapshot.nodes()) {
            IndexNode old = byPosition.get(position(node));
            boolean reuse = existing.configuration().equals(configuration) && old != null
                    && Objects.equals(old.content(), node.content()) && Objects.equals(old.metadata(), node.metadata());
            IndexNode planned = new IndexNode(reuse ? old.id() : "li_" + ids.get(), node.content(), node.metadata(), null);
            desired.add(planned);
            if (!reuse) changed.add(planned);
        }
        var desiredIds = new HashSet<>(desired.stream().map(IndexNode::id).toList());
        int removed = (int) existing.nodes().stream().filter(n -> !desiredIds.contains(n.id())).count();
        return new IndexPlan(previous, new PreparedIndex(ids.get(), configuration,
                SystemClockAdapter.pythonIso(clock.nowUtc().truncatedTo(ChronoUnit.MICROS)), desired, changed), removed);
    }

    public IngestResult ingest(SourceSettings sources) {
        store.checkFts();
        // 必须先取 active 再扫描：扫描期间发生其他发布将由 CAS 拒绝，不能覆盖该发布。
        return ingest(plan(scan(sources)));
    }

    public IngestResult ingest(IndexPlan plan) {
        store.checkFts();
        PreparedIndex prepared = plan.prepared();
        requireSource(prepared.desiredNodes());
        if (prepared.changedNodes().isEmpty() && plan.removed() == 0) return result(plan.expectedActive(), plan);
        List<IndexNode> embedded = new ArrayList<>();
        UsageCore.UsageRecorder usage = new UsageCore.UsageRecorder();
        try {
            for (int start = 0; start < prepared.changedNodes().size(); start += batchSize) {
                List<IndexNode> batch = prepared.changedNodes().subList(start, Math.min(start + batchSize, prepared.changedNodes().size()));
                List<Vector1024> vectors = embedding.embed(batch.stream().map(IndexNode::content).toList(), usage);
                if (vectors == null || vectors.size() != batch.size()) throw new IllegalArgumentException("Embedding 数量与片段不匹配");
                for (int i = 0; i < batch.size(); i++) {
                    Vector1024 vector = vectors.get(i);
                    validate(vector == null ? null : vector.values());
                    List<Float> values = new ArrayList<>(1024);
                    for (float value : vector.values()) values.add(value);
                    IndexNode node = batch.get(i);
                    embedded.add(new IndexNode(node.id(), node.content(), node.metadata(), values));
                }
            }
        } finally {
            try { usageSink.flush(usage); }
            catch (RuntimeException failure) {
                LOG.warn("usage_record_failed: ingest_embed rows={} error_type={}", usage.entries().size(), failure.getClass().getSimpleName());
            }
        }
        // release id 在外部调用前仅为本地计划值；未发布计划不写入任何 release 行。
        publish(plan.expectedActive(), new PreparedIndex(prepared.releaseId(), prepared.configuration(),
                prepared.createdAt(), prepared.desiredNodes(), embedded));
        return result(prepared.releaseId(), plan);
    }

    @Override
    public PublishReceipt publish(String expectedActive, PreparedIndex index) {
        requireSource(index.desiredNodes());
        if (!fingerprint.pythonCompatibleConfiguration(config).equals(index.configuration())) {
            throw new IllegalArgumentException("待发布索引配置不匹配");
        }
        // 公共发布端口也校验 changed vectors，防止运维入口绕过 ingest。
        for (IndexNode node : index.changedNodes()) validate(node.embedding());
        store.publish(expectedActive, index);
        return new PublishReceipt(index.releaseId());
    }

    /** 活动版本读取供运维与健康接线复用，不另复制 SQL。 */
    public String activeRelease() {
        return store.active();
    }

    public IndexHealth health(String releaseId) {
        return store.health(releaseId, fingerprint.pythonCompatibleConfiguration(config));
    }

    @Override
    public void rollbackChecked(String releaseId) {
        IndexHealth health = health(releaseId);
        if (health.declared() < 1) throw new IllegalArgumentException("版本不存在");
        if (!health.configMatch()) throw new IllegalArgumentException("目标版本与当前 embedding 配置不匹配，拒绝回滚");
        if (health.members() != health.declared() || health.physical() != health.members()) {
            throw new IllegalArgumentException("目标版本节点不完整（declared=" + health.declared() + " members="
                    + health.members() + " physical=" + health.physical() + "），拒绝回滚");
        }
        store.rollback(releaseId);
    }

    private IngestResult result(String release, IndexPlan plan) {
        int total = plan.prepared().desiredNodes().size();
        int changed = plan.prepared().changedNodes().size();
        return new IngestResult(release, total, total - changed, changed, plan.removed(), 0, settings.tableName());
    }
    private static Position position(IndexNode node) { return new Position(node.metadata().get("source_key"), node.metadata().get("ordinal")); }
    private static void requireSource(List<IndexNode> nodes) {
        if (nodes.isEmpty()) throw new IllegalArgumentException("知识源为空，拒绝覆盖活动索引");
    }
    private static void validate(List<Float> values) {
        if (values == null) throw new IllegalArgumentException("Embedding 必须是非零的 1024 维有限数值");
        float[] array = new float[values.size()];
        for (int i = 0; i < values.size(); i++) array[i] = values.get(i) == null ? Float.NaN : values.get(i);
        validate(array);
    }
    private static void validate(float[] values) {
        boolean nonzero = false;
        if (values == null || values.length != 1024) throw new IllegalArgumentException("Embedding 必须是非零的 1024 维有限数值");
        for (float value : values) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Embedding 必须是非零的 1024 维有限数值");
            nonzero |= value != 0;
        }
        if (!nonzero) throw new IllegalArgumentException("Embedding 必须是非零的 1024 维有限数值");
    }
}
