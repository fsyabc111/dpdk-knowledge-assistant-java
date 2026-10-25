package io.dpdk.assistant.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.session.SessionId;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * J2-05 会话查询契约（验收 A/B/C）：golden 列表/历史/越权/参数校验 8 例 +
 * 分页/truncated/取消消息/metadata 字段、历史引用顺序与 li_/legacy 双路径回读、
 * release 首次绑定 CAS（并发同赢家、跨实例不漂移、删除重建重新绑定）。
 *
 * golden 映射（evidence/J2-05 登记）：identity:50→sess_dns2、40→sess_dns、
 * 22→sess_explain、6→sess_unanswered、3→sess_hist、2→msg_hist_01。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(OrderAnnotation.class)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class SessionQueryContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Instant T0 = J205TestHarness.T0;

    private static final String STUDENT_A = "U20270000011";
    private static final String STUDENT_B = "U20270000021";
    private static final String TEACHER_A = "U20270000002";
    private static final String LEADER = "U20270000001";

    private static final String SESS_HIST = "sess_qhist001";

    private static J205TestHarness h;

    @BeforeAll
    static void setup() throws Exception {
        h = J205TestHarness.accountMode("query");
        h.seedUser(LEADER, "[\"leader\"]", 10000, null);
        h.seedUser(TEACHER_A, "[\"admin\"]", 10000, LEADER);
        h.seedUser(STUDENT_A, "[\"viewer\"]", 10000, TEACHER_A);
        h.seedUser(STUDENT_B, "[\"viewer\"]", 10000, null);

        // golden 列表五会话（student_a，updated_at 微秒对齐 oracle 序列）。
        h.insertSession("sess_qdns2xx01", STUDENT_A, "DNS 解析什么",
                T0.plusNanos(60_000), T0.plusNanos(103_000));
        h.insertSession("sess_qdnsxxx01", STUDENT_A, "DNS 做什么",
                T0.plusNanos(50_000), T0.plusNanos(94_000));
        h.insertSession("sess_qexpl001", STUDENT_A, "作业讲解·合成DNS作业修订·dns",
                T0.plusNanos(40_000), T0.plusNanos(69_000));
        h.insertSession("sess_qunans001", STUDENT_A, "合成未解答",
                T0.plusNanos(30_000), T0.plusNanos(19_000));
        h.insertSession(SESS_HIST, STUDENT_A, "合成历史",
                T0.plusNanos(2_000), T0.plusNanos(11_000));

        // golden 历史：单条 assistant 消息 + p0-history 引用。
        h.insertMessage("msg_hist_01", SESS_HIST, "assistant", "历史固定回答", "{}",
                T0.plusNanos(10_000));
        h.insertCitation("cit_hist_01", "msg_hist_01", "p0-history", "legacy", 1.0, 1);
        h.insertKnowledgeChunk("doc_hist", "历史DNS", "p0-history", "synthetic/history.md",
                "历史", "历史固定来源", "{}");

        // 行为分支：li_ 引用、缺失来源、取消消息、metadata 字段、rank 顺序。
        h.insertSession("sess_qbehav001", STUDENT_B, "行为", T0, T0.plusNanos(5_000));
        h.insertMessage("msg_b_01", "sess_qbehav001", "user", "问题一", "{}",
                T0.plusNanos(1_000));
        h.insertMessage("msg_b_02", "sess_qbehav001", "assistant", "回答一",
                "{\"confidence\":\"high\",\"answer_status\":\"ok\",\"answer_reason\":\"done\","
                        + "\"request_id\":\"req-1\",\"task_id\":\"task-1\",\"issue_id\":\"iss-1\"}",
                T0.plusNanos(2_000));
        h.insertMessage("msg_b_03", "sess_qbehav001", "assistant", "回答二",
                "{\"confidence\":\"low\",\"answer_status\":\"cancelled\","
                        + "\"stream_status\":\"cancelled\"}",
                T0.plusNanos(3_000));
        // rank 乱序写入：回读必须保持 rank 升序（不按当前检索重排）。
        h.insertCitation("cit_b_02", "msg_b_02", "li_seednode01", "llama_index", 0.8, 2);
        h.insertCitation("cit_b_01", "msg_b_02", "p0-history", "legacy", 0.9, 1);
        h.insertCitation("cit_b_03", "msg_b_02", "chunk_missing1", "legacy", 0.7, 3);
        h.insertLiNode("li_seednode01", "li 固定内容", "synthetic/li.md", "LI 文档", "li 小节");

        // B 的会话（越权 404 对照）。
        h.insertSession("sess_qforeign1", STUDENT_B, "B 私有", T0, T0);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    /* ------------------------------------------------------------------ */
    /* golden                                                              */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(1)
    void goldenSessionList() throws Exception {
        JsonNode golden = J205TestHarness.loadGolden();
        var mvc = h.newMvc();
        String token = h.login(mvc, STUDENT_A);
        JsonNode actual = MAPPER.readTree(mvc.perform(get("/api/chat/sessions")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode expected = substitute(observation(golden, "GET /api/chat/sessions::success")
                .get("body"));
        assertThat(actual).isEqualTo(expected);

        // unauthorized / invalid-input。
        mvc.perform(get("/api/chat/sessions"))
                .andExpect(status().isUnauthorized())
                .andExpect(r -> assertThat(MAPPER.readTree(r.getResponse().getContentAsString()))
                        .isEqualTo(observation(golden, "GET /api/chat/sessions::unauthorized")
                                .get("body")));
        mvc.perform(get("/api/chat/sessions").queryParam("limit", "0")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(r -> assertThat(MAPPER.readTree(r.getResponse().getContentAsString()))
                        .isEqualTo(observation(golden, "GET /api/chat/sessions::invalid-input")
                                .get("body")));
    }

    @Test
    @Order(2)
    void goldenSessionHistoryAndGuards() throws Exception {
        JsonNode golden = J205TestHarness.loadGolden();
        var mvc = h.newMvc();
        String tokenA = h.login(mvc, STUDENT_A);
        String tokenB = h.login(mvc, STUDENT_B);

        JsonNode actual = MAPPER.readTree(mvc.perform(get("/api/chat/sessions/" + SESS_HIST)
                        .header("Authorization", bearer(tokenA)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode expected = substitute(observation(golden,
                "GET /api/chat/sessions/{session_id}::success").get("body"));
        assertThat(actual).isEqualTo(expected);

        // foreign-tree：B 读 A 的会话 → 与缺失同形 404。
        mvc.perform(get("/api/chat/sessions/" + SESS_HIST)
                        .header("Authorization", bearer(tokenB)))
                .andExpect(status().isNotFound())
                .andExpect(r -> assertThat(MAPPER.readTree(r.getResponse().getContentAsString()))
                        .isEqualTo(observation(golden,
                                "GET /api/chat/sessions/{session_id}::foreign-tree").get("body")));
        // 不存在的会话统一 404（session not found）。
        mvc.perform(get("/api/chat/sessions/sess_missing_xx")
                        .header("Authorization", bearer(tokenA)))
                .andExpect(status().isNotFound());
        // unauthorized。
        mvc.perform(get("/api/chat/sessions/" + SESS_HIST))
                .andExpect(status().isUnauthorized())
                .andExpect(r -> assertThat(MAPPER.readTree(r.getResponse().getContentAsString()))
                        .isEqualTo(observation(golden,
                                "GET /api/chat/sessions/{session_id}::unauthorized").get("body")));
        // invalid-input：limit=0 → 422（golden detail 数组）。
        mvc.perform(get("/api/chat/sessions/" + SESS_HIST).queryParam("limit", "0")
                        .header("Authorization", bearer(tokenA)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(r -> assertThat(MAPPER.readTree(r.getResponse().getContentAsString()))
                        .isEqualTo(observation(golden,
                                "GET /api/chat/sessions/{session_id}::invalid-input").get("body")));
    }

    /* ------------------------------------------------------------------ */
    /* 行为：分页/truncated/取消消息/metadata/引用路径                      */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(3)
    void historyMetadataCitationsAndRankOrderPreserved() throws Exception {
        var mvc = h.newMvc();
        String token = h.login(mvc, STUDENT_B);
        JsonNode body = MAPPER.readTree(mvc.perform(get("/api/chat/sessions/sess_qbehav001")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(body.get("truncated").asBoolean()).isFalse();
        assertThat(body.get("messages").size()).isEqualTo(3);

        JsonNode answer = body.get("messages").get(1);
        assertThat(answer.get("confidence").asText()).isEqualTo("high");
        assertThat(answer.get("answer_status").asText()).isEqualTo("ok");
        assertThat(answer.get("answer_reason").asText()).isEqualTo("done");
        assertThat(answer.get("request_id").asText()).isEqualTo("req-1");
        assertThat(answer.get("task_id").asText()).isEqualTo("task-1");
        assertThat(answer.get("issue_id").asText()).isEqualTo("iss-1");
        assertThat(answer.get("citations").size()).isEqualTo(3);
        // rank 升序保持（写入时乱序）：legacy(1) → li_(2) → missing(3)。
        assertThat(answer.get("citations").get(0).get("chunk_id").asText())
                .isEqualTo("p0-history");
        assertThat(answer.get("citations").get(0).get("title").asText()).isEqualTo("历史DNS");
        assertThat(answer.get("citations").get(1).get("chunk_id").asText())
                .isEqualTo("li_seednode01");
        assertThat(answer.get("citations").get(1).get("section").asText()).isEqualTo("li 小节");
        // 缺失来源：占位空字段，不报错、不重排。
        assertThat(answer.get("citations").get(2).get("chunk_id").asText())
                .isEqualTo("chunk_missing1");
        assertThat(answer.get("citations").get(2).get("source_path").asText()).isEmpty();
        assertThat(answer.get("citations").get(2).get("title").asText()).isEmpty();

        // 取消消息在历史展示路径保留（用户可见终态）。
        JsonNode cancelled = body.get("messages").get(2);
        assertThat(cancelled.get("answer_status").asText()).isEqualTo("cancelled");
        // 会话记忆口径（getMessages false）过滤取消占位。
        assertThat(h.sessions.getMessages("sess_qbehav001", false)).hasSize(2);
    }

    @Test
    @Order(4)
    void historyTruncationKeepsLastNAndListPagination() throws Exception {
        var mvc = h.newMvc();
        String token = h.login(mvc, STUDENT_A);
        JsonNode truncated = MAPPER.readTree(mvc.perform(
                        get("/api/chat/sessions/sess_qbehav001").queryParam("limit", "2")
                                .header("Authorization", bearer(h.login(mvc, STUDENT_B))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(truncated.get("truncated").asBoolean()).isTrue();
        assertThat(truncated.get("messages").size()).isEqualTo(2);
        assertThat(truncated.get("messages").get(0).get("id").asText()).isEqualTo("msg_b_02");
        assertThat(truncated.get("messages").get(1).get("id").asText()).isEqualTo("msg_b_03");

        // 列表分页：limit=2 offset=0 → 前两行（最新优先）。
        JsonNode page = MAPPER.readTree(mvc.perform(get("/api/chat/sessions")
                        .queryParam("limit", "2").queryParam("offset", "0")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page.get("sessions").size()).isEqualTo(2);
        assertThat(page.get("sessions").get(0).get("id").asText()).isEqualTo("sess_qdns2xx01");
        assertThat(page.get("sessions").get(1).get("id").asText()).isEqualTo("sess_qdnsxxx01");
        JsonNode page2 = MAPPER.readTree(mvc.perform(get("/api/chat/sessions")
                        .queryParam("limit", "2").queryParam("offset", "2")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page2.get("sessions").get(0).get("id").asText()).isEqualTo("sess_qexpl001");
        // offset 越界为空但不报错。
        JsonNode page9 = MAPPER.readTree(mvc.perform(get("/api/chat/sessions")
                        .queryParam("offset", "50")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page9.get("sessions").size()).isZero();
    }

    /* ------------------------------------------------------------------ */
    /* release 首次绑定 CAS                                                */
    /* ------------------------------------------------------------------ */

    @Test
    @Order(5)
    void releaseBindsOnceAndNeverDriftsAcrossInstancesAndRecreation() throws Exception {
        h.publishRelease("r1");
        SessionId session = h.sessions.ensureOwned(java.util.Optional.empty(), "绑定", STUDENT_A);
        assertThat(h.sessions.resolveRelease(session.value())).contains("r1");

        // 跨请求/重启（新实例）不漂移：active 变 r2 仍读 r1。
        h.publishRelease("r2");
        SessionService freshInstance = new SessionService(h.runner, h.clock);
        assertThat(freshInstance.resolveRelease(session.value())).contains("r1");

        // 删除重建（请求同 id）→ 新行重新绑定当前 active r2。
        h.sessions.deleteSession(session.value(), STUDENT_A);
        SessionId recreated = h.sessions.ensureOwned(java.util.Optional.of(session.value()),
                "重建", STUDENT_A);
        assertThat(recreated.value()).isNotEqualTo(session.value());
        assertThat(h.sessions.resolveRelease(recreated.value())).contains("r2");

        // 无活动发布：未绑定会话返回 empty（等待首次发布）。
        h.clearActiveRelease();
        SessionId waiting = h.sessions.ensureOwned(java.util.Optional.empty(), "等待", STUDENT_A);
        assertThat(h.sessions.resolveRelease(waiting.value())).isEmpty();
    }

    @Test
    @Order(6)
    void parallelFirstResolutionUsesCasWinner() throws Exception {
        h.publishRelease("r1");
        SessionId session = h.sessions.ensureOwned(java.util.Optional.empty(), "并发绑定",
                STUDENT_A);
        ThreadLocal<String> candidate = new ThreadLocal<>();
        h.setActiveReleaseOverride(candidate::get);
        CyclicBarrier barrier = new CyclicBarrier(2);
        List<String> results = new java.util.concurrent.CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(2);
        Thread t1 = new Thread(() -> {
            candidate.set("rX");
            await(barrier);
            results.add(h.sessions.resolveRelease(session.value()).orElse(null));
            done.countDown();
        });
        Thread t2 = new Thread(() -> {
            candidate.set("rY");
            await(barrier);
            results.add(h.sessions.resolveRelease(session.value()).orElse(null));
            done.countDown();
        });
        t1.start();
        t2.start();
        assertThat(done.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        t1.join(5000);
        t2.join(5000);
        h.setActiveReleaseOverride(null);

        // 并发双方读到同一赢家（数据库条件更新的 CAS 胜者）。
        assertThat(results).hasSize(2);
        assertThat(results.get(0)).isEqualTo(results.get(1)).isIn("rX", "rY");
        String winner = h.columnValue("assistant_sessions", "rag_release_id", "id=?",
                session.value());
        assertThat(winner).isEqualTo(results.get(0));
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @Order(7)
    void resolveReleaseMissingSessionIsInternalError() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> h.sessions.resolveRelease("sess_nope"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("会话不存在");
    }

    /* ------------------------------------------------------------------ */

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /** golden identity 占位 → 本测试种子 id（映射登记 evidence/J2-05）。 */
    private static JsonNode substitute(JsonNode body) throws Exception {
        String text = MAPPER.writeValueAsString(body)
                .replace("<identity:50>", "sess_qdns2xx01")
                .replace("<identity:40>", "sess_qdnsxxx01")
                .replace("<identity:22>", "sess_qexpl001")
                .replace("<identity:6>", "sess_qunans001")
                .replace("<identity:3>", SESS_HIST)
                .replace("<identity:2>", "msg_hist_01");
        return MAPPER.readTree(text);
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
