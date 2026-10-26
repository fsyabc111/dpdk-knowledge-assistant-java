package io.dpdk.assistant.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashSet;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** FastAPI业务路由只接受显式方法；文档HEAD和CORS预检单独保留。 */
@Configuration
public class ExplicitHttpMethods implements WebMvcConfigurer {
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;
    public ExplicitHttpMethods(@Qualifier("requestMappingHandlerMapping")
            ObjectProvider<RequestMappingHandlerMapping> mappings) { this.mappings = mappings; }

    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                    Object handler) throws Exception {
                String method = request.getMethod();
                if (!(method.equals("HEAD") || method.equals("OPTIONS"))
                        || CorsUtils.isPreFlightRequest(request)) return true;
                var allowed = new LinkedHashSet<String>();
                mappings.getObject().getHandlerMethods().forEach((info, target) -> {
                    var paths = info.getPathPatternsCondition();
                    if (paths == null || paths.getMatchingCondition(request) == null) return;
                    info.getMethodsCondition().getMethods().forEach(value -> allowed.add(value.name()));
                    if (target.getBeanType() == FrozenDocumentationController.class) allowed.add("HEAD");
                });
                if (!allowed.isEmpty() && !allowed.contains(method))
                    throw new HttpRequestMethodNotSupportedException(method, allowed);
                return true;
            }
        });
    }
}
