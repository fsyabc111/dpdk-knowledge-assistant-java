package io.dpdk.assistant.operations;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OperationsMetricsTest {
    @Test void onlyActualHttpObservationsAreExportedAndLabelsAreBounded() {
        OperationsMetrics metrics = new OperationsMetrics();
        metrics.record("GET", 200, .25); metrics.record("GET", 200, .5); metrics.record("SECRET_METHOD", 900, -.5);
        String result = metrics.render(new OperationsMetricsPort.Snapshot(List.of(), List.of("assistant_rag_outcomes_total")));
        assertTrue(result.contains("assistant_java_http_requests_total{method=\"GET\",status=\"200\"} 2"));
        assertTrue(result.contains("assistant_java_http_dispatch_duration_seconds_sum{method=\"GET\",status=\"200\"} 0.75"));
        assertTrue(result.contains("method=\"OTHER\",status=\"500\""));
        assertFalse(result.contains("SECRET_METHOD")); assertFalse(result.contains("assistant_rag_outcomes_total 0"));
        assertTrue(result.contains("assistant_java_domain_metric_available{metric=\"assistant_rag_outcomes_total\"} 0"));
    }
    @Test void servletFilterRecordsActualResponseStatus() throws Exception {
        OperationsMetrics metrics = new OperationsMetrics();
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/synthetic");
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        metrics.doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(409));
        String result = metrics.render(new OperationsMetricsPort.Snapshot(List.of(), List.of()));
        assertTrue(result.contains("assistant_java_http_requests_total{method=\"POST\",status=\"409\"} 1"));
        assertFalse(result.contains("synthetic"));
    }
    @Test void snapshotsAreGaugesAndContentLabelsAreRejected() {
        OperationsMetrics metrics = new OperationsMetrics();
        String result = metrics.render(new OperationsMetricsPort.Snapshot(List.of(new OperationsMetricsPort.Sample(
                "assistant_upstream_usage_unknown_total", Map.of("stage", "answer"), 3)), List.of()));
        assertTrue(result.contains("# TYPE assistant_upstream_usage_unknown_total gauge"));
        assertTrue(result.contains("{stage=\"answer\"} 3.0"));
        assertThrows(IllegalArgumentException.class, () -> metrics.render(new OperationsMetricsPort.Snapshot(List.of(
                new OperationsMetricsPort.Sample("assistant_x", Map.of("question", "secret"), 1)), List.of())));
    }
}
