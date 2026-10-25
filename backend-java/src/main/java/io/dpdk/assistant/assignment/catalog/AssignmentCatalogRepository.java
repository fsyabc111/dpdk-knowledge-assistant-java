package io.dpdk.assistant.assignment.catalog;

import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.DbTx;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

/**
 * 作业目录仓储：assignments / assignment_items / 作业视角的 grading_submissions
 * 统计（对齐 backend/app/services/assignment_service.py）。
 *
 * 事务形状与源一致：创建/修改/撤回各一笔事务（协议项整体替换与主行更新同事务，
 * 中途约束冲突整体回滚——重复协议靠 assignment_items UNIQUE(assignment_id,protocol)
 * 拒绝，与 Python 相同不预校验）；时间戳统一走 clock.nextSessionIso()（Python
 * now_iso 的进程内严格递增微秒语义）。
 */
@Repository
public class AssignmentCatalogRepository {

    /** user 侧 status 取值（assignments.status：active 学生可见；withdrawn 隐藏保留成绩）。 */
    public static final String STATUS_ACTIVE = "active";
    public static final String STATUS_WITHDRAWN = "withdrawn";

    private final TransactionRunner runner;
    private final ClockPort clock;

    public AssignmentCatalogRepository(TransactionRunner runner, ClockPort clock) {
        this.runner = runner;
        this.clock = clock;
    }

    @FunctionalInterface
    private interface SqlWork<T> {

        T apply(Connection connection) throws SQLException;
    }

    private <T> T inTx(SqlWork<T> work) {
        return runner.required((DbTx tx) -> {
            try {
                return work.apply(tx.connection());
            } catch (SQLException e) {
                throw new IllegalStateException("assignment storage failure", e);
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* 行类型                                                               */
    /* ------------------------------------------------------------------ */

    /** 学生逐协议统计（_my_best_sync 行）。 */
    public record ProtocolStat(int bestScore, int submissionCount) {
    }

    /** 受理快照原始行（含 LEFT JOIN 的 points，未命中为 null）。 */
    private record SnapshotRaw(String id, String title, String createdBy, String scope,
            Instant deadline, String status, Instant updatedAt, Integer points) {
    }

    /* ------------------------------------------------------------------ */
    /* 创建 / 修改 / 状态                                                   */
    /* ------------------------------------------------------------------ */

    /**
     * 创建作业；scope 服务端按角色产生（leader→all，admin→class），客户端不可指定。
     */
    public AssignmentRow createAssignment(String actorId, List<String> actorRoles, String title,
            String description, Instant deadline, List<AssignmentRow.ItemRow> items) {
        String scope = actorRoles.contains("leader") ? "all" : "class";
        String assignmentId = "asg_" + hex16();
        String now = clock.nextSessionIso();
        inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO assignments(id, title, description, created_by, scope, deadline,"
                            + " status, created_at, updated_at)"
                            + " VALUES (?,?,?,?,?,?,'active',?,?)")) {
                ps.setString(1, assignmentId);
                ps.setString(2, title);
                ps.setString(3, description);
                ps.setString(4, actorId);
                ps.setString(5, scope);
                ps.setObject(6, OffsetDateTime.ofInstant(deadline, java.time.ZoneOffset.UTC));
                ps.setObject(7, OffsetDateTime.parse(now));
                ps.setObject(8, OffsetDateTime.parse(now));
                ps.executeUpdate();
            }
            replaceItems(conn, assignmentId, items);
            return null;
        });
        return getAssignment(assignmentId).orElseThrow();
    }

    /**
     * 修改作业；不存在返回 empty；非创建者/组长抛 {@link AssignmentPermissionException}。
     * items 非 null 时整体替换（成绩动态折算，提交记录不动）。
     */
    public Optional<AssignmentRow> updateAssignment(String assignmentId, String actorId,
            List<String> actorRoles, String title, String description, Instant deadline,
            List<AssignmentRow.ItemRow> items) {
        inTx(conn -> {
            String createdBy = selectCreatedBy(conn, assignmentId);
            if (createdBy == null) {
                return null;
            }
            checkManagePermission(createdBy, actorId, actorRoles);
            List<String> sets = new ArrayList<>();
            List<Object> params = new ArrayList<>();
            if (title != null) {
                sets.add("title = ?");
                params.add(title);
            }
            if (description != null) {
                sets.add("description = ?");
                params.add(description);
            }
            if (deadline != null) {
                sets.add("deadline = ?");
                params.add(OffsetDateTime.ofInstant(deadline, java.time.ZoneOffset.UTC));
            }
            sets.add("updated_at = ?");
            params.add(OffsetDateTime.parse(clock.nextSessionIso()));
            params.add(assignmentId);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE assignments SET " + String.join(", ", sets) + " WHERE id = ?")) {
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                ps.executeUpdate();
            }
            if (items != null) {
                replaceItems(conn, assignmentId, items);
            }
            return null;
        });
        return getAssignment(assignmentId);
    }

    /** 撤回/恢复（status ∈ {withdrawn, active}）；不存在 empty、越权抛权限异常。 */
    public Optional<AssignmentRow> setStatus(String assignmentId, String status, String actorId,
            List<String> actorRoles) {
        inTx(conn -> {
            String createdBy = selectCreatedBy(conn, assignmentId);
            if (createdBy == null) {
                return null;
            }
            checkManagePermission(createdBy, actorId, actorRoles);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE assignments SET status = ?, updated_at = ? WHERE id = ?")) {
                ps.setString(1, status);
                ps.setObject(2, OffsetDateTime.parse(clock.nextSessionIso()));
                ps.setString(3, assignmentId);
                ps.executeUpdate();
            }
            return null;
        });
        return getAssignment(assignmentId);
    }

    /** 协议项整体替换：DELETE 全部再 INSERT（重复协议由 UNIQUE 约束拒绝，事务回滚）。 */
    private void replaceItems(Connection conn, String assignmentId,
            List<AssignmentRow.ItemRow> items) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM assignment_items WHERE assignment_id = ?")) {
            ps.setString(1, assignmentId);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO assignment_items(id, assignment_id, protocol, points)"
                        + " VALUES (?,?,?,?)")) {
            for (AssignmentRow.ItemRow item : items) {
                ps.setString(1, "item_" + hex16());
                ps.setString(2, assignmentId);
                ps.setString(3, item.protocol());
                ps.setInt(4, item.points());
                ps.executeUpdate();
            }
        }
    }

    private static String selectCreatedBy(Connection conn, String assignmentId)
            throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT created_by FROM assignments WHERE id = ?")) {
            ps.setString(1, assignmentId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("created_by") : null;
            }
        }
    }

    /** 作业修改权限 = 创建者本人或 leader（_check_manage_permission）。 */
    private static void checkManagePermission(String createdBy, String actorId,
            List<String> actorRoles) {
        if (!actorId.equals(createdBy) && !actorRoles.contains("leader")) {
            throw new AssignmentPermissionException("只有作业创建者或组长可以修改");
        }
    }

    /* ------------------------------------------------------------------ */
    /* 查询                                                                 */
    /* ------------------------------------------------------------------ */

    /** 单作业完整定义（含 items，protocol ASC）。 */
    public Optional<AssignmentRow> getAssignment(String assignmentId) {
        return inTx(conn -> {
            AssignmentRow row = selectAssignment(conn, assignmentId);
            if (row == null) {
                return Optional.<AssignmentRow>empty();
            }
            List<AssignmentRow.ItemRow> items =
                    loadItems(conn, List.of(assignmentId)).getOrDefault(assignmentId, List.of());
            return Optional.of(withItems(row, items));
        });
    }

    /** manager 列表：admin→自己创建的；leader/operator→全部；带提交统计。 */
    public List<ManagerListRow> listForManager(String actorId, List<String> actorRoles) {
        return inTx(conn -> {
            List<AssignmentRow> rows;
            if (!actorRoles.contains("leader") && !actorRoles.contains("operator")) {
                rows = selectAssignments(conn, "created_by = ?", List.of(actorId));
            } else {
                rows = selectAssignments(conn, null, List.of());
            }
            List<String> ids = rows.stream().map(AssignmentRow::id).toList();
            Map<String, List<AssignmentRow.ItemRow>> itemMap = loadItems(conn, ids);
            Map<String, int[]> stats = submissionStats(conn, ids);
            List<ManagerListRow> out = new ArrayList<>();
            for (AssignmentRow row : rows) {
                AssignmentRow full = withItems(row, itemMap.getOrDefault(row.id(), List.of()));
                int[] stat = stats.getOrDefault(row.id(), new int[] {0, 0});
                out.add(new ManagerListRow(full, stat[0], stat[1]));
            }
            return out;
        });
    }

    /** manager 列表行：定义 + (提交总数, 去重学生数)。 */
    public record ManagerListRow(AssignmentRow assignment, int submissionCount,
            int studentCount) {
    }

    /** 学生可见作业：active 且（scope='all' 或创建者是自己的老师）。 */
    public List<StudentAssignmentView> listVisibleForStudent(String studentId,
            String studentCreatedBy) {
        return inTx(conn -> {
            List<AssignmentRow> rows;
            if (studentCreatedBy != null) {
                rows = selectAssignments(conn,
                        "status = 'active' AND (scope = 'all' OR created_by = ?)",
                        List.of(studentCreatedBy));
            } else {
                rows = selectAssignments(conn, "status = 'active' AND scope = 'all'", List.of());
            }
            List<String> ids = rows.stream().map(AssignmentRow::id).toList();
            Map<String, List<AssignmentRow.ItemRow>> itemMap = loadItems(conn, ids);
            Map<String, Map<String, ProtocolStat>> mine = myBest(conn, ids, studentId);
            List<StudentAssignmentView> out = new ArrayList<>();
            for (AssignmentRow row : rows) {
                AssignmentRow full = withItems(row, itemMap.getOrDefault(row.id(), List.of()));
                out.add(StudentAssignmentView.assemble(full,
                        mine.getOrDefault(row.id(), Map.of())));
            }
            return out;
        });
    }

    /** 详情可见性判定（提交与详情共用）：不可见返回 empty。 */
    public Optional<StudentAssignmentView> getVisibleAssignment(String assignmentId,
            String studentId, String studentCreatedBy) {
        AssignmentRow assignment = getAssignment(assignmentId).orElse(null);
        if (assignment == null || !STATUS_ACTIVE.equals(assignment.status())) {
            return Optional.empty();
        }
        if (!"all".equals(assignment.scope())
                && !assignment.createdBy().equals(studentCreatedBy)) {
            return Optional.empty();
        }
        Map<String, ProtocolStat> mine =
                inTx(conn -> myBest(conn, List.of(assignmentId), studentId))
                        .getOrDefault(assignmentId, Map.of());
        return Optional.of(StudentAssignmentView.assemble(assignment, mine));
    }

    /** 受理快照（单条 SQL，避免 PATCH 夹在两次 SELECT 之间）。 */
    public Optional<SubmissionSnapshot> submissionSnapshot(String assignmentId,
            String studentCreatedBy, String protocol) {
        SnapshotRaw raw = inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT a.id, a.title, a.created_by, a.scope, a.deadline, a.status,"
                            + " a.updated_at, i.points FROM assignments a"
                            + " LEFT JOIN assignment_items i"
                            + " ON i.assignment_id = a.id AND i.protocol = ?"
                            + " WHERE a.id = ?")) {
                ps.setString(1, protocol);
                ps.setString(2, assignmentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    Integer points = rs.getObject("points") == null
                            ? null : rs.getInt("points");
                    return new SnapshotRaw(rs.getString("id"), rs.getString("title"),
                            rs.getString("created_by"), rs.getString("scope"),
                            rs.getObject("deadline", OffsetDateTime.class).toInstant(),
                            rs.getString("status"),
                            rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                            points);
                }
            }
        });
        if (raw == null || !STATUS_ACTIVE.equals(raw.status())) {
            return Optional.empty();
        }
        if (!"all".equals(raw.scope) && !raw.createdBy().equals(studentCreatedBy)) {
            return Optional.empty();
        }
        return Optional.of(new SubmissionSnapshot(raw.id(), raw.title(), raw.createdBy(),
                raw.scope(), raw.deadline(), raw.updatedAt(), raw.points()));
    }

    /** 该学生此作业此协议历史最高分（无提交 empty）。 */
    public Optional<Integer> bestScore(String assignmentId, String userId, String protocol) {
        return inTx(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT MAX(score) AS best FROM grading_submissions"
                            + " WHERE assignment_id = ? AND user_id = ? AND protocol = ?")) {
                ps.setString(1, assignmentId);
                ps.setString(2, userId);
                ps.setString(3, protocol);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int best = rs.getInt("best");
                        return rs.wasNull() ? Optional.<Integer>empty() : Optional.of(best);
                    }
                    return Optional.empty();
                }
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* 内部 SQL helpers                                                     */
    /* ------------------------------------------------------------------ */

    private static AssignmentRow withItems(AssignmentRow row, List<AssignmentRow.ItemRow> items) {
        return new AssignmentRow(row.id(), row.title(), row.description(), row.createdBy(),
                row.scope(), row.deadline(), row.status(), row.createdAt(), row.updatedAt(),
                items);
    }

    private static AssignmentRow selectAssignment(Connection conn, String id) throws SQLException {
        List<AssignmentRow> rows = selectAssignments(conn, "id = ?", List.of(id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** SELECT * FROM assignments [WHERE ...] ORDER BY created_at DESC, id DESC。 */
    private static List<AssignmentRow> selectAssignments(Connection conn, String where,
            List<Object> params) throws SQLException {
        String sql = "SELECT * FROM assignments" + (where == null ? "" : " WHERE " + where)
                + " ORDER BY created_at DESC, id DESC";
        List<AssignmentRow> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new AssignmentRow(rs.getString("id"), rs.getString("title"),
                            rs.getString("description"), rs.getString("created_by"),
                            rs.getString("scope"),
                            rs.getObject("deadline", OffsetDateTime.class).toInstant(),
                            rs.getString("status"),
                            rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                            rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                            List.of()));
                }
            }
        }
        return out;
    }

    /** items 按 assignment_id 批量加载（ORDER BY protocol）。 */
    private static Map<String, List<AssignmentRow.ItemRow>> loadItems(Connection conn,
            List<String> assignmentIds) throws SQLException {
        Map<String, List<AssignmentRow.ItemRow>> out = new LinkedHashMap<>();
        if (assignmentIds.isEmpty()) {
            return out;
        }
        String marks = repeatMarks(assignmentIds.size());
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT assignment_id, protocol, points FROM assignment_items"
                        + " WHERE assignment_id IN (" + marks + ") ORDER BY protocol")) {
            for (int i = 0; i < assignmentIds.size(); i++) {
                ps.setString(i + 1, assignmentIds.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getString("assignment_id"),
                            k -> new ArrayList<>()).add(new AssignmentRow.ItemRow(
                            rs.getString("protocol"), rs.getInt("points")));
                }
            }
        }
        return out;
    }

    /** (提交总数, 去重学生数) per assignment（grading_submissions.assignment_id 等值）。 */
    private static Map<String, int[]> submissionStats(Connection conn, List<String> ids)
            throws SQLException {
        Map<String, int[]> out = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return out;
        }
        String marks = repeatMarks(ids.size());
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT assignment_id, COUNT(*) AS cnt, COUNT(DISTINCT user_id) AS students"
                        + " FROM grading_submissions WHERE assignment_id IN (" + marks + ")"
                        + " GROUP BY assignment_id")) {
            for (int i = 0; i < ids.size(); i++) {
                ps.setString(i + 1, ids.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.put(rs.getString("assignment_id"),
                            new int[] {rs.getInt("cnt"), rs.getInt("students")});
                }
            }
        }
        return out;
    }

    /** assignment_id → protocol → {best, cnt}（当前学生）。 */
    private static Map<String, Map<String, ProtocolStat>> myBest(Connection conn,
            List<String> assignmentIds, String studentId) throws SQLException {
        Map<String, Map<String, ProtocolStat>> out = new LinkedHashMap<>();
        if (assignmentIds.isEmpty()) {
            return out;
        }
        String marks = repeatMarks(assignmentIds.size());
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT assignment_id, protocol, MAX(score) AS best_score, COUNT(*) AS cnt"
                        + " FROM grading_submissions WHERE assignment_id IN (" + marks + ")"
                        + " AND user_id = ? GROUP BY assignment_id, protocol")) {
            int idx = 0;
            for (String id : assignmentIds) {
                ps.setString(++idx, id);
            }
            ps.setString(++idx, studentId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.computeIfAbsent(rs.getString("assignment_id"),
                            k -> new LinkedHashMap<>()).put(rs.getString("protocol"),
                            new ProtocolStat(rs.getInt("best_score"), rs.getInt("cnt")));
                }
            }
        }
        return out;
    }

    private static String repeatMarks(int count) {
        StringBuilder sb = new StringBuilder(count * 2);
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("?");
        }
        return sb.toString();
    }

    /** Python uuid4().hex[:16] 同形：16 位小写十六进制（UUID.toString 已是小写 hex）。 */
    private static String hex16() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
