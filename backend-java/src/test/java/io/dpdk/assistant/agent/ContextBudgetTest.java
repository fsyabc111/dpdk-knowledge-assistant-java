package io.dpdk.assistant.agent;

import static org.assertj.core.api.Assertions.assertThat;

import io.dpdk.assistant.agent.context.CodePoints;
import io.dpdk.assistant.agent.context.ContextBudget;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 上下文预算分级收缩管线测试（移植 test_agent_context_prepare.py 的核心行为）。
 *
 * <p>配对不变式：带 tool_calls 的 AI 消息与其全部 ToolMessage 是原子单元；
 * ToolMessage 只改 content，AI 只改 tool_calls 参数，id/顺序不变；
 * 「已执行」= id 有配对 ToolMessage。字符计量按 Unicode 码点。
 */
class ContextBudgetTest {

    private static ContextBudget.Summarizer PASS_THROUGH = (toolName, content) ->
            "{\"tool\": \"" + toolName + "\", \"ok\": true, \"summary\": \""
                    + (content.length() > 10 ? "已压缩" : content) + "\"}";

    // 有界摘要（模拟 ToolSummaries 的收敛形态：短于原文）。
    private static ContextBudget.Summarizer BOUNDED = (toolName, content) -> "{\"s\":\"压\"}";

    private static AgentMessage human(String content) {
        return AgentMessage.human(content);
    }

    private static AgentMessage aiWithCalls(String id) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("query", "q" + id);
        return AgentMessage.ai("", List.of(ToolCallSpec.of(id, "kb_search", args)));
    }

    private static AgentMessage toolResult(String id, String content) {
        return AgentMessage.tool("kb_search", id, content);
    }

    private static String bigToolResult(int chars) {
        return "{\"ok\": true, \"data\": {\"chunks\": [\"" + "长".repeat(chars) + "\"]}}";
    }

    private static List<AgentMessage> twoRounds(int firstChars, int secondChars) {
        return List.of(
                human("查一下"),
                aiWithCalls("c1"),
                toolResult("c1", bigToolResult(firstChars)),
                aiWithCalls("c2"),
                toolResult("c2", bigToolResult(secondChars)));
    }

    // ------------------------------------------------------------------
    // 分级
    // ------------------------------------------------------------------

    @Test
    void prep01_single_oversized_round_is_compressed() {
        List<AgentMessage> history = List.of(
                human("查一下"), aiWithCalls("c1"), toolResult("c1", bigToolResult(3000)));
        ContextBudget.PreparedContext prepared = ContextBudget.prepare(
                "系统提示", history, 1000, BOUNDED);
        assertThat(prepared.level()).isGreaterThanOrEqualTo(1);
        assertThat(prepared.totalChars()).isLessThanOrEqualTo(1000);
        assertThat(prepared.overBudget()).isFalse();
        // 原始 history 不被改写
        assertThat(history.get(2).content().length()).isGreaterThan(3000);
    }

    @Test
    void prep02_multi_round_compresses_older_keeps_latest_raw() {
        List<AgentMessage> history = twoRounds(2000, 2000);
        ContextBudget.PreparedContext prepared = ContextBudget.prepare(
                "系统提示", history, 5000, BOUNDED);
        AgentMessage first = prepared.messages().get(3); // system + h + ai + tool(c1)
        AgentMessage last = prepared.messages().get(5);  // ai(c2) 之后是 tool(c2)
        assertThat(first.isTool()).isTrue();
        assertThat(first.content()).isEqualTo("{\"s\":\"压\"}");
        assertThat(last.isTool()).isTrue();
        assertThat(last.content()).isEqualTo(history.get(4).content());
    }

    @Test
    void prep03_at_baseline_limit_stays_at_level_one() {
        List<AgentMessage> history = twoRounds(1000, 500);
        int baselineChars = ContextBudget.messageChars(history.get(2));
        int total = "系统提示".codePointCount(0, 4) + ContextBudget.messagesChars(history);
        // 预算恰为基线总体积：第 1 级只压缩早轮，需保证 fits。直接用大预算观察 level=1。
        ContextBudget.PreparedContext generous = ContextBudget.prepare(
                "系统提示", history, total + 10, BOUNDED);
        assertThat(generous.level()).isEqualTo(1);
        // 紧缩到「只压早轮才放得下」：把预算卡在 raw 与压缩后之间。
        int rawTool = ContextBudget.messageChars(history.get(4));
        int compressedTool = ContextBudget.messageChars(
                history.get(4).withContent(BOUNDED.summarize("kb_search", history.get(4).content())));
        ContextBudget.PreparedContext squeezed = ContextBudget.prepare(
                "系统提示", history, total - rawTool + compressedTool, BOUNDED);
        assertThat(squeezed.level()).isEqualTo(1);
        assertThat(squeezed.totalChars()).isLessThanOrEqualTo(total - rawTool + compressedTool);
        assertThat(baselineChars).isGreaterThan(0);
    }

    @Test
    void prep04_pairing_survives_every_level() {
        List<AgentMessage> history = twoRounds(2000, 2000);
        for (int budget : List.of(3000, 1500, 800, 500)) {
            ContextBudget.PreparedContext prepared = ContextBudget.prepare(
                    "系统提示", history, budget, BOUNDED);
            assertPairingValid(prepared.messages(), budget);
        }
    }

    private static void assertPairingValid(List<AgentMessage> messages, int budget) {
        int aiIndex = -1;
        for (int i = 0; i < messages.size(); i++) {
            AgentMessage message = messages.get(i);
            if (!message.toolCalls().isEmpty()) {
                aiIndex = i;
                int k = 0;
                for (ToolCallSpec call : message.toolCalls()) {
                    AgentMessage follower = messages.get(i + 1 + k);
                    assertThat(follower.isTool()).as("预算 %d 下配对完整", budget).isTrue();
                    assertThat(follower.toolCallId()).isEqualTo(call.id());
                    k++;
                }
                // id/类型/顺序不变
                assertThat(messages.get(aiIndex).toolCalls().get(0).id()).isNotEmpty();
            }
        }
        assertThat(aiIndex).isGreaterThan(0);
    }

    @Test
    void prep05_counts_tool_call_args() {
        // message_chars 计入 tool_calls 的参数（grade_plugin 的 plugin_source 可达数 KB）。
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("protocol", "dns");
        args.put("plugin_source", "x".repeat(5000));
        AgentMessage ai = AgentMessage.ai("", List.of(ToolCallSpec.of("c1", "grade_plugin", args)));
        int chars = ContextBudget.messageChars(ai);
        assertThat(chars).isGreaterThanOrEqualTo(5000);
        // 只算 content 会显著低估：content 为空
        assertThat(chars).isGreaterThan(CodePoints.length(ai.content()));
    }

    @Test
    void prep06_disabled_is_noop() {
        List<AgentMessage> history = twoRounds(3000, 3000);
        ContextBudget.PreparedContext prepared = ContextBudget.prepare(
                "系统提示", history, 0, BOUNDED);
        assertThat(prepared.level()).isEqualTo(0);
        assertThat(prepared.overBudget()).isFalse();
        assertThat(prepared.messages().get(1)).isSameAs(history.get(0));
        assertThat(prepared.messages().get(3)).isSameAs(history.get(2));
    }

    @Test
    void prep07_floor_overflow_reports_instead_of_raising() {
        List<AgentMessage> history = List.of(human("超长提问"));
        ContextBudget.PreparedContext prepared = ContextBudget.prepare(
                "系统提示", history, 5, BOUNDED);
        assertThat(prepared.overBudget()).isTrue();
        assertThat(prepared.level()).isEqualTo(5);
        assertThat(prepared.stats()).containsEntry("reason", "floor_exceeded");
        // 地板 = 系统提示词 + 未丢弃的历史（当前请求受保护）
        assertThat(prepared.messages()).hasSize(2);
    }

    @Test
    void prep07b_large_reference_prompt_still_fits_with_trim() {
        // 系统提示词占大半预算（线上参考实现 13076/24000 形状）仍可收缩历史适配。
        String bigPrompt = "参考".repeat(3000); // 6000 码点
        List<AgentMessage> history = twoRounds(3000, 3000);
        ContextBudget.PreparedContext prepared = ContextBudget.prepare(
                bigPrompt, history, 8000, BOUNDED);
        assertThat(prepared.overBudget()).isFalse();
        assertThat(prepared.totalChars()).isLessThanOrEqualTo(8000);
    }

    @Test
    void prep11_completed_last_round_args_are_compactable() {
        // 第 3 级：已执行轮的长实参压成占位符（per-call 判据，最后单元也要压）。
        Map<String, Object> bigArgs = new LinkedHashMap<>();
        bigArgs.put("plugin_source", "s".repeat(1000));
        List<AgentMessage> history = new ArrayList<>(List.of(
                human("评测"),
                AgentMessage.ai("", List.of(ToolCallSpec.of("c1", "grade_plugin", bigArgs))),
                toolResult("c1", "{\"ok\": true}")));
        List<AgentMessage> level3 = ContextBudget.compactHistoricalArgs(history);
        ToolCallSpec compacted = level3.get(1).toolCalls().get(0);
        assertThat(compacted.args().get("plugin_source")).isEqualTo("[已省略 1000 字]");
        assertThat(compacted.id()).isEqualTo("c1");
        assertThat(level3).hasSameSizeAs(history);
    }

    @Test
    void prep11b_pending_call_args_keep_raw() {
        // 无配对 ToolMessage 的调用是模型刚发出的请求：参数原文保留。
        Map<String, Object> bigArgs = new LinkedHashMap<>();
        bigArgs.put("plugin_source", "s".repeat(1000));
        List<AgentMessage> history = List.of(
                human("评测"),
                AgentMessage.ai("", List.of(ToolCallSpec.of("c1", "grade_plugin", bigArgs))));
        List<AgentMessage> level3 = ContextBudget.compactHistoricalArgs(history);
        assertThat(level3.get(1).toolCalls().get(0).args().get("plugin_source"))
                .isEqualTo("s".repeat(1000));
    }

    @Test
    void prep11c_drop_units_drops_pending_keeps_answered() {
        // 第 5 级：末尾悬空的未执行调用单元可整单元丢弃；含最后已执行轮与最后
        // 用户消息的单元受保护。
        List<AgentMessage> history = new ArrayList<>(List.of(
                human("问题"),
                aiWithCalls("c1"),
                toolResult("c1", bigToolResult(1000)),
                AgentMessage.ai("", List.of(ToolCallSpec.of("c2", "kb_search",
                        Map.of("query", "q2"))))));
        List<int[]> slices = ContextBudget.unitSlices(history);
        assertThat(slices).hasSize(3);
        List<List<AgentMessage>> candidates = ContextBudget.dropUnits(history, slices);
        // 受保护：human 单元 + 已执行轮(c1) 单元 → 唯一可丢的是末尾悬空 c2 单元。
        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0)).hasSize(3);
        assertThat(candidates.get(0).stream().noneMatch(m ->
                !m.toolCalls().isEmpty() && m.toolCalls().get(0).id().equals("c2"))).isTrue();
    }

    @Test
    void drop_units_protects_last_answered_round_and_last_human() {
        List<AgentMessage> history = new ArrayList<>(List.of(
                human("问题"),
                aiWithCalls("c1"),
                toolResult("c1", bigToolResult(500)),
                human("追问"),
                aiWithCalls("c2"),
                toolResult("c2", bigToolResult(500)),
                AgentMessage.ai("", List.of(ToolCallSpec.of("c3", "kb_search",
                        Map.of("query", "pending"))))));
        List<int[]> slices = ContextBudget.unitSlices(history);
        List<List<AgentMessage>> candidates = ContextBudget.dropUnits(history, slices);
        // 可丢：c1 轮（已执行但非最后）、追问前?（问题单元受保护）… 保护的是
        // 最后 human（追问）与最后已执行轮 c2。可丢单元 = c1 轮 + 悬空 c3 轮。
        assertThat(candidates).hasSize(3);
        for (List<AgentMessage> candidate : candidates) {
            assertThat(candidate.stream().anyMatch(m -> m.isHuman()
                    && m.content().equals("追问"))).isTrue();
            assertThat(candidate.stream().anyMatch(m -> m.isTool()
                    && m.toolCallId().equals("c2"))).isTrue();
        }
    }

    @Test
    void splitlines_matches_python_semantics() {
        assertThat(CodePoints.splitLines("a\nb")).containsExactly("a", "b");
        assertThat(CodePoints.splitLines("a\n")).containsExactly("a");
        assertThat(CodePoints.splitLines("")).isEmpty();
        assertThat(CodePoints.splitLines("a\r\nb")).containsExactly("a", "b");
        assertThat(CodePoints.splitLines("a\u2028b")).containsExactly("a", "b");
    }

    @Test
    void codepoint_substring_safe() {
        String mixed = "a𠀀b"; // 4 个 UTF-16 单元 = 3 码点
        assertThat(CodePoints.length(mixed)).isEqualTo(3);
        assertThat(CodePoints.substring(mixed, 1, 2)).isEqualTo("𠀀");
        assertThat(CodePoints.substring(mixed, 2, 99)).isEqualTo("b");
        assertThat(CodePoints.truncate(mixed, 2)).isEqualTo("a𠀀");
    }
}
