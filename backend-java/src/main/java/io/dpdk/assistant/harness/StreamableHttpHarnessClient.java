package io.dpdk.assistant.harness;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dpdk.assistant.contracts.model.Deadline;
import io.dpdk.assistant.contracts.model.HarnessPort;
import io.dpdk.assistant.contracts.model.HarnessReply;
import io.dpdk.assistant.contracts.model.HarnessReply.SubmissionPhase;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * teaching_harness MCP Streamable HTTP 客户端（对齐 backend/app/agent/mcp_client.py）。
 *
 * <p>失败分类（契约 model.HarnessPort 语义）：
 * <ul>
 *   <li>工具参数无法序列化：字节尚未发出 → NOT_SUBMITTED（harness_bad_request）。
 *       Java 侧在任何 HTTP 建连之前完成序列化，因而不存在「参数畸形却已提交」的窗口
 *       （源实现把序列化算在 call_tool 内，会落入已提交分支——本实现更保守）；</li>
 *   <li>建连/initialize 阶段失败：最多重试 2 次（退避 0.5s * 2^n），仍失败 →
 *       NOT_SUBMITTED（harness_unavailable）；</li>
 *   <li>deadline 在任何 tools/call 字节发出前到期 → NOT_SUBMITTED
 *       （harness_deadline_exceeded）；deadline 不中断已发出的请求，单请求上限由
 *       requestTimeout 决定；</li>
 *   <li>进入 tools/call 提交阶段后的任何失败 → UNKNOWN（harness_unavailable），
 *       绝不重发（已提交的评测重试会重复消耗配额与工时）。</li>
 * </ul>
 *
 * <p>每次调用建立新传输（initialize → notifications/initialized → tools/call 各为一次
 * POST，Mcp-Session-Id 透传），用完即关；不做连接池，也不宣称长连接语义。响应体兼容
 * application/json（单响应）与 text/event-stream（data 帧）两种 Streamable HTTP 形态。
 * 所有传输/协议层异常都收敛为结构化 {@code {"ok": false, ...}}，不向业务抛穿。
 */
public final class StreamableHttpHarnessClient implements HarnessPort {

    private static final Logger log = LoggerFactory.getLogger(StreamableHttpHarnessClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BODY = 1024 * 1024;

    /** 源常量：建连/initialize 重试次数（不含首次）与退避基数。 */
    static final int CONNECT_RETRIES = 2;
    static final double CONNECT_RETRY_BASE_SECONDS = 0.5;

    private final String url;
    private final int connectRetries;
    private final double backoffBaseSeconds;
    private final Duration requestTimeout;

    public StreamableHttpHarnessClient(String url) {
        this(url, CONNECT_RETRIES, CONNECT_RETRY_BASE_SECONDS, Duration.ofSeconds(30));
    }

    /** 测试可注入更小的退避/超时；默认值与源常量一致。 */
    StreamableHttpHarnessClient(String url, int connectRetries, double backoffBaseSeconds,
            Duration requestTimeout) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("harness MCP URL 不能为空");
        }
        this.url = url;
        this.connectRetries = connectRetries;
        this.backoffBaseSeconds = backoffBaseSeconds;
        this.requestTimeout = requestTimeout;
    }

    String url() {
        return url;
    }

    // ------------------------------------------------------------------
    // 契约端口
    // ------------------------------------------------------------------

    @Override
    public HarnessReply call(String tool, Map<String, Object> args, Deadline deadline) {
        // 参数序列化先于任何字节发出：失败即 NOT_SUBMITTED，不重试、不建连。
        JsonNode arguments;
        try {
            arguments = JSON.valueToTree(args == null ? Map.of() : args);
            JSON.writeValueAsString(arguments); // 触发实际序列化以暴露不可序列化值
        } catch (RuntimeException | JsonProcessingException e) {
            log.warn("mcp_bad_request tool={} reason=arguments_not_serializable", tool);
            return failure(SubmissionPhase.NOT_SUBMITTED, "harness_bad_request",
                    "工具参数无法序列化：" + e.getClass().getSimpleName());
        }
        return callTool(tool, arguments, deadline);
    }

    // ------------------------------------------------------------------
    // 核心流程
    // ------------------------------------------------------------------

    private HarnessReply callTool(String tool, JsonNode arguments, Deadline deadline) {
        for (int attempt = 0; ; attempt++) {
            if (expired(deadline)) {
                return failure(SubmissionPhase.NOT_SUBMITTED, "harness_deadline_exceeded",
                        "截止期限已到，未提交任何 tools/call 请求");
            }
            RestClient transport = newTransport();
            String sessionId;
            try {
                sessionId = initialize(transport);
            } catch (Exception exc) {
                if (attempt >= connectRetries) {
                    warnFailed(tool, exc, attempt + 1, false);
                    return failure(SubmissionPhase.NOT_SUBMITTED, "harness_unavailable",
                            unavailableDetail(exc));
                }
                log.warn("mcp_call_retry tool={} error_type={} attempt={}",
                        tool, exc.getClass().getSimpleName(), attempt + 1);
                if (!sleepQuietly(backoffBaseSeconds * Math.pow(2.0, attempt))) {
                    return failure(SubmissionPhase.NOT_SUBMITTED, "harness_unavailable",
                            unavailableDetail(exc));
                }
                continue;
            }
            // initialize 完成：即将进入提交阶段。此后任何失败都是「已提交，结果未知」。
            if (expired(deadline)) {
                // tools/call 字节尚未发出，如实记 NOT_SUBMITTED。
                return failure(SubmissionPhase.NOT_SUBMITTED, "harness_deadline_exceeded",
                        "截止期限在 initialize 后、提交前到期");
            }
            try {
                JsonNode result = sendToolCall(transport, sessionId, tool, arguments);
                return success(tool, result);
            } catch (Exception exc) {
                warnFailed(tool, exc, attempt + 1, true);
                return failure(SubmissionPhase.UNKNOWN, "harness_unavailable", unavailableDetail(exc));
            }
        }
    }

    /** initialize 请求 + initialized 通知；返回服务端会话 id（可为 null）。 */
    private String initialize(RestClient transport) {
        ObjectNode request = JSON.createObjectNode();
        request.put("jsonrpc", "2.0").put("id", 1).put("method", "initialize");
        ObjectNode params = request.putObject("params");
        params.put("protocolVersion", "2025-06-18");
        params.putObject("capabilities");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "dpdk-assistant-java").put("version", "0.1.0");

        PostOutcome outcome = postJsonRpc(transport, request, null);
        if (!outcome.message().has("result") || !outcome.message().get("result").isObject()) {
            throw new McpTransportException("initialize 响应缺少 result");
        }
        ObjectNode notification = JSON.createObjectNode();
        notification.put("jsonrpc", "2.0").put("method", "notifications/initialized");
        postJsonRpc(transport, notification, outcome.sessionId());
        return outcome.sessionId();
    }

    private JsonNode sendToolCall(RestClient transport, String sessionId, String tool, JsonNode arguments) {
        ObjectNode request = JSON.createObjectNode();
        request.put("jsonrpc", "2.0").put("id", 2).put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", tool);
        params.set("arguments", arguments);
        PostOutcome outcome = postJsonRpc(transport, request, sessionId);
        JsonNode message = outcome.message();
        if (message.has("error")) {
            throw new McpTransportException("tools/call 返回 JSON-RPC error");
        }
        if (!message.has("result") || !message.get("result").isObject()) {
            throw new McpTransportException("tools/call 响应缺少 result");
        }
        return message.get("result");
    }

    // ------------------------------------------------------------------
    // Streamable HTTP 传输
    // ------------------------------------------------------------------

    /** 每次尝试新建传输（对位源「每次调用建立一次连接」；不承诺池化）。 */
    private RestClient newTransport() {
        java.net.http.HttpClient httpClient = java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(requestTimeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    private record PostOutcome(JsonNode message, String sessionId) {
    }

    /** 单次 JSON-RPC POST；响应兼容 JSON 体与 SSE data 帧；通知（无 id）不解析响应体。
     * 请求体显式按 UTF-8 字节写出：classpath 同时存在 Jackson 2/3，直接交给
     * RestClient 的节点转换可能按 POJO 展开，字节数组则原样上送。 */
    private PostOutcome postJsonRpc(RestClient transport, ObjectNode request, String sessionId) {
        byte[] wireBody = request.toString().getBytes(StandardCharsets.UTF_8);
        return transport.post()
                .uri(url)
                .headers(headers -> {
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    headers.setAccept(List.of(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM));
                    if (sessionId != null) {
                        headers.set("Mcp-Session-Id", sessionId);
                    }
                })
                .body(wireBody)
                .exchange((clientRequest, response) -> handleRpcResponse(request, response));
    }

    private PostOutcome handleRpcResponse(ObjectNode request, ClientHttpResponse response) throws IOException {
        if (response.getStatusCode().isError()) {
            throw new McpTransportException("HTTP " + response.getStatusCode().value());
        }
        String sessionId = response.getHeaders().getFirst("Mcp-Session-Id");
        if (!request.has("id")) {
            return new PostOutcome(JSON.createObjectNode(), sessionId); // 通知：响应体不期待内容
        }
        MediaType contentType = response.getHeaders().getContentType();
        JsonNode message;
        if (contentType != null && MediaType.TEXT_EVENT_STREAM.includes(contentType)) {
            message = readSseMessage(response, request.get("id"));
        } else {
            message = parseJsonBody(readBody(response));
        }
        return new PostOutcome(message, sessionId);
    }

    /** SSE：取与请求 id 匹配的 data 帧；无匹配时取最后一帧。 */
    private JsonNode readSseMessage(ClientHttpResponse response, JsonNode requestId) throws IOException {
        List<JsonNode> frames = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String raw = line.substring("data:".length()).strip();
                if (raw.isEmpty()) {
                    continue;
                }
                try {
                    frames.add(JSON.readTree(raw));
                } catch (JsonProcessingException ignoredFrame) {
                    // 坏帧跳过
                }
            }
        }
        JsonNode last = null;
        for (JsonNode frame : frames) {
            last = frame;
            if (frame.path("id").equals(requestId)) {
                return frame;
            }
        }
        if (last == null) {
            throw new McpTransportException("SSE 流中没有可用响应帧");
        }
        return last;
    }

    // ------------------------------------------------------------------
    // 结果规范化（对位 _parse_result）
    // ------------------------------------------------------------------

    private HarnessReply success(String tool, JsonNode result) {
        return new HarnessReply(SubmissionPhase.SUBMITTED, normalize(tool, result), null);
    }

    /** structuredContent → 文本 JSON → isError → 非 JSON 四分支按旧契约规范化为 JSON 字符串。 */
    private String normalize(String tool, JsonNode result) {
        JsonNode structured = result.get("structuredContent");
        if (structured != null && structured.isObject()) {
            return structured.toString();
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode item : result.path("content")) {
            JsonNode text = item.get("text");
            if (text != null && text.isTextual() && !text.asText().isEmpty()) {
                texts.add(text.asText());
            }
        }
        String payload = String.join("\n", texts);
        if (result.path("isError").asBoolean(false)) {
            return errorPayload("harness_tool_error", truncate(payload, 2000));
        }
        JsonNode parsed;
        try {
            parsed = payload.isEmpty() ? null : JSON.readTree(payload);
        } catch (JsonProcessingException badPayload) {
            parsed = null;
        }
        if (parsed == null) {
            // 日志不回显 payload（不得回显合成源码哨兵），只记长度。
            log.warn("mcp_bad_response tool={} payload_len={}", tool, payload.length());
            return errorPayload("harness_bad_response", truncate(payload, 2000));
        }
        if (parsed.isObject()) {
            return parsed.toString();
        }
        ObjectNode wrapped = JSON.createObjectNode();
        wrapped.put("ok", true);
        wrapped.set("data", parsed);
        return wrapped.toString();
    }

    private static String errorPayload(String error, String detail) {
        ObjectNode out = JSON.createObjectNode();
        out.put("ok", false);
        out.put("error", error);
        out.put("detail", detail);
        return out.toString();
    }

    private HarnessReply failure(SubmissionPhase phase, String error, String detail) {
        return new HarnessReply(phase, errorPayload(error, detail), error);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static String unavailableDetail(Exception exc) {
        return "评测服务不可达：" + exc.getClass().getSimpleName();
    }

    private void warnFailed(String tool, Exception exc, int attempts, boolean submitted) {
        log.warn("mcp_call_failed tool={} error_type={} attempts={} submitted={} detail_len={}",
                tool, exc.getClass().getSimpleName(), attempts, submitted,
                exc.getMessage() == null ? 0 : Math.min(exc.getMessage().length(), 500));
    }

    private static String truncate(String s, int limit) {
        return s.length() <= limit ? s : s.substring(0, limit);
    }

    private static boolean expired(Deadline deadline) {
        if (deadline == null) {
            return false;
        }
        if (deadline.monotonicNanosDeadline() > 0) {
            return System.nanoTime() >= deadline.monotonicNanosDeadline();
        }
        return deadline.wallDeadline() != null && Instant.now().isAfter(deadline.wallDeadline());
    }

    private boolean sleepQuietly(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000.0));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static JsonNode parseJsonBody(String body) {
        try {
            JsonNode root = JSON.readTree(body == null ? "" : body);
            if (root == null || root.isNull()) {
                throw new McpTransportException("MCP 响应体为空");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new McpTransportException("MCP 响应体不是合法 JSON", e);
        }
    }

    private static String readBody(ClientHttpResponse response) throws IOException {
        try (InputStream in = response.getBody()) {
            return new String(in.readNBytes(MAX_BODY), StandardCharsets.UTF_8);
        }
    }
}
