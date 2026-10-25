package io.dpdk.assistant.knowledge.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.dpdk.assistant.knowledge.workflow.KnowledgeWorkflowContractTest.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import io.dpdk.assistant.agent.AgentChatModel;
import io.dpdk.assistant.chat.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="DPDK_TEST_PG_URL",matches=".+")
class KnowledgePublicationIntegrationTest {
    @Test void originalQuestionUsesRealProductRetrievalCurrentReleaseAndForcedJudgeWithoutCreatingTask() throws Exception {
        AtomicInteger generated=new AtomicInteger(),judged=new AtomicInteger();
        AgentChatModel model=(messages,tools) -> {
            if(messages.getFirst().content().equals(AnswerValidator.JUDGE_PROMPT)) {
                judged.incrementAndGet();
                assertThat(messages.getLast().content()).contains("DNS UDP protocol");
                return AgentChatModel.AgentReply.text("{\"status\":\"answered\",\"reason\":\"supported\",\"supported\":true}");
            }
            generated.incrementAndGet();
            assertThat(messages.getLast().content()).contains("DNS UDP protocol");
            return AgentChatModel.AgentReply.text("DNS 知识依据 [1]");
        };
        try(var h=ChatTestHarness.create("j402realverify",model,null)) {
            prepareTeacher(h);
            h.seedKnowledge("active-new","li_dns","DNS UDP protocol implementation");
            var issue=WorkflowTestHarness.admitQuestion(h.task,"student","DNS",true);
            var teacher=WorkflowTestHarness.principal("teacher","admin");
            var service=realVerifier(h,model);
            assertThat(ChatSettings.defaults().onlineJudgeEnabled()).isFalse();
            var result=service.verify(issue.id(),teacher);
            assertThat(result.status()).isEqualTo("answered");
            assertThat(result.releaseId()).isEqualTo("active-new");
            assertThat(generated).hasValue(1); assertThat(judged).hasValue(1);
            assertThat(h.task.countRows("rag_requests",null)).isEqualTo(1);
            assertThat(h.task.countRows("assistant_messages",null)).isEqualTo(2);
            assertThat(h.task.columnValue("usage_counters","chat_count","subject='teacher' AND kind='chat'" )).isEqualTo("1");
            assertThat(h.task.gate.active()).isZero();
            assertThat(service.update(issue.id(),teacher,state("resolved"))).containsEntry("status","resolved");
        }
    }

    @Test void feedbackDuringRealRetrievalGenerationJudgeCannotBeOverwrittenByOldVerification() throws Exception {
        var entered=new CountDownLatch(1);var allow=new CountDownLatch(1);
        AgentChatModel model=(messages,tools) -> {
            if(messages.getFirst().content().equals(AnswerValidator.JUDGE_PROMPT))
                return AgentChatModel.AgentReply.text("{\"status\":\"answered\",\"reason\":\"supported\",\"supported\":true}");
            entered.countDown();
            try {if(!allow.await(5,TimeUnit.SECONDS))throw new IllegalStateException("model latch timeout");}
            catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
            return AgentChatModel.AgentReply.text("DNS 知识依据 [1]");
        };
        try(var h=ChatTestHarness.create("j402verifierace",model,null);var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            prepareTeacher(h);h.seedKnowledge("r1","li_dns","DNS UDP protocol implementation");
            var issue=WorkflowTestHarness.admitQuestion(h.task,"student","DNS",true);
            var teacher=WorkflowTestHarness.principal("teacher","admin");
            var service=realVerifier(h,model);
            var future=pool.submit(() -> IssueConcurrencyIntegrationTest.outcome(() -> service.verify(issue.id(),teacher)));
            try {
                assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
                service.feedback(issue.messageId(),WorkflowTestHarness.principal("student","viewer"),"incorrect","new feedback while verifying");
            } finally {allow.countDown();}
            assertThat(future.get(5,TimeUnit.SECONDS)).isEqualTo(409);
            assertThat(service.issue(issue.id(),teacher)).containsEntry("verified_at",null)
                    .containsEntry("status","open").containsEntry("feedback","incorrect: new feedback while verifying");
            assertThat(h.task.countRows("rag_issue_events","action='verify'")).isZero();
            assertThat(h.task.gate.active()).isZero();
        }
    }

    private static KnowledgeWorkflowService realVerifier(ChatTestHarness h,AgentChatModel model) {
        return new KnowledgeWorkflowService(h.task.runner,h.task.clock,new GroundedQuestionVerifier(
                h.product,h.task.counters,h.task.gate,h.retrieval,model,ChatSettings.defaults()));
    }

    private static void prepareTeacher(ChatTestHarness h)throws Exception {
        try(var conn=h.task.ds.getConnection();var statement=conn.createStatement()) {
            statement.executeUpdate("UPDATE users SET created_by='teacher' WHERE id='student'");
            statement.executeUpdate("UPDATE users SET roles='[\"admin\"]' WHERE id='teacher'");
        }
    }

    @Test void confirmationOwnershipRevisionAndWithdrawnHistoryAreEnforced() throws Exception {
        try(var h=WorkflowTestHarness.create("publication")) {
            assertStatus(() -> h.workflow.writeKnowledge(null,h.teacher,write("DNS",0,"published",false)),400);
            var published=h.workflow.writeKnowledge(null,h.teacher,write("DNS",0,"published",true));
            String id=(String)published.get("id");
            assertThat(published).containsEntry("revision",1).containsEntry("index_status","pending");
            assertStatus(() -> h.workflow.writeKnowledge(id,h.other,write("steal",1,"published",true)),404);
            h.workflow.writeKnowledge(id,h.teacher,write("DNS v2",1,"published",true));
            assertStatus(() -> h.workflow.writeKnowledge(id,h.teacher,write("old",1,"published",true)),409);
            var withdrawal=h.workflow.writeKnowledge(id,h.leader,write("DNS withdrawn",2,"withdrawn",false));
            assertThat(withdrawal).containsEntry("revision",3).containsEntry("index_status","pending");
            var rows=(List<Map<String,Object>>)h.workflow.revisions(id,h.other).get("items");
            assertThat(rows).extracting(r->r.get("revision")).containsExactly(3,2,1);
            assertThat(rows).extracting(r->r.get("actor_id")).containsExactly("leader","teacher","teacher");
            assertThat(h.workflow.revisions("missing",h.teacher)).containsEntry("items",List.of());
        }
    }

    @Test void concurrentRevisionCasHasOneWinnerAndOneConflictWithNoExtraHistory() throws Exception {
        try(var h=WorkflowTestHarness.create("revisionrace");var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            String id=(String)h.workflow.writeKnowledge(null,h.teacher,write("base",0,"published",true)).get("id");
            var go=new CountDownLatch(1);
            Future<Integer> a=pool.submit(() -> {go.await();return IssueConcurrencyIntegrationTest.outcome(() -> h.workflow.writeKnowledge(id,h.teacher,write("first",1,"published",true)));});
            Future<Integer> b=pool.submit(() -> {go.await();return IssueConcurrencyIntegrationTest.outcome(() -> h.workflow.writeKnowledge(id,h.leader,write("second",1,"published",true)));});
            go.countDown();
            assertThat(List.of(a.get(5,TimeUnit.SECONDS),b.get(5,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
            assertThat(h.task.countRows("rag_knowledge_revisions",null)).isEqualTo(2);
            assertThat(h.task.columnValue("rag_knowledge","revision","id=?",id)).isEqualTo("2");
        }
    }

    @Test void publicationReturnsPendingAndDoesNotMutateActiveReleaseOrIndexNodes() throws Exception {
        try(var h=WorkflowTestHarness.create("pending")) {
            h.release("active-fixture");
            var result=h.workflow.writeKnowledge(null,h.teacher,write("pending shared",0,"published",true));
            assertThat(result).containsEntry("index_status","pending");
            assertThat(h.workflow.knowledge(h.other)).containsEntry("active_release","active-fixture");
            assertThat((List<?>)h.workflow.knowledge(h.other).get("items")).hasSize(1);
            assertThat(h.task.countRows("data_li_knowledge_v1",null)).isZero();
            assertThat(h.task.countRows("rag_release_nodes",null)).isZero();
        }
    }

    @Test void manuallyConfirmedKnowledgeRetainsSourceCodeBytesAndRevisionFailureRollsBack() throws Exception {
        try(var h=WorkflowTestHarness.create("literalbody")) {
            String body="教学代码\n```c\nstruct dns { int value; };\n```";
            String id=(String)h.workflow.writeKnowledge(null,h.teacher,new KnowledgeWorkflowService.KnowledgeWrite("DNS",body,0,"published",true)).get("id");
            assertThat(h.task.columnValue("rag_knowledge","body","id=?",id)).isEqualTo(body);
            h.sql("ALTER TABLE rag_knowledge_revisions ADD CONSTRAINT reject_second_revision CHECK(revision<>2)");
            assertThatThrownBy(() -> h.workflow.writeKnowledge(id,h.teacher,write("must rollback",1,"published",true))).isInstanceOf(IllegalStateException.class);
            assertThat(h.task.columnValue("rag_knowledge","revision","id=?",id)).isEqualTo("1");
            assertThat(h.task.columnValue("rag_knowledge","body","id=?",id)).isEqualTo(body);
            assertThat(h.task.countRows("rag_knowledge_revisions",null)).isEqualTo(1);
        }
    }

    static KnowledgeWorkflowService.KnowledgeWrite write(String title,int revision,String status,boolean confirmed) {
        return new KnowledgeWorkflowService.KnowledgeWrite(title,"DNS 共享知识正文",revision,status,confirmed);
    }
}
