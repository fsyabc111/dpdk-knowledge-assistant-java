package io.dpdk.assistant.usage;

/**
 * 日配额超额（对齐 core/rate_limit.py _enforce_daily_quota 的 429 语义）。
 *
 * 由 {@link UsageCounterStore} 在计数越限（count &gt; quota）时抛出：
 * - 事务内版本（countAndCheck）经调用方事务整笔回滚（受理与 request 唯一键同提交）；
 * - 独立版本（checkAndCountDaily）为源现状：先递增后检查，抛出时计数已提交
 *   （失败计数保留，接口变更登记见 handoff）。
 *
 * 消费方（J2-04/J3-01）映射为 HTTP 429：detail=daily {kind} quota exceeded ({quota}/day)，
 * Retry-After=retryAfterSeconds（距下一 UTC 午夜，至少 1 秒）。
 */
public class QuotaExceededException extends RuntimeException {

    private final String kind;
    private final int quota;
    private final long retryAfterSeconds;

    public QuotaExceededException(String kind, int quota, long retryAfterSeconds) {
        super("daily " + kind + " quota exceeded (" + quota + "/day)");
        this.kind = kind;
        this.quota = quota;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String kind() {
        return kind;
    }

    public int quota() {
        return quota;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
