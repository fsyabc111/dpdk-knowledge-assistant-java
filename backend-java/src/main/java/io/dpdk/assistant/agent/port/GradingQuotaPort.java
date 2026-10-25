package io.dpdk.assistant.agent.port;

/**
 * 评测日配额端口（非任务路径；对位 rate_limiter.check_and_count_daily，owner J2-05）。
 *
 * <p>语义：quota=0 不限不记；独立事务先递增后检查（现状口径，J2-05 已登记差异）；
 * 超限抛 {@link QuotaExceededException}。任务路径的配额经
 * {@link TaskJobLedgerPort#reserveGradingJob} 同事务扣减，不经本端口。
 */
public interface GradingQuotaPort {

    void checkAndCountDaily(String subject, int quota, String kind);
}
