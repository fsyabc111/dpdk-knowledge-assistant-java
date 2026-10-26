package io.dpdk.assistant.knowledge.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.platform.ClockPort;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 冻结Python每个持久写入的独立now语义；逻辑微秒时钟+真实PG，经公开工作流读回。 */
@EnabledIfEnvironmentVariable(named="DPDK_TEST_PG_URL",matches=".+")
class WorkflowTimestampContractPgTest {
    @Test void knowledgeRowAndRevisionHaveIndependentPublicationAndPatchTimes() throws Exception {
        try(var h=WorkflowTestHarness.create("p5timestampsknowledge")) {
            var clock=new MicrosecondClock();var service=service(h,clock);
            var created=service.writeKnowledge(null,h.teacher,write(0));String id=(String)created.get("id");
            assertThat(knowledge(service,h.teacher,id).get("updated_at")).isEqualTo("2026-10-26T12:00:00+00:00");
            assertThat(revisions(service,h.teacher,id).getFirst().get("created_at")).isEqualTo("2026-10-26T12:00:00.000001+00:00");
            service.writeKnowledge(id,h.teacher,write(1));
            assertThat(knowledge(service,h.teacher,id).get("updated_at")).isEqualTo("2026-10-26T12:00:00.000002+00:00");
            assertThat(revisions(service,h.teacher,id).getFirst().get("created_at")).isEqualTo("2026-10-26T12:00:00.000003+00:00");
        }
    }
    @Test void feedbackInsertUpdateAndEventEachHaveOwnWallClockInstant() throws Exception {
        try(var h=WorkflowTestHarness.create("p5timestampsfeedback")) {
            var issue=h.issue("student","DNS",false);
            var service=service(h,new MicrosecondClock());
            service.feedback(issue.messageId(),h.student,"incorrect","first");
            var row=service.issue(issue.id(),h.teacher);
            assertThat(row.get("updated_at")).isEqualTo("2026-10-26T12:00:00.000001+00:00");
            assertThat(events(row).getFirst().get("created_at")).isEqualTo("2026-10-26T12:00:00.000002+00:00");
            service.feedback(issue.messageId(),h.student,"unresolved","second");
            row=service.issue(issue.id(),h.teacher);
            assertThat(row.get("updated_at")).isEqualTo("2026-10-26T12:00:00.000004+00:00");
            assertThat(events(row).getLast().get("created_at")).isEqualTo("2026-10-26T12:00:00.000005+00:00");
        }
    }
    @Test void issuePatchAndAuditEventHaveSeparateTimes() throws Exception {
        try(var h=WorkflowTestHarness.create("p5timestampsissue")) {
            var issue=h.issue("student","DNS",true);var service=service(h,new MicrosecondClock());
            var row=service.update(issue.id(),h.teacher,new KnowledgeWorkflowService.IssueUpdate("in_progress","合成备注",true,null,null));
            assertThat(row.get("updated_at")).isEqualTo("2026-10-26T12:00:00+00:00");
            assertThat(events(row).getFirst().get("created_at")).isEqualTo("2026-10-26T12:00:00.000001+00:00");
        }
    }
    @Test void answeredVerificationAndNonansweredVerificationFollowIndependentPersistentTimes() throws Exception {
        try(var h=WorkflowTestHarness.create("p5timestampsverify")) {
            var issue=h.issue("student","DNS",true);var service=service(h,new MicrosecondClock());
            String expected=(String)service.issue(issue.id(),h.teacher).get("updated_at");
            service.markVerified(issue.id(),h.teacher,"release-1","answered",expected);
            var row=service.issue(issue.id(),h.teacher);
            assertThat(row.get("verified_at")).isEqualTo("2026-10-26T12:00:00+00:00");
            assertThat(row.get("updated_at")).isEqualTo("2026-10-26T12:00:00.000001+00:00");
            assertThat(events(row).getFirst().get("created_at")).isEqualTo("2026-10-26T12:00:00.000002+00:00");
            service.markVerified(issue.id(),h.teacher,"release-1","partial",(String)row.get("updated_at"));
            row=service.issue(issue.id(),h.teacher);
            assertThat(row.get("verified_at")).isNull();
            assertThat(row.get("updated_at")).isEqualTo("2026-10-26T12:00:00.000003+00:00");
            assertThat(events(row).getLast().get("created_at")).isEqualTo("2026-10-26T12:00:00.000004+00:00");
        }
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> events(Map<String,Object> row) {
        return (List<Map<String,Object>>)row.get("events");
    }
    private static KnowledgeWorkflowService.KnowledgeWrite write(int revision) {
        return new KnowledgeWorkflowService.KnowledgeWrite("合成DNS资料","仅合成教学内容",revision,"published",true);
    }
    private static KnowledgeWorkflowService service(WorkflowTestHarness h,ClockPort clock) {
        return new KnowledgeWorkflowService(h.task.runner,clock,new OriginalQuestionVerifier() {
            public void prepareAdmission(Principal p) { throw new AssertionError("此测试不做原题外部验证"); }
            public Result verify(Principal p,String q,String c) { throw new AssertionError("不得调用模型或MCP"); }
        });
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> knowledge(KnowledgeWorkflowService service,Principal p,String id) {
        return ((List<Map<String,Object>>)service.knowledge(p).get("items")).stream().filter(x->id.equals(x.get("id"))).findFirst().orElseThrow();
    }
    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> revisions(KnowledgeWorkflowService service,Principal p,String id) {
        return (List<Map<String,Object>>)service.revisions(id,p).get("items");
    }
    static final class MicrosecondClock implements ClockPort {
        long ticks;
        public Instant nowUtc() { return Instant.parse("2026-10-26T12:00:00Z").plusNanos(1_000*ticks++); }
        public long monotonicNanos() { return 0; }
        public String nextSessionIso() { throw new AssertionError("工作流应使用独立墙钟，不消费会话时钟"); }
    }
}
