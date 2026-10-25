package io.dpdk.assistant.assignment.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.auth.AuthServerSettings;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.web.MockPart;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * J2-04 提交契约测试（验收 A/D/F/G + golden 流程）：
 * JSON/multipart 受理与错误码顺序、可见性/截止/协议资格、受理快照与响应口径、
 * 评测中途修改按受理分值、畸形 payload 的 Python 数值化语义、UTF-8 替换解码
 * hash 与 Python 逐字节一致（Python 冻结值来自本机 CPython 计算，证据留档）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class AssignmentSubmitContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Pattern GRADE_ID = Pattern.compile("^grade_[a-f0-9]{16}$");
    private static final Pattern PYTHON_ISO =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{6})?\\+00:00$");
    private static final Pattern PYTHON_STR_TIME =
            Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}(\\.\\d{6})?\\+00:00$");

    // golden 合成树（U2027 命名空间，与 oracle IDS 一致）。
    private static final String G_TEACHER_A = "U20270000002";
    private static final String G_STUDENT_A = "U20270000011";

    // 行为测试树（U2026 命名空间）。
    private static final String LEADER = "U20260000001";
    private static final String TEACHER_A = "U20260000002";
    private static final String TEACHER_B = "U20260000003";
    private static final String STUDENT_A1 = "U20260000011";
    private static final String STUDENT_B1 = "U20260000021";
    private static final String STUDENT_FREE = "U20260000031";

    private static final String GOLDEN_CODE_SHA =
            "1d6051bdbbdc6dd9cffb8848b37f5c81265eccdad2b9e3cdbe25e09a67464c22";

    private static SubmissionTestHarness h;
    private static MockMvc mvc;
    private static String studentA1;
    private static String teacherA;
    private static String teacherB;
    private static String leader;

    @BeforeAll
    static void setup() throws Exception {
        h = SubmissionTestHarness.accountMode("submit");
        // 行为树
        h.seedUser(LEADER, "[\"leader\"]", null, true, LEADER, null);
        h.seedUser(TEACHER_A, "[\"admin\"]", null, true, TEACHER_A, LEADER);
        h.seedUser(TEACHER_B, "[\"admin\"]", null, true, TEACHER_B, LEADER);
        h.seedUser(STUDENT_A1, "[\"viewer\"]", null, true, STUDENT_A1, TEACHER_A);
        h.seedUser(STUDENT_B1, "[\"viewer\"]", null, true, STUDENT_B1, TEACHER_B);
        h.seedUser(STUDENT_FREE, "[\"viewer\"]", null, true, STUDENT_FREE, null);
        mvc = h.newMvc();
        studentA1 = h.login(mvc, STUDENT_A1, STUDENT_A1);
        teacherA = h.login(mvc, TEACHER_A, TEACHER_A);
        teacherB = h.login(mvc, TEACHER_B, TEACHER_B);
        leader = h.login(mvc, LEADER, LEADER);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private String createAssignment(String token, String title, String deadline,
            String itemsJson) throws Exception {
        MvcResult result = mvc.perform(post("/api/assignments")
                        .contentType("application/json").header("Authorization", bearer(token))
                        .content("{\"title\":\"" + title + "\",\"description\":\"P0\","
                                + "\"deadline\":\"" + deadline + "\",\"items\":" + itemsJson + "}"))
                .andExpect(status().isCreated()).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsByteArray())
                .path("id").asText();
    }

    private String createDefaultAssignment(String token, String title) throws Exception {
        return createAssignment(token, title, "2099-01-01T00:00:00Z",
                "[{\"protocol\":\"dns\",\"points\":20},{\"protocol\":\"http\",\"points\":30}]");
    }

    private JsonNode submitJson(String aid, String body, String token) throws Exception {
        MvcResult result = mvc.perform(post("/api/assignments/" + aid + "/submit")
                        .contentType("application/json")
                        .header("Authorization", token == null ? "" : bearer(token))
                        .content(body))
                .andReturn();
        String content = result.getResponse().getContentAsString();
        return MAPPER.readTree(content.isEmpty() ? "{}" : content);
    }

    private MvcResult submitRaw(String aid, String body, String token) throws Exception {
        return mvc.perform(post("/api/assignments/" + aid + "/submit")
                        .contentType("application/json")
                        .header("Authorization", bearer(token))
                        .content(body))
                .andReturn();
    }

    /* ------------------------------------------------------------------ */
    /* golden 流程（observation 53/148/149 逐字段）                         */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(1)
    void goldenFlowSubmitSuccess() throws Exception {
        // golden 合成树（与 J2-03 同构；仅本测试需要的学生/老师）。
        h.seedUser("U20270000001", "[\"leader\"]", 10000, true, "U20270000001", null);
        h.seedUser(G_TEACHER_A, "[\"admin\"]", 10000, true, G_TEACHER_A, "U20270000001");
        h.seedUser(G_STUDENT_A, "[\"viewer\"]", 10000, true, G_STUDENT_A, G_TEACHER_A);
        h.harness.setSuccessPayload("dns", 80, 4, 5, "["
                + "{\"name\":\"synthetic-case\",\"result\":\"FAIL\",\"category\":\"basic\","
                + "\"failures\":[{\"desc\":\"synthetic field\",\"expected\":\"x\",\"actual\":\"y\"}]}]");
        String token = h.login(mvc, G_STUDENT_A, G_STUDENT_A);
        String goldenTeacher = h.login(mvc, G_TEACHER_A, G_TEACHER_A);
        String aid = createAssignment(goldenTeacher, "合成DNS作业", "2099-01-01T00:00:00Z",
                "[{\"protocol\":\"dns\",\"points\":20}]");

        int gradeRowsBefore = h.countRows("grading_submissions", null);
        int callsBefore = h.harness.gradeCallCount();
        JsonNode body = submitJson(aid, "{\"protocol\":\"dns\",\"code\":"
                + json(goldenSource()) + "}", token);
        JsonNode golden = observation(
                "POST /api/assignments/{assignment_id}/submit::success");

        assertThat(body.path("score").asInt()).isEqualTo(80);
        assertThat(body.path("passed").asInt()).isEqualTo(4);
        assertThat(body.path("total").asInt()).isEqualTo(5);
        assertThat(body.path("points").asInt()).isEqualTo(20);
        assertThat(body.path("earned").asDouble()).isEqualTo(16.0);
        assertThat(body.path("best_earned").asDouble()).isEqualTo(16.0);
        assertThat(body.path("submission_id").asText()).matches(GRADE_ID);
        assertThat(body.path("results")).isEqualTo(golden.path("body").path("results"));

        // 业务行：成绩 1 行、配额 kind=grading 1 行；MCP 恰 1 次。
        assertThat(h.countRows("grading_submissions", null)).isEqualTo(gradeRowsBefore + 1);
        assertThat(h.counterValue(G_STUDENT_A, "grading")).isEqualTo(1);
        assertThat(h.harness.gradeCallCount() - callsBefore).isEqualTo(1);

        // 落库行逐字段（identity/时间按 golden-diff 映射）。
        String submissionId = body.path("submission_id").asText();
        assertThat(h.columnValue("grading_submissions", "user_id", "id=?", submissionId))
                .isEqualTo(G_STUDENT_A);
        assertThat(h.columnValue("grading_submissions", "protocol", "id=?", submissionId))
                .isEqualTo("dns");
        assertThat(h.columnValue("grading_submissions", "code_hash", "id=?", submissionId))
                .isEqualTo(GOLDEN_CODE_SHA);
        assertThat(h.columnValue("grading_submissions", "assignment_id", "id=?", submissionId))
                .isEqualTo(aid);
        assertThat(h.columnValue("grading_submissions", "session_id", "id=?", submissionId))
                .isNull();
        assertThat(h.columnValue("grading_submissions", "task_job_id", "id=?", submissionId))
                .isNull();
        JsonNode details = MAPPER.readTree(h.columnValue("grading_submissions",
                "results_json::text", "id=?", submissionId));
        assertThat(details.path("source_origin").asText()).isEqualTo("pasted");
        assertThat(details.path("results")).isEqualTo(golden.path("body").path("results"));
        JsonNode accepted = details.path("accepted_assignment");
        assertThat(accepted.path("points").asInt()).isEqualTo(20);
        assertThat(accepted.path("scope").asText()).isEqualTo("class");
        assertThat(accepted.path("created_by").asText()).isEqualTo(G_TEACHER_A);
        assertThat(accepted.path("deadline").asText())
                .matches(PYTHON_STR_TIME).isEqualTo("2099-01-01 00:00:00+00:00");
        assertThat(accepted.path("assignment_updated_at").asText()).matches(PYTHON_STR_TIME);
        assertThat(accepted.path("accepted_at").asText()).matches(PYTHON_ISO);

        // 401（无 token）：golden unauthorized 逐字节。
        MvcResult unauthorized = mvc.perform(post("/api/assignments/" + aid + "/submit")
                        .contentType("application/json").content("{}")).andReturn();
        assertThat(unauthorized.getResponse().getStatus()).isEqualTo(401);
        assertThat(MAPPER.readTree(unauthorized.getResponse().getContentAsByteArray()))
                .isEqualTo(observation(
                        "POST /api/assignments/{assignment_id}/submit::unauthorized")
                        .path("body"));

        // 422（空对象）：golden invalid-input 逐字段（loc 无 body 前缀）。
        JsonNode invalid = submitJson(aid, "{}", token);
        assertThat(invalid).isEqualTo(observation(
                "POST /api/assignments/{assignment_id}/submit::invalid-input").path("body"));
    }

    private static String goldenSource() {
        return SubmissionTestHarness.goldenRuntimeSource();
    }

    private static String json(String value) {
        return MAPPER.valueToTree(value).toString();
    }

    private static JsonNode observation(String caseId) throws Exception {
        JsonNode golden = SubmissionTestHarness.loadGolden();
        for (JsonNode node : golden.path("observations")) {
            if (caseId.equals(node.path("case_id").asText())) {
                return node;
            }
        }
        throw new IllegalStateException("missing observation " + caseId);
    }

    /* ------------------------------------------------------------------ */
    /* 计分与多次提交（验收 A）                                             */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(2)
    void scoringAndBestOfMultipleAttempts() throws Exception {
        String aid = createDefaultAssignment(teacherA, "计分作业");
        h.harness.setSuccessPayload("dns", 80, 4, 5, "[{\"name\":\"c1\",\"passed\":true}]");

        JsonNode first = submitJson(aid, "{\"protocol\":\"dns\",\"code\":\"int x;\"}", studentA1);
        assertThat(first.path("earned").asDouble()).isEqualTo(16.0);
        assertThat(first.path("best_earned").asDouble()).isEqualTo(16.0);

        h.harness.setSuccessPayload("dns", 100, 5, 5, "[]");
        JsonNode second = submitJson(aid, "{\"protocol\":\"dns\",\"code\":\"int x;\"}", studentA1);
        assertThat(second.path("earned").asDouble()).isEqualTo(20.0);
        assertThat(second.path("best_earned").asDouble()).isEqualTo(20.0);

        h.harness.setSuccessPayload("dns", 50, 2, 5, "[]");
        JsonNode third = submitJson(aid, "{\"protocol\":\"dns\",\"code\":\"int x;\"}", studentA1);
        assertThat(third.path("earned").asDouble()).isEqualTo(10.0);
        assertThat(third.path("best_earned").asDouble()).isEqualTo(20.0);

        // 学生视角列表（当前作业定义）含 best/submission_count（口径不混）。
        JsonNode list = MAPPER.readTree(mvc.perform(get("/api/assignments")
                        .header("Authorization", bearer(studentA1)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode dns = null;
        for (JsonNode assignment : list.path("assignments")) {
            if (aid.equals(assignment.path("id").asText())) {
                for (JsonNode item : assignment.path("items")) {
                    if ("dns".equals(item.path("protocol").asText())) {
                        dns = item;
                    }
                }
            }
        }
        assertThat(dns).isNotNull();
        assertThat(dns.path("best_score").asInt()).isEqualTo(100);
        assertThat(dns.path("submission_count").asInt()).isEqualTo(3);
    }

    @Test
    @Order(3)
    void rejectsPastDeadlineAndUnknownProtocol() throws Exception {
        h.harness.resetCounters();
        String expired = createAssignment(teacherA, "过期作业", "2020-01-01T00:00:00Z",
                "[{\"protocol\":\"dns\",\"points\":20}]");
        MvcResult pastDeadline = submitRaw(expired, "{\"protocol\":\"dns\",\"code\":\"x\"}",
                studentA1);
        assertThat(pastDeadline.getResponse().getStatus()).isEqualTo(400);
        assertThat(MAPPER.readTree(pastDeadline.getResponse().getContentAsByteArray())
                .path("detail").asText()).isEqualTo("已过截止时间，不能提交");
        assertThat(h.harness.gradeCallCount()).isZero();

        String active = createDefaultAssignment(teacherA, "有效作业");
        MvcResult unknown = submitRaw(active, "{\"protocol\":\"ssh\",\"code\":\"x\"}",
                studentA1);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(400);
        assertThat(MAPPER.readTree(unknown.getResponse().getContentAsByteArray())
                .path("detail").asText()).isEqualTo("协议 ssh 不属于该作业");
        assertThat(h.harness.gradeCallCount()).isZero();
    }

    /* ------------------------------------------------------------------ */
    /* multipart 上传（验收 A：扩展名/编码边界/strip 语义）                  */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(4)
    void multipartUploadAndBoundaries() throws Exception {
        String aid = createDefaultAssignment(teacherA, "上传作业");
        h.harness.setSuccessPayload("dns", 80, 4, 5, "[]");

        // .c 上传 + protocol strip（" dns " → dns）。
        MvcResult ok = mvc.perform(multipart("/api/assignments/" + aid + "/submit")
                        .part(new MockPart("protocol", "  dns  ".getBytes(StandardCharsets.UTF_8)),
                                new MockPart("file", "plug_dns.c",
                                        "int main() { return 0; }".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", bearer(studentA1)))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String submissionId = MAPPER.readTree(ok.getResponse().getContentAsByteArray())
                .path("submission_id").asText();
        assertThat(h.columnValue("grading_submissions", "code_hash", "id=?", submissionId))
                .isEqualTo(SubmissionTestHarness.sha256Hex(
                        "int main() { return 0; }".getBytes(StandardCharsets.UTF_8)));
        assertThat(MAPPER.readTree(h.columnValue("grading_submissions",
                "results_json::text", "id=?", submissionId)).path("source_origin").asText())
                .isEqualTo("upload");

        // 大写扩展名 .C/.SO 允许（lower 后命中）；.txt 拒绝。
        assertThat(mvc.perform(multipart("/api/assignments/" + aid + "/submit")
                        .part(new MockPart("protocol", "dns".getBytes()),
                                new MockPart("file", "plug.C", "x".getBytes()))
                        .header("Authorization", bearer(studentA1)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
        MvcResult txt = mvc.perform(multipart("/api/assignments/" + aid + "/submit")
                        .part(new MockPart("protocol", "dns".getBytes()),
                                new MockPart("file", "plug.txt", "x".getBytes()))
                        .header("Authorization", bearer(studentA1)))
                .andReturn();
        assertThat(txt.getResponse().getStatus()).isEqualTo(400);
        assertThat(MAPPER.readTree(txt.getResponse().getContentAsByteArray())
                .path("detail").asText()).isEqualTo("仅支持上传 .c / .so 文件（收到 .txt）");

        // 无扩展名（Path.suffix 语义）；点文件 ".c" 后缀为空 → 无扩展名。
        for (String filename : new String[] {"noext", "dotfile.", ".c"}) {
            MvcResult noSuffix = mvc.perform(multipart("/api/assignments/" + aid + "/submit")
                            .part(new MockPart("protocol", "dns".getBytes()),
                                    new MockPart("file", filename, "x".getBytes()))
                            .header("Authorization", bearer(studentA1)))
                    .andReturn();
            assertThat(noSuffix.getResponse().getStatus()).isEqualTo(400);
            assertThat(MAPPER.readTree(noSuffix.getResponse().getContentAsByteArray())
                    .path("detail").asText())
                    .isEqualTo("仅支持上传 .c / .so 文件（收到 无扩展名）");
        }

        // 缺 file 部件 / file 无文件名 → 422；缺 protocol → 422（先于 file 判定）。
        MvcResult noFile = mvc.perform(multipart("/api/assignments/" + aid + "/submit")
                        .part(new MockPart("protocol", "dns".getBytes()))
                        .header("Authorization", bearer(studentA1)))
                .andReturn();
        assertThat(noFile.getResponse().getStatus()).isEqualTo(422);
        assertThat(MAPPER.readTree(noFile.getResponse().getContentAsByteArray())
                .path("detail").asText()).isEqualTo("需要上传 .c/.so 文件，或用 JSON 粘贴代码");
        MvcResult noName = mvc.perform(multipart("/api/assignments/" + aid + "/submit")
                        .part(new MockPart("protocol", "dns".getBytes()),
                                new MockPart("file", "", "x".getBytes()))
                        .header("Authorization", bearer(studentA1)))
                .andReturn();
        assertThat(noName.getResponse().getStatus()).isEqualTo(422);
        MvcResult noProtocol = mvc.perform(multipart("/api/assignments/" + aid + "/submit")
                        .part(new MockPart("file", "a.c", "x".getBytes()))
                        .header("Authorization", bearer(studentA1)))
                .andReturn();
        assertThat(noProtocol.getResponse().getStatus()).isEqualTo(422);
        assertThat(MAPPER.readTree(noProtocol.getResponse().getContentAsByteArray())
                .path("detail").asText()).isEqualTo("protocol must not be empty");

        // 非法 UTF-8 上传：hash 按替换后文本（验收 F），非原始字节。
        byte[] invalidBytes = HexFormat.of().parseHex("eda080");
        MvcResult binary = mvc.perform(multipart("/api/assignments/" + aid + "/submit")
                        .part(new MockPart("protocol", "dns".getBytes()),
                                new MockPart("file", "plug.so", invalidBytes))
                        .header("Authorization", bearer(studentA1)))
                .andReturn();
        assertThat(binary.getResponse().getStatus()).isEqualTo(200);
        String binaryId = MAPPER.readTree(binary.getResponse().getContentAsByteArray())
                .path("submission_id").asText();
        String stored = h.columnValue("grading_submissions", "code_hash", "id=?", binaryId);
        assertThat(stored).isEqualTo("a73f4cb996ceb6ee097888d897ae1004c9b1faab6c97629214139b9639aaf1af");
        assertThat(stored).isNotEqualTo(SubmissionTestHarness.sha256Hex(invalidBytes));
    }

    /* ------------------------------------------------------------------ */
    /* 大小上限（验收 C：拒绝不调用 MCP；JSON 路径晚于资格判定）             */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(5)
    void sizeLimitOrdering() throws Exception {
        h.harness.resetCounters();
        int counterBefore = h.counterValue(STUDENT_A1, "grading");
        String aid = createDefaultAssignment(teacherA, "大小作业");
        // JSON 路径：先资格判定（404 优先于 413）。
        MvcResult missingAssignment = submitRaw("asg_missing",
                "{\"protocol\":\"dns\",\"code\":\"" + "x".repeat(300000) + "\"}", studentA1);
        assertThat(missingAssignment.getResponse().getStatus()).isEqualTo(404);
        // 合法作业上 JSON 超限 → 413（grade_code 内文案）。
        MvcResult jsonTooLarge = submitRaw(aid,
                "{\"protocol\":\"dns\",\"code\":\"" + "x".repeat(300000) + "\"}", studentA1);
        assertThat(jsonTooLarge.getResponse().getStatus()).isEqualTo(413);
        assertThat(MAPPER.readTree(jsonTooLarge.getResponse().getContentAsByteArray())
                .path("detail").asText()).isEqualTo("源码大小超过上限（262144 字节）");

        // multipart 路径：解析期 413，先于快照（未知作业也 413）。
        byte[] bigFile = new byte[262145];
        java.util.Arrays.fill(bigFile, (byte) 'x');
        MvcResult uploadTooLarge = mvc.perform(multipart("/api/assignments/asg_missing/submit")
                        .part(new MockPart("protocol", "dns".getBytes()),
                                new MockPart("file", "a.c", bigFile))
                        .header("Authorization", bearer(studentA1)))
                .andReturn();
        assertThat(uploadTooLarge.getResponse().getStatus()).isEqualTo(413);
        assertThat(MAPPER.readTree(uploadTooLarge.getResponse().getContentAsByteArray())
                .path("detail").asText()).isEqualTo("文件超过大小上限（262144 字节）");

        assertThat(h.harness.gradeCallCount()).isZero();
        // 大小拒绝不消耗配额（计数保持测试前值）。
        assertThat(h.counterValue(STUDENT_A1, "grading")).isEqualTo(counterBefore);
    }

    /* ------------------------------------------------------------------ */
    /* JSON 校验（pydantic 手工模型形态 + 500 TypeError 面）                 */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(6)
    void jsonValidationShapes() throws Exception {
        String aid = createDefaultAssignment(teacherA, "校验作业");
        // protocol 数字 → string_type；空串 → string_too_short；65 字 → string_too_long。
        JsonNode wrongType = submitJson(aid, "{\"protocol\":5,\"code\":\"x\"}", studentA1);
        assertThat(wrongType.path("detail")).isEqualTo(MAPPER.readTree(
                "[{\"type\":\"string_type\",\"loc\":[\"protocol\"],"
                        + "\"msg\":\"Input should be a valid string\",\"input\":5}]"));
        JsonNode emptyProtocol = submitJson(aid, "{\"protocol\":\"\",\"code\":\"x\"}", studentA1);
        assertThat(emptyProtocol.path("detail")).isEqualTo(MAPPER.readTree(
                "[{\"type\":\"string_too_short\",\"loc\":[\"protocol\"],"
                        + "\"msg\":\"String should have at least 1 character\",\"input\":\"\"}]"));
        JsonNode longProtocol = submitJson(aid, "{\"protocol\":\"" + "p".repeat(65)
                + "\",\"code\":\"x\"}", studentA1);
        assertThat(longProtocol.path("detail")).isEqualTo(MAPPER.readTree(
                "[{\"type\":\"string_too_long\",\"loc\":[\"protocol\"],"
                        + "\"msg\":\"String should have at most 64 characters\",\"input\":\""
                        + "p".repeat(65) + "\"}]"));
        // 缺 code；code 类型错；code 空串。
        JsonNode missingCode = submitJson(aid, "{\"protocol\":\"dns\"}", studentA1);
        assertThat(missingCode.path("detail").path(0).path("type").asText()).isEqualTo("missing");
        assertThat(missingCode.path("detail").path(0).path("loc").path(0).asText())
                .isEqualTo("code");
        JsonNode wrongCodeType = submitJson(aid, "{\"protocol\":\"dns\",\"code\":3}",
                studentA1);
        assertThat(wrongCodeType.path("detail").path(0).path("type").asText())
                .isEqualTo("string_type");
        JsonNode emptyCode = submitJson(aid, "{\"protocol\":\"dns\",\"code\":\"\"}", studentA1);
        assertThat(emptyCode.path("detail").path(0).path("type").asText())
                .isEqualTo("string_too_short");
        // 两字段同时缺失按定义序（protocol, code）；missing 的 input=整个 body 字典。
        JsonNode both = submitJson(aid, "{\"extra\":1}", studentA1);
        assertThat(both.path("detail").path(0).path("loc").path(0).asText()).isEqualTo("protocol");
        assertThat(both.path("detail").path(0).path("input").path("extra").asInt()).isEqualTo(1);
        assertThat(both.path("detail").path(1).path("loc").path(0).asText()).isEqualTo("code");
        // 非法 JSON → 422 明文；非对象 body → 500 纯文本（Python TypeError 面，经全局兜底）。
        MvcResult badJson = submitRaw(aid, "{oops", studentA1);
        assertThat(badJson.getResponse().getStatus()).isEqualTo(422);
        assertThat(MAPPER.readTree(badJson.getResponse().getContentAsByteArray())
                .path("detail").asText()).isEqualTo("request body must be valid JSON");
        MvcResult nonObject = submitRaw(aid, "[]", studentA1);
        assertThat(nonObject.getResponse().getStatus()).isEqualTo(500);
        assertThat(nonObject.getResponse().getContentAsString()).isEqualTo("Internal Server Error");
    }

    /* ------------------------------------------------------------------ */
    /* 可见性与守卫（验收 A/D）                                             */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(7)
    void visibilityAndGuards() throws Exception {
        String withdrawn = createDefaultAssignment(teacherA, "撤回作业");
        mvc.perform(post("/api/assignments/" + withdrawn + "/withdraw")
                        .header("Authorization", bearer(teacherA)))
                .andExpect(status().isOk());
        assertThat(submitRaw(withdrawn, "{\"protocol\":\"dns\",\"code\":\"x\"}", studentA1)
                .getResponse().getStatus()).isEqualTo(404);

        String otherClass = createDefaultAssignment(teacherB, "别班作业");
        assertThat(submitRaw(otherClass, "{\"protocol\":\"dns\",\"code\":\"x\"}", studentA1)
                .getResponse().getStatus()).isEqualTo(404);
        // 同班学生可见。
        String studentB1 = h.login(mvc, STUDENT_B1, STUDENT_B1);
        h.harness.setSuccessPayload("dns", 80, 4, 5, "[]");
        assertThat(submitRaw(otherClass, "{\"protocol\":\"dns\",\"code\":\"x\"}", studentB1)
                .getResponse().getStatus()).isEqualTo(200);

        // 未分配学生 + scope=all（leader 发布）可见。
        String allScope = createAssignment(leader, "全员作业", "2099-01-01T00:00:00Z",
                "[{\"protocol\":\"dns\",\"points\":20}]");
        assertThat(submitRaw(allScope, "{\"protocol\":\"dns\",\"code\":\"x\"}", studentA1)
                .getResponse().getStatus()).isEqualTo(200);
        // 静态 token（created_by=null）对 class 作业不可见，对 all 作业可提交。
        assertThat(submitRaw(otherClass, "{\"protocol\":\"dns\",\"code\":\"x\"}",
                SubmissionTestHarness.TOKEN_VIEWER).getResponse().getStatus()).isEqualTo(404);
        assertThat(submitRaw(allScope, "{\"protocol\":\"dns\",\"code\":\"x\"}",
                SubmissionTestHarness.TOKEN_VIEWER).getResponse().getStatus()).isEqualTo(200);
    }

    /* ------------------------------------------------------------------ */
    /* 评测中途修改按受理快照（验收 D/E：响应受理分值，汇总当前分值）        */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(8)
    void keepsAcceptancePointsWhenAssignmentChangesDuringGrading() throws Exception {
        String aid = createDefaultAssignment(teacherA, "中途修改作业");
        h.harness.onCall = () -> {
            try {
                h.catalogRepo.updateAssignment(aid, TEACHER_A, java.util.List.of("admin"),
                        null, null, null,
                        java.util.List.of(
                                new io.dpdk.assistant.assignment.catalog.AssignmentRow.ItemRow(
                                        "dns", 40),
                                new io.dpdk.assistant.assignment.catalog.AssignmentRow.ItemRow(
                                        "http", 30)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        h.harness.setSuccessPayload("dns", 80, 4, 5, "[]");
        JsonNode body = submitJson(aid, "{\"protocol\":\"dns\",\"code\":\"x\"}", studentA1);
        h.harness.onCall = null;
        // 响应用受理分值（20 分），非修改后的 40 分。
        assertThat(body.path("points").asInt()).isEqualTo(20);
        assertThat(body.path("earned").asDouble()).isEqualTo(16.0);
        // 落库 accepted_assignment 为受理快照。
        String submissionId = body.path("submission_id").asText();
        JsonNode accepted = MAPPER.readTree(h.columnValue("grading_submissions",
                "results_json::text", "id=?", submissionId)).path("accepted_assignment");
        assertThat(accepted.path("points").asInt()).isEqualTo(20);
        assertThat(accepted.path("scope").asText()).isEqualTo("class");
        // 成绩视图按当前 40 分动态折算（汇总口径归 J2-03 report）。
        JsonNode grades = MAPPER.readTree(mvc.perform(
                        get("/api/assignments/" + aid + "/grades")
                                .header("Authorization", bearer(teacherA)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(grades.path("rows").path(0).path("scores").path("dns").asDouble())
                .isEqualTo(32.0);
    }

    /* ------------------------------------------------------------------ */
    /* 畸形 MCP payload（验收 G：舍入与异常语义）                           */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(9)
    void malformedPayloadSemantics() throws Exception {
        String aid = createDefaultAssignment(teacherA, "畸形作业");
        // score 缺失 → 0；results 缺失 → []。
        h.harness.setSuccessPayload("dns", 0, 0, 0, "[]");
        h.harness.successPayloadJson =
                "{\"ok\":true,\"protocol\":\"dns\",\"passed\":2}";
        JsonNode noScore = submitJson(aid, "{\"protocol\":\"dns\",\"code\":\"x\"}", studentA1);
        assertThat(noScore.path("score").asInt()).isZero();
        assertThat(noScore.path("passed").asInt()).isEqualTo(2);
        assertThat(noScore.path("total").asInt()).isZero();
        assertThat(noScore.path("results").isArray()).isTrue();
        assertThat(noScore.path("earned").asDouble()).isZero();
        // HALF_EVEN：79.5 → 80，78.5 → 78；字符串 "90" → 90。
        h.harness.setSuccessPayload("dns", 0, 0, 0, "[]");
        h.harness.successPayloadJson = "{\"ok\":true,\"protocol\":\"dns\",\"score\":79.5,"
                + "\"passed\":4,\"cases_total\":5,\"results\":[]}";
        assertThat(submitJson(aid, "{\"protocol\":\"dns\",\"code\":\"x\"}", studentA1)
                .path("score").asInt()).isEqualTo(80);
        h.harness.successPayloadJson = "{\"ok\":true,\"protocol\":\"dns\",\"score\":78.5,"
                + "\"passed\":4,\"cases_total\":5,\"results\":[]}";
        assertThat(submitJson(aid, "{\"protocol\":\"dns\",\"code\":\"x\"}", studentA1)
                .path("score").asInt()).isEqualTo(78);
        h.harness.successPayloadJson = "{\"ok\":true,\"protocol\":\"dns\",\"score\":\"90\","
                + "\"passed\":4,\"cases_total\":5,\"results\":[]}";
        assertThat(submitJson(aid, "{\"protocol\":\"dns\",\"code\":\"x\"}", studentA1)
                .path("score").asInt()).isEqualTo(90);
        // results 为字符串 → list("ab") 语义。
        h.harness.successPayloadJson = "{\"ok\":true,\"protocol\":\"dns\",\"score\":80,"
                + "\"passed\":4,\"cases_total\":5,\"results\":\"ab\"}";
        JsonNode chars = submitJson(aid, "{\"protocol\":\"dns\",\"code\":\"x\"}", studentA1);
        assertThat(chars.path("results").path(0).asText()).isEqualTo("a");
        assertThat(chars.path("results").path(1).asText()).isEqualTo("b");
        // score 非数值 → 500 纯文本（Python ValueError 面；全局兜底渲染，登记平台差异）。
        h.harness.successPayloadJson = "{\"ok\":true,\"protocol\":\"dns\",\"score\":\"abc\","
                + "\"passed\":4,\"cases_total\":5,\"results\":[]}";
        int rowsBefore = h.countRows("grading_submissions", null);
        MvcResult nanScore = submitRaw(aid, "{\"protocol\":\"dns\",\"code\":\"x\"}", studentA1);
        assertThat(nanScore.getResponse().getStatus()).isEqualTo(500);
        assertThat(nanScore.getResponse().getContentAsString()).isEqualTo("Internal Server Error");
        assertThat(h.countRows("grading_submissions", null)).isEqualTo(rowsBefore);
        // ok=false 未知错误 → 502 + detail；detail 缺失 → 兜底文案。
        h.harness.successPayloadJson = "{\"ok\":false,\"error\":\"cheat\",\"detail\":\"作弊嫌疑\"}";
        MvcResult cheat = submitRaw(aid, "{\"protocol\":\"dns\",\"code\":\"x\"}", studentA1);
        assertThat(cheat.getResponse().getStatus()).isEqualTo(502);
        assertThat(MAPPER.readTree(cheat.getResponse().getContentAsByteArray())
                .path("detail").asText()).isEqualTo("作弊嫌疑");
        h.harness.successPayloadJson = "{\"ok\":false,\"error\":\"boom\"}";
        MvcResult noDetail = submitRaw(aid, "{\"protocol\":\"dns\",\"code\":\"x\"}", studentA1);
        assertThat(noDetail.getResponse().getStatus()).isEqualTo(502);
        assertThat(MAPPER.readTree(noDetail.getResponse().getContentAsByteArray())
                .path("detail").asText()).isEqualTo("评测服务返回失败");
        // payload protocol 覆盖存储协议；best 仍按请求协议（dns 行最高 90 → 18.0；
        // dns-v2 行不计入 dns 的 best）。
        h.harness.setSuccessPayload("dns", 80, 4, 5, "[]");
        h.harness.successPayloadJson = h.defaultGradePayload("dns", 80, 4, 5, "[]")
                .replace("\"protocol\":\"dns\"", "\"protocol\":\"dns-v2\"");
        JsonNode renamed = submitJson(aid, "{\"protocol\":\"dns\",\"code\":\"x\"}", studentA1);
        String renamedId = renamed.path("submission_id").asText();
        assertThat(h.columnValue("grading_submissions", "protocol", "id=?", renamedId))
                .isEqualTo("dns-v2");
        assertThat(renamed.path("best_earned").asDouble()).isEqualTo(18.0);
        assertThat(h.countRows("grading_submissions",
                "assignment_id=? AND protocol='dns-v2'", aid)).isEqualTo(1);
    }

    /* ------------------------------------------------------------------ */
    /* 成绩行只读端口（实现步骤 4）                                         */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(10)
    void recordReaderReturnsRow() throws Exception {
        String aid = createDefaultAssignment(teacherA, "读取作业");
        h.harness.setSuccessPayload("dns", 80, 4, 5, "[]");
        JsonNode body = submitJson(aid, "{\"protocol\":\"dns\",\"code\":\"x\"}", studentA1);
        var record = h.reader.get(body.path("submission_id").asText());
        assertThat(record).isPresent();
        assertThat(record.get().userId()).isEqualTo(STUDENT_A1);
        assertThat(record.get().assignmentId()).isEqualTo(aid);
        assertThat(record.get().score()).isEqualTo(80);
        assertThat(record.get().resultsJson().path("source_origin").asText())
                .isEqualTo("pasted");
        assertThat(h.reader.get("grade_" + "0".repeat(16))).isEmpty();
    }

    /* ------------------------------------------------------------------ */
    /* UTF-8 替换解码 hash 电池（验收 F；Python CPython 冻结值）            */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(11)
    void pythonUtf8ReplaceHashParity() {
        Map<String, byte[]> battery = Map.ofEntries(
                Map.entry("valid_ascii", "int main() { return 0; }"
                        .getBytes(StandardCharsets.UTF_8)),
                Map.entry("surrogate_cesu", HexFormat.of().parseHex("eda080")),
                Map.entry("overlong_c0af", HexFormat.of().parseHex("c0af")),
                Map.entry("truncated_tail", new byte[] {'a', 'b', (byte) 0xE2, (byte) 0x82}),
                Map.entry("stray_cont", HexFormat.of().parseHex("8081")),
                Map.entry("mixed", new byte[] {'a', (byte) 0xFF, 'b', (byte) 0xF0, (byte) 0x90,
                        (byte) 0x28, (byte) 0xBC, (byte) 0xF4, (byte) 0x90, (byte) 0x80,
                        (byte) 0x80}),
                Map.entry("valid_utf8_cn", "中文路径".getBytes(StandardCharsets.UTF_8)),
                Map.entry("f0_80_80_80", HexFormat.of().parseHex("f0808080")),
                Map.entry("c2_bare", HexFormat.of().parseHex("c2")),
                Map.entry("e0_80_mid", new byte[] {(byte) 0xE0, (byte) 0x80, 'a'}),
                Map.entry("ed_a0_tail", new byte[] {(byte) 0xED, (byte) 0xA0}),
                Map.entry("f4_90_80_80", new byte[] {(byte) 0xF4, (byte) 0x90, (byte) 0x80,
                        (byte) 0x80}),
                Map.entry("e2_82_61", new byte[] {(byte) 0xE2, (byte) 0x82, 'a'}));
        Map<String, String> pythonFrozen = Map.ofEntries(
                Map.entry("valid_ascii",
                        "80a7161009ffaf868641acac3f5e49bc5f86021ee1d177f3b1cbb47573513649"),
                Map.entry("surrogate_cesu",
                        "a73f4cb996ceb6ee097888d897ae1004c9b1faab6c97629214139b9639aaf1af"),
                Map.entry("overlong_c0af",
                        "52793f8dc1d85e409f8c88be99d8b31d58f676246340150f406289e04a11151e"),
                Map.entry("truncated_tail",
                        "7ad10e79f47f28b42a026de5feb9fc43e99e0399fe85aee84ce578157c2216fe"),
                Map.entry("stray_cont",
                        "52793f8dc1d85e409f8c88be99d8b31d58f676246340150f406289e04a11151e"),
                Map.entry("mixed",
                        "c4ac6638a0253b2b30dc847c4b9ac9e3f5503dcfd48b7e43bab4b7c13e1c6e99"),
                Map.entry("valid_utf8_cn",
                        "1bab176604b9a21870613790acc87e33d73d51860580c45d62d04b094d4aee97"),
                Map.entry("f0_80_80_80",
                        "c22917d19a6656769f72fb7090d6590cb857aa735ef9e646a4ef15d78e7d2317"),
                Map.entry("c2_bare",
                        "83d544ccc223c057d2bf80d3f2a32982c32c3c0db8e2674820da5064783fb097"),
                Map.entry("e0_80_mid",
                        "85c19ece421fc0dd7f225342a4b573b06f825cb4dc6930b18c7f5768592868dd"),
                Map.entry("ed_a0_tail",
                        "52793f8dc1d85e409f8c88be99d8b31d58f676246340150f406289e04a11151e"),
                Map.entry("f4_90_80_80",
                        "c22917d19a6656769f72fb7090d6590cb857aa735ef9e646a4ef15d78e7d2317"),
                Map.entry("e2_82_61",
                        "94b964456d33b6a0fb82bd59fb16d700eb6d2fea5366d4974e28980cc9c7144e"));
        for (var entry : battery.entrySet()) {
            String replaced = PythonUtf8.replaceDecode(entry.getValue());
            String hash = PythonUtf8.sha256Hex(PythonUtf8.encodeReplace(replaced));
            assertThat(hash).as("case %s", entry.getKey())
                    .isEqualTo(pythonFrozen.get(entry.getKey()));
        }
        // JSON 粘贴路径：孤立代理经 encode-replace 的 hash 与 Python 一致（'?' 替换）。
        String surrogateCode = "a\ud800b";
        assertThat(PythonUtf8.sha256Hex(PythonUtf8.encodeReplace(surrogateCode)))
                .isEqualTo("c2a7b64a2d2520047bfc9248fdda834c9cc2c0c9e89826355c7a6ae16fe11179");
    }
}
