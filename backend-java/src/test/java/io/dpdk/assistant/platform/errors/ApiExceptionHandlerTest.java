package io.dpdk.assistant.platform.errors;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** FastAPI 风格 {"detail": ...} 精确渲染（不使用 ProblemDetail；契约 http-and-sse.md）。 */
class ApiExceptionHandlerTest {

    private MockMvc mvc;

    @RestController
    static class ThrowingController {
        @GetMapping("/boom")
        String boom() {
            throw new ApiDetailException(HttpStatus.TOO_MANY_REQUESTS, "too many requests",
                    Map.of("Retry-After", "30"));
        }

        @GetMapping("/list")
        String list() {
            throw new ApiDetailException(HttpStatus.UNPROCESSABLE_ENTITY,
                    List.of(Map.of("loc", List.of("body"), "msg", "bad")));
        }

        @GetMapping("/obj")
        String obj() {
            throw new ApiDetailException(HttpStatus.UNAUTHORIZED, Map.of("code", "disabled"));
        }
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
                .setControllerAdvice(new ApiExceptionHandler())
                .build();
    }

    @Test
    void stringDetailWithHeaders() throws Exception {
        mvc.perform(get("/boom"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(content().json("{\"detail\":\"too many requests\"}"))
                .andExpect(content().string("{\"detail\":\"too many requests\"}"));
    }

    @Test
    void listDetailPreservedAsArray() throws Exception {
        mvc.perform(get("/list"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(content().json("{\"detail\":[{\"loc\":[\"body\"],\"msg\":\"bad\"}]}"));
    }

    @Test
    void objectDetailPreserved() throws Exception {
        mvc.perform(get("/obj"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"detail\":{\"code\":\"disabled\"}}"));
    }
}
