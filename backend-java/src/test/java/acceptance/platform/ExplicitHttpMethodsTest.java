package acceptance.platform;

import io.dpdk.assistant.platform.web.ExplicitHttpMethods;
import io.dpdk.assistant.platform.web.FrozenDocumentationController;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import io.dpdk.assistant.platform.errors.ApiExceptionHandler;

/** 真实MVC映射：拒绝隐式方法必须发生在业务调用前，未知路径仍404。 */
class ExplicitHttpMethodsTest {
    @Configuration @EnableWebMvc
    static class Config {
        @Bean Probe probe() { return new Probe(); }
        @Bean FrozenDocumentationController docs() { return new FrozenDocumentationController(); }
        @Bean ApiExceptionHandler errors() { return new ApiExceptionHandler(); }
        @Bean ExplicitHttpMethods explicit(org.springframework.beans.factory.ObjectProvider<
                org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping> mappings) {
            return new ExplicitHttpMethods(mappings);
        }
    }
    @RestController static class Probe {
        final AtomicInteger calls = new AtomicInteger();
        @GetMapping("/api/probe") String read() { calls.incrementAndGet(); return "ok"; }
    }
    @Test void rejectsImplicitHeadAndOptionsWithoutBusinessSideEffect() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext()); context.register(Config.class); context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            for (var method : new org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder[]{
                    head("/api/probe"), options("/api/probe")}) {
                var response = mvc.perform(method).andReturn().getResponse();
                assertEquals(405, response.getStatus()); assertEquals("GET", response.getHeader("Allow"));
            }
            assertEquals(0, context.getBean(Probe.class).calls.get());
            assertEquals(200, mvc.perform(head("/docs")).andReturn().getResponse().getStatus());
            assertEquals(404, mvc.perform(options("/missing")).andReturn().getResponse().getStatus());
        }
    }
}
