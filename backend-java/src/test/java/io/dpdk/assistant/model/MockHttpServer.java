package io.dpdk.assistant.model;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JDK 内置 HTTP 测试替身（零新依赖）。每个用例 start/close；handler 在线程池上执行，
 * 支持并发/挂起场景。记录请求计数与请求体快照。
 */
public final class MockHttpServer implements AutoCloseable {

    public interface Handler {
        void handle(HttpExchange exchange, RequestRecord request) throws Exception;
    }

    /** 一次请求的快照：方法、路径、请求头、请求体。 */
    public record RequestRecord(String method, String path, String body, String authorization,
            String contentType, String sessionHeader) {
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final Handler handler;
    public final List<RequestRecord> requests = new CopyOnWriteArrayList<>();
    public final AtomicInteger active = new AtomicInteger();
    public final AtomicInteger maxActive = new AtomicInteger();

    private MockHttpServer(HttpServer server, ExecutorService executor, Handler handler) {
        this.server = server;
        this.executor = executor;
        this.handler = handler;
    }

    public static MockHttpServer start(Handler handler) {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool();
            MockHttpServer mock = new MockHttpServer(server, executor, handler);
            server.createContext("/", exchange -> {
                RequestRecord record = mock.record(exchange);
                mock.requests.add(record);
                int now = mock.active.incrementAndGet();
                mock.maxActive.accumulateAndGet(now, Math::max);
                try {
                    mock.handler.handle(exchange, record);
                } catch (IOException io) {
                    throw io;
                } catch (Exception other) {
                    throw new IllegalStateException("测试替身内部错误", other);
                } finally {
                    mock.active.decrementAndGet();
                    exchange.close();
                }
            });
            server.setExecutor(executor);
            server.start();
            return mock;
        } catch (IOException e) {
            throw new IllegalStateException("无法启动本地测试 HTTP 服务", e);
        }
    }

    private RequestRecord record(HttpExchange exchange) throws IOException {
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        List<String> auth = exchange.getRequestHeaders().get("Authorization");
        List<String> contentType = exchange.getRequestHeaders().get("Content-Type");
        List<String> session = exchange.getRequestHeaders().get("Mcp-Session-Id");
        return new RequestRecord(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getPath(),
                body,
                auth == null ? null : auth.get(0),
                contentType == null ? null : contentType.get(0),
                session == null ? null : session.get(0));
    }

    /** http://127.0.0.1:{port} */
    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public int requestCount() {
        return requests.size();
    }

    // ---- 响应工具 ----

    public static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    public static void respondText(HttpExchange exchange, int status, String contentType, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    /** chunked SSE：先建流，逐段写入并 flush。 */
    public static SseStream respondSse(HttpExchange exchange, int status) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(status, 0);
        return new SseStream(exchange.getResponseBody());
    }

    public static final class SseStream implements AutoCloseable {
        private final OutputStream out;
        private boolean closed;

        private SseStream(OutputStream out) {
            this.out = out;
        }

        public void send(String chunk) throws IOException {
            out.write(chunk.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        public void sendLine(String line) throws IOException {
            send(line + "\n");
        }

        public void sendDataFrame(String json) throws IOException {
            send("data: " + json + "\n\n");
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                out.close();
            }
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
