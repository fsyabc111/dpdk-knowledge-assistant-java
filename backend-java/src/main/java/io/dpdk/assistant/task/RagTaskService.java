package io.dpdk.assistant.task;

import io.dpdk.assistant.contracts.identity.AccountWriteGuard;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.contracts.session.MessageInput;
import io.dpdk.assistant.contracts.session.SessionPort;
import io.dpdk.assistant.contracts.task.AdmissionInput;
import io.dpdk.assistant.contracts.task.AdmissionPort;
import io.dpdk.assistant.contracts.task.AdmissionResult;
import io.dpdk.assistant.contracts.task.AnswerCompletion;
import io.dpdk.assistant.contracts.task.AnswerCompletionInput;
import io.dpdk.assistant.contracts.task.CompletedAnswer;
import io.dpdk.assistant.contracts.task.LifecyclePort;
import io.dpdk.assistant.contracts.task.Probe;
import io.dpdk.assistant.contracts.task.RequestIdentity;
import io.dpdk.assistant.contracts.task.TaskRow;
import io.dpdk.assistant.contracts.usage.QuotaParticipant;
import io.dpdk.assistant.platform.ids.PythonUuid5;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * 持久任务服务（J3-01 主编排；实现冻结端口 AdmissionPort/LifecyclePort/
 * AnswerCompletion，并承担任务状态 API）。
 *
 * - 受理事务（admit）：启用账号锁 + 唯一任务 + 临时输入 + 用户消息 + 会话 touch +
 *   chat 日配额同一笔事务提交；同 request 并发受理只产生一任务/一消息/一次扣额；
 *   任一步失败整笔回滚；唯一键冲突按既有任务 attach/replay（不重扣）；
 * - probe 三态重放：miss/in_progress/terminal/replay；hash/用户不匹配 409；
 *   历史 NULL hash 拒绝（fail-closed）；讲解身份按确定性 uuid5 白名单放行标题漂移；
 * - 生命周期：owner 缺失/越权 404；cancel 先库行条件更新再中断本进程执行线程；
 *   claim 只更新时间条件（无 fencing token，单副本/心跳范围）；10s 心跳；
 * - finish：pending→终态条件更新、assistant message、ranked citations、issue(若需要)、
 *   session touch、删除临时输入同一事务；重复终态拒绝。
 */
public final class RagTaskService implements AdmissionPort, LifecyclePort, AnswerCompletion {

    private final TaskStore store;
    private final TaskRunner runner;
    private final TaskRecovery recovery;
    private final TaskEventStreams streams;
    private final AccountWriteGuard guard;
    private final SessionPort sessions;
    private final QuotaParticipant counters;
    private final ClockPort clock;
    private final TransactionRunner txRunner;
    private final TaskSettings settings;
    private final IdGenerator ids;

    /** id 生成（测试可注入确定性生成器）。 */
    @FunctionalInterface
    public interface IdGenerator {
        String hex();
    }

    public RagTaskService(TaskStore store, TaskRunner runner, TaskRecovery recovery,
                          TaskEventStreams streams, AccountWriteGuard guard, SessionPort sessions,
                          QuotaParticipant counters, ClockPort clock, TransactionRunner txRunner,
                          TaskSettings settings) {
        this(store, runner, recovery, streams, guard, sessions, counters, clock, txRunner,
                settings, () -> UUID.randomUUID().toString().replace("-", ""));
    }

    public RagTaskService(TaskStore store, TaskRunner runner, TaskRecovery recovery,
                          TaskEventStreams streams, AccountWriteGuard guard, SessionPort sessions,
                          QuotaParticipant counters, ClockPort clock, TransactionRunner txRunner,
                          TaskSettings settings, IdGenerator ids) {
        this.store = store;
        this.runner = runner;
        this.recovery = recovery;
        this.streams = streams;
        this.guard = guard;
        this.sessions = sessions;
        this.counters = counters;
        this.clock = clock;
        this.txRunner = txRunner;
        this.settings = settings;
        this.ids = ids;
    }

    /* ------------------------------------------------------------------ */
    /* probe（三态重放探测）                                                 */
    /* ------------------------------------------------------------------ */

    /** 富 probe：state/row/message 随行（合同 Probe 仅 state；富形态登记见交接）。 */
    public TaskProbeOutcome probeDetail(RequestIdentity identity, String subject,
                                        Optional<String> sessionId) {
        String requestId = identity.requestId();
        String session = sessionId.orElse(null);
        return txRunner.required(tx -> {
            try {
                Optional<TaskRecordRow> row = session == null
                        ? store.findByInitialRequest(tx, requestId, subject)
                        : store.findByRequestAndSession(tx, requestId, session);
                if (row.isEmpty()) {
                    return new TaskProbeOutcome("miss", Optional.empty(), Optional.empty());
                }
                TaskRecordRow current = row.get();
                if (current.userId() == null || !current.userId().equals(subject)) {
                    throw new TaskExceptions.TaskConflictException(
                            "request_id 输入身份不匹配或不可验证，请更换 request_id");
                }
                // 讲解由服务端按成绩行生成固定身份。旧讲解的正文含可修改标题，
                // 仅这个经确定性 uuid5 核实的入口允许标题漂移；普通 chat 始终按 hash 拒绝。
                boolean explainIdentity = false;
                if (identity.purpose() == RequestIdentity.Purpose.explain
                        && "explain".equals(current.purpose())
                        && current.sessionId() != null && current.inputHash() != null
                        && current.sessionId().startsWith("explain-")) {
                    String submissionId = current.sessionId().substring("explain-".length());
                    String expectedRequest = PythonUuid5.uuid5Url(
                            "assignment-explain:" + submissionId);
                    explainIdentity = current.sessionId().equals("explain-" + submissionId)
                            && requestId.equals(expectedRequest);
                }
                if (!explainIdentity && (current.inputHash() == null
                        || !current.inputHash().equals(identity.inputHash()))) {
                    throw new TaskExceptions.TaskConflictException(
                            "request_id 输入身份不匹配或不可验证，请更换 request_id");
                }
                if ("pending".equals(current.status())) {
                    return new TaskProbeOutcome("in_progress", Optional.of(current),
                            Optional.empty());
                }
                if (current.messageId() == null || current.messageId().isEmpty()) {
                    // 取消/错误终态无正文 → terminal，而非 replay。
                    return new TaskProbeOutcome("terminal", Optional.of(current),
                            Optional.empty());
                }
                Optional<TaskStore.MessageRecord> message = store.loadRowMessageInTx(tx,
                        current.messageId());
                if (message.isEmpty()) {
                    throw new TaskExceptions.TaskConflictException(
                            "该请求的会话已删除，请新建请求");
                }
                return new TaskProbeOutcome("replay", Optional.of(current), message);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("task probe failure", e);
            }
        });
    }

    @Override
    public Probe probe(RequestIdentity identity, String subject, Optional<String> sessionId) {
        var result = probeDetail(identity, subject, sessionId);
        return new Probe(result.state(), result.row().map(TaskRecordRow::toContract),
                result.message().map(m -> new io.dpdk.assistant.contracts.task.TaskMessage(
                        m.content(), m.metadataJson())));
    }

    /* ------------------------------------------------------------------ */
    /* admit（原子受理）                                                     */
    /* ------------------------------------------------------------------ */

    /** 富 admit：created/attached/replayed 分类 + 行/消息（合同 AdmissionResult 登记见交接）。 */
    public TaskAdmitOutcome admitDetail(AdmissionInput input) {
        RequestIdentity identity = input.identity();
        String subject = input.subject();
        String session = input.sessionId();
        var created = admitWithinTx(input);
        if (created != null) {
            return created;
        }
        // 唯一键冲突：赢家已把身份与配额同事务提交，按既有任务接续（不重扣）。
        TaskProbeOutcome probed = probeDetail(identity, subject,
                identity.initialRequest() ? Optional.empty() : Optional.ofNullable(session));
        if (probed.isReplay()) {
            return new TaskAdmitOutcome(TaskAdmitOutcome.REPLAYED, probed.row(), probed.message());
        }
        if (probed.row().isPresent()) {
            return new TaskAdmitOutcome(TaskAdmitOutcome.ATTACHED, probed.row(), Optional.empty());
        }
        // 冲突后又查不到行（极端：行被清理）——按源语义抛 409 由端点层转换。
        throw new TaskExceptions.TaskConflictException("该请求已受理，请读取会话结果后重试");
    }

    @Override
    public AdmissionResult admit(AdmissionInput input) {
        var result = admitDetail(input);
        return new AdmissionResult(result.outcome(), result.row().map(TaskRecordRow::toContract),
                result.message().map(m -> new io.dpdk.assistant.contracts.task.TaskMessage(
                        m.content(), m.metadataJson())));
    }

    private TaskAdmitOutcome admitWithinTx(AdmissionInput input) {
        RequestIdentity identity = input.identity();
        String subject = input.subject();
        String session = input.sessionId();
        String taskId = ids.hex();
        String purpose = identity.purpose() == RequestIdentity.Purpose.explain ? "explain" : "chat";
        return txRunner.required(tx -> {
            try {
                // 1. 启用账号锁：停用/删除与受理按提交顺序裁决。
                if (!guard.lockEnabled(tx, subject)) {
                    throw new TaskExceptions.TaskForbiddenException("账号已停用，不能受理新任务");
                }
                // 2. 唯一任务（ON CONFLICT DO NOTHING；false=并发首发败方）。
                boolean won = store.insertRequest(tx, taskId, identity.requestId(),
                        identity.inputHash(), identity.initialRequest(), purpose, subject,
                        session, input.redactedQuestion(), input.context(), input.model());
                if (!won) {
                    return null;   // 冲突接续：tx 内无业务写入，提交无害
                }
                // 3. 临时输入（恢复重驱动的权威字节）。
                var temporary = input.temporaryInput();
                store.storeInputs(tx, taskId, input.rawTemporaryInput(), temporary.attachmentName(),
                        temporary.attachmentSuffix(), temporary.sourceText(), temporary.sourceOrigin());
                // 4. 用户消息（共享事务；不能让嵌套独立事务先提交）。
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("request_id", identity.requestId());
                metadata.put("task_id", taskId);
                sessions.appendUser(tx, new MessageInput(input.redactedQuestion(), session,
                        "user", metadata));
                // 5. 会话更新时间。
                sessions.touch(tx, session, clock.nextSessionIso());
                // 6. chat 日配额（与唯一键同提交，超额抛出回滚全笔）。
                counters.countAndCheck(tx, subject,
                        LocalDate.ofInstant(clock.nowUtc(), ZoneOffset.UTC), "chat",
                        input.dailyQuota());
                Optional<TaskRecordRow> row = store.findByInitialRequest(tx, identity.requestId(),
                        subject);
                if (row.isEmpty()) {
                    row = store.findByRequestAndSession(tx, identity.requestId(), session);
                }
                return new TaskAdmitOutcome(TaskAdmitOutcome.CREATED, row, Optional.empty());
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("task admit failure", e);
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* 生命周期（LifecyclePort + 富形态）                                    */
    /* ------------------------------------------------------------------ */

    @Override
    public Optional<TaskRow> getOwned(String taskId, String subject) {
        return store.findOwned(taskId, subject).map(TaskRecordRow::toContract);
    }

    /** 富 get_task：全行视图（404 语义由消费方按 empty 映射）。 */
    public Optional<TaskRecordRow> getOwnedDetail(String taskId, String subject) {
        return store.findOwned(taskId, subject);
    }

    /** active_task_for_session：会话内最近一条 pending（断线重连找回入口）。 */
    public Optional<TaskRecordRow> activeTaskForSession(String sessionId, String subject) {
        return store.activeTaskForSession(sessionId, subject);
    }

    @Override
    public TaskRow cancel(String taskId, String subject) {
        return cancelDetail(taskId, subject, "user_cancelled").rowView();
    }

    /** 富 cancel：rowView/statusChanged 双信息（幂等：重复取消返回当前终态）。 */
    public CancelOutcome cancelDetail(String taskId, String subject, String origin) {
        Optional<TaskRecordRow> row = store.findOwned(taskId, subject);
        if (row.isEmpty()) {
            throw new TaskExceptions.TaskNotFoundException("task not found");
        }
        // 库内终态先于本地协程取消，成绩事务据此拒绝迟到的写入。
        boolean changed = store.cancelTask(taskId, origin, subject);
        ManagedTask managed = runner.registry().get(taskId);
        if (managed != null && !managed.isTerminal()) {
            runner.cancelLocal(managed);
        }
        Optional<TaskRecordRow> fresh = store.findOwned(taskId, subject);
        return new CancelOutcome(fresh.orElse(row.get()), changed);
    }

    public record CancelOutcome(TaskRecordRow row, boolean changed) {
        public TaskRow rowView() {
            return row.toContract();
        }
    }

    /** cancel_pending_for_user：批量库行终态 + 本进程协程取消（停号等）。 */
    public int cancelPendingForUser(String subject, String origin) {
        int count = store.cancelPendingForUser(subject, origin);
        cancelLocalForUser(subject);
        return count;
    }

    /** cancel_local_for_user：库事务已终态化后，取消本进程残留执行线程。 */
    public void cancelLocalForUser(String subject) {
        List<ManagedTask> running = List.copyOf(runner.registry().runningForSubject(subject));
        for (ManagedTask managed : running) {
            runner.cancelLocal(managed);
        }
    }

    @Override
    public boolean claimRecovery(String taskId, Instant staleCutoff) {
        return store.claimRecovery(taskId, staleCutoff);
    }

    @Override
    public void heartbeat(Collection<String> ownedIds) {
        store.heartbeat(List.copyOf(ownedIds));
    }

    /** is_running：注册表命中且未收口（pending + 在跑 → running）。 */
    public boolean isRunning(String taskId) {
        return runner.isRunning(taskId);
    }

    /** statusOf：pending 行在跑 → running（GET /api/tasks/{id} 的状态口径）。 */
    public String statusOf(TaskRecordRow row) {
        if ("pending".equals(row.status()) && runner.isRunning(row.id())) {
            return "running";
        }
        return row.status();
    }

    /* ------------------------------------------------------------------ */
    /* 终态提交（AnswerCompletion）                                          */
    /* ------------------------------------------------------------------ */

    @Override
    public CompletedAnswer finish(AnswerCompletionInput input) {
        List<CitationData> citations = input.orderedCitations().stream()
                .map(item -> {
                    if (item instanceof CitationData data) {
                        return data;
                    }
                    throw new IllegalArgumentException(
                            "orderedCitations 必须是 CitationData（J3-03 装配口径）");
                })
                .toList();
        String messageId = store.finish(input.taskId(), input.sessionId(), input.answer(),
                citations, input.verdict(), input.reason(), input.releaseId(), input.elapsedMs());
        return new CompletedAnswer(input.taskId(), input.verdict(), messageId);
    }

    /** 富 finish：messageId + metadata 随行（重复终态抛 IllegalStateException 同源）。 */
    public FinishResult finishDetail(String taskId, String sessionId, String answer,
                                     List<CitationData> citations, String status, String reason,
                                     String releaseId, long elapsedMs) {
        String messageId = store.finish(taskId, sessionId, answer, citations, status, reason,
                releaseId, elapsedMs);
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("task_id", taskId);
        metadata.put("confidence", "answered".equals(status) ? "high" : "low");
        metadata.put("answer_status", status);
        metadata.put("answer_reason", reason);
        metadata.put("stream_status", "completed");
        if (!"answered".equals(status) && !"not_applicable".equals(status)) {
            metadata.put("issue_id", taskId);
        }
        return new FinishResult(taskId, status, messageId, metadata);
    }

    public record FinishResult(String taskId, String status, String messageId,
                               Map<String, Object> metadata) {
    }

    /* ------------------------------------------------------------------ */
    /* 执行体托管 / 订阅 / 恢复（委托运行层）                                 */
    /* ------------------------------------------------------------------ */

    /** start_task：幂等起步（占槽 429 原样抛出）。 */
    public ManagedTask startTask(String subject, String requestId, String sessionId,
                                 TaskExecutor.TaskExecutionContext context, TaskExecutor executor) {
        return runner.startTask(subject, requestId, sessionId, context, executor);
    }

    /** 起步并返回句柄（受理事务之后由消费方调用；恢复路径走 recovery）。 */
    public ManagedTask ensureStarted(String subject, String requestId, String sessionId,
                                     TaskExecutor.TaskExecutionContext context,
                                     TaskExecutor executor) {
        ManagedTask managed = runner.registry().get(context.taskId());
        if (managed != null) {
            return managed;
        }
        return runner.startTask(subject, requestId, sessionId, context, executor);
    }

    /** waitManagedResult：等执行收口；超时抛 409 语义（detail 带 task_id）。 */
    public TaskExecutor.TaskExecutionResult waitResult(ManagedTask managed, java.time.Duration timeout)
            throws InterruptedException {
        return runner.waitResult(managed, timeout);
    }

    /**
     * attached_response：probe 命中 in_progress/terminal 时的非流式接续。
     * pending 行先尝试重驱动等待；仍 pending 抛 TaskPendingException（409 语义）。
     */
    public TaskResultView attachResponse(TaskRecordRow row, String subject,
                                         java.time.Duration waitTimeout) throws InterruptedException {
        Optional<TaskRecordRow> current = Optional.of(row);
        if ("pending".equals(row.status())) {
            Optional<ManagedTask> managed = recovery.ensureManaged(row);
            if (managed.isPresent()) {
                TaskExecutor.TaskExecutionResult result = runner.waitResult(managed.get(),
                        waitTimeout);
                if (result != null) {
                    Optional<TaskRecordRow> fresh = store.findOwned(row.id(), subject);
                    if (fresh.isPresent()) {
                        current = fresh;
                    }
                } else if (managed.get().isTerminal()) {
                    Optional<TaskRecordRow> fresh = store.findOwned(row.id(), subject);
                    if (fresh.isPresent()) {
                        current = fresh;
                    }
                } else {
                    current = Optional.empty();
                }
            } else {
                current = store.findOwned(row.id(), subject);
            }
        }
        if (current.isEmpty() || "pending".equals(current.get().status())) {
            throw new TaskExceptions.TaskPendingException(row.id(),
                    "任务等待重驱动，可稍后重试或通过 /api/tasks/{task_id} 续传");
        }
        TaskRecordRow terminal = current.get();
        Optional<TaskStore.MessageRecord> message = terminal.messageId() == null
                ? Optional.empty() : store.loadRowMessage(terminal.messageId());
        return streams.chatResponseFromRow(terminal, message.orElse(null));
    }

    /** task_events 路由（SSE 续传：注册表 → 订阅；终态 → 重建；pending → 重驱动）。 */
    public java.util.concurrent.Flow.Publisher<io.dpdk.assistant.contracts.chat.SseEvent>
    taskEvents(String taskId, String subject) {
        return streams.subscribeOwned(taskId, subject);
    }

    /** 恢复扫描一拍（recover_orphans）。 */
    public int recoverOrphans() {
        return recovery.recoverOrphans();
    }

    /** heartbeat_owned：本进程在跑任务的 pending 行拨新。 */
    public int heartbeatOwned() {
        return recovery.heartbeatOwned();
    }

    public TaskRecovery recovery() {
        return recovery;
    }

    public TaskRunner runner() {
        return runner;
    }

    public TaskStore store() {
        return store;
    }

    /** load_task_inputs（恢复上下文/账本回填读取）。 */
    public Optional<TaskInputRecord> loadInputs(String taskId) {
        return store.loadInputs(taskId);
    }

    /** mark_interrupted（超时/判死路径的库侧条件更新）。 */
    public boolean markInterrupted(String taskId, String reason) {
        return store.markInterrupted(taskId, reason);
    }
}
