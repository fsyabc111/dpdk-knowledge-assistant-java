package io.dpdk.assistant.platform.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 原中间件成功/拒绝、头白名单与普通OPTIONS隔离。 */
class CorsProtocolTest {
    @Test void starlettePreflightBytesAndHeaders() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FrozenDocumentationController())
                .addFilters(new SecurityHeaders(), new CorsConfig().corsFilter("http://127.0.0.1:5174")).build();
        var ok = mvc.perform(options("/api/chat").header("Origin", "http://127.0.0.1:5174")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type")).andReturn().getResponse();
        assertEquals(200, ok.getStatus()); assertEquals("OK", ok.getContentAsString());
        assertEquals("GET, POST, PATCH, DELETE", ok.getHeader("Access-Control-Allow-Methods"));
        assertEquals("Accept, Accept-Language, Authorization, Content-Language, Content-Type, X-Maintenance-Key",
                ok.getHeader("Access-Control-Allow-Headers"));
        assertNull(ok.getHeader("Allow")); assertEquals("nosniff", ok.getHeader("X-Content-Type-Options"));
        var bad = mvc.perform(options("/api/chat").header("Origin", "http://bad.invalid")
                .header("Access-Control-Request-Method", "POST")).andReturn().getResponse();
        assertEquals(400, bad.getStatus()); assertEquals("Disallowed CORS origin", bad.getContentAsString());
        assertNull(bad.getHeader("Access-Control-Allow-Origin"));
        var headers = mvc.perform(options("/api/chat").header("Origin", "http://127.0.0.1:5174")
                .header("Access-Control-Request-Method", "PUT").header("Access-Control-Request-Headers", "x-private"))
                .andReturn().getResponse();
        assertEquals(400, headers.getStatus()); assertEquals("Disallowed CORS method, headers", headers.getContentAsString());
    }
}
