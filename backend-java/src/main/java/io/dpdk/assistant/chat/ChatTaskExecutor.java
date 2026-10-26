package io.dpdk.assistant.chat;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.dpdk.assistant.agent.*;
import io.dpdk.assistant.agent.port.*;
import io.dpdk.assistant.agent.tools.*;
import io.dpdk.assistant.contracts.model.HarnessPort;
import io.dpdk.assistant.contracts.platform.*;
import io.dpdk.assistant.contracts.retrieval.*;
import io.dpdk.assistant.retrieval.*;
import io.dpdk.assistant.session.SessionService;
import io.dpdk.assistant.task.*;
import io.dpdk.assistant.usage.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** 产品执行体：真实 Agent/检索/账本接线，正文只作为候选交 TaskRunner 原子保存。 */
public final class ChatTaskExecutor implements TaskExecutor {
    private final SessionService sessions;
    private final TransactionRunner tx;
    private final ClockPort clock;
    private final AgentChatModel model;
    private final AgentSettings agentSettings;
    private final PgReleaseReader retrieval;
    private final HarnessPort harness;
    private final TaskGradeLedger grades;
    private final ChatAgentPorts ports;
    private final UsageLedgerStore usage;
    private final ChatSettings settings;
    private final ConcurrentMap<String, ChatMemory.View> preparedMemory = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Map<String, Object>> explainContexts =
            new ConcurrentHashMap<>();
    private record PendingUsage(UsageCore.UsageRecorder recorder, String requestId, String subject,
                                String sessionId, long startedNanos, RequestMetrics metrics,
                                CompletableFuture<Void> candidateExited, CompletableFuture<Void> accountingAck) {}
    private volatile io.dpdk.assistant.contracts.observability.ProcessMetrics metrics = io.dpdk.assistant.contracts.observability.ProcessMetrics.NONE;
    public void setMetrics(io.dpdk.assistant.contracts.observability.ProcessMetrics metrics) { this.metrics = java.util.Objects.requireNonNull(metrics); }
    private final ConcurrentMap<String, Long> preparedStarts = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, PendingUsage> completionUsage = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<CompletableFuture<Void>>> accountingAcks = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicReference<Throwable> accountingFailure = new java.util.concurrent.atomic.AtomicReference<>();
    private volatile Function<TaskExecutionContext, Map<String, Object>> explainResolver =
            c -> null;

    private record UsagePolicy(
            boolean enabled, Map<String, UsageCore.ModelPrice> prices, String version) {}

    private volatile UsagePolicy usagePolicy = new UsagePolicy(true, Map.of(), "");
    private volatile AnswerValidator.Judge judge;

    public ChatTaskExecutor(
            SessionService sessions,
            TransactionRunner tx,
            ClockPort clock,
            AgentChatModel model,
            AgentSettings agentSettings,
            PgReleaseReader retrieval,
            HarnessPort harness,
            TaskStore tasks,
            TaskGradeLedger grades,
            UsageCounterStore quotas,
            UsageLedgerStore usage,
            ChatSettings settings) {
        this.sessions = sessions;
        this.tx = tx;
        this.clock = clock;
        this.model = model;
        this.agentSettings = agentSettings;
        this.retrieval = retrieval;
        this.harness = harness;
        this.grades = grades;
        this.ports = new ChatAgentPorts(tasks, tx, clock, quotas);
        this.usage = usage;
        this.settings = settings;
    }

    public void setUsagePolicy(
            boolean enabled, Map<String, UsageCore.ModelPrice> prices, String version) {
        usagePolicy = new UsagePolicy(enabled, Map.copyOf(prices), Objects.toString(version, ""));
    }

    public String modelName() {
        return agentSettings.modelName();
    }

    public void ensureAvailable() {
        if (model == null || retrieval == null)
            throw ChatRequestParser.error(503, "服务暂时不可用，请保留问题后重试");
    }

    public void setExplainResolver(Function<TaskExecutionContext, Map<String, Object>> resolver) {
        this.explainResolver = Objects.requireNonNull(resolver);
    }

    public void setJudge(AnswerValidator.Judge judge) {
        this.judge = judge;
    }

    /** 受理前构建记忆；任何必要依赖装配失败时没有 pending 行。 */
    public Prepared prepare(String sessionId) {
        return prepare(sessionId, null, null);
    }

    public Prepared prepare(String sessionId, String requestId, String subject) {
        ensureAvailable();
        long preparedStarted = System.nanoTime();
        UsageCore.UsageRecorder recorder = new UsageCore.UsageRecorder(subject, sessionId);
        try {
            return new Prepared(
                    ChatMemory.build(sessionId, sessions, tx, summaryModel(recorder), settings), preparedStarted);
        } finally {
            if (requestId != null) flushUsage(recorder, requestId, subject, sessionId);
        }
    }

    private AgentChatModel summaryModel(UsageCore.UsageRecorder recorder) {
        return (messages, tools) -> {
            AgentChatModel.AgentReply reply = null;
            try {
                reply = model.call(messages, tools);
                return reply;
            } finally {
                recordUsage(recorder, "summary", reply == null ? null : reply.usage());
            }
        };
    }

    private void recordUsage(UsageCore.UsageRecorder recorder, String stage, AgentUsage tokens) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (tokens != null) {
            values.put("prompt_tokens", tokens.promptTokens());
            values.put("completion_tokens", tokens.completionTokens());
            values.put("total_tokens", tokens.totalTokens());
        }
        recorder.recordLlm(stage, agentSettings.modelName(), values);
    }

    private void flushUsage(
            UsageCore.UsageRecorder recorder, String requestId, String subject, String sessionId) {
        try { flushUsageEntries(recorder.entries(), requestId, subject, sessionId, usagePolicy); }
        catch (RuntimeException failure) { usageWarning(failure); }
    }

    /** JDBC只在recorder锁外运行；取消线程的interrupt暂清、原样恢复。 */
    private void flushUsageEntries(List<UsageCore.UsageEntry> entries, String requestId,
            String subject, String sessionId, UsagePolicy policy) {
        if (!policy.enabled() || entries.isEmpty()) return;
        boolean interrupted = Thread.interrupted();
        try {
            usage.recordRows(UsageCore.entriesToRows(entries, policy.prices(), policy.version(),
                    requestId, subject, sessionId, clock.nextSessionIso()));
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static void usageWarning(Throwable failure) {
        org.slf4j.LoggerFactory.getLogger(ChatTaskExecutor.class).warn(
                "[usage_receipt_failed] error_type={}", failure.getClass().getSimpleName());
    }

    /** 调用方可在取消前取得此ack；不暴露request正文/执行源，完成后内部map即移除。 */
    public CompletionStage<Void> accountingCompletion(String taskId) {
        Set<CompletableFuture<Void>> pending = accountingAcks.get(taskId);
        return (pending == null ? CompletableFuture.<Void>completedFuture(null)
                : CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))).minimalCompletionStage();
    }

    /** collector/停机的显式排空屏障；失败或超时一律false，不把终态当成已落完账。 */
    public boolean awaitAccountingReceipts(Duration timeout) {
        long deadline = System.nanoTime() + Math.max(0, timeout.toNanos());
        var acknowledgments = accountingAcks.values().stream().flatMap(Set::stream).toList();
        for (var pending : acknowledgments) {
            try { pending.get(Math.max(0, deadline-System.nanoTime()), TimeUnit.NANOSECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return false; }
            catch (ExecutionException | TimeoutException failed) { return false; }
        }
        return accountingFailure.get() == null && accountingAcks.values().stream()
                .flatMap(Set::stream).allMatch(CompletableFuture::isDone);
    }

    @jakarta.annotation.PreDestroy
    public void checkpointOnShutdown() {
        if (!awaitAccountingReceipts(Duration.ofMillis(250))) {
            org.slf4j.LoggerFactory.getLogger(ChatTaskExecutor.class).warn("[usage_receipts_pending_at_shutdown]");
        }
    }

    public record Prepared(ChatMemory.View memory, long startedNanos) {
        public Prepared(ChatMemory.View memory) { this(memory, System.nanoTime()); }
        public String retrievalContext() {
            return memory.retrievalContext();
        }
    }

    public void bindPrepared(String taskId, Prepared prepared, Map<String, Object> explain) {
        preparedMemory.put(taskId, prepared.memory());
        preparedStarts.put(taskId, prepared.startedNanos());
        if (explain != null) explainContexts.put(taskId, new LinkedHashMap<>(explain));
    }

    @Override
    public TaskExecutionResult run(TaskExecutionContext context, Consumer<TaskEvent> emit)
            throws Exception {
        ensureAvailable();
        UsageCore.UsageRecorder recorder =
                new UsageCore.UsageRecorder(context.subject(), context.sessionId());
        Long started = preparedStarts.remove(context.taskId());
        RequestMetrics requestMetrics = new RequestMetrics(metrics);
        var candidateExited = new CompletableFuture<Void>();
        var candidateState = new java.util.concurrent.atomic.AtomicInteger(); // 0待启动/1已启动/2取消前放弃
        var producer = recorder.retainProducer();
        var accountingAck = new CompletableFuture<Void>();
        accountingAcks.compute(context.taskId(), (taskId, active) -> {
            if (active == null) active = ConcurrentHashMap.newKeySet();
            active.add(accountingAck);
            return active;
        });
        completionUsage.put(context.taskId(), new PendingUsage(recorder, context.requestId(),
                context.subject(), context.sessionId(), started == null ? System.nanoTime() : started,
                requestMetrics, candidateExited, accountingAck));
        ExecutorService wall = Executors.newVirtualThreadPerTaskExecutor();
        Future<TaskExecutionResult> future;
        try {
            future = wall.submit(() -> {
                if (!candidateState.compareAndSet(0, 1)) throw new CancellationException("candidate abandoned");
                try { return candidate(context, emit, recorder, requestMetrics); }
                finally { producer.close(); candidateExited.complete(null); }
            });
        } catch (RuntimeException rejected) {
            producer.close(); candidateExited.complete(null); wall.shutdownNow(); throw rejected;
        }
        try {
            return future.get(settings.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            return new TaskExecutionResult(
                    AnswerValidator.UNAVAILABLE,
                    "system_error",
                    "generation_failed",
                    List.of(),
                    "");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof HarnessResultUnknown) {
                return new TaskExecutionResult(
                        "评测结果待核对，请稍后联系教师确认；系统不会自动重新提交。",
                        "system_error",
                        "harness_result_unknown",
                        List.of(),
                        "");
            }
            if (cause instanceof RetryableTaskFailure retry) throw retry;
            if (cause instanceof InterruptedException interrupted) throw interrupted;
            return new TaskExecutionResult(
                    AnswerValidator.UNAVAILABLE,
                    "system_error",
                    "generation_failed",
                    List.of(),
                    "");
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            throw interrupted;
        } finally {
            wall.shutdownNow();
            if (candidateState.compareAndSet(0, 2)) { producer.close(); candidateExited.complete(null); }
            preparedMemory.remove(context.taskId());
            explainContexts.remove(context.taskId());
        }
    }

    @Override public void afterCompletion(TaskExecutionContext context) { afterCompletion(context,null); }

    @Override public void afterCompletion(TaskExecutionContext context, TaskExecutionResult persisted) {
        PendingUsage pending = completionUsage.remove(context.taskId());
        if (pending == null) return;
        // 普通可中断调用尽快完成finally；共享loader不在此等待，取消最多握手100ms。
        try { pending.candidateExited().get(100, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException notYetExited) { /* 由迟到sink继续归账。 */ }
        String requestId = pending.requestId(), subject = pending.subject(), sessionId = pending.sessionId();
        String taskId = context.taskId();
        UsagePolicy policy = usagePolicy;
        RequestMetrics requestMetrics = pending.metrics();
        var checkpoint = pending.recorder().checkpointAndSubscribe(entry ->
                flushUsageEntries(List.of(entry), requestId, subject, sessionId, policy));
        CompletableFuture<Void> ack = pending.accountingAck();
        checkpoint.completion().whenComplete((ignored, failure) -> {
            Throwable completedFailure = failure;
            try { requestMetrics.publish(); }
            catch (RuntimeException metricFailure) { if (completedFailure == null) completedFailure = metricFailure; }
            if (completedFailure != null) {
                accountingFailure.compareAndSet(null, completedFailure);
                usageWarning(completedFailure);
                ack.completeExceptionally(completedFailure);
            } else ack.complete(null);
            accountingAcks.computeIfPresent(taskId, (id, active) -> {
                active.remove(ack);
                return active.isEmpty() ? null : active;
            });
        });
        try {
            if (persisted != null) metrics.rag(persisted.status(), persisted.reason(), (System.nanoTime()-pending.startedNanos())/1e9);
            flushUsageEntries(checkpoint.entries(), requestId, subject, sessionId, policy);
            checkpoint.acknowledge();
        } catch (RuntimeException failure) {
            checkpoint.fail(failure);
        }
    }

    private TaskExecutionResult candidate(
            TaskExecutionContext c, Consumer<TaskEvent> emit, UsageCore.UsageRecorder recorder,
            io.dpdk.assistant.contracts.observability.ProcessMetrics metrics)
            throws Exception {
        long candidateStarted = System.nanoTime();
        ChatMemory.View memory = preparedMemory.get(c.taskId());
        if (memory == null)
            memory =
                    ChatMemory.build(c.sessionId(), sessions, tx, summaryModel(recorder), settings);
        final String[] pinned = {""};
        final String[] retrievalFailure = {null};
        KnowledgeRetriever retriever =
                (query, limit, conversation) -> {
                    if (retrieval == null) {
                        retrievalFailure[0] = "retrieval_unavailable";
                        throw new IllegalStateException("retrieval unavailable");
                    }
                    try {
                        String release = sessions.resolveRelease(c.sessionId()).orElse("");
                        pinned[0] = release;
                        SearchResult result =
                                retrieval.searchPinned(
                                        retrieval.pin(release),
                                        new SearchInput(query, null, conversation),
                                        limit, recorder, metrics);
                        if (result.trace() instanceof RetrievalTrace trace) {
                            retrievalFailure[0] = trace.blockedReason() != null ? trace.blockedReason()
                                    : trace.fallbacks().contains(PgReleaseReader.QUERY_EMBEDDING_FAILED_FALLBACK)
                                            ? "query_embedding_failed" : null;
                            return trace.nodes().stream()
                                    .map(
                                            n ->
                                                    new KbChunk(
                                                            n.id(),
                                                            n.sourcePath(),
                                                            n.section(),
                                                            n.title(),
                                                            n.content(),
                                                            n.score()))
                                    .toList();
                        }
                        throw new IllegalStateException("检索富结果缺失");
                    } catch (RuntimeException failure) {
                        retrievalFailure[0] = "retrieval_failed";
                        throw failure;
                    }
                };
        AgentUsageSink sink =
                new AgentUsageSink() {
                    public void recordLlm(String stage, String name, AgentUsage u) {
                        Map<String, Object> tokens = new LinkedHashMap<>();
                        if (u != null) {
                            tokens.put("prompt_tokens", u.promptTokens());
                            tokens.put("completion_tokens", u.completionTokens());
                            tokens.put("total_tokens", u.totalTokens());
                        }
                        recorder.recordLlm(stage, name, tokens);
                    }

                    public void recordOperation(String kind, String stage, long duration) {
                        recorder.recordOperation(kind, stage, (int) Math.min(Integer.MAX_VALUE, duration), 1);
                    }
                };
        HarnessPort external =
                harness == null
                        ? (tool, args, deadline) -> {
                            throw new IllegalStateException("harness unavailable");
                        }
                        : harness;
        var deps =
                new AgentOrchestrator.Deps(
                        retriever,
                        new HarnessCaller(external, agentSettings, sink),
                        ports,
                        ports,
                        ports,
                        grades::recordTaskGrade,
                        ports,
                        sink);
        AgentOrchestrator.Attachment attachment =
                c.attachmentName() == null && c.attachmentSuffix() == null
                        ? null
                        : new AgentOrchestrator.Attachment(
                                c.attachmentName(),
                                protocolHint(c.attachmentName()),
                                "upload".equals(c.sourceOrigin()) ? c.sourceText() : null);
        AgentToolContext tools =
                AgentOrchestrator.buildContext(
                        agentSettings,
                        c.subject(),
                        c.roles(),
                        c.sessionId(),
                        c.question(),
                        attachment,
                        deps,
                        c.taskId(),
                        c.sourceText());
        tools.metrics = metrics;
        Map<String, Object> explain = null;
        if ("explain".equals(c.purpose())) {
            explain = explainContexts.get(c.taskId());
            if (explain == null) explain = explainResolver.apply(c);
        }
        List<Map<String, Object>> evidenceTools = new ArrayList<>();
        Set<String> seenTools = new HashSet<>();
        final long[] productTokens = {0};
        AgentChatModel recordingModel =
                (messages, names) -> {
                    for (AgentMessage message : messages) {
                        if (message.isTool()
                                && !"kb_search".equals(message.toolName())
                                && seenTools.add(message.toolCallId())) {
                            try {
                                Map<String, Object> payload =
                                        new ObjectMapper()
                                                .readValue(
                                                        message.content(),
                                                        new com.fasterxml.jackson.core.type
                                                                        .TypeReference<
                                                                Map<String, Object>>() {});
                                if (Boolean.TRUE.equals(payload.get("ok")))
                                    evidenceTools.add(
                                            Map.of("tool", message.toolName(), "result", payload));
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    AgentChatModel.AgentReply reply = null;
                    try {
                        reply = model.call(messages, names);
                        productTokens[0] += reply.usage().totalOrZero();
                        if (productTokens[0] > settings.requestTokenBudget()) {
                            // 先执行产品预算闸，再把planner回复交图层执行工具。
                            throw new IllegalStateException("request_token_budget");
                        }
                        return reply;
                    } finally {
                        sink.recordLlm(
                                names.isEmpty() ? "finalize" : "planner",
                                agentSettings.modelName(),
                                reply == null ? null : reply.usage());
                    }
                };
        AgentOrchestrator.AgentRunResult result = null;
        long generationStarted = System.nanoTime();
        try {
            result = new AgentOrchestrator(recordingModel, agentSettings)
                        .runProduct(
                                c.subject(),
                                c.roles(),
                                c.sessionId(),
                                c.question(),
                                attachment,
                                memory.memory(),
                                memory.retrievalContext(),
                                new AgentOrchestrator.TaskContext(
                                        c.taskId(), c.sourceText(), explain),
                                new AgentRunListener() {
                                    public void onToolStart(String tool, String label) {
                                        emit.accept(
                                                TaskEvent.of(
                                                        "tool_start",
                                                        Map.of("tool", tool, "label", tool)));
                                    }

                                    public void onToolEnd(Map<String, Object> summary) {
                                        emit.accept(TaskEvent.of("tool_end", summary));
                                    }
                                    // Agent token/delta 禁止直接下发，保存后由 TaskEventStreams 发送正文。
                                },
                                tools);
        } finally {
            boolean fallback = (result != null && result.fallback) || retrievalFailure[0] != null;
            String outcome = result == null ? (Thread.currentThread().isInterrupted() ? "cancelled" : "error")
                    : fallback ? "fallback" : "success";
            // 产品自己的RequestTrace没有rag_engine，源默认标签为unknown。
            metrics.llm("unknown", outcome, (System.nanoTime()-generationStarted)/1e9);
            if (fallback) metrics.fallback();
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        if (result.fallback || result.tokensUsed > settings.requestTokenBudget())
            return new TaskExecutionResult(
                    AnswerValidator.UNAVAILABLE,
                    "system_error",
                    "generation_failed",
                    List.of(),
                    "");
        if (tools.searchAttempted
                && result.collectedChunks.isEmpty()
                && retrievalFailure[0] != null)
            return new TaskExecutionResult(
                    AnswerValidator.UNAVAILABLE,
                    "system_error",
                    "retrieval_failed",
                    List.of(),
                    pinned[0]);
        emit.accept(TaskEvent.of("status", Map.of("stage", "validation")));
        Map<String, Object> evidence =
                AnswerValidator.evidence(
                        result.collectedChunks, evidenceTools, settings.evidenceMaxChars());
        AnswerValidator.Judge activeJudge = judge;
        if (activeJudge == null && settings.onlineJudgeEnabled())
            activeJudge =
                    (question, answer, ev, timeout) -> {
                        var reply =
                                model.call(
                                        List.of(
                                                AgentMessage.system(AnswerValidator.JUDGE_PROMPT),
                                                AgentMessage.human(
                                                        ChatAgentPorts.json(
                                                                Map.of(
                                                                        "question",
                                                                        question,
                                                                        "answer",
                                                                        answer,
                                                                        "evidence",
                                                                        ev)))),
                                        List.of());
                        return new ObjectMapper()
                                .readerFor(
                                        new com.fasterxml.jackson.core.type.TypeReference<
                                                Map<String, Object>>() {})
                                .with(
                                        com.fasterxml.jackson.databind.DeserializationFeature
                                                .FAIL_ON_TRAILING_TOKENS)
                                .readValue(reply.content());
                    };
        AnswerValidator.Judge selectedJudge = activeJudge;
        AnswerValidator.Judge timedJudge =
                selectedJudge == null
                        ? null
                        : (question, answer, ev, timeout) -> {
                            ExecutorService judgeWorkers =
                                    Executors.newVirtualThreadPerTaskExecutor();
                            Future<Map<String, Object>> f =
                                    judgeWorkers.submit(
                                            () ->
                                                    selectedJudge.judge(
                                                            question, answer, ev, timeout));
                            try {
                                return f.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                            } finally {
                                f.cancel(true);
                                judgeWorkers.shutdownNow();
                            }
                        };
        AnswerValidator.Verdict verdict =
                AnswerValidator.validate(
                        c.question(),
                        result.answer,
                        evidence,
                        timedJudge,
                        judgeBudget(candidateStarted), metrics);
        List<CitationData> citations =
                result.collectedChunks.stream()
                        .map(
                                chunk ->
                                        new CitationData(
                                                chunk.chunkId(),
                                                chunk.sourcePath(),
                                                chunk.title(),
                                                chunk.section(),
                                                round4(chunk.score())))
                        .toList();
        return new TaskExecutionResult(
                verdict.answer(), verdict.status(), verdict.reason(), citations, pinned[0]);
    }

    private Duration judgeBudget(long started) {
        long remaining =
                settings.requestTimeout().toMillis() - (System.nanoTime() - started) / 1_000_000;
        return Duration.ofMillis(
                Math.max(1000, Math.min(settings.validationTimeout().toMillis(), remaining)));
    }

    private static double round4(double value) {
        return new java.math.BigDecimal(value)
                .setScale(4, java.math.RoundingMode.HALF_EVEN)
                .doubleValue();
    }

    private static String protocolHint(String name) {
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        String[] p = (dot < 0 ? name : name.substring(0, dot)).split("_", -1);
        return p.length >= 3 && !p[2].isEmpty() ? p[2].toLowerCase(Locale.ROOT) : null;
    }
}
