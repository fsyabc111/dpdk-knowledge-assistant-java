package io.dpdk.assistant.usage;

import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.DbTx;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.contracts.usage.QuotaParticipant;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * 配额计数存储（usage_counters 表；对齐 core/rate_limit.py RateLimiter 的库内部分）。
 *
 * 实现契约 usage.QuotaParticipant：
 * - countAndCheck 参与 caller 事务：quota=0 不记日计数（不限额）；
 *   INSERT ... ON CONFLICT(subject,day,kind) DO UPDATE chat_count+1 RETURNING 的
 *   原子增量与 request 唯一键同提交，count&gt;quota 抛 {@link QuotaExceededException}
 *   整笔回滚；
 * - checkAndCountDaily 为独立事务版本（源现状）：先递增后检查，失败抛出时计数已
 *   提交——「失败计数」现状保留并登记（grading 等非受理路径使用）；
 * - fetchCounters 供 /api/usage/me、/api/usage/subjects 读取（只统计 kind='chat'）。
 */
@Repository
public class UsageCounterStore implements QuotaParticipant {

    private final TransactionRunner runner;
    private final ClockPort clock;

    /** （subject, day, chat_count）只读行。 */
    public record CounterRow(String subject, String day, int chatCount) {
    }

    public UsageCounterStore(TransactionRunner runner, ClockPort clock) {
        this.runner = runner;
        this.clock = clock;
    }

    /* ------------------------------------------------------------------ */
    /* 契约：参与调用方事务                                                 */
    /* ------------------------------------------------------------------ */

    @Override
    public void countAndCheck(DbTx tx, String subject, LocalDate utcDay, String kind, int quota) {
        if (quota == 0) {
            return;
        }
        try {
            int count = incrementDailyInTx(tx.connection(), subject, utcDay.toString(), kind);
            enforce(subject, quota, kind, count);
        } catch (SQLException e) {
            throw new IllegalStateException("usage counter failure", e);
        }
    }

    /** count_kind_in_conn：同 countAndCheck，返回递增后的计数（供编排方记录）。 */
    public int countKindInTx(DbTx tx, String subject, String day, String kind, int quota) {
        if (quota == 0) {
            return 0;
        }
        try {
            int count = incrementDailyInTx(tx.connection(), subject, day, kind);
            enforce(subject, quota, kind, count);
            return count;
        } catch (SQLException e) {
            throw new IllegalStateException("usage counter failure", e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 独立事务版本（源现状：失败计数保留）                                  */
    /* ------------------------------------------------------------------ */

    /** check_and_count_daily：独立事务先递增后检查；超额抛出时本次递增已提交。 */
    public void checkAndCountDaily(String subject, String kind, int quota) {
        if (quota == 0) {
            return;
        }
        String day = LocalDate.ofInstant(clock.nowUtc(), java.time.ZoneOffset.UTC).toString();
        int count = runner.required((DbTx tx) -> {
            try {
                return incrementDailyInTx(tx.connection(), subject, day, kind);
            } catch (SQLException e) {
                throw new IllegalStateException("usage counter failure", e);
            }
        });
        enforce(subject, quota, kind, count);
    }

    /** increment_daily_in_conn：upsert 原子增量，返回递增后的计数。 */
    public int incrementDailyInTx(Connection conn, String subject, String day, String kind)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO usage_counters(subject, day, kind, chat_count) VALUES (?,?,?,1) "
                        + "ON CONFLICT(subject, day, kind) DO UPDATE "
                        + "SET chat_count = usage_counters.chat_count + 1 RETURNING chat_count")) {
            ps.setString(1, subject);
            ps.setString(2, day);
            ps.setString(3, kind);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 读取                                                                */
    /* ------------------------------------------------------------------ */

    /** _fetch_counters_sync：subject=null 全部；只统计 kind='chat'（grading 不进面板）。 */
    public List<CounterRow> fetchCounters(String subject) {
        return runner.required((DbTx tx) -> {
            String sql = subject == null
                    ? "SELECT subject, day, chat_count FROM usage_counters WHERE kind = 'chat'"
                    : "SELECT subject, day, chat_count FROM usage_counters "
                            + "WHERE subject = ? AND kind = 'chat'";
            try (PreparedStatement ps = tx.connection().prepareStatement(sql)) {
                if (subject != null) {
                    ps.setString(1, subject);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<CounterRow> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new CounterRow(rs.getString("subject"), rs.getString("day"),
                                rs.getInt("chat_count")));
                    }
                    return List.copyOf(out);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("usage counter read failure", e);
            }
        });
    }

    /* ------------------------------------------------------------------ */

    private void enforce(String subject, int quota, String kind, int count) {
        if (count > quota) {
            throw new QuotaExceededException(kind, quota, secondsUntilUtcMidnight());
        }
    }

    /** _seconds_until_utc_midnight：距下一 UTC 午夜秒数，至少 1。 */
    private long secondsUntilUtcMidnight() {
        java.time.Instant now = clock.nowUtc();
        java.time.Instant midnight = now.truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                .plus(Duration.ofDays(1));
        return Math.max(1, Duration.between(now, midnight).getSeconds());
    }
}
