package io.dpdk.assistant.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.model.Deadline;
import io.dpdk.assistant.contracts.model.HarnessReply;
import io.dpdk.assistant.contracts.model.HarnessReply.SubmissionPhase;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * harness MCP 客户端的 fake server 行为（对齐 tests/test_mcp_client.py 与
 * test_mcp_client_retry.py）：四分支规范化、建连重试、提交后不重发、参数/期限预检。
 */
class HarnessClientHttpTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** 快速退避（源默认 0.5s 起，测试用 0.01s；重试次数语义不变）。 */
    private static StreamableHttpHarnessClient fastClient(String url) {
        return new StreamableHttpHarnessClient(url, 2, 0.01, Duration.ofSeconds(5));
    }

    private static Deadline noDeadline() {
        return null;
    }

    private static Deadline deadlineInSeconds(long seconds) {
        return new Deadline(Instant.now().plusSeconds(seconds),
                System.nanoTime() + seconds * 1_000_000_000L);
    }

    private static JsonNode payload(HarnessReply reply) throws Exception {
        return JSON.readTree(reply.payloadJson());
    }

    // ------------------------------------------------------------------
    // 结果规范化四分支
    // ------------------------------------------------------------------

    @Test
    void structuredContentTakesPrecedence() throws Exception {
        try (FakeMcpServer harness = FakeMcpServer.start()) {
            harness.toolMode = FakeMcpServer.ToolMode.STRUCTURED;
            HarnessReply reply = fastClient(harness.url())
                    .call("grade_plugin", Map.of("protocol", "dns"), noDeadline());

            assertThat(reply.submissionPhase()).isEqualTo(SubmissionPhase.SUBMITTED);
            assertThat(reply.error()).isNull();
            JsonNode payload = payload(reply);
            assertThat(payload.get("ok").asBoolean()).isTrue();
            assertThat(payload.get("score").asInt()).isEqualTo(62); // structured 优先于文本
            assertThat(harness.initializePosts.get()).isEqualTo(1);
            assertThat(harness.notificationPosts.get()).isEqualTo(1);
            assertThat(harness.toolCalls).containsExactly("grade_plugin");
        }
    }

    @Test
    void textJsonDictIsParsedAsIs() throws Exception {
        try (FakeMcpServer harness = FakeMcpServer.start()) {
            harness.toolMode = FakeMcpServer.ToolMode.TEXT_JSON_DICT;
            HarnessReply reply = fastClient(harness.url())
                    .call("grade_plugin", Map.of(), noDeadline());

            JsonNode payload = payload(reply);
            assertThat(payload.get("ok").asBoolean()).isTrue();
            assertThat(payload.get("score").asInt()).isEqualTo(100);
        }
    }

    @Test
    void nonDictJsonIsWrappedWithOkTrue() throws Exception {
        try (FakeMcpServer harness = FakeMcpServer.start()) {
            harness.toolMode = FakeMcpServer.ToolMode.TEXT_NON_DICT_JSON;
            HarnessReply reply = fastClient(harness.url())
                    .call("list_protocols", Map.of(), noDeadline());

            JsonNode payload = payload(reply);
            assertThat(payload.get("ok").asBoolean()).isTrue();
            assertThat(payload.get("data").asInt()).isEqualTo(42);
        }
    }

    @Test
    void isErrorBecomesStructuredToolError() throws Exception {
        try (FakeMcpServer harness = FakeMcpServer.start()) {
            harness.toolMode = FakeMcpServer.ToolMode.IS_ERROR;
            HarnessReply reply = fastClient(harness.url())
                    .call("grade_plugin", Map.of(), noDeadline());

            // 工具已执行并返回：提交阶段确认为 SUBMITTED，但按旧契约规范化为 ok=false。
            assertThat(reply.submissionPhase()).isEqualTo(SubmissionPhase.SUBMITTED);
            JsonNode payload = payload(reply);
            assertThat(payload.get("ok").asBoolean()).isFalse();
            assertThat(payload.get("error").asText()).isEqualTo("harness_tool_error");
            assertThat(payload.get("detail").asText()).contains("boom");
        }
    }

    @Test
    void nonJsonPayloadBecomesBadResponse() throws Exception {
        try (FakeMcpServer harness = FakeMcpServer.start()) {
            harness.toolMode = FakeMcpServer.ToolMode.NON_JSON;
            HarnessReply reply = fastClient(harness.url())
                    .call("grade_plugin", Map.of(), noDeadline());

            JsonNode payload = payload(reply);
            assertThat(payload.get("ok").asBoolean()).isFalse();
            assertThat(payload.get("error").asText()).isEqualTo("harness_bad_response");
            assertThat(payload.get("detail").asText()).isEqualTo("<not json>");
        }
    }

    @Test
    void sseResponsesAreHandledLikeJsonBodies() throws Exception {
        try (FakeMcpServer harness = FakeMcpServer.start()) {
            harness.sseMode = true;
            harness.toolMode = FakeMcpServer.ToolMode.TEXT_JSON_DICT;
            HarnessReply reply = fastClient(harness.url())
                    .call("grade_plugin", Map.of(), noDeadline());

            assertThat(reply.submissionPhase()).isEqualTo(SubmissionPhase.SUBMITTED);
            assertThat(payload(reply).get("score").asInt()).isEqualTo(100);
        }
    }

    @Test
    void sessionIdIsEchoedOnNotificationAndToolCall() throws Exception {
        try (FakeMcpServer harness = FakeMcpServer.start()) {
            harness.sendSessionId = true;
            fastClient(harness.url()).call("grade_plugin", Map.of(), noDeadline());
            // initialize 响应带的 Mcp-Session-Id 在后续两个 POST 上回传。
            assertThat(harness.sessionIdsSeen).containsExactly("sess-1", "sess-1");
        }
    }

    // ------------------------------------------------------------------
    // 建连/initialize 重试
    // ------------------------------------------------------------------

    @Test
    void handshakeFailuresRetryThenSucceed() throws Exception {
        try (FakeMcpServer harness = FakeMcpServer.start()) {
            harness.failConnectRemaining = 1;
            harness.failInitializeRemaining = 1;
            HarnessReply reply = fastClient(harness.url())
                    .call("list_protocols", Map.of(), noDeadline());

            assertThat(reply.submissionPhase()).isEqualTo(SubmissionPhase.SUBMITTED);
            assertThat(payload(reply).get("ok").asBoolean()).isTrue();
            assertThat(harness.initializePosts.get()).isEqualTo(3); // 首次 + 2 次重试都用满
            assertThat(harness.toolCalls).hasSize(1);
        }
    }

    @Test
    void persistentConnectFailureGivesUpAfterExactlyTwoRetries() throws Exception {
        try (FakeMcpServer harness = FakeMcpServer.start()) {
            harness.failConnectRemaining = 99;
            HarnessReply reply = fastClient(harness.url())
                    .call("list_protocols", Map.of(), noDeadline());

            assertThat(reply.submissionPhase()).isEqualTo(SubmissionPhase.NOT_SUBMITTED);
            assertThat(reply.error()).isEqualTo("harness_unavailable");
            JsonNode payload = payload(reply);
            assertThat(payload.get("ok").asBoolean()).isFalse();
            assertThat(payload.get("error").asText()).isEqualTo("harness_unavailable");
            assertThat(payload.get("detail").asText()).contains("评测服务不可达");
            assertThat(harness.initializePosts.get()).isEqualTo(3); // 不多不少
            assertThat(harness.toolCalls).isEmpty(); // 从未提交
        }
    }

    // ------------------------------------------------------------------
    // 提交后失败：UNKNOWN，绝不重发
    // ------------------------------------------------------------------

    @Test
    void submittedCallFailureNeverRetries() throws Exception {
        try (FakeMcpServer harness = FakeMcpServer.start()) {
            harness.toolMode = FakeMcpServer.ToolMode.HTTP_500;
            HarnessReply reply = fastClient(harness.url())
                    .call("grade_plugin", Map.of("protocol", "dns"), noDeadline());

            assertThat(reply.submissionPhase()).isEqualTo(SubmissionPhase.UNKNOWN);
            assertThat(reply.error()).isEqualTo("harness_unavailable");
            // grade_plugin 只提交一次：重发会重复消耗评测配额。
            assertThat(harness.toolCalls).containsExactly("grade_plugin");
            assertThat(harness.initializePosts.get()).isEqualTo(1); // initialize 也只发生一次尝试
        }
    }

    @Test
    void submittedCallTimeoutAfterSendIsUnknownNotResubmitted() throws Exception {
        try (FakeMcpServer harness = FakeMcpServer.start()) {
            harness.toolMode = FakeMcpServer.ToolMode.HANG_THEN_CLOSE;
            StreamableHttpHarnessClient shortTimeout =
                    new StreamableHttpHarnessClient(harness.url(), 2, 0.01, Duration.ofMillis(100));
            HarnessReply reply = shortTimeout.call("grade_plugin", Map.of(), noDeadline());

            assertThat(reply.submissionPhase()).isEqualTo(SubmissionPhase.UNKNOWN);
            assertThat(harness.toolCalls).containsExactly("grade_plugin"); // 只提交一次
        }
    }

    // ------------------------------------------------------------------
    // 预检：参数序列化与 deadline
    // ------------------------------------------------------------------

    @Test
    void malformedArgumentsFailBeforeAnyHttpRequest() throws Exception {
        try (FakeMcpServer harness = FakeMcpServer.start()) {
            Map<String, Object> bad = new LinkedHashMap<>();
            bad.put("plugin_source", new Object() {
                @SuppressWarnings("unused")
                public String getSource() {
                    throw new IllegalStateException("不可序列化对象");
                }
            });
            HarnessReply reply = fastClient(harness.url()).call("grade_plugin", bad, noDeadline());

            assertThat(reply.submissionPhase()).isEqualTo(SubmissionPhase.NOT_SUBMITTED);
            assertThat(reply.error()).isEqualTo("harness_bad_request");
            assertThat(harness.server.requestCount()).isZero(); // 任何字节都没发出
        }
    }

    @Test
    void expiredDeadlineIsNotSubmitted() throws Exception {
        try (FakeMcpServer harness = FakeMcpServer.start()) {
            Deadline expired = new Deadline(Instant.now().minusSeconds(1),
                    System.nanoTime() - 1_000_000_000L);
            HarnessReply reply = fastClient(harness.url())
                    .call("grade_plugin", Map.of(), expired);

            assertThat(reply.submissionPhase()).isEqualTo(SubmissionPhase.NOT_SUBMITTED);
            assertThat(reply.error()).isEqualTo("harness_deadline_exceeded");
            assertThat(harness.server.requestCount()).isZero();
        }
    }

    @Test
    void deadlineAllowsNormalCompletion() throws Exception {
        try (FakeMcpServer harness = FakeMcpServer.start()) {
            HarnessReply reply = fastClient(harness.url())
                    .call("grade_plugin", Map.of(), deadlineInSeconds(30));
            assertThat(reply.submissionPhase()).isEqualTo(SubmissionPhase.SUBMITTED);
        }
    }

    // ------------------------------------------------------------------
    // 入口收敛：同 URL 复用实例
    // ------------------------------------------------------------------

    @Test
    void factoryConvergesOnSameInstanceForSameUrl() {
        HarnessClients.clearForTest();
        try {
            StreamableHttpHarnessClient first = HarnessClients.forUrl("http://harness:8100/mcp");
            StreamableHttpHarnessClient second = HarnessClients.forUrl("http://harness:8100/mcp");
            StreamableHttpHarnessClient other = HarnessClients.forUrl("http://other:8100/mcp");
            assertThat(first).isSameAs(second);
            assertThat(first).isNotSameAs(other);
        } finally {
            HarnessClients.clearForTest();
        }
    }
}
