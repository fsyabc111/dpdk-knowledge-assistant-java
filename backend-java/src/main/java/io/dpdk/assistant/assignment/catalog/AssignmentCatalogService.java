package io.dpdk.assistant.assignment.catalog;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 作业目录服务：仓储编排 + {@link AssignmentCatalogPort} 实现（对齐
 * AssignmentService 的公开方法子集；scope 由服务端按角色产生，客户端不可指定）。
 */
@Service
public class AssignmentCatalogService implements AssignmentCatalogPort {

    private final AssignmentCatalogRepository repo;

    public AssignmentCatalogService(AssignmentCatalogRepository repo) {
        this.repo = repo;
    }

    public AssignmentRow createAssignment(String actorId, List<String> actorRoles, String title,
            String description, Instant deadline, List<AssignmentRow.ItemRow> items) {
        return repo.createAssignment(actorId, actorRoles, title, description, deadline, items);
    }

    public Optional<AssignmentRow> updateAssignment(String assignmentId, String actorId,
            List<String> actorRoles, String title, String description, Instant deadline,
            List<AssignmentRow.ItemRow> items) {
        return repo.updateAssignment(assignmentId, actorId, actorRoles, title, description,
                deadline, items);
    }

    public Optional<AssignmentRow> setStatus(String assignmentId, String status, String actorId,
            List<String> actorRoles) {
        return repo.setStatus(assignmentId, status, actorId, actorRoles);
    }

    public List<AssignmentCatalogRepository.ManagerListRow> listForManager(String actorId,
            List<String> actorRoles) {
        return repo.listForManager(actorId, actorRoles);
    }

    public List<StudentAssignmentView> listVisibleForStudent(String studentId,
            String studentCreatedBy) {
        return repo.listVisibleForStudent(studentId, studentCreatedBy);
    }

    public Optional<StudentAssignmentView> getVisibleAssignment(String assignmentId,
            String studentId, String studentCreatedBy) {
        return repo.getVisibleAssignment(assignmentId, studentId, studentCreatedBy);
    }

    /* ---------------- AssignmentCatalogPort（J2-04 消费） ---------------- */

    @Override
    public Optional<SubmissionSnapshot> submissionSnapshot(String assignmentId,
            String studentCreatedBy, String protocol) {
        return repo.submissionSnapshot(assignmentId, studentCreatedBy, protocol);
    }

    @Override
    public Optional<AssignmentRow> getAssignment(String assignmentId) {
        return repo.getAssignment(assignmentId);
    }

    @Override
    public Optional<Integer> bestScore(String assignmentId, String userId, String protocol) {
        return repo.bestScore(assignmentId, userId, protocol);
    }

    @Override
    public boolean isPastDeadline(SubmissionSnapshot snapshot, Instant now) {
        return now.isAfter(snapshot.deadline());
    }
}
