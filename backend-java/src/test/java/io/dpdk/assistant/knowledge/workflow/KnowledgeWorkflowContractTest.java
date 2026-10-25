package io.dpdk.assistant.knowledge.workflow;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.auth.AuthServerSettings;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named="DPDK_TEST_PG_URL",matches=".+")
class KnowledgeWorkflowContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void feedbackRequiresOwnMessageReusesIssueReopensAndClearsValidation() throws Exception {
        try (var h = WorkflowTestHarness.create("feedback")) {
            var issue = h.issue("student", "DNS", false);
            assertThatThrownBy(() -> h.workflow.feedback(issue.messageId(),h.foreign,"incorrect","no"))
                    .isInstanceOfSatisfying(ApiDetailException.class,e -> assertThat(e.status().value()).isEqualTo(404));
            assertThat(h.task.countRows("rag_issues", null)).isZero();
            assertThat(h.workflow.feedback(issue.messageId(),h.student,"incorrect","first")).isEqualTo(issue.id());
            String expected = (String)h.workflow.issue(issue.id(),h.teacher).get("updated_at");
            h.workflow.markVerified(issue.id(),h.teacher,"release-1","answered",expected);
            h.release("release-1");
            h.workflow.update(issue.id(),h.teacher,new KnowledgeWorkflowService.IssueUpdate("resolved","",true,null,null));
            h.workflow.feedback(issue.messageId(),h.student,"unresolved","```c\nprivate\n```\nstudent@example.com U20270000001\npassword=top-secret");
            var row = h.workflow.issue(issue.id(),h.teacher);
            assertThat(row).containsEntry("status","open").containsEntry("verified_at",null).containsEntry("verified_release_id",null);
            assertThat((String)row.get("feedback")).doesNotContain("private","student@example.com","U20270000001","top-secret");
            assertThat(h.task.countRows("rag_issues",null)).isEqualTo(1);
            assertThat(h.task.countRows("rag_issue_events",null)).isEqualTo(4);
        }
    }

    @Test void teacherScopePaginationClaimNoteAndEventOrder() throws Exception {
        try (var h = WorkflowTestHarness.create("scope")) {
            var own = h.issue("student","DNS",true);
            var second = h.issue("student","TCP",true);
            var foreign = h.issue("foreign","cross-class",true);
            assertThatThrownBy(() -> h.workflow.issue(foreign.id(),h.teacher))
                    .isInstanceOfSatisfying(ApiDetailException.class,e -> assertThat(e.status().value()).isEqualTo(404));
            assertThat((List<?>)h.workflow.issues(h.teacher,"",1,0).get("items")).hasSize(1);
            assertThat((List<?>)h.workflow.issues(h.teacher,"",1,1).get("items")).hasSize(1);
            assertThat((List<?>)h.workflow.issues(h.teacher,"",1,2).get("items")).isEmpty();
            assertThat((List<?>)h.workflow.issues(h.leader,"",100,0).get("items")).hasSize(3);
            h.workflow.update(own.id(),h.teacher,new KnowledgeWorkflowService.IssueUpdate("in_progress","note\ntoken=secret-value",true,null,null));
            var row = h.workflow.update(own.id(),h.teacher,new KnowledgeWorkflowService.IssueUpdate("awaiting_verification","[上传附件：code.c]\n123456789",false,null,null));
            assertThat(row).containsEntry("owner_id","teacher");
            assertThat((String)row.get("note")).isEqualTo("[附件已省略]\n[号码已省略]");
            var events = (List<Map<String,Object>>)row.get("events");
            assertThat(events).extracting(e -> e.get("action")).containsExactly("in_progress","awaiting_verification");
            assertThat(events.getFirst().get("detail")).isEqualTo("note\n[敏感内容已省略]");
            assertThat(second.id()).isNotEqualTo(own.id());
        }
    }

    @Test void mergeRejectsSelfMergedTargetAndSourceWithChildren() throws Exception {
        try (var h = WorkflowTestHarness.create("merge")) {
            var a = h.issue("student","A",true); var b = h.issue("student","B",true); var c = h.issue("student","C",true);
            assertStatus(() -> h.workflow.update(a.id(),h.teacher,merge(a.id())),400);
            h.workflow.update(a.id(),h.teacher,merge(b.id()));
            assertStatus(() -> h.workflow.update(c.id(),h.teacher,merge(a.id())),400);
            assertStatus(() -> h.workflow.update(b.id(),h.teacher,merge(c.id())),409);
            assertThat(h.workflow.issue(a.id(),h.teacher)).containsEntry("merged_into",b.id());
        }
    }

    @Test void resolveRequiresAnsweredVerificationOnCurrentRelease() throws Exception {
        try (var h = WorkflowTestHarness.create("resolve")) {
            var i = h.issue("student","DNS",true);
            h.release("release-1");
            assertStatus(() -> h.workflow.update(i.id(),h.teacher,state("resolved")),409);
            String before = (String)h.workflow.issue(i.id(),h.teacher).get("updated_at");
            h.workflow.markVerified(i.id(),h.teacher,"release-1","partial",before);
            assertStatus(() -> h.workflow.update(i.id(),h.teacher,state("resolved")),409);
            before = (String)h.workflow.issue(i.id(),h.teacher).get("updated_at");
            h.workflow.markVerified(i.id(),h.teacher,"release-1","answered",before);
            assertThat(h.workflow.update(i.id(),h.teacher,state("resolved"))).containsEntry("status","resolved");
            h.release("release-2");
            assertStatus(() -> h.workflow.update(i.id(),h.teacher,state("resolved")),409);
        }
    }

    @Test void eventFailureRollsBackFeedbackAndStatusTogether() throws Exception {
        try (var h = WorkflowTestHarness.create("rollback")) {
            var i = h.issue("student","DNS",true);
            h.sql("ALTER TABLE rag_issue_events ADD CONSTRAINT reject_feedback CHECK(action <> 'feedback')");
            assertThatThrownBy(() -> h.workflow.feedback(i.messageId(),h.student,"incorrect","must rollback"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(h.workflow.issue(i.id(),h.teacher)).containsEntry("feedback","");
            assertThat(h.task.countRows("rag_issue_events",null)).isZero();
        }
    }

    @Test void retentionOnlyCleansOwnTablesAndObsoleteIdentityReferences() throws Exception {
        try (var h = WorkflowTestHarness.create("retain")) {
            var old = h.issue("student","old",true); var current = h.issue("student","current",true);
            h.workflow.feedback(old.messageId(),h.student,"incorrect","old");
            h.workflow.feedback(current.messageId(),h.student,"incorrect","current");
            String cutoff = "2026-04-28T04:00:00+00:00";
            h.sql("UPDATE rag_requests SET created_at=? WHERE id=?",cutoff,old.id());
            h.sql("UPDATE rag_issues SET owner_id='deleted-actor'");
            var first = h.task.runner.required(tx -> h.workflow.maintainInTx(tx,180));
            // 180 天边界恰好保留；小于边界才清。
            assertThat(first).containsEntry("issues",0);
            h.sql("UPDATE rag_requests SET created_at='2026-04-28T03:59:59+00:00' WHERE id=?",old.id());
            var cleaned = h.task.runner.required(tx -> h.workflow.maintainInTx(tx,180));
            assertThat(cleaned).containsEntry("issues",1).containsEntry("events",1);
            assertThat(h.task.countRows("rag_requests",null)).isEqualTo(2);
            assertThat(h.task.countRows("assistant_messages",null)).isEqualTo(4);
            assertThat(h.workflow.issue(current.id(),h.teacher)).containsEntry("owner_id",null);
        }
    }

    @Test void realAuthenticationRoleAndModeGatesAndFrozenInvalidFields() throws Exception {
        try (var h = WorkflowTestHarness.create("http")) {
            var mvc = h.mvc();
            mvc.perform(get("/api/rag/issues")).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/rag/issues").header("Authorization","Bearer "+h.tokens.get("student"))).andExpect(status().isNotFound());
            mvc.perform(get("/api/rag/issues?limit=0").header("Authorization","Bearer "+h.tokens.get("teacher")))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail[0].type").value("greater_than_equal"));
            mvc.perform(patch("/api/rag/issues/missing").header("Authorization","Bearer "+h.tokens.get("teacher"))
                    .contentType("application/json").content("{}"))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail[0].loc[1]").value("status"));
            mvc.perform(post("/api/rag/knowledge").header("Authorization","Bearer "+h.tokens.get("teacher"))
                    .contentType("application/json").content("{}"))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail.length()").value(2));
            h.task.authSettings.replace(AuthServerSettings.of("local","{}",7,8,4,200));
            mvc.perform(get("/api/rag/knowledge")).andExpect(status().isNotFound());
        }
    }

    @Test void allFiveFrozenInvalidInputBodiesMatchPythonGoldenExactly() throws Exception {
        try (var h = WorkflowTestHarness.create("golden")) {
            var mvc=h.mvc();
            var cases=JSON.readTree(getClass().getResourceAsStream("/migration/J4-02/invalid-input-golden.json")).get("cases");
            for(var fields=cases.fields();fields.hasNext();) {
                var c=fields.next();
                String verb=c.getKey().split(" ",2)[0];
                String url=c.getKey().split(" ",2)[1].split("::")[0]
                        .replace("{issue_id}","missing").replace("{knowledge_id}","missing");
                var builder=verb.equals("GET") ? get(url+"?limit=0")
                        : verb.equals("POST") ? post(url).contentType("application/json").content("{}")
                        : patch(url).contentType("application/json").content("{}");
                var result=mvc.perform(builder.header("Authorization","Bearer "+h.tokens.get("teacher")))
                        .andExpect(status().isUnprocessableEntity()).andReturn();
                assertThat(JSON.readTree(result.getResponse().getContentAsString())).as(c.getKey()).isEqualTo(c.getValue());
            }
        }
    }

    @Test void missingVerificationStillConsumesDailyQuotaBeforeScopeLookup() throws Exception {
        try (var h = WorkflowTestHarness.create("order")) {
            assertStatus(() -> h.workflow.verify("missing",h.teacher),404);
            assertThat(h.task.columnValue("usage_counters","chat_count","subject='teacher' AND kind='chat'"))
                    .isEqualTo("1");
        }
    }

    static KnowledgeWorkflowService.IssueUpdate state(String status) { return new KnowledgeWorkflowService.IssueUpdate(status,"",false,null,null); }
    static KnowledgeWorkflowService.IssueUpdate merge(String target) { return new KnowledgeWorkflowService.IssueUpdate("ignored","merged",false,target,null); }
    static void assertStatus(Runnable call,int code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiDetailException.class,e -> assertThat(e.status().value()).isEqualTo(code));
    }
}
