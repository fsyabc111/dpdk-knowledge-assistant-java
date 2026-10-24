package io.dpdk.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.model.ModelDelta;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 流式 chat 的 Mock HTTP 行为（对齐 tests/test_llm_client_usage.py 的 stream_chat 节）。
 * 读取边界 ≠ SSE 帧边界：增量按读取到的非空 delta 逐段投递。
 */
class ChatClientStreamHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private OpenAiCompatibleChatClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
    }

    private static ChatClientSettings settings(String baseUrl, boolean streamUsage, long timeoutSeconds) {
        return new ChatClientSettings(baseUrl, "test-key", "qwen3.8-flash", 64, 2,
                0.001, timeoutSeconds, streamUsage);
    }

    /** 收集型订阅者：可配置收到首个增量后取消。 */
    private static final class Collector implements Flow.Subscriber<ModelDelta> {
        final List<String> deltas = new CopyOnWriteArrayList<>();
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        volatile Flow.Subscription subscription;
        volatile boolean cancelAfterFirst;
        volatile Runnable onFirstDelta;

        @Override
        public void onSubscribe(Flow.Subscription s) {
            subscription = s;
            s.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(ModelDelta item) {
            deltas.add(item.text());
            if (cancelAfterFirst) {
                subscription.cancel();
            }
            Runnable hook = onFirstDelta;
            if (hook != null) {
                hook.run();
            }
        }

        @Override
        public void onError(Throwable throwable) {
            error.set(throwable);
            finished.countDown();
        }

        @Override
        public void onComplete() {
            finished.countDown();
        }
    }

    private Collector collect(Flow.Publisher<ModelDelta> publisher) throws InterruptedException {
        Collector collector = new Collector();
        publisher.subscribe(collector);
        assertThat(collector.finished.await(10, TimeUnit.SECONDS))
                .as("流应正常终结（完成或错误）").isTrue();
        return collector;
    }

    private static JsonNode bodyOf(MockHttpServer.RequestRecord request) throws Exception {
        return JSON.readTree(request.body());
    }

    // ------------------------------------------------------------------
    // 正常流
    // ------------------------------------------------------------------

    @Test
    void streamParsesDeltasAndUsageTailFrame() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
                + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":40,\"completion_tokens\":5,\"total_tokens\":45}}\n\n"
                + "data: [DONE]\n\n";
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondText(ex, 200, "text/event-stream", sse))) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl(), true, 5), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            Collector collector = collect(client.streamChat(
                    List.of(new ChatMessage("user", "hi")), "m", ledger, "answer"));

            assertThat(collector.deltas).containsExactly("你", "好"); // 尾帧不产生空 delta
            assertThat(collector.error.get()).isNull();
            assertThat(bodyOf(server.requests.get(0)).get("stream_options")
                    .get("include_usage").asBoolean()).isTrue();
            assertThat(bodyOf(server.requests.get(0)).get("stream").asBoolean()).isTrue();

            ModelUsageEntry entry = ledger.entries().get(0);
            assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UPSTREAM);
            assertThat(entry.totalTokens()).isEqualTo(45);
            assertThat(entry.stage()).isEqualTo("answer");
            assertThat(entry.callCount()).isEqualTo(1);
        }
    }

    @Test
    void streamOmitsStreamOptionsWhenDisabled() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\ndata: [DONE]\n\n";
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondText(ex, 200, "text/event-stream", sse))) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl(), false, 5), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            Collector collector = collect(client.streamChat(
                    List.of(new ChatMessage("user", "hi")), "m", ledger, "answer"));

            assertThat(collector.deltas).containsExactly("x");
            assertThat(bodyOf(server.requests.get(0)).has("stream_options")).isFalse();
            ModelUsageEntry entry = ledger.entries().get(0);
            assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
        }
    }

    @Test
    void streamIgnoresMalformedFrames() throws Exception {
        String sse = "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n\n"
                + "data: {not json}\n\n"
                + "data: \n\n"
                + "data: [DONE]\n\n";
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondText(ex, 200, "text/event-stream", sse))) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl(), true, 5), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            Collector collector = collect(client.streamChat(
                    List.of(new ChatMessage("user", "hi")), "m", ledger, "answer"));

            assertThat(collector.deltas).containsExactly("a"); // 坏帧跳过，不失败
            assertThat(ledger.entries().get(0).usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
        }
    }

    @Test
    void emptyStreamRecordsUnknownUsage() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondText(ex, 200, "text/event-stream", ""))) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl(), true, 5), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            Collector collector = collect(client.streamChat(
                    List.of(new ChatMessage("user", "hi")), "m", ledger, "answer"));

            assertThat(collector.deltas).isEmpty();
            assertThat(collector.error.get()).isNull();
            ModelUsageEntry entry = ledger.entries().get(0);
            assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
            assertThat(entry.callCount()).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // stream_options 被拒：去掉字段重试一次
    // ------------------------------------------------------------------

    @Test
    void streamRetriesWithoutStreamOptionsOn400() throws Exception {
        java.util.concurrent.atomic.AtomicInteger served = new java.util.concurrent.atomic.AtomicInteger();
        try (MockHttpServer server = MockHttpServer.start((ex, request) -> {
            if (served.incrementAndGet() == 1) {
                MockHttpServer.respondJson(ex, 400,
                        "{\"error\":{\"message\":\"unknown field stream_options\"}}");
            } else {
                MockHttpServer.respondText(ex, 200, "text/event-stream",
                        "data: {\"choices\":[{\"delta\":{\"content\":\"fallback\"}}]}\n\n"
                                + "data: [DONE]\n\n");
            }
        })) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl(), true, 5), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            Collector collector = collect(client.streamChat(
                    List.of(new ChatMessage("user", "hi")), "m", ledger, "answer"));

            assertThat(collector.deltas).containsExactly("fallback");
            assertThat(server.requestCount()).isEqualTo(2);
            assertThat(bodyOf(server.requests.get(0)).has("stream_options")).isTrue();
            assertThat(bodyOf(server.requests.get(1)).has("stream_options")).isFalse();
            ModelUsageEntry entry = ledger.entries().get(0);
            assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
            assertThat(entry.callCount()).isEqualTo(2); // 两次上游调用都在账上
        }
    }

    @Test
    void streamDoesNotRetryOther400Causes() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 400,
                        "{\"error\":{\"message\":\"context length exceeded\"}}"))) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl(), true, 5), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            Collector collector = collect(client.streamChat(
                    List.of(new ChatMessage("user", "hi")), "m", ledger, "answer"));

            assertThat(server.requestCount()).isEqualTo(1); // 不给真实错误白送一次重试
            assertThat(collector.error.get()).isInstanceOf(ModelHttpException.class);
            assertThat(((ModelHttpException) collector.error.get()).status()).isEqualTo(400);
            assertThat(ledger.entries().get(0).usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
        }
    }

    @Test
    void streamErrorStatusSurfacesOnError() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, request) ->
                MockHttpServer.respondJson(ex, 503, "unavailable"))) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl(), true, 5), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();

            Collector collector = collect(client.streamChat(
                    List.of(new ChatMessage("user", "hi")), "m", ledger, "answer"));

            assertThat(collector.error.get()).isInstanceOf(ModelHttpException.class);
            assertThat(ledger.entries()).hasSize(1);
            assertThat(ledger.entries().get(0).usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
        }
    }

    // ------------------------------------------------------------------
    // 消费方提前取消
    // ------------------------------------------------------------------

    @Test
    void earlyCancelStopsReadingAndRecordsUnknown() throws Exception {
        CountDownLatch firstDeltaDelivered = new CountDownLatch(1);
        CountDownLatch cancelConfirmed = new CountDownLatch(1);
        CountDownLatch ledgerReady = new CountDownLatch(1);
        ModelUsageLedger[] ledgerBox = new ModelUsageLedger[1];

        try (MockHttpServer server = MockHttpServer.start((ex, request) -> {
            MockHttpServer.SseStream sse = MockHttpServer.respondSse(ex, 200);
            sse.sendDataFrame("{\"choices\":[{\"delta\":{\"content\":\"a\"}}]}");
            // 等消费方取消被确认后再补发尾帧：证明取消后尾帧不再进账。
            firstDeltaDelivered.await();
            cancelConfirmed.await();
            sse.sendDataFrame("{\"choices\":[{\"delta\":{\"content\":\"b\"}}]}");
            sse.sendDataFrame("{\"choices\":[],\"usage\":{\"total_tokens\":99}}");
            sse.sendLine("data: [DONE]");
            sse.close();
        })) {
            client = new OpenAiCompatibleChatClient(
                    settings(server.baseUrl(), true, 5), new UpstreamGate(8));
            ModelUsageLedger ledger = ModelUsageLedger.create();
            ledgerBox[0] = ledger;

            Collector collector = new Collector();
            collector.cancelAfterFirst = true;
            collector.onFirstDelta = () -> {
                firstDeltaDelivered.countDown();
                // 首个 delta 已投递且订阅已 cancel；等一拍让 IO 线程观察到取消。
                Thread cancelWaiter = new Thread(() -> {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    cancelConfirmed.countDown();
                }, "cancel-confirm");
                cancelWaiter.setDaemon(true);
                cancelWaiter.start();
            };
            client.streamChat(List.of(new ChatMessage("user", "hi")), "m", ledger, "answer")
                    .subscribe(collector);

            firstDeltaDelivered.await(5, TimeUnit.SECONDS);
            // 取消后不应再收到任何 delta / 完成 / 错误信号。
            Thread.sleep(400);
            assertThat(collector.deltas).containsExactly("a");
            assertThat(collector.finished.getCount()).isEqualTo(1); // 无 onComplete/onError

            cancelConfirmed.countDown();
            // 请求已发出：账必须留下（unknown，尾帧未被消费）。
            Thread watcher = new Thread(() -> {
                try {
                    for (int i = 0; i < 200 && ledgerBox[0].entries().isEmpty(); i++) {
                        Thread.sleep(20);
                    }
                    ledgerReady.countDown();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "ledger-watcher");
            watcher.setDaemon(true);
            watcher.start();
            assertThat(ledgerReady.await(5, TimeUnit.SECONDS)).isTrue();

            ModelUsageEntry entry = ledger.entries().get(0);
            assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
            assertThat(entry.totalTokens()).isNull(); // 尾帧没消费，不知道就不编
            assertThat(entry.callCount()).isEqualTo(1);
        }
    }
}
