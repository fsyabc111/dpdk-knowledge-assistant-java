package io.dpdk.assistant.platform.wiring;

import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.index.IndexService;
import io.dpdk.assistant.index.SourceSettings;
import io.dpdk.assistant.model.ModelUsageLedger;
import io.dpdk.assistant.model.OpenAiCompatibleEmbeddingClient;
import io.dpdk.assistant.platform.config.ModelEmbeddingProperties;
import io.dpdk.assistant.platform.config.UsageRecordingPolicy;
import io.dpdk.assistant.retrieval.IndexConfigurationFingerprint;
import io.dpdk.assistant.retrieval.RetrievalSettings;
import io.dpdk.assistant.usage.UsageCore;
import io.dpdk.assistant.usage.UsageLedgerStore;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 批量向量客户端与索引端口的唯一装配；无端点时不创建写入服务。 */
@Configuration
public class IndexWiring {
    @Bean
    @ConditionalOnProperty("dpdk.model.embedding.base-url")
    IndexService indexService(DataSource ds, TransactionRunner tx, RetrievalSettings retrieval,
            EmbeddingConfig config, ClockPort clock, OpenAiCompatibleEmbeddingClient client,
            ModelEmbeddingProperties model, UsageLedgerStore ledger, UsageRecordingPolicy policy) {
        return new IndexService(ds, tx, retrieval, config, new IndexConfigurationFingerprint(), clock,
                (texts, usage) -> {
                    ModelUsageLedger calls = ModelUsageLedger.create();
                    try { return client.embedTexts(texts, calls, "ingest_embed").stream()
                            .map(io.dpdk.assistant.contracts.model.Vector1024::new).toList(); }
                    finally {
                        for (var entry : calls.entries()) {
                            if (UsageCore.ZERO_CALL_SOURCES.contains(entry.usageSource())) {
                                usage.recordSkipped(entry.stage(), entry.usageSource(), entry.model(), entry.kind());
                            } else {
                                Map<String, Object> tokens = new LinkedHashMap<>();
                                tokens.put("prompt_tokens", entry.promptTokens());
                                tokens.put("completion_tokens", entry.completionTokens());
                                tokens.put("total_tokens", entry.totalTokens());
                                usage.recordLlm(entry.stage(), entry.model(), tokens,
                                        entry.durationMs() == null ? null : (int) Math.min(Integer.MAX_VALUE, entry.durationMs()),
                                        entry.kind(), entry.callCount());
                            }
                        }
                    }
                }, usage -> {
                    if (policy.enabled() && !usage.entries().isEmpty()) {
                        ledger.recordRows(UsageCore.entriesToRows(usage.entries(), policy.prices(), policy.version(),
                                null, null, null, clock.nextSessionIso()));
                    }
                }, model.batchSize());
    }

    @Bean
    Function<Path, SourceSettings> sourceSettingsFactory(
            @Value("${dpdk.index.dpdk-root:${DPDK_JAVA_DPDK_ROOT:}}") String dpdk,
            @Value("${dpdk.index.assistant-root:${DPDK_JAVA_ASSISTANT_ROOT:}}") String assistant,
            @Value("${dpdk.index.working-directory:${DPDK_JAVA_INDEX_WORKING_DIRECTORY:}}") String working) {
        return manifest -> {
            if (manifest == null || dpdk.isBlank() || assistant.isBlank() || working.isBlank()) {
                throw new IllegalStateException("索引需要显式manifest、DPDK根、assistant根和工作目录");
            }
            return new SourceSettings(manifest, Path.of(dpdk), Path.of(assistant), Path.of(working));
        };
    }
}
