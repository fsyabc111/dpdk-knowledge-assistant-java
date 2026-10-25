package io.dpdk.assistant.chat;

import io.dpdk.assistant.agent.*;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import io.dpdk.assistant.contracts.model.*;
import io.dpdk.assistant.retrieval.*;
import io.dpdk.assistant.task.*;
import io.dpdk.assistant.usage.UsageLedgerStore;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** J3-03/04 真实业务基座；仅模型、embedding、MCP 为固定外部替身。 */
public final class ChatTestHarness implements AutoCloseable {
    public final TaskTestHarness task;
    public final ChatTaskExecutor executor;
    public final ProductChatService product;
    public final PgReleaseReader retrieval;
    public final AtomicInteger modelCalls = new AtomicInteger();
    public final AtomicInteger harnessCalls = new AtomicInteger();
    public final Principal student = new Principal("student", List.of("viewer"), 10000, true);

    private ChatTestHarness(
            TaskTestHarness task, AgentChatModel model, HarnessPort harness, ChatSettings settings)
            throws Exception {
        this.task = task;
        task.seedUser("student", "[\"viewer\"]", 10000);
        task.seedUser("teacher", "[\"operator\"]", 10000);
        retrieval =
                new PgReleaseReader(
                        task.ds,
                        RetrievalSettings.defaults(),
                        new EmbeddingConfig("synthetic-embedding", "", 1024, "bounded-lines-v2"),
                        new IndexConfigurationFingerprint(),
                        new HeuristicQueryRewriter(),
                        input -> List.of(new Vector1024(new float[1024])));
        AgentChatModel counting =
                model == null
                        ? null
                        : (messages, tools) -> {
                            modelCalls.incrementAndGet();
                            return model.call(messages, tools);
                        };
        HarnessPort countingHarness =
                (tool, args, deadline) -> {
                    harnessCalls.incrementAndGet();
                    if (harness == null)
                        throw new IllegalStateException("unexpected harness call: " + tool);
                    return harness.call(tool, args, deadline);
                };
        executor =
                new ChatTaskExecutor(
                        task.sessions,
                        task.runner,
                        task.clock,
                        counting,
                        AgentSettings.defaults(),
                        retrieval,
                        countingHarness,
                        task.store,
                        new TaskGradeLedger(task.store, task.guard, task.runner, task.clock),
                        task.counters,
                        new UsageLedgerStore(task.runner),
                        settings);
        product =
                new ProductChatService(
                        task.service, task.gate, task.sessions, executor, settings, task.clock);
        task.setRecoveryExecutor(executor);
    }

    public static ChatTestHarness create(String tag, AgentChatModel model, HarnessPort harness)
            throws Exception {
        return create(tag, model, harness, ChatSettings.defaults());
    }

    public static ChatTestHarness create(
            String tag, AgentChatModel model, HarnessPort harness, ChatSettings settings)
            throws Exception {
        return new ChatTestHarness(TaskTestHarness.create("j303" + tag), model, harness, settings);
    }

    public ChatInput input(String question) {
        return new ChatInput(null, UUID.randomUUID().toString(), question, Map.of(), null);
    }

    public void seedKnowledge(String release, String node, String text) throws Exception {
        var config = new EmbeddingConfig("synthetic-embedding", "", 1024, "bounded-lines-v2");
        String fingerprint =
                new IndexConfigurationFingerprint().pythonCompatibleConfiguration(config);
        try (var conn = task.ds.getConnection()) {
            try (var ps =
                    conn.prepareStatement(
                            "INSERT INTO data_li_knowledge_v1(node_id,text,metadata_,embedding)"
                                    + " VALUES (?,?,?::jsonb,?::vector)")) {
                ps.setString(1, node);
                ps.setString(2, text);
                ps.setString(
                        3, "{\"source_path\":\"src/dns.c\",\"title\":\"DNS\",\"section\":\"DNS\"}");
                ps.setString(4, "[" + String.join(",", Collections.nCopies(1024, "0")) + "]");
                ps.executeUpdate();
            }
            try (var ps =
                    conn.prepareStatement(
                            "INSERT INTO rag_releases(id,configuration,created_at,node_count)"
                                    + " VALUES (?,?,?,1) ON CONFLICT DO NOTHING")) {
                ps.setString(1, release);
                ps.setString(2, fingerprint);
                ps.setString(3, task.clock.nextSessionIso());
                ps.executeUpdate();
            }
            try (var ps =
                    conn.prepareStatement(
                            "INSERT INTO rag_release_nodes(release_id,node_id) VALUES (?,?)")) {
                ps.setString(1, release);
                ps.setString(2, node);
                ps.executeUpdate();
            }
            try (var ps =
                    conn.prepareStatement(
                            "UPDATE rag_index_state SET release_id=? WHERE name='active'")) {
                ps.setString(1, release);
                ps.executeUpdate();
            }
        }
    }

    @Override
    public void close() throws Exception {
        task.taskRunner.shutdownWatchdog();
        task.drop();
    }
}
