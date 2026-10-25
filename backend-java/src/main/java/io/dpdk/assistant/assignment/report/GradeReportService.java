package io.dpdk.assistant.assignment.report;

import io.dpdk.assistant.admin.AdminClassScopePort;
import io.dpdk.assistant.assignment.catalog.AssignmentCatalogPort;
import io.dpdk.assistant.assignment.catalog.AssignmentRow;
import io.dpdk.assistant.contracts.identity.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * 成绩汇总与 CSV 导出服务（对齐 assignments.py 的 `_scoped_grade_rows` 与
 * export 路由）。
 *
 * 树隔离 `_scope_user_ids`：leader/operator → 不限；静态 token 主体 → 不限
 * （经 AdminClassScopePort.scopeCreator 判定）；老师（admin）→ 仅自己班学生。
 * 账号删除后成绩保留、学号保留、班级归「未分配」。
 *
 * 汇总口径：按当前作业项动态折算（受理口径的边界见 AssignmentCatalogPort）。
 */
@Service
public class GradeReportService {

    static final String UNASSIGNED_CLASS = "未分配";

    private final AssignmentCatalogPort catalog;
    private final GradeReportRepository repo;
    private final AdminClassScopePort scopePort;

    public GradeReportService(AssignmentCatalogPort catalog, GradeReportRepository repo,
            AdminClassScopePort scopePort) {
        this.catalog = catalog;
        this.repo = repo;
        this.scopePort = scopePort;
    }

    /** 作业定义 + 树隔离后的成绩行（含班级归属）；作业不存在返回 null。 */
    public ScopedGrades scopedGrades(String assignmentId, Principal principal) {
        AssignmentRow assignment = catalog.getAssignment(assignmentId).orElse(null);
        if (assignment == null) {
            return null;
        }
        List<GradeRow> rows = repo.gradesForAssignment(assignment);
        List<String> scope = scopeUserIds(principal);
        if (scope != null) {
            List<String> allowed = List.copyOf(scope);
            rows = rows.stream().filter(row -> allowed.contains(row.userId())).toList();
        }
        Map<String, String> teachers = repo.classTeachers(
                rows.stream().map(GradeRow::userId).toList());
        List<GradeRow> scoped = new ArrayList<>();
        for (GradeRow row : rows) {
            scoped.add(new GradeRow(row.userId(), teachers.get(row.userId()), row.scores(),
                    row.totalEarned(), row.submissionCount(), row.lastSubmittedAt()));
        }
        return new ScopedGrades(assignment, scoped, scope != null);
    }

    /** 树隔离 + 分组结果（scopeIsTeacher=true 表示老师单班视角）。 */
    public record ScopedGrades(AssignmentRow assignment, List<GradeRow> rows,
            boolean scopeIsTeacher) {
    }

    /** `_scope_user_ids`：null=不限。 */
    private List<String> scopeUserIds(Principal principal) {
        if (principal.roles().contains("leader") || principal.roles().contains("operator")) {
            return null;
        }
        if (scopePort.scopeCreator(principal).isEmpty()) {
            // 静态应急 token 主体不受树隔离。
            return null;
        }
        return scopePort.studentIds(principal.subject());
    }

    /* ------------------------------------------------------------------ */
    /* CSV                                                                 */
    /* ------------------------------------------------------------------ */

    /** 安全文件名（_csv_safe：非 [A-Za-z0-9_.-] 连续段替换为 _）。 */
    static String csvSafe(String text) {
        return text.replaceAll("[^A-Za-z0-9_.-]+", "_");
    }

    /** CSV 字节输出（UTF-8 BOM + csv.writer CRLF/引号规则）；scopeIsTeacher 决定分组。 */
    public CsvResult exportCsv(ScopedGrades grades, Principal principal) {
        AssignmentRow assignment = grades.assignment();
        List<String> header = new ArrayList<>();
        header.add("班级(老师学号)");
        header.add("学生学号");
        for (AssignmentRow.ItemRow item : assignment.items()) {
            header.add(item.protocol() + "(满分" + item.points() + ")");
        }
        header.add("总分");
        header.add("提交次数");
        header.add("最近提交时间");

        PythonCsv csv = new PythonCsv();
        csv.writeRow(header);
        String filename;
        if (grades.scopeIsTeacher()) {
            // 老师视角：只有自己班，单组输出，文件名含老师学号。
            for (GradeRow row : grades.rows()) {
                csv.writeRow(gradeCells(row, assignment));
            }
            filename = "assignment_" + assignment.id() + "_"
                    + csvSafe(principal.subject()) + ".csv";
        } else {
            // 组长/operator/静态 token 视角：按班级分组、组间空行、未分配排最后。
            Map<String, List<GradeRow>> groups = new LinkedHashMap<>();
            for (GradeRow row : grades.rows()) {
                groups.computeIfAbsent(row.classTeacher() == null
                        ? UNASSIGNED_CLASS : row.classTeacher(), k -> new ArrayList<>())
                        .add(row);
            }
            List<String> ordered = new ArrayList<>(
                    new TreeMap<>(groups).keySet().stream()
                            .filter(name -> !UNASSIGNED_CLASS.equals(name)).toList());
            if (groups.containsKey(UNASSIGNED_CLASS)) {
                ordered.add(UNASSIGNED_CLASS);
            }
            for (int i = 0; i < ordered.size(); i++) {
                if (i > 0) {
                    csv.writeBlank();
                }
                for (GradeRow row : groups.get(ordered.get(i))) {
                    csv.writeRow(gradeCells(row, assignment));
                }
            }
            filename = "assignment_" + assignment.id() + "_grades.csv";
        }
        return new CsvResult(csv.toUtf8BytesWithBom(), filename);
    }

    /** CSV 结果字节与文件名。 */
    public record CsvResult(byte[] bytes, String filename) {
    }

    private static List<String> gradeCells(GradeRow row, AssignmentRow assignment) {
        List<Object> cells = new ArrayList<>();
        cells.add(row.classTeacher() == null ? UNASSIGNED_CLASS : row.classTeacher());
        cells.add(row.userId());
        for (AssignmentRow.ItemRow item : assignment.items()) {
            Double score = row.scores().get(item.protocol());
            cells.add(score == null ? "" : score);
        }
        cells.add(row.totalEarned());
        cells.add(row.submissionCount());
        cells.add(row.lastSubmittedAt() == null ? "" : row.lastSubmittedAt());
        return PythonCsv.row(cells.toArray());
    }
}
