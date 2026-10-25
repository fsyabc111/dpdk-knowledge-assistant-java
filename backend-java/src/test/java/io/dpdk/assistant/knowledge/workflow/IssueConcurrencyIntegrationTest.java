package io.dpdk.assistant.knowledge.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.dpdk.assistant.knowledge.workflow.KnowledgeWorkflowContractTest.*;
import io.dpdk.assistant.task.TaskTestHarness;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="DPDK_TEST_PG_URL",matches=".+")
class IssueConcurrencyIntegrationTest {
    @Test void oldVerificationCannotOverwriteFeedbackFromSecondIndependentPgConnection() throws Exception {
        try (var h = WorkflowTestHarness.create("racefeedback")) {
            var i = h.issue("student","DNS",true);
            TaskTestHarness second = TaskTestHarness.attach(h.task.database);
            var otherProcess = h.service(second.runner);
            try (Connection a=h.task.ds.getConnection(); Connection b=second.ds.getConnection()) {
                assertThat(pid(a)).isNotEqualTo(pid(b));
                String expected = (String)h.workflow.issue(i.id(),h.teacher).get("updated_at");
                var snapshot = h.workflow.issue(i.id(),h.teacher);
                // 验证外部调用期间另一进程反馈；同一可控时钟也必须改变 updated_at。
                otherProcess.feedback(i.messageId(),h.student,"incorrect","new feedback");
                assertStatus(() -> h.workflow.markVerified(i.id(),h.teacher,"release-1","answered",expected),409);
                var finalRow = h.workflow.issue(i.id(),h.teacher);
                assertThat(finalRow).containsEntry("status","open").containsEntry("feedback","incorrect: new feedback")
                        .containsEntry("verified_at",null).containsEntry("verified_release_id",null);
                assertThat(finalRow.get("updated_at")).isNotEqualTo(snapshot.get("updated_at"));
                assertThat(h.task.countRows("rag_issue_events","action='verify'")).isZero();
                assertThat(h.task.countRows("rag_issue_events","action='feedback'")).isEqualTo(1);
            }
        }
    }

    @Test void oldVerificationCannotOverwriteTeacherEditOnSecondPgConnection() throws Exception {
        try (var h = WorkflowTestHarness.create("raceedit")) {
            var i = h.issue("student","DNS",true);
            TaskTestHarness second = TaskTestHarness.attach(h.task.database);
            String expected = (String)h.workflow.issue(i.id(),h.teacher).get("updated_at");
            h.service(second.runner).update(i.id(),h.teacher,new KnowledgeWorkflowService.IssueUpdate("in_progress","new note",true,null,null));
            assertStatus(() -> h.workflow.markVerified(i.id(),h.teacher,"release-1","answered",expected),409);
            assertThat(h.workflow.issue(i.id(),h.teacher)).containsEntry("status","in_progress")
                    .containsEntry("note","new note").containsEntry("verified_at",null);
            assertThat(h.task.countRows("rag_issue_events",null)).isEqualTo(1);
        }
    }

    @Test void concurrentFeedbackCreatesOneIssueAndPreservesEveryEvent() throws Exception {
        try (var h = WorkflowTestHarness.create("raceduplicate"); var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var i = h.issue("student","DNS",false);
            var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
            List<Future<String>> futures=new ArrayList<>();
            for(int n=0;n<2;n++) {
                String detail="feedback-"+n;
                futures.add(pool.submit(() -> { ready.countDown(); go.await(); return h.workflow.feedback(i.messageId(),h.student,"incorrect",detail); }));
            }
            assertThat(ready.await(5,TimeUnit.SECONDS)).isTrue(); go.countDown();
            assertThat(futures.get(0).get(5,TimeUnit.SECONDS)).isEqualTo(i.id());
            assertThat(futures.get(1).get(5,TimeUnit.SECONDS)).isEqualTo(i.id());
            assertThat(h.task.countRows("rag_issues",null)).isEqualTo(1);
            assertThat(h.task.countRows("rag_issue_events",null)).isEqualTo(2);
            var events=(List<Map<String,Object>>)h.workflow.issue(i.id(),h.teacher).get("events");
            assertThat(events.get(0).get("created_at")).isNotEqualTo(events.get(1).get("created_at"));
        }
    }

    @Test void concurrentReciprocalMergesCannotCreateCycle() throws Exception {
        try (var h = WorkflowTestHarness.create("racemerge"); var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=h.issue("student","A",true); var b=h.issue("student","B",true);
            var go=new CountDownLatch(1);
            Future<Integer> first=pool.submit(() -> { go.await(); return outcome(() -> h.workflow.update(a.id(),h.teacher,merge(b.id()))); });
            Future<Integer> second=pool.submit(() -> { go.await(); return outcome(() -> h.workflow.update(b.id(),h.teacher,merge(a.id()))); });
            go.countDown();
            assertThat(List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS))).contains(200).containsAnyOf(400,409);
            assertThat(h.task.countRows("rag_issues","merged_into IS NOT NULL")).isEqualTo(1);
            assertThat(h.task.countRows("rag_issue_events",null)).isEqualTo(1);
        }
    }

    static int outcome(Runnable work) {
        try {work.run();return 200;}
        catch(io.dpdk.assistant.platform.errors.ApiDetailException e) {return e.status().value();}
    }
    private static int pid(Connection c) throws Exception {try(var s=c.createStatement();var rs=s.executeQuery("SELECT pg_backend_pid()")) {rs.next();return rs.getInt(1);}}
}
