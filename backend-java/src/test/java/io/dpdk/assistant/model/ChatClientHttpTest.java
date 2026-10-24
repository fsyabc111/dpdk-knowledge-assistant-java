package io.dpdk.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.model.ChatInput;
import io.dpdk.assistant.contracts.model.ChatResult;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

/**
 * 非流式 chat 的 Mock HTTP 行为（对齐 tests/test_llm_client_usage.py 的 chat 节）。
 * 全部走 JDK HttpServer 替身，不连真实模型。
 */
class ChatClientHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiCompatibleChatClient client;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        pool.shutdownNow();
    }

    private static ChatClientSettings settings(String baseUrl, int maxRetries, long timeoutSeconds) {
        return new ChatClientSettings(baseUrl, "test-key", "qwen3.8-flash", 64, maxRetries,
                0.001, timeoutSeconds, true);
    }

    private static String chatBody(String content) throws Exception {
        return chatBody(content, null);
    }

    private static String chatBody(String content, String usageJson) throws Exception {
        String body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"" + content + "\"}}]";
        if (usageJson != null) {
            body += ",\"usage\":" + usageJson;
        }
        return body + "}";
    }

    private static JsonNode bodyOf(MockHttpServer.RequestRecord request) throws Exception {
        return JSON.readTree(request.body());
    }

    // ------------------------------------------------------------------
    // 成功路径与请求形状
    // ------------------------------------------------------------------

    @Test
    void chatRecordsUpstreamUsageWithoutChangingReturn() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 200,
                        chatBody("答案", "{\"prompt_tokens\":120,\"completion_tokens\":30,\"total_tokens\":150}")))) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl() + "/v1", 2, 2), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            OpenAiCompatibleChatClient.RawChatResult result =
                    client.chat(List.of(new ChatMessage("user", "hi")), "qwen3.8-flash", ledger, "rewrite");

            assertThat(result.content()).isEqualTo("答案"); // 返回值不受记账影响
            assertThat(server.requestCount()).isEqualTo(1);
            JsonNode payload = bodyOf(server.requests.get(0));
            assertThat(payload.get("model").asText()).isEqualTo("qwen3.8-flash");
            assertThat(payload.get("temperature").asDouble()).isEqualTo(0.2);
            assertThat(payload.get("max_tokens").asInt()).isEqualTo(64);
            assertThat(payload.get("messages").get(0).get("role").asText()).isEqualTo("user");
            assertThat(server.requests.get(0).authorization()).isEqualTo("Bearer test-key");
            assertThat(server.requests.get(0).path()).isEqualTo("/v1/chat/completions");

            ModelUsageEntry entry = ledger.entries().get(0);
            assertThat(entry.stage()).isEqualTo("rewrite");
            assertThat(entry.kind()).isEqualTo(ModelUsageLedger.KIND_LLM);
            assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UPSTREAM);
            assertThat(entry.promptTokens()).isEqualTo(120);
            assertThat(entry.completionTokens()).isEqualTo(30);
            assertThat(entry.totalTokens()).isEqualTo(150);
            assertThat(entry.callCount()).isEqualTo(1);
            assertThat(entry.durationMs()).isNotNull();
        }
    }

    @Test
    void chatWithoutUsageFieldRecordsUnknownNotZero() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 200, chatBody("答案")))) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl(), 2, 2), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            var result = client.chat(List.of(new ChatMessage("user", "hi")), "m", ledger, "rewrite");

            assertThat(result.content()).isEqualTo("答案");
            ModelUsageEntry entry = ledger.entries().get(0);
            assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
            assertThat(entry.promptTokens()).isNull();
            assertThat(entry.completionTokens()).isNull();
            assertThat(entry.totalTokens()).isNull(); // 未知不是 0
        }
    }

    @Test
    void completeMapsUsageToContractChatResult() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 200,
                        chatBody("答案", "{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}")))) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl(), 0, 2), new UpstreamGate(8));

            ChatResult result = client.complete(
                    new ChatInput("覆盖模型", "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"));

            assertThat(result.content()).isEqualTo("答案");
            assertThat(result.usage().promptTokens()).isEqualTo(10);
            assertThat(result.usage().usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UPSTREAM);
            // ChatInput.model 覆盖 settings.modelName。
            assertThat(bodyOf(server.requests.get(0)).get("model").asText()).isEqualTo("覆盖模型");
        }
    }

    @Test
    void missingUsageMapsToContractUnknown() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 200, chatBody("ok")))) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl(), 0, 2), new UpstreamGate(8));

            ChatResult result = client.complete(
                    new ChatInput(null, "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"));
            assertThat(result.usage().usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
            assertThat(result.usage().totalTokens()).isNull();
        }
    }

    @Test
    void chatWithoutLedgerStillWorks() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 200, chatBody("答案", "{\"total_tokens\":5}")))) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl(), 2, 2), new UpstreamGate(8));
            var result = client.chat(List.of(new ChatMessage("user", "hi")), "m", null, "");
            assertThat(result.content()).isEqualTo("答案");
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // 错误状态 / 重试 / 超时
    // ------------------------------------------------------------------

    @Test
    void nonRetriableStatusFailsOnceAndRecordsUnknown() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 401, "{\"error\":\"unauthorized\"}"))) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl(), 2, 2), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            assertThatThrownBy(() -> client.chat(
                    List.of(new ChatMessage("user", "hi")), "m", ledger, "planner"))
                    .isInstanceOf(ModelHttpException.class)
                    .satisfies(e -> assertThat(((ModelHttpException) e).status()).isEqualTo(401));

            assertThat(server.requestCount()).isEqualTo(1); // 401 不重试
            ModelUsageEntry entry = ledger.entries().get(0);
            assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
            assertThat(entry.callCount()).isEqualTo(1);
        }
    }

    @Test
    void retriableStatusRetriesThenSuccessKeepsCallCountVisible() throws Exception {
        java.util.concurrent.atomic.AtomicInteger served = new java.util.concurrent.atomic.AtomicInteger();
        try (MockHttpServer server = MockHttpServer.start((ex, request) -> {
            if (served.incrementAndGet() == 1) { // 第 1 个请求失败，第 2 个成功
                MockHttpServer.respondJson(ex, 500, "boom");
            } else {
                MockHttpServer.respondJson(ex, 200, chatBody("recovered", "{\"total_tokens\":20}"));
            }
        })) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl(), 2, 2), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            var result = client.chat(List.of(new ChatMessage("user", "hi")), "m", ledger, "planner");

            assertThat(result.content()).isEqualTo("recovered");
            assertThat(server.requestCount()).isEqualTo(2);
            assertThat(ledger.entries()).hasSize(1); // 只记一笔，不重复计费
            assertThat(ledger.entries().get(0).callCount()).isEqualTo(2);
            assertThat(ledger.entries().get(0).totalTokens()).isEqualTo(20);
            assertThat(ledger.entries().get(0).usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UPSTREAM);
        }
    }

    @Test
    void exhaustedRetriesRecordsUnknownAndRethrows() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 503, "unavailable"))) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl(), 2, 2), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            assertThatThrownBy(() -> client.chat(
                    List.of(new ChatMessage("user", "hi")), "m", ledger, "planner"))
                    .isInstanceOf(ModelHttpException.class)
                    .satisfies(e -> assertThat(((ModelHttpException) e).status()).isEqualTo(503));

            assertThat(server.requestCount()).isEqualTo(3); // 初次 + 2 次重试
            ModelUsageEntry entry = ledger.entries().get(0);
            assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
            assertThat(entry.totalTokens()).isNull();
            assertThat(entry.callCount()).isEqualTo(3);
        }
    }

    @Test
    void readTimeoutIsRetriableThenFailsWithUnknown() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) -> {
            Thread.sleep(1_500); // 超过 1s 读超时
            MockHttpServer.respondJson(ex, 200, chatBody("太慢"));
        })) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl(), 1, 1), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            assertThatThrownBy(() -> client.chat(
                    List.of(new ChatMessage("user", "hi")), "m", ledger, "planner"))
                    .isInstanceOf(ResourceAccessException.class); // 网络超时对位 httpx.TimeoutException

            assertThat(server.requestCount()).isEqualTo(2);
            ModelUsageEntry entry = ledger.entries().get(0);
            assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
            assertThat(entry.callCount()).isEqualTo(2);
        }
    }

    @Test
    void emptyResponseBodyIsProtocolErrorWithoutRetry() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 200, ""))) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl(), 2, 2), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            assertThatThrownBy(() -> client.chat(
                    List.of(new ChatMessage("user", "hi")), "m", ledger, "planner"))
                    .isInstanceOf(ModelProtocolException.class);

            assertThat(server.requestCount()).isEqualTo(1); // 协议错误不可重试
            ModelUsageEntry entry = ledger.entries().get(0);
            assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
            assertThat(entry.callCount()).isEqualTo(1);
        }
    }

    @Test
    void missingContentKeyIsProtocolError() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 200, "{\"choices\":[{\"message\":{\"role\":\"assistant\"}}]}"))) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl(), 0, 2), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();
            assertThatThrownBy(() -> client.chat(
                    List.of(new ChatMessage("user", "hi")), "m", ledger, "p"))
                    .isInstanceOf(ModelProtocolException.class)
                    .hasMessageContaining("choices[0].message.content");
            assertThat(ledger.entries()).hasSize(1);
        }
    }

    @Test
    void malformedChatInputFailsBeforeAnyHttpRequest() {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 200, chatBody("x")))) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl(), 2, 2), new UpstreamGate(8));

            assertThatThrownBy(() -> client.complete(new ChatInput("m", "{not json")))
                    .isInstanceOf(ModelProtocolException.class);
            assertThatThrownBy(() -> client.complete(new ChatInput("m", "{\"messages\":\"no\"}")))
                    .isInstanceOf(ModelProtocolException.class);
            assertThatThrownBy(() -> client.complete(new ChatInput("m",
                    "{\"messages\":[{\"role\":1,\"content\":\"x\"}]}")))
                    .isInstanceOf(ModelProtocolException.class);
            assertThat(server.requestCount()).isZero(); // 任何 HTTP 字节都没发出
        }
    }

    @Test
    void unconfiguredEndpointFailsClosed() {
        assertThatThrownBy(() -> new ChatClientSettings("", "k", "m", 1, 1, 0.1, 1, true))
                .isInstanceOf(ModelConfigurationException.class)
                .hasMessageContaining("LLM API is not configured");
    }

    // ------------------------------------------------------------------
    // 取消：不伪称远端已取消；资源最终释放
    // ------------------------------------------------------------------

    @Test
    void cancelledWaitRecordsUnknownAndReleasesGatePermit() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean serverCompleted = new AtomicBoolean(false);
        UpstreamGate gate = new UpstreamGate(1);
        try (MockHttpServer server = MockHttpServer.start((ex, request) -> {
            entered.countDown();
            Thread.sleep(400); // 给调用方留出取消窗口
            serverCompleted.set(true);
            MockHttpServer.respondJson(ex, 200, chatBody("来不及返回"));
        })) {
            client = new OpenAiCompatibleChatClient(settings(server.baseUrl(), 2, 5), gate);
            ModelUsageLedger ledger = ModelUsageLedger.create();

            Future<?> future = pool.submit(() -> client.chat(
                    List.of(new ChatMessage("user", "hi")), "m", ledger, "judge"));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            future.cancel(true); // 调用方在等待期间取消

            assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(java.util.concurrent.CancellationException.class);

            // 账立即记 unknown：请求已经发出，「没记」不等于「没花」，更不能记 0。
            awaitUntil(() -> ledger.entries().size() == 1, 3000);
            ModelUsageEntry entry = ledger.entries().get(0);
            assertThat(entry.stage()).isEqualTo("judge");
            assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
            assertThat(entry.totalTokens()).isNull();
            assertThat(entry.callCount()).isEqualTo(1);

            // 已发送请求在背景收尾后许可归还；服务器完整处理 = 我们没伪称远端已取消。
            awaitUntil(() -> gate.availablePermits() == 1, 5000);
            assertThat(serverCompleted.get()).isTrue();
        }
    }

    /** 并发上限经客户端生效：gate 限 2 时服务器最多同时看到 2 个请求。 */
    @Test
    void upstreamConcurrencyCapAppliesToChatRequests() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) -> {
            Thread.sleep(250);
            MockHttpServer.respondJson(ex, 200, chatBody("ok"));
        })) {
            UpstreamGate gate = new UpstreamGate(2);
            client = new OpenAiCompatibleChatClient(settings(server.baseUrl(), 0, 5), gate);

            final OpenAiCompatibleChatClient shared = client;
            List<Future<String>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 4; i++) {
                final String q = "q" + i;
                futures.add(pool.submit(() -> shared.chat(
                        List.of(new ChatMessage("user", q)), "m", null, "").content()));
            }
            for (Future<String> f : futures) {
                assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo("ok");
            }
            assertThat(server.requestCount()).isEqualTo(4);
            assertThat(server.maxActive.get()).isLessThanOrEqualTo(2);
            assertThat(gate.availablePermits()).isEqualTo(2);
        }
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("条件在 " + timeoutMs + "ms 内未满足");
            }
            Thread.sleep(20);
        }
    }
}
