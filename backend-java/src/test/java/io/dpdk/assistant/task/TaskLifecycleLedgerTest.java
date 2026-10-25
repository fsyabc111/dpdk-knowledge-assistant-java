package io.dpdk.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dpdk.assistant.contracts.task.AdmissionInput;
import io.dpdk.assistant.contracts.task.RequestIdentity;
import io.dpdk.assistant.task.TaskExceptions.TaskNotFoundException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 验收 2/6 分支矩阵（库面原语，对齐 test_s3c_task_ledger.py）：
 * get_task 归属、active_task_for_session、cancel 占位消息/幂等/终态拒绝、
 * mark_interrupted、orphaned_pending grace（时钟可控）、claim_recovery 真值表、
 * 心跳刷新与终态跳过、临时输入 roundtrip/过期/覆盖、finish 重复拒绝与引用落库。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class TaskLifecycleLedgerTest {

    private static TaskTestHarness h;
    private static String subject;

    @BeforeAll
    static void setup() throws Exception {
        h = TaskTestHarness.create("lifecycle");
        subject = "U20260000021";
        h.seedUser(subject, "[\"viewer\"]", 10000);
    }

    @AfterAll
    static void teardown() throws Exception {
        if (h != null) {
            h.drop();
        }
    }

    private static String newSession() throws Exception {
        String id = "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        h.seedSession(id, subject);
        return id;
    }

    private static TaskAdmitOutcome admit(String question, String sessionId) {
        return h.service.admitDetail(new AdmissionInput(
                new RequestIdentity(UUID.randomUUID().toString(),
                        TaskInputHash.compute(question), false, RequestIdentity.Purpose.chat),
                subject, sessionId, question, "", "test-model", question, 0));
    }


    /** storeInputs 的测试包装（checked exception 折叠）。 */
    private static void storeInputs(String taskId, String question, String attachmentName,
                                    String attachmentSuffix, String sourceText, String sourceOrigin) {
        h.runner.required(tx -> {
            try {
                h.store.storeInputs(tx, taskId, question, attachmentName, attachmentSuffix,
                        sourceText, sourceOrigin);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e);
            }
            return null;
        });
    }

    /* ------------------------------------------------------------------ */
    /* 读取面                                                               */
    /* ------------------------------------------------------------------ */

    @Test
    void getTaskIsScopedToOwner() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("归属问题", sessionId);
        assertThat(h.service.getOwnedDetail(admit.taskId(), subject)).isPresent();
        assertThat(h.service.getOwnedDetail(admit.taskId(), "U99999999999")).isEmpty();
        assertThat(h.service.getOwnedDetail("missing", subject)).isEmpty();
    }

    @Test
    void activeTaskForSessionReturnsLatestPendingOnly() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome older = admit("旧问题", sessionId);
        TaskAdmitOutcome newer = admit("新问题", sessionId);
        // older 拨旧（时钟前进 + updated_at 重写为 created 值的直接 SQL 形式）
        try (var conn = h.ds.getConnection(); var st = conn.createStatement()) {
            st.execute("UPDATE rag_requests SET updated_at='2020-01-01T00:00:00+00:00' WHERE id='"
                    + older.taskId() + "'");
        }
        assertThat(h.service.activeTaskForSession(sessionId, subject)
                .orElseThrow().id()).isEqualTo(newer.taskId());
        // 终态不算活动；他人/其他会话不可见
        h.service.cancelDetail(newer.taskId(), subject, "user_cancelled");
        assertThat(h.service.activeTaskForSession(sessionId, subject)
                .orElseThrow().id()).isEqualTo(older.taskId());
        assertThat(h.service.activeTaskForSession(sessionId, "U99999999999")).isEmpty();
        assertThat(h.service.activeTaskForSession("no-such", subject)).isEmpty();
    }

    /* ------------------------------------------------------------------ */
    /* 取消 / 判死                                                          */
    /* ------------------------------------------------------------------ */

    @Test
    void cancelWritesPlaceholderClearsInputsAndIsIdempotent() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("取消问题", sessionId);
        storeInputs(admit.taskId(), "原始问题", "p.c", ".c", "源码", "upload");
        assertThat(h.service.loadInputs(admit.taskId())).isPresent();

        RagTaskService.CancelOutcome cancelled = h.service.cancelDetail(admit.taskId(), subject,
                "user_cancelled");
        assertThat(cancelled.row().status()).isEqualTo("cancelled");
        assertThat(cancelled.row().reason()).isEqualTo("user_cancelled");
        assertThat(cancelled.row().messageId()).isNotBlank();
        assertThat(h.service.loadInputs(admit.taskId())).isEmpty();

        String message = h.columnValue("assistant_messages", "content",
                "id=?", cancelled.row().messageId());
        assertThat(message).isEqualTo("（请求已取消）");
        String metadata = h.columnValue("assistant_messages", "metadata_json",
                "id=?", cancelled.row().messageId());
        assertThat(metadata.replace(" ", "")).contains("\"stream_status\":\"cancelled\"")
                .contains(admit.taskId());

        // 重复取消幂等（changed=false，返回同一终态）
        RagTaskService.CancelOutcome again = h.service.cancelDetail(admit.taskId(), subject,
                "user_cancelled");
        assertThat(again.changed()).isFalse();
        assertThat(again.row().status()).isEqualTo("cancelled");

        // 终态行不可再取消
        TaskAdmitOutcome done = admit("完成问题", sessionId);
        h.service.finishDetail(done.taskId(), sessionId, "答案", List.of(), "answered",
                "supported", "", 1);
        assertThat(h.service.cancelDetail(done.taskId(), subject, "user_cancelled")
                .changed()).isFalse();
    }

    @Test
    void cancelUnknownOrForeignTaskIs404Carrier() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("越权取消", sessionId);
        assertThatThrownBy(() -> h.service.cancelDetail(admit.taskId(), "U99999999999",
                "user_cancelled"))
                .isInstanceOf(TaskNotFoundException.class)
                .hasMessage("task not found");
        assertThatThrownBy(() -> h.service.cancelDetail("missing", subject, "user_cancelled"))
                .isInstanceOf(TaskNotFoundException.class);
    }

    @Test
    void markInterruptedRecordsIssueAndCleansInputsOnce() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("判死问题", sessionId);
        storeInputs(admit.taskId(), "原始问题", null, null, null, null);
        assertThat(h.store.markInterrupted(admit.taskId(), "account_disabled")).isTrue();
        assertThat(h.store.markInterrupted(admit.taskId(), "account_disabled")).isFalse();
        assertThat(h.columnValue("rag_requests", "status", "id=?", admit.taskId()))
                .isEqualTo("system_error");
        assertThat(h.columnValue("rag_requests", "reason", "id=?", admit.taskId()))
                .isEqualTo("account_disabled");
        assertThat(h.countRows("rag_issues", "request_id=?", admit.taskId())).isEqualTo(1);
        assertThat(h.service.loadInputs(admit.taskId())).isEmpty();
    }

    /* ------------------------------------------------------------------ */
    /* 孤儿扫描 / 认领闸 / 心跳（时钟可控）                                   */
    /* ------------------------------------------------------------------ */

    @Test
    void orphanedPendingRespectsGraceWindowWithControllableClock() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome fresh = admit("新鲜问题", sessionId);
        assertThat(h.store.orphanedPending(Duration.ofSeconds(30))).isEmpty();
        h.clock.advanceWall(Duration.ofSeconds(120));
        List<TaskRecordRow> orphans = h.store.orphanedPending(Duration.ofSeconds(30));
        assertThat(orphans).anyMatch(row -> row.id().equals(fresh.taskId()));
        // 非 pending 不入列
        h.service.cancelDetail(fresh.taskId(), subject, "user_cancelled");
        assertThat(h.store.orphanedPending(Duration.ofSeconds(30)))
                .noneMatch(row -> row.id().equals(fresh.taskId()));
    }

    @Test
    void claimRecoveryTruthTable() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("认领问题", sessionId);
        String taskId = admit.taskId();
        // 太新（< grace）→ 拒
        assertThat(h.store.claimRecovery(taskId, h.clock.nowUtc().minus(Duration.ofSeconds(30))))
                .isFalse();
        h.clock.advanceWall(Duration.ofSeconds(120));
        // 先到先得
        assertThat(h.store.claimRecovery(taskId, h.clock.nowUtc().minus(Duration.ofSeconds(30))))
                .isTrue();
        // 已拨新 → 后到者被拒
        assertThat(h.store.claimRecovery(taskId, h.clock.nowUtc().minus(Duration.ofSeconds(30))))
                .isFalse();
        // 非 pending → 拒
        h.service.cancelDetail(taskId, subject, "user_cancelled");
        h.clock.advanceWall(Duration.ofSeconds(120));
        assertThat(h.store.claimRecovery(taskId, h.clock.nowUtc().minus(Duration.ofSeconds(30))))
                .isFalse();
    }

    @Test
    void heartbeatRefreshesOwnedPendingAndSkipsTerminal() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome running = admit("心跳问题", sessionId);
        h.clock.advanceWall(Duration.ofSeconds(120));
        assertThat(h.store.orphanedPending(Duration.ofSeconds(30)))
                .anyMatch(row -> row.id().equals(running.taskId()));
        // 心跳拨新后不再入选孤儿扫描
        assertThat(h.service.heartbeatOwned()).isEqualTo(0); // 注册表无在跑句柄
        assertThat(h.store.heartbeat(List.of(running.taskId()))).isEqualTo(1);
        assertThat(h.store.orphanedPending(Duration.ofSeconds(30)))
                .noneMatch(row -> row.id().equals(running.taskId()));
        // 已终态行不被误拨
        h.service.cancelDetail(running.taskId(), subject, "user_cancelled");
        String stale = h.columnValue("rag_requests", "updated_at", "id=?", running.taskId());
        assertThat(h.store.heartbeat(List.of(running.taskId()))).isZero();
        assertThat(h.columnValue("rag_requests", "updated_at", "id=?", running.taskId()))
                .isEqualTo(stale);
    }

    /* ------------------------------------------------------------------ */
    /* 临时输入                                                             */
    /* ------------------------------------------------------------------ */

    @Test
    void taskInputsRoundtripOverwriteExpiryAndDrop() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("输入往返", sessionId);
        storeInputs(admit.taskId(), "原始问题", "p.c", ".c", "源码", "upload");
        TaskInputRecord row = h.service.loadInputs(admit.taskId()).orElseThrow();
        assertThat(row.question()).isEqualTo("原始问题");
        assertThat(row.attachmentName()).isEqualTo("p.c");
        assertThat(row.attachmentSuffix()).isEqualTo(".c");
        assertThat(row.sourceText()).isEqualTo("源码");
        assertThat(row.sourceOrigin()).isEqualTo("upload");
        assertThat(row.expiresAt().compareTo(row.createdAt()) > 0).isTrue();

        // 同 task 重复寄存是覆盖而非第二行
        storeInputs(admit.taskId(), "原始问题 v2", null, null, null, null);
        assertThat(h.countRows("rag_task_inputs", "task_id=?", admit.taskId())).isEqualTo(1);
        assertThat(h.service.loadInputs(admit.taskId()).orElseThrow().question())
                .isEqualTo("原始问题 v2");

        // update_task_source 回填执行源
        h.store.updateTaskSource(admit.taskId(), "模型复述源", "model_args");
        assertThat(h.service.loadInputs(admit.taskId()).orElseThrow().sourceOrigin())
                .isEqualTo("model_args");

        // drop 幂等
        h.store.dropInputs(admit.taskId());
        assertThat(h.service.loadInputs(admit.taskId())).isEmpty();
        h.store.dropInputs(admit.taskId());
    }

    /* ------------------------------------------------------------------ */
    /* finish 终态提交                                                       */
    /* ------------------------------------------------------------------ */

    @Test
    void finishCommitsTerminalMessageCitationsAndRejectsDuplicate() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("终态问题", sessionId);
        storeInputs(admit.taskId(), "原始问题", null, null, null, null);
        List<CitationData> citations = List.of(
                new CitationData("li_abc123", "docs/dns.md", "配置"),
                new CitationData("legacy_chunk", "docs/old.md", "历史"));
        RagTaskService.FinishResult result = h.service.finishDetail(admit.taskId(), sessionId,
                "终态正文", citations, "answered", "supported", "release-1", 12);
        assertThat(result.messageId()).isNotBlank();

        TaskRecordRow row = h.service.getOwnedDetail(admit.taskId(), subject).orElseThrow();
        assertThat(row.status()).isEqualTo("answered");
        assertThat(row.messageId()).isEqualTo(result.messageId());
        assertThat(row.releaseId()).isEqualTo("release-1");
        assertThat(row.elapsedMs()).isEqualTo(12);
        // evidence_json 只存 chunk_id/source_path/section；引用表 engine 由 li_ 前缀判定
        assertThat(row.evidenceJson()).contains("li_abc123").contains("docs/dns.md");
        assertThat(h.columnValue("assistant_citations", "citation_engine",
                "chunk_id='li_abc123'")).isEqualTo("llama_index");
        assertThat(h.columnValue("assistant_citations", "citation_engine",
                "chunk_id='legacy_chunk'")).isEqualTo("legacy");
        assertThat(h.columnValue("assistant_citations", "rank", "message_id=?",
                result.messageId())).isEqualTo("1");
        // answered 不登记 issue；临时输入即清（§8）
        assertThat(h.countRows("rag_issues", "request_id=?", admit.taskId())).isZero();
        assertThat(h.service.loadInputs(admit.taskId())).isEmpty();

        // 重复终态拒绝（重复保存回答）
        assertThatThrownBy(() -> h.service.finishDetail(admit.taskId(), sessionId, "再来一份",
                List.of(), "answered", "supported", "", 1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("请求已结束，禁止重复保存回答");
    }

    @Test
    void unansweredFinishRegistersIssueWithLowConfidence() throws Exception {
        String sessionId = newSession();
        TaskAdmitOutcome admit = admit("拒答问题", sessionId);
        RagTaskService.FinishResult result = h.service.finishDetail(admit.taskId(), sessionId,
                "当前资料不足以支持可靠回答。", List.of(), "unanswered", "no_evidence", "", 3);
        String metadata = h.columnValue("assistant_messages", "metadata_json", "id=?",
                result.messageId());
        assertThat(metadata.replace(" ", "")).contains("\"confidence\":\"low\"")
                .contains("\"answer_status\":\"unanswered\"")
                .contains("\"issue_id\"");
        assertThat(h.countRows("rag_issues", "request_id=?", admit.taskId())).isEqualTo(1);
    }
}
