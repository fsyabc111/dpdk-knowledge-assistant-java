package io.dpdk.assistant.assignment.explanation;

import static org.assertj.core.api.Assertions.assertThat;

import io.dpdk.assistant.agent.AgentChatModel.AgentReply;
import io.dpdk.assistant.auth.AuthServerSettings;
import io.dpdk.assistant.contracts.model.HarnessReply;
import io.dpdk.assistant.chat.ProductChatService;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** HTTP→真实产品任务→真实Agent→持久化；替身限于模型、embedding、MCP。 */
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class ExplainReplayIntegrationTest {

    @Test
    void httpPermissionAndUnavailableHaveFrozenErrors() throws Exception {
        try (var h = new ExplainFlowHarness("http", null, null)) {
            var assignment = h.assignment();
            String grade = h.grade(assignment, false);
            assertThat(h.explain(assignment.id(), grade, null).getResponse().getStatus()).isEqualTo(401);
            assertThat(h.explain(assignment.id(), "missing", h.token).getResponse().getStatus()).isEqualTo(404);
            h.chat.task.seedUser("other", "[\"viewer\"]", 1000);
            String other = h.auth.issueToken("other").token();
            assertThat(h.explain(assignment.id(), grade, other).getResponse().getStatus()).isEqualTo(404);
            var second = h.assignment();
            assertThat(h.explain(second.id(), grade, h.token).getResponse().getStatus()).isEqualTo(404);
            var unavailable = h.explain(assignment.id(), grade, h.token);
            assertThat(unavailable.getResponse().getStatus()).isEqualTo(503);
            assertThat(h.body(unavailable).path("detail").asText()).isEqualTo("服务暂时不可用，请保留问题后重试");
            assertThat(h.chat.task.countRows("rag_requests", "")).isZero();
            h.catalog.setStatus(assignment.id(), "withdrawn", "teacher", List.of("leader"));
            assertThat(h.explain(assignment.id(), grade, h.token).getResponse().getStatus()).isEqualTo(404);
            h.chat.task.authSettings.replace(AuthServerSettings.of("local", "{}", 7, 8, 4, 200));
            assertThat(h.explain(assignment.id(), grade, null).getResponse().getStatus()).isEqualTo(404);
        }
    }

    @Test
    void concurrentFirstExplainAttachesOnceAndTitleChangeReplaysWithoutCharge() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger gradeCalls = new AtomicInteger(), references = new AtomicInteger();
        AtomicBoolean seeded = new AtomicBoolean(), hasReference = new AtomicBoolean();
        try (var h = new ExplainFlowHarness("concurrent", (messages, tools) -> {
            String system = messages.getFirst().content();
            seeded.set(system.contains("作业评测讲解模式") && system.contains("得分 80"));
            hasReference.set(system.contains("SYNTHETIC_REFERENCE"));
            entered.countDown();
            try {if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");}
            catch (InterruptedException e) {Thread.currentThread().interrupt(); throw new IllegalStateException(e);}
            if (messages.stream().noneMatch(m -> m.isTool() && "kb_search".equals(m.toolName())))
                return new AgentReply("", List.of(io.dpdk.assistant.agent.ToolCallSpec.of("kb-explain", "kb_search", Map.of("query", "dns"))), null);
            return AgentReply.text("该历史评测为80分，请检查DNS边界用例的长度处理。[1]");
        }, (tool, args, deadline) -> {
            if ("grade_plugin".equals(tool)) gradeCalls.incrementAndGet();
            if ("get_reference_answer".equals(tool)) references.incrementAndGet();
            return new HarnessReply(HarnessReply.SubmissionPhase.SUBMITTED,
                    "{\"ok\":true,\"protocol\":\"dns\",\"source\":\"SYNTHETIC_REFERENCE\"}", null);
        })) {
            var assignment = h.assignment();
            String grade = h.grade(assignment, false), snapshot = h.gradeSnapshot(grade);
            var prepared = h.data.prepare(assignment.id(), grade, h.chat.student);
            List<ProductChatService.StartResult> starts = new CopyOnWriteArrayList<>();
            try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<?>> futures = new ArrayList<>();
                for (int n=0;n<12;n++) futures.add(pool.submit(() -> starts.add(h.chat.product.startExplain(
                        h.chat.student, prepared.identity().sessionId(), prepared.identity().requestId(),
                        prepared.question(), prepared.context(), () -> h.data.ensureSession(prepared, "student")))));
                for (var future : futures) future.get(10, TimeUnit.SECONDS);
            }
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(starts).hasSize(12);
            assertThat(starts.stream().map(ProductChatService.StartResult::taskId).distinct()).hasSize(1);
            assertThat(starts.stream().filter(s -> "started".equals(s.status()))).hasSize(1);
            assertThat(starts.stream().filter(s -> "attached".equals(s.status()))).hasSize(11);
            String taskId = starts.getFirst().taskId();
            var active = h.explain(assignment.id(), grade, h.token);
            assertThat(h.body(active).path("status").asText()).isEqualTo("attached");
            assertThat(h.body(active).path("task_id").asText()).isEqualTo(taskId);
            release.countDown();
            var managed = h.chat.task.registry.get(taskId);
            assertThat(managed.awaitDone(Duration.ofSeconds(10))).isTrue();
            assertThat(h.chat.task.store.findOwned(taskId, "student").orElseThrow().status()).isEqualTo("answered");
            h.catalog.updateAssignment(assignment.id(), "teacher", List.of("leader"), "标题修改", "",
                    assignment.deadline(), assignment.items());
            var replay = h.explain(assignment.id(), grade, h.token);
            assertThat(replay.getResponse().getStatus()).isEqualTo(200);
            var replayBody = h.body(replay);
            assertThat(replayBody.fieldNames()).toIterable().containsExactly("session_id", "task_id", "status");
            assertThat(replayBody.path("status").asText()).isEqualTo("replayed");
            assertThat(replayBody.path("task_id").asText()).isEqualTo(taskId);
            assertThat(replayBody.path("session_id").asText()).isEqualTo("explain-" + grade);
            assertThat(h.chat.modelCalls).hasValue(2);
            assertThat(gradeCalls).hasValue(0);
            assertThat(references).hasValue(1);
            assertThat(seeded).isTrue();
            assertThat(hasReference).isTrue();
            assertThat(h.chat.task.countRows("rag_requests", "session_id=?", prepared.identity().sessionId())).isEqualTo(1);
            assertThat(h.chat.task.countRows("assistant_messages", "session_id=? AND role='user'", prepared.identity().sessionId())).isEqualTo(1);
            assertThat(h.chat.task.columnValue("usage_counters", "chat_count", "subject=? AND kind='chat'", "student")).isEqualTo("1");
            assertThat(h.chat.task.service.loadInputs(taskId)).isEmpty();
            assertThat(h.chat.task.countRows("grading_submissions", "")).isEqualTo(1);
            assertThat(h.gradeSnapshot(grade)).isEqualTo(snapshot);
            assertThat(h.chat.task.columnValue("rag_requests", "purpose", "id=?", taskId)).isEqualTo("explain");
        } finally {release.countDown();}
    }

    @Test
    void directExplainWithoutToolEvidenceKeepsFrozenNoEvidenceVerdict() throws Exception {
        try (var h = new ExplainFlowHarness("noevidence", (messages, tools) -> AgentReply.text("历史评测全部通过。"), null)) {
            var assignment = h.assignment();
            String grade = h.grade(assignment, true);
            String taskId = h.body(h.explain(assignment.id(), grade, h.token)).path("task_id").asText();
            var managed = h.chat.task.registry.get(taskId);
            assertThat(managed.awaitDone(Duration.ofSeconds(10))).isTrue();
            var row = h.chat.task.store.findOwned(taskId, "student").orElseThrow();
            assertThat(row.status()).isEqualTo("unanswered");
            assertThat(row.reason()).isEqualTo("no_evidence");
            assertThat(h.chat.harnessCalls).hasValue(0);
        }
    }

    @Test
    void allPassSkipsReferenceAndNeverRegrades() throws Exception {
        AtomicBoolean seeded = new AtomicBoolean();
        try (var h = new ExplainFlowHarness("allpass", (messages, tools) -> {
            seeded.set(messages.getFirst().content().contains("得分 100"));
            if (messages.stream().noneMatch(m -> m.isTool() && "kb_search".equals(m.toolName())))
                return new AgentReply("", List.of(io.dpdk.assistant.agent.ToolCallSpec.of("kb-pass", "kb_search", Map.of("query", "dns"))), null);
            return AgentReply.text("该次历史评测全部通过，已达到本次作业的协议要求。[1]");
        }, null)) {
            var assignment = h.assignment();
            String grade = h.grade(assignment, true), snapshot = h.gradeSnapshot(grade);
            var response = h.explain(assignment.id(), grade, h.token);
            assertThat(response.getResponse().getStatus()).isEqualTo(200);
            String taskId = h.body(response).path("task_id").asText();
            assertThat(h.body(response).path("status").asText()).isEqualTo("started");
            assertThat(h.chat.task.registry.get(taskId).awaitDone(Duration.ofSeconds(10))).isTrue();
            assertThat(h.chat.task.store.findOwned(taskId, "student").orElseThrow().status()).isEqualTo("answered");
            assertThat(seeded).isTrue();
            assertThat(h.chat.harnessCalls).hasValue(0);
            assertThat(h.chat.task.countRows("grading_submissions", "")).isEqualTo(1);
            assertThat(h.gradeSnapshot(grade)).isEqualTo(snapshot);
        }
    }
}
