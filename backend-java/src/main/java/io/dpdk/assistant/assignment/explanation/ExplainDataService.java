package io.dpdk.assistant.assignment.explanation;

import io.dpdk.assistant.admin.AdminUserRepository;
import io.dpdk.assistant.assignment.catalog.AssignmentCatalogService;
import io.dpdk.assistant.assignment.catalog.AssignmentRow;
import io.dpdk.assistant.assignment.submission.SubmissionRecordReader;
import io.dpdk.assistant.assignment.submission.SubmissionRecordReader.SubmissionRecord;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import io.dpdk.assistant.task.TaskExecutor.TaskExecutionContext;
import java.util.Map;
import java.util.logging.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 讲解授权与可信上下文读取。截止日期不参与讲解可见性判断，不写成绩。 */
@Service
public class ExplainDataService {

    private static final Logger LOG = Logger.getLogger(ExplainDataService.class.getName());
    private final AdminUserRepository users;
    private final AssignmentCatalogService assignments;
    private final SubmissionRecordReader submissions;
    private final ExplainSessionRepository sessions;

    public ExplainDataService(AdminUserRepository users, AssignmentCatalogService assignments,
            SubmissionRecordReader submissions, ExplainSessionRepository sessions) {
        this.users = users;
        this.assignments = assignments;
        this.submissions = submissions;
        this.sessions = sessions;
    }

    public record Prepared(ExplainIdentity identity, String question, String title,
            Map<String, Object> context) {
    }

    public Prepared prepare(String assignmentId, String submissionId, Principal principal) {
        String createdBy = users.getUser(principal.subject())
                .map(AdminUserRepository.UserRow::createdBy).orElse(null);
        var assignment = assignments.getVisibleAssignment(assignmentId, principal.subject(),
                createdBy).orElseThrow(ExplainDataService::notFound);
        SubmissionRecord row = submissions.get(submissionId).orElseThrow(ExplainDataService::notFound);
        if (!principal.subject().equals(row.userId()) || !assignmentId.equals(row.assignmentId())) {
            throw notFound();
        }
        ExplainIdentity identity = ExplainIdentity.forSubmission(submissionId);
        return new Prepared(identity, "请讲解提交 " + submissionId + " 的评测结果。",
                "作业讲解·" + assignment.title() + "·" + row.protocol(),
                ExplainContext.from(row, assignment.title()));
    }

    public void ensureSession(Prepared prepared, String userId) {
        sessions.ensure(prepared.identity().sessionId(), userId, prepared.title());
    }

    /** 每次执行（包括恢复）从成绩库重建，不信任客户端上下文或会话前缀。 */
    public Map<String, Object> resolve(TaskExecutionContext execution) {
        String submissionId = ExplainIdentity.submissionId(execution.sessionId(),
                execution.requestId(), execution.purpose());
        if (submissionId == null) {
            return null;
        }
        try {
            SubmissionRecord row = submissions.get(submissionId).orElse(null);
            if (row == null || !execution.subject().equals(row.userId())) {
                return null;
            }
            String title = assignments.getAssignment(row.assignmentId() == null ? ""
                    : row.assignmentId()).map(AssignmentRow::title).orElse("");
            return ExplainContext.from(row, title);
        } catch (RuntimeException error) {
            LOG.warning("[explain_context_rebuild_failed] task_id=" + execution.taskId()
                    + " error_type=" + error.getClass().getSimpleName());
            return null;
        }
    }

    private static ApiDetailException notFound() {
        return new ApiDetailException(HttpStatus.NOT_FOUND, "not found");
    }
}
