package io.dpdk.assistant.retrieval;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.dpdk.assistant.contracts.model.Vector1024;
import io.dpdk.assistant.model.OpenAiCompatibleEmbeddingClient;
import io.dpdk.assistant.platform.config.ModelEmbeddingProperties;
import io.dpdk.assistant.platform.wiring.RetrievalWiring;
import io.dpdk.assistant.usage.UsageCore;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 仅外部向量边界可替换，缓存/记账必须仍经过生产RetrievalWiring和PgReleaseReader。 */
class RetrievalUsageWiringTest {
    @Test void fixedVectorCallbackBypassesHttpClientAndKeepsRealCacheAudit() {
        String endpoint = "http://127.0.0.1/"+UUID.randomUUID();
        var configuredClient = mock(OpenAiCompatibleEmbeddingClient.class);
        var calls = new AtomicInteger();
        PgReleaseReader.QueryEmbeddingClient fixed = (input, ledger, stage) -> {
            calls.incrementAndGet(); assertThat(input).isEqualTo("dns"); assertThat(stage).isEqualTo("query_embed");
            return List.of(new Vector1024(new float[1024]));
        };
        new ApplicationContextRunner().withUserConfiguration(RetrievalWiring.class)
                .withPropertyValues("dpdk.model.embedding.base-url="+endpoint)
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(ModelEmbeddingProperties.class, () -> new ModelEmbeddingProperties(endpoint, "fixed-not-secret", "text-embedding-v4", 10, 5, 1, 60))
                .withBean(OpenAiCompatibleEmbeddingClient.class, () -> configuredClient)
                .withBean(PgReleaseReader.QueryEmbeddingClient.class, () -> fixed)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    var reader = ctx.getBean(PgReleaseReader.class);
                    var first = new UsageCore.UsageRecorder(); var second = new UsageCore.UsageRecorder();
                    reader.queryEmbedding("dns", "dns", first);
                    reader.queryEmbedding("dns", "dns", second);
                    assertThat(first.entries()).isEmpty();
                    assertThat(second.entries()).containsExactly(new UsageCore.UsageEntry("embedding", "query_embed", "cache_hit", "text-embedding-v4", null, null, null, 0, null));
                    assertThat(calls).hasValue(1);
                    verifyNoInteractions(configuredClient);
                });
    }
}
