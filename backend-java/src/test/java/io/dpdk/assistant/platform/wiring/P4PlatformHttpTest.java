package io.dpdk.assistant.platform.wiring;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.dpdk.assistant.Application;
import io.dpdk.assistant.index.IndexService;
import io.dpdk.assistant.index.SourceSettings;
import io.dpdk.assistant.model.OpenAiCompatibleChatClient;
import io.dpdk.assistant.model.OpenAiCompatibleEmbeddingClient;
import io.dpdk.assistant.platform.config.ModelChatProperties;
import io.dpdk.assistant.platform.config.ModelEmbeddingProperties;
import io.dpdk.assistant.platform.pg.TestPg;
import io.dpdk.assistant.task.TaskTestHarness;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 完整嵌入式Spring与本机HTTP上游；不是MockMvc或只展开配置。 */
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class P4PlatformHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    @TempDir Path temp;

    @RestController
    static class SyntheticFailureEndpoint {
        @GetMapping("/__test/p4/failure")
        public Object failure() { throw new IllegalStateException("SYNTHETIC_PRIVATE_EXCEPTION_DETAIL"); }
    }

    static final class FakeUpstream implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger embeddingCalls = new AtomicInteger();
        FakeUpstream() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/embeddings", exchange -> {
                embeddingCalls.incrementAndGet();
                var input = JSON.readTree(exchange.getRequestBody().readAllBytes()).path("input");
                int count = input.isArray() ? input.size() : 1;
                List<Map<String, Object>> data = new ArrayList<>();
                List<Float> vector = new ArrayList<>();
                for (int i = 0; i < 1024; i++) vector.add(i == 0 ? 1f : 0f);
                for (int i = 0; i < count; i++) data.add(Map.of("index", i, "embedding", vector));
                respond(exchange, Map.of("data", data, "usage", Map.of("prompt_tokens", count, "total_tokens", count)));
            });
            server.createContext("/v1/chat/completions", exchange -> {
                exchange.getRequestBody().readAllBytes();
                respond(exchange, Map.of("choices", List.of(Map.of("message", Map.of("content", "synthetic answer"))),
                        "usage", Map.of("total_tokens", 1)));
            });
            server.start();
        }
        static void respond(com.sun.net.httpserver.HttpExchange exchange, Object value) throws java.io.IOException {
            byte[] body = JSON.writeValueAsBytes(value);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }
        String base() { return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1"; }
        @Override public void close() { server.stop(0); }
    }

    private static Map<String, Object> settings(TaskTestHarness h) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("server.port", "0"); s.put("server.address", "127.0.0.1"); s.put("AUTH_MODE", "local");
        s.put("dpdk.datasource.url", TestPg.url().substring(0, TestPg.url().lastIndexOf('/')+1)+h.database);
        s.put("dpdk.datasource.username", TestPg.user()); s.put("dpdk.datasource.password", TestPg.password());
        s.put("dpdk.database-identity.expected-database", h.database);
        s.put("dpdk.database-identity.expected-user", TestPg.user());
        s.put("dpdk.database-identity.expected-comment", TestPg.COMMENT);
        s.put("dpdk.database-identity.expected-data-dir", System.getenv("DPDK_TEST_PG_DATA_DIR"));
        s.put("dpdk.migration.run-on-startup", "false");
        return s;
    }

    private static ConfigurableApplicationContext start(Map<String, Object> settings, Map<String, Object> env) {
        return new SpringApplicationBuilder(Application.class, SyntheticFailureEndpoint.class).initializers(ctx -> {
            ctx.getEnvironment().getPropertySources().replace("systemEnvironment",
                    new SystemEnvironmentPropertySource("systemEnvironment", env));
            ctx.getEnvironment().getPropertySources().addFirst(new MapPropertySource("p4-http-db-settings", settings));
        }).run();
    }

    private static HttpResponse<String> request(int port, String path, boolean post) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10));
        if (post) b.POST(HttpRequest.BodyPublishers.ofString("{}")); else b.GET();
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void headers(HttpResponse<?> response) {
        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(response.headers().firstValue("Referrer-Policy")).contains("no-referrer");
        assertThat(response.headers().firstValue("Content-Security-Policy")).contains("default-src 'self'");
    }

    private static void sql(TaskTestHarness h, String text, Object... args) throws Exception {
        try (var c = h.ds.getConnection(); var ps = c.prepareStatement(text)) {
            for (int i=0; i<args.length; i++) ps.setObject(i+1, args[i]);
            ps.executeUpdate();
        }
    }

    private SourceSettings sources() throws Exception {
        Path dpdk = Files.createDirectories(temp.resolve("dpdk"));
        Path assistant = Files.createDirectories(temp.resolve("assistant"));
        Files.writeString(dpdk.resolve("base.md"), "# Base\nSynthetic DPDK packet flow.\n");
        Files.writeString(assistant.resolve("guide.md"), "# Guide\nSynthetic assistant documentation.\n");
        Path manifest = temp.resolve("sources.yml");
        Files.writeString(manifest, "sources:\n  - name: dpdk\n    root: ${DPDK_REPO_PATH}\n    include: [base.md]\n  - name: assistant\n    root: ${ASSISTANT_PROJECT_PATH}\n    include: [guide.md]\n");
        return new SourceSettings(manifest, dpdk, assistant, temp);
    }

    @Test
    void deploymentStyleEnvironmentCreatesTypedClientsInActualApplication() throws Exception {
        TaskTestHarness h = TaskTestHarness.create("p4envhttp");
        try (FakeUpstream fake = new FakeUpstream()) {
            Map<String, Object> env = Map.of("DPDK_MODEL_CHAT_BASE_URL", fake.base(),
                    "DPDK_MODEL_CHAT_API_KEY", "synthetic-chat-key", "DPDK_MODEL_CHAT_MODEL_NAME", "synthetic-chat",
                    "DPDK_MODEL_EMBEDDING_BASE_URL", fake.base(), "DPDK_MODEL_EMBEDDING_API_KEY", "synthetic-embedding-key",
                    "DPDK_MODEL_EMBEDDING_MODEL_NAME", "synthetic-embedding");
            // 模型配置只进真正SystemEnvironmentPropertySource；高优先级Map仅放独立DB/服务设置。
            try (var ctx = start(settings(h), env)) {
                assertThat(ctx.getBean(ModelChatProperties.class).baseUrl()).isEqualTo(fake.base());
                assertThat(ctx.getBean(ModelChatProperties.class).apiKey()).isEqualTo("synthetic-chat-key");
                assertThat(ctx.getBean(ModelChatProperties.class).modelName()).isEqualTo("synthetic-chat");
                assertThat(ctx.getBean(ModelEmbeddingProperties.class).baseUrl()).isEqualTo(fake.base());
                assertThat(ctx.getBean(ModelEmbeddingProperties.class).apiKey()).isEqualTo("synthetic-embedding-key");
                assertThat(ctx.getBean(ModelEmbeddingProperties.class).modelName()).isEqualTo("synthetic-embedding");
                assertThat(ctx.getBean(OpenAiCompatibleChatClient.class)).isNotNull();
                assertThat(ctx.getBean(OpenAiCompatibleEmbeddingClient.class)).isNotNull();
                int port = ((WebServerApplicationContext)ctx).getWebServer().getPort();
                var health = request(port, "/api/health", false);
                assertThat(health.statusCode()).isEqualTo(200); headers(health);
            }
        } finally { h.taskRunner.shutdownWatchdog(); h.drop(); }
    }

    @Test
    void readyRequiresCompleteActiveIndexAndFrameworkErrorsKeepFrozenBodiesAndAllSecurityHeaders() throws Exception {
        TaskTestHarness h = TaskTestHarness.create("p4readyhttp");
        try (FakeUpstream fake = new FakeUpstream()) {
            var s = settings(h);
            s.put("dpdk.model.chat.base-url", fake.base()); s.put("dpdk.model.chat.api-key", "synthetic-chat-key");
            s.put("dpdk.model.chat.model-name", "synthetic-chat");
            s.put("dpdk.model.embedding.base-url", fake.base()); s.put("dpdk.model.embedding.api-key", "synthetic-embedding-key");
            s.put("dpdk.model.embedding.model-name", "synthetic-embedding");
            try (var ctx = start(s, Map.of())) {
                int port = ((WebServerApplicationContext)ctx).getWebServer().getPort();
                var health = request(port, "/api/health", false);
                assertThat(health.statusCode()).isEqualTo(200); headers(health);
                var empty = request(port, "/api/ready", false);
                assertThat(empty.statusCode()).isEqualTo(503); headers(empty);
                assertThat(JSON.readTree(empty.body()).path("detail").asText()).isEqualTo("not ready");
                var index = ctx.getBean(IndexService.class);
                String release = index.ingest(sources()).releaseId();
                assertThat(fake.embeddingCalls.get()).isGreaterThan(0);
                var ready = request(port, "/api/ready", false);
                assertThat(ready.statusCode()).isEqualTo(200); headers(ready);
                String node = h.columnValue("rag_release_nodes", "node_id", "release_id=?", release);
                sql(h, "DELETE FROM rag_release_nodes WHERE release_id=? AND node_id=?", release, node);
                var missingMember = request(port, "/api/ready", false);
                assertThat(missingMember.statusCode()).isEqualTo(503); headers(missingMember);
                sql(h, "INSERT INTO rag_release_nodes(release_id,node_id) VALUES (?,?)", release, node);
                sql(h, "UPDATE data_li_knowledge_v1 SET node_id=? WHERE node_id=?", "missing-"+node, node);
                var missingPhysical = request(port, "/api/ready", false);
                assertThat(missingPhysical.statusCode()).isEqualTo(503); headers(missingPhysical);
                sql(h, "UPDATE data_li_knowledge_v1 SET node_id=? WHERE node_id=?", node, "missing-"+node);
                String configuration = h.columnValue("rag_releases", "configuration", "id=?", release);
                sql(h, "UPDATE rag_releases SET configuration='synthetic-drift' WHERE id=?", release);
                var mismatch = request(port, "/api/ready", false);
                assertThat(mismatch.statusCode()).isEqualTo(503); headers(mismatch);
                sql(h, "UPDATE rag_releases SET configuration=? WHERE id=?", configuration, release);
                assertThat(request(port, "/api/ready", false).statusCode()).isEqualTo(200);
                assertThat(request(port, "/api/health", false).statusCode()).isEqualTo(200);
                var absent = request(port, "/__test/p4/not-found", false);
                assertThat(absent.statusCode()).isEqualTo(404); headers(absent);
                assertThat(JSON.readTree(absent.body()).path("detail").asText()).isEqualTo("Not Found");
                var method = request(port, "/api/health", true);
                assertThat(method.statusCode()).isEqualTo(405); headers(method);
                assertThat(JSON.readTree(method.body()).path("detail").asText()).isEqualTo("Method Not Allowed");
                assertThat(method.headers().firstValue("Allow").orElseThrow()).contains("GET");
                var failure = request(port, "/__test/p4/failure", false);
                assertThat(failure.statusCode()).isEqualTo(500); headers(failure);
                assertThat(failure.body()).isEqualTo("Internal Server Error").doesNotContain("SYNTHETIC_PRIVATE_EXCEPTION_DETAIL");
            }
        } finally { h.taskRunner.shutdownWatchdog(); h.drop(); }
    }
}
