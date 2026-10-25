package io.dpdk.assistant.knowledge.workflow;

import static org.assertj.core.api.Assertions.*;
import static io.dpdk.assistant.knowledge.workflow.KnowledgeWorkflowContractTest.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="DPDK_TEST_PG_URL",matches=".+")
class KnowledgePublicationIntegrationTest {
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
            assertThat(h.task.countRows("li_nodes",null)).isZero();
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
