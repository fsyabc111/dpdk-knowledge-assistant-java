package io.dpdk.assistant.chat;

import static org.junit.jupiter.api.Assertions.*;
import io.dpdk.assistant.agent.*;
import io.dpdk.assistant.contracts.observability.ProcessMetrics;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** 真模型工具编排/PG检索/finish/重放的因果观测，不用静态metric字符串证明接线。 */
@EnabledIfEnvironmentVariable(named="DPDK_TEST_PG_URL",matches=".+")
class ProductProcessMetricsPgTest {
    @Test void actualProductEventsAndReplayDoNotDoubleCount() throws Exception {
        AgentChatModel model=(messages,names)->messages.stream().anyMatch(AgentMessage::isTool)
                ?new AgentChatModel.AgentReply("DNS 解析域名 [1]。",List.of(),new AgentUsage(10,2,12))
                :new AgentChatModel.AgentReply("",List.of(ToolCallSpec.of("metric-call","kb_search",Map.of("query","DNS"))),new AgentUsage(10,2,12));
        try(var h=ChatTestHarness.create("processmetrics",model,null)) {
            h.seedKnowledge("metrics-release","li_metrics_node","DNS 解析域名，核对字段边界。");
            var events=new CopyOnWriteArrayList<String>();
            ProcessMetrics metrics=new ProcessMetrics() {
                public void tool(String name,boolean ok,double seconds,int bytes) { assertEquals("kb_search",name);assertTrue(ok);assertTrue(bytes>0);events.add("tool"); }
                public void validation(String status,double seconds) { assertEquals("answered",status);events.add("validation"); }
                public void retrieval(double seconds) { assertTrue(seconds>=0);events.add("retrieval"); }
                public void retrievalStage(String name,double seconds) { events.add(name); }
                public void llm(String engine,String status,double seconds) { assertEquals("unknown",engine);assertEquals("success",status);events.add("llm"); }
                public void rag(String status,String reason,double seconds) {
                    assertEquals("answered",status);
                    try { assertEquals(1,h.task.countRows("rag_requests","status=?","answered")); }
                    catch(Exception failed) { throw new AssertionError(failed); }
                    events.add("rag");
                }
                public void agentTokens(long tokens) { assertEquals(24,tokens);events.add("tokens"); }
            };
            h.executor.setMetrics(metrics);h.product.setMetrics(metrics);
            var input=h.input("DNS 做什么");
            assertEquals("answered",h.product.chat(h.student,input).answerStatus());
            for(String event:List.of("tool","validation","rag","retrieval","rewrite","embedding","llm"))
                assertEquals(1,Collections.frequency(events,event),event);
            assertEquals(0,Collections.frequency(events,"tokens"));
            assertTrue(events.indexOf("rag")<events.indexOf("llm"));
            int count=events.size(),calls=h.modelCalls.get();
            h.product.chat(h.student,input);
            assertEquals(count,events.size());assertEquals(calls,h.modelCalls.get());
        }
    }
    @Test void generationExceptionCannotCountAsSuccess() throws Exception {
        try(var h=ChatTestHarness.create("processfailure",(messages,names)->{throw new IllegalStateException("synthetic model failure");},null)) {
            var outcomes=new CopyOnWriteArrayList<String>();
            h.executor.setMetrics(new ProcessMetrics() {
                public void llm(String engine,String outcome,double seconds) { assertEquals("unknown",engine);outcomes.add(outcome); }
            });
            assertEquals("system_error",h.product.chat(h.student,h.input("DNS")).answerStatus());
            assertEquals(List.of("error"),outcomes);
        }
    }
}
