package io.dpdk.assistant.platform.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import io.dpdk.assistant.model.ModelClients;
import io.dpdk.assistant.model.OpenAiCompatibleChatClient;
import io.dpdk.assistant.model.OpenAiCompatibleEmbeddingClient;
import io.dpdk.assistant.platform.config.HarnessProperties;
import io.dpdk.assistant.platform.config.ModelChatProperties;
import io.dpdk.assistant.platform.config.ModelEmbeddingProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * SP-01 修复验收（2026-10-25 U12）：chat / embedding 分离配置的四种启动组合。
 * chat-only、embedding-only、两者、均未配置都必须可装配；无默认外部端点、
 * 不发起真实调用（仅断言 Bean 存在性，客户端构造即止）。
 */
class ModelWiringCombinationsTest {

    private static final String CHAT_URL = "http://127.0.0.1:1/v1";
    private static final String EMBEDDING_URL = "http://127.0.0.1:2/v1";

    @Configuration
    @EnableConfigurationProperties({ModelChatProperties.class, ModelEmbeddingProperties.class,
            HarnessProperties.class})
    static class PropsConfig {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropsConfig.class, ModelWiring.class));

    @Test
    void chatOnlyStarts() {
        runner.withPropertyValues(
                        "dpdk.model.chat.base-url=" + CHAT_URL,
                        "dpdk.model.chat.api-key=cfg-chat",
                        "dpdk.model.chat.model-name=chat-model")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ModelClients.class);
                    assertThat(ctx).hasSingleBean(OpenAiCompatibleChatClient.class);
                    assertThat(ctx).hasSingleBean(io.dpdk.assistant.agent.AgentChatModel.class);
                    assertThat(ctx).doesNotHaveBean(OpenAiCompatibleEmbeddingClient.class);
                });
    }

    @Test
    void embeddingOnlyStarts() {
        runner.withPropertyValues(
                        "dpdk.model.embedding.base-url=" + EMBEDDING_URL,
                        "dpdk.model.embedding.api-key=cfg-emb",
                        "dpdk.model.embedding.model-name=emb-model")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ModelClients.class);
                    assertThat(ctx).hasSingleBean(OpenAiCompatibleEmbeddingClient.class);
                    assertThat(ctx).doesNotHaveBean(OpenAiCompatibleChatClient.class);
                    assertThat(ctx).doesNotHaveBean(io.dpdk.assistant.agent.AgentChatModel.class);
                });
    }

    @Test
    void bothConfiguredStarts() {
        runner.withPropertyValues(
                        "dpdk.model.chat.base-url=" + CHAT_URL,
                        "dpdk.model.chat.api-key=cfg-chat",
                        "dpdk.model.chat.model-name=chat-model",
                        "dpdk.model.embedding.base-url=" + EMBEDDING_URL,
                        "dpdk.model.embedding.api-key=cfg-emb",
                        "dpdk.model.embedding.model-name=emb-model")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(ModelClients.class);
                    assertThat(ctx).hasSingleBean(OpenAiCompatibleChatClient.class);
                    assertThat(ctx).hasSingleBean(io.dpdk.assistant.agent.AgentChatModel.class);
                    assertThat(ctx).hasSingleBean(OpenAiCompatibleEmbeddingClient.class);
                });
    }

    @Test
    void noneConfiguredStartsWithoutModelBeans() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(ModelClients.class);
            assertThat(ctx).doesNotHaveBean(OpenAiCompatibleChatClient.class);
                    assertThat(ctx).doesNotHaveBean(io.dpdk.assistant.agent.AgentChatModel.class);
            assertThat(ctx).doesNotHaveBean(OpenAiCompatibleEmbeddingClient.class);
            // harness 未配置 url 时默认装配（matchIfMissing=true），保持 J1-02 行为。
            assertThat(ctx).hasSingleBean(
                    io.dpdk.assistant.harness.StreamableHttpHarnessClient.class);
        });
    }
}
