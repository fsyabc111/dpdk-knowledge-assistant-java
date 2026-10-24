package io.dpdk.assistant.contracts.model;

/** 聊天结果；含 usage（未知为 null，不用 0 顶替）与错误分类载体，字段由 J1-02 登记补全。 */
public record ChatResult(String content, Usage usage) {

    /** token 三列未知为 null；0 表示确实为零（契约 usage 语义）。 */
    public record Usage(Integer promptTokens, Integer completionTokens, Integer totalTokens, String usageSource) {
    }
}
