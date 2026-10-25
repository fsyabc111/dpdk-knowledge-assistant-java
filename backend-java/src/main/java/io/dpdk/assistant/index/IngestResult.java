package io.dpdk.assistant.index;

import java.util.LinkedHashMap;
import java.util.Map;

/** 摄取统计（deleted 是新版本移除成员数，物理旧节点保留）。 */
public record IngestResult(String releaseId, int nodes, int unchanged, int insertedOrUpdated,
                           int deleted, int deduplicated, String table) {
    public Map<String, Object> asMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("release_id", releaseId);
        out.put("nodes", nodes);
        out.put("unchanged", unchanged);
        out.put("inserted_or_updated", insertedOrUpdated);
        out.put("deleted", deleted);
        out.put("deduplicated", deduplicated);
        out.put("table", table);
        return out;
    }
}
