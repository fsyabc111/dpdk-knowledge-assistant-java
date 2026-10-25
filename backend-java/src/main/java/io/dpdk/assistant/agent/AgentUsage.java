package io.dpdk.assistant.agent;

/**
 * 一次模型调用的 token 用量（对位 usage_metadata.total_tokens 等）。
 *
 * <p>契约语义（contracts/model.ChatResult.Usage）：三列未知为 null，不用 0 顶替；
 * 0 表示确实为零。totalTokens 未知时图内预算按 0 累计（与 Python
 * ``(usage_metadata or {}).get("total_tokens", 0)`` 口径一致）。
 */
public record AgentUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {

    public static final AgentUsage UNKNOWN = new AgentUsage(null, null, null);

    /** 图内 token 预算累计口径：未知按 0。 */
    public int totalOrZero() {
        return totalTokens == null ? 0 : totalTokens;
    }
}
