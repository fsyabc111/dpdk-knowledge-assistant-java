package io.dpdk.assistant.chat;

import static org.assertj.core.api.Assertions.*;

import io.dpdk.assistant.agent.*;
import io.dpdk.assistant.contracts.chat.SseEvent;
import io.dpdk.assistant.contracts.model.*;
import io.dpdk.assistant.task.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class ProductChatIntegrationTest {
    static AgentChatModel kbModel() {
        return (messages, tools) ->
                messages.stream().anyMatch(m -> m.isTool())
                        ? AgentChatModel.AgentReply.text("DNS 知识依据 [1]")
                        : new AgentChatModel.AgentReply(
                                "不应提前显示的供应商正文",
                                List.of(
                                        new ToolCallSpec(
                                                "kb", "kb_search", Map.of("query", "DNS"))),
                                AgentUsage.UNKNOWN);
    }

    @Test
    void nonstreamStreamReplayHaveStableIdentityAndNoDuplicateCalls() throws Exception {
        try (var h = ChatTestHarness.create("identity", kbModel(), null)) {
            h.seedKnowledge("r1", "node-dns", "DNS UDP protocol implementation knowledge");
            ChatInput input = h.input("DNS");
            var answer = h.product.chat(h.student, input);
            assertThat(answer.answerStatus()).isEqualTo("answered");
            assertThat(answer.answer()).isEqualTo("DNS 知识依据 [1]");
            assertThat(answer.citations()).hasSize(1);
            assertThat(h.modelCalls).hasValue(2);
            int calls = h.modelCalls.get();
            var replay = h.product.chat(h.student, input);
            assertThat(replay.taskId()).isEqualTo(answer.taskId());
            assertThat(replay.answer()).isEqualTo(answer.answer());
            assertThat(replay.citations().getFirst().score()).isEqualTo(0);
            assertThat(replay.nextSteps()).isEmpty();
            var events = collect(h.product.events(h.student, input));
            assertThat(events)
                    .extracting(SseEvent::name)
                    .containsExactly("start", "sources", "delta", "done");
            assertThat(h.modelCalls).hasValue(calls);
            assertThat(h.task.countRows("rag_requests", null)).isEqualTo(1);
            assertThat(h.task.countRows("assistant_messages", "role='user'")).isEqualTo(1);
            assertThat(
                            h.task.columnValue(
                                    "usage_counters",
                                    "chat_count",
                                    "subject='student' AND kind='chat'"))
                    .isEqualTo("1");
            assertThat(h.task.countRows("rag_task_inputs", null)).isZero();
            assertThat(ChatHttp.wire(events.getFirst()))
                    .endsWith(new byte[] {10, 10})
                    .doesNotContain((byte) 13);
        }
    }

    @Test
    void eightConcurrentInitialRequestsHaveOneTaskOneQuotaAndOneExecution() throws Exception {
        CountDownLatch modelEntered = new CountDownLatch(1), allow = new CountDownLatch(1);
        AgentChatModel model =
                (messages, tools) -> {
                    modelEntered.countDown();
                    try {
                        allow.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                    return AgentChatModel.AgentReply.text("没有证据");
                };
        try (var h = ChatTestHarness.create("parallel", model, null);
                var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            ChatInput input = h.input("DNS");
            List<Future<ProductChatService.StartResult>> futures = new ArrayList<>();
            for (int i = 0; i < 8; i++)
                futures.add(pool.submit(() -> h.product.start(h.student, input)));
            Set<String> ids = new HashSet<>();
            for (var f : futures) ids.add(f.get(10, TimeUnit.SECONDS).taskId());
            assertThat(modelEntered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(ids).hasSize(1);
            assertThat(h.modelCalls).hasValue(1);
            assertThat(h.task.countRows("rag_requests", null)).isEqualTo(1);
            assertThat(h.task.countRows("assistant_messages", "role='user'")).isEqualTo(1);
            assertThat(
                            h.task.columnValue(
                                    "usage_counters",
                                    "chat_count",
                                    "subject='student' AND kind='chat'"))
                    .isEqualTo("1");
            allow.countDown();
            var managed = h.task.registry.get(ids.iterator().next());
            h.task.service.waitResult(managed, java.time.Duration.ofSeconds(5));
        } finally {
            allow.countDown();
        }
    }

    @Test
    void failedSaveNeverEmitsCandidateAndLeavesRecoverableInputs() throws Exception {
        try (var h = ChatTestHarness.create("savefailure", kbModel(), null)) {
            h.seedKnowledge("r1", "node-dns", "DNS UDP protocol implementation knowledge");
            try (var conn = h.task.ds.getConnection();
                    var st = conn.createStatement()) {
                st.execute(
                        "CREATE FUNCTION reject_answer() RETURNS trigger LANGUAGE plpgsql AS $$"
                            + " BEGIN IF NEW.role='assistant' THEN RAISE EXCEPTION 'injected save"
                            + " failure'; END IF; RETURN NEW; END $$");
                st.execute(
                        "CREATE TRIGGER reject_answer BEFORE INSERT ON assistant_messages FOR EACH"
                                + " ROW EXECUTE FUNCTION reject_answer()");
            }
            var start = h.product.start(h.student, h.input("DNS"));
            h.task.service.waitResult(start.managed(), java.time.Duration.ofSeconds(5));
            List<SseEvent> events = collect(h.task.service.taskEvents(start.taskId(), "student"));
            assertThat(events).noneMatch(e -> "delta".equals(e.name()));
            assertThat(events.getLast().dataJson()).contains("\"terminal\":false");
            assertThat(h.task.countRows("assistant_messages", "role='assistant'")).isZero();
            assertThat(h.task.countRows("rag_task_inputs", null)).isEqualTo(1);
            assertThat(h.task.store.findOwned(start.taskId(), "student").orElseThrow().status())
                    .isEqualTo("pending");
        }
    }

    @Test
    void fixedSessionReleaseAndHistoricalCitationsDoNotDrift() throws Exception {
        try (var h = ChatTestHarness.create("release", kbModel(), null)) {
            h.seedKnowledge("r1", "old-dns", "DNS UDP old version");
            var first = h.product.chat(h.student, h.input("DNS"));
            String firstChunk = first.citations().getFirst().chunkId();
            h.seedKnowledge("r2", "new-dns", "DNS UDP new version");
            var follow =
                    new ChatInput(
                            first.sessionId(), UUID.randomUUID().toString(), "DNS", Map.of(), null);
            var second = h.product.chat(h.student, follow);
            assertThat(second.citations())
                    .extracting(CitationData::chunkId)
                    .containsExactly(firstChunk);
            assertThat(
                            h.task.columnValue(
                                    "assistant_sessions",
                                    "rag_release_id",
                                    "id=?",
                                    first.sessionId()))
                    .isEqualTo("r1");
            assertThat(h.retrieval.historical("llama_index", firstChunk)).isPresent();
        }
    }

    @Test
    void unconfiguredModelFailsBeforeTaskOrQuotaAndReplayStillWorks() throws Exception {
        try (var h = ChatTestHarness.create("missing", null, null)) {
            assertThatThrownBy(() -> h.product.start(h.student, h.input("DNS")))
                    .isInstanceOf(io.dpdk.assistant.platform.errors.ApiDetailException.class);
            assertThat(h.task.countRows("rag_requests", null)).isZero();
            assertThat(h.task.countRows("usage_counters", null)).isZero();
        }
    }

    @Test
    void emptyAndFailedGenerationUseProductVerdictsRatherThanAgentFallback() throws Exception {
        try (var h =
                ChatTestHarness.create(
                        "empty", (m, t) -> AgentChatModel.AgentReply.text(""), null)) {
            var answer = h.product.chat(h.student, h.input("Q"));
            assertThat(answer.answerStatus()).isEqualTo("unanswered");
            assertThat(answer.answerReason()).isEqualTo("empty_answer");
        }
        try (var h =
                ChatTestHarness.create(
                        "generation",
                        (m, t) -> {
                            throw new IllegalStateException("upstream failed");
                        },
                        null)) {
            var answer = h.product.chat(h.student, h.input("Q"));
            assertThat(answer.answerStatus()).isEqualTo("system_error");
            assertThat(answer.answerReason()).isEqualTo("generation_failed");
        }
    }

    @Test
    void prepareTimeoutCreatesNoTaskAndReturnsAdmissionSlot() throws Exception {
        var defaults = ChatSettings.defaults();
        var settings =
                new ChatSettings(
                        defaults.requestTimeout(),
                        java.time.Duration.ofMillis(40),
                        defaults.validationTimeout(),
                        defaults.evidenceMaxChars(),
                        defaults.requestTokenBudget(),
                        defaults.ratePerMinute(),
                        0,
                        defaults.summaryMaxChars(),
                        defaults.memoryMaxChars(),
                        defaults.summaryMaxTokens(),
                        false);
        try (var h =
                ChatTestHarness.create(
                        "preparewall",
                        (m, t) -> {
                            try {
                                Thread.sleep(5000);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw new IllegalStateException(interrupted);
                            }
                            return AgentChatModel.AgentReply.text("summary");
                        },
                        null,
                        settings)) {
            h.task.seedSession("old", "student");
            h.task.runner.required(
                    tx -> {
                        try {
                            h.task.sessions.appendMessage(
                                    tx, "old", "user", "old question", Map.of());
                            return null;
                        } catch (java.sql.SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    });
            ChatInput request =
                    new ChatInput("old", UUID.randomUUID().toString(), "Q", Map.of(), null);
            assertThatThrownBy(() -> h.product.start(h.student, request))
                    .isInstanceOf(io.dpdk.assistant.platform.errors.ApiDetailException.class);
            assertThat(h.task.countRows("rag_requests", null)).isZero();
            assertThat(h.task.countRows("usage_counters", null)).isZero();
            assertThat(h.task.gate.active()).isZero();
        }
    }

    @Test
    void explicitJudgeTimeoutUsesValidationFailureAndNeverPublishesCandidateBody()
            throws Exception {
        var defaults = ChatSettings.defaults();
        var settings =
                new ChatSettings(
                        defaults.requestTimeout(),
                        defaults.prepareTimeout(),
                        java.time.Duration.ofSeconds(1),
                        defaults.evidenceMaxChars(),
                        defaults.requestTokenBudget(),
                        defaults.ratePerMinute(),
                        defaults.recentTurns(),
                        defaults.summaryMaxChars(),
                        defaults.memoryMaxChars(),
                        defaults.summaryMaxTokens(),
                        false);
        try (var h = ChatTestHarness.create("judgewall", kbModel(), null, settings)) {
            h.seedKnowledge("r1", "li_judge", "DNS UDP protocol");
            h.executor.setJudge(
                    (q, a, e, t) -> {
                        Thread.sleep(5000);
                        return Map.of("status", "answered", "supported", true);
                    });
            var answer = h.product.chat(h.student, h.input("DNS"));
            assertThat(answer.answerReason()).isEqualTo("validation_failed");
            assertThat(answer.answer()).isEqualTo(AnswerValidator.UNAVAILABLE);
        }
    }

    @Test
    void usagePolicyDisablesWritesAndConfiguredPriceIsRecordedAtWriteTime() throws Exception {
        AgentChatModel known =
                (m, t) ->
                        new AgentChatModel.AgentReply(
                                "answer", List.of(), new AgentUsage(1000, 500, 1500));
        try (var h = ChatTestHarness.create("usageoff", known, null)) {
            h.executor.setUsagePolicy(false, Map.of(), "");
            h.product.chat(h.student, h.input("Q"));
            assertThat(h.task.countRows("upstream_usage_records", null)).isZero();
        }
        try (var h = ChatTestHarness.create("usageprice", known, null)) {
            h.executor.setUsagePolicy(
                    true,
                    io.dpdk.assistant.usage.UsageCore.parsePrices(
                            "{\"scripted-test-model\":{\"input_per_1k\":\"0.01\",\"output_per_1k\":\"0.02\",\"currency\":\"CNY\"}}"),
                    "synthetic-price-v1");
            var input = h.input("Q");
            h.product.chat(h.student, input);
            assertThat(
                            h.task.countRows(
                                    "upstream_usage_records", "request_id=?", input.requestId()))
                    .isEqualTo(1);
            assertThat(
                            h.task.columnValue(
                                    "upstream_usage_records",
                                    "cost_micros",
                                    "request_id=?",
                                    input.requestId()))
                    .isEqualTo("20000");
            assertThat(
                            h.task.columnValue(
                                    "upstream_usage_records",
                                    "price_version",
                                    "request_id=?",
                                    input.requestId()))
                    .isEqualTo("synthetic-price-v1");
            assertThat(
                            h.task.columnValue(
                                    "upstream_usage_records",
                                    "total_tokens",
                                    "request_id=?",
                                    input.requestId()))
                    .isEqualTo("1500");
        }
    }

    static List<SseEvent> collect(Flow.Publisher<SseEvent> publisher) throws Exception {
        List<SseEvent> out = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        publisher.subscribe(
                new Flow.Subscriber<>() {
                    public void onSubscribe(Flow.Subscription s) {
                        s.request(Long.MAX_VALUE);
                    }

                    public void onNext(SseEvent e) {
                        out.add(e);
                    }

                    public void onError(Throwable t) {
                        error.set(t);
                        done.countDown();
                    }

                    public void onComplete() {
                        done.countDown();
                    }
                });
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(error.get()).isNull();
        return out;
    }
}
