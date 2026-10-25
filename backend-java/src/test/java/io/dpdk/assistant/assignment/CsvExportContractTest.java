package io.dpdk.assistant.assignment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
 * J2-03 CSV 导出契约测试（验收 F/G + golden export 原始字节摘要）：
 * BOM/UTF-8、csv.writer CRLF 与引号规则、表头/排序/组间空行/未分配最后/未提交空串、
 * text/csv; charset=utf-8-sig 与安全文件名 Content-Disposition；teacher 单班视角。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class CsvExportContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // golden 合成树
    private static final String G_TEACHER_A = "U20270000002";
    private static final String G_STUDENT_A = "U20270000011";

    // 行为测试树
    private static final String LEADER = "U20260000001";
    private static final String TEACHER_A = "U20260000002";
    private static final String TEACHER_B = "U20260000003";
    private static final String STUDENT_A1 = "U20260000011";
    private static final String STUDENT_B1 = "U20260000021";
    private static final String STUDENT_FREE = "U20260000031";

    private static final Instant SUBMISSION_AT = Instant.parse("2026-10-24T04:00:00.000060Z");

    /** golden export observation 的原始字节摘要（J0-02 冻结）。 */
    private static final String GOLDEN_CSV_SHA256 =
            "f280bec9cd2ffed50320aac7ea4f89ca5561351eef11268c04acdcc4277da763";
    private static final int GOLDEN_CSV_LENGTH = 163;

    private static AssignmentTestHarness h;

    @BeforeAll
    static void setup() throws Exception {
        h = AssignmentTestHarness.accountMode("csv");
        h.seedUser(LEADER, "[\"leader\"]", null, true, true, LEADER, null);
        h.seedUser(TEACHER_A, "[\"admin\"]", null, true, true, TEACHER_A, LEADER);
        h.seedUser(TEACHER_B, "[\"admin\"]", null, true, true, TEACHER_B, LEADER);
        h.seedUser(STUDENT_A1, "[\"viewer\"]", null, true, true, STUDENT_A1, TEACHER_A);
        h.seedUser(STUDENT_B1, "[\"viewer\"]", null, true, true, STUDENT_B1, TEACHER_B);
        h.seedUser(STUDENT_FREE, "[\"viewer\"]", null, true, true, STUDENT_FREE, null);
        // golden 还原树（teacher_a 班：student_a）
        h.seedUser("U20270000001", "[\"leader\"]", 10000, true, true, "U20270000001", null);
        h.seedUser(G_TEACHER_A, "[\"admin\"]", 10000, true, true, G_TEACHER_A, "U20270000001");
        h.seedUser(G_STUDENT_A, "[\"viewer\"]", 10000, true, true, G_STUDENT_A, G_TEACHER_A);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    /* ------------------------------------------------------------------ */
    /* golden 原始字节摘要（teacher_a 视角单班）                             */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(1)
    void goldenExportByteDigest() throws Exception {
        MockMvc mvc = h.newMvc();
        String teacherToken = h.login(mvc, G_TEACHER_A, G_TEACHER_A);
        // 还原 golden 前置：dns/20 作业 + dns/80 成绩行（created_at=oracle tick 60）。
        String aid = MAPPER.readTree(mvc.perform(post("/api/assignments")
                        .contentType("application/json").header("Authorization", auth(teacherToken))
                        .content("{\"title\":\"合成DNS作业\",\"description\":\"P0\","
                                + "\"deadline\":\"2099-01-01T00:00:00Z\","
                                + "\"items\":[{\"protocol\":\"dns\",\"points\":20}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray())
                .path("id").asText();
        h.seedSubmission("grade_csv_golden_01", G_STUDENT_A, "dns", 80, aid, SUBMISSION_AT, null);

        MvcResult result = mvc.perform(get("/api/assignments/" + aid + "/export")
                        .header("Authorization", auth(teacherToken)))
                .andExpect(status().isOk()).andReturn();
        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body).hasSize(GOLDEN_CSV_LENGTH);
        assertThat(sha256Hex(body)).isEqualTo(GOLDEN_CSV_SHA256);
        assertThat(result.getResponse().getHeader("Content-Type"))
                .isEqualTo("text/csv; charset=utf-8-sig");
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"assignment_" + aid + "_" + G_TEACHER_A
                        + ".csv\"");
    }

    /* ------------------------------------------------------------------ */
    /* 分组/空行/未分配（leader 与 operator/静态主体视角）                    */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(2)
    void leaderGroupedByClassWithBlankLinesAndUnassignedLast() throws Exception {
        MockMvc mvc = h.newMvc();
        String leaderToken = h.login(mvc, LEADER, LEADER);
        String aid = createAssignment(mvc, leaderToken);
        seed("g_c2_a1", STUDENT_A1, "dns", 80, aid, 0);
        seed("g_c2_b1", STUDENT_B1, "dns", 80, aid, 1);
        seed("g_c2_free", STUDENT_FREE, "dns", 80, aid, 2);

        MvcResult result = mvc.perform(get("/api/assignments/" + aid + "/export")
                        .header("Authorization", auth(leaderToken)))
                .andExpect(status().isOk()).andReturn();
        List<List<String>> rows = parseCsv(result.getResponse().getContentAsByteArray());
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"assignment_" + aid + "_grades.csv\"");

        // 表头（协议项按当前 items 顺序；未提交协议列存在）。
        assertThat(rows.get(0)).containsExactly("班级(老师学号)", "学生学号",
                "dns(满分20)", "http(满分30)", "总分", "提交次数", "最近提交时间");

        // 数据区：三组（A/B/未分配），组间恰好一个空行，未分配最后。
        List<List<String>> data = rows.subList(1, rows.size());
        List<Integer> blanks = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            if (data.get(i).isEmpty()) {
                blanks.add(i);
            }
        }
        assertThat(blanks).hasSize(2);
        List<List<String>> nonBlank = data.stream().filter(r -> !r.isEmpty()).toList();
        assertThat(nonBlank.stream().map(r -> r.get(0)).toList())
                .containsExactly(TEACHER_A, TEACHER_B, "未分配");
        assertThat(nonBlank.stream().map(r -> r.get(1)).toList())
                .containsExactly(STUDENT_A1, STUDENT_B1, STUDENT_FREE);
        // dns 折算 16.0；http 未提交空串；总分/次数/时间。
        List<String> first = nonBlank.get(0);
        assertThat(first.get(2)).isEqualTo("16.0");
        assertThat(first.get(3)).isEqualTo("");
        assertThat(first.get(4)).isEqualTo("16.0");
        assertThat(first.get(5)).isEqualTo("1");
        assertThat(first.get(6)).isEqualTo("2026-10-24 04:00:00.000060+00:00");

        // operator / 静态 admin token 同为分组视角（树隔离豁免）。
        String operator = "Bearer " + AssignmentTestHarness.TOKEN_OPERATOR;
        MvcResult operatorResult = mvc.perform(get("/api/assignments/" + aid + "/export")
                        .header("Authorization", operator)).andExpect(status().isOk()).andReturn();
        assertThat(parseCsv(operatorResult.getResponse().getContentAsByteArray()).size())
                .isEqualTo(rows.size());
        String staticTeacher = "Bearer " + AssignmentTestHarness.TOKEN_TEACHER_STATIC;
        MvcResult staticResult = mvc.perform(get("/api/assignments/" + aid + "/export")
                        .header("Authorization", staticTeacher)).andExpect(status().isOk())
                .andReturn();
        List<List<String>> staticRows = parseCsv(staticResult.getResponse().getContentAsByteArray());
        assertThat(staticRows.size()).isEqualTo(6); // 表头 + 3 数据行 + 2 组间空行
    }

    @Test
    @Order(3)
    void adminSeesOnlyOwnClassSingleGroup() throws Exception {
        MockMvc mvc = h.newMvc();
        String leaderToken = h.login(mvc, LEADER, LEADER);
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        String aid = createAssignment(mvc, leaderToken);
        seed("g_c3_a1", STUDENT_A1, "dns", 80, aid, 0);
        seed("g_c3_b1", STUDENT_B1, "dns", 80, aid, 1);

        MvcResult result = mvc.perform(get("/api/assignments/" + aid + "/export")
                        .header("Authorization", auth(teacherToken)))
                .andExpect(status().isOk()).andReturn();
        List<List<String>> rows = parseCsv(result.getResponse().getContentAsByteArray());
        assertThat(rows).hasSize(2); // 表头 + 本班 1 行（无空行分组）
        assertThat(rows.get(1).get(0)).isEqualTo(TEACHER_A);
        assertThat(rows.get(1).get(1)).isEqualTo(STUDENT_A1);
        assertThat(result.getResponse().getHeader("Content-Disposition"))
                .contains("assignment_" + aid + "_" + TEACHER_A + ".csv");
    }

    @Test
    @Order(4)
    void deletedAccountKeptAsUnassignedGroup() throws Exception {
        MockMvc mvc = h.newMvc();
        String leaderToken = h.login(mvc, LEADER, LEADER);
        String aid = createAssignment(mvc, leaderToken);
        seed("g_c4_a1", STUDENT_A1, "dns", 80, aid, 0);
        seed("g_c4_free", STUDENT_FREE, "dns", 40, aid, 1);
        h.deleteUser(STUDENT_A1);

        MvcResult result = mvc.perform(get("/api/assignments/" + aid + "/export")
                        .header("Authorization", auth(leaderToken)))
                .andExpect(status().isOk()).andReturn();
        List<List<String>> rows = parseCsv(result.getResponse().getContentAsByteArray());
        List<List<String>> nonBlank = rows.stream().skip(1)
                .filter(r -> !r.isEmpty()).toList();
        // A1 删除后与 FREE 同归「未分配」组（一组两行，学号保留、成绩保留）。
        assertThat(nonBlank.stream().map(r -> r.get(0)).toList())
                .containsExactly("未分配", "未分配");
        assertThat(nonBlank.stream().map(r -> r.get(1)).toList())
                .containsExactly(STUDENT_A1, STUDENT_FREE);
        assertThat(nonBlank.get(0).get(4)).isEqualTo("16.0");
        assertThat(nonBlank.get(1).get(4)).isEqualTo("8.0");
        // 恢复账号行，供后续用例复用同一棵树。
        h.seedUser(STUDENT_A1, "[\"viewer\"]", null, true, true, STUDENT_A1, TEACHER_A);
    }

    /* ------------------------------------------------------------------ */
    /* 字节级规则（BOM/CRLF/空串/零分/汇总空 scores）                        */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(5)
    void bomCrLfAndValueRendering() throws Exception {
        MockMvc mvc = h.newMvc();
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        String aid = createAssignment(mvc, teacherToken);
        // dns 提交但 0 分 → 0.0（与未提交空串区分）；http 无提交 → ""。
        seed("g_c5_zero", STUDENT_A1, "dns", 0, aid, 0);
        // 提交到已不在当前项内的协议：计入次数/时间，不产生 scores 单元格。
        seed("g_c5_old", STUDENT_A1, "ssh", 90, aid, 1);

        MvcResult result = mvc.perform(get("/api/assignments/" + aid + "/export")
                        .header("Authorization", auth(teacherToken)))
                .andExpect(status().isOk()).andReturn();
        byte[] body = result.getResponse().getContentAsByteArray();
        // UTF-8 BOM。
        assertThat(body[0]).isEqualTo((byte) 0xEF);
        assertThat(body[1]).isEqualTo((byte) 0xBB);
        assertThat(body[2]).isEqualTo((byte) 0xBF);
        String text = new String(Arrays.copyOfRange(body, 3, body.length), StandardCharsets.UTF_8);
        // Python csv.writer：CRLF 行结束。
        assertThat(text).startsWith("班级(老师学号),学生学号,dns(满分20),http(满分30),总分,提交次数,最近提交时间\r\n");
        List<String> lines = text.split("\r\n", -1).length > 0
                ? Arrays.stream(text.split("\r\n", -1)).filter(l -> !l.isEmpty()).toList()
                : List.of();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(1)).isEqualTo(TEACHER_A + "," + STUDENT_A1
                + ",0.0,,0.0,2,2026-10-24 04:00:00.000061+00:00");
        // 中文表头不带引号（QUOTE_MINIMAL：无逗号/引号/换行不转义）。
        assertThat(text).doesNotContain("\"");

        // 作业不存在 → 404；viewer → 404；未认证 → 401。
        assertThat(mvc.perform(get("/api/assignments/p0-missing/export")
                        .header("Authorization", auth(teacherToken)))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/assignments/" + aid + "/export")
                        .header("Authorization", "Bearer " + AssignmentTestHarness.TOKEN_VIEWER))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/assignments/" + aid + "/export"))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
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

    private String createAssignment(MockMvc mvc, String token) throws Exception {
        return MAPPER.readTree(mvc.perform(post("/api/assignments")
                        .contentType("application/json").header("Authorization", auth(token))
                        .content("{\"title\":\"组长作业\",\"description\":\"\","
                                + "\"deadline\":\"2099-06-01T00:00:00Z\","
                                + "\"items\":[{\"protocol\":\"dns\",\"points\":20},"
                                + "{\"protocol\":\"http\",\"points\":30}]}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsByteArray())
                .path("id").asText();
    }

    /** 解析（BOM 剥离、CRLF 分行、简单逗号分列——本域无引号字段）。 */
    private static List<List<String>> parseCsv(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        if (text.startsWith("\ufeff")) {
            text = text.substring(1);
        }
        List<List<String>> rows = new ArrayList<>();
        for (String line : text.split("\r\n", -1)) {
            if (line.isEmpty()) {
                rows.add(List.of());
            } else {
                rows.add(Arrays.asList(line.split(",", -1)));
            }
        }
        if (!rows.isEmpty() && rows.get(rows.size() - 1).isEmpty()
                && text.endsWith("\r\n")) {
            rows.remove(rows.size() - 1); // 末行终结符不产生额外空行
        }
        return rows;
    }

    private static String sha256Hex(byte[] body) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
