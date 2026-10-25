package io.dpdk.assistant.chat;

import static org.assertj.core.api.Assertions.*;

import io.dpdk.assistant.contracts.chat.SseEvent;
import io.dpdk.assistant.task.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;

class ChatSseContractTest {
    @Test
    void errorOnlyIsLegalAndUsesExactLfFramingWithSafePayload() throws Exception {
        Flow.Publisher<SseEvent> failed =
                subscriber -> {
                    subscriber.onSubscribe(
                            new Flow.Subscription() {
                                public void request(long n) {
                                    subscriber.onError(
                                            new IllegalStateException("sensitive-secret"));
                                }

                                public void cancel() {}
                            });
                };
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ChatHttp.stream(failed).getBody().writeTo(out);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .isEqualTo(
                        "event: error\n"
                                + "data: {\"message\":\"服务暂时不可用，请稍后重试\",\"retryable\":true}\n\n");
    }

    @Test
    void cumulativeSourcesKeepSameFileSeparateChunksAndPendingStaysNonterminal() throws Exception {
        var first = new CitationData("li_a", "same.c", "A", "one", 0.5);
        var second = new CitationData("li_b", "same.c", "A", "two", 0.4);
        var publisher =
                TaskEventStreams.staticPublisher(
                        List.of(
                                TaskEventStreams.sourcesFrame(List.of(first)),
                                TaskEventStreams.sourcesFrame(List.of(first, second)),
                                TaskEventStreams.pendingDoneFrame("task")));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        var response = ChatHttp.stream(publisher);
        response.getBody().writeTo(out);
        String bytes = out.toString(StandardCharsets.UTF_8);
        assertThat(bytes)
                .doesNotContain("\r")
                .contains("\"terminal\":false")
                .contains("\"status\":\"pending\"");
        assertThat(bytes.split("\n\n")).hasSize(3);
        assertThat(bytes).contains("li_a", "li_b", "same.c");
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-cache");
        assertThat(response.getHeaders().getFirst("X-Accel-Buffering")).isEqualTo("no");
    }

    @Test
    void disconnectCancelsSubscriptionOnlyAndNeverAddsSyntheticDone() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        Flow.Publisher<SseEvent> publisher =
                subscriber ->
                        subscriber.onSubscribe(
                                new Flow.Subscription() {
                                    public void request(long n) {
                                        subscriber.onNext(new SseEvent("start", "{}"));
                                    }

                                    public void cancel() {
                                        cancelled.set(true);
                                    }
                                });
        OutputStream disconnected =
                new OutputStream() {
                    public void write(int b) throws IOException {
                        throw new IOException("disconnected");
                    }
                };
        ChatHttp.stream(publisher).getBody().writeTo(disconnected);
        assertThat(cancelled).isTrue();
    }
}
