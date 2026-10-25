package io.dpdk.assistant.assignment.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 作业 JSON 装配（schemas.AssignmentInfo / StudentAssignment 字段序）。
 * catalog 与 report 两包共用（成绩响应内嵌 AssignmentInfo）。
 */
public final class AssignmentJson {

    private AssignmentJson() {
    }

    /** AssignmentInfo：id, title, description, created_by, scope, deadline, status,
     * created_at, updated_at, items[{protocol, points}]。 */
    public static Map<String, Object> assignmentInfo(AssignmentRow a) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", a.id());
        out.put("title", a.title());
        out.put("description", a.description());
        out.put("created_by", a.createdBy());
        out.put("scope", a.scope());
        out.put("deadline", PythonDateTime.pythonStr(a.deadline()));
        out.put("status", a.status());
        out.put("created_at", PythonDateTime.pythonStr(a.createdAt()));
        out.put("updated_at", PythonDateTime.pythonStr(a.updatedAt()));
        List<Map<String, Object>> items = new ArrayList<>();
        for (AssignmentRow.ItemRow item : a.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("protocol", item.protocol());
            row.put("points", item.points());
            items.add(row);
        }
        out.put("items", items);
        return out;
    }

    /** StudentAssignment：不含 scope/created_at/updated_at；items 带逐协议成绩。 */
    public static Map<String, Object> studentAssignment(StudentAssignmentView v) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", v.id());
        out.put("title", v.title());
        out.put("description", v.description());
        out.put("created_by", v.createdBy());
        out.put("deadline", PythonDateTime.pythonStr(v.deadline()));
        out.put("status", v.status());
        List<Map<String, Object>> items = new ArrayList<>();
        for (StudentAssignmentView.StudentItemGrade item : v.items()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("protocol", item.protocol());
            row.put("points", item.points());
            row.put("best_score", item.bestScore());
            row.put("earned", item.earned());
            row.put("submission_count", item.submissionCount());
            items.add(row);
        }
        out.put("items", items);
        out.put("total_points", v.totalPoints());
        out.put("total_earned", v.totalEarned());
        out.put("submission_count", v.submissionCount());
        return out;
    }
}
