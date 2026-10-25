package io.dpdk.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;

import io.dpdk.assistant.contracts.model.ModelDelta;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 终态回调就是业务flush边界：在回调内部保存账本快照，不靠等待finally或sleep消竞态。 */
class StreamTerminalLedgerVisibilityTest {
    private OpenAiCompatibleChatClient client;

    @AfterEach
    void cleanup() { if (client != null) client.close(); }

    private void streamAndInspectAtTerminal(MockHttpServer server, boolean expectedError) throws Exception {
        var io = Executors.newSingleThreadExecutor();
        client = new OpenAiCompatibleChatClient(new ChatClientSettings(server.baseUrl(), "synthetic-key", "synthetic-model",
                64, 2, 0, 5, true), new UpstreamGate(2), OpenAiCompatibleChatClient.defaultRestClient(5), io, true);
        ModelUsageLedger ledger = ModelUsageLedger.create();
        AtomicReference<List<ModelUsageEntry>> terminalSnapshot = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        AtomicInteger terminalCalls = new AtomicInteger();
        List<String> deltas = new CopyOnWriteArrayList<>();
        CountDownLatch terminal = new CountDownLatch(1);
        client.streamChat(List.of(new ChatMessage("user", "synthetic question")), "synthetic-model", ledger, "answer")
                .subscribe(new Flow.Subscriber<>() {
                    public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                    public void onNext(ModelDelta item) { deltas.add(item.text()); }
                    public void onComplete() { capture(); }
                    public void onError(Throwable failure) { error.set(failure); capture(); }
                    private void capture() {
                        // entries()返回不可变快照：旧实现此处必为空，即使finally随后记账也不能把红变绿。
                        terminalSnapshot.set(ledger.entries());
                        terminalCalls.incrementAndGet();
                        terminal.countDown();
                    }
                });
        assertThat(terminal.await(5, TimeUnit.SECONDS)).isTrue();
        // 同executor的排队哨兵确认runStream已退出：区分“晚记”与“根本漏记”。
        assertThat(io.submit(ledger::entries).get(5, TimeUnit.SECONDS)).hasSize(1);
        assertThat(terminalCalls.get()).isEqualTo(1);
        assertThat(terminalSnapshot.get()).as("终态回调可见前必须已有唯一账，业务立即flush不漏记").hasSize(1);
        assertThat(ledger.entries()).hasSize(1);
        ModelUsageEntry entry = terminalSnapshot.get().getFirst();
        assertThat(entry.callCount()).isEqualTo(1);
        assertThat(server.requestCount()).isEqualTo(1);
        if (expectedError) {
            assertThat(error.get()).isInstanceOf(ModelHttpException.class);
            assertThat(((ModelHttpException)error.get()).status()).isEqualTo(400);
            assertThat(deltas).isEmpty();
            assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UNKNOWN);
            assertThat(entry.totalTokens()).isNull();
        } else {
            assertThat(error.get()).isNull();
            assertThat(deltas).containsExactly("synthetic answer");
            assertThat(entry.usageSource()).isEqualTo(ModelUsageLedger.SOURCE_UPSTREAM);
            assertThat(entry.promptTokens()).isEqualTo(4);
            assertThat(entry.completionTokens()).isEqualTo(2);
            assertThat(entry.totalTokens()).isEqualTo(6);
        }
    }

    @Test
    void successUsageIsAlreadyRecordedInsideOnComplete() throws Exception {
        String body = "data: {\"choices\":[{\"delta\":{\"content\":\"synthetic answer\"}}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6}}\n\n"
                + "data: [DONE]\n\n";
        try (MockHttpServer server = MockHttpServer.start((exchange, req) ->
                MockHttpServer.respondText(exchange, 200, "text/event-stream", body))) {
            streamAndInspectAtTerminal(server, false);
        }
    }

    @Test
    void unknownUsageIsAlreadyRecordedInsideOnErrorForNonRetriable400() throws Exception {
        try (MockHttpServer server = MockHttpServer.start((exchange, req) ->
                MockHttpServer.respondJson(exchange, 400, "{\"error\":{\"message\":\"context length exceeded\"}}"))) {
            streamAndInspectAtTerminal(server, true);
        }
    }
}
