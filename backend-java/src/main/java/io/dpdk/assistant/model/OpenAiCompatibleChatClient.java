package io.dpdk.assistant.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.model.ChatInput;
import io.dpdk.assistant.contracts.model.ChatModelPort;
import io.dpdk.assistant.contracts.model.ChatResult;
import io.dpdk.assistant.contracts.model.ModelDelta;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * OpenAI 兼容聊天客户端（对齐 backend/app/core/llm_client.py 的 chat/stream_chat）。
 *
 * <p>关键语义：
 * <ul>
 *   <li>每次上游尝试都持有 {@link UpstreamGate} 许可（upstream_slot 对位）；</li>
 *   <li>重试分类走 {@link ModelFailureClassifier}：429/5xx/网络/超时可重试，指数退避无抖动；</li>
 *   <li>内容解析成功后才记上游账；失败/取消/重试耗尽统一在 finally 补记一次 unknown
 *       （null 不是 0），重试次数通过 callCount 可见，绝不重复计费；</li>
 *   <li>流式默认不带 usage，streamUsageEnabled 时显式索取；上游以 400 拒绝
 *       stream_options/include_usage 时去掉该字段重试一次（calls 记 2）；其他 400 原样抛出；</li>
 *   <li>调用线程中断 = 取消等待：不伪称远端已取消，账记 unknown；已发送的 HTTP 请求
 *       在背景 IO 线程上自然收尾并释放并发门许可。取消发生在发送之前时请求不出门。</li>
 * </ul>
 */
public final class OpenAiCompatibleChatClient implements ChatModelPort {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ERROR_BODY = 64 * 1024;
    /** 源 payload 固定 temperature=0.2（llm_client.chat）。 */
    private static final double TEMPERATURE = 0.2;

    private final ChatClientSettings settings;
    private final UpstreamGate gate;
    private final RestClient restClient;
    private final ExecutorService io;
    private final boolean ownedExecutor;

    public OpenAiCompatibleChatClient(ChatClientSettings settings, UpstreamGate gate) {
        this(settings, gate,
                defaultRestClient(settings.requestTimeoutSeconds()),
                Executors.newCachedThreadPool(OpenAiCompatibleChatClient::ioThread),
                true);
    }

    OpenAiCompatibleChatClient(
            ChatClientSettings settings, UpstreamGate gate, RestClient restClient,
            ExecutorService io, boolean ownedExecutor) {
        this.settings = settings;
        this.gate = gate;
        this.restClient = restClient;
        this.io = io;
        this.ownedExecutor = ownedExecutor;
    }

    private static Thread ioThread(Runnable runnable) {
        Thread t = new Thread(runnable, "dpdk-model-io");
        t.setDaemon(true);
        return t;
    }

    /** 进程级复用的 HTTP 客户端（对位 httpx.AsyncClient 类级缓存；90s 读 / 10s 连接）。 */
    static RestClient defaultRestClient(long requestTimeoutSeconds) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(ChatClientSettings.CONNECT_TIMEOUT_SECONDS))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(requestTimeoutSeconds));
        return RestClient.builder().requestFactory(factory).build();
    }

    public void close() {
        if (ownedExecutor) {
            io.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // 契约端口适配
    // ------------------------------------------------------------------

    @Override
    public ChatResult complete(ChatInput input) {
        ChatMessage.ParsedPayload parsed = ChatMessage.parsePayload(input == null ? null : input.jsonPayload());
        String model = firstNonBlank(
                input == null ? null : input.model(), parsed.modelOverride(), settings.modelName());
        RawChatResult outcome = chat(parsed.messages(), model, null, "");
        return new ChatResult(outcome.content(), toContractUsage(outcome.rawUsage()));
    }

    @Override
    public Flow.Publisher<ModelDelta> stream(ChatInput input) {
        ChatMessage.ParsedPayload parsed = ChatMessage.parsePayload(input == null ? null : input.jsonPayload());
        String model = firstNonBlank(
                input == null ? null : input.model(), parsed.modelOverride(), settings.modelName());
        return streamChat(parsed.messages(), model, null, "");
    }

    static String firstNonBlank(String a, String b, String c) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return c;
    }

    static ChatResult.Usage toContractUsage(Map<String, Object> rawUsage) {
        Map<String, Integer> tokens = ModelUsageLedger.tokensFromUsage(rawUsage);
        boolean any = tokens.get("prompt_tokens") != null
                || tokens.get("completion_tokens") != null
                || tokens.get("total_tokens") != null;
        return new ChatResult.Usage(
                tokens.get("prompt_tokens"),
                tokens.get("completion_tokens"),
                tokens.get("total_tokens"),
                any ? ModelUsageLedger.SOURCE_UPSTREAM : ModelUsageLedger.SOURCE_UNKNOWN);
    }

    // ------------------------------------------------------------------
    // 非流式 chat
    // ------------------------------------------------------------------

    /** 单次 chat 的上游原始结果。 */
    public record RawChatResult(String content, Map<String, Object> rawUsage) {
    }

    /**
     * 富接口 chat（供业务层携带账本与 stage；契约口 complete 是它的无账本封装）。
     * 整个调用只记一笔账：成功记上游 usage，失败/取消在 finally 记 unknown。
     */
    public RawChatResult chat(List<ChatMessage> messages, String model, ModelUsageLedger usage, String stage) {
        long started = System.nanoTime();
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean recorded = new AtomicBoolean();
        Map<String, Object> payload = basePayload(messages, model, false);
        String modelName = (String) payload.get("model");
        try {
            while (true) {
                int attempt = calls.incrementAndGet();
                try {
                    RawChatResult outcome = exchangeOnce(payload);
                    recordOnce(usage, stage, modelName, outcome.rawUsage(), elapsedMs(started),
                            attempt, ModelUsageLedger.KIND_LLM, recorded);
                    return outcome;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw cancelledAtAwait(modelName);
                } catch (RuntimeException failure) {
                    if (attempt > settings.maxRetries() || !ModelFailureClassifier.isRetriable(failure)) {
                        throw failure; // finally 补 unknown 账后向调用方透传
                    }
                    try {
                        Thread.sleep((long) (ModelFailureClassifier
                                .retryDelaySeconds(attempt, settings.retryBaseSeconds()) * 1000.0));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw cancelledAtAwait(modelName);
                    }
                }
            }
        } finally {
            recordOnce(usage, stage, modelName, null, elapsedMs(started),
                    Math.max(calls.get(), 1), ModelUsageLedger.KIND_LLM, recorded);
        }
    }

    private static ModelCallCancelledException cancelledAtAwait(String modelName) {
        return new ModelCallCancelledException(
                "等待模型(" + modelName + ")响应时被取消；已发送请求的远端结果未知，账已按 unknown 记录");
    }

    private static long elapsedMs(long startedNanos) {
        return Math.max((System.nanoTime() - startedNanos) / 1_000_000L, 0L);
    }

    /**
     * 单次上游交换：跑在背景 IO 线程并持有并发门许可。
     * 调用线程中断只取消「等待」——取消发生在发送之前则请求不出门；已发出的请求
     * 继续收尾并释放许可（不伪称远端已取消）。
     */
    private RawChatResult exchangeOnce(Map<String, Object> payload) throws InterruptedException {
        AtomicBoolean abandoned = new AtomicBoolean(false);
        CompletableFuture<RawChatResult> task = new CompletableFuture<>();
        Future<?> submitted = io.submit(() -> {
            if (abandoned.get()) {
                return; // 取消先于发送：对位 Python 在 upstream_slot 等待中被取消
            }
            try (UpstreamGate.Permit ignored = gate.acquire()) {
                if (abandoned.get()) {
                    return;
                }
                task.complete(doPost(payload));
            } catch (Throwable t) {
                task.completeExceptionally(t instanceof RuntimeException runtime ? runtime
                        : new ModelProtocolException("模型上游交换失败", t));
            }
        });
        try {
            return task.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ModelProtocolException("模型上游交换失败", cause);
        } catch (InterruptedException e) {
            abandoned.set(true);
            submitted.cancel(false); // 不打断 IO 线程：已发送请求自然收尾并释放许可
            throw e;
        }
    }

    private RawChatResult doPost(Map<String, Object> payload) {
        return restClient.post()
                .uri(settings.chatCompletionsUrl())
                .headers(this::authorize)
                .body(payload)
                .exchange((request, response) -> handleChatResponse(response));
    }

    private RawChatResult handleChatResponse(ClientHttpResponse response) throws IOException {
        if (response.getStatusCode().isError()) {
            throw httpError(response);
        }
        JsonNode root = parseJson(readBody(response));
        JsonNode choices = root.path("choices");
        JsonNode message = choices.path(0).path("message");
        JsonNode content = message.path("content");
        if (!choices.isArray() || choices.isEmpty() || !message.isObject() || !content.isTextual()) {
            // 对位 data["choices"][0]["message"]["content"] 的 KeyError：先解析内容再记账。
            throw new ModelProtocolException("上游响应缺少 choices[0].message.content");
        }
        return new RawChatResult(content.asText(), rawUsage(root.get("usage")));
    }

    // ------------------------------------------------------------------
    // 流式 stream_chat
    // ------------------------------------------------------------------

    /**
     * 富接口流式 chat：增量文本按「读取边界」逐段下发（读取边界 ≠ SSE 帧边界），
     * usage 尾帧只进账本不产生空 delta。消费方提前取消时停止读取（尾帧到不了 →
     * 账记 unknown），请求已发出的花费不丢账。流式不做传输层重试，仅保留
     * stream_options 被拒后去掉字段重发一次的源行为。
     */
    public Flow.Publisher<ModelDelta> streamChat(
            List<ChatMessage> messages, String model, ModelUsageLedger usage, String stage) {
        return subscriber -> {
            StreamSubscription subscription = new StreamSubscription();
            subscriber.onSubscribe(subscription);
            io.execute(() -> runStream(subscriber, subscription, messages, model, usage, stage));
        };
    }

    /** 终止读取的内部信号（订阅已取消，无需再向下游投递）。 */
    private static final class StopStreamSignal extends RuntimeException {
    }

    /** 最小 Subscription：需求到 0 时阻塞等待，取消后停止投递并停止读取。 */
    private static final class StreamSubscription implements Flow.Subscription {
        private final AtomicLong demand = new AtomicLong();
        private final AtomicBoolean cancelled = new AtomicBoolean();

        @Override
        public void request(long n) {
            if (n <= 0) {
                return; // 非正需求视为无变化（本切片不实现规则 3.9 报错）
            }
            demand.accumulateAndGet(n, (current, add) ->
                    current + add < 0 ? Long.MAX_VALUE : current + add);
            synchronized (this) {
                notifyAll();
            }
        }

        @Override
        public void cancel() {
            cancelled.set(true);
            synchronized (this) {
                notifyAll();
            }
        }

        boolean cancelled() {
            return cancelled.get();
        }

        void emitOne(ModelDelta delta, java.util.function.Consumer<ModelDelta> deliver) {
            synchronized (this) {
                while (demand.get() == 0 && !cancelled.get()) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new StopStreamSignal();
                    }
                }
                if (cancelled.get()) {
                    throw new StopStreamSignal();
                }
                demand.decrementAndGet();
            }
            deliver.accept(delta);
        }
    }

    private void runStream(
            Flow.Subscriber<? super ModelDelta> subscriber, StreamSubscription subscription,
            List<ChatMessage> messages, String model, ModelUsageLedger usage, String stage) {
        long started = System.nanoTime();
        AtomicInteger calls = new AtomicInteger();
        AtomicBoolean recorded = new AtomicBoolean();
        AtomicReference<Map<String, Object>> usageSink = new AtomicReference<>();
        try {
            Map<String, Object> payload = basePayload(messages, model, true);
            calls.set(1);
            try {
                pumpStream(payload, subscription, subscriber, usageSink);
            } catch (ModelHttpException first) {
                // 上游不认 stream_options：核对 400 正文确实指向该字段后去掉重来一次；
                // 其他 400（如上下文超长）照旧抛出，不给真实错误白送一次上游调用。
                if (!payload.containsKey("stream_options") || first.status() != 400
                        || !rejectedStreamOptions(first.body())) {
                    throw first;
                }
                log.warn("llm_stream_options_rejected status=400");
                payload.remove("stream_options");
                calls.set(2);
                pumpStream(payload, subscription, subscriber, usageSink);
            }
            if (!subscription.cancelled()) {
                subscriber.onComplete();
            }
        } catch (StopStreamSignal cancelledByConsumer) {
            // 消费方提前取消：读取已停止；finally 以当前 sink（无尾帧 → null）记账。
        } catch (Throwable error) {
            if (!subscription.cancelled()) {
                subscriber.onError(error);
            }
        } finally {
            recordOnce(usage, stage, model, usageSink.get(), elapsedMs(started),
                    Math.max(calls.get(), 1), ModelUsageLedger.KIND_LLM, recorded);
        }
    }

    private void pumpStream(
            Map<String, Object> payload, StreamSubscription subscription,
            Flow.Subscriber<? super ModelDelta> subscriber,
            AtomicReference<Map<String, Object>> usageSink) {
        restClient.post()
                .uri(settings.chatCompletionsUrl())
                .headers(this::authorize)
                .body(payload)
                .exchange((request, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw httpError(response);
                    }
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (subscription.cancelled()) {
                                return null; // 停止读取：usage 尾帧不会被消费
                            }
                            if (!line.startsWith("data:")) {
                                continue;
                            }
                            String raw = line.substring("data:".length()).strip();
                            if (raw.equals("[DONE]")) {
                                return null;
                            }
                            JsonNode data;
                            try {
                                data = JSON.readTree(raw);
                            } catch (JsonProcessingException malformedFrame) {
                                continue; // 坏帧跳过（源行为）
                            }
                            JsonNode frameUsage = data.get("usage");
                            if (frameUsage != null && frameUsage.isObject()) {
                                usageSink.set(rawUsage(frameUsage));
                            }
                            String delta = extractDelta(data);
                            if (!delta.isEmpty()) {
                                subscription.emitOne(new ModelDelta(delta), subscriber::onNext);
                            }
                        }
                    }
                    return null;
                });
    }

    /** 对位 _rejected_stream_options：400 正文指向 stream_options/include_usage 才重试。 */
    private static boolean rejectedStreamOptions(String errorBody) {
        String lowered = errorBody.toLowerCase();
        return lowered.contains("stream_options") || lowered.contains("include_usage");
    }

    /** 对位 choices[0].delta.content：缺帧/空帧一律空串，不产生空 delta。 */
    private static String extractDelta(JsonNode data) {
        JsonNode deltaNode = data.path("choices").path(0).path("delta").path("content");
        return deltaNode.isTextual() ? deltaNode.asText() : "";
    }

    // ------------------------------------------------------------------
    // 共享工具
    // ------------------------------------------------------------------

    private void authorize(HttpHeaders headers) {
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + settings.apiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);
    }

    private Map<String, Object> basePayload(List<ChatMessage> messages, String model, boolean stream) {
        ChatClientSettings.requireConfig(settings.baseUrl(), settings.apiKey(), "LLM");
        List<Map<String, String>> serialized = new ArrayList<>();
        if (messages != null) {
            for (ChatMessage m : messages) {
                Map<String, String> item = new LinkedHashMap<>();
                item.put("role", m.role());
                item.put("content", m.content());
                serialized.add(item);
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", serialized);
        payload.put("temperature", TEMPERATURE);
        payload.put("max_tokens", settings.maxTokens());
        if (stream) {
            payload.put("stream", true);
            if (settings.streamUsageEnabled()) {
                // 流式默认不带 usage，要显式索取；尾帧是 choices 为空、只带 usage 的帧。
                Map<String, Object> streamOptions = new LinkedHashMap<>();
                streamOptions.put("include_usage", true);
                payload.put("stream_options", streamOptions);
            }
        }
        return payload;
    }

    private static Map<String, Object> rawUsage(JsonNode usageNode) {
        if (usageNode == null || !usageNode.isObject()) {
            return null;
        }
        return JSON.convertValue(usageNode, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
        });
    }

    private static JsonNode parseJson(String body) {
        try {
            JsonNode root = JSON.readTree(body == null ? "" : body);
            if (root == null || root.isNull()) {
                throw new ModelProtocolException("上游响应体为空或 null");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new ModelProtocolException("上游响应体不是合法 JSON", e);
        }
    }

    private static String readBody(ClientHttpResponse response) throws IOException {
        try (InputStream in = response.getBody()) {
            return new String(in.readNBytes(MAX_ERROR_BODY), StandardCharsets.UTF_8);
        }
    }

    private static ModelHttpException httpError(ClientHttpResponse response) throws IOException {
        int status = response.getStatusCode().value();
        String body = new String(response.getBody().readNBytes(MAX_ERROR_BODY), StandardCharsets.UTF_8);
        String retryAfter = response.getHeaders().getFirst("Retry-After");
        Double retryAfterSeconds = null;
        if (retryAfter != null) {
            try {
                retryAfterSeconds = Double.parseDouble(retryAfter);
            } catch (NumberFormatException ignored) {
                retryAfterSeconds = null;
            }
        }
        return new ModelHttpException(status, body, retryAfterSeconds);
    }

    /** 只记一次账：成功记上游 usage；失败/取消记 unknown。CAS 防止取消与背景完成双记。 */
    private static void recordOnce(
            ModelUsageLedger usage, String stage, String model, Map<String, Object> rawUsage,
            Long durationMs, int calls, String kind, AtomicBoolean recorded) {
        if (usage == null) {
            return;
        }
        if (recorded.compareAndSet(false, true)) {
            usage.recordLlm(stage, model, rawUsage, durationMs, kind, calls);
        }
    }
}
