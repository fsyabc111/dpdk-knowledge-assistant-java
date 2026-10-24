package io.dpdk.assistant.contracts.session;

import io.dpdk.assistant.contracts.platform.DbTx;
import java.util.Optional;

/**
 * 会话端口（owner J2-05；契约 transaction-ports.json：session.SessionPort）。
 *
 * ensureOwned 在 Python 是受理前独立提交的会话操作；原子受理内 append/touch 参与
 * caller 事务；首发唯一键败方只补偿清理空会话，不删除赢家有消息的会话；
 * release 绑定首次后保持。
 */
public interface SessionPort {

    SessionId ensureOwned(Optional<String> requestedId, String title, String subject);

    void appendUser(DbTx tx, MessageInput input);

    void touch(DbTx tx, String sessionId, String iso);

    void discardEmpty(String sessionId, String subject);
}
