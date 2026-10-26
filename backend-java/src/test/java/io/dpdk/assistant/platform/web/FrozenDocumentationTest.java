package io.dpdk.assistant.platform.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** 文档真实字节与冻结FastAPI视图对照；HEAD不返回正文。 */
class FrozenDocumentationTest {
    @Test void allGetAndHeadViewsMatchFrozenPython() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new FrozenDocumentationController())
                .addFilters(new SecurityHeaders()).build();
        var mapper = new ObjectMapper();
        Path root = Path.of("..").toAbsolutePath().normalize();
        var golden = mapper.readTree(Files.readString(root.resolve("migration/fixtures/p0-v2/python-golden.json")));
        for (String route : new String[]{"/openapi.json", "/docs", "/docs/oauth2-redirect", "/redoc"}) {
            var response = mvc.perform(get(route).header("Accept", "*/*")).andReturn().getResponse();
            assertEquals(200, response.getStatus(), route);
            var expected = java.util.stream.StreamSupport.stream(golden.path("observations").spliterator(), false)
                    .filter(row -> row.path("case_id").asText().equals("GET " + route + "::success"))
                    .findFirst().orElseThrow().path("body");
            byte[] bytes = response.getContentAsByteArray();
            if (route.equals("/openapi.json")) assertEquals(expected, mapper.readTree(bytes));
            else {
                assertEquals(expected.path("sha256").asText(), java.util.HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(bytes)), route);
                assertEquals(expected.path("length").asInt(), response.getContentAsString().length(), route);
            }
            var head = mvc.perform(head(route)).andReturn().getResponse();
            assertEquals(200, head.getStatus(), route);
            assertEquals(0, head.getContentAsByteArray().length, route);
            assertEquals(bytes.length, head.getContentLength(), route);
            assertEquals(response.getContentType(), head.getContentType(), route);
            assertEquals("nosniff", head.getHeader("X-Content-Type-Options"));
        }
    }
}
