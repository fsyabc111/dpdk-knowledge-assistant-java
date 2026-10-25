package io.dpdk.assistant.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.session.J205TestHarness;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * J2-05 用量查询契约（验收 D/E）：golden /me /subjects /cost（空计数、空账本，
 * leader 视角、U2027 树）+ 行为分支：角色/班级范围、日期窗口、request_id 过滤、
 * 空账本与 unknown 一致、未定价不出价、非 token 单列、不泄露他班主体。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class UsageQueryContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Instant T0 = J205TestHarness.T0;

    // golden U2027 树（与 J2-02 同构：leader + 2 老师 + 5 学生，quota 10000）。
    private static final String LEADER = "U20270000001";
    private static final String TEACHER_A = "U20270000002";
    private static final String TEACHER_B = "U20270000003";
    private static final String STUDENT_A1 = "U20270000011";
    private static final String STUDENT_B1 = "U20270000021";

    private static J205TestHarness h;

    @BeforeAll
    static void setup() throws Exception {
        h = J205TestHarness.accountMode("usage");
        h.seedUser(LEADER, "[\"leader\"]", 10000, null);
        h.seedUser(TEACHER_A, "[\"admin\"]", 10000, LEADER);
        h.seedUser(TEACHER_B, "[\"admin\"]", 10000, LEADER);
        h.seedUser(STUDENT_A1, "[\"viewer\"]", 10000, TEACHER_A);
        h.seedUser(STUDENT_B1, "[\"viewer\"]", 10000, TEACHER_B);
        h.seedUser("U20270000031", "[\"viewer\"]", 10000, null);
        h.seedUser("U20270000041", "[\"viewer\"]", 10000, TEACHER_A);
        h.seedUser("U20270000051", "[\"viewer\"]", 10000, TEACHER_A);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    /* ------------------------------------------------------------------ */
    /* golden（空计数 / 空账本阶段）                                        */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(1)
    void goldenUsageViewsOnEmptyState() throws Exception {
        JsonNode golden = J205TestHarness.loadGolden();
        var mvc = h.newMvc();
        String leaderToken = h.login(mvc, LEADER);

        // /usage/me::success（零填充 7 天 + 今天）。
        JsonNode me = json(mvc, get("/api/usage/me").header("Authorization", bearer(leaderToken)));
        assertThat(me).isEqualTo(observation(golden, "GET /api/usage/me::success").get("body"));

        // /usage/subjects::success（8 主体全零，按 today_count=0 → subject 升序）。
        JsonNode subjects = json(mvc, get("/api/usage/subjects")
                .header("Authorization", bearer(leaderToken)));
        assertThat(subjects).isEqualTo(observation(golden, "GET /api/usage/subjects::success")
                .get("body"));

        // /usage/cost::success（空账本：token null 而非 0；since 微秒对齐 oracle）。
        h.clock.setWall(T0.plusNanos(31_000));
        JsonNode cost;
        try {
            cost = json(mvc, get("/api/usage/cost").header("Authorization", bearer(leaderToken)));
        } finally {
            h.clock.setWall(T0);
        }
        assertThat(cost).isEqualTo(observation(golden, "GET /api/usage/cost::success").get("body"));

        // unauthorized 三例（401 detail 一致）。
        for (String path : new String[]{"/api/usage/me", "/api/usage/subjects", "/api/usage/cost"}) {
            mvc.perform(get(path))
                    .andExpect(status().isUnauthorized())
                    .andExpect(r -> assertThat(MAPPER.readTree(r.getResponse().getContentAsString()))
                            .isEqualTo(observation(golden, "GET " + path + "::unauthorized")
                                    .get("body")));
        }
        // cost invalid-input：days=0 → 422。
        mvc.perform(get("/api/usage/cost").queryParam("days", "0")
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(r -> assertThat(MAPPER.readTree(r.getResponse().getContentAsString()))
                        .isEqualTo(observation(golden, "GET /api/usage/cost::invalid-input")
                                .get("body")));
        // 非 admin/leader：404 不泄露端点存在性（viewer 学生）。
        String studentToken = h.login(mvc, STUDENT_A1);
        mvc.perform(get("/api/usage/subjects").header("Authorization", bearer(studentToken)))
                .andExpect(status().isNotFound())
                .andExpect(r -> assertThat(MAPPER.readTree(r.getResponse().getContentAsString()))
                        .isEqualTo(MAPPER.readTree("{\"detail\":\"not found\"}")));
        mvc.perform(get("/api/usage/cost").header("Authorization", bearer(studentToken)))
                .andExpect(status().isNotFound());
    }

    /* ------------------------------------------------------------------ */
    /* /usage/me 行为                                                      */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(2)
    void meCountsOnlyChatKindAndZeroFillsMissingDays() throws Exception {
        h.insertUsageCounter(STUDENT_A1, "2026-10-24", 3, "chat");
        h.insertUsageCounter(STUDENT_A1, "2026-10-21", 2, "chat");
        // grading 配额独立计数，不进聊天面板。
        h.insertUsageCounter(STUDENT_A1, "2026-10-24", 7, "grading");
        var mvc = h.newMvc();
        JsonNode me = json(mvc, get("/api/usage/me")
                .header("Authorization", bearer(h.login(mvc, STUDENT_A1))));
        assertThat(me.get("today").get("chat_count").asInt()).isEqualTo(3);
        assertThat(me.get("recent_days").size()).isEqualTo(7);
        java.util.List<String> days = new java.util.ArrayList<>();
        me.get("recent_days").forEach(d -> days.add(d.get("day").asText()));
        assertThat(days).containsExactly("2026-10-18", "2026-10-19", "2026-10-20", "2026-10-21",
                "2026-10-22", "2026-10-23", "2026-10-24");
        for (JsonNode day : me.get("recent_days")) {
            int expected = "2026-10-24".equals(day.get("day").asText()) ? 3
                    : "2026-10-21".equals(day.get("day").asText()) ? 2 : 0;
            assertThat(day.get("chat_count").asInt()).isEqualTo(expected);
        }
        assertThat(me.get("daily_quota").asInt()).isEqualTo(10000);
    }

    /* ------------------------------------------------------------------ */
    /* /usage/subjects 行为（含树隔离与遗留主体）                          */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(3)
    void subjectsScopesTeacherTreeAndListsLegacySubject() throws Exception {
        h.insertUsageCounter(STUDENT_A1, "2026-10-24", 4, "chat");
        h.insertUsageCounter(STUDENT_B1, "2026-10-24", 9, "chat");
        h.insertUsageCounter(TEACHER_A, "2026-10-24", 2, "chat");
        // 历史遗留主体（不在 users 表）：roles 空、配额取默认 10000。
        h.insertUsageCounter("legacy-user1", "2026-10-22", 5, "chat");

        var mvc = h.newMvc();
        JsonNode teacherView = json(mvc, get("/api/usage/subjects")
                .header("Authorization", bearer(h.login(mvc, TEACHER_A))));
        java.util.List<String> seen = new java.util.ArrayList<>();
        teacherView.get("subjects").forEach(s -> seen.add(s.get("subject").asText()));
        // 老师 A 只见 自己 + 自建学生（A1/A41/A51），不见 B 树、未分配学生与遗留主体。
        assertThat(seen).containsExactly(STUDENT_A1, TEACHER_A, "U20270000041",
                "U20270000051");
        // 排序：-today_count 再 subject（A1 今日 4 次 > 老师 2 次）。
        assertThat(teacherView.get("subjects").get(0).get("subject").asText())
                .isEqualTo(STUDENT_A1);
        assertThat(teacherView.get("subjects").get(0).get("today_count").asInt()).isEqualTo(4);
        JsonNode legacy = null;
        JsonNode leaderView = json(mvc, get("/api/usage/subjects")
                .header("Authorization", bearer(h.login(mvc, LEADER))));
        for (JsonNode s : leaderView.get("subjects")) {
            if ("legacy-user1".equals(s.get("subject").asText())) {
                legacy = s;
            }
        }
        assertThat(legacy).isNotNull();
        assertThat(legacy.get("roles")).isEmpty();
        assertThat(legacy.get("daily_quota").asInt()).isEqualTo(10000);
        assertThat(legacy.get("total_count").asInt()).isEqualTo(5);
        assertThat(legacy.get("last_active_day").asText()).isEqualTo("2026-10-22");
    }

    /* ------------------------------------------------------------------ */
    /* /usage/cost 行为                                                    */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(4)
    void costSeparatesTokensOperationsUnknownAndUnpriced() throws Exception {
        seedLedgerRowsForAggregation();
        var mvc = h.newMvc();
        JsonNode cost = json(mvc, get("/api/usage/cost")
                .header("Authorization", bearer(h.login(mvc, LEADER))));

        // totals：2 条定价 llm（100+150 token，成本 1800000+? 只校验聚合存在）+ 1 条未定价。
        JsonNode totals = cost.get("totals");
        assertThat(totals.get("entries").asInt()).isEqualTo(5);
        assertThat(totals.get("prompt_tokens").asInt()).isEqualTo(420);
        assertThat(totals.get("completion_tokens").asInt()).isEqualTo(205);
        assertThat(totals.get("total_tokens").asInt()).isEqualTo(625);
        assertThat(totals.get("known_entries").asInt()).isEqualTo(4);
        assertThat(totals.get("unknown_entries").asInt()).isEqualTo(1);
        assertThat(totals.get("cost_micros_by_currency").get("CNY").asInt())
                .isEqualTo(1_800_000 + 300_000 + 8_000);
        // 未定价模型单列（有 token 无成本）。
        assertThat(cost.get("unpriced_models")).isEqualTo(
                MAPPER.readTree("[\"unpriced-model\"]"));
        // 非 token 操作分列：harness/mcp 只报次数与耗时，priced=false。
        JsonNode operations = cost.get("operations");
        assertThat(operations.get("entries").asInt()).isEqualTo(2);
        assertThat(operations.get("calls").asInt()).isEqualTo(3);
        assertThat(operations.get("duration_ms").asInt()).isEqualTo(220);
        assertThat(operations.get("priced").asBoolean()).isFalse();
        // by_stage / by_model / by_day 分组键。
        assertThat(cost.get("by_stage").size()).isEqualTo(6);
        assertThat(cost.get("by_model").size()).isEqualTo(3);
        assertThat(cost.get("by_day").size()).isEqualTo(2);
        assertThat(cost.get("truncated").asBoolean()).isFalse();
        assertThat(cost.get("row_limit").asInt()).isEqualTo(20000);
        assertThat(cost.get("cost_currency_mixed").asBoolean()).isFalse();
        // 顶层 unknown 冗余信号 = totals.unknown_entries。
        assertThat(cost.get("unknown_entries").asInt()).isEqualTo(1);
    }

    @Test
    @Order(5)
    void costFiltersByStageSubjectRequestIdAndRollingWindow() throws Exception {
        var mvc = h.newMvc();
        String leader = bearer(h.login(mvc, LEADER));

        JsonNode byStage = json(mvc, get("/api/usage/cost").queryParam("stage", "planner")
                .header("Authorization", leader));
        assertThat(byStage.get("stage").asText()).isEqualTo("planner");
        assertThat(byStage.get("totals").get("entries").asInt()).isEqualTo(2);

        JsonNode bySubject = json(mvc, get("/api/usage/cost").queryParam("subject", STUDENT_B1)
                .header("Authorization", leader));
        assertThat(bySubject.get("subject").asText()).isEqualTo(STUDENT_B1);
        assertThat(bySubject.get("totals").get("entries").asInt()).isEqualTo(2);

        JsonNode byRequest = json(mvc, get("/api/usage/cost").queryParam("request_id", "req-b-1")
                .header("Authorization", leader));
        assertThat(byRequest.get("request_id").asText()).isEqualTo("req-b-1");
        assertThat(byRequest.get("totals").get("entries").asInt()).isEqualTo(1);

        // 滚动窗口：days=1 排除 2 天前的行（created_at 文本比较）。
        JsonNode window = json(mvc, get("/api/usage/cost").queryParam("days", "1")
                .header("Authorization", leader));
        assertThat(window.get("days").asInt()).isEqualTo(1);
        com.fasterxml.jackson.databind.node.ArrayNode dayKeys = MAPPER.createArrayNode();
        window.get("by_day").forEach(d -> dayKeys.add(d.get("key")));
        assertThat(dayKeys.toString()).contains("2026-10-24");
        assertThat(dayKeys.toString()).doesNotContain("2026-10-22");

        // 未知 request_id = 空账（null 而非 0）。
        JsonNode empty = json(mvc, get("/api/usage/cost").queryParam("request_id", "req-none")
                .header("Authorization", leader));
        assertThat(empty.get("totals").get("entries").asInt()).isZero();
        assertThat(empty.get("totals").get("total_tokens").isNull()).isTrue();
        assertThat(empty.get("totals").get("prompt_tokens").isNull()).isTrue();
        assertThat(empty.get("operations").get("duration_ms").isNull()).isTrue();
    }

    @Test
    @Order(6)
    void costTeacherTreeIsolationAnd404ForForeignSubject() throws Exception {
        var mvc = h.newMvc();
        String teacherA = bearer(h.login(mvc, TEACHER_A));

        // 老师默认可见主体回显：本人 + 自建学生（排序）。
        JsonNode scoped = json(mvc, get("/api/usage/cost").header("Authorization", teacherA));
        java.util.List<String> subjects = new java.util.ArrayList<>();
        scoped.get("subjects").forEach(s -> subjects.add(s.asText()));
        assertThat(subjects).containsExactly(TEACHER_A, STUDENT_A1, "U20270000041",
                "U20270000051");
        // 只见自己树内的账目（B1 的行不可见；A1 的 llm 行 3 条）。
        assertThat(scoped.get("totals").get("entries").asInt()).isEqualTo(3);

        // B 树学生 → 404（不暗示存在性）。
        mvc.perform(get("/api/usage/cost").queryParam("subject", STUDENT_B1)
                        .header("Authorization", teacherA))
                .andExpect(status().isNotFound());
        // request_id 不能越过树隔离：B 树的 request_id 只得到空账。
        JsonNode narrowed = json(mvc, get("/api/usage/cost")
                .queryParam("request_id", "req-b-1")
                .header("Authorization", teacherA));
        assertThat(narrowed.get("totals").get("entries").asInt()).isZero();
        // 自己的学生可见。
        JsonNode ownStudent = json(mvc, get("/api/usage/cost")
                .queryParam("subject", STUDENT_A1)
                .header("Authorization", teacherA));
        assertThat(ownStudent.get("totals").get("entries").asInt()).isEqualTo(3);

        // 静态兜底 token（emergency-admin）不受树约束：subjects=null（全部）。
        JsonNode fallback = json(mvc, get("/api/usage/cost")
                .header("Authorization", bearer(J205TestHarness.FALLBACK_TOKEN)));
        assertThat(fallback.get("subjects").isNull()).isTrue();
        assertThat(fallback.get("totals").get("entries").asInt()).isEqualTo(5);
    }

    /* ------------------------------------------------------------------ */

    /** 造账：planner（A1 定价）、finalize（A1 未定价）、judge（A1 unknown）、
     *  mcp+harness 操作行（A1）、B 树一行（req-b-1 定价，2 天前一行）。 */
    private static void seedLedgerRowsForAggregation() throws Exception {
        String now = "2026-10-24T04:00:00.000100+00:00";
        String old = "2026-10-22T04:00:00.000100+00:00";
        java.util.List<UsageLedgerRow> rows = java.util.List.of(
                new UsageLedgerRow("use_a000000001", "req-a-1", STUDENT_A1, null, "llm",
                        "planner", "qwen3.8-flash", 100, 50, 150, "upstream", 1, 10,
                        1_800_000, "CNY", "v1", now),
                new UsageLedgerRow("use_a000000002", "req-a-1", STUDENT_A1, null, "llm",
                        "finalize", "unpriced-model", 110, 55, 165, "upstream", 1, 20,
                        null, null, null, now),
                new UsageLedgerRow("use_a000000003", "req-a-2", STUDENT_A1, null, "llm",
                        "judge", "qwen3.8-flash", null, null, null, "unknown", 1, 30,
                        null, null, null, now),
                new UsageLedgerRow("use_a000000004", "req-a-2", STUDENT_A1, null, "mcp",
                        "submit_source", null, null, null, null, "operation", 2, 120,
                        null, null, null, now),
                new UsageLedgerRow("use_a000000005", "req-a-2", STUDENT_A1, null, "harness",
                        "evaluate", null, null, null, null, "operation", 1, 100,
                        null, null, null, now),
                new UsageLedgerRow("use_b000000001", "req-b-1", STUDENT_B1, null, "llm",
                        "planner", "qwen3.8-flash", 200, 100, 300, "upstream", 1, 40,
                        300_000, "CNY", "v1", now),
                new UsageLedgerRow("use_b000000002", "req-b-2", STUDENT_B1, null, "llm",
                        "query_embed", "embed-v1", 10, null, 10, "upstream", 1, 5,
                        8_000, "CNY", "v1", old));
        h.ledger.recordRows(rows);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static JsonNode json(org.springframework.test.web.servlet.MockMvc mvc,
                                 org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b)
            throws Exception {
        return MAPPER.readTree(mvc.perform(b).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private static JsonNode observation(JsonNode golden, String caseId) {
        for (JsonNode o : golden.get("observations")) {
            if (caseId.equals(o.get("case_id").asText())) {
                return o;
            }
        }
        throw new AssertionError("golden case not found: " + caseId);
    }
}
