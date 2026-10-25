package io.dpdk.assistant.session;

import io.dpdk.assistant.contracts.platform.DbTx;
import io.dpdk.assistant.contracts.session.MessageInput;
import io.dpdk.assistant.contracts.session.SessionId;
import io.dpdk.assistant.contracts.session.SessionPort;
import java.util.Map;
import java.util.Optional;

/**
 * contracts.session.SessionPort 的实现（契约 transaction-ports.json：session.SessionPort）。
 *
 * - ensureOwned / touch / discardEmpty 完整落地（委托 {@link SessionService}）；
 * - appendUser 受阻于冻结契约 {@link MessageInput} 仅有 content 字段（缺
 *   sessionId/role/metadata，无法表达受理事务内的用户消息写入）：本适配器抛出
 *   指向登记的 IllegalStateException；过渡期消费方（J3-01/J3-03/J3-04）使用
 *   {@link SessionService#appendMessage}。接口变更请求（MessageInput 补字段：
 *   sessionId、role、metadata）已登记 handoffs/J2-05.md，集成者更新契约后
 *   本方法单点改为委托 appendMessage。
 */
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
        throw new IllegalStateException(
                "SessionPort.MessageInput 契约字段不足（缺 sessionId/role/metadata）：接口变更请求"
                        + "已登记 handoffs/J2-05.md；过渡期请使用 SessionService.appendMessage(tx, "
                        + "sessionId, role, content, metadata)（同为参与 caller 事务的实现）");
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
