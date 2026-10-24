package io.dpdk.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 校验 src/test/resources/migration/J1-02/wire-samples.json 的脱敏线上样本：
 * 全部可解析、形态与源实现一致、不含任何真实凭据值。
 */
class WireSamplesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonNode sample(String name) throws Exception {
        JsonNode root = JSON.readTree(getClass()
                .getResourceAsStream("/migration/J1-02/wire-samples.json"));
        return root.get(name);
    }

    @Test
    void samplesAreWellFormedAndSanitized() throws Exception {
        JsonNode chatRequest = sample("chatRequest");
        assertThat(chatRequest.get("temperature").asDouble()).isEqualTo(0.2);
        assertThat(chatRequest.get("messages").isArray()).isTrue();
        assertThat(chatRequest.toString()).doesNotContain("Bearer");

        JsonNode chatResponse = sample("chatResponse");
        assertThat(chatResponse.get("usage").get("total_tokens").asInt()).isEqualTo(150);

        JsonNode streamRequest = sample("chatStreamRequest");
        assertThat(streamRequest.get("stream").asBoolean()).isTrue();
        assertThat(streamRequest.get("stream_options").get("include_usage").asBoolean()).isTrue();

        JsonNode frames = sample("chatStreamFrames");
        assertThat(frames.get(3).asText()).isEqualTo("data: [DONE]");
        assertThat(frames.get(2).asText()).contains("\"usage\"");

        JsonNode rejected = sample("chatStreamOptionsRejected");
        assertThat(rejected.get("status").asInt()).isEqualTo(400);
        assertThat(rejected.get("body").asText()).contains("stream_options");

        JsonNode embedRequest = sample("embeddingsRequest");
        assertThat(embedRequest.get("input").size()).isEqualTo(2);

        JsonNode mcpInit = sample("mcpInitializeRequest");
        assertThat(mcpInit.get("method").asText()).isEqualTo("initialize");
        assertThat(mcpInit.get("params").get("clientInfo").get("name").asText())
                .isEqualTo("dpdk-assistant-java");

        JsonNode mcpNotification = sample("mcpInitializedNotification");
        assertThat(mcpNotification.has("id")).isFalse(); // 通知没有 id

        JsonNode toolCall = sample("mcpToolCallRequest");
        assertThat(toolCall.get("params").get("name").asText()).isEqualTo("grade_plugin");
        // 合成源码哨兵只保留占位符，不携带真实源码。
        assertThat(toolCall.get("params").get("arguments").get("plugin_source").asText())
                .doesNotContain("int main");

        JsonNode structured = sample("mcpToolCallResultStructured");
        assertThat(structured.get("result").has("structuredContent")).isTrue();

        JsonNode unavailable = sample("mcpUnavailableReply");
        assertThat(unavailable.get("error").asText()).isEqualTo("harness_unavailable");
    }

    @Test
    void noCredentialValuesInAnySample() throws Exception {
        String all = JSON.readTree(getClass()
                .getResourceAsStream("/migration/J1-02/wire-samples.json")).toString();
        assertThat(all).doesNotContain("sk-").doesNotContain("Bearer ").doesNotContain("api_key");
    }
}
