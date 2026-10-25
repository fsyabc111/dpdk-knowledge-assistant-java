package io.dpdk.assistant.chat;

import io.dpdk.assistant.agent.AgentSettings;
import io.dpdk.assistant.auth.AuthPrincipalResolver;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.Map;

/** 正式 /api/chat* 产品入口；认证→手工解析→分钟限流→probe/受理。 */
@RestController
public class ChatController {
    private final ProductChatService product;
    private final AuthPrincipalResolver auth;
    private final AgentSettings settings;

    public ChatController(
            ProductChatService product, AuthPrincipalResolver auth, AgentSettings settings) {
        this.product = product;
        this.auth = auth;
        this.settings = settings;
    }

    @PostMapping("/api/chat")
    public Map<String, Object> chat(HttpServletRequest request) throws Exception {
        var principal = ChatHttp.authenticate(auth, request);
        var input = ChatRequestParser.parse(request, settings.gradingMaxCodeBytes());
        product.checkRateLimit(principal.subject());
        return ChatHttp.response(product.chat(principal, input));
    }

    @PostMapping("/api/chat/stream")
    public ResponseEntity<StreamingResponseBody> stream(HttpServletRequest request)
            throws Exception {
        var principal = ChatHttp.authenticate(auth, request);
        var input = ChatRequestParser.parse(request, settings.gradingMaxCodeBytes());
        product.checkRateLimit(principal.subject());
        return ChatHttp.stream(product.events(principal, input));
    }
}
