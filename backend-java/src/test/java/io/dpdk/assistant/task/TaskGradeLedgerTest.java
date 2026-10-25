package io.dpdk.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dpdk.assistant.contracts.grading.TaskGradeInput;
import io.dpdk.assistant.contracts.grading.TaskGradeResult;
import io.dpdk.assistant.contracts.task.AdmissionInput;
import io.dpdk.assistant.contracts.task.RequestIdentity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 评测账本接线（contracts.grading.TaskGradeParticipant）：job 登记+配额同事务、
 * 成绩+submission_done 同事务、task_job_id 防重复、legacy_unlinked/cancelled 分支、
 * 账本缺行拒绝。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class TaskGradeLedgerTest {

    private static TaskTestHarness h;
    private static String subject;
    private static TaskGradeLedger ledger;

    @BeforeAll
    static void setup() throws Exception {
        h = TaskTestHarness.create("grade");
        subject = "U20260000041";
        h.seedUser(subject, "[\"viewer\"]", 10000);
        ledger = new TaskGradeLedger(h.store, h.guard, h.runner, h.clock);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    private static String newSession() throws Exception {
        String id = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        h.seedSession(id, subject);
        return id;
    }

    private static TaskAdmitOutcome admit(String question, String sessionId) {
        return h.service.admitDetail(new AdmissionInput(
                new RequestIdentity(UUID.randomUUID().toString(),
                        TaskInputHash.compute(question), false, RequestIdentity.Purpose.chat),
                subject, sessionId, question, "", "test-model", question, 0));
    }

    private static TaskGradeInput gradeInput(String taskId, String jobId) {
        return new TaskGradeInput(taskId, jobId, "dns", "hash-abc", 62, 5, 8,
                "[{\"name\":\"dns_basic\",\"result\":\"FAIL\"}]");
    }

    @Test
    void reserveGradingJobChargesQuotaOnceAndRollsBackOnExcess() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("评测记账", sessionId);
        String day = "2026-10-25";
        // 首次登记扣一次配额；重复登记不重扣
        assertThat(h.store.reserveGradingJob(admit.taskId(), "job-r1", "dns", "hash-a",
                subject, 5, day)).isTrue();
        assertThat(h.store.reserveGradingJob(admit.taskId(), "job-r1", "dns", "hash-a",
                subject, 5, day)).isFalse();
        String count = h.columnValue("usage_counters", "chat_count",
                "subject=? AND kind='grading'", subject);
        assertThat(count).isEqualTo("1");
        int jobsBefore = h.countRows("rag_task_jobs", "task_id=?", admit.taskId());

        // 超额：job 登记与配额同事务 → 账本行一并回滚（当前 grading 计数 1，配额 1）
        assertThatThrownBy(() -> h.store.reserveGradingJob(admit.taskId(), "job-r2", "http",
                "hash-b", subject, 1, day))
                .isInstanceOf(io.dpdk.assistant.usage.QuotaExceededException.class);
        assertThat(h.countRows("rag_task_jobs", "task_id=?", admit.taskId()))
                .isEqualTo(jobsBefore);
        assertThat(h.columnValue("usage_counters", "chat_count",
                "subject=? AND kind='grading'", subject)).isEqualTo("1");
    }

    @Test
    void recordTaskGradePersistsWithSubmissionDoneAtomicallyAndIsIdempotent() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("成绩落库", sessionId);
        h.store.recordJob(admit.taskId(), "job-g1", "grade_plugin", "dns", "hash-abc");

        assertThat(ledger.recordTaskGrade(gradeInput(admit.taskId(), "job-g1")))
                .isEqualTo(TaskGradeResult.persisted);
        // 重复调用不产生第二条成绩（task_job_id 部分唯一索引 + 先查幂等）
        assertThat(ledger.recordTaskGrade(gradeInput(admit.taskId(), "job-g1")))
                .isEqualTo(TaskGradeResult.persisted);
        assertThat(h.countRows("grading_submissions", "task_job_id=?", "job-g1")).isEqualTo(1);
        assertThat(h.columnValue("rag_task_jobs", "submission_done", "task_id=? AND job_id=?",
                admit.taskId(), "job-g1")).isEqualTo("1");
        // results_json 保留 results 与 source_origin（来源取自任务临时输入）
        String results = h.columnValue("grading_submissions", "results_json", "task_job_id=?",
                "job-g1");
        assertThat(results).contains("dns_basic");
        assertThat(results.replace(" ", "")).contains("\"results\"");

        // 同任务不同协议/源码各自登记
        h.store.recordJob(admit.taskId(), "job-g2", "grade_plugin", "http", "hash-abc");
        assertThat(ledger.recordTaskGrade(gradeInput(admit.taskId(), "job-g2")))
                .isEqualTo(TaskGradeResult.persisted);
        assertThat(h.countRows("grading_submissions",
                "task_job_id IN ('job-g1','job-g2') AND assignment_id IS NULL")).isEqualTo(2);
    }

    @Test
    void gradeWriteFailureLeavesSubmissionDoneClaimedUntouched() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("成绩回滚", sessionId);
        h.store.recordJob(admit.taskId(), "job-g3", "grade_plugin", "dns", "hash-abc");
        // 成绩 insert 失败 → submission_done 同事务回滚（账本闸保持未认领）
        TaskGradeLedger failing = new TaskGradeLedger(h.store, (tx, sub) -> {
            throw new IllegalStateException("synthetic grade write failure");
        }, h.runner, h.clock);
        assertThatThrownBy(() -> failing.recordTaskGrade(gradeInput(admit.taskId(), "job-g3")))
                .hasMessage("synthetic grade write failure");
        assertThat(h.columnValue("rag_task_jobs", "submission_done", "task_id=? AND job_id=?",
                admit.taskId(), "job-g3")).isEqualTo("0");
        assertThat(h.countRows("grading_submissions", "task_job_id=?", "job-g3")).isZero();
        // 恢复后照常落库恰好一次
        assertThat(ledger.recordTaskGrade(gradeInput(admit.taskId(), "job-g3")))
                .isEqualTo(TaskGradeResult.persisted);
        assertThat(h.countRows("grading_submissions", "task_job_id=?", "job-g3")).isEqualTo(1);
    }

    @Test
    void cancelledTaskAndDisabledUserRefuseGrade() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("取消成绩", sessionId);
        h.store.recordJob(admit.taskId(), "job-g4", "grade_plugin", "dns", "hash-abc");
        // 非 pending 任务 → cancelled
        h.service.cancelDetail(admit.taskId(), subject, "user_cancelled");
        assertThat(ledger.recordTaskGrade(gradeInput(admit.taskId(), "job-g4")))
                .isEqualTo(TaskGradeResult.cancelled);
        assertThat(h.countRows("grading_submissions", "task_job_id=?", "job-g4")).isZero();

        // 停用账号 → cancelled（账号锁在同一事务裁决）
        String disabled = "U20260000042";
        h.seedUser(disabled, "[\"viewer\"]", 10000);
        String sess2 = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        h.seedSession(sess2, disabled);
        TaskAdmitOutcome admit2 = h.service.admitDetail(new AdmissionInput(
                new RequestIdentity(UUID.randomUUID().toString(),
                        TaskInputHash.compute("停用成绩"), false, RequestIdentity.Purpose.chat),
                disabled, sess2, "停用成绩", "", "test-model", "停用成绩", 0));
        h.store.recordJob(admit2.taskId(), "job-g5", "grade_plugin", "dns", "hash-abc");
        h.adminUsers().setEnabled(disabled, false);
        assertThat(ledger.recordTaskGrade(gradeInput(admit2.taskId(), "job-g5")))
                .isEqualTo(TaskGradeResult.cancelled);
        assertThat(h.countRows("grading_submissions", "task_job_id=?", "job-g5")).isZero();
    }

    @Test
    void missingJobRowRefusesGradeAndLegacyUnlinkedHonored() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("无账本成绩", sessionId);
        // 账本缺行：拒绝无账本记成绩
        assertThatThrownBy(() -> ledger.recordTaskGrade(gradeInput(admit.taskId(), "job-none")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("评测作业账本缺行，拒绝无账本记成绩");

        // 0014 以前已标完成、无关联成绩 → legacy_unlinked，不猜测补写
        h.store.recordJob(admit.taskId(), "job-g6", "grade_plugin", "dns", "hash-abc");
        h.runner.required(tx -> {
            try (var ps = tx.connection().prepareStatement(
                    "UPDATE rag_task_jobs SET submission_done=1 WHERE task_id=? AND job_id=?")) {
                ps.setString(1, admit.taskId());
                ps.setString(2, "job-g6");
                ps.executeUpdate();
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
        assertThat(ledger.recordTaskGrade(gradeInput(admit.taskId(), "job-g6")))
                .isEqualTo(TaskGradeResult.legacy_unlinked);
        assertThat(h.countRows("grading_submissions", "task_job_id=?", "job-g6")).isZero();
    }

    @Test
    void finishJobWritesResultAndFindJobMatchesNullSemantics() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("作业结果", sessionId);
        h.store.recordJob(admit.taskId(), "job-g7", "grade_plugin", "dns", null);
        assertThat(h.store.findJob(admit.taskId(), "dns", null).orElseThrow().status())
                .isEqualTo("submitted");
        h.store.finishJob("job-g7", "{\"score\":90,\"ok\":true}");
        TaskJobRecord job = h.store.findJob(admit.taskId(), "dns", null).orElseThrow();
        assertThat(job.status()).isEqualTo("finished");
        assertThat(job.resultJson()).contains("\"score\":90");
        // code_hash IS NULL 语义：非 null 检索不命中
        assertThat(h.store.findJob(admit.taskId(), "dns", "hash-abc")).isEmpty();
        assertThat(h.store.findJob(admit.taskId(), "http", null)).isEmpty();
    }
}
