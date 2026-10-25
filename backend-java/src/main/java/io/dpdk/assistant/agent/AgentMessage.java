package io.dpdk.assistant.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 循环的消息模型（对位 langchain_core.messages 的四类消息，D11 图无状态）。
 *
 * <p>四种角色：system（系统提示词/会话摘要）、human（用户）、ai（模型回复，可携带
 * tool_calls）、tool（工具结果，toolCallId 与调用一一配对）。配对不变式与 Python
 * 基线一致：带 tool_calls 的 AI 消息与其后紧邻、id 匹配的 ToolMessage 组成原子
 * 单元；上下文收缩只改写 content / tool_calls 参数，不改 id/顺序（context 包锁死）。
 */
public final class AgentMessage {

    public enum Role {SYSTEM, HUMAN, AI, TOOL}

    private final Role role;
    private final String content;
    /** AI 消息的待执行工具调用（发向工具层）。 */
    private final List<ToolCallSpec> toolCalls;
    /** TOOL 消息的配对调用 id；空串表示无法可靠配对（与 Python truthy 过滤口径一致）。 */
    private final String toolCallId;
    /** TOOL 消息对应的工具名。 */
    private final String toolName;

    private AgentMessage(Role role, String content, List<ToolCallSpec> toolCalls,
                         String toolCallId, String toolName) {
        this.role = Objects.requireNonNull(role);
        this.content = content == null ? "" : content;
        this.toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        this.toolCallId = toolCallId == null ? "" : toolCallId;
        this.toolName = toolName == null ? "" : toolName;
    }

    public static AgentMessage system(String content) {
        return new AgentMessage(Role.SYSTEM, content, null, null, null);
    }

    public static AgentMessage human(String content) {
        return new AgentMessage(Role.HUMAN, content, null, null, null);
    }

    public static AgentMessage ai(String content, List<ToolCallSpec> toolCalls) {
        return new AgentMessage(Role.AI, content, toolCalls, null, null);
    }

    public static AgentMessage ai(String content) {
        return ai(content, List.of());
    }

    public static AgentMessage tool(String toolName, String toolCallId, String content) {
        return new AgentMessage(Role.TOOL, content, null, toolCallId, toolName);
    }

    /** 仅替换 content（上下文压缩用）；其余字段原样保留。 */
    public AgentMessage withContent(String newContent) {
        return new AgentMessage(role, newContent, toolCalls, toolCallId, toolName);
    }

    /** 仅替换 tool_calls 参数（历史轮参数压缩用）；id/name/顺序不变。 */
    public AgentMessage withToolCalls(List<ToolCallSpec> newCalls) {
        return new AgentMessage(role, content, newCalls, toolCallId, toolName);
    }

    public Role role() {
        return role;
    }

    public String content() {
        return content;
    }

    public List<ToolCallSpec> toolCalls() {
        return toolCalls;
    }

    public String toolCallId() {
        return toolCallId;
    }

    public String toolName() {
        return toolName;
    }

    public boolean isTool() {
        return role == Role.TOOL;
    }

    public boolean isHuman() {
        return role == Role.HUMAN;
    }

    /** 不可变拷贝工具：在原列表后追加并返回新列表。 */
    public static List<AgentMessage> appended(List<AgentMessage> messages, AgentMessage... extra) {
        List<AgentMessage> out = new ArrayList<>(messages);
        out.addAll(List.of(extra));
        return out;
    }

    public static List<AgentMessage> appendedAll(List<AgentMessage> messages, List<AgentMessage> extra) {
        List<AgentMessage> out = new ArrayList<>(messages);
        out.addAll(extra);
        return out;
    }

    @Override
    public String toString() {
        return "AgentMessage[" + role + (toolName.isEmpty() ? "" : " name=" + toolName) + "]";
    }
}
