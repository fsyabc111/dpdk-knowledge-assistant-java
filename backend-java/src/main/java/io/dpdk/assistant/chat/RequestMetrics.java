package io.dpdk.assistant.chat;

import io.dpdk.assistant.contracts.observability.ProcessMetrics;
import java.util.LinkedHashMap;
import java.util.Map;

/** 源RequestTrace观测：等candidate及迟到收据ack排空后发布一次，工具/校验按源即时记录。 */
final class RequestMetrics implements ProcessMetrics {
    private final ProcessMetrics target;
    private final Map<String,Double> stages = new LinkedHashMap<>();
    private boolean hasRetrieval, hasLlm, fallback, published;
    private double retrieval, generation;
    private String engine, result;
    private long tokens;
    RequestMetrics(ProcessMetrics target) { this.target=target; }
    @Override public synchronized void llm(String engine,String result,double seconds) {
        hasLlm=true; this.engine=engine; this.result=result; generation+=seconds;
    }
    @Override public synchronized void retrieval(double seconds) { hasRetrieval=true; retrieval+=seconds; }
    @Override public synchronized void retrievalStage(String stage,double seconds) { stages.merge(stage,seconds,Double::sum); }
    @Override public synchronized void fallback() { fallback=true; }
    @Override public synchronized void agentTokens(long value) { tokens+=value; }
    @Override public void tool(String tool,boolean ok,double seconds,int bytes) { target.tool(tool,ok,seconds,bytes); }
    @Override public void validation(String status,double seconds) { target.validation(status,seconds); }
    synchronized void publish() {
        if(published) return;
        published=true;
        if(hasRetrieval) target.retrieval(retrieval);
        stages.forEach(target::retrievalStage);
        if(hasLlm) target.llm(engine,result,generation);
        if(fallback) target.fallback();
        if(tokens>0) target.agentTokens(tokens);
    }
}
