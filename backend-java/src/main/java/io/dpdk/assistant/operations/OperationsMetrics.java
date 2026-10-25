package io.dpdk.assistant.operations;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

/** 实际 HTTP 请求统计。标签仅含固定 method/status，不含路径、用户、问题、源码或凭据。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OperationsMetrics extends OncePerRequestFilter {
    private record Key(String method, int status) { }
    private static final class Observation { final LongAdder count = new LongAdder(); final DoubleAdder seconds = new DoubleAdder(); }
    private final ConcurrentHashMap<Key, Observation> observations = new ConcurrentHashMap<>();
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long started = System.nanoTime();
        boolean failed = false;
        try { chain.doFilter(request, response); }
        catch (IOException | ServletException | RuntimeException e) { failed = true; throw e; }
        finally { record(request.getMethod(), failed ? 500 : response.getStatus(), (System.nanoTime() - started) / 1e9); }
    }
    /** 可独立验证的记录入口；SSE 统计 servlet dispatch 返回耗时，非完整流寿命。 */
    public void record(String method, int status, double seconds) {
        String safeMethod = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(method) ? method : "OTHER";
        int safeStatus = status >= 100 && status <= 599 ? status : 500;
        Observation observation = observations.computeIfAbsent(new Key(safeMethod, safeStatus), ignored -> new Observation());
        observation.count.increment(); observation.seconds.add(Math.max(0, seconds));
    }
    public String render(OperationsMetricsPort.Snapshot domain) {
        StringBuilder out = new StringBuilder("# TYPE assistant_java_http_requests_total counter\n# TYPE assistant_java_http_dispatch_duration_seconds summary\n");
        observations.entrySet().stream().sorted(java.util.Comparator.comparing(entry -> entry.getKey().method() + entry.getKey().status())).forEach(entry -> {
            String labels = "{method=\"" + entry.getKey().method() + "\",status=\"" + entry.getKey().status() + "\"}";
            out.append("assistant_java_http_requests_total").append(labels).append(' ').append(entry.getValue().count.sum()).append('\n');
            out.append("assistant_java_http_dispatch_duration_seconds_count").append(labels).append(' ').append(entry.getValue().count.sum()).append('\n');
            out.append("assistant_java_http_dispatch_duration_seconds_sum").append(labels).append(' ').append(entry.getValue().seconds.sum()).append('\n');
        });
        Set<String> declared = new java.util.HashSet<>();
        for (OperationsMetricsPort.Sample sample : domain.samples()) {
            if (!sample.name().matches("assistant_[a-zA-Z0-9_]+") || !Double.isFinite(sample.value())) continue;
            StringBuilder labels = new StringBuilder();
            new TreeMap<>(sample.labels()).forEach((key, value) -> {
                // 标签白名单排除任何业务身份/正文维度。
                if (!Set.of("stage", "kind", "source", "model", "currency", "engine", "result", "tool", "ok", "status", "reason").contains(key))
                    throw new IllegalArgumentException("指标标签不在白名单");
                if (!labels.isEmpty()) labels.append(',');
                labels.append(key).append("=\"").append(escape(value)).append('"');
            });
            // 快照是当前账本聚合，不谎称进程单调 counter。
            if (declared.add(sample.name())) out.append("# TYPE ").append(sample.name()).append(" gauge\n");
            out.append(sample.name());
            if (!labels.isEmpty()) out.append('{').append(labels).append('}');
            out.append(' ').append(sample.value()).append('\n');
        }
        out.append("# TYPE assistant_java_domain_metric_available gauge\n");
        for (String missing : domain.unavailable())
            out.append("assistant_java_domain_metric_available{metric=\"").append(escape(missing)).append("\"} 0\n");
        return out.toString();
    }
    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\""); }

    @RestController
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public static class Endpoint {
        private final OperationsMetrics http;
        private final ObjectProvider<OperationsMetricsPort> domain;
        public Endpoint(OperationsMetrics http, ObjectProvider<OperationsMetricsPort> domain) { this.http = http; this.domain = domain; }
        @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
        public String metrics() {
            OperationsMetricsPort provider = domain.getIfAvailable();
            return http.render(provider == null ? new OperationsMetricsPort.Snapshot(List.of(), List.of("domain_provider")) : provider.snapshot());
        }
    }
}
