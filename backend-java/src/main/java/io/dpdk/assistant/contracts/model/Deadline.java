package io.dpdk.assistant.contracts.model;

/** 截止期限（纳秒单调时钟 + 墙钟双表述；受控时钟注入）。 */
public record Deadline(java.time.Instant wallDeadline, long monotonicNanosDeadline) {
}
