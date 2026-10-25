package io.dpdk.assistant.assignment.explanation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dpdk.assistant.assignment.catalog.AssignmentCatalogRepository;
import io.dpdk.assistant.assignment.catalog.AssignmentCatalogService;
import io.dpdk.assistant.assignment.catalog.AssignmentRow;
import io.dpdk.assistant.assignment.submission.SubmissionGradeWriter;
import io.dpdk.assistant.assignment.submission.SubmissionGradeWriter.RecordInput;
import io.dpdk.assistant.assignment.submission.SubmissionRecordReader;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import io.dpdk.assistant.task.TaskExecutor.TaskExecutionContext;
import io.dpdk.assistant.task.TaskTestHarness;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;

/** 真实 PG/作业/成绩仓储授权与恢复载荷；完整任务闭环另测。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class ExplainDataIntegrationTest {

    private TaskTestHarness h;
    private AssignmentCatalogService catalog;
    private SubmissionGradeWriter writer;
    private ExplainDataService data;
    private Principal owner;

    @BeforeAll
    void setup() throws Exception {
        h = TaskTestHarness.create("explaindata");
        h.seedUser("U20260000101", "[\"leader\"]", 1000);
        h.seedUser("U20260000102", "[\"viewer\"]", 1000);
        h.seedUser("U20260000103", "[\"viewer\"]", 1000);
        catalog = new AssignmentCatalogService(new AssignmentCatalogRepository(h.runner, h.clock));
        writer = new SubmissionGradeWriter(h.runner, h.guard, h.clock);
        data = new ExplainDataService(h.adminUsers(), catalog, new SubmissionRecordReader(h.runner),
                new ExplainSessionRepository(h.runner, h.clock));
        owner = new Principal("U20260000102", List.of("viewer"), 1000, false);
    }

    @AfterAll
    void cleanup() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    private AssignmentRow assignment() {
        return catalog.createAssignment("U20260000101", List.of("leader"), "讲解作业", "",
                Instant.parse("2020-01-01T00:00:00Z"), List.of(new AssignmentRow.ItemRow("dns", 20)));
    }

    private String grade(AssignmentRow assignment, String student) {
        return writer.recordSubmission(new RecordInput(student, "dns", "source-hash", 80, 4, 5,
                "{\"source_origin\":\"uploaded\",\"results\":[{\"case\":\"fail\",\"result\":\"FAIL\"}]}",
                assignment.id()));
    }

    @Test
    void deadlineDoesNotBlockAndPrebuildUsesExactIdentityAndRetainsTitle() throws Exception {
        AssignmentRow assignment = assignment();
        String submission = grade(assignment, owner.subject());
        var prepared = data.prepare(assignment.id(), submission, owner);
        assertThat(prepared.context()).containsEntry("score", 80).containsEntry("assignment_title", "讲解作业");
        data.ensureSession(prepared, owner.subject());
        data.ensureSession(prepared, owner.subject());
        assertThat(h.countRows("assistant_sessions", "id=?", prepared.identity().sessionId())).isEqualTo(1);
        assertThat(h.columnValue("assistant_sessions", "user_id", "id=?", prepared.identity().sessionId()))
                .isEqualTo(owner.subject());
        assertThat(h.sessions.ensureOwned(java.util.Optional.of(prepared.identity().sessionId()), "x",
                owner.subject()).value()).isEqualTo(prepared.identity().sessionId());
    }

    @Test
    void missingForeignMismatchAndInvisibleAreUniform404() {
        AssignmentRow a = assignment();
        AssignmentRow b = assignment();
        String grade = grade(a, owner.subject());
        String foreign = grade(a, "U20260000103");
        assertNotFound(() -> data.prepare(a.id(), "missing", owner));
        assertNotFound(() -> data.prepare(a.id(), foreign, owner));
        assertNotFound(() -> data.prepare(b.id(), grade, owner));
        catalog.setStatus(a.id(), "withdrawn", "U20260000101", List.of("leader"));
        assertNotFound(() -> data.prepare(a.id(), grade, owner));
    }

    @Test
    void recoveryReadsCurrentTrustedRecordAndRejectsForgeryAndForeignOwner() {
        AssignmentRow a = assignment();
        String grade = grade(a, owner.subject());
        ExplainIdentity identity = ExplainIdentity.forSubmission(grade);
        TaskExecutionContext good = context(identity, owner.subject(), "explain");
        assertThat(data.resolve(good)).containsEntry("submission_id", grade).containsEntry("score", 80);
        assertThat(data.resolve(context(identity, "U20260000103", "explain"))).isNull();
        assertThat(data.resolve(context(identity, owner.subject(), "chat"))).isNull();
        ExplainIdentity forged = new ExplainIdentity(identity.sessionId(), "client-random");
        assertThat(data.resolve(context(forged, owner.subject(), "explain"))).isNull();
        catalog.updateAssignment(a.id(), "U20260000101", List.of("leader"), "改名后", "",
                a.deadline(), a.items());
        assertThat(data.resolve(good)).containsEntry("assignment_title", "改名后");
    }

    private static TaskExecutionContext context(ExplainIdentity identity, String subject, String purpose) {
        return new TaskExecutionContext("task-trusted", identity.requestId(), identity.sessionId(), subject,
                List.of("viewer"), purpose, "讲解", "客户端伪造上下文不能成为成绩来源", null, null,
                null, null);
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiDetailException.class,
                error -> {
                    assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(error.detail()).isEqualTo("not found");
                });
    }
}
