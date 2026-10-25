package io.dpdk.assistant.platform.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.dpdk.assistant.Application;
import io.dpdk.assistant.platform.pg.TestPg;
import io.dpdk.assistant.task.TaskTestHarness;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;

/** 启动真实嵌入式服务并走HTTP；仅模型在本机HTTP替身边界。 */
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class ProductApplicationHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    void realApplicationWiresChatReplaySseAndDisabledUpstream() throws Exception {
        TaskTestHarness h = TaskTestHarness.create("apphttp");
        HttpServer fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        fake.createContext("/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"fixed answer\"}}],\"usage\":{\"total_tokens\":2}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        fake.start();
        try {
            Map<String, Object> settings = new LinkedHashMap<>();
            settings.put("server.port", "0");
            settings.put("AUTH_MODE", "local");
            settings.put("dpdk.datasource.url", TestPg.url().substring(0, TestPg.url().lastIndexOf('/') + 1) + h.database);
            settings.put("dpdk.datasource.username", TestPg.user());
            settings.put("dpdk.datasource.password", TestPg.password());
            settings.put("dpdk.database-identity.expected-database", h.database);
            settings.put("dpdk.database-identity.expected-user", TestPg.user());
            settings.put("dpdk.database-identity.expected-comment", TestPg.COMMENT);
            settings.put("dpdk.database-identity.expected-data-dir", System.getenv("DPDK_TEST_PG_DATA_DIR"));
            settings.put("dpdk.migration.run-on-startup", "false");
            // 无配置启动不能偷偷回退真实上游。
            try (var ctx = start(settings)) {
                int port = ((WebServerApplicationContext) ctx).getWebServer().getPort();
                assertThat(post(port, "/api/chat", "{\"message\":\"hello\"}").statusCode()).isEqualTo(503);
                assertThat(h.countRows("rag_requests", "1=1")).isZero();
            }
            settings.put("dpdk.model.chat.base-url", "http://127.0.0.1:" + fake.getAddress().getPort() + "/v1");
            settings.put("dpdk.model.chat.api-key", "fixed-local-test-key");
            settings.put("dpdk.model.chat.model-name", "fixed-model");
            settings.put("dpdk.model.embedding.base-url", "http://127.0.0.1:" + fake.getAddress().getPort() + "/v1");
            settings.put("dpdk.model.embedding.api-key", "fixed-local-embedding-key");
            settings.put("dpdk.model.embedding.model-name", "fixed-embedding");
            try (var ctx = start(settings)) {
                int port = ((WebServerApplicationContext) ctx).getWebServer().getPort();
                String request = UUID.randomUUID().toString();
                String body = JSON.writeValueAsString(Map.of("message", "hello", "request_id", request));
                HttpResponse<String> response = post(port, "/api/chat", body);
                assertThat(response.statusCode()).isEqualTo(200);
                var first = JSON.readTree(response.body());
                assertThat(first.path("answer_status").asText()).isEqualTo("unanswered");
                assertThat(first.path("answer_reason").asText()).isEqualTo("no_evidence");
                assertThat(first.path("message_id").asText()).isNotBlank();
                int after = calls.get();
                HttpResponse<String> replay = post(port, "/api/chat", body);
                assertThat(replay.statusCode()).isEqualTo(200);
                // 冻结Python rag_product_service.py:232/652：即时结果有继续提问，
                // DB重建next_steps为空；其余字段逐项完全比较，不忽略业务差异。
                var expectedReplay = first.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) expectedReplay).set("next_steps", JSON.createArrayNode());
                assertThat(JSON.readTree(replay.body())).isEqualTo(expectedReplay);
                assertThat(calls.get()).isEqualTo(after);
                String task = first.path("task_id").asText();
                HttpResponse<String> events = HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/tasks/" + task + "/events")).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(events.statusCode()).isEqualTo(200);
                assertThat(events.body()).startsWith("event: start\ndata: ").contains("event: done\ndata: ").endsWith("\n\n").doesNotContain("\r");
                assertThat(calls.get()).isEqualTo(after);
                assertThat(h.countRows("rag_requests", "id=?", task)).isEqualTo(1);
                assertThat(h.countRows("rag_task_inputs", "task_id=?", task)).isZero();
                java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
                while (root != null && !java.nio.file.Files.exists(root.resolve("frontend/src/api/client.js"))) { root = root.getParent(); }
                assertThat(root).isNotNull();
                Process client = new ProcessBuilder("node", root.resolve("backend-java/src/test/resources/migration/J3-03/frontend-client.mjs").toString(),
                        "http://127.0.0.1:" + port, root.toString()).redirectErrorStream(true).start();
                boolean finished = client.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                if (!finished) { client.destroyForcibly(); }
                String output = new String(client.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(finished).as("前端原客户端进程超时").isTrue();
                assertThat(client.exitValue()).as(output).isZero();
                assertThat(output).contains("FRONTEND_CLIENT_CONTRACT=PASS");
            }
        } finally { fake.stop(0); h.drop(); }
    }

    private static HttpResponse<String> post(int port, String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static org.springframework.context.ConfigurableApplicationContext start(Map<String, Object> settings) {
        return new SpringApplicationBuilder(Application.class).initializers(ctx ->
                ctx.getEnvironment().getPropertySources().addFirst(
                        new org.springframework.core.env.MapPropertySource("isolated-http-test", settings))).run();
    }
}
