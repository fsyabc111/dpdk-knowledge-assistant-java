package io.dpdk.assistant.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.auth.AuthPrincipalResolver;
import io.dpdk.assistant.auth.AuthSettingsProvider;
import io.dpdk.assistant.contracts.identity.AuthSettings;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.identity.RequestHeaders;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import io.dpdk.assistant.source.SourceRepository;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/chat/sessions* 与 /api/internal/* 端点（对齐 backend/app/api/chat.py 的
 * sessions/internal 路由）。
 *
 * 求值序（FastAPI solve_dependencies：Depends 子依赖先于参数校验，golden 与
 * J2-01/J2-02 证据锁定）：认证 401 → 查询参数 422 → 业务 404。
 * internal 两端点无认证依赖，仅维护 key：查询参数 422 先于 key 校验；
 * 未配置 503、缺失/不符 403（secrets.compare_digest 恒时比较）；不进 OpenAPI
 * （include_in_schema=False，本仓未启 springdoc，语义以文档登记为准）。
 * dry_run 与 apply 分离：dry_run 只计数不删除。
 */
@RestController
public class SessionController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AuthSettingsProvider settings;
    private final AuthPrincipalResolver resolver;
    private final SessionService sessions;
    private final SourceRepository sources;
    private final MaintenanceSettings maintenance;

    public SessionController(AuthSettingsProvider settings, AuthPrincipalResolver resolver,
                             SessionService sessions, SourceRepository sources,
                             MaintenanceSettings maintenance) {
        this.settings = settings;
        this.resolver = resolver;
        this.sessions = sessions;
        this.sources = sources;
        this.maintenance = maintenance;
    }

    /* ------------------------------------------------------------------ */
    /* GET /api/chat/sessions                                              */
    /* ------------------------------------------------------------------ */

    @GetMapping("/api/chat/sessions")
    public ResponseEntity<String> listSessions(
            @RequestParam(value = "limit", required = false) String limitRaw,
            @RequestParam(value = "offset", required = false) String offsetRaw,
            HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        int limit = PydanticQuery.intParam("limit", limitRaw, 50, 1, 200);
        int offset = PydanticQuery.intParam("offset", offsetRaw, 0, 0, null);
        List<Map<String, Object>> out = new ArrayList<>();
        for (SessionRows.SessionRow row : sessions.listSessions(principal.subject(), limit, offset)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.id());
            item.put("title", row.title());
            item.put("updated_at", SessionTime.pythonStr(row.updatedAt()));
            out.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessions", out);
        return json(body);
    }

    /* ------------------------------------------------------------------ */
    /* GET /api/chat/sessions/{session_id}                                 */
    /* ------------------------------------------------------------------ */

    @GetMapping("/api/chat/sessions/{session_id}")
    public ResponseEntity<String> loadSession(
            @PathVariable("session_id") String sessionId,
            @RequestParam(value = "limit", required = false) String limitRaw,
            HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        int limit = PydanticQuery.intParam("limit", limitRaw, 100, 1, 200);
        SessionRows.SessionRow session = sessions.getSession(sessionId).orElse(null);
        if (session == null) {
            throw sessionNotFound();
        }
        // 非 local 模式只能读自己的会话；他人的 id 与缺失同形（不泄露存在性）。
        if (!"local".equals(settings.current().authMode())
                && !session.userId().equals(principal.subject())) {
            throw sessionNotFound();
        }
        List<SessionRows.MessageRow> all = sessions.getMessages(sessionId, true);
        boolean truncated = all.size() > limit;
        List<SessionRows.MessageRow> messages = all.subList(Math.max(0, all.size() - limit), all.size());
        Map<String, List<SessionRows.CitationRow>> citationsByMessage =
                sessions.getCitations(messages.stream().map(SessionRows.MessageRow::id).toList());

        Set<String> sourceKeys = new LinkedHashSet<>();
        for (List<SessionRows.CitationRow> citations : citationsByMessage.values()) {
            for (SessionRows.CitationRow citation : citations) {
                sourceKeys.add(citation.engine() + "\u0000" + citation.chunkId());
            }
        }
        Map<String, SourceRepository.SourceView> sourceMap = new LinkedHashMap<>();
        for (String key : sourceKeys) {
            String[] parts = key.split("\u0000", 2);
            sources.resolve(parts[0], parts[1]).ifPresent(v -> sourceMap.put(key, v));
        }

        List<Map<String, Object>> history = new ArrayList<>();
        for (SessionRows.MessageRow message : messages) {
            List<Map<String, Object>> citations = new ArrayList<>();
            for (SessionRows.CitationRow stored : citationsByMessage.getOrDefault(message.id(), List.of())) {
                SourceRepository.SourceView source =
                        sourceMap.get(stored.engine() + "\u0000" + stored.chunkId());
                Map<String, Object> citation = new LinkedHashMap<>();
                citation.put("chunk_id", stored.chunkId());
                citation.put("source_path", source == null ? "" : source.sourcePath());
                citation.put("title", source == null ? "" : source.title());
                citation.put("section", source == null ? "" : source.section());
                citation.put("score", stored.score());
                citations.add(citation);
            }
            Map<String, Object> metadata = message.metadata();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", message.id());
            item.put("role", message.role());
            item.put("content", message.content());
            item.put("citations", citations);
            item.put("confidence", metadata.containsKey("confidence")
                    ? metadata.get("confidence") : "medium");
            item.put("created_at", SessionTime.pythonStr(message.createdAt()));
            item.put("answer_status", metadata.get("answer_status"));
            item.put("answer_reason", metadata.get("answer_reason"));
            item.put("issue_id", metadata.get("issue_id"));
            item.put("request_id", metadata.get("request_id"));
            item.put("task_id", metadata.get("task_id"));
            history.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("session_id", sessionId);
        body.put("title", session.title());
        body.put("messages", history);
        body.put("truncated", truncated);
        return json(body);
    }

    /* ------------------------------------------------------------------ */
    /* DELETE /api/chat/sessions/{session_id}                              */
    /* ------------------------------------------------------------------ */

    @DeleteMapping("/api/chat/sessions/{session_id}")
    public ResponseEntity<Void> deleteSession(@PathVariable("session_id") String sessionId,
                                              HttpServletRequest request) {
        Principal principal = authenticate(request);
        String subject = "local".equals(settings.current().authMode()) ? null : principal.subject();
        if (!sessions.deleteSession(sessionId, subject)) {
            throw sessionNotFound();
        }
        return ResponseEntity.noContent().header("Content-Type", "application/json").build();
    }

    /* ------------------------------------------------------------------ */
    /* GET /api/internal/session-storage（维护 key；不进 OpenAPI）           */
    /* ------------------------------------------------------------------ */

    @GetMapping("/api/internal/session-storage")
    public ResponseEntity<String> sessionStorage(
            @RequestHeader(value = "x-maintenance-key", required = false) String maintenanceKey)
            throws Exception {
        checkMaintenanceKey(maintenanceKey);
        return json(sessions.storageUsage());
    }

    /* ------------------------------------------------------------------ */
    /* POST /api/internal/purge-sessions（dry-run 默认；不进 OpenAPI）       */
    /* ------------------------------------------------------------------ */

    @PostMapping("/api/internal/purge-sessions")
    public ResponseEntity<String> purgeSessions(
            @RequestParam(value = "retention_days", required = false) String retentionDaysRaw,
            @RequestParam(value = "dry_run", required = false) String dryRunRaw,
            @RequestHeader(value = "x-maintenance-key", required = false) String maintenanceKey)
            throws Exception {
        Integer retentionDays = PydanticQuery.optionalIntParam("retention_days", retentionDaysRaw, 1, null);
        boolean dryRun = PydanticQuery.boolParam("dry_run", dryRunRaw, true);
        checkMaintenanceKey(maintenanceKey);
        int days = retentionDays == null ? maintenance.sessionRetentionDays() : retentionDays;
        return json(sessions.purgeExpired(days, dryRun));
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                                */
    /* ------------------------------------------------------------------ */

    private Principal authenticate(HttpServletRequest request) {
        return resolver.resolve(requestHeaders(request), contractSettings());
    }

    /** _check_maintenance_key：未配置 503；缺失/不符 403（恒时比较）。 */
    private void checkMaintenanceKey(String provided) {
        String configured = maintenance.maintenanceApiKey();
        if (configured == null || configured.isEmpty()) {
            throw new ApiDetailException(HttpStatus.SERVICE_UNAVAILABLE,
                    "maintenance endpoint is not configured");
        }
        if (provided == null || provided.isEmpty()
                || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),
                configured.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiDetailException(HttpStatus.FORBIDDEN, "forbidden");
        }
    }

    private static ApiDetailException sessionNotFound() {
        return new ApiDetailException(HttpStatus.NOT_FOUND, "session not found");
    }

    private static ResponseEntity<String> json(Map<String, Object> body) throws Exception {
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

    /** 契约 AuthSettings 仍为占位（见 AuthPrincipalResolver 注释）。 */
    private static AuthSettings contractSettings() {
        return new AuthSettings();
    }
}
