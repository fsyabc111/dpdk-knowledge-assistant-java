package io.dpdk.assistant.admin;

import org.springframework.stereotype.Component;

/**
 * LocalTaskCancellationPort 的默认空实现（J3-01 接线前的占位装配）。
 *
 * 仅表达“端口已被调用”的时序；不执行任何取消动作，不得据此宣称
 * 停号→任务取消集成完成（integration_pending，evidence/J2-02/integration-pending.md）。
 */
@Component
public class NoOpLocalTaskCancellation implements LocalTaskCancellationPort {

    @Override
    public void cancelLocalForUser(String userId) {
        // 有意空实现：真实执行体由 J3-01 提供。
    }
}
