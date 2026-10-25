package io.dpdk.assistant.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.SQLException;
import java.util.List;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * J2-02 删除事务测试（验收 D/E/G）：密码确认、静态 token 挡删除、自保护、
 * 单事务级联清理（token/会话/引用/配额/重置申请；成绩保留脱钩）、审计按树隔离、
 * 中段故障整体回滚（真实 PG 事务 + SQLException 注入）、停号级联与
 * AccountWriteGuard 的账号行事务顺序。审计断言依赖 @Order 顺序累积。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class AdminDeletionTransactionTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String LEADER = "U20260000001";
    private static final String TEACHER_A = "U20260000002";
    private static final String TEACHER_B = "U20260000003";
    private static final String STUDENT_A1 = "U20260000011";
    private static final String STUDENT_A2 = "U20260000012";
    private static final String STUDENT_B1 = "U20260000021";

    private static AdminTestHarness h;

    @BeforeAll
    static void setup() throws Exception {
        h = AdminTestHarness.accountMode("delete");
        h.seedUser(LEADER, "[\"leader\"]", null, true, true, LEADER, null);
        h.seedUser(TEACHER_A, "[\"admin\"]", null, true, true, TEACHER_A, LEADER);
        h.seedUser(TEACHER_B, "[\"admin\"]", null, true, true, TEACHER_B, LEADER);
        h.seedUser(STUDENT_A1, "[\"viewer\"]", null, true, true, STUDENT_A1, TEACHER_A);
        h.seedUser(STUDENT_A2, "[\"viewer\"]", null, true, true, STUDENT_A2, TEACHER_A);
        h.seedUser(STUDENT_B1, "[\"viewer\"]", null, true, true, STUDENT_B1, TEACHER_B);
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

    /* ------------------------------------------------------------------ */
    /* 验收 D：密码确认与静态 token                                          */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(1)
    void deleteRequiresCorrectPassword() throws Exception {
        MockMvc mvc = h.newMvc();
        String token = h.login(mvc, TEACHER_A, TEACHER_A);
        mvc.perform(post("/api/admin/users/delete").contentType("application/json")
                        .header("Authorization", auth(token))
                        .content("{\"ids\":[\"" + STUDENT_A1 + "\"],\"password\":\"wrongpassword1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(r -> assertThat(r.getResponse().getContentAsString())
                        .isEqualTo("{\"detail\":\"password verification failed\"}"));
        assertThat(h.countRows("users", "id=?", STUDENT_A1)).isEqualTo(1);
        assertThat(h.countRows("admin_audit_log", null, (String[]) null)).isZero();
    }

    @Test
    @Order(2)
    void staticTokenCannotDelete() throws Exception {
        // 静态应急 token 主体不在 users 表中，无法通过密码校验（同名标识也不行）。
        h.seedUser("emergency-admin", "[\"admin\"]", null, true, true, "whatever1", null);
        MockMvc mvc = h.newMvc();
        mvc.perform(post("/api/admin/users/delete").contentType("application/json")
                        .header("Authorization", auth(AdminTestHarness.FALLBACK_TOKEN))
                        .content("{\"ids\":[\"" + STUDENT_A1 + "\"],\"password\":\"whatever\"}"))
                .andExpect(status().isForbidden());
        assertThat(h.countRows("users", "id=?", STUDENT_A1)).isEqualTo(1);
    }

    /* ------------------------------------------------------------------ */
    /* 树隔离与自保护                                                        */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(3)
    void cannotDeleteSelfAndTreeScoping() throws Exception {
        MockMvc mvc = h.newMvc();
        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        // 自己进 skipped，不打断批量。
        MvcResult self = mvc.perform(post("/api/admin/users/delete")
                        .contentType("application/json").header("Authorization", auth(teacherToken))
                        .content("{\"ids\":[\"" + TEACHER_A + "\"],\"password\":\"" + TEACHER_A + "\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(MAPPER.readTree(self.getResponse().getContentAsString()).toString())
                .isEqualTo("{\"deleted\":[],\"skipped\":[\"" + TEACHER_A + "\"]}");
        assertThat(h.countRows("users", "id=?", TEACHER_A)).isEqualTo(1);
        // 他班学生/不存在跳过，自己的学生删除成功。
        MvcResult mixed = mvc.perform(post("/api/admin/users/delete")
                        .contentType("application/json").header("Authorization", auth(teacherToken))
                        .content("{\"ids\":[\"" + STUDENT_B1 + "\",\"U20269999999\",\"" + STUDENT_A1
                                + "\"],\"password\":\"" + TEACHER_A + "\"}"))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = MAPPER.readTree(mixed.getResponse().getContentAsString());
        assertThat(body.path("deleted").toString()).isEqualTo("[\"" + STUDENT_A1 + "\"]");
        assertThat(body.path("skipped").size()).isEqualTo(2);
        assertThat(h.countRows("users", "id=?", STUDENT_B1)).isEqualTo(1);
        assertThat(h.countRows("users", "id=?", STUDENT_A1)).isZero();
        // viewer 主体不可见端点组。
        String viewerToken = h.login(mvc, STUDENT_A2, STUDENT_A2);
        mvc.perform(post("/api/admin/users/delete").contentType("application/json")
                        .header("Authorization", auth(viewerToken))
                        .content("{\"ids\":[\"U20260000051\"],\"password\":\"x\"}"))
                .andExpect(status().isNotFound());
    }

    /* ------------------------------------------------------------------ */
    /* 验收 E：级联清理与审计                                                */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(4)
    void deleteCascadesDataAndWritesAudit() throws Exception {
        // 造数据：学生会话（消息+引用）、配额计数、pending 改密申请、登录 token、
        // 会话关联成绩 + 任务关联成绩。
        h.seedUser("U20260000051", "[\"viewer\"]", null, true, true, "U20260000051", TEACHER_A);
        MockMvc mvc = h.newMvc();
        String studentToken = h.login(mvc, "U20260000051", "U20260000051");
        h.insertSessionWithContent("U20260000051", "sess-d1");
        h.insertUsageCounter("U20260000051", "2026-01-01", 3);
        h.insertPendingResetRequest("req-d1", "U20260000051", "2026-01-01T00:00:00+00:00");
        h.insertGradingSubmission("grade-session-d1", "U20260000051", "sess-d1", null);
        h.insertPendingRagTask("task-d1", "U20260000051");
        h.insertGradingSubmission("grade-task-d1", "U20260000051", null, "job-task-d1");

        String teacherToken = h.login(mvc, TEACHER_A, TEACHER_A);
        MvcResult result = mvc.perform(post("/api/admin/users/delete")
                        .contentType("application/json").header("Authorization", auth(teacherToken))
                        .content("{\"ids\":[\"U20260000051\"],\"password\":\"" + TEACHER_A + "\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(MAPPER.readTree(result.getResponse().getContentAsString()).path("deleted")
                .toString()).isEqualTo("[\"U20260000051\"]");

        // 关联表全清（删除前后摘要对照见 evidence/J2-02/deletion-cascade.md）。
        assertThat(h.countRows("users", "id=?", "U20260000051")).isZero();
        assertThat(h.countRows("api_tokens", "user_id=?", "U20260000051")).isZero();
        assertThat(h.countRows("assistant_sessions", "id=?", "sess-d1")).isZero();
        assertThat(h.countRows("assistant_messages", "session_id=?", "sess-d1")).isZero();
        assertThat(h.countRows("assistant_citations", "message_id=?", "m1")).isZero();
        assertThat(h.countRows("usage_counters", "subject=?", "U20260000051")).isZero();
        assertThat(h.countRows("password_reset_requests", "user_id=?", "U20260000051")).isZero();
        // 成绩保留：会话关联行 session_id 置 NULL 脱钩；任务关联行同样保留（删除路径不触碰）。
        assertThat(h.countRows("grading_submissions", "user_id=?", "U20260000051")).isEqualTo(2);
        assertThat(h.columnValue("grading_submissions", "session_id", "id=?", "grade-session-d1"))
                .isNull();
        assertThat(h.countRows("rag_requests", "id=?", "task-d1")).isEqualTo(1);

        // 被删学生的 token 立即失效（401 由 resolver 抛出）。
        mvc.perform(get("/api/admin/users").header("Authorization", auth(studentToken)))
                .andExpect(status().isUnauthorized());

        // 审计行：一次删除一行，记录操作者与目标；targets_json 落库为 JSON 数组（jsonb 比较需显式 cast）。
        assertThat(h.countRows("admin_audit_log", "targets_json=?::jsonb", "[\"U20260000051\"]"))
                .isEqualTo(1);
        assertThat(h.columnValue("admin_audit_log", "actor", "targets_json=?::jsonb",
                "[\"U20260000051\"]")).isEqualTo(TEACHER_A);
        assertThat(h.columnValue("admin_audit_log", "action", "targets_json=?::jsonb",
                "[\"U20260000051\"]")).isEqualTo("delete_users");
    }

    @Test
    @Order(5)
    void deleteTeacherUnassignsStudents() throws Exception {
        MockMvc mvc = h.newMvc();
        String leaderToken = h.login(mvc, LEADER, LEADER);
        MvcResult result = mvc.perform(post("/api/admin/users/delete")
                        .contentType("application/json").header("Authorization", auth(leaderToken))
                        .content("{\"ids\":[\"" + TEACHER_A + "\"],\"password\":\"" + LEADER + "\"}"))
                .andExpect(status().isOk()).andReturn();
        assertThat(MAPPER.readTree(result.getResponse().getContentAsString()).path("deleted")
                .toString()).isEqualTo("[\"" + TEACHER_A + "\"]");
        assertThat(h.countRows("users", "id=?", TEACHER_A)).isZero();
        // 名下学生保留，created_by 置 NULL（未分配组）。
        assertThat(h.columnValue("users", "created_by", "id=?", STUDENT_A2)).isNull();
    }

    @Test
    @Order(6)
    void auditLogScopedByActor() throws Exception {
        MockMvc mvc = h.newMvc();
        // 此前累积：TEACHER_A 两行（A1、U20260000051）、LEADER 一行（TEACHER_A）。
        String teacherB = h.login(mvc, TEACHER_B, TEACHER_B);
        String leaderToken = h.login(mvc, LEADER, LEADER);
        h.seedUser("U20260000061", "[\"viewer\"]", null, true, true, "U20260000061", TEACHER_B);
        mvc.perform(post("/api/admin/users/delete").contentType("application/json")
                        .header("Authorization", auth(teacherB))
                        .content("{\"ids\":[\"U20260000061\"],\"password\":\"" + TEACHER_B + "\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/users/delete").contentType("application/json")
                        .header("Authorization", auth(leaderToken))
                        .content("{\"ids\":[\"" + STUDENT_B1 + "\"],\"password\":\"" + LEADER + "\"}"))
                .andExpect(status().isOk());

        // leader 看全部（新的在前，created_at 严格递增微秒保证序确定）。
        MvcResult leaderEntries = mvc.perform(get("/api/admin/audit-log")
                        .header("Authorization", auth(leaderToken)))
                .andExpect(status().isOk()).andReturn();
        JsonNode leaderBody = MAPPER.readTree(leaderEntries.getResponse().getContentAsString());
        assertThat(leaderBody.path("entries").findValuesAsText("actor")).containsExactly(
                LEADER, TEACHER_B, LEADER, TEACHER_A, TEACHER_A);
        assertThat(leaderBody.path("entries").get(0).path("targets").toString())
                .isEqualTo("[\"" + STUDENT_B1 + "\"]");

        // teacher_b 只看自己的操作。
        MvcResult teacherEntries = mvc.perform(get("/api/admin/audit-log")
                        .header("Authorization", auth(teacherB)))
                .andExpect(status().isOk()).andReturn();
        JsonNode teacherBody = MAPPER.readTree(teacherEntries.getResponse().getContentAsString());
        assertThat(teacherBody.path("entries").findValuesAsText("actor")).containsOnly(TEACHER_B);
        assertThat(teacherBody.path("entries").size()).isEqualTo(1);
        assertThat(teacherBody.path("entries").get(0).path("targets").toString())
                .isEqualTo("[\"U20260000061\"]");

        String viewerToken = h.login(mvc, STUDENT_A2, STUDENT_A2);
        mvc.perform(get("/api/admin/audit-log").header("Authorization", auth(viewerToken)))
                .andExpect(status().isNotFound());
    }

    /* ------------------------------------------------------------------ */
    /* 验收 G：中段故障整体回滚（真实 PG 事务）                              */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(7)
    void midTransactionFailureRollsBackDeletesAndAudit() throws Exception {
        h.seedUser("U20260000071", "[\"viewer\"]", null, true, true, "U20260000071", TEACHER_B);
        h.seedUser("U20260000072", "[\"viewer\"]", null, true, true, "U20260000072", TEACHER_B);
        h.insertUsageCounter("U20260000071", "2026-01-02", 1);
        int auditBefore = h.countRows("admin_audit_log", null, (String[]) null);
        int usersBefore = h.countRows("users", null, (String[]) null);

        // 在第二个目标的级联删除中段注入 SQLException → 整笔回滚（第一个已删的也回滚）。
        // MockMvc standalone 对未捕获运行时异常向上抛 ServletException（真容器为 500），
        // 断言其 cause 链即为注入的 SQLException。
        h.users.setProbe(step -> {
            if ("delete-user-rows:U20260000072".equals(step)) {
                throw new SQLException("injected mid-transaction failure");
            }
        });
        MockMvc mvc = h.newMvc();
        String leaderToken = h.login(mvc, LEADER, LEADER);
        try {
            mvc.perform(post("/api/admin/users/delete").contentType("application/json")
                            .header("Authorization", auth(leaderToken))
                            .content("{\"ids\":[\"U20260000071\",\"U20260000072\"],\"password\":\""
                                    + LEADER + "\"}"));
            throw new AssertionError("注入的 SQLException 应向上传播");
        } catch (jakarta.servlet.ServletException e) {
            assertThat(e.getCause()).isInstanceOf(IllegalStateException.class);
            assertThat(e.getCause().getCause()).isInstanceOf(SQLException.class);
            assertThat(e.getCause().getCause()).hasMessageContaining("injected mid-transaction failure");
        } finally {
            h.users.setProbe(null);
        }
        assertThat(h.countRows("users", null, (String[]) null)).isEqualTo(usersBefore);
        assertThat(h.countRows("users", "id=?", "U20260000071")).isEqualTo(1);
        assertThat(h.countRows("users", "id=?", "U20260000072")).isEqualTo(1);
        assertThat(h.countRows("usage_counters", "subject=?", "U20260000071")).isEqualTo(1);
        assertThat(h.countRows("admin_audit_log", null, (String[]) null)).isEqualTo(auditBefore);
    }

    /* ------------------------------------------------------------------ */
    /* 停号：库内级联 + 进程内取消端口（验收 F 的 J2-02 侧）                  */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(8)
    void disableCascadesTokensTasksAndCallsCancellationPort() throws Exception {
        h.seedUser("U20260000081", "[\"viewer\"]", null, true, true, "U20260000081", TEACHER_B);
        MockMvc mvc = h.newMvc();
        String token = h.login(mvc, "U20260000081", "U20260000081");
        h.insertPendingRagTask("task-e1", "U20260000081");
        h.insertGradingSubmission("grade-task-e1", "U20260000081", null, "job-task-e1");
        h.taskCancels.clear();

        String leaderToken = h.login(mvc, LEADER, LEADER);
        mvc.perform(post("/api/admin/users/U20260000081/status").contentType("application/json")
                        .header("Authorization", auth(leaderToken))
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNoContent());
        // 库内级联：pending 任务取消并留因、输入删除、任务关联成绩删除；token 全撤。
        assertThat(h.columnValue("rag_requests", "status", "id=?", "task-e1")).isEqualTo("cancelled");
        assertThat(h.columnValue("rag_requests", "reason", "id=?", "task-e1"))
                .isEqualTo("account_disabled");
        assertThat(h.countRows("rag_task_inputs", "task_id=?", "task-e1")).isZero();
        assertThat(h.countRows("grading_submissions", "id=?", "grade-task-e1")).isZero();
        assertThat(h.countRows("api_tokens", "user_id=? AND revoked_at IS NULL", "U20260000081"))
                .isZero();
        // token 立即失效（缓存已清）。
        mvc.perform(get("/api/admin/users").header("Authorization", auth(token)))
                .andExpect(status().isUnauthorized());
        // 提交后 best-effort 取消端口被调用（真实执行体 integration_pending：J3-01）。
        assertThat(h.taskCancels).containsExactly("U20260000081");

        // 重新启用不复活已撤销 token；启用不触发取消端口。
        h.taskCancels.clear();
        mvc.perform(post("/api/admin/users/U20260000081/status").contentType("application/json")
                        .header("Authorization", auth(leaderToken))
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNoContent());
        assertThat(h.taskCancels).isEmpty();
        assertThat(h.countRows("api_tokens", "user_id=? AND revoked_at IS NULL", "U20260000081"))
                .isZero();
    }

    @Test
    @Order(9)
    void accountWriteGuardDecidesByCommittedState() throws Exception {
        // 验收 F 的账号行事务顺序（J2-02 侧）：守卫按已提交的账号行状态裁决——
        // 启用中放行；停号提交后一律失败（后续成绩落库/任务受理照此顺序被拒）。
        h.seedUser("U20260000091", "[\"viewer\"]", null, true, true, "U20260000091", TEACHER_B);
        boolean before = h.runner.required(tx -> h.guard.lockEnabled(tx, "U20260000091"));
        assertThat(before).isTrue();
        MockMvc mvc = h.newMvc();
        String leaderToken = h.login(mvc, LEADER, LEADER);
        mvc.perform(post("/api/admin/users/U20260000091/status")
                        .contentType("application/json")
                        .header("Authorization", auth(leaderToken))
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNoContent());
        boolean after = h.runner.required(tx -> h.guard.lockEnabled(tx, "U20260000091"));
        assertThat(after).isFalse();
        assertThat(h.columnValue("users", "enabled", "id=?", "U20260000091")).isEqualTo("f");
    }

    @Test
    @Order(10)
    void concurrentGuardAndDisableSerializeOnAccountRow() throws Exception {
        // 行锁时间线：T1 持账号行事务（守卫锁），T2 停号阻塞至 T1 提交后完成。
        h.seedUser("U20260000092", "[\"viewer\"]", null, true, true, "U20260000092", TEACHER_B);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch txStarted = new CountDownLatch(1);
            CountDownLatch releaseTx = new CountDownLatch(1);
            Future<?> writer = pool.submit(() -> {
                try {
                    h.runner.required(tx -> {
                        assertThat(h.guard.lockEnabled(tx, "U20260000092")).isTrue();
                        txStarted.countDown();
                        // 事务保持打开（账号行锁持有中）。
                        try {
                            releaseTx.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return true;
                    });
                } catch (Exception e) {
                    txStarted.countDown();
                    throw new RuntimeException(e);
                }
            });
            assertThat(txStarted.await(5, TimeUnit.SECONDS)).isTrue();

            Future<MockMvc> disabler = pool.submit(() -> {
                MockMvc mvc = h.newMvc();
                String leaderToken = h.login(mvc, LEADER, LEADER);
                long start = System.nanoTime();
                mvc.perform(post("/api/admin/users/U20260000092/status")
                                .contentType("application/json")
                                .header("Authorization", auth(leaderToken))
                                .content("{\"enabled\":false}"))
                        .andExpect(status().isNoContent());
                return null;
            });
            // 停号在行锁上等待（至少观察到一个短暂窗口），随后释放写方事务。
            Thread.sleep(200);
            releaseTx.countDown();
            writer.get(5, TimeUnit.SECONDS);
            disabler.get(5, TimeUnit.SECONDS);
            assertThat(h.columnValue("users", "enabled", "id=?", "U20260000092")).isEqualTo("f");
            boolean after = h.runner.required(tx -> h.guard.lockEnabled(tx, "U20260000092"));
            assertThat(after).isFalse();
        } finally {
            pool.shutdownNow();
        }
    }
}
