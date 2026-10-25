package io.dpdk.assistant.assignment.explanation;

import io.dpdk.assistant.agent.*;
import io.dpdk.assistant.assignment.catalog.*;
import io.dpdk.assistant.assignment.submission.SubmissionRecordReader;
import io.dpdk.assistant.chat.*;
import io.dpdk.assistant.contracts.model.*;
import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import io.dpdk.assistant.retrieval.*;
import java.util.*;
import io.dpdk.assistant.platform.pg.*;
import io.dpdk.assistant.task.*;
import io.dpdk.assistant.usage.UsageLedgerStore;
import java.time.Duration;
import java.util.concurrent.atomic.*;

/** 独立 JVM：无原进程注册表/讲解缓存，真实任务引擎+Agent 从可信成绩恢复。 */
public final class ExplainRecoveryProcess {
    public static void main(String[] args) throws Exception {
        TaskTestHarness h = TaskTestHarness.attach(args[0]);
        try (var c = h.ds.getConnection()) {DatabaseIdentityGuard.verify(c, TestPg.identity(h.database));}
        h.clock.advanceWall(Duration.ofMinutes(2));
        AtomicInteger calls = new AtomicInteger(), grade = new AtomicInteger(), references = new AtomicInteger();
        AtomicBoolean rebuilt = new AtomicBoolean();
        AgentChatModel model = (messages, tools) -> {
            calls.incrementAndGet();
            String system = messages.getFirst().content();
            rebuilt.set(system.contains("恢复后可信标题") && system.contains("得分 80")
                    && system.contains("SYNTHETIC_REFERENCE"));
            if (messages.stream().noneMatch(m -> m.isTool() && "kb_search".equals(m.toolName())))
                return new AgentChatModel.AgentReply("", List.of(ToolCallSpec.of("kb-recovered", "kb_search", Map.of("query", "dns"))), null);
            return AgentChatModel.AgentReply.text("独立进程已从可信成绩恢复讲解，历史得分80分。[1]");
        };
        HarnessPort harness = (tool, params, deadline) -> {
            if ("grade_plugin".equals(tool)) grade.incrementAndGet();
            if ("get_reference_answer".equals(tool)) references.incrementAndGet();
            return new HarnessReply(HarnessReply.SubmissionPhase.SUBMITTED,
                    "{\"ok\":true,\"source\":\"SYNTHETIC_REFERENCE\"}", null);
        };
        var retrieval = new PgReleaseReader(h.ds, RetrievalSettings.defaults(),
                new EmbeddingConfig("synthetic-embedding", "", 1024, "bounded-lines-v2"),
                new IndexConfigurationFingerprint(), new HeuristicQueryRewriter(),
                input -> List.of(new Vector1024(new float[1024])));
        var executor = new ChatTaskExecutor(h.sessions, h.runner, h.clock, model, AgentSettings.defaults(),
                retrieval, harness, h.store, new TaskGradeLedger(h.store, h.guard, h.runner, h.clock),
                h.counters, new UsageLedgerStore(h.runner), ChatSettings.defaults());
        var catalog = new AssignmentCatalogService(new AssignmentCatalogRepository(h.runner, h.clock));
        var data = new ExplainDataService(h.adminUsers(), catalog, new SubmissionRecordReader(h.runner),
                new ExplainSessionRepository(h.runner, h.clock));
        executor.setExplainResolver(data::resolve);
        h.setRecoveryExecutor(executor);
        var managed = h.recovery.ensureManaged(h.store.findOwned(args[1], "student").orElseThrow()).orElseThrow();
        if (!managed.awaitDone(Duration.ofSeconds(15))) throw new IllegalStateException("recovery did not finish");
        String status = h.store.findOwned(args[1], "student").orElseThrow().status();
        System.out.println("RECOVERED_CONTEXT=" + rebuilt.get());
        System.out.println("MODEL_CALLS=" + calls.get());
        System.out.println("GRADE_CALLS=" + grade.get());
        System.out.println("REFERENCE_CALLS=" + references.get());
        System.out.println("STATUS=" + status);
        h.taskRunner.shutdownWatchdog();
        if (!rebuilt.get() || calls.get()!=2 || grade.get()!=0 || references.get()!=1 || !"answered".equals(status))
            throw new IllegalStateException("recovery contract assertion failed");
    }
}
