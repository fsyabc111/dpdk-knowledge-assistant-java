package io.dpdk.assistant.contracts.session;

import java.util.Map;

/**
 * 追加用户消息输入（J2-05 登记扩展：sessionId/role/metadata 为受理事务内完整落库所需；
 * 兼容构造器保留单参形态）。J3-01 原子受理消费完整形态。
 */
public record MessageInput(String content, String sessionId, String role,
                           Map<String, Object> metadata) {

    public MessageInput(String content) {
        this(content, null, "user", Map.of());
    }
}
