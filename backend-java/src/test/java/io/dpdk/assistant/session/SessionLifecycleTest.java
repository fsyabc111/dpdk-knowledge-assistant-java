package io.dpdk.assistant.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.session.SessionId;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * J2-05 会话生命周期（验收 A/B/G 子集）：golden DELETE 三例 + 维护端点两例
 * （鉴权 503/403、dry-run/apply 分离）、删除级联（成绩保留 session_id 置 NULL）、
 * ensureOwned 归属/新建、discardEmpty 空会话补偿、appendMessage 参与调用方事务、
 * SessionPort 适配（appendUser 契约缺口指向登记）。
 *
 * golden 映射：identity:3→sess_hist、identity:40→sess_del（evidence/J2-05 登记）。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class SessionLifecycleTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Instant T0 = J205TestHarness.T0;

    // golden 命名空间（student_a=U20270000011）。
    private static final String STUDENT_A = "U20270000011";
    private static final String STUDENT_B = "U20270000021";
    private static final String TEACHER_A = "U20270000002";
    private static final String LEADER = "U20270000001";

    private static final String SESS_HIST = "sess_goldenhist1";
    private static final String SESS_DEL = "sess_goldendel01";

    private static J205TestHarness h;
    private static J205TestHarness local;

    @BeforeAll
    static void setup() throws Exception {
        h = J205TestHarness.accountMode("life");
        h.seedUser(LEADER, "[\"leader\"]", 10000, null);
        h.seedUser(TEACHER_A, "[\"admin\"]", 10000, LEADER);
        h.seedUser(STUDENT_A, "[\"viewer\"]", 10000, TEACHER_A);
        h.seedUser(STUDENT_B, "[\"viewer\"]", 10000, null);
        // golden 初始态：恰 2 个会话（session-storage::success 的 session_count=2）。
        h.insertSession(SESS_HIST, STUDENT_A, "合成历史", T0.plusNanos(2_000), T0.plusNanos(11_000));
        h.insertSession(SESS_DEL, STUDENT_A, "DNS 做什么", T0.plusNanos(3_000), T0.plusNanos(94_000));
        h.insertMessage("msg_hist_01", SESS_HIST, "assistant", "历史固定回答", "{}",
                T0.plusNanos(10_000));
        h.insertCitation("cit_hist_01", "msg_hist_01", "p0-history", "legacy", 1.0, 1);
        h.insertMessage("msg_del_01", SESS_DEL, "user", "DNS 做什么问题", "{}",
                T0.plusNanos(20_000));
        h.insertMessage("msg_del_02", SESS_DEL, "assistant", "DNS 固定回答", "{}",
                T0.plusNanos(21_000));
        h.insertCitation("cit_del_01", "msg_del_02", "p0-history", "legacy", 0.9, 1);
        h.insertGradingSubmission("gra_del_01", STUDENT_A, SESS_DEL, "dns", 80, 8, 10,
                T0.plusNanos(30_000));
        h.insertKnowledgeChunk("doc_hist", "历史DNS", "p0-history", "synthetic/history.md",
                "历史", "历史固定来源", "{}");

        local = J205TestHarness.localMode("lifelocal");
        local.insertSession("sess_localown1", STUDENT_B, "本地", T0, T0);
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
    /* golden                                                              */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(1)
    void goldenMaintenanceThenDeleteFlow() throws Exception {
        JsonNode golden = J205TestHarness.loadGolden();
        var mvc = h.newMvc();
        String studentAToken = h.login(mvc, STUDENT_A);

        // GET /api/internal/session-storage::success（storage_bytes 为物理大小，
        // 归一化断言 >0；session_count 精确 =2；backend=postgres）。
        JsonNode storage = body(mvc, get("/api/internal/session-storage")
                .header("x-maintenance-key", J205TestHarness.MAINT_KEY));
        JsonNode expected = observation(golden, "GET /api/internal/session-storage::success");
        assertThat(storage.get("session_count").asInt())
                .isEqualTo(expected.get("body").get("session_count").asInt());
        assertThat(storage.get("database_backend").asText()).isEqualTo("postgres");
        assertThat(storage.get("storage_bytes").asLong()).isPositive();

        // POST /api/internal/purge-sessions::success（dry-run；cutoff 微秒对齐 oracle）。
        h.clock.setWall(T0.plusNanos(32_000));
        try {
            JsonNode purge = body(mvc, post("/api/internal/purge-sessions")
                    .queryParam("dry_run", "true")
                    .header("x-maintenance-key", J205TestHarness.MAINT_KEY));
            assertThat(purge).isEqualTo(observation(golden,
                    "POST /api/internal/purge-sessions::success").get("body"));
        } finally {
            h.clock.setWall(T0);
        }

        // POST /api/internal/purge-sessions::unauthorized（无 key → 403）与
        // GET session-storage::unauthorized。
        mvc.perform(post("/api/internal/purge-sessions"))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().json("{\"detail\":\"forbidden\"}"));
        mvc.perform(get("/api/internal/session-storage"))
                .andExpect(status().isForbidden());

        // purge::invalid-input（retention_days=0 → 422，golden detail 数组逐字段）。
        mvc.perform(post("/api/internal/purge-sessions")
                        .queryParam("retention_days", "0")
                        .header("x-maintenance-key", J205TestHarness.MAINT_KEY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(result -> assertThat(MAPPER.readTree(
                                result.getResponse().getContentAsString()))
                        .isEqualTo(observation(golden,
                                "POST /api/internal/purge-sessions::invalid-input").get("body")));

        // DELETE::unauthorized（401 detail）。
        mvc.perform(delete("/api/chat/sessions/" + SESS_HIST))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(MAPPER.readTree(
                                result.getResponse().getContentAsString()))
                        .isEqualTo(observation(golden,
                                "DELETE /api/chat/sessions/{session_id}::unauthorized").get("body")));

        // DELETE::invalid-resource（missing → 404）。
        mvc.perform(delete("/api/chat/sessions/p0-missing")
                        .header("Authorization", bearer(studentAToken)))
                .andExpect(status().isNotFound())
                .andExpect(result -> assertThat(MAPPER.readTree(
                                result.getResponse().getContentAsString()))
                        .isEqualTo(observation(golden,
                                "DELETE /api/chat/sessions/{session_id}::invalid-resource")
                                .get("body")));

        // DELETE::success（204 无体）；级联：成绩保留、session_id 置 NULL。
        mvc.perform(delete("/api/chat/sessions/" + SESS_DEL)
                        .header("Authorization", bearer(studentAToken)))
                .andExpect(status().isNoContent());
        assertThat(h.countRows("assistant_sessions", "id=?", SESS_DEL)).isZero();
        assertThat(h.countRows("assistant_messages", "session_id=?", SESS_DEL)).isZero();
        assertThat(h.countRows("assistant_citations", "message_id=?", "msg_del_02")).isZero();
        assertThat(h.countRows("grading_submissions", "id=?", "gra_del_01")).isEqualTo(1);
        assertThat(h.columnValue("grading_submissions", "session_id", "id=?", "gra_del_01"))
                .isNull();
    }

    /* ------------------------------------------------------------------ */
    /* 删除行为                                                            */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(2)
    void foreignAndMissingDeleteAreUniform404AndForeignUntouched() throws Exception {
        var mvc = h.newMvc();
        String tokenB = h.login(mvc, STUDENT_B);
        h.insertSession("sess_bforeign", STUDENT_B, "B 的会话", T0, T0);

        mvc.perform(delete("/api/chat/sessions/sess_bforeign")
                        .header("Authorization", bearer(tokenB)))
                .andExpect(status().isNoContent());

        // A 删 B 的会话 → 404，且 B 的会话不受影响。
        h.insertSession("sess_bkeep", STUDENT_B, "B 保留", T0, T0);
        String tokenA = h.login(mvc, STUDENT_A);
        mvc.perform(delete("/api/chat/sessions/sess_bkeep")
                        .header("Authorization", bearer(tokenA)))
                .andExpect(status().isNotFound());
        assertThat(h.countRows("assistant_sessions", "id=?", "sess_bkeep")).isEqualTo(1);
    }

    @Test
    @Order(3)
    void localModeDeleteSkipsOwnership() throws Exception {
        var mvc = local.newMvc();
        // local 模式无认证依赖（Principal=local/admin），删除不做归属检查（源口径）。
        mvc.perform(delete("/api/chat/sessions/sess_localown1"))
                .andExpect(status().isNoContent());
        assertThat(local.countRows("assistant_sessions", "id=?", "sess_localown1")).isZero();
    }

    /* ------------------------------------------------------------------ */
    /* 维护：503 未配置 / dry-run 与 apply 分离                            */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(4)
    void maintenanceKeyNotConfiguredAnswers503() throws Exception {
        h.setMaintenance(new MaintenanceSettings("", 30));
        try {
            var mvc = h.newMvc();
            mvc.perform(get("/api/internal/session-storage")
                            .header("x-maintenance-key", J205TestHarness.MAINT_KEY))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .content().json(
                                    "{\"detail\":\"maintenance endpoint is not configured\"}"));
            // 未配置时错误 key 也一样 503（配置缺失优先）。
            mvc.perform(get("/api/internal/session-storage"))
                    .andExpect(status().isServiceUnavailable());
        } finally {
            h.setMaintenance(new MaintenanceSettings(J205TestHarness.MAINT_KEY, 30));
        }
    }

    @Test
    @Order(5)
    void purgeDryRunCountsThenApplyDeletesOnlyExpired() throws Exception {
        var mvc = h.newMvc();
        Instant old = T0.minus(Duration.ofDays(40));
        h.insertSession("sess_old01", STUDENT_A, "旧会话", old, old);
        h.insertMessage("msg_old_01", "sess_old01", "user", "旧问题", "{}", old);
        h.insertMessage("msg_old_02", "sess_old01", "assistant", "旧回答", "{}", old);
        h.insertCitation("cit_old_01", "msg_old_02", "p0-history", "legacy", 0.5, 1);
        int before = h.countRows("assistant_sessions", null);

        // dry_run（默认 true）：只计数。
        JsonNode dry = body(mvc, post("/api/internal/purge-sessions")
                .header("x-maintenance-key", J205TestHarness.MAINT_KEY));
        assertThat(dry.get("dry_run").asBoolean()).isTrue();
        assertThat(dry.get("retention_days").asInt()).isEqualTo(30);
        assertThat(dry.get("sessions").asInt()).isEqualTo(1);
        assertThat(dry.get("messages").asInt()).isEqualTo(2);
        assertThat(dry.get("citations").asInt()).isEqualTo(1);
        assertThat(h.countRows("assistant_sessions", null)).isEqualTo(before);

        // apply：只删过期（旧会话三表清空，近期会话保留）。
        JsonNode apply = body(mvc, post("/api/internal/purge-sessions")
                .queryParam("dry_run", "false")
                .header("x-maintenance-key", J205TestHarness.MAINT_KEY));
        assertThat(apply.get("dry_run").asBoolean()).isFalse();
        assertThat(apply.get("sessions").asInt()).isEqualTo(1);
        assertThat(h.countRows("assistant_sessions", "id=?", "sess_old01")).isZero();
        assertThat(h.countRows("assistant_messages", "session_id=?", "sess_old01")).isZero();
        assertThat(h.countRows("assistant_citations", "message_id=?", "msg_old_02")).isZero();
        assertThat(h.countRows("assistant_sessions", null)).isEqualTo(before - 1);
        // retention_days 覆盖默认。
        JsonNode narrow = body(mvc, post("/api/internal/purge-sessions")
                .queryParam("retention_days", "365")
                .header("x-maintenance-key", J205TestHarness.MAINT_KEY));
        assertThat(narrow.get("retention_days").asInt()).isEqualTo(365);
        assertThat(narrow.get("sessions").asInt()).isEqualTo(0);
    }

    /* ------------------------------------------------------------------ */
    /* ensureOwned / discardEmpty / 事务参与                               */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(6)
    void ensureOwnedReusesOwnIdAndCreatesFreshForForeignOrMissing() throws Exception {
        SessionId created = h.sessions.ensureOwned(Optional.empty(), "问题", STUDENT_A);
        // 自己的既有 id：touch 后原 id 复用（受理前独立提交）。
        SessionId own = h.sessions.ensureOwned(Optional.of(created.value()), "问题", STUDENT_A);
        assertThat(own.value()).isEqualTo(created.value());
        // 他人 id 不挂载：返回新 id（不附加到他人历史）。
        SessionId fresh = h.sessions.ensureOwned(Optional.of("sess_bkeep"), "问题", STUDENT_A);
        assertThat(fresh.value()).isNotEqualTo("sess_bkeep").startsWith("sess_");
        // 缺失/未知 id 同样新建（不采用请求 id）；title 截 80。
        SessionId titled = h.sessions.ensureOwned(Optional.of("sess_unknown_id"), "t".repeat(120),
                STUDENT_A);
        assertThat(titled.value()).startsWith("sess_").isNotEqualTo("sess_unknown_id");
        assertThat(h.columnValue("assistant_sessions", "title", "id=?", titled.value()).length())
                .isEqualTo(80);
    }

    @Test
    @Order(7)
    void discardEmptyOnlyRemovesOwnEmptySession() throws Exception {
        h.sessions.ensureOwned(Optional.empty(), "空", STUDENT_A);
        String emptyId = h.columnValue("assistant_sessions", "id", "title=?", "空");
        String withMsgId = h.sessions.ensureOwned(Optional.empty(), "有消息", STUDENT_A).value();
        String withReqId = h.sessions.ensureOwned(Optional.empty(), "有任务", STUDENT_A).value();
        h.runner.required(tx -> {
            try {
                h.sessions.appendMessage(tx, withMsgId, "user", "q", null);
                return null;
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        // rag_requests 存在的会话不算空。
        try (var conn = h.ds.getConnection();
             var ps = conn.prepareStatement(
                     "INSERT INTO rag_requests(id, question, status, created_at, updated_at, "
                             + "session_id) VALUES ('req_01','q','pending',now(),now(),?)")) {
            ps.setString(1, withReqId);
            ps.executeUpdate();
        }

        h.sessions.discardEmpty(emptyId, STUDENT_A);
        assertThat(h.countRows("assistant_sessions", "id=?", emptyId)).isZero();
        h.sessions.discardEmpty(withMsgId, STUDENT_A);
        assertThat(h.countRows("assistant_sessions", "id=?", withMsgId)).isEqualTo(1);
        h.sessions.discardEmpty(withReqId, STUDENT_A);
        assertThat(h.countRows("assistant_sessions", "id=?", withReqId)).isEqualTo(1);
        // 他人主体不能清理。
        String otherEmpty = h.sessions.ensureOwned(Optional.empty(), "他人的空", STUDENT_A).value();
        h.sessions.discardEmpty(otherEmpty, STUDENT_B);
        assertThat(h.countRows("assistant_sessions", "id=?", otherEmpty)).isEqualTo(1);
    }

    @Test
    @Order(8)
    void appendMessageAndTouchParticipateInCallerTransactionRollback() throws Exception {
        SessionId session = h.sessions.ensureOwned(Optional.empty(), "事务", STUDENT_A);
        String before = h.columnValue("assistant_sessions", "updated_at", "id=?", session.value());
        try {
            h.runner.required(tx -> {
                try {
                    h.sessions.appendMessage(tx, session.value(), "user", "将回滚", null);
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException(e);
                }
                h.sessionPort.touch(tx, session.value(),
                        io.dpdk.assistant.platform.time.SystemClockAdapter.pythonIso(
                                T0.plusNanos(500_000)));
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            assertThat(expected.getMessage()).isEqualTo("boom");
        }
        assertThat(h.countRows("assistant_messages", "session_id=?", session.value())).isZero();
        assertThat(h.columnValue("assistant_sessions", "updated_at", "id=?", session.value()))
                .isEqualTo(before);

        h.runner.required(tx -> {
            try {
                return h.sessions.appendMessage(tx, session.value(), "user", "提交", null);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(h.countRows("assistant_messages", "session_id=?", session.value())).isEqualTo(1);
    }

    @Test
    @Order(9)
    void sessionPortAppendUserDelegatesIntoTheCallerTransaction() throws Exception {
        SessionId session = h.sessions.ensureOwned(Optional.empty(), "P3端口接线", STUDENT_A);
        h.runner.required(tx -> {
            h.sessionPort.appendUser(tx, new io.dpdk.assistant.contracts.session.MessageInput(
                    "端口已接线", session.value(), "user", java.util.Map.of("task_id", "port-test")));
            return null;
        });
        assertThat(h.countRows("assistant_messages", "session_id=?", session.value())).isEqualTo(1);
        assertThat(h.columnValue("assistant_messages", "content", "session_id=?", session.value()))
                .isEqualTo("端口已接线");
    }

    /* ------------------------------------------------------------------ */

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static JsonNode body(org.springframework.test.web.servlet.MockMvc mvc,
                                 org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder)
            throws Exception {
        var result = mvc.perform(builder).andExpect(status().isOk()).andReturn();
        return MAPPER.readTree(result.getResponse().getContentAsString());
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
