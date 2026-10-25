package io.dpdk.assistant.assignment.explanation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.assignment.submission.SubmissionRecordReader.SubmissionRecord;
import io.dpdk.assistant.session.SessionTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 普通入口与重启恢复共用的可信成绩载荷；不包含源码或 code_hash。 */
public final class ExplainContext {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExplainContext() {
    }

    public static Map<String, Object> from(SubmissionRecord row, String assignmentTitle) {
        JsonNode payload = row.resultsJson();
        JsonNode results = payload != null && payload.isObject() ? payload.get("results") : null;
        JsonNode origin = payload != null && payload.isObject() ? payload.get("source_origin") : null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("submission_id", row.id());
        out.put("assignment_id", row.assignmentId());
        out.put("assignment_title", assignmentTitle == null ? "" : assignmentTitle);
        out.put("protocol", row.protocol());
        out.put("score", row.score());
        out.put("passed", row.passed());
        out.put("total", row.total());
        out.put("results", truthy(results) ? MAPPER.convertValue(results, Object.class) : List.of());
        out.put("source_origin", origin == null || origin.isNull() ? null
                : MAPPER.convertValue(origin, Object.class));
        out.put("submitted_at", row.createdAt() == null ? null
                : SessionTime.pythonStr(SessionTime.utc(row.createdAt())));
        return out;
    }

    /** Python `value or []`：空串、false、零、空容器与 null 归为空列表。 */
    private static boolean truthy(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return false;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return value.decimalValue().signum() != 0;
        }
        if (value.isTextual()) {
            return !value.textValue().isEmpty();
        }
        return !value.isContainerNode() || !value.isEmpty();
    }
}
