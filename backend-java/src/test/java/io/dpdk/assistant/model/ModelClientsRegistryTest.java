package io.dpdk.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 客户端注册表：同 URL 复用实例；聊天与 embedding 分离；尾斜杠等价。 */
class ModelClientsRegistryTest {

    @Test
    void sameUrlReusesChatClientInstance() {
        try (ModelClients clients = new ModelClients(4)) {
            ChatClientSettings a = new ChatClientSettings(
                    "https://upstream.example/v1", "k", "m", 64, 1, 0.1, 90, true);
            ChatClientSettings b = new ChatClientSettings(
                    "https://upstream.example/v1/", "k2", "other", 32, 0, 0.2, 60, false);

            OpenAiCompatibleChatClient first = clients.chatFor(a);
            OpenAiCompatibleChatClient second = clients.chatFor(b);
            OpenAiCompatibleChatClient other = clients.chatFor(new ChatClientSettings(
                    "https://other.example/v1", "k", "m", 64, 1, 0.1, 90, true));

            assertThat(first).isSameAs(second); // 尾斜杠视为同 URL
            assertThat(first).isNotSameAs(other);
        }
    }

    @Test
    void embeddingClientsAreSeparateFromChatClients() {
        try (ModelClients clients = new ModelClients(4)) {
            OpenAiCompatibleChatClient chat = clients.chatFor(new ChatClientSettings(
                    "https://upstream.example/v1", "k", "m", 64, 1, 0.1, 90, true));
            OpenAiCompatibleEmbeddingClient embedding = clients.embeddingFor(
                    new EmbeddingClientSettings("https://upstream.example/v1", "k", "e", 10, 1, 0.1, 60, () -> 0.0));
            OpenAiCompatibleEmbeddingClient embeddingAgain = clients.embeddingFor(
                    new EmbeddingClientSettings("https://upstream.example/v1", "k", "e2", 8, 0, 0.2, 30, () -> 0.0));

            assertThat(embedding).isSameAs(embeddingAgain);
            assertThat(embedding).isNotSameAs(chat);
        }
    }

    @Test
    void sharedGateBacksAllClients() {
        try (ModelClients clients = new ModelClients(3)) {
            assertThat(clients.gate().concurrency()).isEqualTo(3);
            assertThat(clients.gate().availablePermits()).isEqualTo(3);
        }
    }

    @Test
    void chatUrlJoinsPathWithTrailingSlashStripped() {
        ChatClientSettings settings = new ChatClientSettings(
                "https://upstream.example/v1///", "k", "m", 64, 1, 0.1, 90, true);
        assertThat(settings.chatCompletionsUrl())
                .isEqualTo("https://upstream.example/v1/chat/completions");
    }

    @Test
    void embeddingUrlJoinsPathWithTrailingSlashStripped() {
        EmbeddingClientSettings settings = new EmbeddingClientSettings(
                "https://upstream.example/v1/", "k", "e", 10, 1, 0.1, 60, () -> 0.0);
        assertThat(settings.embeddingsUrl()).isEqualTo("https://upstream.example/v1/embeddings");
        assertThat(settings.effectiveBatchSize()).isEqualTo(10);
        assertThat(new EmbeddingClientSettings("b", "k", "e", 99, 1, 0.1, 60, () -> 0.0)
                .effectiveBatchSize()).isEqualTo(10);
        assertThat(new EmbeddingClientSettings("b", "k", "e", 0, 1, 0.1, 60, () -> 0.0)
                .effectiveBatchSize()).isEqualTo(1);
    }
}
