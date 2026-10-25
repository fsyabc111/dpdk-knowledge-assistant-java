package io.dpdk.assistant.task;

import io.dpdk.assistant.contracts.platform.DbTx;
import io.dpdk.assistant.contracts.session.MessageInput;
import io.dpdk.assistant.contracts.session.SessionId;
import io.dpdk.assistant.contracts.session.SessionPort;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;

/**
 * SessionPort → SessionService 过渡期桥（J2-05 交接指明的过渡期消费形态）。
 *
 * contracts.session.SessionPort.appendUser 的完整能力在
 * {@code session.SessionService.appendMessage}（事务参与，已测试）；
 * session 包自带的 SessionPortAdapter.appendUser 在适配器未更新前抛出登记指针，
 * 本桥由本卡持有装配（Spring 装配由集成者统一接线）。
 */
public final class TaskSessionPortBridge implements SessionPort {

    private final io.dpdk.assistant.session.SessionService service;

    public TaskSessionPortBridge(io.dpdk.assistant.session.SessionService service) {
        this.service = service;
    }

    @Override
    public SessionId ensureOwned(Optional<String> requestedId, String title, String subject) {
        return service.ensureOwned(requestedId, title, subject);
    }

    @Override
    public void appendUser(DbTx tx, MessageInput input) {
        try {
            service.appendMessage(tx, input.sessionId(), input.role(), input.content(),
                    input.metadata());
        } catch (SQLException e) {
            throw new IllegalStateException("session append failure", e);
        }
    }

    @Override
    public void touch(DbTx tx, String sessionId, String iso) {
        try {
            service.touch(tx, sessionId, iso);
        } catch (SQLException e) {
            throw new IllegalStateException("session touch failure", e);
        }
    }

    @Override
    public void discardEmpty(String sessionId, String subject) {
        service.discardEmpty(sessionId, subject);
    }

    /** 便捷委托：受理事务外独立提交的会话内追加（用户消息旁路写入用）。 */
    public String appendMessage(DbTx tx, String sessionId, String role, String content,
                                Map<String, Object> metadata) {
        try {
            return service.appendMessage(tx, sessionId, role, content, metadata);
        } catch (SQLException e) {
            throw new IllegalStateException("session append failure", e);
        }
    }
}
