package io.dpdk.assistant.agent.graph;

/**
 * 连「系统提示词 + 当前请求」都放不下（P0.1）。
 *
 * <p>消息保留 {@code agent_context_budget} 字样：chat_trace 的 fallback_reason
 * 观测口径不变；编排层据类型改走保留评分事实的安全降级，而不是把它当作
 * 未知上游故障。
 */
public class ContextBudgetExceededException extends RuntimeException {

    public ContextBudgetExceededException(String detail) {
        super("agent_context_budget" + (detail == null || detail.isEmpty() ? "" : ": " + detail));
        this.detail = detail == null ? "" : detail;
    }

    private final String detail;

    public String detail() {
        return detail;
    }
}
