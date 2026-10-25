package io.dpdk.assistant.assignment.submission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * fake MCP server（JDK HttpServer，Streamable HTTP 形态）：initialize/initialized/
 * tools/call 三步；按脚本应答并可注入时延。只记录调用计数，不记录、不打印
 * plugin_source（敏感纪律：账本只存身份/计数）。
 */
public final class FakeMcpServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    public final AtomicInteger initializes = new AtomicInteger();
    public final AtomicInteger toolCalls = new AtomicInteger();

    /** tools/call 行为脚本。 */
    public enum ToolMode {
        SUCCESS, HTTP_500, SLEEP_FOREVER
    }

    private volatile ToolMode toolMode = ToolMode.SUCCESS;
    private volatile String toolPayload = SubmissionTestHarness.defaultGradePayload(
            "dns", 80, 4, 5, "[]");
    private volatile long initializeDelayMillis;
    private HttpServer server;

    public String start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    public void setToolMode(ToolMode mode) {
        this.toolMode = mode;
    }

    public void setToolPayload(String payloadJson) {
        this.toolPayload = payloadJson;
    }

    public void setInitializeDelayMillis(long millis) {
        this.initializeDelayMillis = millis;
    }

    private void handle(HttpExchange exchange) throws IOException {
        byte[] body = exchange.getRequestBody().readAllBytes();
        try {
            ObjectNode request = (ObjectNode) JSON.readTree(
                    new String(body, StandardCharsets.UTF_8));
            String method = request.path("method").asText("");
            switch (method) {
                case "initialize" -> {
                    initializes.incrementAndGet();
                    if (initializeDelayMillis > 0) {
                        try {
                            Thread.sleep(initializeDelayMillis);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    ObjectNode result = JSON.createObjectNode();
                    result.putObject("capabilities");
                    result.putObject("serverInfo").put("name", "fake-harness")
                            .put("version", "0.0.0");
                    respond(exchange, 200, rpcResult(request, result),
                            "fake-session-" + initializes.get());
                }
                case "notifications/initialized" -> respond(exchange, 200, "{}", null);
                case "tools/call" -> {
                    toolCalls.incrementAndGet();
                    switch (toolMode) {
                        case SUCCESS -> {
                            ObjectNode result = JSON.createObjectNode();
                            result.putObject("structuredContent").setAll(
                                    (ObjectNode) JSON.readTree(toolPayload));
                            result.put("isError", false);
                            respond(exchange, 200, rpcResult(request, result), null);
                        }
                        case HTTP_500 -> respond(exchange, 500, "{}", null);
                        case SLEEP_FOREVER -> {
                            try {
                                Thread.sleep(120_000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            respond(exchange, 200, "{}", null);
                        }
                    }
                }
                default -> respond(exchange, 400, "{}", null);
            }
        } catch (IOException parse) {
            respond(exchange, 400, "{}", null);
        } finally {
            exchange.close();
        }
    }

    private static String rpcResult(ObjectNode request, ObjectNode result) {
        ObjectNode response = JSON.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", request.get("id"));
        response.set("result", result);
        return response.toString();
    }

    private static void respond(HttpExchange exchange, int status, String body, String sessionId)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        if (sessionId != null) {
            exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
        }
        exchange.sendResponseHeaders(status, body.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }
}
