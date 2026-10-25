package io.dpdk.assistant.index;

/** 发布、健康探针与回滚共享的完整性结果。 */
public record IndexHealth(int declared, int members, int physical, boolean configMatch) {
    public boolean healthy() { return declared > 0 && declared == members && members == physical && configMatch; }
}
