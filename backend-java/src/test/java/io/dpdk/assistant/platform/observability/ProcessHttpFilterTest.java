package io.dpdk.assistant.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;
import io.dpdk.assistant.operations.OperationsMetrics;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

/** 真实Filter公开边界；源HTTP只量Content-Length，不读取/复制正文，SSE完成才记一次。 */
class ProcessHttpFilterTest {
    @Test void formalEndpointNeverReadsPersistentGaugeProvider() {
        var registry=new ProcessMetricsRegistry(()->1000);
        registry.llm("llama_index","success",.2);
        String text=new OperationsMetrics.Endpoint(new OperationsMetrics(registry),null).metrics();
        assertThat(text).contains("# TYPE assistant_llm_requests_total counter\n","# TYPE assistant_rag_outcomes_total counter\n")
                .doesNotContain("persisted_","domain_metric_available");
    }
    @Test void observesMatchedTemplateStatusAndHeaderLengthsWithoutReadingRequestBody() throws Exception {
        var registry=new ProcessMetricsRegistry(()->1000);var filter=new OperationsMetrics(registry);
        var request=new MockHttpServletRequest("POST","/api/tasks/PRIVATE_USER_ID") {
            @Override public ServletInputStream getInputStream() { throw new AssertionError("metrics must never read body"); }
        };
        request.addHeader("Content-Length","9");request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/api/tasks/{task_id}");
        var response=new MockHttpServletResponse();response.setHeader("Content-Length","7");
        filter.doFilter(request,response,(r,s)->((MockHttpServletResponse)s).setStatus(201));
        assertThat(registry.renderPrometheus()).contains("http_requests_total{method=\"POST\",status=\"2xx\",handler=\"/api/tasks/{task_id}\"} 1.0\n",
                "http_request_size_bytes_sum{handler=\"/api/tasks/{task_id}\"} 9.0\n","http_response_size_bytes_sum{handler=\"/api/tasks/{task_id}\"} 7.0\n")
                .doesNotContain("PRIVATE_USER_ID");
    }
    @Test void untemplatedMissingLengthAndAsyncSseAreObservedOnlyAtCompletion() throws Exception {
        var registry=new ProcessMetricsRegistry(()->1000);var filter=new OperationsMetrics(registry);
        var request=new MockHttpServletRequest("GET","/api/chat/stream");request.setAsyncSupported(true);
        request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE,"/api/chat/stream");
        var response=new MockHttpServletResponse();response.setContentType("text/event-stream");
        filter.doFilter(request,response,(r,s)->request.startAsync(r,s));
        assertThat(registry.renderPrometheus()).doesNotContain("http_requests_total{method=\"GET\"");
        MockAsyncContext async=(MockAsyncContext)request.getAsyncContext();
        var event=new AsyncEvent(async,request,response);
        for(AsyncListener listener:async.getListeners())listener.onComplete(event);
        for(AsyncListener listener:async.getListeners())listener.onComplete(event);
        assertThat(registry.renderPrometheus()).contains("http_requests_total{method=\"GET\",status=\"2xx\",handler=\"/api/chat/stream\"} 1.0\n",
                "http_response_size_bytes_sum{handler=\"/api/chat/stream\"} 0.0\n");
    }
    @Test void asyncErrorAfterCommittedHeadersKeepsObservedStatusAndDoesNotDoubleCount() throws Exception {
        var registry=new ProcessMetricsRegistry(()->1000);var filter=new OperationsMetrics(registry);
        var request=new MockHttpServletRequest("GET","/PRIVATE_UNMATCHED");request.setAsyncSupported(true);
        var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(r,s)->request.startAsync(r,s));
        response.flushBuffer();
        MockAsyncContext async=(MockAsyncContext)request.getAsyncContext();var event=new AsyncEvent(async,request,response,new IOException("synthetic disconnect"));
        for(AsyncListener listener:async.getListeners())listener.onError(event);
        assertThat(registry.renderPrometheus()).doesNotContain("http_requests_total{method=\"GET\"");
        for(AsyncListener listener:async.getListeners())listener.onComplete(event);
        assertThat(registry.renderPrometheus()).contains("http_requests_total{method=\"GET\",status=\"2xx\",handler=\"none\"} 1.0\n")
                .doesNotContain("PRIVATE_UNMATCHED");
    }
    @Test void synchronousExceptionRecordsFailureOnceWithoutInventingHeaderSizes() throws Exception {
        var registry=new ProcessMetricsRegistry(()->1000);var filter=new OperationsMetrics(registry);
        var request=new MockHttpServletRequest("GET","/error");var response=new MockHttpServletResponse();
        try {filter.doFilter(request,response,(r,s)->{throw new IOException("synthetic");});}
        catch(IOException expected) { }
        assertThat(registry.renderPrometheus()).contains("http_requests_total{method=\"GET\",status=\"5xx\",handler=\"none\"} 1.0\n",
                "http_request_size_bytes_sum{handler=\"none\"} 0.0\n","http_response_size_bytes_sum{handler=\"none\"} 0.0\n");
    }
}
