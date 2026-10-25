package io.dpdk.assistant.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dpdk.assistant.assignment.catalog.SubmissionSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.regex.Pattern;
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
 * J2-03 目录契约测试（验收 A/B/C + protocols + 受理快照端口）：
 * 守卫（非 account 404 / viewer 404 / operator 只读 / 401 精确 detail）、
 * scope 服务端产生、树隔离可见性、CRUD 权限与事务回滚、golden 逐字段断言
 * （动态 identity/created_at/updated_at 按 evidence/J2-03/golden-diff.md 映射）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class AssignmentCatalogContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Python str(datetime) 形态（golden 时间字段归一化映射）。 */
    private static final Pattern PYTHON_STR_TIME =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d{6})?\\+00:00$");

    // golden 合成树（U2027 命名空间，与 oracle IDS 一致）。
    private static final String G_LEADER = "U20270000001";
    private static final String G_TEACHER_A = "U20270000002";
    private static final String G_TEACHER_B = "U20270000003";
    private static final String G_STUDENT_A = "U20270000011";
    private static final String G_STUDENT_B = "U20270000021";
    private static final String G_STUDENT_FREE = "U20270000031";

    // 行为测试树（U2026 命名空间，与 golden 树互不污染）。
    private static final String LEADER = "U20260000001";
    private static final String TEACHER_A = "U20260000002";
    private static final String TEACHER_B = "U20260000003";
    private static final String STUDENT_A1 = "U20260000011";
    private static final String STUDENT_B1 = "U20260000021";
    private static final String STUDENT_FREE = "U20260000031";

    /** 受理/成绩合成行的固定时间（对位 oracle logical tick 60）。 */
    private static final Instant SUBMISSION_AT = Instant.parse("2026-10-24T04:00:00.000060Z");

    private static AssignmentTestHarness h;

    @BeforeAll
    static void setup() throws Exception {
        h = AssignmentTestHarness.accountMode("catalog");
        // golden 合成树
        h.seedUser(G_LEADER, "[\"leader\"]", 10000, true, true, G_LEADER, null);
        h.seedUser(G_TEACHER_A, "[\"admin\"]", 10000, true, true, G_TEACHER_A, G_LEADER);
        h.seedUser(G_TEACHER_B, "[\"admin\"]", 10000, true, true, G_TEACHER_B, G_LEADER);
        h.seedUser(G_STUDENT_A, "[\"viewer\"]", 10000, true, true, G_STUDENT_A, G_TEACHER_A);
        h.seedUser(G_STUDENT_B, "[\"viewer\"]", 10000, true, true, G_STUDENT_B, G_TEACHER_B);
        h.seedUser(G_STUDENT_FREE, "[\"viewer\"]", 10000, true, true, G_STUDENT_FREE, null);
        // 行为测试树
        h.seedUser(LEADER, "[\"leader\"]", null, true, true, LEADER, null);
        h.seedUser(TEACHER_A, "[\"admin\"]", null, true, true, TEACHER_A, LEADER);
        h.seedUser(TEACHER_B, "[\"admin\"]", null, true, true, TEACHER_B, LEADER);
        h.seedUser(STUDENT_A1, "[\"viewer\"]", null, true, true, STUDENT_A1, TEACHER_A);
        h.seedUser(STUDENT_B1, "[\"viewer\"]", null, true, true, STUDENT_B1, TEACHER_B);
        h.seedUser(STUDENT_FREE, "[\"viewer\"]", null, true, true, STUDENT_FREE, null);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    /* ------------------------------------------------------------------ */
    /* golden 流程（按 fixture 顺序；submit/explain 属 J2-04 以合成成绩替代） */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(1)
    void goldenFlowInFixtureOrder() throws Exception {
        JsonNode golden = AssignmentTestHarness.loadGolden();
        h.harness.setProtocolsJson("{\"ok\":true,\"protocols\":["
                + "{\"name\":\"dns\",\"cases\":5,\"categories\":{\"basic\":5}}]}");
        MockMvc mvc = h.newMvc();
        String leaderToken = h.login(mvc, G_LEADER, G_LEADER);
        String teacherToken = h.login(mvc, G_TEACHER_A, G_TEACHER_A);
        String studentToken = h.login(mvc, G_STUDENT_A, G_STUDENT_A);

        // GET /api/assignments::success（leader 空态）
        assertThat(getJson(mvc, get("/api/assignments").header("Authorization", auth(leaderToken))))
                .isEqualTo(observation(golden, "GET /api/assignments::success"));

        // GET /api/assignments/protocols::success（harness 替身；error=null）
        assertThat(getJson(mvc, get("/api/assignments/protocols")
                .header("Authorization", auth(leaderToken))))
                .isEqualTo(observation(golden, "GET /api/assignments/protocols::success"));

        // POST /api/assignments::success（teacher_a 建 class 作业）
        MvcResult created = mvc.perform(post("/api/assignments")
                        .contentType("application/json").header("Authorization", auth(teacherToken))
                        .content("{\"title\":\"合成DNS作业\",\"description\":\"P0\","
                                + "\"deadline\":\"2099-01-01T00:00:00Z\","
                                + "\"items\":[{\"protocol\":\"dns\",\"points\":20}]}"))
                .andExpect(status().isCreated()).andReturn();
        String aid = MAPPER.readTree(created.getResponse().getContentAsByteArray())
                .path("id").asText();
        assertThat(aid).matches("^asg_[a-f0-9]{16}$");
        assertGoldenWithDynamicIdAndTimes(created, golden,
                "POST /api/assignments::success", aid, "<identity:19>");

        // GET /{assignment_id}::success（student_a 详情：未提交 earned=null）
        assertGoldenWithDynamicIdAndTimes(mvc.perform(get("/api/assignments/" + aid)
                        .header("Authorization", auth(studentToken))).andReturn(), golden,
                "GET /api/assignments/{assignment_id}::success", aid, "<identity:19>");

        // PATCH::success（改名）
        assertGoldenWithDynamicIdAndTimes(mvc.perform(patch("/api/assignments/" + aid)
                        .contentType("application/json").header("Authorization", auth(teacherToken))
                        .content("{\"title\":\"合成DNS作业修订\"}")).andReturn(), golden,
                "PATCH /api/assignments/{assignment_id}::success", aid, "<identity:19>");

        // （Python 在此执行 submit + explain——J2-04 范围；以合成已落库成绩替代，
        // created_at 取 oracle tick 60，供成绩/CSV 逐字节断言。）
        h.seedSubmission("grade_" + "0".repeat(12) + "601", G_STUDENT_A, "dns", 80, aid,
                SUBMISSION_AT, null);

        // GET /api/assignments::success-nonempty-manager（teacher_a：统计 1/1）
        JsonNode managerList = getJson(mvc,
                get("/api/assignments").header("Authorization", auth(teacherToken)));
        JsonNode expectedManager = normalizeDynamic(observation(golden,
                "GET /api/assignments::success-nonempty-manager"));
        assertThat(managerList.path("assignments").size()).isEqualTo(1);
        assertThat(normalizeDynamic(substitute(managerList, aid, "<identity:19>")))
                .isEqualTo(expectedManager);

        // GET /api/assignments::success-nonempty-student（student_a：best 80 / earned 16.0）
        JsonNode studentList = getJson(mvc,
                get("/api/assignments").header("Authorization", auth(studentToken)));
        JsonNode expectedStudent = normalizeDynamic(observation(golden,
                "GET /api/assignments::success-nonempty-student"));
        assertThat(studentList.path("assignments").size()).isEqualTo(1);
        assertThat(normalizeDynamic(substitute(studentList, aid, "<identity:19>")))
                .isEqualTo(expectedStudent);

        // withdraw / reactivate（golden 末段；状态与 updated_at 归一化断言）
        assertGoldenWithDynamicIdAndTimes(mvc.perform(post("/api/assignments/" + aid + "/withdraw")
                        .header("Authorization", auth(teacherToken))).andReturn(), golden,
                "POST /api/assignments/{assignment_id}/withdraw::success", aid, "<identity:19>");
        assertGoldenWithDynamicIdAndTimes(mvc.perform(post("/api/assignments/" + aid + "/reactivate")
                        .header("Authorization", auth(teacherToken))).andReturn(), golden,
                "POST /api/assignments/{assignment_id}/reactivate::success", aid, "<identity:19>");

        // negative：unauthorized（401 精确 detail，逐路由）
        for (String route : new String[] {"GET /api/assignments", "GET /api/assignments/protocols",
                "GET /api/assignments/{assignment_id}", "PATCH /api/assignments/{assignment_id}",
                "POST /api/assignments", "POST /api/assignments/{assignment_id}/withdraw",
                "POST /api/assignments/{assignment_id}/reactivate"}) {
            String path = route.split(" ", 2)[1].replace("{assignment_id}", aid);
            MvcResult result = mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .request(org.springframework.http.HttpMethod.valueOf(
                                            route.split(" ", 2)[0]), path)
                                    .contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized()).andReturn();
            assertThat(MAPPER.readTree(result.getResponse().getContentAsByteArray()))
                    .isEqualTo(observation(golden, route + "::unauthorized"));
        }

        // invalid-input（422 精确 detail 数组）
        assertThat(MAPPER.readTree(mvc.perform(post("/api/assignments")
                        .contentType("application/json").header("Authorization", auth(leaderToken))
                        .content("{}")).andExpect(status().isUnprocessableEntity()).andReturn()
                .getResponse().getContentAsByteArray()))
                .isEqualTo(observation(golden, "POST /api/assignments::invalid-input"));
        assertThat(MAPPER.readTree(mvc.perform(patch("/api/assignments/" + aid)
                        .contentType("application/json").header("Authorization", auth(leaderToken))
                        .content("{\"title\":\"\"}"))
                .andExpect(status().isUnprocessableEntity()).andReturn()
                .getResponse().getContentAsByteArray()))
                .isEqualTo(observation(golden,
                        "PATCH /api/assignments/{assignment_id}::invalid-input"));

        // invalid-resource（p0-missing → 404 {"detail":"not found"}，leader 视角）
        for (String route : new String[] {"GET /api/assignments/{assignment_id}",
                "POST /api/assignments/{assignment_id}/withdraw",
                "POST /api/assignments/{assignment_id}/reactivate"}) {
            String path = route.split(" ", 2)[1].replace("{assignment_id}", "p0-missing");
            MvcResult result = mvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .request(org.springframework.http.HttpMethod.valueOf(
                                            route.split(" ", 2)[0]), path)
                                    .header("Authorization", auth(leaderToken)))
                    .andExpect(status().isNotFound()).andReturn();
            assertThat(MAPPER.readTree(result.getResponse().getContentAsByteArray()))
                    .isEqualTo(observation(golden, route + "::invalid-resource"));
        }

        // foreign-tree（student_b 不可见 teacher_a 的 class 作业 → 同口径 404）
        String studentBToken = h.login(mvc, G_STUDENT_B, G_STUDENT_B);
        assertThat(MAPPER.readTree(mvc.perform(get("/api/assignments/" + aid)
                        .header("Authorization", auth(studentBToken)))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsByteArray()))
                .isEqualTo(observation(golden, "GET /api/assignments/{assignment_id}::foreign-tree"));
    }

    /* ------------------------------------------------------------------ */
    /* 守卫（验收 A）                                                       */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(2)
    void nonAccountModeHidesWholeGroup() throws Exception {
        AssignmentTestHarness local = AssignmentTestHarness.create("localmode",
                io.dpdk.assistant.auth.AuthServerSettings.of("local", "{}", 7, 8, 4, 200));
        try {
            MockMvc mvc = local.newMvc();
            for (String path : new String[] {"/api/assignments", "/api/assignments/protocols",
                    "/api/assignments/asg_missing"}) {
                assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus())
                        .as("local 模式 %s 应整组 404", path).isEqualTo(404);
            }
            assertThat(mvc.perform(post("/api/assignments").contentType("application/json")
                    .content("{}")).andReturn().getResponse().getStatus()).isEqualTo(404);
        } finally {
            local.drop();
        }
    }

    @Test
    @Order(3)
    void viewerRoleCannotManage404() throws Exception {
        MockMvc mvc = h.newMvc();
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        String aid = createAssignment(mvc, teacherToken);
        String viewer = "Bearer " + AssignmentTestHarness.TOKEN_VIEWER;
        assertThat(postStatus(mvc, post("/api/assignments").header("Authorization", viewer)
                .contentType("application/json").content("{}"))).isEqualTo(404);
        assertThat(getStatus(mvc, get("/api/assignments/protocols").header("Authorization", viewer)))
                .isEqualTo(404);
        assertThat(patchStatus(mvc, patch("/api/assignments/" + aid)
                .header("Authorization", viewer).contentType("application/json").content("{}")))
                .isEqualTo(404);
        assertThat(postStatus(mvc, post("/api/assignments/" + aid + "/withdraw")
                .header("Authorization", viewer))).isEqualTo(404);
        assertThat(postStatus(mvc, post("/api/assignments/" + aid + "/reactivate")
                .header("Authorization", viewer))).isEqualTo(404);
        assertThat(getStatus(mvc, get("/api/assignments/" + aid + "/grades")
                .header("Authorization", viewer))).isEqualTo(404);
        assertThat(getStatus(mvc, get("/api/assignments/" + aid + "/export")
                .header("Authorization", viewer))).isEqualTo(404);
    }

    @Test
    @Order(4)
    void operatorCanViewButNotManage() throws Exception {
        MockMvc mvc = h.newMvc();
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        String aid = createAssignment(mvc, teacherToken);
        String operator = "Bearer " + AssignmentTestHarness.TOKEN_OPERATOR;
        // 可列表/详情/成绩/导出（静态 operator 主体，树隔离豁免）。
        assertThat(getStatus(mvc, get("/api/assignments").header("Authorization", operator)))
                .isEqualTo(200);
        assertThat(getStatus(mvc, get("/api/assignments/" + aid).header("Authorization", operator)))
                .isEqualTo(200);
        assertThat(getStatus(mvc, get("/api/assignments/" + aid + "/grades")
                .header("Authorization", operator))).isEqualTo(200);
        assertThat(getStatus(mvc, get("/api/assignments/" + aid + "/export")
                .header("Authorization", operator))).isEqualTo(200);
        // 不能发布/修改/撤回（依赖门禁先于 422：带空体也 404）。
        assertThat(postStatus(mvc, post("/api/assignments").header("Authorization", operator)
                .contentType("application/json").content("{}"))).isEqualTo(404);
        assertThat(patchStatus(mvc, patch("/api/assignments/" + aid)
                .header("Authorization", operator).contentType("application/json").content("{}")))
                .isEqualTo(404);
        assertThat(postStatus(mvc, post("/api/assignments/" + aid + "/withdraw")
                .header("Authorization", operator))).isEqualTo(404);
    }

    /* ------------------------------------------------------------------ */
    /* CRUD 与可见性（验收 B/C）                                            */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(5)
    void createScopeByRole() throws Exception {
        MockMvc mvc = h.newMvc();
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        String leaderToken = h.login(mvc, LEADER, LEADER);
        JsonNode teacherAssignment = createAssignmentJson(mvc, teacherToken);
        assertThat(teacherAssignment.path("scope").asText()).isEqualTo("class");
        assertThat(teacherAssignment.path("created_by").asText()).isEqualTo(TEACHER_A);
        assertThat(teacherAssignment.path("status").asText()).isEqualTo("active");
        // items 按协议字典序（_load_items ORDER BY protocol）。
        assertThat(teacherAssignment.path("items").toString())
                .isEqualTo("[{\"protocol\":\"dns\",\"points\":20},{\"protocol\":\"http\",\"points\":30}]");
        JsonNode leaderAssignment = MAPPER.readTree(mvc.perform(post("/api/assignments")
                        .contentType("application/json").header("Authorization", auth(leaderToken))
                        .content("{\"title\":\"组长作业\",\"description\":\"描述\","
                                + "\"deadline\":\"2099-06-01T00:00:00Z\","
                                + "\"items\":[{\"protocol\":\"dns\",\"points\":10}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
        assertThat(leaderAssignment.path("scope").asText()).isEqualTo("all");
    }

    @Test
    @Order(6)
    void treeIsolationVisibility() throws Exception {
        MockMvc mvc = h.newMvc();
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        String leaderToken = h.login(mvc, LEADER, LEADER);
        String studentA = h.login(mvc, STUDENT_A1, STUDENT_A1);
        String studentB = h.login(mvc, STUDENT_B1, STUDENT_B1);
        String studentFree = h.login(mvc, STUDENT_FREE, STUDENT_FREE);
        JsonNode teacherAssignment = createAssignmentJson(mvc, teacherToken);
        JsonNode leaderAssignment = createAssignmentJson(mvc, leaderToken);

        // 库内含前序用例作业，按本用例创建的 id 断言包含/排除关系。
        java.util.Set<String> studentAIds = visibleIds(mvc, studentA);
        java.util.Set<String> studentBIds = visibleIds(mvc, studentB);
        java.util.Set<String> freeIds = visibleIds(mvc, studentFree);
        String teacherAid = teacherAssignment.path("id").asText();
        String leaderAid = leaderAssignment.path("id").asText();
        assertThat(studentAIds).contains(teacherAid, leaderAid);
        assertThat(studentBIds).contains(leaderAid).doesNotContain(teacherAid);
        assertThat(freeIds).contains(leaderAid).doesNotContain(teacherAid);
        // 别班学生取详情 → 404（不泄露存在性）。
        assertThat(getStatus(mvc, get("/api/assignments/" + teacherAssignment.path("id").asText())
                .header("Authorization", auth(studentB)))).isEqualTo(404);
    }

    @Test
    @Order(7)
    void managerListScopingAndStats() throws Exception {
        MockMvc mvc = h.newMvc();
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        String teacherBToken = h.login(mvc, TEACHER_B, TEACHER_B);
        String leaderToken = h.login(mvc, LEADER, LEADER);
        String aid = createAssignment(mvc, teacherToken);
        createAssignmentJson(mvc, teacherBToken, "B 的作业");
        createAssignmentJson(mvc, leaderToken, "组长作业");

        // teacher 只见自己创建的（不见 B 的/组长的）；leader 全部。
        assertThat(titles(mvc, teacherToken)).contains("作业一")
                .doesNotContain("B 的作业", "组长作业");
        assertThat(titles(mvc, leaderToken)).contains("作业一", "B 的作业", "组长作业");

        // 提交统计（合成成绩：A1 提交一次 → submission_count=1 / student_count=1）。
        h.seedSubmission("grade_" + "a1a1a1a1a1a1a1a1".substring(0, 16), STUDENT_A1, "dns", 80,
                aid, SUBMISSION_AT, null);
        JsonNode rows = getJson(mvc, get("/api/assignments")
                .header("Authorization", auth(teacherToken))).path("assignments");
        JsonNode row = null;
        for (JsonNode candidate : rows) {
            if (aid.equals(candidate.path("id").asText())) {
                row = candidate;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.path("submission_count").asInt()).isEqualTo(1);
        assertThat(row.path("student_count").asInt()).isEqualTo(1);
    }

    @Test
    @Order(8)
    void updatePermissionAndItemsReplace() throws Exception {
        MockMvc mvc = h.newMvc();
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        String teacherBToken = h.login(mvc, TEACHER_B, TEACHER_B);
        String leaderToken = h.login(mvc, LEADER, LEADER);
        String aid = createAssignment(mvc, teacherToken);

        // 非创建者（别的老师）→ 403 精确 detail。
        MvcResult forbidden = mvc.perform(patch("/api/assignments/" + aid)
                        .contentType("application/json").header("Authorization", auth(teacherBToken))
                        .content("{\"title\":\"改名\"}"))
                .andExpect(status().isForbidden()).andReturn();
        assertThat(MAPPER.readTree(forbidden.getResponse().getContentAsByteArray())
                .path("detail").asText()).isEqualTo("只有作业创建者或组长可以修改");

        JsonNode updated = MAPPER.readTree(mvc.perform(patch("/api/assignments/" + aid)
                        .contentType("application/json").header("Authorization", auth(teacherToken))
                        .content("{\"title\":\"改名\",\"items\":[{\"protocol\":\"dns\",\"points\":40}]}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(updated.path("title").asText()).isEqualTo("改名");
        assertThat(updated.path("items").toString())
                .isEqualTo("[{\"protocol\":\"dns\",\"points\":40}]");

        // leader 可改任意作业。
        JsonNode leaderUpdated = MAPPER.readTree(mvc.perform(patch("/api/assignments/" + aid)
                        .contentType("application/json").header("Authorization", auth(leaderToken))
                        .content("{\"description\":\"组长改的\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(leaderUpdated.path("description").asText()).isEqualTo("组长改的");

        // 不存在的作业 → 404。
        assertThat(patchStatus(mvc, patch("/api/assignments/asg_missing")
                .header("Authorization", auth(leaderToken)).contentType("application/json")
                .content("{\"title\":\"x\"}"))).isEqualTo(404);
    }

    @Test
    @Order(9)
    void duplicateProtocolRejectedAndTransactionRolledBack() throws Exception {
        MockMvc mvc = h.newMvc();
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        String aid = createAssignment(mvc, teacherToken);

        // 同一请求内重复协议 → UNIQUE(assignment_id, protocol) 冲突（Python 同样不预校验，
        // 500 由约束拒绝触发）；主行 title 更新必须随事务一并回滚（验收 C/PG 原子性）。
        boolean failed = false;
        try {
            mvc.perform(patch("/api/assignments/" + aid).contentType("application/json")
                    .header("Authorization", auth(teacherToken))
                    .content("{\"title\":\"不应生效\",\"items\":[{\"protocol\":\"dns\",\"points\":10},"
                            + "{\"protocol\":\"dns\",\"points\":20}]}")).andReturn();
        } catch (Exception e) {
            failed = true;
        }
        assertThat(failed).as("重复协议必须以存储失败终止（oracle 为 500），不能静默成功").isTrue();
        assertThat(h.columnValue("assignments", "title", "id = ?", aid)).isEqualTo("作业一");
        assertThat(h.countRows("assignment_items", "assignment_id = ?", aid)).isEqualTo(2);
    }

    @Test
    @Order(10)
    void invalidInputVariants() throws Exception {
        MockMvc mvc = h.newMvc();
        String leaderToken = h.login(mvc, LEADER, LEADER);
        // 分值非法（pydantic ge/le）。
        JsonNode pointsLow = MAPPER.readTree(mvc.perform(post("/api/assignments")
                        .contentType("application/json").header("Authorization", auth(leaderToken))
                        .content("{\"title\":\"t\",\"deadline\":\"2099-01-01T00:00:00Z\","
                                + "\"items\":[{\"protocol\":\"dns\",\"points\":0}]}"))
                .andExpect(status().isUnprocessableEntity()).andReturn()
                .getResponse().getContentAsByteArray());
        assertThat(pointsLow.at("/detail/0/type").asText()).isEqualTo("greater_than_equal");
        assertThat(pointsLow.at("/detail/0/loc/2").asInt()).isEqualTo(0);
        assertThat(pointsLow.at("/detail/0/loc/3").asText()).isEqualTo("points");
        JsonNode pointsHigh = MAPPER.readTree(mvc.perform(post("/api/assignments")
                        .contentType("application/json").header("Authorization", auth(leaderToken))
                        .content("{\"title\":\"t\",\"deadline\":\"2099-01-01T00:00:00Z\","
                                + "\"items\":[{\"protocol\":\"dns\",\"points\":1001}]}"))
                .andExpect(status().isUnprocessableEntity()).andReturn()
                .getResponse().getContentAsByteArray());
        assertThat(pointsHigh.at("/detail/0/type").asText()).isEqualTo("less_than_equal");
        // 非法截止时刻。
        JsonNode badDeadline = MAPPER.readTree(mvc.perform(post("/api/assignments")
                        .contentType("application/json").header("Authorization", auth(leaderToken))
                        .content("{\"title\":\"t\",\"deadline\":\"not-a-date\","
                                + "\"items\":[{\"protocol\":\"dns\",\"points\":10}]}"))
                .andExpect(status().isUnprocessableEntity()).andReturn()
                .getResponse().getContentAsByteArray());
        assertThat(badDeadline.at("/detail/0/type").asText()).isEqualTo("datetime_parsing");
        // naive 截止时刻按 UTC 接受（_normalize_deadline）。
        JsonNode naive = MAPPER.readTree(mvc.perform(post("/api/assignments")
                        .contentType("application/json").header("Authorization", auth(leaderToken))
                        .content("{\"title\":\"naive\",\"deadline\":\"2099-01-01T00:00:00\","
                                + "\"items\":[{\"protocol\":\"dns\",\"points\":10}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
        assertThat(naive.path("deadline").asText()).isEqualTo("2099-01-01 00:00:00+00:00");
        // 时区折算（+08:00 → UTC 渲染）。
        JsonNode offset = MAPPER.readTree(mvc.perform(post("/api/assignments")
                        .contentType("application/json").header("Authorization", auth(leaderToken))
                        .content("{\"title\":\"offset\",\"deadline\":\"2099-01-01T08:30:00+08:00\","
                                + "\"items\":[{\"protocol\":\"dns\",\"points\":10}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray());
        assertThat(offset.path("deadline").asText()).isEqualTo("2099-01-01 00:30:00+00:00");
    }

    @Test
    @Order(11)
    void withdrawAndReactivateLifecycle() throws Exception {
        MockMvc mvc = h.newMvc();
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        String teacherBToken = h.login(mvc, TEACHER_B, TEACHER_B);
        String student = h.login(mvc, STUDENT_A1, STUDENT_A1);
        String aid = createAssignment(mvc, teacherToken);

        JsonNode withdrawn = MAPPER.readTree(mvc.perform(post("/api/assignments/" + aid + "/withdraw")
                        .header("Authorization", auth(teacherToken)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(withdrawn.path("status").asText()).isEqualTo("withdrawn");

        // 撤回后：学生列表不可见、详情 404。
        assertThat(visibleIds(mvc, student)).doesNotContain(aid);
        assertThat(getStatus(mvc, get("/api/assignments/" + aid)
                .header("Authorization", auth(student)))).isEqualTo(404);

        JsonNode active = MAPPER.readTree(mvc.perform(
                        post("/api/assignments/" + aid + "/reactivate")
                                .header("Authorization", auth(teacherToken)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        assertThat(active.path("status").asText()).isEqualTo("active");
        assertThat(visibleIds(mvc, student)).contains(aid);

        // 非创建者不能撤回（403 同 detail）。
        MvcResult forbidden = mvc.perform(post("/api/assignments/" + aid + "/withdraw")
                        .header("Authorization", auth(teacherBToken)))
                .andExpect(status().isForbidden()).andReturn();
        assertThat(MAPPER.readTree(forbidden.getResponse().getContentAsByteArray())
                .path("detail").asText()).isEqualTo("只有作业创建者或组长可以修改");
    }

    /* ------------------------------------------------------------------ */
    /* protocols（验收 A + harness 降级沿 oracle）                           */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(12)
    void protocolsEndpointSuccessAndDegradation() throws Exception {
        MockMvc mvc = h.newMvc();
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        h.harness.setProtocolsJson(AssignmentTestHarness.DEFAULT_PROTOCOLS_JSON);
        int callsBefore = h.harness.listProtocolCalls.get();

        JsonNode body = getJson(mvc, get("/api/assignments/protocols")
                .header("Authorization", auth(teacherToken)));
        assertThat(body.path("error").isNull()).isTrue();
        assertThat(body.path("protocols").findValuesAsText("name")).containsExactly("dns", "http");
        assertThat(body.path("protocols").get(0).path("cases").asInt()).isEqualTo(5);
        assertThat(body.path("protocols").get(0).path("categories").path("basic").asInt())
                .isEqualTo(5);
        assertThat(h.harness.listProtocolCalls.get() - callsBefore).isEqualTo(1);

        // harness 故障：结构化降级为空清单 + error 字段（不展示为有效空协议列表）。
        h.harness.setProtocolsJson("{\"ok\":false,\"error\":\"harness_unavailable\",\"detail\":\"down\"}");
        JsonNode degraded = getJson(mvc, get("/api/assignments/protocols")
                .header("Authorization", auth(teacherToken)));
        assertThat(degraded.toString())
                .isEqualTo("{\"protocols\":[],\"error\":\"harness_unavailable\"}");
        h.harness.setProtocolsJson("{\"ok\":false}"); // error 缺省回落 list_protocols_failed
        JsonNode fallback = getJson(mvc, get("/api/assignments/protocols")
                .header("Authorization", auth(teacherToken)));
        assertThat(fallback.toString())
                .isEqualTo("{\"protocols\":[],\"error\":\"list_protocols_failed\"}");
        h.harness.setProtocolsJson(AssignmentTestHarness.DEFAULT_PROTOCOLS_JSON);
    }

    /* ------------------------------------------------------------------ */
    /* 受理快照端口（验收 E 双口径分界，J2-04 消费面）                        */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(13)
    void submissionSnapshotPortDualScope() throws Exception {
        MockMvc mvc = h.newMvc();
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        String aid = createAssignment(mvc, teacherToken);

        // 本班学生 + 成员协议 → 受理分值 20（当前定义）。
        SubmissionSnapshot snapshot = h.catalog.submissionSnapshot(aid, TEACHER_A, "dns")
                .orElseThrow();
        assertThat(snapshot.points()).isEqualTo(20);
        assertThat(snapshot.scope()).isEqualTo("class");
        assertThat(snapshot.deadline()).isEqualTo(Instant.parse("2099-01-01T00:00:00Z"));
        // 协议不属于该作业 → points=null（路由层 400 由 J2-04 渲染）。
        assertThat(h.catalog.submissionSnapshot(aid, TEACHER_A, "ssh").orElseThrow().points())
                .isNull();
        // 别班学生 → 不可见 empty。
        assertThat(h.catalog.submissionSnapshot(aid, TEACHER_B, "dns")).isEmpty();
        // 撤回 → empty。
        mvc.perform(post("/api/assignments/" + aid + "/withdraw")
                .header("Authorization", auth(teacherToken))).andReturn();
        assertThat(h.catalog.submissionSnapshot(aid, TEACHER_A, "dns")).isEmpty();

        // 汇总口径（当前定义动态）：items 改为 40 分后，受理快照读到 40；
        // 已落库成绩内嵌的 accepted_assignment（受理口径）保持 20 —— 两口径并存。
        mvc.perform(post("/api/assignments/" + aid + "/reactivate")
                .header("Authorization", auth(teacherToken))).andReturn();
        mvc.perform(patch("/api/assignments/" + aid).contentType("application/json")
                .header("Authorization", auth(teacherToken))
                .content("{\"items\":[{\"protocol\":\"dns\",\"points\":40}]}")).andReturn();
        assertThat(h.catalog.submissionSnapshot(aid, TEACHER_A, "dns").orElseThrow().points())
                .isEqualTo(40);
        String acceptedJson = "{\"points\":20,\"scope\":\"class\",\"created_by\":\""
                + TEACHER_A + "\"}";
        h.seedSubmission("grade_snap_0000000001", STUDENT_A1, "dns", 80, aid,
                SUBMISSION_AT, acceptedJson);
        assertThat(h.columnValue("grading_submissions",
                        "(results_json->'accepted_assignment'->>'points')::text", "id = ?",
                        "grade_snap_0000000001")).isEqualTo("20");
        // 历史最高分端口（best_earned 数据面）。
        assertThat(h.catalog.bestScore(aid, STUDENT_A1, "dns")).contains(80);
        assertThat(h.catalog.bestScore(aid, STUDENT_A1, "http")).isEmpty();
        // 截止判定。
        SubmissionSnapshot snap = h.catalog.submissionSnapshot(aid, TEACHER_A, "dns").orElseThrow();
        assertThat(h.catalog.isPastDeadline(snap, Instant.parse("2098-12-31T23:59:59Z"))).isFalse();
        assertThat(h.catalog.isPastDeadline(snap, Instant.parse("2099-01-02T00:00:00Z"))).isTrue();
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                              */
    /* ------------------------------------------------------------------ */

    private static String auth(String token) {
        return "Bearer " + token;
    }

    private static JsonNode getJson(MockMvc mvc, org.springframework.test.web.servlet.RequestBuilder rb)
            throws Exception {
        MvcResult result = mvc.perform(rb).andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsByteArray());
    }

    private static int getStatus(MockMvc mvc, org.springframework.test.web.servlet.RequestBuilder rb)
            throws Exception {
        return mvc.perform(rb).andReturn().getResponse().getStatus();
    }

    private static int postStatus(MockMvc mvc,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rb)
            throws Exception {
        return mvc.perform(rb).andReturn().getResponse().getStatus();
    }

    private static int patchStatus(MockMvc mvc,
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder rb)
            throws Exception {
        return mvc.perform(rb).andReturn().getResponse().getStatus();
    }

    private String createAssignment(MockMvc mvc, String token) throws Exception {
        return createAssignmentJson(mvc, token, "作业一").path("id").asText();
    }

    private JsonNode createAssignmentJson(MockMvc mvc, String token) throws Exception {
        return createAssignmentJson(mvc, token, "作业一");
    }

    private JsonNode createAssignmentJson(MockMvc mvc, String token, String title) throws Exception {
        return MAPPER.readTree(mvc.perform(post("/api/assignments")
                        .contentType("application/json").header("Authorization", auth(token))
                        .content("{\"title\":\"" + title + "\",\"description\":\"描述\","
                                + "\"deadline\":\"2099-01-01T00:00:00Z\","
                                + "\"items\":[{\"protocol\":\"dns\",\"points\":20},"
                                + "{\"protocol\":\"http\",\"points\":30}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsByteArray());
    }

    private java.util.Set<String> visibleIds(MockMvc mvc, String studentToken) throws Exception {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (JsonNode row : getJson(mvc, get("/api/assignments")
                .header("Authorization", auth(studentToken))).path("assignments")) {
            out.add(row.path("id").asText());
        }
        return out;
    }

    private java.util.Set<String> titles(MockMvc mvc, String token) throws Exception {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (JsonNode row : getJson(mvc, get("/api/assignments")
                .header("Authorization", auth(token))).path("assignments")) {
            out.add(row.path("title").asText());
        }
        return out;
    }

    private static JsonNode observation(JsonNode golden, String caseId) {
        for (JsonNode o : golden.path("observations")) {
            if (caseId.equals(o.path("case_id").asText())) {
                return o.path("body");
            }
        }
        throw new IllegalStateException("golden 缺少 observation: " + caseId);
    }

    /** 动态 identity 替换（实际 id ↔ <identity:N> 占位符）。 */
    private static JsonNode substitute(JsonNode node, String from, String to) {
        return MAPPER.convertValue(replaceNode(node, from, to), JsonNode.class);
    }

    private static Object replaceNode(JsonNode node, String from, String to) {
        if (node.isTextual() && node.asText().equals(from)) {
            return to;
        }
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode out = MAPPER.createObjectNode();
            node.fields().forEachRemaining(e -> out.set(e.getKey(),
                    MAPPER.convertValue(replaceNode(e.getValue(), from, to), JsonNode.class)));
            return out;
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode item : node) {
                out.add(MAPPER.convertValue(replaceNode(item, from, to), JsonNode.class));
            }
            return out;
        }
        return node;
    }

    /** 动态时间字段（created_at/updated_at）先校验形态再替换为 golden 期望值。 */
    private static JsonNode normalizeDynamic(JsonNode actual) {
        JsonNode out = actual.deepCopy();
        for (String field : new String[] {"created_at", "updated_at"}) {
            if (out.isObject() && out.has(field)) {
                String value = out.get(field).asText();
                assertThat(value).as("%s 必须为 Python str(datetime) 形态", field)
                        .matches(PYTHON_STR_TIME);
                ((ObjectNode) out).put(field, "<normalized>");
            }
        }
        if (out.has("assignments")) {
            for (JsonNode row : out.path("assignments")) {
                for (String field : new String[] {"created_at", "updated_at"}) {
                    if (row.has(field)) {
                        assertThat(row.get(field).asText()).matches(PYTHON_STR_TIME);
                        ((ObjectNode) row).put(field, "<normalized>");
                    }
                }
            }
        }
        return out;
    }

    private void assertGoldenWithDynamicIdAndTimes(MvcResult result, JsonNode golden,
            String caseId, String actualId, String goldenId) throws Exception {
        JsonNode actual = MAPPER.readTree(result.getResponse()
                .getContentAsByteArray());
        JsonNode expected = observation(golden, caseId);
        JsonNode normalized = normalizeDynamic(substitute(actual, actualId, goldenId));
        JsonNode expectedNormalized = normalizeDynamic(expected);
        assertThat(normalized).isEqualTo(expectedNormalized);
    }
}
