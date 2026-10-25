package io.dpdk.assistant.agent;

import java.util.Map;

/**
 * 一次工具调用请求（对位 AIMessage.tool_calls 的条目：name/args/id）。
 *
 * <p>args 保留 JSON 反序列化后的 Map 形态；序列化口径统一走
 * {@code io.dpdk.assistant.retrieval.PythonJson}（json.dumps 兼容）。
 */
public record ToolCallSpec(String id, String name, Map<String, Object> args) {

    public ToolCallSpec {
        id = id == null ? "" : id;
        name = name == null ? "" : name;
        args = args == null ? Map.of() : args;
    }

    public static ToolCallSpec of(String id, String name, Map<String, Object> args) {
        return new ToolCallSpec(id, name, args);
    }
}
