package io.dpdk.assistant.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 冻结HTTP文档契约的静态视图；不依赖Python运行时，也不另造Java框架默认schema。 */
@RestController
public class FrozenDocumentationController {
    private record Payload(String mediaType, byte[] bytes) {}
    private final Map<String, Payload> payloads = Map.of(
            "/openapi.json", load("openapi.json", "application/json"),
            "/docs", load("docs.html", "text/html; charset=utf-8"),
            "/docs/oauth2-redirect", load("oauth2-redirect.html", "text/html; charset=utf-8"),
            "/redoc", load("redoc.html", "text/html; charset=utf-8"));

    private static Payload load(String name, String mediaType) {
        try (var stream = FrozenDocumentationController.class.getResourceAsStream("/compat/frozen-docs/" + name)) {
            if (stream == null) { throw new IllegalStateException("冻结文档资源缺失"); }
            return new Payload(mediaType, stream.readAllBytes());
        } catch (IOException failed) { throw new IllegalStateException("冻结文档资源不可读", failed); }
    }
    @GetMapping({"/openapi.json", "/docs", "/docs/oauth2-redirect", "/redoc"})
    public ResponseEntity<byte[]> document(HttpServletRequest request) {
        var payload = payloads.get(request.getServletPath().isEmpty() ? request.getRequestURI() : request.getServletPath());
        return ResponseEntity.ok().header("Content-Type", payload.mediaType())
                .contentLength(payload.bytes().length)
                .body("HEAD".equals(request.getMethod()) ? new byte[0] : payload.bytes());
    }
}
