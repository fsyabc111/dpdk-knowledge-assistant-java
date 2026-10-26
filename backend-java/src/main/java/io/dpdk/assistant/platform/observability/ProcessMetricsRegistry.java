package io.dpdk.assistant.platform.observability;

import io.dpdk.assistant.contracts.observability.ProcessMetrics;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.DoubleSupplier;

/** 正式进程级指标：源counter/histogram/summary；不查询或回放持久账本。由共享wiring唯一装配。 */
public final class ProcessMetricsRegistry implements ProcessMetrics {
    private static final double[] DEFAULT = {.005,.01,.025,.05,.075,.1,.25,.5,.75,1,2.5,5,7.5,10};
    private static final double[] RAG = {1,5,10,20,30,60,120,240};
    private static final double[] VALIDATION = {1,5,10,20,30};
    private static final double[] TOOL_DURATION = {.05,.25,1,5,15,30,60,120,240};
    private static final double[] TOOL_BYTES = {256,1024,4096,16384,65536,262144};
    private static final double[] TOKENS = {1000,4000,16000,32000,48000,64000,96000};
    private static final double[] HTTP_HIGH = {.01,.025,.05,.075,.1,.25,.5,.75,1,1.5,2,2.5,3,3.5,4,4.5,5,7.5,10,30,60};
    private static final double[] HTTP_LOW = {.1,.5,1};
    private static final Set<String> TOOLS=Set.of("kb_search","list_protocols","list_cases","plugin_template","grade_plugin","get_task_status","get_reference_answer");
    private final DoubleSupplier epochSeconds;
    private final List<Family> domain = new ArrayList<>();
    private final List<Family> http = new ArrayList<>();
    private final Family llmRequests, llmDuration, retrieval, retrievalStage, fallbacks, rateLimits;
    private final Family ragOutcomes, ragDuration, validation, toolCalls, toolDuration, toolBytes, agentTokens;
    private final Family usageTokens, usageCost, usageUnknown;
    private final Family httpRequests, httpIn, httpOut, httpHigh, httpLow;
    private final JvmRuntimeMetrics runtime = new JvmRuntimeMetrics();

    public ProcessMetricsRegistry() { this(() -> System.currentTimeMillis()/1000.0); }
    /** 指标创建时钟与业务ClockPort分离，不消费冻结业务逻辑时钟。 */
    public ProcessMetricsRegistry(DoubleSupplier epochSeconds) {
        this.epochSeconds = epochSeconds;
        // 领域注册顺序对齐backend/app/core/metrics.py；有标签family尚无真实事件时不创建child。
        llmRequests = counter(domain,"assistant_llm_requests_total","LLM answer-generation calls by engine and final result.","engine","result");
        llmDuration = histogram(domain,"assistant_llm_request_duration_seconds","End-to-end LLM answer-generation latency including retries.",DEFAULT,"engine");
        retrieval = histogram(domain,"assistant_chat_retrieval_duration_seconds","Chat retrieval latency reported by the request trace.",DEFAULT);
        retrievalStage = histogram(domain,"assistant_chat_stage_duration_seconds","Per-stage retrieval latency (rewrite/embedding) reported by the request trace.",DEFAULT,"stage");
        fallbacks = counter(domain,"assistant_chat_fallbacks_total","Chat requests that ended in a degraded fallback answer.");
        rateLimits = counter(domain,"assistant_rate_limit_hits_total","Rejected chat requests by limiter kind (per_minute / daily_quota).","kind");
        ragOutcomes = counter(domain,"assistant_rag_outcomes_total","RAG 校验后结果","status","reason");
        ragDuration = histogram(domain,"assistant_rag_duration_seconds","RAG 完整答案耗时",RAG);
        validation = histogram(domain,"assistant_rag_validation_duration_seconds","RAG 证据校验耗时（按结论分类）",VALIDATION,"result");
        toolCalls = counter(domain,"assistant_agent_tool_calls_total","Agent 工具调用次数（按工具与成败）","tool","ok");
        toolDuration = histogram(domain,"assistant_agent_tool_duration_seconds","Agent 工具调用耗时",TOOL_DURATION,"tool");
        toolBytes = histogram(domain,"assistant_agent_tool_result_bytes","Agent 工具结果 JSON 体积（源len(json.dumps ensure_asciiFalse)，单位Unicode码点，非UTF-8字节）",TOOL_BYTES,"tool");
        agentTokens = histogram(domain,"assistant_agent_request_tokens","Agent 单请求累计 tokens（planner+finalize 的 usage_metadata 合计）",TOKENS);
        usageTokens = counter(domain,"assistant_upstream_usage_tokens_total","上游 token 用量（按阶段/种类/来源；只统计上游确实返回值的那部分）","stage","kind","source");
        usageCost = counter(domain,"assistant_upstream_usage_cost_micros_total","上游成本（整数微单位，按币种分列；未定价的模型不计入）","stage","kind","model","currency");
        usageUnknown = counter(domain,"assistant_upstream_usage_unknown_total","调了上游但没拿到用量的条目数——这个计数器不为 0 就说明账面不完整","stage","kind");
        // 冻结prometheus-fastapi-instrumentator 7.0.2的五个真实HTTP观测。
        httpRequests = counter(http,"http_requests_total","Total number of requests by method, status and handler.","method","status","handler");
        httpIn = summary(http,"http_request_size_bytes","Content length of incoming requests by handler. Only value of header is respected. Otherwise ignored. No percentile calculated.","handler");
        httpOut = summary(http,"http_response_size_bytes","Content length of outgoing responses by handler. Only value of header is respected. Otherwise ignored. No percentile calculated.","handler");
        httpHigh = histogram(http,"http_request_duration_highr_seconds","Latency with many buckets but no API specific labels.",HTTP_HIGH);
        httpLow = histogram(http,"http_request_duration_seconds","Latency with only few buckets by handler.",HTTP_LOW,"method","handler");
    }

    @Override public void llm(String engine,String result,double seconds) {
        llmRequests.add(1,engine,result); llmDuration.observe(nonnegative(seconds),engine);
    }
    @Override public void retrieval(double seconds) { retrieval.observe(nonnegative(seconds)); }
    @Override public void retrievalStage(String stage,double seconds) { retrievalStage.observe(nonnegative(seconds),stage); }
    @Override public void fallback() { fallbacks.add(1); }
    @Override public void rateLimit(String kind) { rateLimits.add(1,kind); }
    @Override public void rag(String status,String reason,double seconds) {
        ragOutcomes.add(1,status,reason); ragDuration.observe(nonnegative(seconds));
    }
    @Override public void validation(String result,double seconds) { validation.observe(nonnegative(seconds),result); }
    @Override public void tool(String tool,boolean ok,double seconds,int resultCodePoints) {
        String name=TOOLS.contains(tool==null?"":tool)?tool:"unknown";
        toolCalls.add(1,name,Boolean.toString(ok));toolDuration.observe(nonnegative(seconds),name);toolBytes.observe(Math.max(resultCodePoints,0),name);
    }
    @Override public void agentTokens(long tokens) { if(tokens>0)agentTokens.observe(tokens); }
    @Override public void usage(UsageSample sample) {
        if(sample==null)return;
        String stage=label(sample.stage()),kind=label(sample.kind()),source=label(sample.source());
        if(sample.totalTokens()!=null&&sample.totalTokens()>0)usageTokens.add(sample.totalTokens(),stage,kind,source);
        if("unknown".equals(source))usageUnknown.add(1,stage,kind);
        if(sample.costMicros()!=null&&sample.costMicros()>=0&&sample.currency()!=null&&!sample.currency().isEmpty())
            usageCost.add(sample.costMicros(),stage,kind,label(sample.model()),label(sample.currency()));
    }
    /** 参数为已实际观察的请求/响应Content-Length，缺失0；duration在完整异步流完成时结束。 */
    public void http(String method,int status,String handler,long requestLength,long responseLength,double seconds) {
        String verb=method==null||!method.matches("[A-Z]{1,16}")?"OTHER":method;
        String route=handler==null||handler.isBlank()?"none":handler;
        if(route.length()>256||route.indexOf('\n')>=0||route.indexOf('\r')>=0)route="none";
        String grouped=(status>=100&&status<=599?status:500)/100+"xx";
        // handler来自Spring匹配模板而非用户URL；不使用普通label过滤，以保留合法/{variable}模板。
        httpRequests.observeLabels(new String[]{verb,grouped,route},1);
        httpIn.observeLabels(new String[]{route},Math.max(requestLength,0));
        httpOut.observeLabels(new String[]{route},Math.max(responseLength,0));
        httpHigh.observe(nonnegative(seconds));httpLow.observeLabels(new String[]{verb,route},nonnegative(seconds));
    }
    public String renderPrometheus() {
        StringBuilder out=new StringBuilder();runtime.append(out);
        for(Family family:domain)family.append(out);
        for(Family family:http)family.append(out);
        return out.toString();
    }
    private Family counter(List<Family> into,String name,String help,String...labels) { return register(into,new Family(name,help,"counter",new double[0],labels)); }
    private Family histogram(List<Family> into,String name,String help,double[] buckets,String...labels) { return register(into,new Family(name,help,"histogram",buckets,labels)); }
    private Family summary(List<Family> into,String name,String help,String...labels) { return register(into,new Family(name,help,"summary",new double[0],labels)); }
    private Family register(List<Family> into,Family family) { into.add(family);if(family.labels.length==0)family.child(new String[0]);return family; }
    private static double nonnegative(double value) { return Double.isFinite(value)?Math.max(value,0):0; }
    private static String label(String value) {
        if(value==null||value.isEmpty()||value.length()>128||value.chars().anyMatch(Character::isISOControl))return "unknown";
        return value;
    }
    static String escape(String text) { return text.replace("\\","\\\\").replace("\n","\\n").replace("\"","\\\""); }
    private static String numeric(double value) { return Double.toString(value); }

    private final class Family {
        final String name,help,type;final double[] buckets;final String[] labels;
        final Map<List<String>,Child> children=new LinkedHashMap<>();
        Family(String name,String help,String type,double[] buckets,String[] labels) {
            this.name=name;this.help=help;this.type=type;this.buckets=buckets.clone();this.labels=labels.clone();
        }
        synchronized Child child(String[] values) {
            if(values.length!=labels.length)throw new IllegalArgumentException("metric label cardinality");
            return children.computeIfAbsent(List.copyOf(Arrays.asList(values)),key->new Child(epochSeconds.getAsDouble(),buckets.length));
        }
        void add(double amount,String...values) {
            String[] clean=Arrays.stream(values).map(ProcessMetricsRegistry::label).toArray(String[]::new);
            child(clean).add(amount);
        }
        void observe(double value,String...values) {
            String[] clean=Arrays.stream(values).map(ProcessMetricsRegistry::label).toArray(String[]::new);
            observeLabels(clean,value);
        }
        void observeLabels(String[] values,double value) {
            Child child=child(values);
            if(type.equals("counter"))child.add(value);else child.observe(value,buckets);
        }
        void append(StringBuilder out) {
            List<Map.Entry<List<String>,Child>> snapshot;
            synchronized(this) { snapshot=new ArrayList<>(children.entrySet()); }
            out.append("# HELP ").append(name).append(' ').append(help.replace("\\","\\\\").replace("\n","\\n")).append('\n');
            out.append("# TYPE ").append(name).append(' ').append(type).append('\n');
            for(var entry:snapshot) {
                Snapshot sample=entry.getValue().snapshot();
                if(type.equals("counter"))sample(out,name,entry.getKey(),null,sample.total());
                else {
                    if(type.equals("histogram")) {
                        long cumulative=0;
                        for(int i=0;i<buckets.length;i++) { cumulative+=sample.bins()[i];sample(out,name+"_bucket",entry.getKey(),numeric(buckets[i]),cumulative); }
                        sample(out,name+"_bucket",entry.getKey(),"+Inf",sample.count());
                    }
                    sample(out,name+"_count",entry.getKey(),null,sample.count());
                    sample(out,name+"_sum",entry.getKey(),null,sample.total());
                }
            }
            if(!snapshot.isEmpty()) {
                String created=type.equals("counter")?name.substring(0,name.length()-"_total".length())+"_created":name+"_created";
                out.append("# HELP ").append(created).append(' ').append(help).append('\n');
                out.append("# TYPE ").append(created).append(" gauge\n");
                for(var entry:snapshot)sample(out,created,entry.getKey(),null,entry.getValue().created);
            }
        }
        void sample(StringBuilder out,String metric,List<String> values,String bound,double value) {
            out.append(metric);
            if(labels.length>0||bound!=null) {
                out.append('{');
                for(int i=0;i<labels.length;i++) { if(i>0)out.append(',');out.append(labels[i]).append("=\"").append(escape(values.get(i))).append('"'); }
                if(bound!=null) { if(labels.length>0)out.append(',');out.append("le=\"").append(bound).append('"'); }
                out.append('}');
            }
            out.append(' ').append(numeric(value)).append('\n');
        }
    }
    private record Snapshot(double total,long count,long[] bins) { }
    private static final class Child {
        final double created;final long[] bins;long count;double total;
        Child(double created,int buckets) { this.created=created;this.bins=new long[buckets]; }
        synchronized void add(double amount) { if(!Double.isFinite(amount)||amount<0)throw new IllegalArgumentException("counter increment");total+=amount; }
        synchronized void observe(double value,double[] bounds) {
            count++;total+=value;
            for(int i=0;i<bounds.length;i++)if(value<=bounds[i]) { bins[i]++;break; }
        }
        synchronized Snapshot snapshot() { return new Snapshot(total,count,bins.clone()); }
    }
}
