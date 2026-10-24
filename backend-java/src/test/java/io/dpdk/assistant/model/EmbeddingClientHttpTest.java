package io.dpdk.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.model.EmbeddingInput;
import io.dpdk.assistant.contracts.model.Vector1024;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * embedding 客户端的 Mock HTTP 行为（对齐 tests/test_llm_client_usage.py 的 embed 节）。
 */
class EmbeddingClientHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiCompatibleEmbeddingClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    private static EmbeddingClientSettings settings(String baseUrl, int maxRetries, double retryBase) {
        return new EmbeddingClientSettings(baseUrl, "test-key", "text-embedding-v4", 10,
                maxRetries, retryBase, 2, () -> 0.0);
    }

    private static String embeddingsBody(int count, boolean withUsage, int tokensPerItem) {
        StringBuilder sb = new StringBuilder("{\"data\":[");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"index\":").append(i).append(",\"embedding\":[").append(i).append(".5]");
            sb.append('}');
        }
        sb.append(']');
        if (withUsage) {
            sb.append(",\"usage\":{\"prompt_tokens\":").append(tokensPerItem * count)
                    .append(",\"total_tokens\":").append(tokensPerItem * count).append('}');
        }
        return sb.append('}').toString();
    }

    private static JsonNode bodyOf(MockHttpServer.RequestRecord request) throws Exception {
        return JSON.readTree(request.body());
    }

    @Test
    void embedRecordsUsagePerRequest() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 200, embeddingsBody(3, true, 7)))) {
            client = new OpenAiCompatibleEmbeddingClient(
                    settings(server.baseUrl(), 1, 0.001), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            List<float[]> vectors = client.embedTexts(List.of("0", "1", "2"), ledger, "query_embed");

            assertThat(vectors).hasSize(3);
            assertThat(vectors.get(2)).containsExactly(2.5f);
            assertThat(server.requestCount()).isEqualTo(1);
            assertThat(server.requests.get(0).path()).isEqualTo("/embeddings");
            assertThat(bodyOf(server.requests.get(0)).get("model").asText()).isEqualTo("text-embedding-v4");

            ModelUsageEntry entry = ledger.entries().get(0);
            assertThat(entry.kind()).isEqualTo(ModelUsageLedger.KIND_EMBEDDING);
            assertThat(entry.stage()).isEqualTo("query_embed");
            assertThat(entry.model()).isEqualTo("text-embedding-v4");
            assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UPSTREAM);
            assertThat(entry.totalTokens()).isEqualTo(21);
            assertThat(entry.completionTokens()).isNull();
        }
    }

    @Test
    void embedSplitsBatchesAndRecordsEach() throws Exception {
        // batch_size=10：11 条文本拆成 2 批，每批独立记账。
        try (MockHttpServer server = MockHttpServer.start((ex, request) -> {
            int inputs = bodyOf(request).get("input").size();
            MockHttpServer.respondJson(ex, 200, embeddingsBody(inputs, true, 2));
        })) {
            EmbeddingClientSettings batchSettings = new EmbeddingClientSettings(
                    server.baseUrl(), "test-key", "text-embedding-v4", 10, 1, 0.001, 2, () -> 0.0);
            client = new OpenAiCompatibleEmbeddingClient(batchSettings, new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            List<String> texts = new ArrayList<>();
            for (int i = 0; i < 11; i++) {
                texts.add("t" + i);
            }
            List<float[]> vectors = client.embedTexts(texts, ledger, "ingest_embed");

            assertThat(vectors).hasSize(11);
            assertThat(server.requestCount()).isEqualTo(2);
            assertThat(bodyOf(server.requests.get(0)).get("input").size()).isEqualTo(10);
            assertThat(bodyOf(server.requests.get(1)).get("input").size()).isEqualTo(1);
            assertThat(ledger.entries()).hasSize(2); // 每批一笔
            assertThat(ledger.entries().get(0).totalTokens()).isEqualTo(20);
            assertThat(ledger.entries().get(1).totalTokens()).isEqualTo(2);
        }
    }

    @Test
    void embedRecordsUnknownWhenUpstreamOmitsUsage() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 200, embeddingsBody(1, false, 0)))) {
            client = new OpenAiCompatibleEmbeddingClient(
                    settings(server.baseUrl(), 1, 0.001), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            client.embedTexts(List.of("a"), ledger, "ingest_embed");

            ModelUsageEntry entry = ledger.entries().get(0);
            assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
            assertThat(entry.totalTokens()).isNull();
        }
    }

    @Test
    void embedFailureAfterRetriesRecordsUnknownPerBatch() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 503, "unavailable"))) {
            client = new OpenAiCompatibleEmbeddingClient(
                    settings(server.baseUrl(), 1, 0.001), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            assertThatThrownBy(() -> client.embedTexts(List.of("a"), ledger, "query_embed"))
                    .isInstanceOf(ModelHttpException.class)
                    .satisfies(e -> assertThat(((ModelHttpException) e).status()).isEqualTo(503));

            assertThat(server.requestCount()).isEqualTo(2); // 初次 + 1 次重试
            ModelUsageEntry entry = ledger.entries().get(0);
            assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
            assertThat(entry.callCount()).isEqualTo(2);
        }
    }

    @Test
    void embedHonorsRetryAfterHeaderOverExponentialBackoff() throws Exception {
        // retryBase=30s：若无 Retry-After 优先级，第二次请求不可能在 5 秒内发生。
        java.util.concurrent.atomic.AtomicInteger served = new java.util.concurrent.atomic.AtomicInteger();
        try (MockHttpServer server = MockHttpServer.start((ex, request) -> {
            if (served.incrementAndGet() == 1) {
                ex.getResponseHeaders().set("Retry-After", "0.05");
                MockHttpServer.respondJson(ex, 429, "{\"error\":\"rate limited\"}");
            } else {
                MockHttpServer.respondJson(ex, 200, embeddingsBody(1, true, 4));
            }
        })) {
            client = new OpenAiCompatibleEmbeddingClient(
                    settings(server.baseUrl(), 1, 30.0), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();
            long started = System.nanoTime();

            List<float[]> vectors = client.embedTexts(List.of("a"), ledger, "query_embed");

            assertThat(vectors).hasSize(1);
            assertThat(server.requestCount()).isEqualTo(2);
            assertThat((System.nanoTime() - started) / 1_000_000L).isLessThan(5_000);
            assertThat(ledger.entries().get(0).callCount()).isEqualTo(2);
            assertThat(ledger.entries().get(0).usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UPSTREAM);
        }
    }

    @Test
    void embedCountMismatchIsProtocolError() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 200, embeddingsBody(1, false, 0)))) {
            client = new OpenAiCompatibleEmbeddingClient(
                    settings(server.baseUrl(), 1, 0.001), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            assertThatThrownBy(() -> client.embedTexts(List.of("a", "b"), ledger, "query_embed"))
                    .isInstanceOf(ModelProtocolException.class)
                    .hasMessageContaining("Embedding count mismatch: expected 2, got 1");

            assertThat(server.requestCount()).isEqualTo(1); // 协议错误不重试
            ModelUsageEntry entry = ledger.entries().get(0);
            assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
            assertThat(entry.callCount()).isEqualTo(1);
        }
    }

    @Test
    void embedEmptyResponseBodyIsProtocolError() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 200, ""))) {
            client = new OpenAiCompatibleEmbeddingClient(
                    settings(server.baseUrl(), 1, 0.001), new UpstreamGate(8));
            assertThatThrownBy(() -> client.embedTexts(List.of("a"), null, "q"))
                    .isInstanceOf(ModelProtocolException.class);
        }
    }

    @Test
    void embedEmptyInputSendsNothingAndRecordsNothing() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 500, "should not be called"))) {
            client = new OpenAiCompatibleEmbeddingClient(
                    settings(server.baseUrl(), 1, 0.001), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            assertThat(client.embedTexts(List.of(), ledger, "q")).isEmpty();
            assertThat(server.requestCount()).isZero();
            assertThat(ledger.entries()).isEmpty();
        }
    }

    @Test
    void embedTimeoutRetriedThenFails() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) -> {
            Thread.sleep(1_500);
            MockHttpServer.respondJson(ex, 200, embeddingsBody(1, false, 0));
        })) {
            EmbeddingClientSettings timeoutSettings = new EmbeddingClientSettings(
                    server.baseUrl(), "test-key", "text-embedding-v4", 10, 1, 0.001, 1, () -> 0.0);
            client = new OpenAiCompatibleEmbeddingClient(timeoutSettings, new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            assertThatThrownBy(() -> client.embedTexts(List.of("a"), ledger, "q"))
                    .isInstanceOf(ResourceAccessException.class);
            assertThat(server.requestCount()).isEqualTo(2);
            assertThat(ledger.entries().get(0).usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
        }
    }

    // ------------------------------------------------------------------
    // 契约端口：1024 维校验
    // ------------------------------------------------------------------

    private static String vectorBody(int dim) {
        StringBuilder vector = new StringBuilder();
        for (int i = 0; i < dim; i++) {
            if (i > 0) {
                vector.append(',');
            }
            vector.append(i % 7).append(".25");
        }
        return "{\"data\":[{\"index\":0,\"embedding\":[" + vector + "]}]}";
    }

    @Test
    void portEmbedReturnsFrozen1024Vector() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 200, vectorBody(1024)))) {
            client = new OpenAiCompatibleEmbeddingClient(
                    settings(server.baseUrl(), 0, 0.001), new UpstreamGate(8));

            List<Vector1024> vectors = client.embed(new EmbeddingInput("一段文本"));

            assertThat(vectors).hasSize(1);
            assertThat(vectors.get(0).values()).hasSize(1024);
            assertThat(vectors.get(0).values()[0]).isEqualTo(0.25f);
        }
    }

    @Test
    void portEmbedRejectsNon1024Dimensions() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 200, vectorBody(8)))) {
            client = new OpenAiCompatibleEmbeddingClient(
                    settings(server.baseUrl(), 0, 0.001), new UpstreamGate(8));

            assertThatThrownBy(() -> client.embed(new EmbeddingInput("文本")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("1024");
        }
    }
}
