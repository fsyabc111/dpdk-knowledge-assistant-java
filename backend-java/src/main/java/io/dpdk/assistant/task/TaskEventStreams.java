package io.dpdk.assistant.task;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.chat.EventStreamPort;
import io.dpdk.assistant.contracts.chat.SseEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SSE 事件流（对齐 task_service.response_frames/terminal_row_frames/subscribe/
 * task_events 与 http-and-sse.md 的帧契约）。
 *
 * - 断线只移除订阅者，绝不取消任务；断开后续跑照常收口落库；
 * - 订阅序：start → 回放快照 → 实时帧 → 终态帧；空闲每 1s 补 status{processing}；
 * - 终态可从 DB 重建（终态行 + 占位/正文消息），不依赖内存日志；
 * - pending 非终态帧明确 terminal=false；显式取消先终态化库行，迟到完成被
 *   pending 条件更新拒绝。
 */
public final class TaskEventStreams implements EventStreamPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TaskStore store;
    private final TaskRunner runner;
    private final TaskRecovery recovery;
    private final io.dpdk.assistant.contracts.platform.ClockPort clock;

    public TaskEventStreams(TaskStore store, TaskRunner runner, TaskRecovery recovery,
                            io.dpdk.assistant.contracts.platform.ClockPort clock) {
        this.store = store;
        this.runner = runner;
        this.recovery = recovery;
        this.clock = clock;
    }

    /* ------------------------------------------------------------------ */
    /* EventStreamPort（task_events 路由）                                  */
    /* ------------------------------------------------------------------ */

    @Override
    public Flow.Publisher<SseEvent> subscribeOwned(String taskId, String subject) {
        runner.sweepFinished();
        ManagedTask managed = runner.registry().get(taskId);
        if (managed != null && !subject.equals(managed.subject)) {
            managed = null;   // 归属不符 → 走库行校验，大概率 404
        }
        if (managed == null) {
            Optional<TaskRecordRow> row = store.findOwned(taskId, subject);
            if (row.isEmpty()) {
                throw new TaskExceptions.TaskNotFoundException("task not found");
            }
            TaskRecordRow current = row.get();
            if ("pending".equals(current.status())) {
                Optional<ManagedTask> ensured = recovery.ensureManaged(current);
                if (ensured.isPresent()) {
                    managed = ensured.get();
                } else {
                    Optional<TaskRecordRow> fresh = store.findOwned(taskId, subject);
                    if (fresh.isPresent()) {
                        current = fresh.get();
                    }
                }
            }
            if (managed == null) {
                if ("pending".equals(current.status())) {
                    // 暂不可恢复（太新/占槽满）：如实回报进行中终帧，客户端稍后重连即可。
                    return staticPublisher(List.of(
                            startFrame(current.sessionId(), current.requestId(), current.id()),
                            pendingDoneFrame(current.id())));
                }
                Optional<TaskStore.MessageRecord> message = current.messageId() == null
                        ? Optional.empty() : store.loadRowMessage(current.messageId());
                return staticPublisher(terminalRowFrames(current, message.orElse(null)));
            }
        }
        return new ManagedEventPublisher(managed);
    }

    /* ------------------------------------------------------------------ */
    /* 帧构造                                                               */
    /* ------------------------------------------------------------------ */

    public static TaskEvent startFrame(String sessionId, String requestId, String taskId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("session_id", sessionId);
        data.put("request_id", requestId);
        data.put("task_id", taskId);
        return TaskEvent.of("start", data);
    }

    public static TaskEvent statusProcessingFrame() {
        return TaskEvent.of("status", Map.of("stage", "processing"));
    }

    public static TaskEvent sourcesFrame(List<CitationData> citations) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (CitationData citation : citations) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("chunk_id", citation.chunkId());
            item.put("source_path", citation.sourcePath());
            item.put("title", citation.title() == null ? "" : citation.title());
            item.put("section", citation.section() == null ? "" : citation.section());
            item.put("score", citation.score());
            items.add(item);
        }
        return TaskEvent.of("sources", Map.of("citations", items));
    }

    public static TaskEvent deltaFrame(String answer) {
        return TaskEvent.of("delta", Map.of("delta", answer == null ? "" : answer));
    }

    /** 结果 → done 帧（ChatResponse 去 answer/citations 的字段集）。 */
    public static TaskEvent doneFrame(TaskResultView view) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("session_id", view.sessionId() == null ? "" : view.sessionId());
        data.put("message_id", view.messageId() == null ? "" : view.messageId());
        data.put("next_steps", view.nextSteps());
        data.put("confidence", view.confidence());
        data.put("debug_trace", null);
        data.put("answer_status", view.answerStatus());
        data.put("answer_reason", view.answerReason());
        data.put("issue_id", view.issueId());
        data.put("request_id", view.requestId());
        data.put("task_id", view.taskId());
        return TaskEvent.of("done", data);
    }

    /** 终态 done 帧：附加 status/terminal 宽松字段（terminal_row_frames 尾帧）。 */
    public static TaskEvent terminalDoneFrame(TaskResultView view, String status) {
        Map<String, Object> data = new LinkedHashMap<>(doneFrame(view).data());
        data.put("status", status);
        data.put("terminal", true);
        return TaskEvent.of("done", data);
    }

    /** pending 非终态帧：明确 terminal=false，不表示任务结束。 */
    public static TaskEvent pendingDoneFrame(String taskId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task_id", taskId);
        data.put("status", "pending");
        data.put("terminal", false);
        return TaskEvent.of("done", data);
    }

    /** 终态行 → start/sources/delta/done 重建帧序列。 */
    public List<TaskEvent> terminalRowFrames(TaskRecordRow row, TaskStore.MessageRecord message) {
        TaskResultView view = chatResponseFromRow(row, message);
        List<TaskEvent> frames = new ArrayList<>();
        frames.add(startFrame(view.sessionId(), view.requestId(), view.taskId()));
        frames.add(sourcesFrame(view.citations()));
        frames.add(deltaFrame(view.answer()));
        frames.add(terminalDoneFrame(view, row.status()));
        return frames;
    }

    /** 结果视图（内存收口形态）：citations 为执行结论里的全量引用。 */
    public static TaskResultView resultView(ManagedTask task) {
        TaskExecutor.TaskExecutionResult result = task.result();
        return new TaskResultView(
                task.sessionId == null ? "" : task.sessionId,
                task.messageId() == null ? "" : task.messageId(),
                result.answer(),
                result.citations(),
                List.of(Map.of("label", "继续提问")),
                "answered".equals(result.status()) ? "high" : "low",
                result.status(),
                result.reason(),
                task.issueId(),
                task.requestId,
                task.taskId);
    }

    /** chat_response_from_row：终态行 + 正文消息 → 重建视图（citations score=0）。 */
    public TaskResultView chatResponseFromRow(TaskRecordRow row, TaskStore.MessageRecord message) {
        Map<String, Object> metadata = parseMetadata(message == null ? null : message.metadataJson());
        List<CitationData> citations = new ArrayList<>();
        if (row.evidenceJson() != null && !row.evidenceJson().isEmpty()) {
            try {
                var tree = MAPPER.readTree(row.evidenceJson());
                if (tree.isArray()) {
                    tree.forEach(item -> citations.add(new CitationData(
                            item.path("chunk_id").asText(null),
                            item.path("source_path").asText(""),
                            item.path("section").asText(""))));
                }
            } catch (Exception e) {
                throw new IllegalStateException("evidence_json 损坏", e);
            }
        }
        Object confidence = metadata.get("confidence");
        return new TaskResultView(
                row.sessionId() == null ? "" : row.sessionId(),
                row.messageId() == null ? "" : row.messageId(),
                message == null ? "" : message.content(),
                citations,
                List.of(),
                confidence == null ? "low" : String.valueOf(confidence),
                row.status(),
                row.reason(),
                metadata.get("issue_id") == null ? null : String.valueOf(metadata.get("issue_id")),
                row.requestId(),
                row.id());
    }

    private static Map<String, Object> parseMetadata(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static SseEvent render(TaskEvent event) {
        try {
            return new SseEvent(event.name(), MAPPER.writeValueAsString(event.data()));
        } catch (Exception e) {
            throw new IllegalStateException("SSE 帧渲染失败", e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 发布者                                                                */
    /* ------------------------------------------------------------------ */

    /** 静态帧序列（DB 重建/pending 分支）：按需求发射后 onComplete。 */
    public static Flow.Publisher<SseEvent> staticPublisher(List<TaskEvent> frames) {
        return new StaticPublisher(frames);
    }

    private static final class StaticPublisher implements Flow.Publisher<SseEvent> {

        private final List<TaskEvent> frames;

        StaticPublisher(List<TaskEvent> frames) {
            this.frames = frames;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super SseEvent> subscriber) {
            subscriber.onSubscribe(new Flow.Subscription() {
                final AtomicLong demand = new AtomicLong();
                final AtomicBoolean done = new AtomicBoolean();
                volatile int cursor;

                @Override
                public void request(long n) {
                    while (!done.get()) {
                        long current = demand.get();
                        if (current <= 0) {
                            if (demand.compareAndSet(current, current + n)) {
                                break;
                            }
                            continue;
                        }
                        long granted = Math.min(current, n);
                        if (!demand.compareAndSet(current, current - granted)) {
                            continue;
                        }
                        for (long i = 0; i < granted && !done.get(); i++) {
                            if (cursor < frames.size()) {
                                subscriber.onNext(render(frames.get(cursor++)));
                            } else if (done.compareAndSet(false, true)) {
                                subscriber.onComplete();
                            }
                        }
                        break;
                    }
                }

                @Override
                public void cancel() {
                    done.set(true);
                }
            });
        }
    }

    /**
     * 在跑/已收口任务的订阅发布者。发射由订阅方 request 驱动（阻塞 SSE 写线程
     * 与源 async-for 语义同构）；空闲等待每 1s 补 status 帧；队列无界，detached
     * 订阅者不反压执行；cancel 只移除订阅者，绝不取消任务。
     */
    final class ManagedEventPublisher implements Flow.Publisher<SseEvent> {

        private final ManagedTask managed;

        ManagedEventPublisher(ManagedTask managed) {
            this.managed = managed;
        }

        @Override
        public void subscribe(Flow.Subscriber<? super SseEvent> subscriber) {
            ManagedTask.SubscriptionView view = managed.subscribeView(clock.monotonicNanos());
            Subscription subscription = new Subscription(subscriber, view);
            subscriber.onSubscribe(subscription);
        }

        private enum Phase { START, SNAPSHOT, LIVE, TERMINAL }

        private final class Subscription implements Flow.Subscription {

            private final Flow.Subscriber<? super SseEvent> subscriber;
            private final ManagedTask.SubscriptionView view;
            private final BlockingQueue<Object> live = new LinkedBlockingQueue<>();
            private final ArrayDeque<TaskEvent> ready = new ArrayDeque<>();
            private final AtomicLong demand = new AtomicLong();
            private final AtomicBoolean finished = new AtomicBoolean();
            private volatile boolean cancelled;

            private Phase phase = Phase.START;
            private int snapshotCursor;
            private List<TaskEvent> tail = List.of();
            private int tailCursor;

            Subscription(Flow.Subscriber<? super SseEvent> subscriber,
                         ManagedTask.SubscriptionView view) {
                this.subscriber = subscriber;
                this.view = view;
            }

            @Override
            public void request(long n) {
                if (n <= 0) {
                    subscriber.onError(new IllegalArgumentException("non-positive request"));
                    return;
                }
                demand.addAndGet(n);
                try {
                    pump();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    if (finished.compareAndSet(false, true)) {
                        managed.removeSubscriber(view.subscriber());
                        subscriber.onError(e);
                    }
                }
            }

            @Override
            public void cancel() {
                cancelled = true;
                // 断开只移除订阅者（绝不取消任务）；阻塞的 poll 最迟 1s 后退出。
                managed.removeSubscriber(view.subscriber());
            }

            private void pump() throws InterruptedException {
                while (!cancelled && !finished.get() && demand.get() > 0) {
                    demand.decrementAndGet();
                    TaskEvent frame = nextFrame();
                    if (frame == null) {
                        return;
                    }
                    subscriber.onNext(render(frame));
                    if (phase == Phase.TERMINAL && frame.name().equals("done")) {
                        if (finished.compareAndSet(false, true)) {
                            managed.removeSubscriber(view.subscriber());
                            subscriber.onComplete();
                        }
                        return;
                    }
                }
            }

            /** 产出下一帧；实时段无帧可发时阻塞等待（1s 超时补 status 帧）。 */
            private TaskEvent nextFrame() throws InterruptedException {
                if (!ready.isEmpty()) {
                    return ready.poll();
                }
                switch (phase) {
                    case START -> {
                        phase = Phase.SNAPSHOT;
                        return startFrame(managed.sessionId, managed.requestId, managed.taskId);
                    }
                    case SNAPSHOT -> {
                        if (snapshotCursor < view.snapshot().size()) {
                            return view.snapshot().get(snapshotCursor++);
                        }
                        if (view.live()) {
                            phase = Phase.LIVE;
                            return nextLiveFrame();
                        }
                        enterTerminal();
                        return nextFrame();
                    }
                    case LIVE -> {
                        return nextLiveFrame();
                    }
                    case TERMINAL -> {
                        if (tailCursor < tail.size()) {
                            return tail.get(tailCursor++);
                        }
                        return null; // 终帧已尽（不应到达）
                    }
                    default -> {
                        return null;
                    }
                }
            }

            private TaskEvent nextLiveFrame() throws InterruptedException {
                Object item = live.poll(1, TimeUnit.SECONDS);
                if (cancelled) {
                    return null;
                }
                if (item == null) {
                    if (managed.isTerminal()) {
                        enterTerminal();
                        return nextFrame();
                    }
                    return statusProcessingFrame();
                }
                if (item == ManagedTask.DONE_SENTINEL) {
                    enterTerminal();
                    return nextFrame();
                }
                return (TaskEvent) item;
            }

            /** 进入终帧段：结果在场按 sources/delta/done，否则按库行/归因兜底。 */
            private void enterTerminal() {
                phase = Phase.TERMINAL;
                if (managed.result() != null) {
                    TaskResultView resultView = resultView(managed);
                    tail = List.of(
                            sourcesFrame(resultView.citations()),
                            deltaFrame(resultView.answer()),
                            doneFrame(resultView));
                    tailCursor = 0;
                    return;
                }
                String status = null;
                boolean rowPending = false;
                Optional<TaskRecordRow> row = store.findOwned(managed.taskId, managed.subject);
                if (row.isPresent()) {
                    if (row.get().isTerminal()) {
                        status = row.get().status();
                    } else {
                        rowPending = true;
                    }
                }
                if (status != null) {
                    tail = List.of(TaskEvent.of("done", new LinkedHashMap<>(Map.of(
                            "task_id", managed.taskId,
                            "status", status,
                            "terminal", true))));
                } else if (rowPending) {
                    // 行仍 pending：如实回报非终态，客户端稍后重连/续传。
                    tail = List.of(pendingDoneFrame(managed.taskId));
                } else {
                    String fallback = managed.isCancelledByUser() ? "cancelled" : "system_error";
                    tail = List.of(TaskEvent.of("done", new LinkedHashMap<>(Map.of(
                            "task_id", managed.taskId,
                            "status", fallback,
                            "terminal", true))));
                }
                tailCursor = 0;
            }
        }
    }
}
