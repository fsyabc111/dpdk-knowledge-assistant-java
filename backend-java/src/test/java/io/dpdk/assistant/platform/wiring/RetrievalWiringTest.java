package io.dpdk.assistant.platform.wiring;

import static org.assertj.core.api.Assertions.assertThat;
import io.dpdk.assistant.retrieval.RetrievalSettings;
import io.dpdk.assistant.retrieval.QueryRewritePort;
import io.dpdk.assistant.retrieval.PgReleaseReader;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class RetrievalWiringTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner().withUserConfiguration(RetrievalWiring.class);
    @Test void defaultSettingsAndNoExternalFallback() {
        context.run(ctx -> {
            assertThat(ctx).hasNotFailed().doesNotHaveBean(PgReleaseReader.class);
            assertThat(ctx.getBean(RetrievalSettings.class)).isEqualTo(RetrievalSettings.defaults());
            assertThat(ctx.getBean(QueryRewritePort.class).rewrite("struct rte_mbuf")).isEqualTo("struct rte_mbuf");
        });
    }
    @Test void frozenEnvironmentAndExplicitPropertyUseSameSettings() {
        context.withPropertyValues("LLAMA_INDEX_TABLE=custom_li", "RETRIEVAL_W_SEMANTIC=0.6",
                "dpdk.retrieval.w-semantic=0.7", "RETRIEVAL_RRF_K=80")
                .run(ctx -> {
                    RetrievalSettings settings = ctx.getBean(RetrievalSettings.class);
                    assertThat(settings.tableName()).isEqualTo("custom_li");
                    assertThat(settings.wSemantic()).isEqualTo(0.7);
                    assertThat(settings.rrfK()).isEqualTo(80);
                });
    }
    @Test void invalidConfigFailsWithoutCallingAnyUpstream() {
        for (String value : new String[]{"LLAMA_INDEX_TABLE=bad;sql", "RETRIEVAL_RRF_K=1.5",
                "RETRIEVAL_W_SEMANTIC=NaN", "RETRIEVAL_MMR_PER_FILE_CAP=0", "LLAMA_INDEX_HNSW_ENABLED=invalid"}) {
            context.withPropertyValues(value).run(ctx -> assertThat(ctx).hasFailed());
        }
    }
}
