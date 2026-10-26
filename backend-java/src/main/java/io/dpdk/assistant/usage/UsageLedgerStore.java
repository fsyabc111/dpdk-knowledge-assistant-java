package io.dpdk.assistant.usage;

import io.dpdk.assistant.contracts.platform.DbTx;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * 用量账本落库与查询（对齐 services/usage_service.py UsageService）。
 *
 * - recordRows 不做异常兜底：写失败就抛（账本静默丢数据比写失败更危险）；
 * - query 为只读聚合：since/subject/subjects/stage/request_id 过滤，
 *   subjects 空集合=看不到任何主体 → 空账（范围算错宁可空账也不泄露）；
 *   request_id 只做收窄，仍受主体约束；
 * - 倒序取 MAX_QUERY_ROWS+1 判 truncated，聚合保持纯函数单一口径。
 */
@Repository
public class UsageLedgerStore {

    public static final int MAX_QUERY_ROWS = 20000;

    private final TransactionRunner runner;
    private volatile io.dpdk.assistant.contracts.observability.ProcessMetrics metrics =
            io.dpdk.assistant.contracts.observability.ProcessMetrics.NONE;

    @org.springframework.beans.factory.annotation.Autowired
    public void setMetrics(io.dpdk.assistant.contracts.observability.ProcessMetrics metrics) {
        this.metrics=java.util.Objects.requireNonNull(metrics);
    }

    public UsageLedgerStore(TransactionRunner runner) {
        this.runner = runner;
    }

    /** record_rows：批量写入，返回写入条数；空列表零写入。 */
    public int recordRows(List<UsageLedgerRow> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        String columns = String.join(", ", UsageLedgerRow.COLUMNS);
        String placeholders = "?" + ", ?".repeat(UsageLedgerRow.COLUMNS.size() - 1);
        String sql = "INSERT INTO upstream_usage_records(" + columns + ") VALUES ("
                + placeholders + ")";
        List<UsageLedgerRow> accepted=List.copyOf(rows);
        int written=runner.required((DbTx tx) -> {
            try {
                Connection conn = tx.connection();
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    for (UsageLedgerRow row : accepted) {
                        List<Object> values = row.values();
                        for (int i = 0; i < values.size(); i++) {
                            ps.setObject(i + 1, values.get(i));
                        }
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
                return accepted.size();
            } catch (SQLException e) {
                throw new IllegalStateException("usage ledger write failure", e);
            }
        });
        Runnable observed=() -> accepted.forEach(row -> metrics.usage(
                new io.dpdk.assistant.contracts.observability.ProcessMetrics.UsageSample(
                        row.stage(),row.kind(),row.usageSource(),row.model(),row.currency(),row.totalTokens(),
                        row.costMicros()==null?null:row.costMicros().longValue())));
        if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                && org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { observed.run(); }
                    });
        } else observed.run();
        return written;
    }

    /**
     * query：读回账本并聚合。since 为 ISO8601 文本（created_at >= 文本比较）。
     * subjects 与 subject 互斥（subject 优先，与源一致）；返回体含 truncated/row_limit。
     */
    public Map<String, Object> query(String since, String subject, List<String> subjects,
                                     String stage, String requestId) {
        if (subject == null && subjects != null) {
            List<String> names = subjects.stream()
                    .filter(n -> n != null && !n.isEmpty()).toList();
            if (names.isEmpty()) {
                return emptyScopedSummary();
            }
        }
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (since != null && !since.isEmpty()) {
            clauses.add("created_at >= ?");
            params.add(since);
        }
        if (subject != null && !subject.isEmpty()) {
            clauses.add("subject = ?");
            params.add(subject);
        } else if (subjects != null) {
            List<String> names = subjects.stream()
                    .filter(n -> n != null && !n.isEmpty()).toList();
            if (!names.isEmpty()) {
                clauses.add("subject IN (" + "?" + ", ?".repeat(names.size() - 1) + ")");
                params.addAll(names);
            }
        }
        if (stage != null && !stage.isEmpty()) {
            clauses.add("stage = ?");
            params.add(stage);
        }
        if (requestId != null && !requestId.isEmpty()) {
            clauses.add("request_id = ?");
            params.add(requestId);
        }
        String where = clauses.isEmpty() ? "" : " WHERE " + String.join(" AND ", clauses);
        String sql = "SELECT " + String.join(", ", UsageLedgerRow.COLUMNS)
                + " FROM upstream_usage_records" + where
                + " ORDER BY created_at DESC LIMIT " + (MAX_QUERY_ROWS + 1);

        List<UsageLedgerRow> rows = runner.required((DbTx tx) -> {
            try (PreparedStatement ps = tx.connection().prepareStatement(sql)) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<UsageLedgerRow> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(rowOf(rs));
                    }
                    return out;
                }
            } catch (SQLException e) {
                throw new IllegalStateException("usage ledger read failure", e);
            }
        });
        boolean truncated = rows.size() > MAX_QUERY_ROWS;
        if (truncated) {
            rows = rows.subList(0, MAX_QUERY_ROWS);
        }
        Map<String, Object> summary = UsageAggregate.aggregateUsage(rows);
        summary.put("truncated", truncated);
        summary.put("row_limit", MAX_QUERY_ROWS);
        return summary;
    }

    /** count_rows：账本行数（运维自检）。 */
    public int countRows(String since) {
        String sql = since == null || since.isEmpty()
                ? "SELECT count(*) FROM upstream_usage_records"
                : "SELECT count(*) FROM upstream_usage_records WHERE created_at >= ?";
        return runner.required((DbTx tx) -> {
            try (PreparedStatement ps = tx.connection().prepareStatement(sql)) {
                if (since != null && !since.isEmpty()) {
                    ps.setString(1, since);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getInt(1);
                }
            } catch (SQLException e) {
                throw new IllegalStateException("usage ledger count failure", e);
            }
        });
    }

    public record MetricGroup(String stage, String kind, String source, String model, String currency,
                              long rows, long calls, Long tokens, long unknownTokens, Long costMicros,
                              long unknownCosts, Long durationMs) { }

    /** 不截断的持久账本聚合；SQL SUM 的 NULL 保留未知，不转为零。 */
    public List<MetricGroup> metricsSnapshot() {
        return runner.required(tx -> {
            String sql = "SELECT stage,kind,usage_source,model,currency,count(*),sum(call_count),sum(total_tokens), "
                    + "count(*) FILTER (WHERE total_tokens IS NULL),sum(cost_micros), "
                    + "count(*) FILTER (WHERE cost_micros IS NULL),sum(duration_ms) FROM upstream_usage_records "
                    + "GROUP BY stage,kind,usage_source,model,currency";
            try (PreparedStatement ps = tx.connection().prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
                List<MetricGroup> rows = new ArrayList<>();
                while (rs.next()) rows.add(new MetricGroup(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), rs.getLong(6), rs.getLong(7), metricLong(rs,8),
                        rs.getLong(9), metricLong(rs,10), rs.getLong(11), metricLong(rs,12)));
                return List.copyOf(rows);
            } catch (SQLException e) { throw new IllegalStateException("usage metrics failure", e); }
        });
    }

    private static Long metricLong(ResultSet rs, int column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).longValue();
    }

    private static Map<String, Object> emptyScopedSummary() {
        Map<String, Object> summary = UsageAggregate.aggregateUsage(List.of());
        summary.put("truncated", false);
        summary.put("row_limit", MAX_QUERY_ROWS);
        return summary;
    }

    private static UsageLedgerRow rowOf(ResultSet rs) throws SQLException {
        return new UsageLedgerRow(
                rs.getString("id"),
                rs.getString("request_id"),
                rs.getString("subject"),
                rs.getString("session_id"),
                rs.getString("kind"),
                rs.getString("stage"),
                rs.getString("model"),
                getIntOrNull(rs, "prompt_tokens"),
                getIntOrNull(rs, "completion_tokens"),
                getIntOrNull(rs, "total_tokens"),
                rs.getString("usage_source"),
                rs.getInt("call_count"),
                getIntOrNull(rs, "duration_ms"),
                getIntOrNull(rs, "cost_micros"),
                rs.getString("currency"),
                rs.getString("price_version"),
                rs.getString("created_at"));
    }

    private static Integer getIntOrNull(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : ((Number) value).intValue();
    }
}
