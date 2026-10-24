package io.dpdk.assistant.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.model.EmbeddingInput;
import io.dpdk.assistant.contracts.model.EmbeddingPort;
import io.dpdk.assistant.contracts.model.Vector1024;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * OpenAI 兼容 embedding 客户端（对齐 backend/app/core/llm_client.py 的 embed）。
 *
 * <p>关键语义：
 * <ul>
 *   <li>批量分批（clamp 1..10），每批独立尝试/重试/记账（前几批已完成的花费不受后面失败影响）；</li>
 *   <li>重试优先 Retry-After 数值秒，否则指数退避 + 均匀抖动 uniform(0, min(0.5, delay*0.25))；</li>
 *   <li>usage 在拿到结果后才记；重试后仍失败记该批 unknown；每批只记一笔，callCount 保留尝试数；</li>
 *   <li>响应 data 按 index 排序；数量不匹配视为协议错误（不可重试）；</li>
 *   <li>空输入不发任何请求、不记账；维度校验交给 Vector1024 契约（1024 冻结）。</li>
 * </ul>
 */
public final class OpenAiCompatibleEmbeddingClient implements EmbeddingPort {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_ERROR_BODY = 64 * 1024;

    private final EmbeddingClientSettings settings;
    private final UpstreamGate gate;
    private final RestClient restClient;
    private final ExecutorService io;
    private final boolean ownedExecutor;

    public OpenAiCompatibleEmbeddingClient(EmbeddingClientSettings settings, UpstreamGate gate) {
        this(settings, gate,
                OpenAiCompatibleChatClient.defaultRestClient(settings.requestTimeoutSeconds()),
                Executors.newCachedThreadPool(OpenAiCompatibleEmbeddingClient::ioThread),
                true);
    }

    OpenAiCompatibleEmbeddingClient(
            EmbeddingClientSettings settings, UpstreamGate gate, RestClient restClient,
            ExecutorService io, boolean ownedExecutor) {
        this.settings = settings;
        this.gate = gate;
        this.restClient = restClient;
        this.io = io;
        this.ownedExecutor = ownedExecutor;
    }

    private static Thread ioThread(Runnable runnable) {
        Thread t = new Thread(runnable, "dpdk-embedding-io");
        t.setDaemon(true);
        return t;
    }

    public void close() {
        if (ownedExecutor) {
            io.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // 契约端口适配：单文本 → 单个 1024 维向量
    // ------------------------------------------------------------------

    @Override
    public List<Vector1024> embed(EmbeddingInput input) {
        String text = input == null ? "" : input.input();
        List<float[]> vectors = embedTexts(List.of(text == null ? "" : text), null, "");
        List<Vector1024> out = new ArrayList<>(vectors.size());
        for (float[] v : vectors) {
            out.add(new Vector1024(v));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // 富接口：多文本分批嵌入（对位 OpenAICompatibleClient.embed）
    // ------------------------------------------------------------------

    /** 单批原始结果：向量 + 上游 usage。 */
    public record BatchResult(List<float[]> vectors, Map<String, Object> rawUsage) {
    }

    public List<float[]> embedTexts(List<String> texts, ModelUsageLedger usage, String stage) {
        ChatClientSettings.requireConfig(settings.baseUrl(), settings.apiKey(), "EMBEDDING");
        if (texts == null || texts.isEmpty()) {
            return List.of(); // 空输入：不发请求、不记账
        }
        int batch = settings.effectiveBatchSize();
        List<float[]> vectors = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += batch) {
            List<String> slice = texts.subList(start, Math.min(start + batch, texts.size()));
            vectors.addAll(embedBatch(slice, usage, stage));
        }
        return vectors;
    }

    private List<float[]> embedBatch(List<String> batch, ModelUsageLedger usage, String stage) {
        long started = System.nanoTime();
        int[] calls = {0};
        AtomicBoolean recorded = new AtomicBoolean();
        try {
            for (int attempt = 0; ; attempt++) {
                calls[0] = attempt + 1;
                try {
                    BatchResult result = exchangeOnce(batch);
                    // usage 在真正拿到结果后才记；calls 让「重试过」在账面上可见。
                    recordBatch(usage, stage, result.rawUsage(), started, calls[0], recorded);
                    return result.vectors();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ModelCallCancelledException(
                            "等待 embedding 响应时被取消；已发送请求的远端结果未知，账已按 unknown 记录");
                } catch (RuntimeException failure) {
                    Double retryAfter = failure instanceof ModelHttpException http
                            ? http.retryAfterSeconds() : null;
                    if (attempt >= settings.maxRetries()
                            || !ModelFailureClassifier.isRetriable(failure)) {
                        // 重试后仍失败：上游可能已消耗算力但没有可用 usage —— 记未知，不记 0。
                        throw failure; // finally 补该批 unknown 账
                    }
                    double delay = ModelFailureClassifier.embeddingRetryDelaySeconds(
                            attempt, settings.retryBaseSeconds(), retryAfter,
                            settings.jitter().getAsDouble());
                    try {
                        Thread.sleep((long) (delay * 1000.0));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new ModelCallCancelledException(
                                "embedding 退避等待中被取消；已发送请求的远端结果未知，账已按 unknown 记录");
                    }
                }
            }
        } finally {
            recordBatch(usage, stage, null, started, Math.max(calls[0], 1), recorded);
        }
    }

    private void recordBatch(
            ModelUsageLedger usage, String stage, Map<String, Object> rawUsage,
            long started, int calls, AtomicBoolean recorded) {
        if (usage == null) {
            return;
        }
        if (recorded.compareAndSet(false, true)) {
            usage.recordLlm(stage, settings.modelName(), rawUsage,
                    Math.max((System.nanoTime() - started) / 1_000_000L, 0L),
                    ModelUsageLedger.KIND_EMBEDDING, calls);
        }
    }

    /** 单批一次上游交换：背景 IO 线程 + 并发门许可；取消语义同 chat。 */
    private BatchResult exchangeOnce(List<String> batch) throws InterruptedException {
        AtomicBoolean abandoned = new AtomicBoolean(false);
        CompletableFuture<BatchResult> task = new CompletableFuture<>();
        Future<?> submitted = io.submit(() -> {
            if (abandoned.get()) {
                return;
            }
            try (UpstreamGate.Permit ignored = gate.acquire()) {
                if (abandoned.get()) {
                    return;
                }
                task.complete(postEmbeddings(batch));
            } catch (Throwable t) {
                task.completeExceptionally(t instanceof RuntimeException runtime ? runtime
                        : new ModelProtocolException("embedding 上游交换失败", t));
            }
        });
        try {
            return task.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ModelProtocolException("embedding 上游交换失败", cause);
        } catch (InterruptedException e) {
            abandoned.set(true);
            submitted.cancel(false);
            throw e;
        }
    }

    private BatchResult postEmbeddings(List<String> batch) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", settings.modelName());
        payload.put("input", batch);
        return restClient.post()
                .uri(settings.embeddingsUrl())
                .headers(this::authorize)
                .body(payload)
                .exchange((request, response) -> handleEmbeddingsResponse(response, batch));
    }

    private void authorize(HttpHeaders headers) {
        headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + settings.apiKey());
        headers.setContentType(MediaType.APPLICATION_JSON);
    }

    private BatchResult handleEmbeddingsResponse(ClientHttpResponse response, List<String> batch)
            throws IOException {
        if (response.getStatusCode().isError()) {
            throw httpError(response);
        }
        JsonNode root = parseJson(readBody(response));
        List<JsonNode> items = new ArrayList<>();
        root.path("data").forEach(items::add); // data 缺失 → 空列表 → 数量不匹配
        items.sort(Comparator.comparingInt(OpenAiCompatibleEmbeddingClient::indexOf));
        List<float[]> vectors = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            JsonNode embedding = item.get("embedding");
            if (embedding == null || !embedding.isArray()) {
                throw new ModelProtocolException("embedding 条目缺少 embedding 数组");
            }
            float[] vector = new float[embedding.size()];
            for (int i = 0; i < embedding.size(); i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            vectors.add(vector);
        }
        if (vectors.size() != batch.size()) {
            throw new ModelProtocolException(
                    "Embedding count mismatch: expected " + batch.size() + ", got " + vectors.size());
        }
        Map<String, Object> rawUsage = null;
        JsonNode usageNode = root.get("usage");
        if (usageNode != null && usageNode.isObject()) {
            rawUsage = JSON.convertValue(usageNode, new com.fasterxml.jackson.core.type.TypeReference<
                    Map<String, Object>>() {
            });
        }
        return new BatchResult(vectors, rawUsage);
    }

    /** 源 sorted(key=lambda item: item.get("index", 0))；非数字 index 按 0。 */
    private static int indexOf(JsonNode item) {
        JsonNode index = item.get("index");
        return index != null && index.isNumber() ? index.asInt() : 0;
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
}
