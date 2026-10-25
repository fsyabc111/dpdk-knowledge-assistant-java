package io.dpdk.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dpdk.assistant.agent.context.CodePoints;
import io.dpdk.assistant.agent.context.ContextBudget;
import io.dpdk.assistant.agent.graph.AgentGraph;
import io.dpdk.assistant.agent.graph.ContextBudgetExceededException;
import io.dpdk.assistant.agent.support.ScriptedChatModel;
import io.dpdk.assistant.agent.tools.AgentTool;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Agent 图路由与上下文预算测试（移植 backend/tests/test_agent_graph.py +
 * test_agent_context_prepare.py 的核心行为；脚本化假模型，无网络）。
 */
class AgentGraphLoopTest {

    private static AgentGraph graph(ScriptedChatModel model, int maxRounds) {
        return AgentGraph.builder()
                .model(model)
                .tools(List.of(echoTool()))
                .promptBuilder(state -> "系统提示")
                .maxRounds(maxRounds)
                .build();
    }

    private static AgentToolStub echoTool() {
        return new AgentToolStub("fake_lookup");
    }

    private static AgentToolStub brokenTool() {
        return new AgentToolStub("always_broken", true);
    }

    private static ToolCallSpec call(String name, Map<String, Object> args, String callId) {
        return ToolCallSpec.of(callId, name, args);
    }

    private static Map<String, Object> args(String key, String value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, value);
        return m;
    }

    private static int sentChars(List<AgentMessage> messages) {
        int total = 0;
        for (AgentMessage message : messages) {
            total += ContextBudget.messageChars(message);
        }
        return total;
    }

    // ------------------------------------------------------------------
    // 路由
    // ------------------------------------------------------------------

    @Test
    void no_tool_calls_exits_after_one_planner_call() {
        ScriptedChatModel model = new ScriptedChatModel(ScriptedChatModel.text("直接回答"));
        AgentGraph.GraphResult result = graph(model, 3).run(
                List.of(AgentMessage.human("hi")), null);
        assertThat(result.lastContent()).isEqualTo("直接回答");
        assertThat(result.state().toolRounds).isEqualTo(0);
        assertThat(model.calls).isEqualTo(1);
    }

    @Test
    void tool_call_roundtrip_feeds_result_back_to_planner() {
        ScriptedChatModel model = new ScriptedChatModel(
                ScriptedChatModel.toolCall(call("fake_lookup", args("query", "dns"), "call_1")),
                ScriptedChatModel.text("最终答复"));
        AgentGraph.GraphResult result = graph(model, 3).run(
                List.of(AgentMessage.human("hi")), null);
        long toolMessages = result.state().messages.stream().filter(AgentMessage::isTool).count();
        assertThat(toolMessages).isEqualTo(1);
        AgentMessage toolMessage = result.state().messages.stream()
                .filter(AgentMessage::isTool).findFirst().orElseThrow();
        assertThat(toolMessage.content()).isEqualTo(
                "{\"ok\": true, \"data\": {\"echo\": \"dns\"}}");
        assertThat(result.lastContent()).isEqualTo("最终答复");
        assertThat(result.state().toolRounds).isEqualTo(1);
    }

    @Test
    void tool_loop_respects_max_rounds_and_finalizes() {
        ScriptedChatModel model = new ScriptedChatModel(
                ScriptedChatModel.toolCall(call("fake_lookup", args("query", "1"), "c1")),
                ScriptedChatModel.toolCall(call("fake_lookup", args("query", "2"), "c2")),
                ScriptedChatModel.toolCall(call("fake_lookup", args("query", "3"), "c3")),
                ScriptedChatModel.text("限轮后的最终答复"));
        AgentGraph.GraphResult result = graph(model, 2).run(
                List.of(AgentMessage.human("hi")), null);
        long toolMessages = result.state().messages.stream().filter(AgentMessage::isTool).count();
        // 第三轮 tool_call 不再执行，转入 finalize 直接产出答复。
        assertThat(toolMessages).isEqualTo(2);
        assertThat(result.state().toolRounds).isEqualTo(2);
        assertThat(result.lastContent()).isEqualTo("限轮后的最终答复");
        assertThat(model.calls).isEqualTo(4);
    }

    @Test
    void finalize_pairs_unexecuted_tool_calls_when_rounds_exhausted() {
        // B4/F3 回归：达限转 finalize 时，末尾未执行的 tool_calls 不得悬空进入上游。
        ScriptedChatModel model = new ScriptedChatModel(
                ScriptedChatModel.toolCall(call("fake_lookup", args("query", "1"), "c_kb")),
                ScriptedChatModel.toolCall(
                        call("fake_lookup", args("query", "2"), "c_kb2"),
                        call("fake_lookup", args("query", "3"), "c_kb3")),
                ScriptedChatModel.text("达限后的最终答复"));
        AgentGraph.GraphResult result = graph(model, 1).run(
                List.of(AgentMessage.human("hi")), null);

        assertThat(result.lastContent()).isEqualTo("达限后的最终答复");
        assertThat(model.calls).isEqualTo(3);

        List<AgentMessage> finalizeMessages = model.seenMessages.get(2);
        // 协议不变式：带 tool_calls 的 AI 消息之后必须紧邻其全部配对 ToolMessage。
        for (int index = 0; index < finalizeMessages.size(); index++) {
            List<ToolCallSpec> calls = finalizeMessages.get(index).toolCalls();
            if (calls.isEmpty()) {
                continue;
            }
            List<AgentMessage> followers = followers(finalizeMessages, index, calls.size());
            assertThat(followers).allSatisfy(AgentMessage::isTool);
            for (int k = 0; k < calls.size(); k++) {
                assertThat(followers.get(k).toolCallId()).isEqualTo(calls.get(k).id());
            }
        }
        // 未执行的两个调用各补一条合成 ToolMessage，形状与 run_tools 错误结果一致。
        List<AgentMessage> synthesized = finalizeMessages.stream()
                .filter(AgentMessage::isTool)
                .filter(m -> m.toolCallId().equals("c_kb2") || m.toolCallId().equals("c_kb3"))
                .toList();
        assertThat(synthesized).hasSize(2);
        assertThat(synthesized.get(0).toolName()).isEqualTo("fake_lookup");
        assertThat(synthesized.get(0).content()).contains("not_executed");
        assertThat(synthesized.get(0).content()).contains("工具调用未执行");
        // 合成结果只进发给模型的副本：图 state 里仍只有第一轮真实执行的 ToolMessage。
        List<String> stateToolIds = result.state().messages.stream()
                .filter(AgentMessage::isTool).map(AgentMessage::toolCallId).toList();
        assertThat(stateToolIds).containsExactly("c_kb");
    }

    private static List<AgentMessage> followers(List<AgentMessage> messages, int index, int count) {
        int from = Math.min(index + 1, messages.size());
        int to = Math.min(index + 1 + count, messages.size());
        return messages.subList(from, to);
    }

    @Test
    void pair_unexecuted_noops_when_tail_is_not_pending_calls() {
        List<AgentMessage> afterTools = List.of(
                AgentMessage.human("hi"),
                AgentMessage.ai("", List.of(call("fake_lookup", args("query", "x"), "c1"))),
                AgentMessage.tool("fake_lookup", "c1", "{\"ok\": true}"));
        assertThat(AgentGraph.pairUnexecutedToolCalls(afterTools)).isSameAs(afterTools);
        List<AgentMessage> plain = List.of(AgentMessage.human("hi"), AgentMessage.ai("直接答复"));
        assertThat(AgentGraph.pairUnexecutedToolCalls(plain)).isSameAs(plain);
        assertThat(AgentGraph.pairUnexecutedToolCalls(List.of())).isEmpty();
    }

    @Test
    void pair_unexecuted_repairs_tail_only() {
        // 只补末尾一条的悬空：历史中部未配对调用（输入腐化）不在 B4 范围。
        List<AgentMessage> history = List.of(
                AgentMessage.human("hi"),
                AgentMessage.ai("", List.of(call("fake_lookup", args("query", "x"), "c1"))),
                AgentMessage.human("追问"),
                AgentMessage.ai("", List.of(call("fake_lookup", args("query", "y"), "c2"))));
        List<AgentMessage> patched = AgentGraph.pairUnexecutedToolCalls(history);
        assertThat(patched).hasSize(history.size() + 1);
        AgentMessage tail = patched.get(patched.size() - 1);
        assertThat(tail.isTool()).isTrue();
        assertThat(tail.toolCallId()).isEqualTo("c2");
        assertThat(tail.content()).contains("not_executed");
        // 中部悬空的 c1 原样保留；原列表不被改写。
        assertThat(patched.stream().noneMatch(m -> m.isTool() && m.toolCallId().equals("c1")))
                .isTrue();
        assertThat(history).hasSize(4);
    }

    @Test
    void tool_exception_becomes_error_tool_message_and_graph_continues() {
        ScriptedChatModel model = new ScriptedChatModel(
                ScriptedChatModel.toolCall(call("always_broken", args("query", "x"), "call_1")),
                ScriptedChatModel.text("降级答复"));
        AgentGraph brokenGraph = AgentGraph.builder()
                .model(model)
                .tools(List.of(brokenTool()))
                .promptBuilder(state -> "系统提示")
                .maxRounds(3)
                .build();
        AgentGraph.GraphResult result = brokenGraph.run(List.of(AgentMessage.human("hi")), null);
        AgentMessage toolMessage = result.state().messages.stream()
                .filter(AgentMessage::isTool).findFirst().orElseThrow();
        assertThat(toolMessage.content()).contains("\"ok\": false");
        assertThat(toolMessage.content()).contains("tool_exception");
        assertThat(result.lastContent()).isEqualTo("降级答复");
    }

    @Test
    void unknown_tool_returns_structured_error() {
        ScriptedChatModel model = new ScriptedChatModel(
                ScriptedChatModel.toolCall(call("nope", Map.of(), "call_1")),
                ScriptedChatModel.text("无法识别工具后的答复"));
        AgentGraph.GraphResult result = graph(model, 3).run(
                List.of(AgentMessage.human("hi")), null);
        AgentMessage toolMessage = result.state().messages.stream()
                .filter(AgentMessage::isTool).findFirst().orElseThrow();
        assertThat(toolMessage.content()).contains("unknown_tool");
        assertThat(toolMessage.content()).contains("未知工具");
        assertThat(result.lastContent()).isEqualTo("无法识别工具后的答复");
    }

    // ------------------------------------------------------------------
    // 上下文预算（P0.1）：planner / finalize 共用分级收缩，不再裸抛
    // ------------------------------------------------------------------

    private static AgentToolStub bulkTool() {
        AgentToolStub tool = new AgentToolStub("bulk_lookup");
        tool.result.put("data", Map.of("query", "q",
                "chunks", List.of(Map.of("content", "长".repeat(20000)))));
        return tool;
    }

    private static AgentGraph budgetGraph(ScriptedChatModel model, AgentToolStub tool,
                                          int maxRounds, int budget) {
        return AgentGraph.builder()
                .model(model)
                .tools(List.of(tool))
                .promptBuilder(state -> "系统提示")
                .maxRounds(maxRounds)
                .contextMaxChars(budget)
                .build();
    }

    @Test
    void planner_compresses_oversized_tool_result_within_budget() {
        // 单轮工具结果远超预算（线上 4 题形状）时可继续生成，不抛 agent_context_budget。
        ScriptedChatModel model = new ScriptedChatModel(
                ScriptedChatModel.toolCall(call("bulk_lookup", args("query", "q"), "c1")),
                ScriptedChatModel.text("最终答复"));
        AgentGraph.GraphResult result = budgetGraph(model, bulkTool(), 3, 4000).run(
                List.of(AgentMessage.human("查一下")), null);

        assertThat(result.lastContent()).isEqualTo("最终答复");
        assertThat(model.calls).isEqualTo(2);
        assertThat(sentChars(model.seenMessages.get(1))).isLessThanOrEqualTo(4000);
        // 原始工具结果仍留在 state 里（压缩只作用于发给模型的副本）
        AgentMessage toolMessage = result.state().messages.stream()
                .filter(AgentMessage::isTool).findFirst().orElseThrow();
        assertThat(toolMessage.content().length()).isGreaterThan(20000);
    }

    @Test
    void finalize_compresses_oversized_history() {
        ScriptedChatModel model = new ScriptedChatModel(
                ScriptedChatModel.toolCall(call("bulk_lookup", args("query", "q1"), "c1")),
                ScriptedChatModel.toolCall(call("bulk_lookup", args("query", "q2"), "c2")),
                ScriptedChatModel.text("最终答复"));
        AgentGraph.GraphResult result = budgetGraph(model, bulkTool(), 1, 5000).run(
                List.of(AgentMessage.human("查一下")), null);
        assertThat(result.lastContent()).isEqualTo("最终答复");
        List<AgentMessage> finalizeMessages = model.seenMessages.get(2);
        assertThat(finalizeMessages.get(0).content().strip())
                .contains("注意：本轮对话的工具调用次数已达上限");
        assertThat(sentChars(finalizeMessages)).isLessThanOrEqualTo(5000);
    }

    @Test
    void finalize_note_counts_toward_budget() {
        // TOOL_LIMIT_NOTE 必须计入预算：否则 finalize 会稳定超出上限约一个附注长度。
        ScriptedChatModel generous = new ScriptedChatModel(
                ScriptedChatModel.toolCall(call("bulk_lookup", args("query", "q1"), "c1")),
                ScriptedChatModel.toolCall(call("bulk_lookup", args("query", "q2"), "c2")),
                ScriptedChatModel.text("最终答复"));
        budgetGraph(generous, bulkTool(), 1, 10_000_000).run(
                List.of(AgentMessage.human("查一下")), null);
        int measured = sentChars(generous.seenMessages.get(2));

        ScriptedChatModel tight = new ScriptedChatModel(
                ScriptedChatModel.toolCall(call("bulk_lookup", args("query", "q1"), "c1")),
                ScriptedChatModel.toolCall(call("bulk_lookup", args("query", "q2"), "c2")),
                ScriptedChatModel.text("最终答复"));
        budgetGraph(tight, bulkTool(), 1, measured - 1).run(
                List.of(AgentMessage.human("查一下")), null);
        assertThat(sentChars(tight.seenMessages.get(2))).isLessThanOrEqualTo(measured - 1);
    }

    @Test
    void floor_overflow_raises_typed_budget_error() {
        // 系统提示词 + 当前请求本身超限 → ContextBudgetExceeded，消息保留可观测标记。
        ScriptedChatModel model = new ScriptedChatModel(ScriptedChatModel.text("不该被调用"));
        AgentGraph overGraph = AgentGraph.builder()
                .model(model)
                .tools(List.of(echoTool()))
                .promptBuilder(state -> "系统提示")
                .maxRounds(3)
                .contextMaxChars(500)
                .build();
        assertThatThrownBy(() -> overGraph.run(
                List.of(AgentMessage.human("超长提问" + "x".repeat(5000))), null))
                .isInstanceOf(ContextBudgetExceededException.class)
                .hasMessageContaining("agent_context_budget");
        assertThat(model.calls).isEqualTo(0);
    }

    @Test
    void context_budget_disabled_is_noop() {
        String huge = "y".repeat(30000);
        ScriptedChatModel model = new ScriptedChatModel(ScriptedChatModel.text("最终答复"));
        budgetGraph(model, echoTool(), 3, 0).run(List.of(AgentMessage.human(huge)), null);
        assertThat(model.seenMessages.get(0).get(1).content()).isEqualTo(huge);
    }

    @Test
    void token_budget_hard_stops_to_finalize() {
        // M4a E9：planner 累计 token 超预算 → 硬转 finalize 兜底直出，不再进 tools。
        AgentToolStub tool = new AgentToolStub("fake_lookup");
        ScriptedChatModel model = new ScriptedChatModel(
                ScriptedChatModel.toolCallWithUsage(new AgentUsage(100, 20, 120),
                        call("fake_lookup", args("query", "dns"), "c1")),
                ScriptedChatModel.text("基于已获得信息的最终答复"));
        AgentGraph tokenGraph = AgentGraph.builder()
                .model(model)
                .tools(List.of(tool))
                .promptBuilder(state -> "系统提示")
                .maxRounds(5)
                .tokenBudget(100)
                .build();
        AgentGraph.GraphResult result = tokenGraph.run(List.of(AgentMessage.human("hi")), null);
        assertThat(result.lastContent()).isEqualTo("基于已获得信息的最终答复");
        assertThat(tool.invocations).isEqualTo(0);
        assertThat(model.systemPrompts.get(1)).contains("工具调用次数已达上限");
    }

    @Test
    void budget_crossed_midloop_finalizes() {
        // M4a E9：预算在第二轮 planner 后超限——第一轮工具正常执行，随后转 finalize。
        AgentToolStub tool = new AgentToolStub("fake_lookup");
        ScriptedChatModel model = new ScriptedChatModel(
                ScriptedChatModel.toolCallWithUsage(new AgentUsage(80, 20, 100),
                        call("fake_lookup", args("query", "1"), "c1")),
                ScriptedChatModel.toolCallWithUsage(new AgentUsage(80, 20, 100),
                        call("fake_lookup", args("query", "2"), "c2")),
                ScriptedChatModel.text("中途兜底答复"));
        AgentGraph tokenGraph = AgentGraph.builder()
                .model(model)
                .tools(List.of(tool))
                .promptBuilder(state -> "系统提示")
                .maxRounds(5)
                .tokenBudget(150)
                .build();
        AgentGraph.GraphResult result = tokenGraph.run(List.of(AgentMessage.human("hi")), null);
        assertThat(tool.invocations).isEqualTo(1);
        assertThat(result.lastContent()).isEqualTo("中途兜底答复");
        assertThat(model.systemPrompts.get(2)).contains("工具调用次数已达上限");
    }

    @Test
    void codepoint_budget_does_not_split_surrogates() {
        // 任务卡：字符上限按 Unicode 码点。非 BMP 字符计 1 个字符且截断不劈代理对。
        String bmpFree = "𠀀".repeat(300); // U+20000，每个码点占 2 个 UTF-16 单元
        assertThat(CodePoints.length(bmpFree)).isEqualTo(300);
        assertThat(bmpFree.length()).isEqualTo(600);
        assertThat(CodePoints.truncate(bmpFree, 250)).hasSize(500); // 250 码点 = 500 单元
        assertThat(CodePoints.length(CodePoints.truncate(bmpFree, 250))).isEqualTo(250);

        ScriptedChatModel model = new ScriptedChatModel(ScriptedChatModel.text("答复"));
        // 预算 400 码点：系统提示(4) + 请求(300 码点) 放得下。
        budgetGraph(model, echoTool(), 3, 400).run(List.of(AgentMessage.human(bmpFree)), null);
        AgentMessage seen = model.seenMessages.get(0).get(1);
        assertThat(seen.content()).isEqualTo(bmpFree);
    }

    // ------------------------------------------------------------------
    // 测试替身
    // ------------------------------------------------------------------

    /** 可编程假工具：echo 或固定结果/抛错。 */
    static final class AgentToolStub implements AgentTool {
        private final String name;
        private final boolean broken;
        public final Map<String, Object> result = new LinkedHashMap<>();
        public int invocations;

        AgentToolStub(String name) {
            this(name, false);
        }

        AgentToolStub(String name, boolean broken) {
            this.name = name;
            this.broken = broken;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "测试替身工具";
        }

        @Override
        public Map<String, Object> invoke(Map<String, Object> args) {
            invocations++;
            if (broken) {
                throw new IllegalStateException("kaboom");
            }
            if (result.isEmpty()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", true);
                out.put("data", Map.of("echo", args.getOrDefault("query", "")));
                return out;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.putAll(result);
            return out;
        }
    }
}
