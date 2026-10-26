package io.dpdk.assistant.chat;

import static org.junit.jupiter.api.Assertions.*;
import io.dpdk.assistant.contracts.observability.ProcessMetrics;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;

/** 累计阶段在收尾后发布一次；工具/校验保持即时，重放不得再发观测。 */
class RequestMetricsTest {
    @Test void aggregateStagesAndPublishOnce() {
        var events=new ArrayList<String>();var stages=new HashMap<String,Double>();
        ProcessMetrics target=new ProcessMetrics() {
            public void retrieval(double seconds) { assertEquals(.9,seconds,1e-12);events.add("retrieval"); }
            public void retrievalStage(String stage,double seconds) { stages.put(stage,seconds); }
            public void llm(String engine,String result,double seconds) { assertEquals("llama_index",engine);events.add("llm"); }
            public void tool(String tool,boolean ok,double seconds,int bytes) { events.add("tool"); }
            public void validation(String status,double seconds) { events.add("validation"); }
            public void agentTokens(long tokens) { assertEquals(42,tokens);events.add("tokens"); }
        };
        var request=new RequestMetrics(target);
        request.retrieval(.4);request.retrieval(.5);
        request.retrievalStage("rewrite",.1);request.retrievalStage("rewrite",.2);
        request.llm("llama_index","success",1);request.agentTokens(42);
        request.tool("kb_search",true,.1,12);request.validation("answered",.01);
        assertEquals(List.of("tool","validation"),events);
        request.publish();request.publish();
        assertEquals(List.of("tool","validation","retrieval","llm","tokens"),events);
        assertEquals(.3,stages.get("rewrite"),1e-12);
    }
    @Test void cancellationValidationIsObservedWithoutClearingInterrupt() {
        var statuses=new ArrayList<String>();
        ProcessMetrics metrics=new ProcessMetrics() {
            public void validation(String status,double seconds) { statuses.add(status); }
        };
        try {
            AnswerValidator.validate("DNS","DNS [1]",Map.of("chunks",List.of(Map.of("ref",1,"content","DNS"))),
                    (q,a,e,t)->{throw new InterruptedException("synthetic");},Duration.ofSeconds(1),metrics);
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(List.of("cancelled"),statuses);
        } finally { Thread.interrupted(); }
    }
    @Test void minuteLimiterRecordsOnlyActualRejectedRequest() {
        var kinds=new ArrayList<String>();
        var service=new ProductChatService(null,null,null,null,ChatSettings.defaults(),new io.dpdk.assistant.auth.TestClock());
        service.setMetrics(new ProcessMetrics() { public void rateLimit(String kind) { kinds.add(kind); } });
        for(int i=0;i<ChatSettings.defaults().ratePerMinute();i++) service.checkRateLimit("synthetic");
        assertThrows(io.dpdk.assistant.platform.errors.ApiDetailException.class,()->service.checkRateLimit("synthetic"));
        assertEquals(List.of("per_minute"),kinds);
    }
}
