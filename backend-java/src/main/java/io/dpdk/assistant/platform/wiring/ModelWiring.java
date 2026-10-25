package io.dpdk.assistant.platform.wiring;

import io.dpdk.assistant.harness.StreamableHttpHarnessClient;
import io.dpdk.assistant.model.ChatClientSettings;
import io.dpdk.assistant.model.EmbeddingClientSettings;
import io.dpdk.assistant.model.ModelClients;
import io.dpdk.assistant.model.OpenAiCompatibleChatClient;
import io.dpdk.assistant.model.OpenAiCompatibleEmbeddingClient;
import io.dpdk.assistant.model.AgentModelAdapter;
import io.dpdk.assistant.agent.AgentChatModel;
import io.dpdk.assistant.platform.config.HarnessProperties;
import io.dpdk.assistant.platform.config.ModelChatProperties;
import io.dpdk.assistant.platform.config.ModelEmbeddingProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.AnyNestedCondition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * 模型/harness 装配（J1-02 显式构造类的 Spring 接线，集成者交付）。
 *
 * 端点未配置时不创建任何 Bean（不加载 .env、无默认回退）；配置存在但缺
 * base-url/api-key 时由 Settings 构造器 fail-closed。共享上游并发门
 * （默认 20，对齐 rag_upstream_concurrency）。
 *
 * SP-01（2026-10-25 验收修复）：chat 与 embedding 分离配置（J1-02），四种启动
 * 组合都必须可装配——ModelClients 在 chat.base-url 或 embedding.base-url 任一
 * 存在时创建；chat-only / embedding-only / 两者 / 均未配置各自只装配所属客户端，
 * 均无默认外部端点、无真实调用。
 */
@Configuration
public class ModelWiring {

    @Bean(destroyMethod = "close")
    @Conditional(ModelClientsNeeded.class)
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
    @ConditionalOnProperty("dpdk.model.chat.base-url")
    public AgentChatModel agentChatModel(OpenAiCompatibleChatClient client, ModelChatProperties props) {
        return new AgentModelAdapter(client, props.modelName());
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

    /** chat 或 embedding 任一配置即需要共享上游并发门（SP-01 四组合）。 */
    static class ModelClientsNeeded extends AnyNestedCondition {

        ModelClientsNeeded() {
            super(ConfigurationPhase.REGISTER_BEAN);
        }

        @ConditionalOnProperty("dpdk.model.chat.base-url")
        static class ChatPresent {
        }

        @ConditionalOnProperty("dpdk.model.embedding.base-url")
        static class EmbeddingPresent {
        }
    }
}
