package io.dpdk.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import io.dpdk.assistant.contracts.task.AdmissionInput;
import io.dpdk.assistant.contracts.task.RequestIdentity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** task、job、issue为不同身份域；防同形INSERT参数误修破坏跨卡引用。 */
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class TaskStoreIdentityIntegrationTest {
    @Test void twoJobsHaveDistinctIdsWhileIssueUsesTaskIdentity() throws Exception {
        TaskTestHarness h = TaskTestHarness.create("p4identity");
        try {
            String user = "U20260000891";
            String session = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            h.seedUser(user, "[\"viewer\"]", 200);
            h.seedSession(session, user);
            var admitted = h.service.admitDetail(new AdmissionInput(
                    new RequestIdentity(UUID.randomUUID().toString(), TaskInputHash.compute("question"),
                            false, RequestIdentity.Purpose.chat), user, session, "question", "", "fixed", "question", 200));
            String task = admitted.taskId();
            h.store.recordJob(task, "job-1", "grade_plugin", "dns", "hash-1");
            h.store.recordJob(task, "job-2", "grade_plugin", "http", "hash-2");
            assertThat(h.countRows("rag_task_jobs", "task_id=?", task)).isEqualTo(2);
            String first = h.columnValue("rag_task_jobs", "id", "job_id=?", "job-1");
            String second = h.columnValue("rag_task_jobs", "id", "job_id=?", "job-2");
            assertThat(first).isNotEqualTo(second).isNotEqualTo(task);
            assertThat(second).isNotEqualTo(task);
            String message = h.store.finish(task, session, "不足以回答", List.of(), "unanswered", "no_evidence", "", 1);
            assertThat(h.columnValue("rag_issues", "id", "request_id=?", task)).isEqualTo(task);
            assertThat(h.columnValue("assistant_messages", "metadata_json", "id=?", message)).contains(task);
        } finally { h.taskRunner.shutdownWatchdog(); h.drop(); }
    }
}
