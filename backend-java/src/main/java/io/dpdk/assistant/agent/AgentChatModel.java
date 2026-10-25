package io.dpdk.assistant.agent;

import java.util.List;

/**
 * Agent 的模型调用端口（替身边界，任务卡允许：固定模型响应用脚本化替身）。
 *
 * <p>集成期由适配器接到 J1-02 的 {@code contracts.model.ChatModelPort} /
 * {@code ModelClients}（富接口 chat(List&lt;ChatMessage&gt;, ...)）；
 * planner 携带工具名列表（对位 bind_tools），finalize 不带工具
 * （对位 Python finalize 直接用未绑定工具的 model）。
 *
 * <p>实现必须线程安全但无跨请求状态；失败抛异常由图统一转降级。
 */
public interface AgentChatModel {

    /**
     * 发起一次模型调用。toolNames 为可绑定工具名（空列表表示不提供工具）。
     * 返回的 {@link AgentReply} 含正文、tool_calls 与用量（未知为 null）。
     */
    AgentReply call(List<AgentMessage> messages, List<String> toolNames);

    /** 一次模型回复：content 与 toolCalls 可同时非空（W9：正文不下发会被丢）。 */
    record AgentReply(String content, List<ToolCallSpec> toolCalls, AgentUsage usage) {

        public AgentReply {
            content = content == null ? "" : content;
            toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
            usage = usage == null ? AgentUsage.UNKNOWN : usage;
        }

        public static AgentReply text(String content) {
            return new AgentReply(content, List.of(), AgentUsage.UNKNOWN);
        }
    }
}
