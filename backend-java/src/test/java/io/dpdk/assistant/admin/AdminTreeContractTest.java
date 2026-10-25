package io.dpdk.assistant.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.auth.AuthServerSettings;
import io.dpdk.assistant.auth.TestClock;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.platform.time.SystemClockAdapter;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * J2-02 树契约测试（验收 A/B/C + golden 36 条 observation）：
 * leader 管、老师树隔离、静态 token 语义、防提权/自保护、批量部分失败契约、
 * golden 逐字段断言（动态 identity/时间按 evidence/J2-02/golden-diff.md 映射）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class AdminTreeContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** created_at 匹配 golden 的 oracle 微秒序列（实现侧字节级可比）。 */
    private static final Instant T0 = Instant.parse("2026-10-24T04:00:00Z");

    // golden 合成树（U2027 命名空间，仅供 golden 流程）。
    private static final String G_LEADER = "U20270000001";
    private static final String G_TEACHER_B = "U20270000003";

    // 行为测试树（U2026 命名空间，与 golden 树互不污染）。
    private static final String LEADER = "U20260000001";
    private static final String TEACHER_A = "U20260000002";
    private static final String TEACHER_B = "U20260000003";
    private static final String STUDENT_A1 = "U20260000011";
    private static final String STUDENT_A2 = "U20260000012";
    private static final String STUDENT_B1 = "U20260000021";
    private static final String ORPHAN = "U20260000031";

    private static final String CREATED_AT_STR =
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d{6})?\\+00:00$";
    private static final String ISO_AT =
            "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{6})?\\+00:00$";

    private static AdminTestHarness h;

    @BeforeAll
    static void setup() throws Exception {
        h = AdminTestHarness.accountMode("tree");
        // golden 合成树（daily_quota=10000、初始密码=学号；created_at 微秒对齐 oracle）。
        h.seedUser(G_LEADER, "[\"leader\"]", 10000, true, true, G_LEADER, null, T0);
        h.seedUser("U20270000002", "[\"admin\"]", 10000, true, true, "U20270000002",
                G_LEADER, T0.plusNanos(1_000));
        h.seedUser(G_TEACHER_B, "[\"admin\"]", 10000, true, true, G_TEACHER_B,
                G_LEADER, T0.plusNanos(2_000));
        h.seedUser("U20270000011", "[\"viewer\"]", 10000, true, true, "U20270000011",
                "U20270000002", T0.plusNanos(3_000));
        h.seedUser("U20270000021", "[\"viewer\"]", 10000, true, true, "U20270000021",
                G_TEACHER_B, T0.plusNanos(6_000));
        h.seedUser("U20270000031", "[\"viewer\"]", 10000, true, true, "U20270000031",
                null, T0.plusNanos(7_000));
        h.seedUser("U20270000041", "[\"viewer\"]", 10000, true, true, "U20270000041",
                "U20270000002", T0.plusNanos(4_000));
        h.seedUser("U20270000051", "[\"viewer\"]", 10000, true, true, "U20270000051",
                "U20270000002", T0.plusNanos(5_000));
        // 行为测试层级树。
        h.seedUser(LEADER, "[\"leader\"]", null, true, true, LEADER, null);
        h.seedUser(TEACHER_A, "[\"admin\"]", null, true, true, TEACHER_A, LEADER);
        h.seedUser(TEACHER_B, "[\"admin\"]", null, true, true, TEACHER_B, LEADER);
        h.seedUser(STUDENT_A1, "[\"viewer\"]", null, true, true, STUDENT_A1, TEACHER_A);
        h.seedUser(STUDENT_A2, "[\"viewer\"]", null, true, true, STUDENT_A2, TEACHER_A);
        h.seedUser(STUDENT_B1, "[\"viewer\"]", null, true, true, STUDENT_B1, TEACHER_B);
        h.seedUser(ORPHAN, "[\"viewer\"]", null, true, true, ORPHAN, null);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    /* ------------------------------------------------------------------ */
    /* golden 成功序列（14 条 success observation 按序执行）                  */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(1)
    void goldenSuccessFlowInFixtureOrder() throws Exception {
        JsonNode golden = AdminTestHarness.loadGolden();
        MockMvc mvc = h.newMvc();
        String leaderToken = h.login(mvc, G_LEADER, G_LEADER);

        // GET /users::success —— golden 子树（U2027 种子 created_at 与 oracle 同值）整体可比；
        // 行为测试树（U2026）同库共存，leader 视图取 golden 子集比对（映射登记 golden-diff.md）。
        JsonNode usersBody = getJson(mvc, get("/api/admin/users").header("Authorization", auth(leaderToken)));
        com.fasterxml.jackson.databind.node.ArrayNode goldenSubtree = MAPPER.createArrayNode();
        for (JsonNode user : usersBody.path("users")) {
            if (user.path("id").asText().startsWith("U2027")) {
                goldenSubtree.add(user);
            }
        }
        com.fasterxml.jackson.databind.node.ObjectNode subtree = MAPPER.createObjectNode();
        subtree.set("users", goldenSubtree);
        assertThat(subtree).isEqualTo(observation(golden, "GET /api/admin/users::success"));

        // GET /audit-log::success / GET /password-reset-requests::success（空态）。
        assertThat(getJson(mvc, get("/api/admin/audit-log").header("Authorization", auth(leaderToken))))
                .isEqualTo(observation(golden, "GET /api/admin/audit-log::success"));
        assertThat(getJson(mvc, get("/api/admin/password-reset-requests")
                .header("Authorization", auth(leaderToken))))
                .isEqualTo(observation(golden, "GET /api/admin/password-reset-requests::success"));

        // POST /users::success（leader 建 U20270000061）。
        JsonNode created = postJson(mvc, post("/api/admin/users")
                .contentType("application/json").header("Authorization", auth(leaderToken))
                .content("{\"ids\":[\"U20270000061\"]}"));
        assertThat(created).isEqualTo(observation(golden, "POST /api/admin/users::success"));

        // PATCH::success（daily_quota=5；created_at 动态归一化）。
        JsonNode patched = postJson(mvc, patch("/api/admin/users/U20270000061")
                .contentType("application/json").header("Authorization", auth(leaderToken))
                .content("{\"daily_quota\":5}"));
        JsonNode expectedPatch = observation(golden, "PATCH /api/admin/users/{user_id}::success");
        assertThat(patched.path("id").asText()).isEqualTo(expectedPatch.path("id").asText());
        assertThat(patched.path("roles").toString()).isEqualTo(expectedPatch.path("roles").toString());
        assertThat(patched.path("daily_quota").asInt()).isEqualTo(5);
        assertThat(patched.path("enabled").asBoolean()).isTrue();
        assertThat(patched.path("must_change_password").asBoolean()).isTrue();
        assertThat(patched.path("created_by").asText()).isEqualTo(G_LEADER);
        assertThat(patched.path("created_at").asText()).matches(CREATED_AT_STR);

        // assign::success（U20270000061 → U20270000002）。
        JsonNode assigned = postJson(mvc, post("/api/admin/users/assign")
                .contentType("application/json").header("Authorization", auth(leaderToken))
                .content("{\"ids\":[\"U20270000061\"],\"created_by\":\"U20270000002\"}"));
        assertThat(assigned).isEqualTo(observation(golden, "POST /api/admin/users/assign::success"));

        // status::success（停用）+ reset-password::success + set-password::success → 204。
        mvc.perform(post("/api/admin/users/U20270000061/status")
                        .contentType("application/json").header("Authorization", auth(leaderToken))
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/users/U20270000061/reset-password")
                        .header("Authorization", auth(leaderToken)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/users/U20270000061/set-password")
                        .contentType("application/json").header("Authorization", auth(leaderToken))
                        .content("{\"new_password\":\"newpassword123\"}"))
                .andExpect(status().isNoContent());

        // 重置申请审批流（golden success-after-request / approve / second / reject）。
        mvc.perform(post("/api/auth/password-reset-requests")
                        .contentType("application/json")
                        .content("{\"username\":\"U20270000031\"}"))
                .andExpect(status().isAccepted());
        JsonNode pending = getJson(mvc, get("/api/admin/password-reset-requests")
                .header("Authorization", auth(leaderToken))).path("requests");
        JsonNode expectedPending = observation(golden,
                "GET /api/admin/password-reset-requests::success-after-request").path("requests");
        assertThat(pending.size()).isEqualTo(1);
        assertThat(pending.path(0).path("user_id").asText())
                .isEqualTo(expectedPending.path(0).path("user_id").asText());
        assertThat(pending.path(0).path("id").asText()).matches("^[0-9a-f]{32}$");
        assertThat(pending.path(0).path("created_at").asText()).matches(CREATED_AT_STR);

        MvcResult approveResult = mvc.perform(post("/api/admin/password-reset-requests/"
                        + pending.path(0).path("id").asText() + "/approve")
                        .header("Authorization", auth(leaderToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode approved = MAPPER.readTree(approveResult.getResponse().getContentAsString());
        assertThat(approved.path("reset_token").asText()).matches("^[A-Za-z0-9_-]{43}$");
        // expires_at = 审批时刻（受控墙钟）+ 30 分钟，pythonIso 字节形态。
        assertThat(approved.path("expires_at").asText())
                .isEqualTo(SystemClockAdapter.pythonIso(h.clock.nowUtc().plusSeconds(30 * 60)))
                .matches(ISO_AT);

        mvc.perform(post("/api/auth/password-reset-requests")
                        .contentType("application/json")
                        .content("{\"username\":\"U20270000021\"}"))
                .andExpect(status().isAccepted());
        JsonNode pendingSecond = getJson(mvc, get("/api/admin/password-reset-requests")
                .header("Authorization", auth(leaderToken))).path("requests");
        assertThat(pendingSecond.size()).isEqualTo(1);
        assertThat(pendingSecond.path(0).path("user_id").asText()).isEqualTo("U20270000021");
        mvc.perform(post("/api/admin/password-reset-requests/"
                        + pendingSecond.path(0).path("id").asText() + "/reject")
                        .header("Authorization", auth(leaderToken)))
                .andExpect(status().isNoContent());

        // delete::success（leader 删 U20270000041，密码=学号）。
        JsonNode deleted = postJson(mvc, post("/api/admin/users/delete")
                .contentType("application/json").header("Authorization", auth(leaderToken))
                .content("{\"ids\":[\"U20270000041\"],\"password\":\"" + G_LEADER + "\"}"));
        assertThat(deleted).isEqualTo(observation(golden, "POST /api/admin/users/delete::success"));
        // 审计行（created_at/id 动态归一化，其余逐字段）。
        JsonNode entries = getJson(mvc, get("/api/admin/audit-log")
                .header("Authorization", auth(leaderToken))).path("entries");
        assertThat(entries.size()).isEqualTo(1);
        assertThat(entries.path(0).path("id").asText()).matches("^aud_[0-9a-f]{12}$");
        assertThat(entries.path(0).path("actor").asText()).isEqualTo(G_LEADER);
        assertThat(entries.path(0).path("action").asText()).isEqualTo("delete_users");
        assertThat(entries.path(0).path("targets").toString()).isEqualTo("[\"U20270000041\"]");
        assertThat(entries.path(0).path("created_at").asText()).matches(CREATED_AT_STR);
    }

    /* ------------------------------------------------------------------ */
    /* golden：401 unauthorized（9 条，字节精确）与 404 隐藏                  */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(2)
    void goldenUnauthorizedObservations() throws Exception {
        JsonNode golden = AdminTestHarness.loadGolden();
        MockMvc mvc = h.newMvc();
        for (JsonNode obs : golden.withArray("observations")) {
            if (!obs.path("case_id").asText().endsWith("::unauthorized")) {
                continue;
            }
            String route = obs.path("route_id").asText();
            String path = obs.path("request").path("path_template").asText()
                    .replace("{user_id}", obs.path("request").path("path_parameters")
                            .path("user_id").asText("U20270000011"))
                    .replace("{request_id}", obs.path("request").path("path_parameters")
                            .path("request_id").asText("p0-missing"));
            MockHttpServletRequestBuilder builder = route.startsWith("GET ") ? get(path)
                    : route.startsWith("PATCH ") ? patch(path) : post(path);
            mvc.perform(builder)
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                            .isEqualTo("{\"detail\":\"missing or malformed bearer token\"}"));
        }
    }

    @Test
    @Order(3)
    void goldenInvalidInputAndInvalidResourceObservations() throws Exception {
        JsonNode golden = AdminTestHarness.loadGolden();
        MockMvc mvc = h.newMvc();
        String leaderToken = h.login(mvc, G_LEADER, G_LEADER);
        for (JsonNode obs : golden.withArray("observations")) {
            String kind = obs.path("kind").asText();
            if (!kind.equals("invalid-input") && !kind.equals("invalid-resource")
                    && !kind.equals("viewer-hidden")) {
                continue;
            }
            String route = obs.path("route_id").asText();
            String path = obs.path("request").path("path_template").asText()
                    .replace("{user_id}", obs.path("request").path("path_parameters")
                            .path("user_id").asText(""))
                    .replace("{request_id}", obs.path("request").path("path_parameters")
                            .path("request_id").asText(""));
            JsonNode input = obs.path("request").path("input");
            MockHttpServletRequestBuilder builder = route.startsWith("GET ") ? get(path)
                    : route.startsWith("PATCH ") ? patch(path) : post(path);
            if (!input.isMissingNode() && !input.isNull()) {
                // 注意：{} 空对象也要作为请求体发送（JsonNode.isEmpty 对空对象为真，不能据此判断缺体）。
                builder = builder.contentType("application/json").content(input.toString());
            }
            // actor=leader 的用例带 leader token；viewer-hidden 用学生 token。
            if (kind.equals("viewer-hidden")) {
                String studentToken = h.login(mvc, "U20270000051", "U20270000051");
                builder = builder.header("Authorization", auth(studentToken));
            } else {
                builder = builder.header("Authorization", auth(leaderToken));
            }
            int expectedStatus = switch (kind) {
                case "invalid-input" -> 422;
                default -> 404;
            };
            MvcResult result = mvc.perform(builder)
                    .andExpect(status().is(expectedStatus))
                    .andExpect(header().string("Content-Type", "application/json"))
                    .andReturn();
            JsonNode actual = MAPPER.readTree(result.getResponse().getContentAsString());
            assertThat(actual).as("case %s", obs.path("case_id").asText())
                    .isEqualTo(obs.path("body"));
        }
    }

    /* ------------------------------------------------------------------ */
    /* 列表与树隔离（验收 A；Python test_user_hierarchy 对齐）               */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(10)
    void teacherListsOnlySelfAndOwnStudents() throws Exception {
        MockMvc mvc = h.newMvc();
        String token = h.login(mvc, TEACHER_A, TEACHER_A);
        JsonNode users = getJson(mvc, get("/api/admin/users").header("Authorization", auth(token)));
        assertThat(users.path("users").findValuesAsText("id"))
                .containsExactly(TEACHER_A, STUDENT_A1, STUDENT_A2);
        // 未分配学生、他班学生、其他老师、组长均不可见。
        assertThat(users.path("users").findValuesAsText("id"))
                .doesNotContain(ORPHAN, STUDENT_B1, TEACHER_B, LEADER);
    }

    @Test
    @Order(11)
    void leaderListsAllUsers() throws Exception {
        MockMvc mvc = h.newMvc();
        String token = h.login(mvc, LEADER, LEADER);
        JsonNode users = getJson(mvc, get("/api/admin/users").header("Authorization", auth(token)));
        assertThat(users.path("users").findValuesAsText("id"))
                .contains(LEADER, TEACHER_A, TEACHER_B, STUDENT_A1, STUDENT_A2, STUDENT_B1, ORPHAN);
    }

    @Test
    @Order(12)
    void teacherCannotManageOtherTeachersStudents() throws Exception {
        MockMvc mvc = h.newMvc();
        String token = h.login(mvc, TEACHER_A, TEACHER_A);
        for (MockHttpServletRequestBuilder builder : List.of(
                post("/api/admin/users/" + STUDENT_B1 + "/reset-password"),
                post("/api/admin/users/" + STUDENT_B1 + "/set-password")
                        .contentType("application/json").content("{\"new_password\":\"whateverpass1\"}"),
                post("/api/admin/users/" + STUDENT_B1 + "/status")
                        .contentType("application/json").content("{\"enabled\":false}"),
                patch("/api/admin/users/" + STUDENT_B1)
                        .contentType("application/json").content("{\"daily_quota\":5}"))) {
            mvc.perform(builder.header("Authorization", auth(token)))
                    .andExpect(status().isNotFound())
                    .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                            .isEqualTo("{\"detail\":\"not found\"}"));
        }
        // 未分配学生与其他老师同样 404。
        mvc.perform(post("/api/admin/users/" + ORPHAN + "/reset-password")
                        .header("Authorization", auth(token)))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/admin/users/" + TEACHER_B + "/reset-password")
                        .header("Authorization", auth(token)))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(13)
    void teacherManagesOwnStudentAndCanPatchQuotaOnly() throws Exception {
        MockMvc mvc = h.newMvc();
        String token = h.login(mvc, TEACHER_A, TEACHER_A);
        mvc.perform(post("/api/admin/users/" + STUDENT_A1 + "/reset-password")
                        .header("Authorization", auth(token)))
                .andExpect(status().isNoContent());
        // 重置回初始密码后可登录。
        h.login(mvc, STUDENT_A1, STUDENT_A1);
        // 老师不能改角色与归属（400，不是 404：请求体先通过校验）。
        for (String body : new String[]{"{\"roles\":[\"viewer\"]}",
                "{\"created_by\":\"" + TEACHER_A + "\"}"}) {
            mvc.perform(patch("/api/admin/users/" + STUDENT_A1)
                            .contentType("application/json")
                            .header("Authorization", auth(token)).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                            .isEqualTo("{\"detail\":\"only leaders can change roles or ownership\"}"));
        }
        // 调配额可以。
        MvcResult result = mvc.perform(patch("/api/admin/users/" + STUDENT_A1)
                        .contentType("application/json")
                        .header("Authorization", auth(token)).content("{\"daily_quota\":7}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(MAPPER.readTree(result.getResponse().getContentAsString())
                .path("daily_quota").asInt()).isEqualTo(7);
        // 学生登录主体进组端点 → 404。
        String studentToken = h.login(mvc, STUDENT_A2, STUDENT_A2);
        mvc.perform(get("/api/admin/users").header("Authorization", auth(studentToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(14)
    void leaderManagesAnyUser() throws Exception {
        MockMvc mvc = h.newMvc();
        String token = h.login(mvc, LEADER, LEADER);
        mvc.perform(post("/api/admin/users/" + STUDENT_B1 + "/reset-password")
                        .header("Authorization", auth(token)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/users/" + TEACHER_B + "/status")
                        .contentType("application/json")
                        .header("Authorization", auth(token)).content("{\"enabled\":false}"))
                .andExpect(status().isNoContent());
        // 恢复启用，避免影响后续用例使用 teacher_b token。
        mvc.perform(post("/api/admin/users/" + TEACHER_B + "/status")
                        .contentType("application/json")
                        .header("Authorization", auth(token)).content("{\"enabled\":true}"))
                .andExpect(status().isNoContent());
    }

    /* ------------------------------------------------------------------ */
    /* 创建权限与防提权（验收 B）                                            */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(20)
    void teacherCreatesStudentWithOwnership() throws Exception {
        MockMvc mvc = h.newMvc();
        String token = h.login(mvc, TEACHER_A, TEACHER_A);
        JsonNode created = postJson(mvc, post("/api/admin/users")
                .contentType("application/json").header("Authorization", auth(token))
                .content("{\"ids\":[\"U20260000099\"]}"));
        assertThat(stringArray(created.path("created"))).containsExactly("U20260000099");
        assertThat(stringArray(created.path("skipped"))).isEmpty();
        JsonNode users = getJson(mvc, get("/api/admin/users")
                .header("Authorization", auth(h.login(mvc, LEADER, LEADER))));
        JsonNode row = userById(users, "U20260000099");
        assertThat(row.path("roles").toString()).isEqualTo("[\"viewer\"]");
        assertThat(row.path("created_by").asText()).isEqualTo(TEACHER_A);
        assertThat(row.path("must_change_password").asBoolean()).isTrue();
    }

    @Test
    @Order(21)
    void teacherCannotCreateTeacherOrOperator() throws Exception {
        MockMvc mvc = h.newMvc();
        String token = h.login(mvc, TEACHER_A, TEACHER_A);
        for (String roles : new String[]{"[\"admin\"]", "[\"operator\"]", "[\"viewer\",\"admin\"]"}) {
            mvc.perform(post("/api/admin/users").contentType("application/json")
                            .header("Authorization", auth(token))
                            .content("{\"ids\":[\"U20260000098\"],\"roles\":" + roles + "}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                            .isEqualTo("{\"detail\":\"teachers can only create viewer accounts\"}"));
        }
    }

    @Test
    @Order(22)
    void apiCannotGrantLeaderRole() throws Exception {
        MockMvc mvc = h.newMvc();
        String token = h.login(mvc, LEADER, LEADER);
        mvc.perform(post("/api/admin/users").contentType("application/json")
                        .header("Authorization", auth(token))
                        .content("{\"ids\":[\"U20260000097\"],\"roles\":[\"leader\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo("{\"detail\":\"role 'leader' can only be granted via the CLI bootstrap\"}"));
        mvc.perform(patch("/api/admin/users/" + STUDENT_A1).contentType("application/json")
                        .header("Authorization", auth(token))
                        .content("{\"roles\":[\"leader\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo("{\"detail\":\"role 'leader' can only be granted via the CLI bootstrap\"}"));
    }

    @Test
    @Order(23)
    void leaderCreatesTeacherAndRoleValidation() throws Exception {
        MockMvc mvc = h.newMvc();
        String token = h.login(mvc, LEADER, LEADER);
        postJson(mvc, post("/api/admin/users").contentType("application/json")
                .header("Authorization", auth(token))
                .content("{\"ids\":[\"U20260000096\"],\"roles\":[\"admin\"]}"));
        JsonNode users = getJson(mvc, get("/api/admin/users").header("Authorization", auth(token)));
        JsonNode row = userById(users, "U20260000096");
        assertThat(row.path("roles").toString()).isEqualTo("[\"admin\"]");
        assertThat(row.path("created_by").asText()).isEqualTo(LEADER);
        // 非法角色名 → 服务端子集校验（文案逐字）。
        mvc.perform(post("/api/admin/users").contentType("application/json")
                        .header("Authorization", auth(token))
                        .content("{\"ids\":[\"U20260000095\"],\"roles\":[\"hacker\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo("{\"detail\":\"roles must be a non-empty subset of "
                                + "['admin', 'leader', 'operator', 'viewer']\"}"));
        // PATCH 空角色列表同样触发子集校验。
        mvc.perform(patch("/api/admin/users/" + STUDENT_A2).contentType("application/json")
                        .header("Authorization", auth(token)).content("{\"roles\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo("{\"detail\":\"roles must be a non-empty subset of "
                                + "['admin', 'leader', 'operator', 'viewer']\"}"));
    }

    @Test
    @Order(24)
    void createUsersSkipsInvalidAndDuplicateIds() throws Exception {
        MockMvc mvc = h.newMvc();
        String token = h.login(mvc, LEADER, LEADER);
        JsonNode result = postJson(mvc, post("/api/admin/users")
                .contentType("application/json").header("Authorization", auth(token))
                .content("{\"ids\":[\"bad-id\",\"U20260000011\",\"U20260000094\"]}"));
        assertThat(result.path("created").toString()).isEqualTo("[\"U20260000094\"]");
        assertThat(stringArray(result.path("skipped")))
                .containsExactlyInAnyOrder("bad-id", "U20260000011");
        // 同请求重复 id：第一次 created、第二次因已存在 skipped。
        JsonNode dup = postJson(mvc, post("/api/admin/users")
                .contentType("application/json").header("Authorization", auth(token))
                .content("{\"ids\":[\"U20260000093\",\"U20260000093\"]}"));
        assertThat(dup.path("created").toString()).isEqualTo("[\"U20260000093\"]");
        assertThat(dup.path("skipped").toString()).isEqualTo("[\"U20260000093\"]");
    }

    /* ------------------------------------------------------------------ */
    /* 组长特权与自保护（验收 B）                                            */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(30)
    void leaderTransfersOwnership() throws Exception {
        MockMvc mvc = h.newMvc();
        String token = h.login(mvc, LEADER, LEADER);
        MvcResult result = mvc.perform(patch("/api/admin/users/" + ORPHAN)
                        .contentType("application/json").header("Authorization", auth(token))
                        .content("{\"created_by\":\"" + TEACHER_B + "\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(MAPPER.readTree(result.getResponse().getContentAsString())
                .path("created_by").asText()).isEqualTo(TEACHER_B);
        // 转归属后 TEACHER_B 可见、TEACHER_A 不可见。
        JsonNode usersB = getJson(mvc, get("/api/admin/users")
                .header("Authorization", auth(h.login(mvc, TEACHER_B, TEACHER_B))));
        assertThat(usersB.path("users").findValuesAsText("id")).contains(ORPHAN);
        JsonNode usersA = getJson(mvc, get("/api/admin/users")
                .header("Authorization", auth(h.login(mvc, TEACHER_A, TEACHER_A))));
        assertThat(usersA.path("users").findValuesAsText("id")).doesNotContain(ORPHAN);
    }

    @Test
    @Order(31)
    void leaderTransferOwnershipValidatesTarget() throws Exception {
        MockMvc mvc = h.newMvc();
        String token = h.login(mvc, LEADER, LEADER);
        for (String target : new String[]{"U20269999999", ORPHAN}) {
            mvc.perform(patch("/api/admin/users/" + ORPHAN)
                            .contentType("application/json").header("Authorization", auth(token))
                            .content("{\"created_by\":\"" + target + "\"}"))
                    .andExpect(status().isBadRequest());
        }
        assertThat(h.columnValue("users", "created_by", "id=?", ORPHAN)).isEqualTo(TEACHER_B);
    }

    @Test
    @Order(32)
    void cannotDisableSelfOrChangeOwnRoles() throws Exception {
        MockMvc mvc = h.newMvc();
        String token = h.login(mvc, LEADER, LEADER);
        mvc.perform(post("/api/admin/users/" + LEADER + "/status")
                        .contentType("application/json").header("Authorization", auth(token))
                        .content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo("{\"detail\":\"cannot disable your own account\"}"));
        mvc.perform(patch("/api/admin/users/" + LEADER)
                        .contentType("application/json").header("Authorization", auth(token))
                        .content("{\"roles\":[\"admin\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo("{\"detail\":\"cannot change your own roles\"}"));
        // 老师停自己也走 400（自停检查先于树检查）。
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        mvc.perform(post("/api/admin/users/" + TEACHER_A + "/status")
                        .contentType("application/json").header("Authorization", auth(teacherToken))
                        .content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest());
        assertThat(h.columnValue("users", "enabled", "id=?", LEADER)).isEqualTo("t");
    }

    @Test
    @Order(33)
    void staticTokenBypassesTree() throws Exception {
        MockMvc mvc = h.newMvc();
        // 静态应急 token 主体不在 users 表中，按组长语义放行。
        mvc.perform(post("/api/admin/users/" + ORPHAN + "/reset-password")
                        .header("Authorization", auth(AdminTestHarness.FALLBACK_TOKEN)))
                .andExpect(status().isNoContent());
        JsonNode users = getJson(mvc, get("/api/admin/users")
                .header("Authorization", auth(AdminTestHarness.FALLBACK_TOKEN)));
        assertThat(users.path("users").findValuesAsText("id")).contains(LEADER, ORPHAN);
    }

    /* ------------------------------------------------------------------ */
    /* 分配（验收 C：转班目标须启用中 teacher/leader）                       */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(40)
    void assignStudentsRules() throws Exception {
        MockMvc mvc = h.newMvc();
        String token = h.login(mvc, LEADER, LEADER);
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        // 老师不能改归属。
        mvc.perform(post("/api/admin/users/assign").contentType("application/json")
                        .header("Authorization", auth(teacherToken))
                        .content("{\"ids\":[\"" + STUDENT_A1 + "\"],\"created_by\":\"" + TEACHER_A + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo("{\"detail\":\"only leaders can change ownership\"}"));
        // 目标必须是存在、启用中的 teacher/leader。
        for (String target : new String[]{"U20269999999", STUDENT_A1}) {
            mvc.perform(post("/api/admin/users/assign").contentType("application/json")
                            .header("Authorization", auth(token))
                            .content("{\"ids\":[\"U20260000092\"],\"created_by\":\"" + target + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                            .isEqualTo("{\"detail\":\"assign target must be an enabled teacher account\"}"));
        }
        // 停用的老师也不行（随后恢复）。
        mvc.perform(post("/api/admin/users/" + TEACHER_B + "/status").contentType("application/json")
                        .header("Authorization", auth(token)).content("{\"enabled\":false}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/users/assign").contentType("application/json")
                        .header("Authorization", auth(token))
                        .content("{\"ids\":[\"U20260000092\"],\"created_by\":\"" + TEACHER_B + "\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/users/" + TEACHER_B + "/status").contentType("application/json")
                        .header("Authorization", auth(token)).content("{\"enabled\":true}"))
                .andExpect(status().isNoContent());
        // 混合批次：缺失/非纯 viewer 跳过，纯 viewer 成功。
        JsonNode result = postJson(mvc, post("/api/admin/users/assign")
                .contentType("application/json").header("Authorization", auth(token))
                .content("{\"ids\":[\"U20269999999\",\"" + TEACHER_A + "\",\"" + STUDENT_A1 + "\"],"
                        + "\"created_by\":\"" + TEACHER_B + "\"}"));
        assertThat(result.path("assigned").toString())
                .isEqualTo("[\"" + STUDENT_A1 + "\"]");
        assertThat(stringArray(result.path("skipped")))
                .containsExactlyInAnyOrder("U20269999999", TEACHER_A);
        assertThat(h.columnValue("users", "created_by", "id=?", STUDENT_A1)).isEqualTo(TEACHER_B);
    }

    /* ------------------------------------------------------------------ */
    /* 非 account 模式隐藏端点组                                             */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(50)
    void nonAccountModeHidesEndpoints() throws Exception {
        AuthServerSettings original = h.settings.current();
        h.settings.replace(AuthServerSettings.defaults());
        try {
            MockMvc mvc = h.newMvc();
            // local 模式免凭证主体也 404。
            mvc.perform(get("/api/admin/users"))
                    .andExpect(status().isNotFound())
                    .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                            .isEqualTo("{\"detail\":\"not found\"}"));
            mvc.perform(post("/api/admin/users").contentType("application/json")
                            .content("{\"ids\":[\"U20260000091\"]}"))
                    .andExpect(status().isNotFound());
        } finally {
            h.settings.replace(original);
        }
    }

    /* ------------------------------------------------------------------ */
    /* 班级范围查询端口（实现步骤 4）                                         */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(60)
    void classScopePortSemantics() {
        Principal leaderP = new Principal(LEADER, List.of("leader"), 0, false);
        Principal teacherP = new Principal(TEACHER_A, List.of("admin"), 0, false);
        Principal staticP = new Principal("emergency-admin", List.of("admin"), 0, false);
        assertThat(h.scopeQueries.scopeCreator(leaderP)).isEqualTo(Optional.empty());
        assertThat(h.scopeQueries.scopeCreator(staticP)).isEqualTo(Optional.empty());
        assertThat(h.scopeQueries.scopeCreator(teacherP)).isEqualTo(Optional.of(TEACHER_A));
        // grading 口径：只含学生；usage 口径：本人在前 + 学生。
        // A1 已被 assign 测试转给 TEACHER_B；U20260000099 为 teacher A 自建学生。
        assertThat(h.scopeQueries.studentIds(TEACHER_A))
                .containsExactly(STUDENT_A2, "U20260000099");
        assertThat(h.scopeQueries.visibleSubjects(TEACHER_A))
                .containsExactly(TEACHER_A, STUDENT_A2, "U20260000099");
        assertThat(h.scopeQueries.visibleSubjects(TEACHER_B))
                .containsExactly(TEACHER_B, STUDENT_A1, STUDENT_B1, ORPHAN);
    }

    /* ------------------------------------------------------------------ */
    /* 辅助                                                                 */
    /* ------------------------------------------------------------------ */

    private static String auth(String token) {
        return "Bearer " + token;
    }

    private static JsonNode observation(JsonNode golden, String caseId) {
        for (JsonNode obs : golden.withArray("observations")) {
            if (caseId.equals(obs.path("case_id").asText())) {
                return obs.path("body");
            }
        }
        throw new AssertionError("golden observation 不存在: " + caseId);
    }

    private static List<String> stringArray(JsonNode array) {
        List<String> out = new java.util.ArrayList<>();
        array.forEach(item -> out.add(item.asText()));
        return out;
    }

    private static JsonNode userById(JsonNode usersBody, String id) {
        for (JsonNode user : usersBody.path("users")) {
            if (id.equals(user.path("id").asText())) {
                return user;
            }
        }
        throw new AssertionError("用户不存在于列表: " + id);
    }

    private static JsonNode getJson(MockMvc mvc, MockHttpServletRequestBuilder builder)
            throws Exception {
        MvcResult result = mvc.perform(builder)
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/json"))
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode postJson(MockMvc mvc, MockHttpServletRequestBuilder builder)
            throws Exception {
        MvcResult result = mvc.perform(builder)
                .andExpect(status().isOk())
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString());
    }
}
