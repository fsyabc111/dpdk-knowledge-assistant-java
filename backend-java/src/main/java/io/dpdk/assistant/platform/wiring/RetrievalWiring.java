package io.dpdk.assistant.platform.wiring;

import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import io.dpdk.assistant.model.ChatMessage;
import io.dpdk.assistant.model.OpenAiCompatibleChatClient;
import io.dpdk.assistant.model.OpenAiCompatibleEmbeddingClient;
import io.dpdk.assistant.platform.config.ModelEmbeddingProperties;
import io.dpdk.assistant.platform.config.ModelChatProperties;
import io.dpdk.assistant.contracts.model.Vector1024;
import io.dpdk.assistant.model.ModelUsageLedger;
import io.dpdk.assistant.retrieval.HeuristicQueryRewriter;
import io.dpdk.assistant.retrieval.IndexConfigurationFingerprint;
import io.dpdk.assistant.retrieval.PgReleaseReader;
import io.dpdk.assistant.retrieval.QueryRewritePort;
import io.dpdk.assistant.retrieval.RetrievalSettings;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/** 检索固定版本配置；配置字符串、查询表和向量客户端使用同一快照。 */
@Configuration
public class RetrievalWiring {
    @Bean
    RetrievalSettings retrievalSettings(Environment env) {
        RetrievalSettings defaults = RetrievalSettings.defaults();
        return new RetrievalSettings(
                env.getProperty("dpdk.llama-index-table", env.getProperty("LLAMA_INDEX_TABLE", defaults.tableName())),
                env.getProperty("dpdk.llama-index-fts-config", env.getProperty("LLAMA_INDEX_FTS_CONFIG", defaults.ftsConfig())),
                value(env, "strategy", "RETRIEVAL_STRATEGY", defaults.retrievalStrategy()),
                booleanValue(value(env, "hnsw-enabled", "LLAMA_INDEX_HNSW_ENABLED", "false")),
                Integer.parseInt(value(env, "hnsw-ef-search", "LLAMA_INDEX_HNSW_EF_SEARCH", "40")),
                number(env, "w-semantic", defaults.wSemantic()),
                number(env, "w-lexical", defaults.wLexical()),
                number(env, "w-ident", defaults.wIdent()),
                number(env, "w-lexical-no-semantic", defaults.wLexicalNoSemantic()),
                number(env, "w-ident-no-semantic", defaults.wIdentNoSemantic()),
                integer(env, "rrf-k", defaults.rrfK()),
                number(env, "rrf-prior-scale", defaults.rrfPriorScale()),
                number(env, "rrf-prior-cap", defaults.rrfPriorCap()),
                number(env, "mmr-redundancy-penalty", defaults.mmrRedundancyPenalty()),
                number(env, "mmr-per-file-penalty", defaults.mmrPerFilePenalty()),
                integer(env, "mmr-per-file-cap", defaults.mmrPerFileCap()),
                number(env, "filename-boost-exact", defaults.filenameBoostExact()),
                number(env, "filename-boost-partial", defaults.filenameBoostPartial()),
                number(env, "topic-boost-specific", defaults.topicBoostSpecific()),
                number(env, "topic-boost-generic", defaults.topicBoostGeneric()),
                number(env, "topic-boost-specific-bare", defaults.topicBoostSpecificBare()),
                number(env, "topic-boost-generic-bare", defaults.topicBoostGenericBare()),
                number(env, "topic-boost-specific-infix", defaults.topicBoostSpecificInfix()),
                number(env, "topic-boost-generic-infix", defaults.topicBoostGenericInfix()),
                number(env, "ident-header-score", defaults.identHeaderScore()),
                number(env, "ident-body-score", defaults.identBodyScore()),
                number(env, "definition-boost-typedef", defaults.definitionBoostTypedef()),
                number(env, "definition-boost-funcsig", defaults.definitionBoostFuncsig()));
    }

    private static String value(Environment env, String key, String legacy, String fallback) {
        return env.getProperty("dpdk.retrieval." + key, env.getProperty(legacy, fallback));
    }
    private static double number(Environment env, String key, double fallback) {
        double result = Double.parseDouble(value(env, key, "RETRIEVAL_" + key.replace('-', '_').toUpperCase(java.util.Locale.ROOT), Double.toString(fallback)));
        if (!Double.isFinite(result)) { throw new IllegalArgumentException("检索配置必须为有限数字"); }
        return result;
    }
    private static int integer(Environment env, String key, int fallback) {
        return Integer.parseInt(value(env, key, "RETRIEVAL_" + key.replace('-', '_').toUpperCase(java.util.Locale.ROOT), Integer.toString(fallback)));
    }
    private static boolean booleanValue(String raw) {
        if ("true".equalsIgnoreCase(raw) || "1".equals(raw)) { return true; }
        if ("false".equalsIgnoreCase(raw) || "0".equals(raw)) { return false; }
        throw new IllegalArgumentException("HNSW开关不是布尔值");
    }

    @Bean
    @ConditionalOnProperty("dpdk.model.embedding.base-url")
    EmbeddingConfig embeddingConfig(ModelEmbeddingProperties model) {
        return new EmbeddingConfig(model.modelName(), model.baseUrl(), 1024, "bounded-lines-v2");
    }

    @Bean
    QueryRewritePort queryRewritePort(ObjectProvider<OpenAiCompatibleChatClient> client,
            ObjectProvider<ModelChatProperties> properties) {
        OpenAiCompatibleChatClient model = client.getIfAvailable();
        ModelChatProperties config = properties.getIfAvailable();
        QueryRewritePort.LlmClient llm = model == null ? null : new QueryRewritePort.LlmClient() {
            public String generate(String system, String user) {
                return generate(system, user, null, "");
            }
            public String generate(String system, String user, ModelUsageLedger ledger, String stage) {
                return model.chat(List.of(new ChatMessage("system", system), new ChatMessage("user", user)),
                        null, ledger, stage).content();
            }
        };
        return new HeuristicQueryRewriter(llm, config == null ? "" : config.baseUrl(),
                config == null ? "qwen3.8-flash" : config.modelName());
    }

    @Bean
    @ConditionalOnProperty("dpdk.model.embedding.base-url")
    PgReleaseReader pgReleaseReader(DataSource dataSource, RetrievalSettings settings,
            EmbeddingConfig config, QueryRewritePort rewriter, OpenAiCompatibleEmbeddingClient embeddings,
            ObjectProvider<PgReleaseReader.QueryEmbeddingClient> explicitBoundary) {
        PgReleaseReader.QueryEmbeddingClient boundary = explicitBoundary.getIfAvailable();
        if (boundary == null) {
            boundary = (text, ledger, stage) -> embeddings.embedTexts(List.of(text), ledger, stage).stream()
                    .map(Vector1024::new).toList();
        }
        return new PgReleaseReader(dataSource, settings, config,
                new IndexConfigurationFingerprint(), rewriter, embeddings, boundary);
    }
}
