package io.dpdk.assistant.assignment.submission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

/**
 * J2-04 停号×成绩落库竞争（验收 E）：users 行锁按事务提交顺序裁决——
 * 先提交者决定成绩能否落库；被停号用户不能在检查后绕过落库门禁。
 *
 * 竞争驱动用两条原生连接复刻两参与者（与 AuthAccountWriteGuard/停号事务
 * 同一条 UPDATE 语句），保证确定性；另有端到端变体：MCP 评测中途停号
 * （onCall 钩子），受理事务内门禁拒绝落库。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class GradingPersistenceRaceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static SubmissionTestHarness h;

    @BeforeAll
    static void setup() throws Exception {
        h = SubmissionTestHarness.accountMode("race");
        h.seedUser("U20240000001", "[\"leader\"]", null, true, "U20240000001", null);
        h.seedUser("U20240000002", "[\"admin\"]", null, true, "U20240000002", "U20240000001");
        h.seedUser("U20240000011", "[\"viewer\"]", null, true, "U20240000011", "U20240000002");
        h.seedUser("U20240000021", "[\"viewer\"]", null, true, "U20240000021", "U20240000002");
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    private String createAssignment(MockMvc mvc, String teacherToken) throws Exception {
        var result = mvc.perform(post("/api/assignments")
                        .contentType("application/json")
                        .header("Authorization", "Bearer " + teacherToken)
                        .content("{\"title\":\"竞争作业\",\"description\":\"P0\","
                                + "\"deadline\":\"2099-01-01T00:00:00Z\","
                                + "\"items\":[{\"protocol\":\"dns\",\"points\":20}]}"))
                .andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsByteArray())
                .path("id").asText();
    }

    private SubmissionGradeWriter.RecordInput recordInput(String userId, String assignmentId) {
        return new SubmissionGradeWriter.RecordInput(userId, "dns",
                SubmissionTestHarness.sha256Hex("int x;".getBytes()), 80, 4, 5,
                "{\"source_origin\":\"pasted\",\"results\":[],\"accepted_assignment\":{}}",
                assignmentId);
    }

    /** 锁住 users 行但不提交（同一守卫语句）；返回保持打开的连接。 */
    private Connection holdUserRowLock(String userId, boolean disable) throws Exception {
        Connection conn = h.ds.getConnection();
        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE users SET enabled = enabled WHERE id = ? AND enabled = true")) {
            ps.setString(1, userId);
            assertThat(ps.executeUpdate()).isEqualTo(1);
        }
        if (disable) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET enabled = false WHERE id = ?")) {
                ps.setString(1, userId);
                ps.executeUpdate();
            }
        }
        return conn;
    }

    /** pg_locks 中未授予的 transactionid 锁（存在即有事务在等待行锁提交序）。 */
    private int waitingLockTransactions() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            try (Connection conn = h.ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "SELECT count(*) AS c FROM pg_locks "
                                 + "WHERE NOT granted AND locktype = 'transactionid'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    int count = rs.getInt("c");
                    if (count >= 1) {
                        return count;
                    }
                }
            }
            Thread.sleep(50);
        }
        return 0;
    }

    /* ------------------------------------------------------------------ */
    /* 停号事务先提交 → 成绩不落（403 语义载体）                            */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(1)
    void disableFirstWinsGradeRejected() throws Exception {
        MockMvc mvc = h.newMvc();
        String teacher = h.login(mvc, "U20240000002", "U20240000002");
        String aid = createAssignment(mvc, teacher);
        assertThat(h.columnValue("users", "enabled", "id=?", "U20240000011")).isEqualTo("t");

        Connection disabler = holdUserRowLock("U20240000011", true);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch started = new CountDownLatch(1);
            Future<?> record = pool.submit(() -> {
                started.countDown();
                assertThatThrownBy(() -> h.writer.recordSubmission(
                                recordInput("U20240000011", aid)))
                        .isInstanceOf(ApiDetailException.class)
                        .extracting(e -> ((ApiDetailException) e).status())
                        .isEqualTo(HttpStatus.FORBIDDEN);
            });
            // 写事务已启动并阻塞在 users 行锁上。
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);
            assertThat(record.isDone()).isFalse();
            disabler.commit();
            record.get(5, TimeUnit.SECONDS);
            // 停号已生效、成绩未落。
            assertThat(h.columnValue("users", "enabled", "id=?", "U20240000011"))
                    .isEqualTo("f");
            assertThat(h.countRows("grading_submissions", "user_id=?", "U20240000011"))
                    .isZero();
        } finally {
            pool.shutdownNow();
            try (Connection restore = h.ds.getConnection();
                 PreparedStatement ps = restore.prepareStatement(
                         "UPDATE users SET enabled = true WHERE id = ?")) {
                ps.setString(1, "U20240000011");
                ps.executeUpdate();
            }
            disabler.close();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 成绩事务先提交 → 成绩落库、停号随后生效（两者皆成立，先提交者胜）    */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(2)
    void gradeFirstWinsThenDisableApplies() throws Exception {
        String aid = "asg_race_manual_" + Long.toHexString(System.nanoTime());
        try (Connection seed = h.ds.getConnection();
             PreparedStatement ps = seed.prepareStatement(
                     "INSERT INTO assignments(id,title,description,created_by,scope,deadline,"
                             + "status,created_at,updated_at) VALUES "
                             + "(?,'竞争作业','',?,'class','2099-01-01','active',now(),now())")) {
            ps.setString(1, aid);
            ps.setString(2, "U20240000002");
            ps.executeUpdate();
        }
        assertThat(h.columnValue("users", "enabled", "id=?", "U20240000021")).isEqualTo("t");

        // 成绩事务：与守卫同语句锁行 + 插成绩，不提交。
        Connection grader = h.ds.getConnection();
        grader.setAutoCommit(false);
        try {
            try (PreparedStatement ps = grader.prepareStatement(
                    "UPDATE users SET enabled = enabled WHERE id = ? AND enabled = true"
                            + " RETURNING id")) {
                ps.setString(1, "U20240000021");
                try (ResultSet rs = ps.executeQuery()) {
                    assertThat(rs.next()).isTrue();
                }
            }
            try (PreparedStatement ps = grader.prepareStatement(
                    "INSERT INTO grading_submissions(id,user_id,session_id,protocol,code_hash,"
                            + "score,passed,total,results_json,assignment_id,task_job_id,"
                            + "created_at) VALUES ('grade_race0000000001',?,NULL,'dns','h',"
                            + "80,4,5,'{}'::jsonb,?,NULL,now())")) {
                ps.setString(1, "U20240000021");
                ps.setString(2, aid);
                ps.executeUpdate();
            }
            // 停号事务在独立线程排队等待成绩事务的行锁。
            ExecutorService pool = Executors.newSingleThreadExecutor();
            Connection disabler = h.ds.getConnection();
            disabler.setAutoCommit(false);
            try {
                CountDownLatch started = new CountDownLatch(1);
                Future<?> disable = pool.submit(() -> {
                    started.countDown();
                    try {
                        try (PreparedStatement ps = disabler.prepareStatement(
                                "UPDATE users SET enabled = false WHERE id = ?")) {
                            ps.setString(1, "U20240000021");
                            ps.executeUpdate();
                        }
                        disabler.commit();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
                assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(waitingLockTransactions()).as("停号事务等待成绩事务行锁")
                        .isGreaterThanOrEqualTo(1);
                grader.commit();
                disable.get(5, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
                disabler.close();
            }
        } finally {
            grader.close();
        }
        // 两者皆成立：成绩行保留（不删除已落成绩）、账号停用。
        assertThat(h.countRows("grading_submissions", "task_job_id IS NULL AND user_id=? "
                + "AND assignment_id=?", "U20240000021", aid)).isEqualTo(1);
        assertThat(h.columnValue("users", "enabled", "id=?", "U20240000021")).isEqualTo("f");

        // 清理：恢复账号、删除手工成绩行（保持树状态可复用）。
        try (Connection restore = h.ds.getConnection();
             PreparedStatement ps = restore.prepareStatement(
                     "DELETE FROM grading_submissions WHERE assignment_id = ?")) {
            ps.setString(1, aid);
            ps.executeUpdate();
        }
        try (Connection restore = h.ds.getConnection();
             PreparedStatement ps = restore.prepareStatement(
                     "UPDATE users SET enabled = true WHERE id = ?")) {
            ps.setString(1, "U20240000021");
            ps.executeUpdate();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 端到端：MCP 评测中途停号 → 受理事务门禁拒绝落库，成绩 0 行            */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(3)
    void disableDuringGradingRejectedAtRecordGate() throws Exception {
        MockMvc mvc = h.newMvc();
        h.harness.resetCounters();
        h.harness.success();
        h.harness.setSuccessPayload("dns", 80, 4, 5, "[]");
        h.harness.onCall = () -> {
            try (Connection conn = h.ds.getConnection();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE users SET enabled = false WHERE id = ?")) {
                ps.setString(1, "U20240000021");
                ps.executeUpdate();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        };
        String teacher = h.login(mvc, "U20240000002", "U20240000002");
        String student = h.login(mvc, "U20240000021", "U20240000021");
        String aid = createAssignment(mvc, teacher);
        int counterBefore = h.counterValue("U20240000021", "grading");

        var result = mvc.perform(post("/api/assignments/" + aid + "/submit")
                        .contentType("application/json")
                        .header("Authorization", "Bearer " + student)
                        .content("{\"protocol\":\"dns\",\"code\":\"int x;\"}"))
                .andReturn();
        h.harness.onCall = null;
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(MAPPER.readTree(result.getResponse().getContentAsByteArray())
                .path("detail").asText()).isEqualTo("账号已停用，本次评测不记成绩");
        // MCP 已发生、配额已计、成绩未落（被停号用户不能绕过落库门禁）。
        assertThat(h.harness.gradeCallCount()).isEqualTo(1);
        assertThat(h.countRows("grading_submissions", "assignment_id=?", aid)).isZero();
        assertThat(h.counterValue("U20240000021", "grading")).isEqualTo(counterBefore + 1);

        try (Connection restore = h.ds.getConnection();
             PreparedStatement ps = restore.prepareStatement(
                     "UPDATE users SET enabled = true WHERE id = ?")) {
            ps.setString(1, "U20240000021");
            ps.executeUpdate();
        }
    }

    /* ------------------------------------------------------------------ */
    /* 已停号用户的 token → 认证层 401（不进入业务面）                      */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(4)
    void disabledAccountTokenRejectedAtAuth() throws Exception {
        try (Connection conn = h.ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET enabled = false WHERE id = ?")) {
            ps.setString(1, "U20240000021");
            ps.executeUpdate();
        }
        MockMvc mvc = h.newMvc();
        var login = mvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"U20240000021\",\"password\":\"U20240000021\"}"))
                .andReturn();
        // 停号后登录拒绝（J2-01 语义）。
        assertThat(login.getResponse().getStatus()).isEqualTo(401);
        try (Connection restore = h.ds.getConnection();
             PreparedStatement ps = restore.prepareStatement(
                     "UPDATE users SET enabled = true WHERE id = ?")) {
            ps.setString(1, "U20240000021");
            ps.executeUpdate();
        }
    }
}
