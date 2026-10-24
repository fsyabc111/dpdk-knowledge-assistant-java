package io.dpdk.assistant.platform.wiring;

import io.dpdk.assistant.harness.StreamableHttpHarnessClient;
import io.dpdk.assistant.model.ChatClientSettings;
import io.dpdk.assistant.model.EmbeddingClientSettings;
import io.dpdk.assistant.model.ModelClients;
import io.dpdk.assistant.model.OpenAiCompatibleChatClient;
import io.dpdk.assistant.model.OpenAiCompatibleEmbeddingClient;
import io.dpdk.assistant.platform.config.HarnessProperties;
import io.dpdk.assistant.platform.config.ModelChatProperties;
import io.dpdk.assistant.platform.config.ModelEmbeddingProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 模型/harness 装配（J1-02 显式构造类的 Spring 接线，集成者交付）。
 *
 * 端点未配置时不创建任何 Bean（不加载 .env、无默认回退）；配置存在但缺
 * base-url/api-key 时由 Settings 构造器 fail-closed。共享上游并发门
 * （默认 20，对齐 rag_upstream_concurrency）。
 */
@Configuration
public class ModelWiring {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty("dpdk.model.chat.base-url")
    public ModelClients modelClients(@Value("${dpdk.upstream-concurrency:20}") int upstreamConcurrency) {
        return new ModelClients(upstreamConcurrency);
    }

    @Bean
    @ConditionalOnProperty("dpdk.model.chat.base-url")
    public OpenAiCompatibleChatClient chatClient(ModelChatProperties props, ModelClients clients) {
        return clients.chatFor(new ChatClientSettings(
                props.baseUrl(), props.apiKey(), props.modelName(),
                props.maxTokens(), props.maxRetries(), props.retryBaseSeconds(),
                props.requestTimeoutSeconds(), props.streamUsageEnabled()));
    }

    @Bean
    @ConditionalOnProperty("dpdk.model.embedding.base-url")
    public OpenAiCompatibleEmbeddingClient embeddingClient(ModelEmbeddingProperties props,
                                                           ModelClients clients) {
        return clients.embeddingFor(new EmbeddingClientSettings(
                props.baseUrl(), props.apiKey(), props.modelName(),
                props.batchSize(), props.maxRetries(), props.retryBaseSeconds(),
                props.requestTimeoutSeconds(), null));
    }

    @Bean
    @ConditionalOnProperty(prefix = "dpdk.harness", name = "url", matchIfMissing = true)
    public StreamableHttpHarnessClient harnessClient(HarnessProperties props) {
        return io.dpdk.assistant.harness.HarnessClients.forUrl(props.url());
    }
}
