package io.dpdk.assistant.source;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.platform.DbTx;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 来源回读（对齐 storage/retrieval_store.get_historical_chunk 与
 * llama_index_service._llama_source_sync；只读 SQL）。
 *
 * - li_ 路径：data_&lt;table&gt; 物理行直查（node_id/text/metadata_），title 缺省
 *   回落 source_path，section 缺省空串；
 * - 非 li_（历史 legacy 引用）：knowledge_chunks LEFT JOIN knowledge_documents，
 *   title 缺省回落 source_path——仅服务旧回答的来源还原，不参与候选召回。
 */
public class SourceRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TransactionRunner runner;
    private final String liTable;

    public SourceRepository(TransactionRunner runner, String llamaIndexTable) {
        this.runner = runner;
        this.liTable = "data_" + llamaIndexTable;
    }

    /** 引用展示的最小字段（chunk_id/source_path/title/section）；content 另取。 */
    public record SourceView(String chunkId, String sourcePath, String title, String section,
                             String content, Map<String, Object> metadata) {

        public Map<String, Object> toBody() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("chunk_id", chunkId);
            out.put("source_path", sourcePath);
            out.put("title", title);
            out.put("section", section);
            out.put("content", content);
            out.put("metadata", metadata);
            return out;
        }
    }

    /** 路由（chat.py _citation_source）：engine=llama_index 或 li_ 前缀走 LI 表。 */
    public Optional<SourceView> resolve(String engine, String chunkId) {
        if ("llama_index".equals(engine) || (chunkId != null && chunkId.startsWith("li_"))) {
            return llamaSource(chunkId);
        }
        return historicalChunk(chunkId);
    }

    /** chunk_id 直查路由（sources.py：li_ 前缀分流，不看 engine）。 */
    public Optional<SourceView> byChunkId(String chunkId) {
        if (chunkId != null && chunkId.startsWith("li_")) {
            return llamaSource(chunkId);
        }
        return historicalChunk(chunkId);
    }

    /* ------------------------------------------------------------------ */

    private Optional<SourceView> llamaSource(String nodeId) {
        if (nodeId == null || nodeId.isEmpty()) {
            return Optional.empty();
        }
        return runner.required((DbTx tx) -> {
            try {
                Connection conn = tx.connection();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT node_id, text, metadata_::text AS metadata_json FROM "
                                + liTable + " WHERE node_id = ? LIMIT 1")) {
                    ps.setString(1, nodeId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return Optional.<SourceView>empty();
                        }
                        Map<String, Object> metadata = parseObject(rs.getString("metadata_json"));
                        String sourcePath = str(metadata.get("source_path"));
                        Object title = metadata.get("title");
                        return Optional.of(new SourceView(rs.getString("node_id"), sourcePath,
                                title != null && !str(title).isEmpty() ? str(title) : sourcePath,
                                str(metadata.getOrDefault("section", "")), rs.getString("text"),
                                metadata));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("li 节点回读失败", e);
            }
        });
    }

    private Optional<SourceView> historicalChunk(String chunkId) {
        if (chunkId == null || chunkId.isEmpty()) {
            return Optional.empty();
        }
        return runner.required((DbTx tx) -> {
            try {
                Connection conn = tx.connection();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT c.id, c.source_path, c.section, c.content, c.metadata_json::text AS mj, "
                                + "d.title FROM knowledge_chunks c "
                                + "LEFT JOIN knowledge_documents d ON d.id = c.document_id "
                                + "WHERE c.id = ?")) {
                    ps.setString(1, chunkId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            return Optional.<SourceView>empty();
                        }
                        String sourcePath = rs.getString("source_path");
                        String title = rs.getString("title");
                        String section = rs.getString("section");
                        return Optional.of(new SourceView(rs.getString("id"), sourcePath,
                                title == null || title.isEmpty() ? sourcePath : title,
                                section == null ? "" : section, rs.getString("content"),
                                parseObject(rs.getString("mj"))));
                    }
                }
            } catch (SQLException e) {
                throw new IllegalStateException("历史引用回读失败", e);
            }
        });
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static Map<String, Object> parseObject(String raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("来源 metadata 不是 JSON 对象", e);
        }
    }
}
