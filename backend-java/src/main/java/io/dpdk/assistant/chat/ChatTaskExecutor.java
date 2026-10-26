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
        UsageCore.UsageRecorder recorder = new UsageCore.UsageRecorder(subject, sessionId);
        try {
            return new Prepared(
                    ChatMemory.build(sessionId, sessions, tx, summaryModel(recorder), settings));
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
        synchronized (recorder) {
            recorder.recordLlm(stage, agentSettings.modelName(), values);
        }
    }

    private void flushUsage(
            UsageCore.UsageRecorder recorder, String requestId, String subject, String sessionId) {
        UsagePolicy policy = usagePolicy;
        if (!policy.enabled()) return;
        try {
            List<UsageCore.UsageEntry> entries;
            synchronized (recorder) {
                entries = recorder.entries();
            }
            if (entries.isEmpty()) return;
            usage.recordRows(
                    UsageCore.entriesToRows(
                            entries,
                            policy.prices(),
                            policy.version(),
                            requestId,
                            subject,
                            sessionId,
                            clock.nextSessionIso()));
        } catch (RuntimeException ignored) {
            /* 与源相同的 best-effort 记账。 */
        }
    }

    public record Prepared(ChatMemory.View memory) {
        public String retrievalContext() {
            return memory.retrievalContext();
        }
    }

    public void bindPrepared(String taskId, Prepared prepared, Map<String, Object> explain) {
        preparedMemory.put(taskId, prepared.memory());
        if (explain != null) explainContexts.put(taskId, new LinkedHashMap<>(explain));
    }

    @Override
    public TaskExecutionResult run(TaskExecutionContext context, Consumer<TaskEvent> emit)
            throws Exception {
        ensureAvailable();
        UsageCore.UsageRecorder recorder =
                new UsageCore.UsageRecorder(context.subject(), context.sessionId());
        ExecutorService wall = Executors.newVirtualThreadPerTaskExecutor();
        Future<TaskExecutionResult> future = wall.submit(() -> candidate(context, emit, recorder));
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
            preparedMemory.remove(context.taskId());
            explainContexts.remove(context.taskId());
            flushUsage(recorder, context.requestId(), context.subject(), context.sessionId());
        }
    }

    private TaskExecutionResult candidate(
            TaskExecutionContext c, Consumer<TaskEvent> emit, UsageCore.UsageRecorder recorder)
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
                                        limit, recorder);
                        if (result.trace() instanceof RetrievalTrace trace) {
                            retrievalFailure[0] = trace.blockedReason();
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
                        synchronized (recorder) {
                            recorder.recordLlm(stage, name, tokens);
                        }
                    }

                    public void recordOperation(String kind, String stage, long duration) {
                        synchronized (recorder) {
                            recorder.recordOperation(
                                    kind, stage, (int) Math.min(Integer.MAX_VALUE, duration), 1);
                        }
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
        var result =
                new AgentOrchestrator(recordingModel, agentSettings)
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
                        judgeBudget(candidateStarted));
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
