package io.dpdk.assistant.retrieval;

import io.dpdk.assistant.contracts.index.ConfigurationFingerprint;
import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import io.dpdk.assistant.contracts.model.EmbeddingInput;
import io.dpdk.assistant.contracts.model.EmbeddingPort;
import io.dpdk.assistant.contracts.model.Vector1024;
import io.dpdk.assistant.contracts.retrieval.ReleaseReader;
import io.dpdk.assistant.contracts.retrieval.ReleaseSnapshot;
import io.dpdk.assistant.contracts.retrieval.RetrievedChunk;
import io.dpdk.assistant.contracts.retrieval.SearchInput;
import io.dpdk.assistant.contracts.retrieval.SearchResult;
import io.dpdk.assistant.contracts.retrieval.SourceChunk;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ReleaseReader 的 PG 实现（契约 retrieval.ReleaseReader；行为对齐
 * services/llama_index_service.py 的 HybridDpdkRetriever 与
 * storage/retrieval_store.py 的 get_historical_chunk）。
 *
 * - pin(releaseId)：空白 releaseId 时解析 rag_index_state 的 active（无则
 *   返回空 releaseId 快照 = 无证据）；显式 releaseId 原样固定（会话绑定语义，
 *   即使后续配置失配也不回退 active）；
 * - searchPinned：release 缺失或配置指纹与当前 EmbeddingConfig 不一致时
 *   fail-closed 返回空（结构化日志 rag_release_config_mismatch，与源一致）；
 *   正常路径跑完整四腿融合/重排/MMR；
 * - 查询向量优先取 SearchInput.queryVector（1024 维冻结向量合成测试）；
 *   缺省时经 EmbeddingPort 按 Python 规则嵌入 original+rewritten；
 * - historical：engine=llama_index 或 li_ 前缀走当前 LI 表，其余走 legacy
 *   knowledge_chunks（仅历史引用回读，不参与候选召回）。
 *
 * 契约字段缺口（最小变更请求见 handoff）：SearchInput 缺改写文本与追问上下文
 * （本实现内部完成启发式改写）；RetrievedChunk/SourceChunk 缺 source_path/
 * section/title/metadata（富字段经 RetrievalTrace.nodes 交付）。
 */
public final class PgReleaseReader implements ReleaseReader {

    private static final Logger log = LoggerFactory.getLogger(PgReleaseReader.class);

    private final DataSource dataSource;
    private final RetrievalSettings settings;
    private final EmbeddingConfig embeddingConfig;
    private final ConfigurationFingerprint fingerprint;
    private final QueryRewritePort rewriter;
    private final EmbeddingPort embeddingPort;

    public PgReleaseReader(DataSource dataSource,
                           RetrievalSettings settings,
                           EmbeddingConfig embeddingConfig,
                           ConfigurationFingerprint fingerprint,
                           QueryRewritePort rewriter,
                           EmbeddingPort embeddingPort) {
        this.dataSource = dataSource;
        this.settings = settings;
        this.embeddingConfig = embeddingConfig;
        this.fingerprint = fingerprint;
        this.rewriter = rewriter;
        this.embeddingPort = embeddingPort;
    }

    /* ------------------------------------------------------------------ */
    /* pin                                                                  */
    /* ------------------------------------------------------------------ */

    @Override
    public ReleaseSnapshot pin(String releaseId) {
        if (releaseId == null || releaseId.isBlank()) {
            return new ReleaseSnapshot(activeRelease());
        }
        return new ReleaseSnapshot(releaseId);
    }

    /** active_release()：rag_index_state 无行返回空串。 */
    String activeRelease() {
        String sql = "SELECT release_id FROM rag_index_state WHERE name='active'";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement st = conn.prepareStatement(sql);
             ResultSet rs = st.executeQuery()) {
            return rs.next() ? rs.getString(1) : "";
        } catch (SQLException e) {
            throw new IllegalStateException("活动发布解析失败", e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* searchPinned                                                         */
    /* ------------------------------------------------------------------ */

    @Override
    public SearchResult searchPinned(ReleaseSnapshot snapshot, SearchInput input) {
        return searchPinned(snapshot, input, 6);
    }

    /**
     * 显式 limit 的检索入口（J1-03 测试/J1-04 装配用；契约 SearchInput 暂无
     * limit 字段，补全请求见 handoff）。Python search 默认 limit=6。
     */
    public SearchResult searchPinned(ReleaseSnapshot snapshot, SearchInput input, int limit) {
        String releaseId = snapshot == null ? "" : orEmpty(snapshot.releaseId());
        if (input == null || input.question() == null) {
            throw new IllegalArgumentException("SearchInput.question 不能为空");
        }
        if (releaseId.isBlank()) {
            // fail-closed：无可用发布（含 active 为空）时不跨版本检索。
            return new SearchResult(List.of(), new RetrievalTrace(
                    "", "no_active_release", input.question(), null, null, 0,
                    List.of(), 0, 0, 0, 0, 0, 0, 0, 0, List.of(), Map.of()));
        }
        String expected = fingerprint.pythonCompatibleConfiguration(embeddingConfig);
        String actual = releaseConfiguration(releaseId);
        if (!expected.equals(actual)) {
            log.warn(PythonJson.dumps(Map.of(
                    "event", "rag_release_config_mismatch", "release_id", releaseId)));
            return new SearchResult(List.of(), new RetrievalTrace(
                    releaseId, "config_mismatch", input.question(), null, null, 0,
                    List.of(), 0, 0, 0, 0, 0, 0, 0, 0, List.of(), Map.of()));
        }

        String query = input.question();
        String rewritten = rewriter.rewrite(query);

        double[] queryEmbedding;
        if (input.queryVector() != null) {
            queryEmbedding = VectorMath.toDoubles(input.queryVector().values());
        } else if (embeddingPort != null) {
            String embeddingQuery = embeddingQueryFor(query, rewritten);
            List<Vector1024> vectors = embeddingPort.embed(new EmbeddingInput(embeddingQuery));
            queryEmbedding = vectors.isEmpty() ? new double[0]
                    : VectorMath.toDoubles(vectors.get(0).values());
        } else {
            queryEmbedding = new double[0];
        }
        String embeddingQuery = embeddingQueryFor(query, rewritten);

        LiNodeRetrievalStore store = new LiNodeRetrievalStore(dataSource, settings, releaseId);
        RetrievalPipeline.Result result =
                new RetrievalPipeline(store, settings).retrieve(query, rewritten, queryEmbedding, limit);

        List<RetrievedChunk> chunks = result.nodes().stream()
                .map(n -> new RetrievedChunk(n.id(), n.content(), n.score()))
                .toList();
        RetrievalTrace partial = result.trace();
        RetrievalTrace trace = new RetrievalTrace(
                releaseId, null, query, rewritten, embeddingQuery,
                partial.embeddingDim(), partial.fallbacks(),
                partial.ftsRewrittenCandidates(), partial.ftsOriginalCandidates(),
                partial.pathCandidates(), partial.semanticCandidates(),
                partial.fusedCandidates(), partial.candidateLimit(),
                partial.rerankedChunks(), partial.selectedChunks(),
                partial.nodes(), partial.legRanks());
        return new SearchResult(chunks, trace);
    }

    /** embedding_query 规则：改写不同则 original + " " + rewritten。 */
    static String embeddingQueryFor(String query, String rewritten) {
        return rewritten.equals(query) ? query : query + " " + rewritten;
    }

    /* ------------------------------------------------------------------ */
    /* historical                                                           */
    /* ------------------------------------------------------------------ */

    @Override
    public Optional<SourceChunk> historical(String engine, String chunkId) {
        if (chunkId == null || chunkId.isEmpty()) {
            return Optional.empty();
        }
        if ("llama_index".equals(engine) || chunkId.startsWith("li_")) {
            Map<String, Object> chunk = new LiNodeRetrievalStore(dataSource, settings, "").getChunk(chunkId);
            if (chunk == null) {
                return Optional.empty();
            }
            return Optional.of(new SourceChunk(
                    String.valueOf(chunk.get("chunk_id")),
                    String.valueOf(chunk.get("source_path")),
                    String.valueOf(chunk.get("section")),
                    String.valueOf(chunk.get("content"))));
        }
        return Optional.ofNullable(legacyChunk(chunkId)).map(row -> new SourceChunk(
                row.id(), row.sourcePath(), row.section(), row.content()));
    }

    /** legacy 富结果（title 供上层引用展示；契约 SourceChunk 暂缺 title 字段）。 */
    public record LegacyChunk(String id, String sourcePath, String title, String section, String content) {
    }

    /** get_historical_chunk：knowledge_chunks LEFT JOIN knowledge_documents。 */
    LegacyChunk legacyChunk(String chunkId) {
        String sql = "SELECT c.id, c.source_path, c.section, c.content, d.title "
                + "FROM knowledge_chunks c "
                + "LEFT JOIN knowledge_documents d ON d.id = c.document_id "
                + "WHERE c.id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, chunkId);
            try (ResultSet rs = st.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                String sourcePath = rs.getString("source_path");
                String title = rs.getString("title");
                return new LegacyChunk(
                        rs.getString("id"),
                        sourcePath,
                        title == null || title.isEmpty() ? sourcePath : title,
                        orEmpty(rs.getString("section")),
                        rs.getString("content"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("历史引用回读失败", e);
        }
    }

    /* ------------------------------------------------------------------ */

    String releaseConfiguration(String releaseId) {
        String sql = "SELECT configuration FROM rag_releases WHERE id=?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement st = conn.prepareStatement(sql)) {
            st.setString(1, releaseId);
            try (ResultSet rs = st.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        } catch (SQLException e) {
            throw new IllegalStateException("发布配置读取失败", e);
        }
    }

    private static String orEmpty(String s) {
        return s == null ? "" : s;
    }
}
