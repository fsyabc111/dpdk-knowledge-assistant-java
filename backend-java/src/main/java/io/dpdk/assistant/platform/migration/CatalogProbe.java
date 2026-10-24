package io.dpdk.assistant.platform.migration;

import io.dpdk.assistant.platform.config.AppProperties;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/**
 * 迁移前目录探查：判断目标处于哪条受控路径（契约 schema-migration.md 三路径）。
 *
 * 判定只依赖 catalog 事实（表/列存在性 + Flyway 历史），不猜测；
 * 除三种明确状态外一律 REFUSE，绝不盲 stamp。
 */
public final class CatalogProbe {

    /** 27 张静态业务表（不含 LI 动态表与版本表）。 */
    public static final Set<String> CORE_TABLES = Set.of(
            "users", "api_tokens", "password_reset_requests",
            "assistant_sessions", "assistant_messages", "assistant_citations",
            "knowledge_documents", "knowledge_chunks", "knowledge_indexes",
            "knowledge_source_files", "teaching_faqs",
            "usage_counters", "upstream_usage_records",
            "grading_submissions", "assignments", "assignment_items", "admin_audit_log",
            "rag_releases", "rag_index_state", "rag_release_nodes", "rag_requests",
            "rag_issues", "rag_issue_events", "rag_knowledge", "rag_knowledge_revisions",
            "rag_task_inputs", "rag_task_jobs");

    /** LI 动态表（llama-index 运行时生成；旧部署可能尚未创建，分类时视为可选）。 */
    public static final String LI_TABLE = "data_li_knowledge_v1";

    public enum Decision {EMPTY, AT_0013, AT_0015, HISTORY, REFUSE}

    public record State(boolean historyPresent, Set<String> coreTablesPresent,
                        boolean liTablePresent, boolean hasTaskJobId, boolean hasPurpose) {
    }

    public static Decision classify(Connection connection) {
        State s = probe(connection);
        if (s.historyPresent()) {
            return Decision.HISTORY;
        }
        boolean empty = s.coreTablesPresent().isEmpty() && !s.liTablePresent();
        if (empty) {
            return Decision.EMPTY;
        }
        if (!s.coreTablesPresent().containsAll(CORE_TABLES)) {
            return Decision.REFUSE;
        }
        // 0014 引入 grading_submissions.task_job_id；0015 引入 rag_requests.purpose。
        if (!s.hasTaskJobId() && !s.hasPurpose()) {
            return Decision.AT_0013;
        }
        if (s.hasTaskJobId() && s.hasPurpose()) {
            return Decision.AT_0015;
        }
        // 混合状态（如 0014 中间态）不属于三条受控路径，拒绝。
        return Decision.REFUSE;
    }

    public static State probe(Connection connection) {
        try {
            Set<String> tables = new HashSet<>();
            boolean historyPresent;
            try (Statement st = connection.createStatement()) {
                ResultSet rs = st.executeQuery(
                        "SELECT table_name FROM information_schema.tables "
                                + "WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'");
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
                historyPresent = tables.contains("flyway_schema_history");
            }
            Set<String> corePresent = new HashSet<>(tables);
            corePresent.retainAll(CORE_TABLES);
            boolean hasTaskJobId = columnExists(connection, "grading_submissions", "task_job_id");
            boolean hasPurpose = columnExists(connection, "rag_requests", "purpose");
            return new State(historyPresent, corePresent, tables.contains(LI_TABLE), hasTaskJobId, hasPurpose);
        } catch (SQLException e) {
            throw new IllegalStateException("catalog 探查失败: " + e.getClass().getSimpleName(), e);
        }
    }

    private static boolean columnExists(Connection connection, String table, String column) {
        try (var ps = connection.prepareStatement(
                "SELECT 1 FROM information_schema.columns "
                        + "WHERE table_schema = current_schema() AND table_name = ? AND column_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, column);
            ResultSet rs = ps.executeQuery();
            return rs.next();
        } catch (SQLException e) {
            throw new IllegalStateException("列存在性探查失败: " + table + "." + column, e);
        }
    }
}
