package io.dpdk.assistant.operations;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
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
import java.util.concurrent.atomic.AtomicBoolean;
import io.dpdk.assistant.platform.observability.ProcessMetricsRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

/** 正式源HTTP过程观测与旧诊断API；默认导出只使用ProcessMetricsRegistry。 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class OperationsMetrics extends OncePerRequestFilter {
    private record Key(String method, int status) { }
    private static final class Observation { final LongAdder count = new LongAdder(); final DoubleAdder seconds = new DoubleAdder(); }
    private final ConcurrentHashMap<Key, Observation> observations = new ConcurrentHashMap<>();
    private final ProcessMetricsRegistry process;
    /** 仅保留旧直接单测/诊断构造；Spring生产必须注入共享registry，不能静默回退NONE。 */
    public OperationsMetrics() { this(new ProcessMetricsRegistry()); }
    @Autowired public OperationsMetrics(ProcessMetricsRegistry process) { this.process=process; }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var observation=new RequestObservation(request,response,System.nanoTime());
        try { chain.doFilter(request,response); }
        catch(IOException|ServletException|RuntimeException failed) { observation.failed=true;throw failed; }
        finally {
            if(request.isAsyncStarted())observation.listen(request.getAsyncContext());
            else observation.complete();
        }
    }
    private final class RequestObservation implements AsyncListener {
        final HttpServletRequest request;final HttpServletResponse response;final long started;
        final AtomicBoolean completed=new AtomicBoolean();volatile boolean failed;
        RequestObservation(HttpServletRequest request,HttpServletResponse response,long started) {
            this.request=request;this.response=response;this.started=started;
        }
        void listen(AsyncContext context) {
            try { context.addListener(this); }
            catch(IllegalStateException alreadyCompleted) { complete(); }
        }
        void complete() {
            if(!completed.compareAndSet(false,true))return;
            int status=failed&&!response.isCommitted()&&response.getStatus()==200?500:response.getStatus();
            double seconds=Math.max(0,(System.nanoTime()-started)/1e9);
            record(request.getMethod(),status,seconds);
            Object route=request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            process.http(request.getMethod(),status,route==null?"none":route.toString(),
                    length(request.getHeader("Content-Length")),length(response.getHeader("Content-Length")),seconds);
        }
        public void onComplete(AsyncEvent event) { complete(); }
        public void onError(AsyncEvent event) { failed=true; }
        public void onTimeout(AsyncEvent event) { failed=true; }
        public void onStartAsync(AsyncEvent event) { listen(event.getAsyncContext()); }
    }
    private static long length(String header) {
        if(header==null)return 0;
        try { return Math.max(0,Long.parseLong(header)); }
        catch(NumberFormatException invalid) { return 0; }
    }
    /** 正式端点只导出真实进程观测，不查询持久snapshot或输出unavailable替代。 */
    public String renderProcess() { return process.renderPrometheus(); }
    /** 可独立验证的记录入口；SSE 统计 servlet dispatch 返回耗时，非完整流寿命。 */
    public void record(String method, int status, double seconds) {
        String safeMethod = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(method) ? method : "OTHER";
        int safeStatus = status >= 100 && status <= 599 ? status : 500;
        Observation observation = observations.computeIfAbsent(new Key(safeMethod, safeStatus), ignored -> new Observation());
        observation.count.increment(); observation.seconds.add(Math.max(0, seconds));
    }
    /** 旧持久账本诊断API；不是GET /metrics正式输出。 */
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
        @Autowired public Endpoint(OperationsMetrics http) { this.http=http; }
        /** 兼容历史直接测试构造；正式端点不读取持久provider。 */
        public Endpoint(OperationsMetrics http, ObjectProvider<OperationsMetricsPort> ignored) { this(http); }
        @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
        public String metrics() {
            return http.renderProcess();
        }
    }
}
