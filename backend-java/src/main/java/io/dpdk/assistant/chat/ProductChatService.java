package io.dpdk.assistant.chat;

import io.dpdk.assistant.agent.context.CodePoints;
import io.dpdk.assistant.agent.fallback.Redact;
import io.dpdk.assistant.agent.tools.FencedSource;
import io.dpdk.assistant.auth.SlidingWindowRateLimiter;
import io.dpdk.assistant.contracts.chat.SseEvent;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.task.*;
import io.dpdk.assistant.session.SessionService;
import io.dpdk.assistant.task.*;

import java.util.*;
import java.util.concurrent.*;

/** 产品受理入口；probe 在占槽前，重放/附着不重复扣额，也不再执行模型。 */
public final class ProductChatService {
    private final RagTaskService tasks;
    private final AdmissionGate gate;
    private final SessionService sessions;
    private final ChatTaskExecutor executor;
    private final ChatSettings settings;
    private final SlidingWindowRateLimiter limiter;

    public ProductChatService(
            RagTaskService tasks,
            AdmissionGate gate,
            SessionService sessions,
            ChatTaskExecutor executor,
            ChatSettings settings,
            ClockPort clock) {
        this.tasks = tasks;
        this.gate = gate;
        this.sessions = sessions;
        this.executor = executor;
        this.settings = settings;
        this.limiter = new SlidingWindowRateLimiter(settings.ratePerMinute(), "chat", clock);
    }

    public record StartResult(
            String sessionId,
            String taskId,
            String status,
            ManagedTask managed,
            TaskRecordRow row) {}

    public void checkRateLimit(String subject) {
        limiter.check(subject);
    }

    public StartResult start(Principal principal, ChatInput input) {
        return startInternal(principal, input, RequestIdentity.Purpose.chat, null, () -> {});
    }

    public StartResult startExplain(
            Principal principal,
            String sessionId,
            String requestId,
            String question,
            Map<String, Object> explain,
            Runnable beforeAdmission) {
        return startInternal(
                principal,
                new ChatInput(sessionId, requestId, question, Map.of(), null),
                RequestIdentity.Purpose.explain,
                explain,
                beforeAdmission);
    }

    private StartResult startInternal(
            Principal principal,
            ChatInput input,
            RequestIdentity.Purpose purpose,
            Map<String, Object> explain,
            Runnable beforeAdmission) {
        String request =
                input.requestId() == null
                        ? UUID.randomUUID().toString().replace("-", "")
                        : input.requestId();
        var a = input.attachment();
        String digest =
                a == null
                        ? TaskInputHash.compute(input.message())
                        : TaskInputHash.compute(input.message(), a.filename(), a.contentHash());
        RequestIdentity identity =
                new RequestIdentity(request, digest, input.sessionId() == null, purpose);
        TaskProbeOutcome probe =
                tasks.probeDetail(
                        identity, principal.subject(), Optional.ofNullable(input.sessionId()));
        if (!probe.isMiss()) return existing(probe);
        gate.acquire();
        boolean transferred = false;
        try {
            executor.ensureAvailable();
            // 讲解会话的确定性预建严格位于 probe miss 后；回放不产生空会话。
            if (beforeAdmission != null) beforeAdmission.run();
            String session =
                    sessions.ensureOwned(
                                    Optional.ofNullable(input.sessionId()),
                                    CodePoints.truncate(Redact.redact(input.message()), 100),
                                    principal.subject())
                            .value();
            ChatTaskExecutor.Prepared prepared =
                    prepareWithinDeadline(session, request, principal.subject());
            String source = a == null ? FencedSource.extract(input.message()) : a.source();
            String origin = a == null ? (source == null ? null : "pasted") : "upload";
            String redacted =
                    a == null
                            ? Redact.redact(input.message())
                            : Redact.redact(input.message()) + "\n[上传附件：" + a.filename() + "]";
            AdmissionInput admission =
                    new AdmissionInput(
                            identity,
                            principal.subject(),
                            session,
                            redacted,
                            prepared.retrievalContext(),
                            executor.modelName(),
                            input.message(),
                            principal.dailyQuota(),
                            new TaskTemporaryInput(
                                    a == null ? null : a.filename(),
                                    a == null ? null : a.suffix(),
                                    source,
                                    origin));
            TaskAdmitOutcome outcome = tasks.admitDetail(admission);
            if (!outcome.created()) {
                if (input.sessionId() == null
                        && !session.equals(
                                outcome.row().map(TaskRecordRow::sessionId).orElse(null)))
                    sessions.discardEmpty(session, principal.subject());
                return new StartResult(
                        outcome.row().orElseThrow().sessionId(),
                        outcome.taskId(),
                        outcome.replayed() ? "replayed" : "attached",
                        null,
                        outcome.row().orElseThrow());
            }
            TaskRecordRow row = outcome.row().orElseThrow();
            executor.bindPrepared(row.id(), prepared, explain);
            TaskExecutor.TaskExecutionContext context =
                    new TaskExecutor.TaskExecutionContext(
                            row.id(),
                            request,
                            session,
                            principal.subject(),
                            principal.roles(),
                            purpose.name(),
                            input.message(),
                            prepared.retrievalContext(),
                            a == null ? null : a.filename(),
                            a == null ? null : a.suffix(),
                            source,
                            origin);
            ManagedTask managed =
                    tasks.runner()
                            .startReserved(
                                    principal.subject(), request, session, context, executor);
            transferred = true;
            return new StartResult(session, row.id(), "started", managed, row);
        } catch (io.dpdk.assistant.platform.errors.ApiDetailException
                | TaskExceptions.TaskConflictException
                | io.dpdk.assistant.usage.QuotaExceededException failure) {
            throw failure;
        } catch (RuntimeException failed) {
            throw ChatRequestParser.error(503, "服务暂时不可用，请保留问题后重试");
        } finally {
            if (!transferred) gate.release();
        }
    }

    private ChatTaskExecutor.Prepared prepareWithinDeadline(
            String session, String requestId, String subject) {
        var workers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<ChatTaskExecutor.Prepared> future =
                    workers.submit(() -> executor.prepare(session, requestId, subject));
            try {
                return future.get(settings.prepareTimeout().toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException interrupted) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw ChatRequestParser.error(503, "服务暂时不可用，请保留问题后重试");
            } catch (ExecutionException | TimeoutException failed) {
                future.cancel(true);
                throw ChatRequestParser.error(503, "服务暂时不可用，请保留问题后重试");
            }
        } finally {
            workers.shutdownNow();
        }
    }

    private static StartResult existing(TaskProbeOutcome probe) {
        TaskRecordRow row = probe.row().orElseThrow();
        return new StartResult(
                row.sessionId(), row.id(), probe.isReplay() ? "replayed" : "attached", null, row);
    }

    public TaskResultView chat(Principal principal, ChatInput input) throws InterruptedException {
        StartResult start = start(principal, input);
        if (start.managed() != null) {
            TaskExecutor.TaskExecutionResult result =
                    tasks.waitResult(start.managed(), settings.requestTimeout());
            if (result != null) return TaskEventStreams.resultView(start.managed());
        }
        return tasks.attachResponse(start.row(), principal.subject(), settings.requestTimeout());
    }

    public Flow.Publisher<SseEvent> events(Principal principal, ChatInput input) {
        StartResult start = start(principal, input);
        if ("replayed".equals(start.status())) {
            var row = start.row();
            var message = tasks.store().loadRowMessage(row.messageId()).orElse(null);
            TaskEventStreams streams =
                    new TaskEventStreams(
                            tasks.store(),
                            tasks.runner(),
                            tasks.recovery(),
                            new io.dpdk.assistant.platform.time.SystemClockAdapter());
            TaskResultView view = streams.chatResponseFromRow(row, message);
            return TaskEventStreams.staticPublisher(
                    List.of(
                            TaskEventStreams.startFrame(
                                    view.sessionId(), view.requestId(), view.taskId()),
                            TaskEventStreams.sourcesFrame(view.citations()),
                            TaskEventStreams.deltaFrame(view.answer()),
                            TaskEventStreams.doneFrame(view)));
        }
        return tasks.taskEvents(start.taskId(), principal.subject());
    }
}
