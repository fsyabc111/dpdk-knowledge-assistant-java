package io.dpdk.assistant.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.agent.AgentChatModel;
import io.dpdk.assistant.agent.AgentMessage;
import io.dpdk.assistant.agent.AgentUsage;
import io.dpdk.assistant.agent.ToolCallSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Agent 富消息到 OpenAI 兼容单轮调用的适配。只转换协议，不运行工具；planner 的
 * 五工具白名单与 finalize 的空工具集合分别编码。正文由产品任务保存后再展示。
 * 本对象只有不可变客户端/模型配置，可由多个请求共享。
 */
public final class AgentModelAdapter implements AgentChatModel {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final TypeReference<Map<String, Object>> ARGUMENTS = new TypeReference<>() { };
    private final OpenAiCompatibleChatClient client;
    private final String modelName;

    public AgentModelAdapter(OpenAiCompatibleChatClient client, String modelName) {
        this.client = Objects.requireNonNull(client);
        this.modelName = modelName;
    }

    @Override
    public AgentReply call(List<AgentMessage> messages, List<String> toolNames) {
        List<Map<String, Object>> schemas = toolNames.stream().map(AgentModelAdapter::schema).toList();
        List<Map<String, Object>> wireMessages = messages.stream()
                .map(AgentModelAdapter::serializeMessage).toList();
        var reply = client.chatTools(wireMessages, schemas, modelName, null, "agent");
        List<ToolCallSpec> calls = new ArrayList<>();
        for (var call : reply.toolCalls()) {
            try {
                JsonNode args = JSON.readTree(call.arguments());
                if (args == null || !args.isObject()) {
                    throw new ModelProtocolException("工具参数必须是 JSON 对象");
                }
                calls.add(new ToolCallSpec(call.id(), call.name(), JSON.convertValue(args, ARGUMENTS)));
            } catch (JsonProcessingException failure) {
                throw new ModelProtocolException("工具参数不是合法 JSON 对象", failure);
            }
        }
        var usage = ModelUsageLedger.tokensFromUsage(reply.rawUsage());
        return new AgentReply(reply.content(), calls, new AgentUsage(usage.get("prompt_tokens"),
                usage.get("completion_tokens"), usage.get("total_tokens")));
    }

    private static Map<String, Object> serializeMessage(AgentMessage message) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", switch (message.role()) {
            case SYSTEM -> "system";
            case HUMAN -> "user";
            case AI -> "assistant";
            case TOOL -> "tool";
        });
        wire.put("content", message.content());
        if (!message.toolCalls().isEmpty()) {
            wire.put("tool_calls", message.toolCalls().stream().map(call -> {
                try {
                    return Map.<String, Object>of("id", call.id(), "type", "function",
                            "function", Map.of("name", call.name(),
                                    "arguments", JSON.writeValueAsString(call.args())));
                } catch (JsonProcessingException failure) {
                    throw new ModelProtocolException("无法编码历史工具调用参数", failure);
                }
            }).toList());
        }
        if (message.isTool()) {
            wire.put("tool_call_id", message.toolCallId());
            wire.put("name", message.toolName());
        }
        return wire;
    }

    /** 参数名/必填项对齐冻结 Python build_tools 的五个签名。 */
    private static Map<String, Object> schema(String name) {
        String description;
        List<String> parameters;
        switch (name) {
            case "kb_search" -> {
                description = "检索项目知识库，回答协议原理、插件接口、项目结构等事实性问题。"
                        + "参数 query：检索关键词或问题。返回编号片段列表，作答时用 [编号] 引用。";
                parameters = List.of("query");
            }
            case "list_protocols" -> {
                description = "列出评测环境（harness）支持的协议清单，含每个协议的用例数与用例类别分布。"
                        + "无参数。学生问有哪些实验/协议可以做时使用。";
                parameters = List.of();
            }
            case "list_cases" -> {
                description = "列出某协议的全部评测用例（名称 + 类别标签）。"
                        + "参数 protocol：协议名（如 dns、http、ssh）。";
                parameters = List.of("protocol");
            }
            case "plugin_template" -> {
                description = "生成某协议的学习用插件骨架（保留 TODO 与 checklist），是起手代码而非作业答案。"
                        + "参数 protocol：协议名（如 dns、http、ssh）。返回骨架代码、解释、检查清单与参考文件路径。";
                parameters = List.of("protocol");
            }
            case "grade_plugin" -> {
                description = "评测学生的协议检测插件源码，返回逐用例结果与分数。"
                        + "参数 protocol：协议名；plugin_source：插件 .c 源码全文。"
                        + "若请求已有上传/粘贴源码，以服务端寄存的为准。";
                parameters = List.of("protocol", "plugin_source");
            }
            default -> throw new ModelProtocolException("未注册的 Agent 工具：" + name);
        }
        Map<String, Object> properties = new LinkedHashMap<>();
        parameters.forEach(parameter -> properties.put(parameter, Map.of("type", "string")));
        return Map.of("type", "function", "function", Map.of("name", name,
                "description", description, "parameters", Map.of("type", "object",
                        "properties", properties, "required", parameters)));
    }
}
