package io.dpdk.assistant.chat;

import static org.assertj.core.api.Assertions.assertThat;

import io.dpdk.assistant.agent.AgentChatModel;
import io.dpdk.assistant.agent.AgentSettings;
import io.dpdk.assistant.agent.AgentUsage;
import io.dpdk.assistant.agent.ToolCallSpec;
import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import io.dpdk.assistant.contracts.model.Vector1024;
import io.dpdk.assistant.retrieval.HeuristicQueryRewriter;
import io.dpdk.assistant.retrieval.IndexConfigurationFingerprint;
import io.dpdk.assistant.retrieval.PgReleaseReader;
import io.dpdk.assistant.retrieval.RetrievalSettings;
import io.dpdk.assistant.task.TaskGradeLedger;
import io.dpdk.assistant.usage.UsageLedgerStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** P3规格审查阻断的真实业务回归；替身仅放模型、改写LLM、embedding等外部边界。 */
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class P3ReviewRegressionTest {
    private static ChatSettings budget(int tokens) {
        var d = ChatSettings.defaults();
        return new ChatSettings(d.requestTimeout(), d.prepareTimeout(), d.validationTimeout(),
                d.evidenceMaxChars(), tokens, d.ratePerMinute(), d.recentTurns(), d.summaryMaxChars(),
                d.memoryMaxChars(), d.summaryMaxTokens(), d.onlineJudgeEnabled());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void productBudgetStopsBeforeFirstOrCumulativeGradeSideEffectsAndRetainsSpentUsage(boolean cumulative)
            throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        int productBudget = 10;
        assertThat(AgentSettings.defaults().agentRequestTokenBudget()).isGreaterThan(productBudget);
        AgentChatModel model = (messages, names) -> {
            int call = modelCalls.incrementAndGet();
            if (cumulative && call == 1) {
                return new AgentChatModel.AgentReply("", List.of(new ToolCallSpec("search-first", "kb_search",
                        Map.of("query", "DNS"))), new AgentUsage(4, 2, 6));
            }
            return new AgentChatModel.AgentReply("", List.of(new ToolCallSpec("must-not-grade", "grade_plugin",
                    Map.of("protocol", "dns", "plugin_source", "/* synthetic budget probe */"))),
                    cumulative ? new AgentUsage(4, 2, 6) : new AgentUsage(7, 4, 11));
        };
        try (var h = ChatTestHarness.create("reviewbudget", model, null, budget(productBudget))) {
            h.seedKnowledge("budget-release", "node-dns", "DNS UDP protocol implementation knowledge");
            var input = h.input("请评测合成DNS插件");
            var answer = h.product.chat(h.student, input);
            assertThat(answer.answerStatus()).isEqualTo("system_error");
            assertThat(answer.answerReason()).isEqualTo("generation_failed");
            // 不能在发现产品预算超额后执行grade，也不能额外调用finalize。
            assertThat(modelCalls.get()).isEqualTo(cumulative ? 2 : 1);
            assertThat(h.harnessCalls.get()).isZero();
            assertThat(h.task.countRows("usage_counters", "subject='student' AND kind='grading'")).isZero();
            assertThat(h.task.countRows("grading_submissions", null)).isZero();
            assertThat(h.task.countRows("rag_task_jobs", null)).isZero();
            assertThat(h.task.columnValue("usage_counters", "chat_count", "subject='student' AND kind='chat'"))
                    .isEqualTo("1");
            assertThat(h.task.countRows("rag_task_inputs", "task_id=?", answer.taskId())).isZero();
            assertThat(h.task.countRows("upstream_usage_records", "request_id=? AND kind='llm'", input.requestId()))
                    .isEqualTo(cumulative ? 2 : 1);
            try (var connection = h.task.ds.getConnection(); var ps = connection.prepareStatement(
                    "SELECT SUM(total_tokens), SUM(call_count) FROM upstream_usage_records WHERE request_id=? AND kind='llm'")) {
                ps.setString(1, input.requestId());
                try (var rows = ps.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(cumulative ? 12 : 11);
                    assertThat(rows.getInt(2)).isEqualTo(cumulative ? 2 : 1);
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"DNS", "HTTP"})
    void persistedHistoryReachesFollowupRewriteEmbeddingAndOrderedCitations(String protocol) throws Exception {
        String question = "它如何实现？";
        String resolved = protocol.equals("DNS") ? "DNS engine_dns.cpp can_handle process"
                : "HTTP http_plugin.c can_handle process";
        String expectedPath = protocol.equals("DNS") ? "src/engine_dns.cpp" : "src/http_plugin.c";
        List<String> resolutionPrompts = new CopyOnWriteArrayList<>();
        List<String> embeddingQueries = new CopyOnWriteArrayList<>();
        AtomicInteger modelCalls = new AtomicInteger();
        AgentChatModel model = (messages, names) -> {
            modelCalls.incrementAndGet();
            if (messages.stream().noneMatch(m -> m.isTool())) {
                return new AgentChatModel.AgentReply("", List.of(new ToolCallSpec("followup-search", "kb_search",
                        Map.of("query", question))), new AgentUsage(3, 1, 4));
            }
            return AgentChatModel.AgentReply.text(protocol + "插件通过检测入口实现 [1]");
        };
        try (var h = ChatTestHarness.create("reviewfollowup", model, null)) {
            h.seedKnowledge("followup-release", "node-dns", "DNS engine_dns.cpp dns_ext.h can_handle process");
            h.seedKnowledge("followup-release", "node-http", "HTTP http_plugin.c engine_http.c can_handle process");
            try (var connection = h.task.ds.getConnection(); var statement = connection.createStatement()) {
                statement.execute("UPDATE data_li_knowledge_v1 SET metadata_='{\"source_path\":\"src/engine_dns.cpp\",\"title\":\"DNS\",\"section\":\"DNS\"}'::jsonb WHERE node_id='node-dns'");
                statement.execute("UPDATE data_li_knowledge_v1 SET metadata_='{\"source_path\":\"src/http_plugin.c\",\"title\":\"HTTP\",\"section\":\"HTTP\"}'::jsonb WHERE node_id='node-http'");
            }
            var rewriter = new HeuristicQueryRewriter((systemPrompt, userPrompt) -> {
                resolutionPrompts.add(userPrompt);
                assertThat(systemPrompt).contains("消解指代");
                assertThat(userPrompt).contains("历史对话：", "正在讨论 " + protocol + " 插件", question);
                return resolved;
            });
            var retrieval = new PgReleaseReader(h.task.ds, RetrievalSettings.defaults(),
                    new EmbeddingConfig("synthetic-embedding", "", 1024, "bounded-lines-v2"),
                    new IndexConfigurationFingerprint(), rewriter, input -> {
                        embeddingQueries.add(input.input());
                        return List.of(new Vector1024(new float[1024]));
                    });
            var settings = ChatSettings.defaults();
            var executor = new ChatTaskExecutor(h.task.sessions, h.task.runner, h.task.clock, model,
                    AgentSettings.defaults(), retrieval,
                    (tool, args, deadline) -> { throw new AssertionError("知识追问不得调用MCP"); },
                    h.task.store, new TaskGradeLedger(h.task.store, h.task.guard, h.task.runner, h.task.clock),
                    h.task.counters, new UsageLedgerStore(h.task.runner), settings);
            var product = new ProductChatService(h.task.service, h.task.gate, h.task.sessions, executor,
                    settings, h.task.clock);
            h.task.setRecoveryExecutor(executor);
            String session = h.task.sessions.ensureOwned(Optional.empty(), "已有历史", "student").value();
            // 真正写入会话历史，随后由ChatMemory读取，未直接给rewriter或SearchInput塞上下文。
            h.task.runner.required(tx -> {
                try {
                    h.task.sessions.appendMessage(tx, session, "user", "正在讨论 " + protocol + " 插件", Map.of());
                    h.task.sessions.appendMessage(tx, session, "assistant", protocol + "插件具有独立检测入口", Map.of());
                    return null;
                } catch (java.sql.SQLException e) { throw new IllegalStateException(e); }
            });
            var answer = product.chat(h.student,
                    new ChatInput(session, UUID.randomUUID().toString(), question, Map.of(), null));
            assertThat(answer.answerStatus()).isEqualTo("answered");
            assertThat(resolutionPrompts).hasSize(1);
            assertThat(embeddingQueries).containsExactly(question + " " + resolved);
            assertThat(modelCalls.get()).isEqualTo(2);
            assertThat(answer.citations()).isNotEmpty();
            assertThat(answer.citations().getFirst().sourcePath()).isEqualTo(expectedPath);
            assertThat(answer.citations().getFirst().chunkId()).contains(protocol.equals("DNS") ? "node-dns" : "node-http");
            assertThat(h.task.columnValue("assistant_sessions", "rag_release_id", "id=?", session))
                    .isEqualTo("followup-release");
        }
    }
}
