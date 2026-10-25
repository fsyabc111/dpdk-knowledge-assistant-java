package io.dpdk.assistant.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.admin.AdminClassScopePort;
import io.dpdk.assistant.admin.AdminTreeRules;
import io.dpdk.assistant.admin.AdminUserRepository;
import io.dpdk.assistant.auth.AuthPrincipalResolver;
import io.dpdk.assistant.auth.AuthServerSettings;
import io.dpdk.assistant.auth.AuthSettingsProvider;
import io.dpdk.assistant.contracts.identity.AuthSettings;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.identity.RequestHeaders;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import io.dpdk.assistant.session.PydanticQuery;
import io.dpdk.assistant.session.SessionTime;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /api/usage/me、/api/usage/subjects、/api/usage/cost（对齐 backend/app/api/usage.py）。
 *
 * 门禁口径：/subjects 与 /cost 对非 admin/leader 一律 404（不泄露端点存在性）；
 * account 模式下老师（admin、非 leader-like）只看自己与自建学生（树隔离）；
 * 与自己无树关系的 subject 查询 404（不暗示「此人没花钱」）。
 * days 为滚动窗口（UTC 文本比较）；request_id 只收窄、仍受树隔离约束。
 * 求值序：认证 401 → 查询参数 422 → 门禁 404（FastAPI Depends 先于参数校验）。
 */
@RestController
public class UsageQueryController {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int RECENT_DAYS = 7;

    private final AuthSettingsProvider settings;
    private final AuthPrincipalResolver resolver;
    private final ClockPort clock;
    private final UsageCounterStore counters;
    private final UsageLedgerStore ledger;
    private final AdminUserRepository users;
    private final AdminClassScopePort classScope;

    public UsageQueryController(AuthSettingsProvider settings, AuthPrincipalResolver resolver,
                                ClockPort clock, UsageCounterStore counters,
                                UsageLedgerStore ledger, AdminUserRepository users,
                                AdminClassScopePort classScope) {
        this.settings = settings;
        this.resolver = resolver;
        this.clock = clock;
        this.counters = counters;
        this.ledger = ledger;
        this.users = users;
        this.classScope = classScope;
    }

    /* ------------------------------------------------------------------ */
    /* GET /api/usage/me                                                   */
    /* ------------------------------------------------------------------ */

    @GetMapping("/api/usage/me")
    public ResponseEntity<String> myUsage(HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (UsageCounterStore.CounterRow row : counters.fetchCounters(principal.subject())) {
            counts.put(row.day(), row.chatCount());
        }
        LocalDate today = LocalDate.ofInstant(clock.nowUtc(), ZoneOffset.UTC);
        List<Map<String, Object>> recent = new ArrayList<>();
        for (int offset = RECENT_DAYS - 1; offset >= 0; offset--) {
            String day = today.minusDays(offset).toString();
            recent.add(usageDay(day, counts.getOrDefault(day, 0)));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subject", principal.subject());
        body.put("roles", principal.roles());
        body.put("daily_quota", principal.dailyQuota());
        body.put("today", usageDay(today.toString(), counts.getOrDefault(today.toString(), 0)));
        body.put("recent_days", recent);
        return json(body);
    }

    /* ------------------------------------------------------------------ */
    /* GET /api/usage/subjects                                             */
    /* ------------------------------------------------------------------ */

    @GetMapping("/api/usage/subjects")
    public ResponseEntity<String> subjectUsage(HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        requireAdminOrLeader(principal);
        AuthServerSettings current = settings.current();
        LocalDate todayDate = LocalDate.ofInstant(clock.nowUtc(), ZoneOffset.UTC);
        String today = todayDate.toString();

        Map<String, Stat> stats = new LinkedHashMap<>();
        for (UsageCounterStore.CounterRow row : counters.fetchCounters(null)) {
            Stat stat = stats.computeIfAbsent(row.subject(), k -> new Stat());
            stat.total += row.chatCount();
            if (today.equals(row.day())) {
                stat.today += row.chatCount();
            }
            if (stat.last == null || row.day().compareTo(stat.last) > 0) {
                stat.last = row.day();
            }
        }

        Map<String, Spec> specs = new LinkedHashMap<>();
        if ("local".equals(current.authMode())) {
            // local 模式只有单一隐式主体，历史遗留 subject 不并入。
            specs.put("local", new Spec(new LinkedHashSet<>(List.of("admin")), 0));
            stats = Map.of("local", stats.getOrDefault("local", new Stat()));
        } else if (current.accountMode()) {
            for (AdminUserRepository.UserRow user : users.listUsers(null)) {
                specs.put(user.id(), new Spec(new LinkedHashSet<>(user.roles()),
                        user.dailyQuota() == null ? current.llmDailyQuotaDefault()
                                : user.dailyQuota()));
            }
            for (String subject : stats.keySet()) {
                specs.putIfAbsent(subject,
                        new Spec(new LinkedHashSet<>(), current.llmDailyQuotaDefault()));
            }
        } else {
            // token 模式：静态 token 合并（roles 并集；配额取 max，0=不限优先）。
            Map<String, List<Principal>> bySubject = new LinkedHashMap<>();
            for (Principal token : current.staticTokens().values()) {
                bySubject.computeIfAbsent(token.subject(), k -> new ArrayList<>()).add(token);
            }
            for (Map.Entry<String, List<Principal>> entry : bySubject.entrySet()) {
                Set<String> roles = new LinkedHashSet<>();
                List<Integer> quotas = new ArrayList<>();
                for (Principal token : entry.getValue()) {
                    roles.addAll(token.roles());
                    quotas.add(token.dailyQuota());
                }
                int quota = quotas.contains(0) ? 0 : quotas.stream().mapToInt(Integer::intValue)
                        .max().orElse(0);
                specs.put(entry.getKey(), new Spec(roles, quota));
            }
            for (String subject : stats.keySet()) {
                specs.putIfAbsent(subject,
                        new Spec(new LinkedHashSet<>(), current.llmDailyQuotaDefault()));
            }
        }

        List<Map<String, Object>> subjects = new ArrayList<>();
        for (Map.Entry<String, Spec> entry : specs.entrySet()) {
            Stat stat = stats.get(entry.getKey());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("subject", entry.getKey());
            item.put("roles", sortedRoles(entry.getValue().roles()));
            item.put("daily_quota", entry.getValue().dailyQuota());
            item.put("today_count", stat == null ? 0 : stat.today);
            item.put("total_count", stat == null ? 0 : stat.total);
            item.put("last_active_day", stat == null ? null : stat.last);
            subjects.add(item);
        }
        if (current.accountMode() && !AdminTreeRules.isLeaderLike(settings, principal)) {
            // 树隔离：老师只看自己与自己创建的学生。
            Set<String> visible = new LinkedHashSet<>();
            visible.add(principal.subject());
            visible.addAll(classScope.studentIds(principal.subject()));
            subjects = subjects.stream()
                    .filter(item -> visible.contains(item.get("subject"))).toList();
        }
        subjects.sort((a, b) -> {
            int byToday = Integer.compare((int) b.get("today_count"), (int) a.get("today_count"));
            return byToday != 0 ? byToday
                    : ((String) a.get("subject")).compareTo((String) b.get("subject"));
        });
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("subjects", subjects);
        return json(body);
    }

    /* ------------------------------------------------------------------ */
    /* GET /api/usage/cost                                                 */
    /* ------------------------------------------------------------------ */

    @GetMapping("/api/usage/cost")
    public ResponseEntity<String> costUsage(
            @RequestParam(value = "days", required = false) String daysRaw,
            @RequestParam(value = "subject", required = false) String subjectRaw,
            @RequestParam(value = "stage", required = false) String stageRaw,
            @RequestParam(value = "request_id", required = false) String requestIdRaw,
            HttpServletRequest request) throws Exception {
        Principal principal = authenticate(request);
        int days = PydanticQuery.intParam("days", daysRaw, 7, 1, 365);
        String subject = PydanticQuery.stringParam("subject", subjectRaw, 128);
        String stage = PydanticQuery.stringParam("stage", stageRaw, 64);
        String requestId = PydanticQuery.stringParam("request_id", requestIdRaw, 128);
        requireAdminOrLeader(principal);

        AuthServerSettings current = settings.current();
        List<String> visible = null;
        if (current.accountMode() && !AdminTreeRules.isLeaderLike(settings, principal)) {
            Set<String> allowed = new LinkedHashSet<>();
            allowed.add(principal.subject());
            allowed.addAll(classScope.studentIds(principal.subject()));
            if (subject != null && !subject.isEmpty()) {
                if (!allowed.contains(subject)) {
                    // 与自己无树关系：不透露存在性，也不返回空账暗示「此人没花钱」。
                    throw new ApiDetailException(HttpStatus.NOT_FOUND, "not found");
                }
            } else {
                visible = new ArrayList<>(allowed);
                visible.sort(String::compareTo);
            }
        }
        String since = SessionTime.iso(clock.nowUtc().minus(Duration.ofDays(days)));
        Map<String, Object> summary = ledger.query(since, emptyToNull(subject), visible,
                emptyToNull(stage), emptyToNull(requestId));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("days", days);
        body.put("since", since);
        body.put("subject", emptyToNull(subject));
        body.put("subjects", visible);
        body.put("stage", emptyToNull(stage));
        body.put("request_id", emptyToNull(requestId));
        body.putAll(summary);
        return json(body);
    }

    /* ------------------------------------------------------------------ */
    /* 内部                                                                */
    /* ------------------------------------------------------------------ */

    private static final class Stat {

        int total;
        int today;
        String last;
    }

    private record Spec(Set<String> roles, int dailyQuota) {
    }

    private static List<String> sortedRoles(Set<String> roles) {
        return roles.stream().sorted().toList();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** 门禁：非 admin/leader → 404（不泄露端点存在性，与 /subjects 同口径）。 */
    private static void requireAdminOrLeader(Principal principal) {
        if (!principal.roles().contains("admin") && !principal.roles().contains("leader")) {
            throw new ApiDetailException(HttpStatus.NOT_FOUND, "not found");
        }
    }

    private static Map<String, Object> usageDay(String day, int chatCount) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", day);
        out.put("chat_count", chatCount);
        return out;
    }

    private Principal authenticate(HttpServletRequest request) {
        return resolver.resolve(requestHeaders(request), contractSettings());
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

    private static AuthSettings contractSettings() {
        return new AuthSettings();
    }
}
