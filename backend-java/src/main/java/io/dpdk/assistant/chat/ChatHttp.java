package io.dpdk.assistant.chat;

import io.dpdk.assistant.auth.AuthPrincipalResolver;
import io.dpdk.assistant.contracts.chat.SseEvent;
import io.dpdk.assistant.contracts.identity.*;
import io.dpdk.assistant.task.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** HTTP 字段与 SSE wire 映射，确保双 LF，无默认 record 命名泄漏。 */
public final class ChatHttp {
    private ChatHttp() {}

    public static Principal authenticate(
            AuthPrincipalResolver resolver, HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Collections.list(request.getHeaderNames())
                .forEach(n -> headers.put(n, request.getHeader(n)));
        return resolver.resolve(new RequestHeaders(headers), new AuthSettings());
    }

    public static Map<String, Object> response(TaskResultView v) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session_id", v.sessionId());
        out.put("message_id", v.messageId());
        out.put("answer", v.answer());
        out.put("citations", v.citations().stream().map(ChatHttp::citation).toList());
        out.put("next_steps", v.nextSteps());
        out.put("confidence", v.confidence());
        out.put("debug_trace", null);
        out.put("answer_status", v.answerStatus());
        out.put("answer_reason", v.answerReason());
        out.put("issue_id", v.issueId());
        out.put("request_id", v.requestId());
        out.put("task_id", v.taskId());
        if ("harness_result_unknown".equals(v.answerReason()))
            out.put("failure_code", "harness_result_unknown");
        return out;
    }

    static Map<String, Object> citation(CitationData c) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("chunk_id", c.chunkId());
        out.put("source_path", c.sourcePath());
        out.put("title", c.title());
        out.put("section", c.section());
        out.put("score", c.score());
        return out;
    }

    public static Map<String, Object> status(TaskRecordRow row, RagTaskService service) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("task_id", row.id());
        out.put("status", service.statusOf(row));
        out.put("reason", Objects.toString(row.reason(), ""));
        out.put("request_id", row.requestId());
        out.put("session_id", row.sessionId());
        out.put("message_id", row.messageId());
        out.put("created_at", Objects.toString(row.createdAt(), ""));
        out.put("elapsed_ms", row.elapsedMs());
        return out;
    }

    public static byte[] wire(SseEvent event) {
        return ("event: " + event.name() + "\ndata: " + event.dataJson() + "\n\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    public static ResponseEntity<StreamingResponseBody> stream(Flow.Publisher<SseEvent> publisher) {
        StreamingResponseBody body =
                output -> {
                    CountDownLatch finished = new CountDownLatch(1);
                    publisher.subscribe(
                            new Flow.Subscriber<>() {
                                private Flow.Subscription subscription;

                                public void onSubscribe(Flow.Subscription value) {
                                    subscription = value;
                                    value.request(Long.MAX_VALUE);
                                }

                                public void onNext(SseEvent event) {
                                    try {
                                        output.write(wire(event));
                                        output.flush();
                                    } catch (java.io.IOException disconnected) {
                                        subscription.cancel();
                                        finished.countDown();
                                    }
                                }

                                public void onError(Throwable error) {
                                    try {
                                        output.write(
                                                "event: error\ndata: {\"message\":\"服务暂时不可用，请稍后重试\",\"retryable\":true}\n\n"
                                                        .getBytes(StandardCharsets.UTF_8));
                                        output.flush();
                                    } catch (java.io.IOException disconnected) {
                                        subscription.cancel();
                                    } finally {
                                        finished.countDown();
                                    }
                                }

                                public void onComplete() {
                                    finished.countDown();
                                }
                            });
                    try {
                        finished.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/event-stream; charset=utf-8"))
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }
}
