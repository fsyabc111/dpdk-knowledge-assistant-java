package io.dpdk.assistant.platform.wiring;

import io.dpdk.assistant.agent.AgentChatModel;
import io.dpdk.assistant.agent.AgentSettings;
import io.dpdk.assistant.assignment.explanation.ExplainDataService;
import io.dpdk.assistant.chat.ChatSettings;
import io.dpdk.assistant.chat.ChatTaskExecutor;
import io.dpdk.assistant.chat.ProductChatService;
import io.dpdk.assistant.contracts.model.HarnessPort;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.retrieval.PgReleaseReader;
import io.dpdk.assistant.session.SessionService;
import io.dpdk.assistant.task.AdmissionGate;
import io.dpdk.assistant.task.RagTaskService;
import io.dpdk.assistant.task.TaskGradeLedger;
import io.dpdk.assistant.task.TaskStore;
import io.dpdk.assistant.usage.UsageCounterStore;
import io.dpdk.assistant.usage.UsageLedgerStore;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 产品入口始终装配；外部能力未配置时由入口在受理前明确503。 */
@Configuration
public class ChatWiring {
    @Bean
    ChatSettings chatSettings(
            @Value("${dpdk.rag.request-timeout-sec:${RAG_REQUEST_TIMEOUT_SEC:120}}") double request,
            @Value("${dpdk.rag.prepare-timeout-sec:${RAG_PREPARE_TIMEOUT_SEC:15}}") double prepare,
            @Value("${dpdk.rag.validation-timeout-sec:${RAG_VALIDATION_TIMEOUT_SEC:60}}") double validation,
            @Value("${dpdk.rag.context-max-chars:${RAG_CONTEXT_MAX_CHARS:24000}}") int evidence,
            @Value("${dpdk.rag.request-token-budget:${RAG_REQUEST_TOKEN_BUDGET:48000}}") int tokens,
            @Value("${dpdk.rate-limit-per-minute:${RATE_LIMIT_PER_MINUTE:20}}") int rate,
            @Value("${dpdk.conversation.recent-turns:${CONVERSATION_RECENT_TURNS:6}}") int recent,
            @Value("${dpdk.conversation.summary-max-chars:${CONVERSATION_SUMMARY_MAX_CHARS:4800}}") int summary,
            @Value("${dpdk.conversation.memory-max-chars:${CONVERSATION_MEMORY_MAX_CHARS:24000}}") int memory,
            @Value("${dpdk.conversation.summary-max-tokens:${CONVERSATION_SUMMARY_MAX_TOKENS:800}}") int summaryTokens,
            @Value("${dpdk.rag.online-judge-enabled:${RAG_ONLINE_JUDGE_ENABLED:false}}") boolean judge) {
        return new ChatSettings(seconds(request), seconds(prepare), seconds(validation), evidence,
                tokens, rate, recent, summary, memory, summaryTokens, judge);
    }
    private static Duration seconds(double value) {
        if (!Double.isFinite(value) || value <= 0) { throw new IllegalArgumentException("请求时限必须为有限正数"); }
        return Duration.ofMillis(Math.max(1, (long) (value * 1000)));
    }

    @Bean
    ChatTaskExecutor chatTaskExecutor(SessionService sessions, TransactionRunner tx, ClockPort clock,
            ObjectProvider<AgentChatModel> model, AgentSettings agentSettings,
            ObjectProvider<PgReleaseReader> retrieval, ObjectProvider<HarnessPort> harness,
            TaskStore tasks, TaskGradeLedger grades, UsageCounterStore quotas,
            UsageLedgerStore usage, ChatSettings settings, ExplainDataService explain) {
        ChatTaskExecutor executor = new ChatTaskExecutor(sessions, tx, clock, model.getIfAvailable(),
                agentSettings, retrieval.getIfAvailable(), harness.getIfAvailable(), tasks, grades,
                quotas, usage, settings);
        executor.setExplainResolver(explain::resolve);
        return executor;
    }

    @Bean
    ProductChatService productChatService(RagTaskService tasks, AdmissionGate gate,
            SessionService sessions, ChatTaskExecutor executor, ChatSettings settings, ClockPort clock) {
        return new ProductChatService(tasks, gate, sessions, executor, settings, clock);
    }
}
