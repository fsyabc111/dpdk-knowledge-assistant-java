package io.dpdk.assistant.grading.query;

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
 * J2-05 通用成绩只读视图（验收 F：班级隔离；与作业汇总不混用过滤逻辑——
 * 本端点不识别 assignment 参数）。golden：空成绩下 submissions/summary
 * success + 401 + 422；行为：角色门禁（account-only）、老师树隔离
 * （学生集不含本人）、operator/leader/静态 token 不限、明细过滤与聚合口径。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class GradingQueryContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Instant T0 = J205TestHarness.T0;

    private static final String LEADER = "U20260000001";
    private static final String TEACHER_A = "U20260000002";
    private static final String TEACHER_B = "U20260000003";
    private static final String OPERATOR = "U20260000004";
    private static final String STUDENT_A1 = "U20260000011";
    private static final String STUDENT_A2 = "U20260000012";
    private static final String STUDENT_B1 = "U20260000021";

    private static J205TestHarness h;
    private static J205TestHarness local;

    @BeforeAll
    static void setup() throws Exception {
        h = J205TestHarness.accountMode("grading");
        h.seedUser(LEADER, "[\"leader\"]", null, null);
        h.seedUser(TEACHER_A, "[\"admin\"]", null, LEADER);
        h.seedUser(TEACHER_B, "[\"admin\"]", null, LEADER);
        h.seedUser(OPERATOR, "[\"operator\"]", null, LEADER);
        h.seedUser(STUDENT_A1, "[\"viewer\"]", null, TEACHER_A);
        h.seedUser(STUDENT_A2, "[\"viewer\"]", null, TEACHER_A);
        h.seedUser(STUDENT_B1, "[\"viewer\"]", null, TEACHER_B);
        local = J205TestHarness.localMode("gradinglocal");
        local.seedUser("U20260000001", "[\"leader\"]", null, null);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
        if (local != null) {
            local.drop();
        }
    }

    /* ------------------------------------------------------------------ */
    /* golden（空成绩阶段）                                                */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(1)
    void goldenGradingViewsOnEmptyState() throws Exception {
        JsonNode golden = J205TestHarness.loadGolden();
        var mvc = h.newMvc();
        String leaderToken = h.login(mvc, LEADER);

        JsonNode submissions = json(mvc, get("/api/grading/submissions")
                .header("Authorization", bearer(leaderToken)));
        assertThat(submissions).isEqualTo(observation(golden,
                "GET /api/grading/submissions::success").get("body"));

        JsonNode summary = json(mvc, get("/api/grading/summary")
                .header("Authorization", bearer(leaderToken)));
        assertThat(summary).isEqualTo(observation(golden, "GET /api/grading/summary::success")
                .get("body"));

        mvc.perform(get("/api/grading/submissions"))
                .andExpect(status().isUnauthorized())
                .andExpect(r -> assertThat(MAPPER.readTree(r.getResponse().getContentAsString()))
                        .isEqualTo(observation(golden,
                                "GET /api/grading/submissions::unauthorized").get("body")));
        mvc.perform(get("/api/grading/summary"))
                .andExpect(status().isUnauthorized())
                .andExpect(r -> assertThat(MAPPER.readTree(r.getResponse().getContentAsString()))
                        .isEqualTo(observation(golden, "GET /api/grading/summary::unauthorized")
                                .get("body")));
        mvc.perform(get("/api/grading/submissions").queryParam("limit", "0")
                        .header("Authorization", bearer(leaderToken)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(r -> assertThat(MAPPER.readTree(r.getResponse().getContentAsString()))
                        .isEqualTo(observation(golden,
                                "GET /api/grading/submissions::invalid-input").get("body")));
    }

    /* ------------------------------------------------------------------ */
    /* 角色门禁                                                            */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(2)
    void viewerAndNonAccountModeGet404() throws Exception {
        var mvc = h.newMvc();
        String studentToken = h.login(mvc, STUDENT_A1);
        mvc.perform(get("/api/grading/submissions")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isNotFound())
                .andExpect(r -> assertThat(MAPPER.readTree(r.getResponse().getContentAsString()))
                        .isEqualTo(MAPPER.readTree("{\"detail\":\"not found\"}")));
        mvc.perform(get("/api/grading/summary")
                        .header("Authorization", bearer(studentToken)))
                .andExpect(status().isNotFound());
        // local/token 模式（无 account 门禁语义）：同样 404。
        var localMvc = local.newMvc();
        localMvc.perform(get("/api/grading/submissions"))
                .andExpect(status().isNotFound());
    }

    /* ------------------------------------------------------------------ */
    /* 树隔离与数据                                                        */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(3)
    void teacherSeesOnlyOwnStudentsLeaderOperatorUnrestricted() throws Exception {
        seedSubmissions();
        var mvc = h.newMvc();

        // 老师A：仅自建学生（A1/A2），不含本人与 B 树。
        JsonNode teacherA = json(mvc, get("/api/grading/submissions")
                .header("Authorization", bearer(h.login(mvc, TEACHER_A))));
        java.util.List<String> users = new java.util.ArrayList<>();
        teacherA.get("submissions").forEach(s -> users.add(s.get("user_id").asText()));
        assertThat(users).containsOnly(STUDENT_A1, STUDENT_A2);
        // newest-first（created_at DESC, id DESC 兜底）。
        assertThat(teacherA.get("submissions").get(0).get("id").asText()).isEqualTo("gra_a1_new1");

        // 老师 B 只有 B1（无自建提交则空树也不越界）。
        JsonNode teacherB = json(mvc, get("/api/grading/submissions")
                .header("Authorization", bearer(h.login(mvc, TEACHER_B))));
        java.util.List<String> usersB = new java.util.ArrayList<>();
        teacherB.get("submissions").forEach(s -> usersB.add(s.get("user_id").asText()));
        assertThat(usersB).containsOnly(STUDENT_B1);

        // leader/operator/静态 token：全部可见（含老师本人提交）。
        JsonNode leader = json(mvc, get("/api/grading/submissions")
                .header("Authorization", bearer(h.login(mvc, LEADER))));
        assertThat(leader.get("submissions").size()).isEqualTo(5);
        JsonNode operator = json(mvc, get("/api/grading/submissions")
                .header("Authorization", bearer(h.login(mvc, OPERATOR))));
        assertThat(operator.get("submissions").size()).isEqualTo(5);
        JsonNode fallback = json(mvc, get("/api/grading/submissions")
                .header("Authorization", bearer(J205TestHarness.FALLBACK_TOKEN)));
        assertThat(fallback.get("submissions").size()).isEqualTo(5);

        // 老师 A 的汇总：by_user 只含学生（无本人行）。
        JsonNode summaryA = json(mvc, get("/api/grading/summary")
                .header("Authorization", bearer(h.login(mvc, TEACHER_A))));
        JsonNode byUser = summaryA.get("by_user");
        assertThat(byUser.size()).isEqualTo(2);
        JsonNode a1 = byUser.get(0);
        assertThat(a1.get("user_id").asText()).isEqualTo(STUDENT_A1);
        assertThat(a1.get("submission_count").asInt()).isEqualTo(2);
        assertThat(a1.get("avg_score").asDouble()).isEqualTo(75.0);
        assertThat(a1.get("best_score").asInt()).isEqualTo(90);
        JsonNode byProtocol = summaryA.get("by_protocol");
        assertThat(byProtocol.size()).isEqualTo(2);
        // 老师A 视口的 dns（仅 A1 一行；本人与 B 树不计入）：分子分母分别聚合。
        JsonNode dns = byProtocol.get(0).get("protocol").asText().equals("dns")
                ? byProtocol.get(0) : byProtocol.get(1);
        assertThat(dns.get("submission_count").asInt()).isEqualTo(1);
        assertThat(dns.get("passed_total").asInt()).isEqualTo(8);
        assertThat(dns.get("total_total").asInt()).isEqualTo(10);
        assertThat(dns.get("student_count").asInt()).isEqualTo(1);
    }

    @Test
    @Order(4)
    void filtersPaginationAndLimitCap() throws Exception {
        var mvc = h.newMvc();
        String leader = bearer(h.login(mvc, LEADER));

        JsonNode byUser = json(mvc, get("/api/grading/submissions")
                .queryParam("user_id", STUDENT_A1).header("Authorization", leader));
        assertThat(byUser.get("submissions").size()).isEqualTo(2);
        JsonNode byProtocol = json(mvc, get("/api/grading/submissions")
                .queryParam("protocol", "tcp").header("Authorization", leader));
        assertThat(byProtocol.get("submissions").size()).isEqualTo(2);
        JsonNode combined = json(mvc, get("/api/grading/submissions")
                .queryParam("user_id", STUDENT_A1).queryParam("protocol", "tcp")
                .header("Authorization", leader));
        assertThat(combined.get("submissions").size()).isEqualTo(1);
        // 越界过滤（别班学生）在老师视角为空列表而非 404（等值过滤语义）。
        JsonNode foreign = json(mvc, get("/api/grading/submissions")
                .queryParam("user_id", STUDENT_B1)
                .header("Authorization", bearer(h.login(mvc, TEACHER_A))));
        assertThat(foreign.get("submissions").size()).isZero();

        JsonNode page = json(mvc, get("/api/grading/submissions")
                .queryParam("limit", "2").queryParam("offset", "1")
                .header("Authorization", leader));
        assertThat(page.get("submissions").size()).isEqualTo(2);
        // 明细字段最小集：无 results_json/code_hash。
        page.get("submissions").forEach(s -> {
            assertThat(s.has("results_json")).isFalse();
            assertThat(s.has("code_hash")).isFalse();
        });
        // limit 超上限截到 500（不报错）。
        JsonNode capped = json(mvc, get("/api/grading/submissions")
                .queryParam("limit", "9000").header("Authorization", leader));
        assertThat(capped.get("submissions").size()).isEqualTo(5);
    }

    /* ------------------------------------------------------------------ */

    /** 造成绩：A1×2（dns 90/8/10、tcp 60/9/10）、A2×1、B1×1、老师本人×1（老师视图不可见）。 */
    private static void seedSubmissions() throws Exception {
        h.insertGradingSubmission("gra_a1_new1", STUDENT_A1, null, "dns", 90, 8, 10,
                T0.plusNanos(50_000));
        h.insertGradingSubmission("gra_a1_old1", STUDENT_A1, null, "tcp", 60, 9, 10,
                T0.plusNanos(10_000));
        h.insertGradingSubmission("gra_a2_0001", STUDENT_A2, null, "tcp", 70, 7, 10,
                T0.plusNanos(20_000));
        h.insertGradingSubmission("gra_b1_0001", STUDENT_B1, null, "dns", 55, 5, 10,
                T0.plusNanos(30_000));
        h.insertGradingSubmission("gra_ta_0001", TEACHER_A, null, "dns", 100, 10, 10,
                T0.plusNanos(40_000));
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
