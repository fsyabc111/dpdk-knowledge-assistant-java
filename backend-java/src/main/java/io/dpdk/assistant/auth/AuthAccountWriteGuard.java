package io.dpdk.assistant.auth;

import io.dpdk.assistant.contracts.identity.AccountWriteGuard;
import io.dpdk.assistant.contracts.platform.DbTx;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.stereotype.Component;

/**
 * AccountWriteGuard 实现（契约 transaction-ports.json：identity.AccountWriteGuard）。
 *
 * 业务写入事务内 `UPDATE users SET enabled=enabled WHERE id=? AND enabled=true RETURNING id`
 * 锁账号行：命中 → true；行存在但停用 → false；无 users 行时静态应急 token 主体放行
 * （返回 true），但同名已停账号不可借静态 token 绕过。非 account 模式恒 true。
 * 停号/删除与受理/记分按提交顺序裁决（参与者不得独立提交）。
 */
@Component
public class AuthAccountWriteGuard implements AccountWriteGuard {

    private final AuthSettingsProvider settings;

    public AuthAccountWriteGuard(AuthSettingsProvider settings) {
        this.settings = settings;
    }

    @Override
    public boolean lockEnabled(DbTx tx, String subject) {
        AuthServerSettings current = settings.current();
        if (!current.accountMode()) {
            return true;
        }
        try {
            var conn = tx.connection();
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET enabled = enabled WHERE id = ? AND enabled = ? RETURNING id")) {
                ps.setString(1, subject);
                ps.setBoolean(2, true);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return true;
                    }
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM users WHERE id = ?")) {
                ps.setString(1, subject);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return false;
                    }
                }
            }
            // 静态应急 token 可以没有 users 行；返回其 subject 集合判定。
            return current.staticTokens().values().stream()
                    .anyMatch(p -> p.subject().equals(subject));
        } catch (SQLException e) {
            throw new IllegalStateException("account write guard failure", e);
        }
    }
}
