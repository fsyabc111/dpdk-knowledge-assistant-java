package io.dpdk.assistant.operations;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.agent.*;
import io.dpdk.assistant.chat.AnswerValidator;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 仅固定内嵌样例与固定模型边界；不连接 PG、模型服务或 harness。 */
class OfflineEvidenceJudgeTest {
    @TempDir Path temp;
    static final ObjectMapper JSON=new ObjectMapper();
    static final Duration TIMEOUT=Duration.ofSeconds(1);
    Map<String,Object> sample(String name,String mode,String answer,Map<String,Object> evidence,String status,String reason) {
        Map<String,Object> out=new LinkedHashMap<>();out.put("name",name);out.put("mode",mode);out.put("question","合成问题");
        out.put("answer",answer);out.put("evidence",evidence);out.put("expect_status",status);
        if(reason!=null)out.put("expect_reason",reason);return out;
    }
    Map<String,Object> evidence() { return Map.of("chunks",List.of(Map.of("ref",1,"source","fixture.md","content","合成证据")),"tools",List.of()); }
    Path file(List<Map<String,Object>> cases) throws Exception {
        Path p=temp.resolve(UUID.randomUUID()+".json");Files.writeString(p,JSON.writeValueAsString(Map.of("cases",cases)));return p;
    }
    @SuppressWarnings("unchecked") Map<String,Object> summary(Map<String,Object> report) {return (Map<String,Object>)report.get("summary");}
    @SuppressWarnings("unchecked") List<Map<String,Object>> records(Map<String,Object> report) {return (List<Map<String,Object>>)report.get("cases");}
    @Test void fiveDeterministicRulesUseRealValidatorAndZeroModelCalls() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        var runner=new OfflineEvidenceJudge((messages,tools)->{calls.incrementAndGet();throw new AssertionError("不得调用模型");},TIMEOUT,false);
        var report=runner.apply(file(List.of(
                sample("empty","deterministic","  ",evidence(),"unanswered","empty_answer"),
                sample("invalid","deterministic","内容 [2]",evidence(),"unanswered","invalid_citation"),
                sample("no-evidence","deterministic","内容",Map.of(),"unanswered","no_evidence"),
                sample("missing-citation","deterministic","内容",evidence(),"unanswered","missing_citation"),
                sample("supported","deterministic","内容 [1]",evidence(),"answered","supported"))));
        assertThat(calls).hasValue(0);assertThat(summary(report)).containsEntry("matched",5).containsEntry("deterministic_mismatched",0);
        assertThat(report).containsEntry("domain","offline_evidence_judge").containsEntry("result","matched").containsEntry("exit_code",0);
        assertThat(records(report)).allSatisfy(r->assertThat(((Map<?,?>)r.get("usage")).get("calls")).isEqualTo(0));
    }
    @Test void safeYamlAndOptionalReasonMatchPythonShape() throws Exception {
        Path p=temp.resolve("cases.yml");Files.writeString(p,"""
                cases:
                  - name: yaml-case
                    mode: deterministic
                    question: 合成问题
                    answer: 答案 [1]
                    evidence:
                      chunks: [{ref: 1, source: fixture.md, content: 合成证据}]
                      tools: []
                    expect_status: answered
                """);
        assertThat(summary(new OfflineEvidenceJudge(null,TIMEOUT,false).apply(p))).containsEntry("matched",1);
    }
    @Test void unauthorizedJudgeIsSkippedAndNeverCountedAsMatched() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        var runner=new OfflineEvidenceJudge((messages,tools)->{calls.incrementAndGet();throw new AssertionError();},TIMEOUT,false);
        var report=runner.apply(file(List.of(sample("live","judge","答案 [1]",evidence(),"answered","supported"))));
        assertThat(calls).hasValue(0);assertThat(summary(report)).containsEntry("matched",0).containsEntry("mismatched",0).containsEntry("skipped",1);
        assertThat(records(report).getFirst()).containsEntry("matched",null).containsEntry("actual_status",null).containsEntry("outcome","skipped_live_not_authorized");
        assertThat(report).containsEntry("result","incomplete");
    }
    @Test void fixedModelVerifiesAllAllowedStatesThroughSharedPrompt() throws Exception {
        Map<String,String> reasons=Map.of("answered","supported","partial","insufficient_evidence","needs_clarification","clarification","unanswered","conflicting_evidence","not_applicable","non_knowledge");
        for(var row:reasons.entrySet()) {
            AtomicInteger calls=new AtomicInteger();
            var runner=new OfflineEvidenceJudge((messages,tools)->{
                calls.incrementAndGet();assertThat(tools).isEmpty();
                assertThat(messages.getFirst().content()).isEqualTo(AnswerValidator.JUDGE_PROMPT);
                assertThat(messages.get(1).content()).contains("\"question\"","\"answer\"","\"evidence\"");
                return new AgentChatModel.AgentReply("{\"status\":\""+row.getKey()+"\",\"reason\":\""+row.getValue()+"\",\"supported\":true}",List.of(),new AgentUsage(2,3,5));
            },TIMEOUT,true);
            var report=runner.apply(file(List.of(sample("fixed","judge","答案 [1]",evidence(),row.getKey(),row.getValue()))));
            assertThat(calls).hasValue(1);assertThat(summary(report)).containsEntry("matched",1);
            assertThat(((Map<?,?>)summary(report).get("usage")).get("total_tokens")).isEqualTo(5L);
        }
    }
    @Test void explicitJudgeHandlesSmalltalkAndClarificationWithoutDeterministicShortCircuit() throws Exception {
        var runner=new OfflineEvidenceJudge((m,t)->AgentChatModel.AgentReply.text("{\"status\":\"not_applicable\",\"reason\":\"non_knowledge\",\"supported\":true}"),TIMEOUT,true);
        assertThat(summary(runner.apply(file(List.of(sample("smalltalk","judge","你好",Map.of(),"not_applicable","non_knowledge")))))).containsEntry("matched",1);
    }
    @Test void deterministicPrechecksRemainAheadOfForcedModelJudge() throws Exception {
        AtomicInteger calls=new AtomicInteger();
        var runner=new OfflineEvidenceJudge((m,t)->{calls.incrementAndGet();throw new AssertionError();},TIMEOUT,true);
        var report=runner.apply(file(List.of(sample("bad-ref","judge","答案 [999]",evidence(),"unanswered","invalid_citation"))));
        assertThat(calls).hasValue(0);assertThat(summary(report)).containsEntry("matched",1);
    }
    @Test void invalidStrictJsonAndVerdictsBecomeValidationFailedWithoutLeakingReply() throws Exception {
        for(String raw:List.of("PRIVATE_SECRET", "```json\n{}\n```", "{\"status\":\"answered\",\"supported\":true} trailing", "{\"status\":\"answered\",\"status\":\"unanswered\",\"supported\":true}", "{\"status\":\"invalid\",\"supported\":true}", "{\"status\":\"answered\",\"supported\":\"true\"}")) {
            var runner=new OfflineEvidenceJudge((m,t)->AgentChatModel.AgentReply.text(raw),TIMEOUT,true);
            var report=runner.apply(file(List.of(sample("bad-json","judge","答案 [1]",evidence(),"answered","supported"))));
            assertThat(records(report).getFirst()).containsEntry("actual_status","system_error").containsEntry("actual_reason","validation_failed").containsEntry("matched",false);
            assertThat(report.toString()).doesNotContain("PRIVATE_SECRET", "答案 [1]", "合成证据");
            assertThat(summary(report)).containsEntry("model_mismatched",0).containsEntry("runtime_errors",1);
        }
    }
    @Test void timeoutCancelsModelAndRecordsUnknownCall() throws Exception {
        CountDownLatch interrupted=new CountDownLatch(1);
        var runner=new OfflineEvidenceJudge((m,t)->{
            try {Thread.sleep(60000);} catch(InterruptedException e) {interrupted.countDown();throw new IllegalStateException("PRIVATE_SECRET");}
            return AgentChatModel.AgentReply.text("{}");
        },Duration.ofMillis(50),true);
        long start=System.nanoTime();
        var report=runner.apply(file(List.of(sample("timeout","judge","答案 [1]",evidence(),"answered","supported"))));
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofSeconds(3));
        assertThat(interrupted.await(1,TimeUnit.SECONDS)).isTrue();
        assertThat(records(report).getFirst()).containsEntry("actual_reason","validation_failed").containsEntry("error","TimeoutException");
        assertThat(summary(report)).containsEntry("runtime_errors",1);
        assertThat(((Map<?,?>)summary(report).get("usage")).get("unknown_entries")).isEqualTo(1L);
        assertThat(report.toString()).doesNotContain("PRIVATE_SECRET");
    }
    @Test void reportOnlyExitDoesNotTurnMismatchIntoPass() throws Exception {
        var runner=new OfflineEvidenceJudge(null,TIMEOUT,false);
        var report=runner.apply(file(List.of(sample("wrong-expect","deterministic","答案 [1]",evidence(),"unanswered","no_evidence"))));
        assertThat(report).containsEntry("exit_code",0).containsEntry("result","mismatched");
        assertThat(summary(report)).containsEntry("matched",0).containsEntry("deterministic_mismatched",1);
        StringWriter out=new StringWriter();
        var port=new OperationsCliTest.SpyPort() { @Override public Map<String,Object> judge(Path path) {return report;} };
        assertThat(OperationsCli.run(new String[]{"judge","--cases","/tmp/fixed.json"},()->port,new PrintWriter(out),new PrintWriter(new StringWriter()))).isZero();
        assertThat(out.toString()).contains("deterministic_mismatched","\"matched\":false");
    }
    @Test void mixedReportSeparatesDeterministicModelMismatchErrorsAndSkips() throws Exception {
        var report = new OfflineEvidenceJudge((m,t)->{throw new IllegalStateException("PRIVATE_SECRET");},TIMEOUT,true)
                .apply(file(List.of(
                    sample("det-wrong","deterministic","答案 [1]",evidence(),"unanswered",null),
                    sample("model-failed","judge","答案 [1]",evidence(),"answered",null))));
        assertThat(summary(report)).containsEntry("deterministic_mismatched",1).containsEntry("model_mismatched",0)
                .containsEntry("runtime_errors",1).containsEntry("skipped",0);
        assertThat(((Map<?,?>)summary(report).get("usage")).get("total_tokens")).isNull();
        assertThat(report).containsEntry("exit_code",0).containsEntry("result","error");
        assertThat(report.toString()).doesNotContain("PRIVATE_SECRET");
    }
    @Test void modelQualityMismatchIsReportedSeparatelyFromRuntimeFailure() throws Exception {
        var report = new OfflineEvidenceJudge((m,t)->AgentChatModel.AgentReply.text(
                "{\"status\":\"answered\",\"reason\":\"supported\",\"supported\":true}"),TIMEOUT,true)
                .apply(file(List.of(sample("model-mismatch","judge","答案 [1]",evidence(),"unanswered","conflicting_evidence"))));
        assertThat(summary(report)).containsEntry("model_mismatched",1).containsEntry("runtime_errors",0)
                .containsEntry("matched",0);
        assertThat(report).containsEntry("result","mismatched").containsEntry("exit_code",0);
    }
    @Test void invalidFilesConfigurationAndUnsafeYamlFailBeforeModelCalls() throws Exception {
        assertThatThrownBy(()->new OfflineEvidenceJudge(null,Duration.ZERO,false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new OfflineEvidenceJudge(null,TIMEOUT,false).apply(Path.of("relative.yml"))).isInstanceOf(IllegalArgumentException.class);
        for(String raw:List.of("cases: []", "cases: [!!java.lang.ProcessBuilder {}]", "cases: [{name: x, name: y, expect_status: answered}]", "cases: [{name: x, mode: bad, expect_status: answered}]", "cases: [{name: x, expect_status: answered, evidence: {chunks: bad}}]")) {
            Path p=temp.resolve(UUID.randomUUID()+".yml");Files.writeString(p,raw);
            assertThatThrownBy(()->new OfflineEvidenceJudge(null,TIMEOUT,false).apply(p)).isInstanceOf(IllegalArgumentException.class);
        }
        Path p=file(List.of(sample("model-not-wired","judge","答案 [1]",evidence(),"answered",null)));
        assertThatThrownBy(()->new OfflineEvidenceJudge(null,TIMEOUT,true).apply(p)).isInstanceOf(IllegalStateException.class);
    }
}
