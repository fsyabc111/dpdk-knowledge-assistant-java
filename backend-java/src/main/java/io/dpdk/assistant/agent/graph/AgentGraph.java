package io.dpdk.assistant.agent.graph;

import io.dpdk.assistant.agent.AgentChatModel;
import io.dpdk.assistant.agent.AgentMessage;
import io.dpdk.assistant.agent.AgentRunListener;
import io.dpdk.assistant.agent.AgentUsage;
import io.dpdk.assistant.agent.ToolCallSpec;
import io.dpdk.assistant.agent.context.ContextBudget;
import io.dpdk.assistant.agent.port.AgentUsageSink;
import io.dpdk.assistant.agent.port.KbChunk;
import io.dpdk.assistant.agent.tools.AgentTool;
import io.dpdk.assistant.agent.tools.AgentToolContext;
import io.dpdk.assistant.agent.tools.RetryableTaskFailure;
import io.dpdk.assistant.agent.tools.ToolSummaries;
import io.dpdk.assistant.retrieval.PythonJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 显式受控循环（planner → tools → finalize；源 backend/app/agent/graph.py 移植）。
 *
 * <p>框架自动工具循环关闭——本类不使用任何框架内置循环，planner/tools/finalize
 * 全部受同一控制器约束：显式循环状态（消息/工具轮次/token 使用）由
 * {@link GraphState} 保存；轮次/token 达限转 finalize；达限转入时末尾
 * AIMessage 的 tool_calls 从未执行，finalize 进模型前先补合成 not_executed
 * ToolMessage 配对（B4）——只作用于发给模型的消息副本，state 本身不动。
 *
 * <p>无 checkpointer（D11）：图无状态，会话上下文由调用方在入图前构建为消息
 * 列表传入；工具以请求级 {@link AgentToolContext} 注入，图可按请求重建。
 */
public final class AgentGraph {

    private static final Logger log = LoggerFactory.getLogger(AgentGraph.class);

    public static final String TOOL_LIMIT_NOTE =
            "\n\n注意：本轮对话的工具调用次数已达上限，请基于已获得的信息直接给出最终答复，"
                    + "不要再尝试调用工具。";

    private static final String NOT_EXECUTED_DETAIL = "工具调用未执行：已达工具轮数或 token 上限";

    /** 系统提示词构造器：按当前图状态动态拼装（检测反馈升级依赖最近的评测结果）。 */
    public interface PromptBuilder {
        String build(List<AgentMessage> messages) throws Exception;
    }

    /** 显式循环状态：消息、工具轮次、token 使用。 */
    public static final class GraphState {
        public List<AgentMessage> messages;
        public int toolRounds;
        public int tokensUsed;

        public GraphState(List<AgentMessage> messages) {
            this.messages = new ArrayList<>(messages);
        }
    }

    /** 一次运行的结果：终态消息列表与用量计数（编排层据此提取答案）。 */
    public record GraphResult(GraphState state) {
        public String lastContent() {
            List<AgentMessage> messages = state.messages;
            return messages.isEmpty() ? "" : messages.get(messages.size() - 1).content();
        }
    }

    private final AgentChatModel model;
    private final Map<String, AgentTool> toolsByName;
    private final PromptBuilder promptBuilder;
    private final int maxRounds;
    private final int tokenBudget;
    private final int contextMaxChars;
    private final AgentUsageSink usage;
    private final String modelName;
    private final AgentToolContext toolContext;

    private AgentGraph(Builder b) {
        this.model = b.model;
        Map<String, AgentTool> byName = new LinkedHashMap<>();
        for (AgentTool tool : b.tools) {
            byName.put(tool.name(), tool);
        }
        this.toolsByName = byName;
        this.promptBuilder = b.promptBuilder;
        this.maxRounds = b.maxRounds;
        this.tokenBudget = b.tokenBudget;
        this.contextMaxChars = b.contextMaxChars;
        this.usage = b.usage;
        this.modelName = b.modelName;
        this.toolContext = b.toolContext;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private AgentChatModel model;
        private List<AgentTool> tools = List.of();
        private PromptBuilder promptBuilder;
        private int maxRounds = 5;
        private int tokenBudget = 0;
        private int contextMaxChars = 0;
        private AgentUsageSink usage;
        private String modelName;
        private AgentToolContext toolContext;

        public Builder model(AgentChatModel v) {
            this.model = v;
            return this;
        }

        public Builder tools(List<AgentTool> v) {
            this.tools = v == null ? List.of() : List.copyOf(v);
            return this;
        }

        public Builder promptBuilder(PromptBuilder v) {
            this.promptBuilder = v;
            return this;
        }

        public Builder maxRounds(int v) {
            this.maxRounds = v;
            return this;
        }

        public Builder tokenBudget(int v) {
            this.tokenBudget = v;
            return this;
        }

        public Builder contextMaxChars(int v) {
            this.contextMaxChars = v;
            return this;
        }

        public Builder usage(AgentUsageSink v) {
            this.usage = v;
            return this;
        }

        public Builder modelName(String v) {
            this.modelName = v;
            return this;
        }

        public Builder toolContext(AgentToolContext v) {
            this.toolContext = v;
            return this;
        }

        public AgentGraph build() {
            return new AgentGraph(this);
        }
    }

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    public GraphResult run(List<AgentMessage> initialMessages, AgentRunListener listener) {
        if (listener == null) {
            listener = noop();
        }
        GraphState state = new GraphState(initialMessages);
        planner(state, listener);
        while (true) {
            String route = routeAfterPlanner(state);
            if (route.equals("END")) {
                return finish(state);
            }
            if (route.equals("finalize")) {
                finalizeNode(state, listener);
                return finish(state);
            }
            runTools(state, listener);
            // tools 回填后若预算已爆，跳过下一次 planner 调用直接 finalize。
            if (tokenBudget > 0 && state.tokensUsed >= tokenBudget) {
                finalizeNode(state, listener);
                return finish(state);
            }
            planner(state, listener);
        }
    }

    private GraphResult finish(GraphState state) {
        if (toolContext != null) toolContext.metrics.agentTokens(state.tokensUsed);
        return new GraphResult(state);
    }

    private static AgentRunListener noop() {
        return new AgentRunListener() {
        };
    }

    // ------------------------------------------------------------------
    // 节点
    // ------------------------------------------------------------------

    private void planner(GraphState state, AgentRunListener listener) {
        try {
            String systemPrompt = promptBuilder.build(state.messages);
            ContextBudget.PreparedContext prepared = ContextBudget.prepare(
                    systemPrompt, state.messages, contextMaxChars, null);
            if (prepared.overBudget()) {
                throw new ContextBudgetExceededException(
                        String.valueOf(prepared.stats().getOrDefault("reason", "floor_exceeded")));
            }
            List<String> toolNames = new ArrayList<>(toolsByName.keySet());
            AgentChatModel.AgentReply reply = invokeWithUsage("planner", prepared.messages(), toolNames);
            state.tokensUsed += reply.usage().totalOrZero();
            AgentMessage ai = AgentMessage.ai(reply.content(), reply.toolCalls());
            state.messages.add(ai);
            if (!reply.content().isEmpty()) {
                listener.onDelta(reply.content());
            }
            for (ToolCallSpec call : reply.toolCalls()) {
                listener.onToolStart(call.name(),
                        ToolSummaries.toolEventLabel(call.name(), call.args()));
            }
        } catch (ContextBudgetExceededException | RetryableTaskFailure e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("planner 调用失败: " + e.getMessage(), e);
        }
    }

    private void runTools(GraphState state, AgentRunListener listener) {
        AgentMessage last = state.messages.get(state.messages.size() - 1);
        List<AgentMessage> outputs = new ArrayList<>();
        boolean sawKbSearch = false;
        for (ToolCallSpec call : last.toolCalls()) {
            long toolStarted = System.nanoTime();
            Map<String, Object> result;
            AgentTool tool = toolsByName.get(call.name());
            if (tool == null) {
                result = new LinkedHashMap<>();
                result.put("ok", false);
                result.put("error", "unknown_tool");
                result.put("detail", "未知工具：" + call.name());
            } else {
                try {
                    Map<String, Object> raw = tool.invoke(call.args());
                    result = raw == null ? Map.of() : raw;
                } catch (io.dpdk.assistant.agent.tools.HarnessResultUnknown e) {
                    throw e;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RetryableTaskFailure("task_interrupted", e);
                } catch (RetryableTaskFailure e) {
                    throw e;
                } catch (Exception exc) {
                    log.warn(PythonJson.dumps(Map.of(
                            "event", "agent_tool_exception", "tool", call.name(),
                            "error_type", exc.getClass().getSimpleName(),
                            "detail", CodePointLimit.detail(exc.getMessage(), 300)), false));
                    result = new LinkedHashMap<>();
                    result.put("ok", false);
                    result.put("error", "tool_exception");
                    result.put("detail", "工具执行异常：" + exc.getClass().getSimpleName());
                }
            }
            String content = PythonJson.dumps(result, false);
            if (toolContext != null) {
                // 源metric虽名为bytes，实际口径为Python Unicode字符串len。
                toolContext.metrics.tool(tool == null ? "unknown" : call.name(),
                        Boolean.TRUE.equals(result.get("ok")), tool == null ? 0 : (System.nanoTime()-toolStarted)/1e9,
                        tool == null ? 0 : content.codePointCount(0, content.length()));
            }
            outputs.add(AgentMessage.tool(call.name(), call.id(), content));
            listener.onToolEnd(ToolSummaries.toolEventSummary(call.name(), content));
            if ("kb_search".equals(call.name())) {
                sawKbSearch = true;
            }
        }
        state.messages.addAll(outputs);
        state.toolRounds += 1;
        if (sawKbSearch && toolContext != null && !toolContext.collectedChunks.isEmpty()) {
            listener.onSources(new ArrayList<>(toolContext.collectedChunks));
        }
    }

    private void finalizeNode(GraphState state, AgentRunListener listener) {
        try {
            String systemPrompt = promptBuilder.build(state.messages) + TOOL_LIMIT_NOTE;
            // B4：达限转入时末尾 tool_calls 未执行，先补合成 ToolMessage 配对——
            // 只改发给模型的消息副本，state.messages 保持不变。
            List<AgentMessage> paired = pairUnexecutedToolCalls(state.messages);
            ContextBudget.PreparedContext prepared = ContextBudget.prepare(
                    systemPrompt, paired, contextMaxChars, null);
            if (prepared.overBudget()) {
                throw new ContextBudgetExceededException(
                        String.valueOf(prepared.stats().getOrDefault("reason", "floor_exceeded")));
            }
            // finalize 不提供工具（对位 Python 直接用未绑定工具的 model）。
            AgentChatModel.AgentReply reply = invokeWithUsage("finalize", prepared.messages(), List.of());
            state.tokensUsed += reply.usage().totalOrZero();
            state.messages.add(AgentMessage.ai(reply.content()));
            if (!reply.content().isEmpty()) {
                listener.onDelta(reply.content());
            }
        } catch (ContextBudgetExceededException | RetryableTaskFailure e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("finalize 调用失败: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 路由与配对
    // ------------------------------------------------------------------

    private String routeAfterPlanner(GraphState state) {
        AgentMessage last = state.messages.get(state.messages.size() - 1);
        if (last.toolCalls().isEmpty()) {
            return "END";
        }
        // token 预算超限：硬转 finalize（E9 兜底同形 TOOL_LIMIT_NOTE），不再抛异常。
        if (tokenBudget > 0 && state.tokensUsed >= tokenBudget) {
            return "finalize";
        }
        if (state.toolRounds >= maxRounds) {
            return "finalize";
        }
        return "tools";
    }

    /**
     * 给末尾悬空的 tool_calls 补合成 ToolMessage（B4），返回新列表。
     * 末尾不是带 tool_calls 的 AI 消息时原样返回；历史中部的悬空属于输入腐化，
     * 不在本函数职责内。
     */
    public static List<AgentMessage> pairUnexecutedToolCalls(List<AgentMessage> messages) {
        if (messages.isEmpty()) {
            return messages;
        }
        List<ToolCallSpec> calls = messages.get(messages.size() - 1).toolCalls();
        if (calls.isEmpty()) {
            return messages;
        }
        List<AgentMessage> synthesized = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (ToolCallSpec call : calls) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("ok", false);
            payload.put("error", "not_executed");
            payload.put("detail", NOT_EXECUTED_DETAIL);
            synthesized.add(AgentMessage.tool(call.name(), call.id(), PythonJson.dumps(payload, false)));
            ids.add(call.id());
        }
        log.warn(PythonJson.dumps(Map.of(
                "event", "agent_tool_calls_not_executed", "tool_call_ids", ids), false));
        return AgentMessage.appendedAll(messages, synthesized);
    }

    /**
     * 调一次上游并把用量记进账本。失败也留一条（usage=null → unknown）：请求
     * 已经发出去，上游可能已经算过钱，「没记」不等于「没花」。usage 为 null 时完全透明。
     */
    private AgentChatModel.AgentReply invokeWithUsage(String stage, List<AgentMessage> messages,
                                                      List<String> toolNames) {
        AgentChatModel.AgentReply reply;
        try {
            reply = model.call(messages, toolNames);
        } catch (RuntimeException e) {
            if (usage != null) {
                usage.recordLlm(stage, modelName, null);
            }
            throw e;
        }
        if (usage != null) {
            usage.recordLlm(stage, modelName, reply.usage());
        }
        return reply;
    }

    /** sources 事件的引用装配（engine/rank）由 J3-03 CitationBinder 承接，本图只交出累计片段。 */
    public static List<KbChunk> sourcesFromChunks(List<KbChunk> chunks) {
        return new ArrayList<>(chunks);
    }

    private static final class CodePointLimit {
        static String detail(String text, int max) {
            if (text == null) {
                return "";
            }
            return io.dpdk.assistant.agent.context.CodePoints.truncate(text, max);
        }
    }
}
