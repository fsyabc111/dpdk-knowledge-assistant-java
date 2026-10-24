package io.dpdk.assistant.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI 兼容消息（role/content 两键，对齐 llm_client.chat 的 messages 形态）。
 * 携带 JSON payload 解析（供契约 ChatInput 适配）。
 */
public record ChatMessage(String role, String content) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** ChatInput.jsonPayload 的解析结果：messages + 可选 model 覆盖。 */
    public record ParsedPayload(List<ChatMessage> messages, String modelOverride) {
    }

    /**
     * 解析契约 ChatInput 的 jsonPayload：必须是含 messages 数组的 JSON 对象，
     * 每条消息含 role/content 字符串。畸形输入在任何 HTTP 字节发出前失败。
     */
    public static ParsedPayload parsePayload(String jsonPayload) {
        JsonNode root;
        try {
            root = JSON.readTree(jsonPayload == null ? "" : jsonPayload);
        } catch (JsonProcessingException e) {
            throw new ModelProtocolException("ChatInput jsonPayload 不是合法 JSON", e);
        }
        if (root == null || !root.isObject() || !root.path("messages").isArray()) {
            throw new ModelProtocolException("ChatInput jsonPayload 必须是包含 messages 数组的 JSON 对象");
        }
        List<ChatMessage> messages = new ArrayList<>();
        for (JsonNode item : root.get("messages")) {
            if (!item.isObject() || !item.path("role").isTextual()
                    || !item.path("content").isTextual()) {
                throw new ModelProtocolException("messages 每条必须包含字符串 role 与 content");
            }
            messages.add(new ChatMessage(item.get("role").asText(), item.get("content").asText()));
        }
        String override = root.path("model").isTextual() ? root.get("model").asText() : null;
        return new ParsedPayload(List.copyOf(messages), override);
    }
}
