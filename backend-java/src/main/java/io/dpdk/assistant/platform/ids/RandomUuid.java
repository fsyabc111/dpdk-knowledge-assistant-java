package io.dpdk.assistant.platform.ids;

import io.dpdk.assistant.contracts.platform.UuidPort;
import org.springframework.stereotype.Component;

/** 正式运行使用JDK随机UUID；不允许通过环境配置降为确定序列。 */
@Component
public final class RandomUuid implements UuidPort {
    @Override public java.util.UUID next() { return java.util.UUID.randomUUID(); }
}
