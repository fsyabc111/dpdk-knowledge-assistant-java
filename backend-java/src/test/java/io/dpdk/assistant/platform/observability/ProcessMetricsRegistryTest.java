package io.dpdk.assistant.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import io.dpdk.assistant.contracts.observability.ProcessMetrics.UsageSample;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** 只调用正式过程端口并解析公开Prometheus文本；不访问PG/模型或持久snapshot。 */
class ProcessMetricsRegistryTest {
    @Test void registrationDoesNotInventLabeledChildrenAndHasGenuineUnlabeledZeroState() {
        var metrics=new ProcessMetricsRegistry(()->1000);String text=metrics.renderPrometheus();
        assertThat(text).contains("# TYPE assistant_llm_requests_total counter\n","# TYPE assistant_llm_request_duration_seconds histogram\n",
                "# TYPE assistant_rag_outcomes_total counter\n","assistant_chat_fallbacks_total 0.0\n",
                "# TYPE assistant_chat_fallbacks_created gauge\n","assistant_chat_fallbacks_created 1000.0\n",
                "assistant_chat_retrieval_duration_seconds_bucket{le=\"+Inf\"} 0.0\n");
        assertThat(text).doesNotContain("assistant_llm_requests_total{","assistant_llm_requests_created",
                "assistant_rag_outcomes_total{","assistant_java_domain_metric_available","python_gc_","python_info");
        assertThat(text).contains("# TYPE jvm_gc_collections_total counter\n","# TYPE jvm_gc_collection_seconds_total counter\n",
                "# TYPE jvm_memory_used_bytes gauge\n","# TYPE jvm_info gauge\n");
    }
    @Test void realEventsIncrementCountersAndExactSourceBuckets() {
        var metrics=new ProcessMetricsRegistry(()->1000);
        metrics.llm("chat_model","success",.02);metrics.llm("chat_model","success",.006);
        metrics.retrieval(.05);metrics.retrievalStage("rewrite",.02);metrics.fallback();metrics.rateLimit("per_minute");
        metrics.rag("answered","supported",2);metrics.validation("answered",1);
        metrics.tool("grade_plugin",false,.15,1024);metrics.agentTokens(5000);metrics.agentTokens(0);
        String text=metrics.renderPrometheus();
        assertThat(text).contains("assistant_llm_requests_total{engine=\"chat_model\",result=\"success\"} 2.0\n",
                "assistant_llm_request_duration_seconds_bucket{engine=\"chat_model\",le=\"0.005\"} 0.0\n",
                "assistant_llm_request_duration_seconds_bucket{engine=\"chat_model\",le=\"0.01\"} 1.0\n",
                "assistant_llm_request_duration_seconds_bucket{engine=\"chat_model\",le=\"0.025\"} 2.0\n",
                "assistant_llm_request_duration_seconds_bucket{engine=\"chat_model\",le=\"+Inf\"} 2.0\n",
                "assistant_rag_duration_seconds_bucket{le=\"1.0\"} 0.0\n","assistant_rag_duration_seconds_bucket{le=\"5.0\"} 1.0\n",
                "assistant_agent_tool_calls_total{tool=\"grade_plugin\",ok=\"false\"} 1.0\n",
                "assistant_agent_tool_result_bytes_bucket{tool=\"grade_plugin\",le=\"1024.0\"} 1.0\n",
                "assistant_agent_request_tokens_count 1.0\n","assistant_agent_request_tokens_sum 5000.0\n");
    }
    @Test void usageRetainsUnknownAndPricingRulesWithoutReplayingStoredGauge() {
        var metrics=new ProcessMetricsRegistry(()->1000);
        metrics.usage(new UsageSample("answer","llm","upstream","synthetic","USD",21,7L));
        metrics.usage(new UsageSample("answer","llm","unknown","synthetic",null,null,null));
        metrics.usage(new UsageSample("answer","llm","cache_hit","synthetic","USD",0,0L));
        String text=metrics.renderPrometheus();
        assertThat(text).contains("assistant_upstream_usage_tokens_total{stage=\"answer\",kind=\"llm\",source=\"upstream\"} 21.0\n",
                "assistant_upstream_usage_cost_micros_total{stage=\"answer\",kind=\"llm\",model=\"synthetic\",currency=\"USD\"} 7.0\n",
                "assistant_upstream_usage_unknown_total{stage=\"answer\",kind=\"llm\"} 1.0\n");
        assertThat(text).doesNotContain("assistant_upstream_usage_tokens_total{stage=\"answer\",kind=\"llm\",source=\"unknown\"}",
                "assistant_upstream_usage_tokens_total{stage=\"answer\",kind=\"llm\",source=\"cache_hit\"}","persisted_","unavailable");
    }
    @Test void childCreationMetadataUsesFirstActualObservationTime() {
        AtomicLong epoch=new AtomicLong(1000);var metrics=new ProcessMetricsRegistry(()->epoch.get());
        epoch.set(2000);metrics.llm("chat_model","success",1);
        epoch.set(3000);metrics.llm("chat_model","success",1);metrics.llm("chat_model","error",1);
        assertThat(metrics.renderPrometheus()).contains("assistant_llm_requests_created{engine=\"chat_model\",result=\"success\"} 2000.0\n",
                "assistant_llm_requests_created{engine=\"chat_model\",result=\"error\"} 3000.0\n");
    }
    @Test void httpMatchesSourceGroupedStatusHeaderSizesAndHistogramBounds() {
        var metrics=new ProcessMetricsRegistry(()->1000);metrics.http("GET",200,"/api/items/{id}",4,8,.4);
        String text=metrics.renderPrometheus();
        assertThat(text).contains("http_requests_total{method=\"GET\",status=\"2xx\",handler=\"/api/items/{id}\"} 1.0\n",
                "http_request_size_bytes_sum{handler=\"/api/items/{id}\"} 4.0\n","http_response_size_bytes_sum{handler=\"/api/items/{id}\"} 8.0\n",
                "http_request_duration_seconds_bucket{method=\"GET\",handler=\"/api/items/{id}\",le=\"0.1\"} 0.0\n",
                "http_request_duration_seconds_bucket{method=\"GET\",handler=\"/api/items/{id}\",le=\"0.5\"} 1.0\n");
    }
    @Test void concurrentEventsDoNotLoseCountsAndUnsafeControlLabelsDoNotLeak() throws Exception {
        var metrics=new ProcessMetricsRegistry(()->1000);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<8;i++)futures.add(pool.submit(()->{for(int j=0;j<200;j++)metrics.fallback();}));
            for(var future:futures)future.get();
        }
        metrics.llm("SECRET\nCONTENT","success",0);
        assertThat(metrics.renderPrometheus()).contains("assistant_chat_fallbacks_total 1600.0\n","engine=\"unknown\"")
                .doesNotContain("SECRET","CONTENT");
    }
}
