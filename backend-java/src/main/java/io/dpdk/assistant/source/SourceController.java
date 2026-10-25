package io.dpdk.assistant.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.auth.AuthPrincipalResolver;
import io.dpdk.assistant.contracts.identity.AuthSettings;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.identity.RequestHeaders;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/sources/{chunk_id}（对齐 backend/app/api/sources.py）。
 *
 * li_ 前缀走当前 LI 表（物理行不可变，不限定 release），其余为历史 legacy
 * knowledge_chunks 行（仅还原旧回答来源）；任一路径缺失统一 404
 * "source chunk not found"。无请求参数，认证 401 先行。
 */
@RestController
public class SourceController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuthPrincipalResolver resolver;
    private final SourceRepository sources;

    public SourceController(AuthPrincipalResolver resolver, SourceRepository sources) {
        this.resolver = resolver;
        this.sources = sources;
    }

    @GetMapping("/api/sources/{chunk_id}")
    public ResponseEntity<String> getSource(@PathVariable("chunk_id") String chunkId,
                                            HttpServletRequest request) throws Exception {
        Principal principal = resolver.resolve(requestHeaders(request), contractSettings());
        SourceRepository.SourceView chunk = sources.byChunkId(chunkId).orElse(null);
        if (chunk == null) {
            throw new ApiDetailException(HttpStatus.NOT_FOUND, "source chunk not found");
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(MAPPER.writeValueAsString(chunk.toBody()));
    }

    private static RequestHeaders requestHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (request != null) {
            var names = request.getHeaderNames();
            while (names != null && names.hasMoreElements()) {
                String name = names.nextElement();
                headers.put(name, request.getHeader(name));
            }
        }
        return new RequestHeaders(headers);
    }

    private static AuthSettings contractSettings() {
        return new AuthSettings();
    }
}
