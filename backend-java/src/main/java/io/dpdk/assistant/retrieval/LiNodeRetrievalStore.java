package io.dpdk.assistant.retrieval;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 单发布版本多路召回 PG 数据面（逐句移植 storage/li_retrieval_store.py）。
 *
 * - 全部查询经 rag_release_nodes JOIN 限定版本成员（releaseId 为空时不 JOIN，
 *   对齐源的 store 构造语义；生产装配始终携带 release）；
 * - 参数化 SQL；动态表名经 RetrievalSettings 标识符校验后以带引号标识符拼接；
 * - FTS 活体 regconfig 探查按表进程内缓存一次；与配置不一致时仅 FTS 腿降级
 *   为空并每表告警一次（其余腿不受影响）；
 * - HNSW 开启时在同一连接/事务上先 set_config('hnsw.ef_search', ?, true)。
 */
public final class LiNodeRetrievalStore implements RecallStore {

    private static final Logger log = LoggerFactory.getLogger(LiNodeRetrievalStore.class);

    private static final java.util.regex.Pattern REGCONFIG_RE =
            java.util.regex.Pattern.compile("'([^']+)'::regconfig");

    /** 缓存值 Optional.empty() = 已探查且表/列缺失（与源 None 缓存语义一致）。 */
    private static final Map<String, java.util.Optional<String>> LIVE_REGCONFIG_CACHE =
            new ConcurrentHashMap<>();
    private static final Set<String> MISMATCH_WARNED = ConcurrentHashMap.newKeySet();

    private final DataSource dataSource;
    private final RetrievalSettings settings;
    private final String table;
    private final String releaseId;

    public LiNodeRetrievalStore(DataSource dataSource, RetrievalSettings settings, String releaseId) {
        this.dataSource = dataSource;
        this.settings = settings;
        this.table = settings.quotedTable();
        this.releaseId = releaseId == null ? "" : releaseId;
    }

    /** 供测试观察「每表只告警一次」与活体探查缓存。 */
    static boolean warnedFor(String table) {
        return MISMATCH_WARNED.contains(table);
    }

    static void resetProcessStateForTest() {
        LIVE_REGCONFIG_CACHE.clear();
        MISMATCH_WARNED.clear();
    }

    /* ------------------------------------------------------------------ */
    /* 四条召回腿                                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public List<String> fetchFtsCandidateIds(String matchQuery, int limit) {
        if (matchQuery == null || matchQuery.isEmpty()) {
            return List.of();
        }
        String config = settings.ftsConfig();
        String live = cachedLiveFtsRegconfig(table);
        if (live != null && !live.equals(config)) {
            if (MISMATCH_WARNED.add(table)) {
                log.warn(PythonJson.dumps(Map.of(
                        "event", "rag_fts_config_mismatch",
                        "table", table,
                        "live_regconfig", live,
                        "configured", config)));
            }
            return List.of();
        }
        String clause = "c.text_search_tsv @@ websearch_to_tsquery(CAST(? AS regconfig), ?)";
        String order = "ts_rank(c.text_search_tsv, websearch_to_tsquery(CAST(? AS regconfig), ?)) DESC";
        return ids(clause, List.of(config, matchQuery), order, List.of(config, matchQuery), limit, null);
    }

    @Override
    public List<String> fetchPathCandidateIds(List<String> fragments, int limit) {
        if (fragments == null || fragments.isEmpty()) {
            return List.of();
        }
        List<String> capped = fragments.subList(0, Math.min(20, fragments.size()));
        List<String> args = new ArrayList<>();
        for (String f : capped) {
            args.add("%" + f.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        String clause = String.join(" OR ", args.stream().map(a -> "c.metadata_->>'source_path' ILIKE ?").toList());
        return ids(clause, args, "c.metadata_->>'source_path'", List.of(), limit, null);
    }

    @Override
    public List<String> fetchSemanticCandidateIds(double[] embedding, int limit) {
        if (embedding == null || embedding.length == 0) {
            return List.of();
        }
        String vector = VectorMath.pgVectorLiteral(embedding);
        String preludeSql = null;
        String preludeArg = null;
        if (settings.hnswEnabled()) {
            preludeSql = "SELECT set_config('hnsw.ef_search', ?, true)";
            preludeArg = String.valueOf(settings.hnswEfSearch());
        }
        // 源 _ids("c.embedding IS NOT NULL", (vector,), "c.embedding <=> %s::vector")：
        // 向量占位符在 ORDER BY；参数位序 = (release, vector)。
        return ids("c.embedding IS NOT NULL", List.of(vector), "c.embedding <=> ?::vector", List.of(),
                limit, preludeSql == null ? null : new String[]{preludeSql, preludeArg});
    }

    /** 没有证据时保持空结果，不能拿任意资料填满候选（fetch_fallback_candidate_ids）。 */
    @Override
    public List<String> fetchFallbackCandidateIds() {
        return List.of();
    }

    /* ------------------------------------------------------------------ */
    /* 行回读与单节点                                                        */
    /* ------------------------------------------------------------------ */

    /** fetch_rows_by_ids：列与别名与源一致；无 ORDER BY（源行为）。 */
    @Override
    public List<CandidateRow> fetchRowsByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        String join = releaseId.isEmpty() ? "" : "JOIN rag_release_nodes r ON r.node_id=c.node_id";
        String version = releaseId.isEmpty() ? "" : "r.release_id=? AND ";
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        String sql = "SELECT c.node_id AS id, c.text AS content, c.metadata_->>'source_path' AS source_path, "
                + "c.metadata_->>'section' AS section, c.metadata_->>'title' AS title, "
                + "c.metadata_::text AS metadata_json, c.embedding::text AS embedding_json "
                + "FROM " + table + " c " + join + " "
                + "WHERE " + version + "c.node_id IN (" + placeholders + ")";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement st = conn.prepareStatement(sql)) {
            int index = 1;
            if (!releaseId.isEmpty()) {
                st.setString(index++, releaseId);
            }
            for (String id : ids) {
                st.setString(index++, id);
            }
            try (ResultSet rs = st.executeQuery()) {
                List<CandidateRow> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(new CandidateRow(
                            rs.getString("id"),
                            rs.getString("content"),
                            rs.getString("source_path"),
                            rs.getString("section"),
                            rs.getString("title"),
                            rs.getString("metadata_json"),
                            rs.getString("embedding_json")));
                }
                return rows;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("候选行回读失败", e);
        }
    }

    /** _llama_source_sync：单节点直查（li 引用回读，不限定 release —— 物理行不可变）。 */
    @Override
    public Map<String, Object> getChunk(String nodeId) {
        String sql = "SELECT node_id, text, metadata_::text AS metadata_json FROM "
                + table + " WHERE node_id = ? LIMIT 1";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, nodeId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                Map<String, Object> metadata = PythonJson.parseObject(rs.getString("metadata_json"));
                String sourcePath = String.valueOf(metadata.getOrDefault("source_path", ""));
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("chunk_id", rs.getString("node_id"));
                out.put("source_path", sourcePath);
                Object title = metadata.get("title");
                out.put("title", title != null && !String.valueOf(title).isEmpty() ? title : sourcePath);
                out.put("section", String.valueOf(metadata.getOrDefault("section", "")));
                out.put("content", rs.getString("text"));
                out.put("metadata", metadata);
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("li 节点回读失败", e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                                 */
    /* ------------------------------------------------------------------ */

    private List<String> ids(String clause, List<String> clauseArgs, String order, List<String> orderArgs,
                             int limit, String[] prelude) {
        String join = releaseId.isEmpty() ? "" : "JOIN rag_release_nodes r ON r.node_id=c.node_id";
        String version = releaseId.isEmpty() ? "TRUE" : "r.release_id=?";
        String sql = "SELECT c.node_id FROM " + table + " c " + join + " "
                + "WHERE " + version + " AND (" + clause + ") "
                + "ORDER BY " + order + ", c.node_id LIMIT " + Math.min(limit, 512);
        boolean transaction = prelude != null;
        try (Connection conn = dataSource.getConnection()) {
            if (transaction) {
                conn.setAutoCommit(false);
            }
            try {
                if (prelude != null) {
                    try (PreparedStatement st = conn.prepareStatement(prelude[0])) {
                        st.setString(1, prelude[1]);
                        st.execute();
                    }
                }
                try (PreparedStatement st = conn.prepareStatement(sql)) {
                    int index = 1;
                    if (!releaseId.isEmpty()) {
                        st.setString(index++, releaseId);
                    }
                    for (String arg : clauseArgs) {
                        st.setString(index++, arg);
                    }
                    for (String arg : orderArgs) {
                        st.setString(index++, arg);
                    }
                    List<String> out = new ArrayList<>();
                    try (ResultSet rs = st.executeQuery()) {
                        while (rs.next()) {
                            out.add(rs.getString(1));
                        }
                    }
                    return out;
                }
            } finally {
                if (transaction) {
                    conn.commit();
                    conn.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("召回查询失败", e);
        }
    }

    /** live_fts_regconfig：读 text_search_tsv 生成列当前 regconfig（缺表/列 → null）。 */
    String liveFtsRegconfig() {
        String sql = "SELECT pg_get_expr(d.adbin, d.adrelid) AS expr "
                + "FROM pg_attrdef d JOIN pg_attribute a ON a.attrelid=d.adrelid AND a.attnum=d.adnum "
                + "WHERE d.adrelid = to_regclass(?) AND a.attname='text_search_tsv'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, table);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String expr = rs.getString("expr");
                if (expr == null || expr.isEmpty()) {
                    return null;
                }
                java.util.regex.Matcher m = REGCONFIG_RE.matcher(expr);
                if (!m.find()) {
                    return null;
                }
                String found = m.group(1);
                int dot = found.lastIndexOf('.');
                return dot >= 0 ? found.substring(dot + 1) : found;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("FTS regconfig 探查失败", e);
        }
    }

    private String cachedLiveFtsRegconfig(String quotedTable) {
        return LIVE_REGCONFIG_CACHE.computeIfAbsent(quotedTable,
                t -> java.util.Optional.ofNullable(liveFtsRegconfig()))
                .orElse(null);
    }
}
