package io.dpdk.assistant.index;

import io.dpdk.assistant.usage.UsageCore;

/** 离线用量落库边界；调用方 finally 刷账，写账异常与 Python 一致告警降级。 */
@FunctionalInterface
public interface IndexUsageSink {
    void flush(UsageCore.UsageRecorder usage);
}
