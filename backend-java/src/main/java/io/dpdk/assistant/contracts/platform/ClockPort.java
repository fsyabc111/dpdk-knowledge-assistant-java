package io.dpdk.assistant.contracts.platform;

import java.time.Instant;

/**
 * 平台时钟端口（契约 transaction-ports.json：platform.ClockPort）。
 *
 * UTC 墙钟用于持久化/期限；单调时钟用于预算；会话时间是进程内严格递增的微秒时间戳，
 * 不可与墙钟互换。时间字符串格式对齐 Python `datetime.now(timezone.utc).isoformat()`：
 * 小数秒最多 6 位、微秒为零时不输出小数、时区固定 "+00:00"（契约 identity-and-data.md）。
 */
public interface ClockPort {

    /** UTC墙钟按冻结Python datetime提供微秒精度；单调预算仍使用纳秒端口。 */
    Instant nowUtc();

    long monotonicNanos();

    String nextSessionIso();

    /** 外部传输预算，区别于持久化事件；默认使用同一时钟并保留真实单调期限。 */
    default io.dpdk.assistant.contracts.model.Deadline deadlineAfter(java.time.Duration budget) {
        return new io.dpdk.assistant.contracts.model.Deadline(nowUtc().plus(budget),
                monotonicNanos() + budget.toNanos());
    }
}
