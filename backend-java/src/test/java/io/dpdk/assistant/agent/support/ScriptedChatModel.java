package io.dpdk.assistant.agent.support;

import io.dpdk.assistant.agent.AgentChatModel;
import io.dpdk.assistant.agent.AgentMessage;
import io.dpdk.assistant.agent.AgentUsage;
import io.dpdk.assistant.agent.ToolCallSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚本化假聊天模型（对位 Python golden_support.ScriptedChatModel；无网络）。
 *
 * <p>按序回放脚本化回复；超出脚本长度时重复最后一条。记录每次调用收到的完整
 * 消息列表与首条系统提示词，供「反馈纪律 / 预算 / 提示词档位」断言。
 */
public final class ScriptedChatModel implements AgentChatModel {

    public final List<AgentReply> responses;
    public int calls;
    public final List<List<AgentMessage>> seenMessages = new ArrayList<>();
    public final List<String> systemPrompts = new ArrayList<>();
    public final List<List<String>> toolNameBindings = new ArrayList<>();

    public ScriptedChatModel(AgentReply... responses) {
        this.responses = new ArrayList<>(List.of(responses));
    }

    public static AgentReply text(String content) {
        return new AgentReply(content, List.of(), AgentUsage.UNKNOWN);
    }

    public static AgentReply toolCall(ToolCallSpec... calls) {
        List<ToolCallSpec> list = new ArrayList<>();
        for (ToolCallSpec call : calls) {
            list.add(call);
        }
        return new AgentReply("", list, AgentUsage.UNKNOWN);
    }

    public static AgentReply toolCallWithUsage(AgentUsage usage, ToolCallSpec... calls) {
        List<ToolCallSpec> list = new ArrayList<>();
        for (ToolCallSpec call : calls) {
            list.add(call);
        }
        return new AgentReply("", list, usage);
    }

    @Override
    public AgentReply call(List<AgentMessage> messages, List<String> toolNames) {
        calls++;
        seenMessages.add(new ArrayList<>(messages));
        String first = messages.isEmpty() ? "" : typeOf(messages.get(0));
        systemPrompts.add(first);
        toolNameBindings.add(new ArrayList<>(toolNames));
        int index = Math.min(calls - 1, responses.size() - 1);
        return responses.get(index);
    }

    private static String typeOf(AgentMessage message) {
        return message.role() == AgentMessage.Role.SYSTEM ? message.content() : "";
    }

    /** 模型历次调用看到过的全部 human 消息文本。 */
    public List<String> humanTexts() {
        List<String> out = new ArrayList<>();
        for (List<AgentMessage> invocation : seenMessages) {
            for (AgentMessage message : invocation) {
                if (message.isHuman()) {
                    out.add(message.content());
                }
            }
        }
        return out;
    }

    /** 按调用序号取该次调用看到的 ToolMessage（按 toolCallId 索引）。 */
    public Map<String, AgentMessage> toolMessagesOf(int callIndex) {
        Map<String, AgentMessage> out = new LinkedHashMap<>();
        for (AgentMessage message : seenMessages.get(callIndex)) {
            if (message.isTool()) {
                out.put(message.toolCallId(), message);
            }
        }
        return out;
    }
}
