package io.dpdk.assistant.platform.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 冻结Starlette CORS协议；预检由中间件处理，不进入业务映射。 */
@Configuration
public class CorsConfig {

    private static final Set<String> METHODS = Set.of("GET", "POST", "PATCH", "DELETE");
    private static final String HEADERS = "Accept, Accept-Language, Authorization, Content-Language, Content-Type, X-Maintenance-Key";
    private static final Set<String> HEADER_NAMES = Arrays.stream(HEADERS.split(", "))
            .map(value -> value.toLowerCase(java.util.Locale.ROOT)).collect(Collectors.toUnmodifiableSet());

    @Bean @Order(Ordered.HIGHEST_PRECEDENCE + 10)
    OncePerRequestFilter corsFilter(@Value("${dpdk.cors-origins:}") String rawOrigins) {
        Set<String> origins = Arrays.stream(rawOrigins.split(",")).map(String::trim)
                .filter(value -> !value.isEmpty()).collect(Collectors.toUnmodifiableSet());
        return new OncePerRequestFilter() {
            @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                    FilterChain chain) throws ServletException, IOException {
                String origin = request.getHeader("Origin");
                if (origin == null) { chain.doFilter(request, response); return; }
                boolean accepted = origins.contains(origin);
                response.setHeader("Access-Control-Allow-Credentials", "true");
                if (accepted) { response.setHeader("Access-Control-Allow-Origin", origin); response.addHeader("Vary", "Origin"); }
                String method = request.getHeader("Access-Control-Request-Method");
                if (!"OPTIONS".equals(request.getMethod()) || method == null) {
                    chain.doFilter(request, response); return;
                }
                response.setHeader("Vary", "Origin");
                response.setHeader("Access-Control-Allow-Methods", "GET, POST, PATCH, DELETE");
                response.setHeader("Access-Control-Allow-Headers", HEADERS);
                response.setHeader("Access-Control-Max-Age", "600");
                var failures = new java.util.ArrayList<String>();
                if (!accepted) failures.add("origin");
                if (!METHODS.contains(method)) failures.add("method");
                String requested = request.getHeader("Access-Control-Request-Headers");
                if (requested != null && Arrays.stream(requested.split(","))
                        .map(value -> value.trim().toLowerCase(java.util.Locale.ROOT))
                        .anyMatch(value -> !HEADER_NAMES.contains(value))) failures.add("headers");
                byte[] body = (failures.isEmpty() ? "OK" : "Disallowed CORS " + String.join(", ", failures))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                response.setStatus(failures.isEmpty() ? 200 : 400);
                response.setContentType("text/plain; charset=utf-8"); response.setContentLength(body.length);
                response.getOutputStream().write(body);
            }
        };
    }
}
