package io.dpdk.assistant.template;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.auth.AuthPrincipalResolver;
import io.dpdk.assistant.contracts.identity.AuthSettings;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.identity.RequestHeaders;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/plugin-template（对齐 backend/app/api/templates.py）。
 *
 * 认证依赖先于请求体校验（FastAPI Depends 求值序，golden unauthorized 以空体
 * 得 401 为准）；生成不触发任何模型调用（模板为静态骨架，非自动评测）。
 */
@RestController
public class TemplateController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuthPrincipalResolver resolver;

    public TemplateController(AuthPrincipalResolver resolver) {
        this.resolver = resolver;
    }

    @PostMapping("/api/plugin-template")
    public ResponseEntity<String> pluginTemplate(@RequestBody(required = false) String rawBody,
                                                 HttpServletRequest request) throws Exception {
        Principal principal = resolver.resolve(requestHeaders(request), contractSettings());
        TemplateBody.Parsed parsed = TemplateBody.parse(rawBody);
        TemplateService.TemplateResult result = TemplateService.generate(parsed.protocol());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("template", result.template());
        body.put("explanation", result.explanation());
        body.put("checklist", result.checklist());
        body.put("references", result.references());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(MAPPER.writeValueAsString(body));
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
