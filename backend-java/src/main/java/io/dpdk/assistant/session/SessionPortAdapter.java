package io.dpdk.assistant.session;

import io.dpdk.assistant.contracts.platform.DbTx;
import io.dpdk.assistant.contracts.session.MessageInput;
import io.dpdk.assistant.contracts.session.SessionId;
import io.dpdk.assistant.contracts.session.SessionPort;
import java.util.Map;
import java.util.Optional;

/** 会话冻结端口；消息写入参与调用方受理事务。 */
public class SessionPortAdapter implements SessionPort {

    private final SessionService service;

    public SessionPortAdapter(SessionService service) {
        this.service = service;
    }

    @Override
    public SessionId ensureOwned(Optional<String> requestedId, String title, String subject) {
        return service.ensureOwned(requestedId, title, subject);
    }

    @Override
    public void appendUser(DbTx tx, MessageInput input) {
        try {
            service.appendMessage(tx, input.sessionId(), input.role(), input.content(), input.metadata());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("session append failure", e);
        }
    }

    @Override
    public void appendUser(DbTx tx, MessageInput input, String createdIso) {
        try {
            service.appendMessageAt(tx, input.sessionId(), input.role(), input.content(), input.metadata(), createdIso);
        } catch (java.sql.SQLException e) { throw new IllegalStateException("session append failure", e); }
    }

    @Override
    public void touch(DbTx tx, String sessionId, String iso) {
        try {
            service.touch(tx, sessionId, iso);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("session touch failure", e);
        }
    }

    @Override
    public void discardEmpty(String sessionId, String subject) {
        service.discardEmpty(sessionId, subject);
    }

    /** 便捷委托：受理事务内追加用户消息（appendMessage 的 role=user 形态）。 */
    public String appendUser(DbTx tx, String sessionId, String content, Map<String, Object> metadata) {
        try {
            return service.appendMessage(tx, sessionId, "user", content, metadata);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("session append failure", e);
        }
    }
}
