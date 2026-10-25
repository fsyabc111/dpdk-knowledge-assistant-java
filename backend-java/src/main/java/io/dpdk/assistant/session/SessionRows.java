package io.dpdk.assistant.session;

import java.time.Instant;
import java.util.Map;

/**
 * 会话三表的行类型（assistant_sessions / assistant_messages / assistant_citations）。
 *
 * 语义对齐 session_service.py 的行读取：
 * - SessionRow.userId 以 NULL→"anonymous" 归一（COALESCE 口径）；
 * - MessageRow.metadata 为已解析对象（jsonb），metadataJson 保留原始文本供调试；
 * - CitationRow.engine 以 NULL→"legacy" 归一（get_citations 口径）。
 */
public final class SessionRows {

    private SessionRows() {
    }

    public record SessionRow(String id, String userId, String title, Instant createdAt,
                             Instant updatedAt) {

        static SessionRow of(String id, String rawUserId, String rawTitle, Instant createdAt,
                             Instant updatedAt) {
            return new SessionRow(id, rawUserId == null || rawUserId.isEmpty() ? "anonymous" : rawUserId,
                    rawTitle == null ? "" : rawTitle, createdAt, updatedAt);
        }
    }

    public record MessageRow(String id, String role, String content, Map<String, Object> metadata,
                             Instant createdAt) {
    }

    public record CitationRow(String messageId, String chunkId, String engine, double score, int rank) {

        static CitationRow of(String messageId, String chunkId, String engine, double score, int rank) {
            return new CitationRow(messageId, chunkId, engine == null || engine.isEmpty()
                    ? "legacy" : engine, score, rank);
        }
    }
}
