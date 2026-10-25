package io.dpdk.assistant.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * J2-02 重置审批测试（经 PasswordResetApprovalPort 的 HTTP 层）：
 * 列表树隔离（老师 JOIN 只见自建学生、孤儿申请对老师不可见）、审批签发一次性令牌
 * （30 分钟、明文仅此一次、可直接经 /api/auth/password-reset 消费）、
 * 驳回、非 pending/缺失/越界 404、过期 400。pending 断言依赖 @Order 顺序。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class PasswordResetApprovalTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CREATED_AT_STR =
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d{6})?\\+00:00$";

    private static final String LEADER = "U20260000001";
    private static final String TEACHER_A = "U20260000002";
    private static final String STUDENT_A1 = "U20260000011";
    private static final String STUDENT_B1 = "U20260000021";
    private static final String ORPHAN = "U20260000031";

    private static AdminTestHarness h;

    @BeforeAll
    static void setup() throws Exception {
        h = AdminTestHarness.accountMode("reset");
        h.seedUser(LEADER, "[\"leader\"]", null, true, true, LEADER, null);
        h.seedUser(TEACHER_A, "[\"admin\"]", null, true, true, TEACHER_A, LEADER);
        h.seedUser("U20260000003", "[\"admin\"]", null, true, true, "U20260000003", LEADER);
        h.seedUser(STUDENT_A1, "[\"viewer\"]", null, true, true, STUDENT_A1, TEACHER_A);
        h.seedUser(STUDENT_B1, "[\"viewer\"]", null, true, true, STUDENT_B1, "U20260000003");
        h.seedUser(ORPHAN, "[\"viewer\"]", null, true, true, ORPHAN, null);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    private static String auth(String token) {
        return "Bearer " + token;
    }

    /** 匿名提交重置申请（/api/auth/password-reset-requests）。 */
    private static void requestReset(MockMvc mvc, String username) throws Exception {
        mvc.perform(post("/api/auth/password-reset-requests")
                        .contentType("application/json")
                        .content(MAPPER.writeValueAsString(Map.of("username", username))))
                .andExpect(status().isAccepted());
    }

    private static JsonNode listRequests(MockMvc mvc, String token) throws Exception {
        MvcResult result = mvc.perform(get("/api/admin/password-reset-requests")
                        .header("Authorization", auth(token)))
                .andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString()).path("requests");
    }

    private static String requestIdFor(JsonNode list, String userId) {
        for (JsonNode row : list) {
            if (userId.equals(row.path("user_id").asText())) {
                return row.path("id").asText();
            }
        }
        throw new AssertionError("pending 申请不存在: " + userId);
    }

    @Test
    @Order(1)
    void fullApproveFlowIssuesConsumableOneTimeToken() throws Exception {
        MockMvc mvc = h.newMvc();
        String leaderToken = h.login(mvc, LEADER, LEADER);
        requestReset(mvc, STUDENT_A1);
        JsonNode pending = listRequests(mvc, leaderToken);
        assertThat(pending.size()).isEqualTo(1);
        assertThat(pending.path(0).path("user_id").asText()).isEqualTo(STUDENT_A1);
        assertThat(pending.path(0).path("id").asText()).matches("^[0-9a-f]{32}$");
        // created_at 为 Python str(datetime) 形态（空格分隔 +00:00）。
        assertThat(pending.path(0).path("created_at").asText()).matches(CREATED_AT_STR);
        String requestId = pending.path(0).path("id").asText();

        MvcResult approveResult = mvc.perform(post("/api/admin/password-reset-requests/"
                        + requestId + "/approve").header("Authorization", auth(leaderToken)))
                .andExpect(status().isOk()).andReturn();
        JsonNode approved = MAPPER.readTree(approveResult.getResponse().getContentAsString());
        String resetToken = approved.path("reset_token").asText();
        assertThat(resetToken).matches("^[A-Za-z0-9_-]{43}$");
        // 有效期 30 分钟（受控墙钟）。
        assertThat(OffsetDateTime.parse(approved.path("expires_at").asText()).toInstant())
                .isEqualTo(h.clock.nowUtc().plusSeconds(30 * 60));

        // 审批后 pending 列表不再包含；重复审批（非 pending）→ 404。
        assertThat(listRequests(mvc, leaderToken).size()).isZero();
        mvc.perform(post("/api/admin/password-reset-requests/" + requestId + "/approve")
                        .header("Authorization", auth(leaderToken)))
                .andExpect(status().isNotFound());

        // 一次性令牌可直接经 /api/auth/password-reset 消费：改密 + must_change 清除。
        mvc.perform(post("/api/auth/password-reset").contentType("application/json")
                        .content(MAPPER.writeValueAsString(Map.of(
                                "token", resetToken, "new_password", "consumedpassword1"))))
                .andExpect(status().isNoContent());
        String login = h.login(mvc, STUDENT_A1, "consumedpassword1");
        assertThat(h.auth.resolveToken(login).orElseThrow().mustChangePassword()).isFalse();
        // 二次消费 → 400。
        mvc.perform(post("/api/auth/password-reset").contentType("application/json")
                        .content(MAPPER.writeValueAsString(Map.of(
                                "token", resetToken, "new_password", "anotherpassword2"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(2)
    void teacherScopeIsolationOnListApproveAndReject() throws Exception {
        MockMvc mvc = h.newMvc();
        requestReset(mvc, STUDENT_A1);
        requestReset(mvc, STUDENT_B1);
        requestReset(mvc, ORPHAN);
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        String leaderToken = h.login(mvc, LEADER, LEADER);

        // 老师只看到自己学生；他班/未分配不可见。
        JsonNode teacherView = listRequests(mvc, teacherToken);
        assertThat(teacherView.findValuesAsText("user_id")).containsExactly(STUDENT_A1);

        JsonNode leaderView = listRequests(mvc, leaderToken);
        assertThat(leaderView.findValuesAsText("user_id"))
                .containsExactlyInAnyOrder(STUDENT_A1, STUDENT_B1, ORPHAN);

        // 老师审批/驳回别人学生的申请 → 404（不泄露存在性）。
        String otherId = requestIdFor(leaderView, STUDENT_B1);
        mvc.perform(post("/api/admin/password-reset-requests/" + otherId + "/approve")
                        .header("Authorization", auth(teacherToken)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/password-reset-requests/" + otherId + "/reject")
                        .header("Authorization", auth(teacherToken)))
                .andExpect(status().isNotFound());

        // 自己学生的申请可以审批（token 形态断言）。
        String ownId = requestIdFor(leaderView, STUDENT_A1);
        MvcResult approved = mvc.perform(post("/api/admin/password-reset-requests/" + ownId
                        + "/approve").header("Authorization", auth(teacherToken)))
                .andExpect(status().isOk()).andReturn();
        assertThat(MAPPER.readTree(approved.getResponse().getContentAsString())
                .path("reset_token").asText()).matches("^[A-Za-z0-9_-]{43}$");

        // 驳回：老师视角无自己的 pending，leader 驳回 ORPHAN 的申请。
        String orphanId = requestIdFor(leaderView, ORPHAN);
        mvc.perform(post("/api/admin/password-reset-requests/" + orphanId + "/reject")
                        .header("Authorization", auth(leaderToken)))
                .andExpect(status().isNoContent());
        // 重复驳回（非 pending）→ 404。
        mvc.perform(post("/api/admin/password-reset-requests/" + orphanId + "/reject")
                        .header("Authorization", auth(leaderToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(3)
    void orphanRequestVisibleOnlyToLeaderAndUnapprovable() throws Exception {
        // 申请人不在 users 表（匿名端点不校验存在）：leader 可见（无 JOIN），老师不可见；
        // 审批时因用户不存在 → 404。
        MockMvc mvc = h.newMvc();
        requestReset(mvc, "U20269999999");
        String leaderToken = h.login(mvc, LEADER, LEADER);
        JsonNode leaderView = listRequests(mvc, leaderToken);
        assertThat(leaderView.findValuesAsText("user_id")).contains("U20269999999");
        String requestId = requestIdFor(leaderView, "U20269999999");
        mvc.perform(post("/api/admin/password-reset-requests/" + requestId + "/approve")
                        .header("Authorization", auth(leaderToken)))
                .andExpect(status().isNotFound());
        // 老师视角：JOIN users 丢弃孤儿申请。
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        assertThat(listRequests(mvc, teacherToken).findValuesAsText("user_id"))
                .doesNotContain("U20269999999");
        // 驳回同样受树隔离（越界 false → 404）。
        mvc.perform(post("/api/admin/password-reset-requests/" + requestId + "/reject")
                        .header("Authorization", auth(teacherToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(4)
    void approvedTokenExpiresAfterTtl() throws Exception {
        MockMvc mvc = h.newMvc();
        requestReset(mvc, STUDENT_A1);
        String leaderToken = h.login(mvc, LEADER, LEADER);
        String requestId = listRequests(mvc, leaderToken).path(0).path("id").asText();
        MvcResult approved = mvc.perform(post("/api/admin/password-reset-requests/" + requestId
                        + "/approve").header("Authorization", auth(leaderToken)))
                .andExpect(status().isOk()).andReturn();
        String token = MAPPER.readTree(approved.getResponse().getContentAsString())
                .path("reset_token").asText();
        h.clock.advanceWall(java.time.Duration.ofMinutes(31));
        mvc.perform(post("/api/auth/password-reset").contentType("application/json")
                        .content(MAPPER.writeValueAsString(Map.of(
                                "token", token, "new_password", "latepassword123"))))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo("{\"detail\":\"invalid or expired reset token\"}"));
        h.clock.advanceWall(java.time.Duration.ofMinutes(-31));
    }

    @Test
    @Order(5)
    void setAndResetPasswordRuleDetailsSurfaceAtHttpLayer() throws Exception {
        h.seedUser("U20260000041", "[\"viewer\"]", null, true, true, "U20260000041", TEACHER_A);
        MockMvc mvc = h.newMvc();
        String leaderToken = h.login(mvc, LEADER, LEADER);
        // set-password 的规则文案经端口透传：过短 → 等于学号（初始态下“等于旧密”不可达，
        // 因规则序为 长度→≠学号→≠旧密）。
        mvc.perform(post("/api/admin/users/U20260000041/set-password")
                        .contentType("application/json").header("Authorization", auth(leaderToken))
                        .content("{\"new_password\":\"short12\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo("{\"detail\":\"new password must be at least 8 characters\"}"));
        mvc.perform(post("/api/admin/users/U20260000041/set-password")
                        .contentType("application/json").header("Authorization", auth(leaderToken))
                        .content("{\"new_password\":\"U20260000041\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo("{\"detail\":\"new password must differ from the user id\"}"));
        // 成功设密：must_change 置位、token 全撤。
        String studentToken = h.login(mvc, "U20260000041", "U20260000041");
        mvc.perform(post("/api/admin/users/U20260000041/set-password")
                        .contentType("application/json").header("Authorization", auth(leaderToken))
                        .content("{\"new_password\":\"admin-set-pass-1\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/users").header("Authorization", auth(studentToken)))
                .andExpect(status().isUnauthorized());
        String relogin = h.login(mvc, "U20260000041", "admin-set-pass-1");
        assertThat(h.auth.resolveToken(relogin).orElseThrow().mustChangePassword()).isTrue();
        // 与旧密相同 → 对应文案。
        mvc.perform(post("/api/admin/users/U20260000041/set-password")
                        .contentType("application/json").header("Authorization", auth(leaderToken))
                        .content("{\"new_password\":\"admin-set-pass-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo("{\"detail\":\"new password must differ from the old password\"}"));
        // 管理员重置回初始密码：学号可登录、must_change 重新置位。
        mvc.perform(post("/api/admin/users/U20260000041/reset-password")
                        .header("Authorization", auth(leaderToken)))
                .andExpect(status().isNoContent());
        String initial = h.login(mvc, "U20260000041", "U20260000041");
        assertThat(h.auth.resolveToken(initial).orElseThrow().mustChangePassword()).isTrue();
        // 目标不存在 → 404（树检查先于行更新）。
        mvc.perform(post("/api/admin/users/U20269999999/reset-password")
                        .header("Authorization", auth(leaderToken)))
                .andExpect(status().isNotFound());
    }
}
