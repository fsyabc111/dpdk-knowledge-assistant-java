package io.dpdk.assistant.assignment.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.harness.StreamableHttpHarnessClient;
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
 * J2-04 配额与真实 harness 集成（验收 B/C）：grading 配额与聊天评测共用
 * usage_counters（kind=grading，独立事务、失败计数保留）；豁免角色一致
 * （{operator,admin,leader}）；429/504/502 映射；真实 StreamableHttpHarnessClient
 * 连 JDK HttpServer fake MCP——已提交后失败 UNKNOWN 恰 1 次 tools/call 不重发。
 * fake MCP 只记 initialize/tools call 计数，不落不打印 plugin_source。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class GradingQuotaIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 固定墙钟当日（配额日计数键）。 */
    private static final String DAY = "2026-10-25";

    private static SubmissionTestHarness h;
    private static final FakeMcpServer FAKE_MCP = new FakeMcpServer();
    private static String fakeMcpUrl;

    @BeforeAll
    static void setup() throws Exception {
        h = SubmissionTestHarness.accountMode("quota");
        h.seedUser("U20250000001", "[\"leader\"]", null, true, "U20250000001", null);
        h.seedUser("U20250000002", "[\"admin\"]", null, true, "U20250000002", "U20250000001");
        h.seedUser("U20250000011", "[\"viewer\"]", null, true, "U20250000011", "U20250000002");
        h.seedUser("U20250000021", "[\"viewer\"]", null, true, "U20250000021", "U20250000002");
        fakeMcpUrl = FAKE_MCP.start();
        h.submitHarness = new StreamableHttpHarnessClient(fakeMcpUrl);
    }

    @AfterAll
    static void teardown() throws Exception {
        FAKE_MCP.stop();
        if (h != null) {
            h.drop();
        }
    }

    private MockMvc realMvc() {
        return h.newMvc();
    }

    private String createAssignment(MockMvc mvc, String teacherToken, String title)
            throws Exception {
        MvcResult result = mvc.perform(post("/api/assignments")
                        .contentType("application/json")
                        .header("Authorization", "Bearer " + teacherToken)
                        .content("{\"title\":\"" + title + "\",\"description\":\"P0\","
                                + "\"deadline\":\"2099-01-01T00:00:00Z\","
                                + "\"items\":[{\"protocol\":\"dns\",\"points\":20}]}"))
                .andExpect(status().isCreated()).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsByteArray())
                .path("id").asText();
    }

    private MvcResult submit(MockMvc mvc, String aid, String token) throws Exception {
        return mvc.perform(post("/api/assignments/" + aid + "/submit")
                        .contentType("application/json")
                        .header("Authorization", "Bearer " + token)
                        .content("{\"protocol\":\"dns\",\"code\":\"int x;\"}"))
                .andReturn();
    }

    /* ------------------------------------------------------------------ */
    /* 成功路径：真实 MCP 三步，配额 +1                                     */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(1)
    void successAgainstFakeMcpCountsQuotaOnce() throws Exception {
        FAKE_MCP.setToolMode(FakeMcpServer.ToolMode.SUCCESS);
        MockMvc mvc = realMvc();
        String teacher = h.login(mvc, "U20250000002", "U20250000002");
        String student = h.login(mvc, "U20250000011", "U20250000011");
        String aid = createAssignment(mvc, teacher, "配额成功作业");

        int toolsBefore = FAKE_MCP.toolCalls.get();
        MvcResult ok = submit(mvc, aid, student);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(FAKE_MCP.toolCalls.get() - toolsBefore).isEqualTo(1);
        assertThat(FAKE_MCP.initializes.get()).isGreaterThanOrEqualTo(1);
        assertThat(h.counterValue("U20250000011", "grading")).isEqualTo(1);
        assertThat(h.countRows("grading_submissions", "assignment_id=?", aid)).isEqualTo(1);
    }

    /* ------------------------------------------------------------------ */
    /* 429：超额拒绝（独立事务失败计数保留），第 4 次不触达 MCP              */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(2)
    void quotaExceededMaps429AndKeepsFailedCount() throws Exception {
        FAKE_MCP.setToolMode(FakeMcpServer.ToolMode.SUCCESS);
        MockMvc mvc = realMvc();
        String teacher = h.login(mvc, "U20250000002", "U20250000002");
        String student = h.login(mvc, "U20250000011", "U20250000011");
        String aid = createAssignment(mvc, teacher, "配额超限作业");

        // 预置当日计数 19（默认额度 20）→ 再 1 次成功后达 20，下一次 429。
        h.seedCounter("U20250000011", DAY, "grading", 19);
        int toolsBefore = FAKE_MCP.toolCalls.get();
        assertThat(submit(mvc, aid, student).getResponse().getStatus()).isEqualTo(200);
        assertThat(h.counterValue("U20250000011", "grading")).isEqualTo(20);

        MvcResult exceeded = submit(mvc, aid, student);
        assertThat(exceeded.getResponse().getStatus()).isEqualTo(429);
        assertThat(MAPPER.readTree(exceeded.getResponse().getContentAsByteArray())
                .path("detail").asText())
                .isEqualTo("今日评测配额已用完（20 次/天），请明天再试");
        // 失败计数保留 + 第 5 次未触达 MCP。
        assertThat(h.counterValue("U20250000011", "grading")).isEqualTo(21);
        assertThat(FAKE_MCP.toolCalls.get() - toolsBefore).isEqualTo(1);
        assertThat(h.countRows("grading_submissions", "assignment_id=?", aid)).isEqualTo(1);
    }

    /* ------------------------------------------------------------------ */
    /* 豁免角色：operator 静态 token 不计 grading 配额                      */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(3)
    void exemptRoleSkipsGradingQuota() throws Exception {
        FAKE_MCP.setToolMode(FakeMcpServer.ToolMode.SUCCESS);
        MockMvc mvc = realMvc();
        // scope=all（leader 发布）——静态 token 主体 created_by=null 仅可见全员作业。
        String leaderToken = h.login(mvc, "U20250000001", "U20250000001");
        String aid = createAssignment(mvc, leaderToken, "豁免角色作业");

        assertThat(h.counterValue("t-operator", "grading")).isZero();
        int toolsBefore = FAKE_MCP.toolCalls.get();
        MvcResult ok = submit(mvc, aid, SubmissionTestHarness.TOKEN_OPERATOR);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(h.counterValue("t-operator", "grading")).isZero();
        assertThat(FAKE_MCP.toolCalls.get() - toolsBefore).isEqualTo(1);
    }

    /* ------------------------------------------------------------------ */
    /* 502：tools/call 已提交后失败（HTTP 500）→ UNKNOWN 恰一次、不重发、    */
    /* 成绩不落、配额已计                                                    */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(4)
    void unknownAfterSubmissionMaps502WithoutRetry() throws Exception {
        FAKE_MCP.setToolMode(FakeMcpServer.ToolMode.HTTP_500);
        MockMvc mvc = realMvc();
        String teacher = h.login(mvc, "U20250000002", "U20250000002");
        String student = h.login(mvc, "U20250000021", "U20250000021");
        String aid = createAssignment(mvc, teacher, "未知结果作业");

        int toolsBefore = FAKE_MCP.toolCalls.get();
        MvcResult lost = submit(mvc, aid, student);
        assertThat(lost.getResponse().getStatus()).isEqualTo(502);
        assertThat(MAPPER.readTree(lost.getResponse().getContentAsByteArray())
                .path("detail").asText()).startsWith("评测服务不可达：");
        // 已提交后恰 1 次 tools/call，不盲重发；成绩不落；配额副作用已发生。
        assertThat(FAKE_MCP.toolCalls.get() - toolsBefore).isEqualTo(1);
        assertThat(h.countRows("grading_submissions", "assignment_id=?", aid)).isZero();
        assertThat(h.counterValue("U20250000021", "grading")).isEqualTo(1);
    }

    /* ------------------------------------------------------------------ */
    /* 504：Deadline 在提交前到期（initialize 拖慢）→ grading_timeout        */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(5)
    void deadlineExceededBeforeSubmissionMaps504() throws Exception {
        FAKE_MCP.setToolMode(FakeMcpServer.ToolMode.SUCCESS);
        FAKE_MCP.setInitializeDelayMillis(1200);
        try {
            h.gradingSettings = new SubmissionSettings(1, 262144, 20);
            MockMvc mvc = realMvc();
            String teacher = h.login(mvc, "U20250000002", "U20250000002");
            String student = h.login(mvc, "U20250000011", "U20250000011");
            String aid = createAssignment(mvc, teacher, "超时作业");

            h.seedCounter("U20250000011", DAY, "grading", 0);
            int toolsBefore = FAKE_MCP.toolCalls.get();
            MvcResult timeout = submit(mvc, aid, student);
            assertThat(timeout.getResponse().getStatus()).isEqualTo(504);
            assertThat(MAPPER.readTree(timeout.getResponse().getContentAsByteArray())
                    .path("detail").asText()).isEqualTo("评测超过 1 秒未完成");
            assertThat(FAKE_MCP.toolCalls.get() - toolsBefore).isZero();
            assertThat(h.countRows("grading_submissions", "assignment_id=?", aid)).isZero();
            assertThat(h.counterValue("U20250000011", "grading")).isEqualTo(1);
        } finally {
            FAKE_MCP.setInitializeDelayMillis(0);
            h.gradingSettings = SubmissionSettings.DEFAULTS;
        }
    }

    /* ------------------------------------------------------------------ */
    /* 建连失败（服务不可达）→ NOT_SUBMITTED 502（Python harness_unavailable）*/
    /* ------------------------------------------------------------------ */

    @Test
    @Order(6)
    void unreachableHarnessMaps502NotSubmitted() throws Exception {
        h.submitHarness = new StreamableHttpHarnessClient("http://127.0.0.1:9/mcp");
        try {
            MockMvc mvc = realMvc();
            String teacher = h.login(mvc, "U20250000002", "U20250000002");
            String student = h.login(mvc, "U20250000021", "U20250000021");
            String aid = createAssignment(mvc, teacher, "不可达作业");

            MvcResult down = submit(mvc, aid, student);
            assertThat(down.getResponse().getStatus()).isEqualTo(502);
            assertThat(h.countRows("grading_submissions", "assignment_id=?", aid)).isZero();
        } finally {
            h.submitHarness = new StreamableHttpHarnessClient(fakeMcpUrl);
        }
    }
}
