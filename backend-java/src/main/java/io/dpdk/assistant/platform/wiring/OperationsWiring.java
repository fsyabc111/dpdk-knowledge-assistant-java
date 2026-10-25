package io.dpdk.assistant.platform.wiring;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.admin.AdminUserRepository;
import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import io.dpdk.assistant.contracts.model.Vector1024;
import io.dpdk.assistant.index.IndexService;
import io.dpdk.assistant.index.SourceSettings;
import io.dpdk.assistant.knowledge.workflow.KnowledgeWorkflowService;
import io.dpdk.assistant.operations.OperationsMetricsPort;
import io.dpdk.assistant.operations.OperationsPort;
import io.dpdk.assistant.operations.adapter.OperationsBusinessAdapter;
import io.dpdk.assistant.operations.adapter.OperationsDomainMetricsAdapter;
import io.dpdk.assistant.retrieval.HeuristicQueryRewriter;
import io.dpdk.assistant.retrieval.IndexConfigurationFingerprint;
import io.dpdk.assistant.retrieval.PgReleaseReader;
import io.dpdk.assistant.retrieval.RetrievalSettings;
import io.dpdk.assistant.session.SessionService;
import io.dpdk.assistant.task.TaskStore;
import io.dpdk.assistant.usage.UsageLedgerStore;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 运维端口真实装配；固定向量与无模型改写器保证默认运维读取不外发。 */
@Configuration
public class OperationsWiring {
    @Bean
    Map<String, Vector1024> fixedRetrievalQueries(
            @Value("${dpdk.operations.fixed-retrieval-queries-json:${DPDK_JAVA_FIXED_RETRIEVAL_QUERIES_JSON:}}") String raw) {
        if (raw.isBlank()) { return Map.of(); }
        try {
            Map<String, List<Float>> parsed = new ObjectMapper()
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(raw, new TypeReference<Map<String, List<Float>>>() {});
            Map<String, Vector1024> result = new LinkedHashMap<>();
            for (var entry : parsed.entrySet()) {
                if (entry.getKey().isBlank() || entry.getValue().size() != 1024) { throw new IllegalArgumentException(); }
                float[] vector = new float[1024];
                for (int i = 0; i < vector.length; i++) {
                    Float value = entry.getValue().get(i);
                    if (value == null || !Float.isFinite(value)) { throw new IllegalArgumentException(); }
                    vector[i] = value;
                }
                result.put(entry.getKey(), new Vector1024(vector));
            }
            return Map.copyOf(result);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("固定检索向量必须为query到1024维有限数值数组的JSON对象");
        }
    }

    @Bean
    OperationsPort operationsPort(AdminUserRepository admin, SessionService sessions, TaskStore tasks,
            KnowledgeWorkflowService workflow, ObjectProvider<IndexService> index,
            DataSource ds, RetrievalSettings settings, ObjectProvider<EmbeddingConfig> embedding,
            Function<Path, SourceSettings> sources,
            @Qualifier("fixedRetrievalQueries") Map<String, Vector1024> vectors) {
        EmbeddingConfig config = embedding.getIfAvailable();
        PgReleaseReader localReader = config == null ? null : new PgReleaseReader(ds, settings, config,
                new IndexConfigurationFingerprint(), new HeuristicQueryRewriter(), null);
        return new OperationsBusinessAdapter(admin, sessions, tasks, workflow, index.getIfAvailable(),
                localReader, sources, vectors);
    }

    @Bean
    OperationsMetricsPort operationsMetricsPort(TaskStore tasks, UsageLedgerStore usage) {
        return new OperationsDomainMetricsAdapter(tasks, usage);
    }
}
