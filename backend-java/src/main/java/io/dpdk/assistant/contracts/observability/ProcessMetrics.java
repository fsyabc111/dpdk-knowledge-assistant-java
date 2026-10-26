package io.dpdk.assistant.contracts.observability;

/** 请求级过程观测端口；标签只允许系统枚举，不接收问题、源码或用户身份。 */
public interface ProcessMetrics {
    ProcessMetrics NONE = new ProcessMetrics() {};
    default void llm(String engine, String result, double seconds) {}
    default void retrieval(double seconds) {}
    default void retrievalStage(String stage, double seconds) {}
    default void fallback() {}
    default void rateLimit(String kind) {}
    default void rag(String status, String reason, double seconds) {}
    default void validation(String result, double seconds) {}
    default void tool(String tool, boolean ok, double seconds, int resultBytes) {}
    default void agentTokens(long tokens) {}
    default void usage(UsageSample sample) {}

    record UsageSample(String stage, String kind, String source, String model, String currency,
                       Integer totalTokens, Long costMicros) {}
}
