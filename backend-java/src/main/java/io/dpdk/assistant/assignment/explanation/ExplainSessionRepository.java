package io.dpdk.assistant.assignment.explanation;

import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import java.sql.PreparedStatement;
import org.springframework.stereotype.Repository;

/** 按 Python 的探测后预建顺序创建确定性会话；不覆盖既有归属、标题或 release。 */
@Repository
public class ExplainSessionRepository {

    private final TransactionRunner txRunner;
    private final ClockPort clock;

    public ExplainSessionRepository(TransactionRunner txRunner, ClockPort clock) {
        this.txRunner = txRunner;
        this.clock = clock;
    }

    public void ensure(String sessionId, String userId, String title) {
        String trimmed = title.codePointCount(0, title.length()) <= 80 ? title
                : title.substring(0, title.offsetByCodePoints(0, 80));
        txRunner.required(tx -> {
            try (PreparedStatement ps = tx.connection().prepareStatement(
                    "INSERT INTO assistant_sessions(id,user_id,title,created_at,updated_at)"
                            + " VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING")) {
                ps.setString(1, sessionId);
                ps.setString(2, userId);
                ps.setString(3, trimmed);
                ps.setString(4, clock.nextSessionIso());
                ps.setString(5, clock.nextSessionIso());
                ps.executeUpdate();
                return null;
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException("explain session storage failure", e);
            }
        });
    }
}
