package io.dpdk.assistant.index;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.index.IndexNode;
import io.dpdk.assistant.contracts.index.PreparedIndex;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.retrieval.RetrievalSettings;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/** 只对显式 LI 表写入，CAS、物理节点、release 与成员使用同一事务。 */
final class JdbcIndexStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern REGCONFIG = Pattern.compile("'([^']+)'::regconfig");
    private final DataSource dataSource;
    private final TransactionRunner transactions;
    private final RetrievalSettings settings;
    private final String table;
    record Existing(String configuration, List<IndexNode> nodes) {}
    record Published(String id, String title, String body, int revision) {}

    JdbcIndexStore(DataSource dataSource, TransactionRunner transactions, RetrievalSettings settings) {
        this.dataSource = dataSource;
        this.transactions = transactions;
        this.settings = settings;
        this.table = settings.quotedTable();
    }

    String active() {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT release_id FROM rag_index_state WHERE name='active'"); ResultSet r = s.executeQuery()) {
            return r.next() ? r.getString(1) : "";
        } catch (SQLException e) { throw failure(e); }
    }

    Existing existing(String release) {
        try (Connection c = dataSource.getConnection()) {
            String config = "";
            try (PreparedStatement s = c.prepareStatement("SELECT configuration FROM rag_releases WHERE id=?")) {
                s.setString(1, release);
                try (ResultSet r = s.executeQuery()) { if (r.next()) config = r.getString(1); }
            }
            List<IndexNode> nodes = new ArrayList<>();
            try (PreparedStatement s = c.prepareStatement("SELECT c.node_id,c.text,c.metadata_ FROM " + table
                    + " c JOIN rag_release_nodes r ON c.node_id=r.node_id WHERE r.release_id=?")) {
                s.setString(1, release);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) nodes.add(new IndexNode(r.getString(1), r.getString(2),
                            JSON.readValue(r.getString(3), new TypeReference<Map<String, Object>>() {}), null));
                }
            }
            return new Existing(config, List.copyOf(nodes));
        } catch (Exception e) { throw failure(e); }
    }

    List<Published> published() {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT id,title,body,revision FROM rag_knowledge WHERE status='published' ORDER BY id");
             ResultSet r = s.executeQuery()) {
            List<Published> out = new ArrayList<>();
            while (r.next()) out.add(new Published(r.getString(1), r.getString(2), r.getString(3), r.getInt(4)));
            return List.copyOf(out);
        } catch (SQLException e) { throw failure(e); }
    }

    void checkFts() {
        String sql = "SELECT pg_get_expr(d.adbin,d.adrelid) FROM pg_attrdef d JOIN pg_attribute a "
                + "ON a.attrelid=d.adrelid AND a.attnum=d.adnum WHERE d.adrelid=to_regclass(?) AND a.attname='text_search_tsv'";
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, table);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next() || r.getString(1) == null) return;
                var match = REGCONFIG.matcher(r.getString(1));
                if (!match.find()) return;
                String live = match.group(1);
                live = live.substring(live.lastIndexOf('.') + 1);
                if (!live.equals(settings.ftsConfig())) throw new IllegalArgumentException(
                        "text_search_tsv 的 regconfig（" + live + "）与 LLAMA_INDEX_FTS_CONFIG（"
                                + settings.ftsConfig() + "）不一致：请先迁移后再 ingest");
            }
        } catch (SQLException e) { throw failure(e); }
    }

    IndexHealth health(String release, String configuration) {
        try (Connection c = dataSource.getConnection(); PreparedStatement s = c.prepareStatement(
                "SELECT node_count,configuration FROM rag_releases WHERE id=?")) {
            s.setString(1, release);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return new IndexHealth(0, 0, 0, false);
                int declared = r.getInt(1);
                boolean matches = configuration.equals(r.getString(2));
                return new IndexHealth(declared, count(c, "SELECT count(*) FROM rag_release_nodes WHERE release_id=?", release),
                        count(c, "SELECT count(*) FROM rag_release_nodes r JOIN " + table + " c ON c.node_id=r.node_id WHERE r.release_id=?", release), matches);
            }
        } catch (SQLException e) { throw failure(e); }
    }

    private int count(Connection c, String sql, String release) throws SQLException {
        try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, release);
            try (ResultSet r = s.executeQuery()) { r.next(); return r.getInt(1); }
        }
    }

    void publish(String expected, PreparedIndex index) {
        transactions.required(tx -> {
            try {
                Connection c = tx.connection();
                try (PreparedStatement s = c.prepareStatement(
                        "UPDATE rag_index_state SET release_id=? WHERE name='active' AND release_id=?")) {
                    s.setString(1, index.releaseId()); s.setString(2, expected);
                    if (s.executeUpdate() != 1) throw new IllegalStateException("另一任务已发布新版本，请重新扫描后再试");
                }
                for (IndexNode node : index.changedNodes()) {
                    try (PreparedStatement s = c.prepareStatement("INSERT INTO " + table
                            + "(node_id,text,metadata_,embedding) VALUES (?,?,?::jsonb,?::vector)")) {
                        s.setString(1, node.id()); s.setString(2, node.content());
                        s.setString(3, JSON.writeValueAsString(node.metadata()));
                        s.setString(4, vector(node.embedding())); s.executeUpdate();
                    }
                }
                try (PreparedStatement s = c.prepareStatement("INSERT INTO rag_releases(id,configuration,created_at,node_count) VALUES (?,?,?,?)")) {
                    s.setString(1, index.releaseId()); s.setString(2, index.configuration());
                    s.setString(3, index.createdAt()); s.setInt(4, index.desiredNodes().size()); s.executeUpdate();
                }
                try (PreparedStatement s = c.prepareStatement("INSERT INTO rag_release_nodes(release_id,node_id) VALUES (?,?)")) {
                    for (IndexNode node : index.desiredNodes()) {
                        s.setString(1, index.releaseId()); s.setString(2, node.id()); s.addBatch();
                    }
                    s.executeBatch();
                }
                return null;
            } catch (Exception e) { throw failure(e); }
        });
    }

    void rollback(String release) {
        transactions.required(tx -> {
            try (PreparedStatement s = tx.connection().prepareStatement("UPDATE rag_index_state SET release_id=? WHERE name='active'")) {
                s.setString(1, release); s.executeUpdate(); return null;
            } catch (SQLException e) { throw failure(e); }
        });
    }
    private static String vector(List<Float> vector) {
        return "[" + String.join(",", vector.stream().map(Object::toString).toList()) + "]";
    }
    private static IllegalStateException failure(Exception e) { return new IllegalStateException("索引数据库操作失败", e); }
}
