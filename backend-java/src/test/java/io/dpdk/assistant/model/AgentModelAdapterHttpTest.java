package io.dpdk.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.agent.AgentMessage;
import io.dpdk.assistant.agent.ToolCallSpec;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 富消息、调用形状及故障语义通过 loopback HTTP 边界验证；不访问真实供应商。 */
class AgentModelAdapterHttpTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private OpenAiCompatibleChatClient client;

    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    private AgentModelAdapter adapter(MockHttpServer server, int retries) {
        client = new OpenAiCompatibleChatClient(new ChatClientSettings(server.baseUrl() + "/v1",
                "synthetic-key", "configured-model", 256, retries, 0, 3, false), new UpstreamGate(2));
        return new AgentModelAdapter(client, "agent-model");
    }

    private static String response(String content, String calls, String usage) {
        return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":" + content
                + (calls == null ? "" : ",\"tool_calls\":" + calls)
                + "}}]" + (usage == null ? "" : ",\"usage\":" + usage) + "}";
    }

    private static String toolCall(String args) throws Exception {
        return JSON.writeValueAsString(List.of(Map.of("id", "call-1", "type", "function",
                "function", Map.of("name", "kb_search", "arguments", args))));
    }

    @Test
    void serializesFourRolesAndPairedToolCallsWithoutExecutingTools() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, req) -> MockHttpServer.respondJson(ex, 200,
                response("\"答案\"", toolCall("{\"query\":\"新的检索\"}"),
                        "{\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15}")))) {
            var adapter = adapter(server, 0);
            var reply = adapter.call(List.of(AgentMessage.system("系统"), AgentMessage.human("问题"),
                    AgentMessage.ai("同时保留正文", List.of(new ToolCallSpec("old-call", "kb_search", Map.of("query", "历史")))),
                    AgentMessage.tool("kb_search", "old-call", "{\"ok\":true}")), List.of("kb_search"));
            assertThat(reply.content()).isEqualTo("答案");
            assertThat(reply.toolCalls()).containsExactly(new ToolCallSpec("call-1", "kb_search", Map.of("query", "新的检索")));
            assertThat(reply.usage().totalTokens()).isEqualTo(15);
            assertThat(server.requestCount()).isEqualTo(1);
            JsonNode payload = JSON.readTree(server.requests.getFirst().body());
            assertThat(payload.path("model").asText()).isEqualTo("agent-model");
            assertThat(payload.has("stream")).isFalse();
            JsonNode messages = payload.path("messages");
            assertThat(messages.get(0).path("role").asText()).isEqualTo("system");
            assertThat(messages.get(1).path("role").asText()).isEqualTo("user");
            assertThat(messages.get(2).path("content").asText()).isEqualTo("同时保留正文");
            assertThat(messages.get(2).path("tool_calls").get(0).path("id").asText()).isEqualTo("old-call");
            assertThat(JSON.readTree(messages.get(2).path("tool_calls").get(0).path("function").path("arguments").asText())
                    .path("query").asText()).isEqualTo("历史");
            assertThat(messages.get(3).path("tool_call_id").asText()).isEqualTo("old-call");
            assertThat(messages.get(3).path("name").asText()).isEqualTo("kb_search");
            assertThat(messages.get(3).path("role").asText()).isEqualTo("tool");
            assertThat(server.requests.getFirst().authorization()).isEqualTo("Bearer synthetic-key");
        }
    }

    @Test
    void encodesAllFiveSchemasAndRequiredArguments() throws Exception {
        List<String> names = List.of("kb_search", "list_protocols", "list_cases", "plugin_template", "grade_plugin");
        try (MockHttpServer server = MockHttpServer.start((ex, req) -> MockHttpServer.respondJson(ex, 200,
                response("\"完成\"", null, null)))) {
            adapter(server, 0).call(List.of(AgentMessage.human("问题")), names);
            JsonNode tools = JSON.readTree(server.requests.getFirst().body()).path("tools");
            assertThat(tools).hasSize(5);
            for (int i = 0; i < names.size(); i++) {
                assertThat(tools.get(i).path("type").asText()).isEqualTo("function");
                assertThat(tools.get(i).path("function").path("name").asText()).isEqualTo(names.get(i));
                assertThat(tools.get(i).path("function").path("parameters").path("type").asText()).isEqualTo("object");
            }
            assertThat(tools.get(1).path("function").path("parameters").path("required")).isEmpty();
            assertThat(tools.get(4).path("function").path("parameters").path("required").toString())
                    .isEqualTo("[\"protocol\",\"plugin_source\"]");
        }
    }

    @Test
    void acceptsNullContentWithToolCallAndKeepsUnknownUsageNull() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, req) -> MockHttpServer.respondJson(ex, 200,
                response("null", toolCall("{\"query\":\"检索\"}"), null)))) {
            var reply = adapter(server, 0).call(List.of(AgentMessage.human("问题")), List.of("kb_search"));
            assertThat(reply.content()).isEmpty();
            assertThat(reply.toolCalls()).hasSize(1);
            assertThat(reply.usage().promptTokens()).isNull();
            assertThat(reply.usage().completionTokens()).isNull();
            assertThat(reply.usage().totalTokens()).isNull();
        }
    }

    @Test
    void finalizeOmitsToolsAndMapsTokenAliases() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, req) -> MockHttpServer.respondJson(ex, 200,
                response("\"最终答案\"", null, "{\"input_tokens\":7,\"output_tokens\":0}")))) {
            var reply = adapter(server, 0).call(List.of(AgentMessage.human("问题")), List.of());
            assertThat(JSON.readTree(server.requests.getFirst().body()).has("tools")).isFalse();
            assertThat(reply.usage().promptTokens()).isEqualTo(7);
            assertThat(reply.usage().completionTokens()).isZero();
            assertThat(reply.usage().totalTokens()).isEqualTo(7);
            assertThat(reply.toolCalls()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "null", "{} {}"})
    void invalidArgumentsFailWithoutTransportRetry(String args) throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, req) -> MockHttpServer.respondJson(ex, 200,
                response("null", toolCall(args), null)))) {
            var adapter = adapter(server, 2);
            assertThatThrownBy(() -> adapter.call(List.of(AgentMessage.human("问题")), List.of("kb_search")))
                    .isInstanceOf(ModelProtocolException.class);
            assertThat(server.requestCount()).isEqualTo(1);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"choices\":[]}",
            "{\"choices\":[{\"message\":{\"content\":null}}]}",
            "{\"choices\":[{\"message\":{\"content\":\"x\",\"tool_calls\":{}}}]}",
            "{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[{\"id\":\"x\",\"type\":\"function\",\"function\":{\"name\":\"kb_search\"}}]}}]}"})
    void malformedReplyFailsClosedAndRecordsUnknown(String body) throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, req) -> MockHttpServer.respondJson(ex, 200, body))) {
            adapter(server, 2);
            var ledger = ModelUsageLedger.create();
            assertThatThrownBy(() -> client.chatTools(List.of(Map.of("role", "user", "content", "问题")),
                    List.of(), "m", ledger, "planner")).isInstanceOf(ModelProtocolException.class);
            assertThat(server.requestCount()).isEqualTo(1);
            assertThat(ledger.entries()).hasSize(1);
            assertThat(ledger.entries().getFirst().usageSource()).isEqualTo("unknown");
        }
    }

    @Test
    void rejectsUnknownBoundToolBeforeAnyHttpRequest() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((ex, req) -> MockHttpServer.respondJson(ex, 200, "{}"))) {
            var adapter = adapter(server, 0);
            assertThatThrownBy(() -> adapter.call(List.of(AgentMessage.human("问题")), List.of("run_shell")))
                    .isInstanceOf(ModelProtocolException.class);
            assertThat(server.requestCount()).isZero();
        }
    }

    @Test
    void toolTransportRetainsRetryCountAndSingleUsageEntry() throws Exception {
        var served = new java.util.concurrent.atomic.AtomicInteger();
        try (MockHttpServer server = MockHttpServer.start((ex, req) -> {
            if (served.incrementAndGet() == 1) {
                MockHttpServer.respondJson(ex, 503, "synthetic failure");
            } else {
                MockHttpServer.respondJson(ex, 200, response("null", toolCall("{}"), "{\"total_tokens\":9}"));
            }
        })) {
            adapter(server, 1);
            var ledger = ModelUsageLedger.create();
            var result = client.chatTools(List.of(Map.of("role", "user", "content", "问题")), List.of(), "m", ledger, "planner");
            assertThat(result.toolCalls()).hasSize(1);
            assertThat(ledger.entries()).hasSize(1);
            assertThat(ledger.entries().getFirst().totalTokens()).isEqualTo(9);
            assertThat(ledger.entries().getFirst().callCount()).isEqualTo(2);
            assertThat(server.requestCount()).isEqualTo(2);
        }
    }

    @Test
    void cancellingAwaitKeepsRemoteResultUnknownAndDoesNotRepeatCall() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try (MockHttpServer server = MockHttpServer.start((ex, req) -> {
            entered.countDown();
            release.await(2, TimeUnit.SECONDS);
            MockHttpServer.respondJson(ex, 200, response("\"稍后\"", null, null));
        })) {
            adapter(server, 2);
            var ledger = ModelUsageLedger.create();
            Thread call = Thread.ofVirtual().start(() -> {
                try {
                    client.chatTools(List.of(Map.of("role", "user", "content", "问题")), List.of(), "m", ledger, "planner");
                } catch (Throwable error) {
                    failure.set(error);
                }
            });
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                call.interrupt();
                call.join(2000);
                assertThat(call.isAlive()).isFalse();
                assertThat(failure.get()).isInstanceOf(ModelCallCancelledException.class);
                assertThat(ledger.entries()).hasSize(1);
                assertThat(ledger.entries().getFirst().usageSource()).isEqualTo("unknown");
                assertThat(ledger.entries().getFirst().callCount()).isEqualTo(1);
                assertThat(server.requestCount()).isEqualTo(1);
            } finally {
                release.countDown();
                call.interrupt();
            }
        }
    }
}
