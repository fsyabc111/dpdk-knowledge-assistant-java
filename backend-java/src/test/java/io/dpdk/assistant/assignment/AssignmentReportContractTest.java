package io.dpdk.assistant.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.dpdk.assistant.assignment.catalog.PythonScore;
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
 * J2-03 成绩契约测试（验收 D/E/F + golden grades observation）：
 * 同协议取最高、聊天路径 assignment_id=NULL 不计入、不同 assignment_id 不串分、
 * 按当前作业项动态折算（受理/汇总双口径差异样例）、树隔离（老师本班/leader 全部/
 * operator 不限）、账号删除后成绩保留且归未分配。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class AssignmentReportContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern PYTHON_STR_TIME =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d{6})?\\+00:00$");

    // golden 合成树
    private static final String G_LEADER = "U20270000001";
    private static final String G_TEACHER_A = "U20270000002";
    private static final String G_STUDENT_A = "U20270000011";

    // 行为测试树
    private static final String LEADER = "U20260000001";
    private static final String TEACHER_A = "U20260000002";
    private static final String TEACHER_B = "U20260000003";
    private static final String STUDENT_A1 = "U20260000011";
    private static final String STUDENT_A2 = "U20260000012";
    private static final String STUDENT_B1 = "U20260000021";
    private static final String STUDENT_FREE = "U20260000031";

    private static final Instant SUBMISSION_AT = Instant.parse("2026-10-24T04:00:00.000060Z");

    private static AssignmentTestHarness h;

    @BeforeAll
    static void setup() throws Exception {
        h = AssignmentTestHarness.accountMode("report");
        h.seedUser(G_LEADER, "[\"leader\"]", 10000, true, true, G_LEADER, null);
        h.seedUser(G_TEACHER_A, "[\"admin\"]", 10000, true, true, G_TEACHER_A, G_LEADER);
        h.seedUser(G_STUDENT_A, "[\"viewer\"]", 10000, true, true, G_STUDENT_A, G_TEACHER_A);
        h.seedUser(LEADER, "[\"leader\"]", null, true, true, LEADER, null);
        h.seedUser(TEACHER_A, "[\"admin\"]", null, true, true, TEACHER_A, LEADER);
        h.seedUser(TEACHER_B, "[\"admin\"]", null, true, true, TEACHER_B, LEADER);
        h.seedUser(STUDENT_A1, "[\"viewer\"]", null, true, true, STUDENT_A1, TEACHER_A);
        h.seedUser(STUDENT_A2, "[\"viewer\"]", null, true, true, STUDENT_A2, TEACHER_A);
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
    /* golden grades observation（teacher_a 视角逐字段）                    */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(1)
    void goldenGradesObservation() throws Exception {
        JsonNode golden = AssignmentTestHarness.loadGolden();
        MockMvc mvc = h.newMvc();
        String teacherToken = h.login(mvc, G_TEACHER_A, G_TEACHER_A);
        // 还原 golden 前置：create（合成DNS作业）→ patch（修订）→ 成绩行（tick 60）。
        String aid = MAPPER.readTree(mvc.perform(post("/api/assignments")
                        .contentType("application/json").header("Authorization", auth(teacherToken))
                        .content("{\"title\":\"合成DNS作业\",\"description\":\"P0\","
                                + "\"deadline\":\"2099-01-01T00:00:00Z\","
                                + "\"items\":[{\"protocol\":\"dns\",\"points\":20}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray())
                .path("id").asText();
        mvc.perform(patch("/api/assignments/" + aid).contentType("application/json")
                .header("Authorization", auth(teacherToken))
                .content("{\"title\":\"合成DNS作业修订\"}")).andReturn();
        h.seedSubmission("grade_golden_000001", G_STUDENT_A, "dns", 80, aid, SUBMISSION_AT, null);

        MvcResult result = mvc.perform(get("/api/assignments/" + aid + "/grades")
                        .header("Authorization", auth(teacherToken)))
                .andExpect(status().isOk()).andReturn();
        JsonNode actual = MAPPER.readTree(result.getResponse().getContentAsByteArray());
        JsonNode expected = observation(golden, "GET /api/assignments/{assignment_id}/grades::success");
        // 动态：assignment id 与 assignment.created_at/updated_at；行内时间逐字节
        // （合成 created_at=oracle tick 60 → last_submitted_at 与 golden 完全一致）。
        JsonNode normalized = substitute(normalizeDynamic(actual), aid, "<identity:19>");
        assertThat(normalized).isEqualTo(normalizeDynamic(expected));
    }

    /* ------------------------------------------------------------------ */
    /* 汇总语义（验收 D）                                                   */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(2)
    void bestScoreOfMultipleAttemptsAndCrossAssignmentIsolation() throws Exception {
        MockMvc mvc = h.newMvc();
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        String aid = create(mvc, teacherToken, "作业甲");
        String otherAid = create(mvc, teacherToken, "作业乙");

        // dns 三次提交 80/100/50 → 最高 100 → earned 20.0；http 一次 0 分 → earned 0.0（非 null）。
        seed("g_r2_1", STUDENT_A1, "dns", 80, aid, 0);
        seed("g_r2_2", STUDENT_A1, "dns", 100, aid, 1);
        seed("g_r2_3", STUDENT_A1, "dns", 50, aid, 2);
        seed("g_r2_4", STUDENT_A1, "http", 0, aid, 3);
        // 聊天路径（assignment_id=NULL）不计入任何作业。
        seed("g_r2_5", STUDENT_A1, "dns", 100, null, 4);
        // 另一作业的同协议高分不串分。
        seed("g_r2_6", STUDENT_A1, "dns", 90, otherAid, 5);

        JsonNode rows = gradesRows(mvc, teacherToken, aid);
        assertThat(rows.size()).isEqualTo(1);
        JsonNode row = rows.get(0);
        assertThat(row.path("user_id").asText()).isEqualTo(STUDENT_A1);
        assertThat(row.path("class_teacher").asText()).isEqualTo(TEACHER_A);
        assertThat(row.path("scores").path("dns").asDouble()).isEqualTo(20.0);
        assertThat(row.path("scores").path("http").asDouble()).isEqualTo(0.0);
        assertThat(row.path("total_earned").asDouble()).isEqualTo(20.0);
        // 提交次数计入该作业全部提交（dns 3 次 + http 1 次）。
        assertThat(row.path("submission_count").asInt()).isEqualTo(4);
        assertThat(row.path("last_submitted_at").asText())
                .isEqualTo("2026-10-24 04:00:00.000063+00:00");

        // 学生视角逐协议统计（最高/次数/未提交 null）。
        String studentToken = h.login(mvc, STUDENT_A1, STUDENT_A1);
        JsonNode studentRow = findAssignmentRow(mvc, studentToken, aid);
        JsonNode dns = itemOf(studentRow, "dns");
        assertThat(dns.path("best_score").asInt()).isEqualTo(100);
        assertThat(dns.path("earned").asDouble()).isEqualTo(20.0);
        assertThat(dns.path("submission_count").asInt()).isEqualTo(3);
        JsonNode http = itemOf(studentRow, "http");
        assertThat(http.path("best_score").asInt()).isEqualTo(0);
        assertThat(http.path("earned").asDouble()).isEqualTo(0.0);
        assertThat(http.path("submission_count").asInt()).isEqualTo(1);
        assertThat(studentRow.path("total_points").asInt()).isEqualTo(50);
        assertThat(studentRow.path("total_earned").asDouble()).isEqualTo(20.0);
        assertThat(studentRow.path("submission_count").asInt()).isEqualTo(4);

        // 未提交协议：新作业学生视图 earned=null 与 0 分区分（W4）。
        String freshAid = create(mvc, teacherToken, "作业丙");
        JsonNode freshRow = findAssignmentRow(mvc, studentToken, freshAid);
        JsonNode freshDns = itemOf(freshRow, "dns");
        assertThat(freshDns.path("best_score").isNull()).isTrue();
        assertThat(freshDns.path("earned").isNull()).isTrue();
        assertThat(freshDns.path("submission_count").asInt()).isEqualTo(0);
    }

    /* ------------------------------------------------------------------ */
    /* 动态折算与受理口径（验收 E）                                          */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(3)
    void dynamicRecalculationVersusAcceptedSnapshot() throws Exception {
        MockMvc mvc = h.newMvc();
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        String aid = create(mvc, teacherToken, "动态折算作业");

        // 受理口径：J2-03 端口在受理时读到 20 分并（由 J2-04）落 accepted_assignment；
        // 此处以合成行内嵌受理快照表达。
        String accepted = "{\"points\":20,\"scope\":\"class\",\"created_by\":\"" + TEACHER_A
                + "\",\"deadline\":\"2099-01-01 00:00:00+00:00\","
                + "\"assignment_updated_at\":\"2026-10-24 04:00:00.000010+00:00\","
                + "\"accepted_at\":\"2026-10-24 04:00:00.000059+00:00\"}";
        h.seedSubmission("grade_r3_1", STUDENT_A1, "dns", 80, aid, SUBMISSION_AT, accepted);
        // 作业受理后修改：dns 20 → 40。
        mvc.perform(patch("/api/assignments/" + aid).contentType("application/json")
                .header("Authorization", auth(teacherToken))
                .content("{\"items\":[{\"protocol\":\"dns\",\"points\":40},"
                        + "{\"protocol\":\"http\",\"points\":30}]}")).andReturn();

        // 汇总口径：按当前 40 分动态折算 → 80×40% = 32.0（与受理 16.0 并存）。
        JsonNode row = gradesRows(mvc, teacherToken, aid).get(0);
        assertThat(row.path("scores").path("dns").asDouble()).isEqualTo(32.0);
        // 受理快照在成绩行内保持 20（受理分值口径，供讲解/审计）。
        assertThat(h.columnValue("grading_submissions",
                "(results_json->'accepted_assignment'->>'points')::text", "id = ?",
                "grade_r3_1")).isEqualTo("20");

        // 受理端口读到的“当前”分值已是 40（下一次受理按新分值）。
        assertThat(h.catalog.submissionSnapshot(aid, TEACHER_A, "dns").orElseThrow().points())
                .isEqualTo(40);
        // Python 计分语义抽查：HALF_EVEN（82.5% 类边界）。
        assertThat(PythonScore.calcEarned(75, 30)).isEqualTo(22.5);
        assertThat(PythonScore.round1(2.25)).isEqualTo(2.2);
        assertThat(PythonScore.round1(2.35)).isEqualTo(2.4);
    }

    /* ------------------------------------------------------------------ */
    /* 树隔离与账号删除（验收 F）                                            */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(4)
    void treeIsolationAndDeletedAccountKeepsGrades() throws Exception {
        MockMvc mvc = h.newMvc();
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        String teacherBToken = h.login(mvc, TEACHER_B, TEACHER_B);
        String leaderToken = h.login(mvc, LEADER, LEADER);
        String studentA2 = h.login(mvc, STUDENT_A2, STUDENT_A2);

        // 组长作业：A1/A2/B1/FREE 各有提交。
        String aid = MAPPER.readTree(mvc.perform(post("/api/assignments")
                        .contentType("application/json").header("Authorization", auth(leaderToken))
                        .content("{\"title\":\"组长作业\",\"description\":\"\","
                                + "\"deadline\":\"2099-06-01T00:00:00Z\","
                                + "\"items\":[{\"protocol\":\"dns\",\"points\":20}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray())
                .path("id").asText();
        seed("g_r4_1", STUDENT_A1, "dns", 80, aid, 0);
        seed("g_r4_2", STUDENT_A2, "dns", 100, aid, 1);
        seed("g_r4_3", STUDENT_B1, "dns", 60, aid, 2);
        seed("g_r4_4", STUDENT_FREE, "dns", 40, aid, 3);

        // 老师 A 只看自己班学生（A1/A2），行按 user_id 升序。
        JsonNode teacherRows = gradesRows(mvc, teacherToken, aid);
        assertThat(teacherRows.findValuesAsText("user_id"))
                .containsExactly(STUDENT_A1, STUDENT_A2);
        assertThat(teacherRows.get(0).path("class_teacher").asText()).isEqualTo(TEACHER_A);

        // 老师 B 只看 B1（老师视角对他人作业可见：grades 门禁按角色，不按创建者）。
        JsonNode teacherBRows = gradesRows(mvc, teacherBToken, aid);
        assertThat(teacherBRows.findValuesAsText("user_id")).containsExactly(STUDENT_B1);

        // leader/operator 看全部；未分配学生 class_teacher=null。
        JsonNode leaderRows = gradesRows(mvc, leaderToken, aid);
        assertThat(leaderRows.findValuesAsText("user_id"))
                .containsExactly(STUDENT_A1, STUDENT_A2, STUDENT_B1, STUDENT_FREE);
        String operator = "Bearer " + AssignmentTestHarness.TOKEN_OPERATOR;
        JsonNode operatorRows = gradesRows(mvc, AssignmentTestHarness.TOKEN_OPERATOR, aid);
        assertThat(operatorRows.size()).isEqualTo(4);
        for (JsonNode row : leaderRows) {
            if (STUDENT_FREE.equals(row.path("user_id").asText())) {
                assertThat(row.path("class_teacher").isNull()).isTrue();
            } else {
                assertThat(row.path("class_teacher").isTextual()).isTrue();
            }
        }

        // 账号删除后：成绩行保留、学号保留、班级归未分配（JSON null）。
        h.deleteUser(STUDENT_A1);
        JsonNode afterDelete = gradesRows(mvc, leaderToken, aid);
        JsonNode deletedRow = null;
        for (JsonNode row : afterDelete) {
            if (STUDENT_A1.equals(row.path("user_id").asText())) {
                deletedRow = row;
            }
        }
        assertThat(deletedRow).isNotNull();
        assertThat(deletedRow.path("class_teacher").isNull()).isTrue();
        assertThat(deletedRow.path("scores").path("dns").asDouble()).isEqualTo(16.0);
        // 恢复账号行，供后续用例复用同一棵树。
        h.seedUser(STUDENT_A1, "[\"viewer\"]", null, true, true, STUDENT_A1, TEACHER_A);

        // viewer 学生不能进成绩端点（404 门禁）。
        assertThat(mvc.perform(get("/api/assignments/" + aid + "/grades")
                        .header("Authorization", auth(studentA2)))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        // 401 与 404（golden unauthorized/invalid-resource 形态）。
        MvcResult unauth = mvc.perform(get("/api/assignments/" + aid + "/grades"))
                .andExpect(status().isUnauthorized()).andReturn();
        assertThat(MAPPER.readTree(unauth.getResponse().getContentAsByteArray()).toString())
                .isEqualTo("{\"detail\":\"missing or malformed bearer token\"}");
        MvcResult missing = mvc.perform(get("/api/assignments/p0-missing/grades")
                        .header("Authorization", auth(leaderToken)))
                .andExpect(status().isNotFound()).andReturn();
        assertThat(MAPPER.readTree(missing.getResponse().getContentAsByteArray()).toString())
                .isEqualTo("{\"detail\":\"not found\"}");
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                              */
    /* ------------------------------------------------------------------ */

    private static String auth(String token) {
        return "Bearer " + token;
    }

    private void seed(String id, String user, String protocol, int score, String assignmentId,
            int tickOffset) throws Exception {
        h.seedSubmission(id, user, protocol, score, assignmentId,
                SUBMISSION_AT.plusNanos(tickOffset * 1_000L), null);
    }

    private String create(MockMvc mvc, String token, String title) throws Exception {
        return MAPPER.readTree(mvc.perform(post("/api/assignments")
                        .contentType("application/json").header("Authorization", auth(token))
                        .content("{\"title\":\"" + title + "\",\"description\":\"\","
                                + "\"deadline\":\"2099-01-01T00:00:00Z\","
                                + "\"items\":[{\"protocol\":\"dns\",\"points\":20},"
                                + "{\"protocol\":\"http\",\"points\":30}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray())
                .path("id").asText();
    }

    private JsonNode gradesRows(MockMvc mvc, String token, String aid) throws Exception {
        JsonNode body = MAPPER.readTree(mvc.perform(get("/api/assignments/" + aid + "/grades")
                        .header("Authorization", auth(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray());
        return body.path("rows");
    }

    private JsonNode findAssignmentRow(MockMvc mvc, String token, String aid) throws Exception {
        JsonNode body = MAPPER.readTree(mvc.perform(get("/api/assignments")
                        .header("Authorization", auth(token))).andReturn()
                .getResponse().getContentAsByteArray());
        for (JsonNode row : body.path("assignments")) {
            if (aid.equals(row.path("id").asText())) {
                return row;
            }
        }
        throw new IllegalStateException("学生列表缺少作业 " + aid);
    }

    private static JsonNode itemOf(JsonNode assignmentRow, String protocol) {
        for (JsonNode item : assignmentRow.path("items")) {
            if (protocol.equals(item.path("protocol").asText())) {
                return item;
            }
        }
        throw new IllegalStateException("缺协议项 " + protocol);
    }

    private static JsonNode observation(JsonNode golden, String caseId) {
        for (JsonNode o : golden.path("observations")) {
            if (caseId.equals(o.path("case_id").asText())) {
                return o.path("body");
            }
        }
        throw new IllegalStateException("golden 缺少 observation: " + caseId);
    }

    private static JsonNode substitute(JsonNode node, String from, String to) {
        return MAPPER.convertValue(replaceNode(node, from, to), JsonNode.class);
    }

    private static Object replaceNode(JsonNode node, String from, String to) {
        if (node.isTextual() && node.asText().equals(from)) {
            return to;
        }
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
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

    /** assignment.created_at/updated_at 归一化（动态微秒）。 */
    private static JsonNode normalizeDynamic(JsonNode node) {
        JsonNode out = node.deepCopy();
        if (out.isObject()) {
            JsonNode assignment = out.path("assignment");
            if (assignment.isObject()) {
                for (String field : new String[] {"created_at", "updated_at"}) {
                    if (assignment.has(field)) {
                        assertThat(assignment.get(field).asText()).matches(PYTHON_STR_TIME);
                        ((ObjectNode) assignment).put(field, "<normalized>");
                    }
                }
            }
        }
        return out;
    }
}
