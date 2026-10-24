package io.dpdk.assistant.harness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.model.MockHttpServer;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * teaching_harness 的 MCP Streamable HTTP fake server（JDK HttpServer，零新依赖）。
 * 按 JSON-RPC method 路由：initialize / notifications/initialized / tools/call。
 * 可配置：建连/initialize 失败次数、tools/call 失败、SSE 响应模式、会话 id、结果形态。
 */
final class FakeMcpServer implements AutoCloseable {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** tools/call 的应答形态。 */
    enum ToolMode {
        STRUCTURED, TEXT_JSON_DICT, TEXT_NON_DICT_JSON, IS_ERROR, NON_JSON, HTTP_500, HANG_THEN_CLOSE
    }

    MockHttpServer server; // 在 start() 内回填（handler 需先引用 fake 自身）
    final AtomicInteger initializePosts = new AtomicInteger();
    final AtomicInteger notificationPosts = new AtomicInteger();
    final List<String> toolCalls = new CopyOnWriteArrayList<>();
    final List<String> sessionIdsSeen = new CopyOnWriteArrayList<>();

    // 行为开关
    volatile int failConnectRemaining;      // 以 503 拒绝 initialize 阶段
    volatile int failInitializeRemaining;   // 200 但缺 result（协议失败）
    volatile boolean sseMode;               // initialize 与 tools/call 都走 SSE data 帧
    volatile boolean sendSessionId = true;  // initialize 响应带 Mcp-Session-Id
    volatile ToolMode toolMode = ToolMode.STRUCTURED;

    private FakeMcpServer(MockHttpServer server) {
        this.server = server;
    }

    static FakeMcpServer start() {
        FakeMcpServer fake = new FakeMcpServer(null);
        MockHttpServer started = MockHttpServer.start((exchange, request) -> {
            try {
                fake.route(exchange, request);
            } catch (IOException io) {
                throw io; // HttpHandler 只允许 IOException
            } catch (Exception other) {
                throw new IllegalStateException("fake harness 内部错误", other);
            }
        });
        fake.server = started;
        return fake;
    }

    private void route(com.sun.net.httpserver.HttpExchange exchange,
            MockHttpServer.RequestRecord request) throws Exception {
        JsonNode body = JSON.readTree(request.body());
        String method = body.path("method").asText("");
        switch (method) {
            case "initialize" -> {
                initializePosts.incrementAndGet();
                if (request.sessionHeader() != null) {
                    sessionIdsSeen.add(request.sessionHeader());
                }
                handleInitialize(exchange);
            }
            case "notifications/initialized" -> {
                notificationPosts.incrementAndGet();
                if (request.sessionHeader() != null) {
                    sessionIdsSeen.add(request.sessionHeader());
                }
                exchange.sendResponseHeaders(202, -1);
            }
            case "tools/call" -> {
                toolCalls.add(body.path("params").path("name").asText());
                if (request.sessionHeader() != null) {
                    sessionIdsSeen.add(request.sessionHeader());
                }
                handleToolCall(exchange, body);
            }
            default -> MockHttpServer.respondJson(exchange, 404,
                    "{\"error\":\"unknown method\"}");
        }
    }

    private void handleInitialize(com.sun.net.httpserver.HttpExchange exchange) throws Exception {
        if (failConnectRemaining > 0) {
            failConnectRemaining--;
            MockHttpServer.respondJson(exchange, 503, "{\"error\":\"starting up\"}");
            return;
        }
        if (failInitializeRemaining > 0) {
            failInitializeRemaining--;
            MockHttpServer.respondJson(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":1}");
            return;
        }
        String result = "{\"serverInfo\":{\"name\":\"fake-harness\",\"version\":\"1.0\"},"
                + "\"capabilities\":{},\"protocolVersion\":\"2025-06-18\"}";
        if (sendSessionId) {
            exchange.getResponseHeaders().set("Mcp-Session-Id", "sess-1");
        }
        if (sseMode) {
            MockHttpServer.SseStream sse = MockHttpServer.respondSse(exchange, 200);
            sse.sendDataFrame("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}");
            sse.close();
        } else {
            MockHttpServer.respondJson(exchange, 200,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":" + result + "}");
        }
    }

    private void handleToolCall(com.sun.net.httpserver.HttpExchange exchange, JsonNode request)
            throws Exception {
        JsonNode id = request.get("id");
        String resultJson = switch (toolMode) {
            case STRUCTURED -> "{\"structuredContent\":{\"ok\":true,\"protocol\":\"dns\",\"score\":62},"
                    + "\"content\":[{\"type\":\"text\",\"text\":\"{\\\"ok\\\":false}\"}]}";
            case TEXT_JSON_DICT -> "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"ok\\\":true,\\\"score\\\":100}\"}]}";
            case TEXT_NON_DICT_JSON -> "{\"content\":[{\"type\":\"text\",\"text\":\"42\"}]}";
            case IS_ERROR -> "{\"isError\":true,\"content\":[{\"type\":\"text\",\"text\":\"{\\\"error\\\":\\\"boom\\\"}\"}]}";
            case NON_JSON -> "{\"content\":[{\"type\":\"text\",\"text\":\"<not json>\"}]}";
            default -> null;
        };
        if (toolMode == ToolMode.HTTP_500) {
            MockHttpServer.respondJson(exchange, 500, "{\"error\":\"internal\"}");
            return;
        }
        if (toolMode == ToolMode.HANG_THEN_CLOSE) {
            Thread.sleep(300);
            exchange.sendResponseHeaders(500, -1);
            return;
        }
        String message = "{\"jsonrpc\":\"2.0\",\"id\":" + id.asInt() + ",\"result\":" + resultJson + "}";
        if (sseMode) {
            MockHttpServer.SseStream sse = MockHttpServer.respondSse(exchange, 200);
            sse.sendDataFrame(message);
            sse.close();
        } else {
            MockHttpServer.respondJson(exchange, 200, message);
        }
    }

    String url() {
        return server.baseUrl() + "/mcp";
    }

    @Override
    public void close() {
        server.close();
    }
}
