package io.dpdk.assistant.platform.wiring;

import io.dpdk.assistant.admin.LocalTaskCancellationPort;
import io.dpdk.assistant.agent.AgentSettings;
import io.dpdk.assistant.assignment.submission.SubmissionSettings;
import io.dpdk.assistant.auth.AuthSettingsProvider;
import io.dpdk.assistant.contracts.identity.AccountWriteGuard;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.contracts.session.SessionPort;
import io.dpdk.assistant.contracts.usage.QuotaParticipant;
import io.dpdk.assistant.session.SessionPortAdapter;
import io.dpdk.assistant.session.SessionService;
import io.dpdk.assistant.task.*;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

/** 任务装配单写入口；受理事务不跨外部调用，应用就绪后才启动后台循环。 */
@Configuration
public class TaskWiring {
    @Bean
    TaskSettings taskSettings(
            @Value("${dpdk.task.max-seconds:${RAG_TASK_MAX_SECONDS:600}}") double maxSeconds,
            @Value("${dpdk.task.queue-timeout-sec:${RAG_QUEUE_TIMEOUT_SEC:10}}") double queueSeconds,
            @Value("${dpdk.task.max-active:${RAG_MAX_ACTIVE_REQUESTS:20}}") int active,
            @Value("${dpdk.task.max-waiting:${RAG_MAX_WAITING_REQUESTS:20}}") int waiting,
            @Value("${dpdk.task.input-ttl-hours:${RAG_TASK_INPUT_TTL_HOURS:24}}") int inputHours) {
        if (!Double.isFinite(maxSeconds) || !Double.isFinite(queueSeconds)
                || maxSeconds <= 0 || queueSeconds <= 0) {
            throw new IllegalArgumentException("任务时限必须为有限正数");
        }
        return new TaskSettings(Duration.ofMillis(Math.max(1, (long) (maxSeconds * 1000))),
                Duration.ofMillis(Math.max(1, (long) (queueSeconds * 1000))), active, waiting,
                Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(300),
                500, Duration.ofHours(inputHours));
    }

    @Bean
    AgentSettings agentSettings(SubmissionSettings grading,
            @Value("${dpdk.agent.max-tool-rounds:${AGENT_MAX_TOOL_ROUNDS:5}}") int rounds,
            @Value("${dpdk.agent.request-token-budget:${AGENT_REQUEST_TOKEN_BUDGET:48000}}") int tokens,
            @Value("${dpdk.agent.context-trim-enabled:${AGENT_CONTEXT_TRIM_ENABLED:true}}") boolean trim,
            @Value("${dpdk.rag.context-max-chars:${RAG_CONTEXT_MAX_CHARS:24000}}") int chars,
            @Value("${dpdk.feedback.escalation-threshold:${FEEDBACK_ESCALATION_THRESHOLD:3}}") int threshold,
            @Value("${dpdk.feedback.allow-explicit-ask:${FEEDBACK_ALLOW_EXPLICIT_ASK:true}}") boolean explicit,
            @Value("${dpdk.agent.profile-inject-enabled:${AGENT_PROFILE_INJECT_ENABLED:true}}") boolean profile,
            @Value("${dpdk.reference-answer.max-chars:${REFERENCE_ANSWER_MAX_CHARS:20000}}") int referenceChars,
            @Value("${dpdk.model.chat.model-name:${MODEL_NAME:qwen3.8-flash}}") String modelName) {
        return new AgentSettings(rounds, tokens, trim, chars, grading.gradingTimeoutSec(),
                grading.gradingMaxCodeBytes(), grading.gradingDailyQuota(), threshold, explicit,
                profile, referenceChars, modelName);
    }

    @Bean SessionPort sessionPort(SessionService sessions) { return new SessionPortAdapter(sessions); }
    @Bean TaskStore taskStore(TransactionRunner tx, ClockPort clock, QuotaParticipant quotas, TaskSettings settings) {
        return new TaskStore(tx, clock, quotas, settings.inputTtl());
    }
    @Bean AdmissionGate admissionGate(TaskSettings s) {
        return new AdmissionGate(s.maxActiveRequests(), s.maxWaitingRequests(), s.admissionQueueTimeout());
    }
    @Bean ManagedTaskRegistry managedTaskRegistry(ClockPort clock, TaskSettings s) {
        return new ManagedTaskRegistry(clock, s.finishedTtl());
    }
    @Bean(destroyMethod = "shutdownWatchdog")
    TaskRunner taskRunner(TaskStore store, AdmissionGate gate, ManagedTaskRegistry registry,
                          ClockPort clock, TaskSettings settings) {
        return TaskRunner.withDefaults(store, store::finish, gate, registry, clock, settings);
    }
    @Bean TaskRecovery taskRecovery(TaskStore store, TaskRunner runner, AdmissionGate gate,
            ClockPort clock, TaskSettings settings, TransactionRunner tx, AuthSettingsProvider auth) {
        return new TaskRecovery(store, runner, gate, clock, settings, tx,
                () -> "local".equals(auth.current().authMode()));
    }
    @Bean TaskEventStreams taskEventStreams(TaskStore store, TaskRunner runner, TaskRecovery recovery, ClockPort clock) {
        return new TaskEventStreams(store, runner, recovery, clock);
    }
    @Bean RagTaskService ragTaskService(TaskStore store, TaskRunner runner, TaskRecovery recovery,
            TaskEventStreams streams, AccountWriteGuard guard, SessionPort sessions,
            QuotaParticipant quotas, ClockPort clock, TransactionRunner tx, TaskSettings settings) {
        return new RagTaskService(store, runner, recovery, streams, guard, sessions, quotas, clock, tx, settings);
    }
    @Bean TaskGradeLedger taskGradeLedger(TaskStore store, AccountWriteGuard guard,
            TransactionRunner tx, ClockPort clock) {
        return new TaskGradeLedger(store, guard, tx, clock);
    }
    @Bean LocalTaskCancellationPort localTaskCancellation(RagTaskService tasks) { return new TaskLocalCancellation(tasks); }
    @Bean(destroyMethod = "close")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
            name = "dpdk.task.background-enabled", havingValue = "true", matchIfMissing = true)
    TaskBackground taskBackground(TaskRecovery recovery, ObjectProvider<TaskExecutor> executor) {
        return new TaskBackground(recovery, executor);
    }

    /** 后台循环只持有任务ID/错误类型；不记录临时执行源。 */
    public static final class TaskBackground implements AutoCloseable {
        private final TaskRecovery recovery;
        private final ObjectProvider<TaskExecutor> executor;
        private Thread heartbeat;
        private Thread orphanScan;
        TaskBackground(TaskRecovery recovery, ObjectProvider<TaskExecutor> executor) {
            this.recovery = recovery;
            this.executor = executor;
        }
        @EventListener(ApplicationReadyEvent.class)
        public synchronized void start() {
            if (heartbeat != null || executor.getIfAvailable() == null) { return; }
            recovery.setExecutor(executor.getObject());
            heartbeat = recovery.startHeartbeatLoop();
            orphanScan = Thread.ofVirtual().name("task-orphan-scan").start(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try { recovery.recoverOrphans(); }
                    catch (RuntimeException e) {
                        org.slf4j.LoggerFactory.getLogger(TaskBackground.class).warn(
                                "[task_recover_failed] error_type={}", e.getClass().getSimpleName());
                    }
                    try { Thread.sleep(300_000); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            });
        }
        public synchronized boolean running() { return heartbeat != null && heartbeat.isAlive(); }
        @Override public synchronized void close() {
            if (heartbeat != null) { heartbeat.interrupt(); }
            if (orphanScan != null) { orphanScan.interrupt(); }
        }
    }
}
