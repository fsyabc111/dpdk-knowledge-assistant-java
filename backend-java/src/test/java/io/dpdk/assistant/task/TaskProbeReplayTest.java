package io.dpdk.assistant.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dpdk.assistant.contracts.task.AdmissionInput;
import io.dpdk.assistant.contracts.task.RequestIdentity;
import io.dpdk.assistant.platform.ids.PythonUuid5;
import io.dpdk.assistant.task.TaskExceptions.TaskConflictException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * 验收 2 的 probe 侧：三态重放探测（miss/in_progress/terminal/replay）、
 * 同 ID 异内容与越权主体 409、历史 NULL hash 拒绝、讲解确定性身份白名单、
 * 会话删除后回放冲突。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class TaskProbeReplayTest {

    private static TaskTestHarness h;
    private static String subject;

    @BeforeAll
    static void setup() throws Exception {
        h = TaskTestHarness.create("probe");
        subject = "U20260000011";
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

    private static RequestIdentity identity(String requestId, String question,
                                            RequestIdentity.Purpose purpose) {
        return new RequestIdentity(requestId, TaskInputHash.compute(question), false, purpose);
    }

    private static AdmissionInput admitInput(String requestId, String question, String sessionId,
                                             RequestIdentity.Purpose purpose) {
        return new AdmissionInput(identity(requestId, question, purpose), subject, sessionId,
                question, "", "test-model", question, 0);
    }

    @Test
    void probeStatesMissInProgressTerminalReplay() throws Exception {
        String sessionId = newSession();
        String requestId = UUID.randomUUID().toString();
        String question = "三态探测问题";

        assertThat(h.service.probeDetail(identity(requestId, question,
                RequestIdentity.Purpose.chat), subject, Optional.of(sessionId)).state())
                .isEqualTo("miss");

        TaskAdmitOutcome admit = h.service.admitDetail(admitInput(requestId, question,
                sessionId, RequestIdentity.Purpose.chat));
        assertThat(admit.created()).isTrue();

        TaskProbeOutcome inProgress = h.service.probeDetail(
                identity(requestId, question, RequestIdentity.Purpose.chat), subject,
                Optional.of(sessionId));
        assertThat(inProgress.state()).isEqualTo("in_progress");
        assertThat(inProgress.row()).isPresent();
        assertThat(inProgress.row().get().id()).isEqualTo(admit.taskId());
        assertThat(inProgress.message()).isEmpty();

        h.service.finishDetail(admit.taskId(), sessionId, "答案正文",
                List.of(new CitationData("li_abc", "docs/x.md", "§1")), "answered", "supported",
                "release-1", 5);

        TaskProbeOutcome replay = h.service.probeDetail(
                identity(requestId, question, RequestIdentity.Purpose.chat), subject,
                Optional.of(sessionId));
        assertThat(replay.state()).isEqualTo("replay");
        assertThat(replay.message().orElseThrow().content()).isEqualTo("答案正文");

        // 取消/错误终态且无正文 → terminal（mark_interrupted 清输入无消息）
        String requestId2 = UUID.randomUUID().toString();
        TaskAdmitOutcome admit2 = h.service.admitDetail(admitInput(requestId2, "判死问题",
                sessionId, RequestIdentity.Purpose.chat));
        h.service.markInterrupted(admit2.taskId(), "interrupted");
        TaskProbeOutcome terminal = h.service.probeDetail(
                identity(requestId2, "判死问题", RequestIdentity.Purpose.chat), subject,
                Optional.of(sessionId));
        assertThat(terminal.state()).isEqualTo("terminal");
        assertThat(terminal.row().orElseThrow().status()).isEqualTo("system_error");
    }

    @Test
    void foreignSubjectAndChangedContentAreRejected() throws Exception {
        String sessionId = newSession();
        String requestId = UUID.randomUUID().toString();
        h.service.admitDetail(admitInput(requestId, "原始问题```c\nreturn 1;\n```", sessionId,
                RequestIdentity.Purpose.chat));

        // 越权主体：同 request_id 不同 user → 409（不区分存在性细节）
        assertThatThrownBy(() -> h.service.probeDetail(
                identity(requestId, "原始问题", RequestIdentity.Purpose.chat), "U99999999999",
                Optional.of(sessionId)))
                .isInstanceOf(TaskConflictException.class)
                .hasMessage("request_id 输入身份不匹配或不可验证，请更换 request_id");

        // 同 ID 异内容：红action 后文本冲突仍按 hash 拒绝
        assertThatThrownBy(() -> h.service.probeDetail(
                identity(requestId, "原始问题```c\nreturn 2;\n```", RequestIdentity.Purpose.chat),
                subject, Optional.of(sessionId)))
                .isInstanceOf(TaskConflictException.class)
                .hasMessage("request_id 输入身份不匹配或不可验证，请更换 request_id");
    }

    @Test
    void historicalNullHashFailsClosed() throws Exception {
        String sessionId = newSession();
        String requestId = UUID.randomUUID().toString();
        // initial_request 形态（客户端未带会话）：None-session probe 的命中前提。
        TaskAdmitOutcome admit = h.service.admitDetail(new AdmissionInput(
                new RequestIdentity(requestId, TaskInputHash.compute("历史行问题"), true,
                        RequestIdentity.Purpose.chat),
                subject, sessionId, "历史行问题", "", "test-model", "历史行问题", 0));
        try (var conn = h.ds.getConnection(); var st = conn.createStatement()) {
            st.execute("UPDATE rag_requests SET input_hash=NULL WHERE id='" + admit.taskId() + "'");
        }
        for (Optional<String> session : List.of(Optional.of(sessionId), Optional.<String>empty())) {
            Optional<TaskProbeOutcome> outcome;
            try {
                outcome = Optional.of(h.service.probeDetail(
                        identity(requestId, "历史行问题", RequestIdentity.Purpose.chat), subject,
                        session));
            } catch (TaskConflictException expected) {
                outcome = Optional.empty();
                assertThat(expected.getMessage())
                        .isEqualTo("request_id 输入身份不匹配或不可验证，请更换 request_id");
            }
            // NULL hash 一律 409，不可能拿到 miss/replay/in_progress
            assertThat(outcome).isEmpty();
        }
    }

    @Test
    void explainIdentityAllowsTitleDriftOnlyForDerivableEntry() throws Exception {
        String submissionId = UUID.randomUUID().toString();
        String sessionId = "explain-" + submissionId;
        String requestId = PythonUuid5.uuid5Url("assignment-explain:" + submissionId);
        h.seedSession(sessionId, subject);
        TaskAdmitOutcome admit = h.service.admitDetail(new AdmissionInput(
                identity(requestId, "讲解我的成绩", RequestIdentity.Purpose.explain),
                subject, sessionId, "讲解我的成绩（可漂移标题）", "", "test-model",
                "讲解我的成绩", 0));
        assertThat(admit.created()).isTrue();

        // 标题漂移（hash 不同）：uuid5 核实的讲解入口放行
        TaskProbeOutcome probed = h.service.probeDetail(
                identity(requestId, "完全不同的正文", RequestIdentity.Purpose.explain), subject,
                Optional.of(sessionId));
        assertThat(probed.state()).isEqualTo("in_progress");

        // 普通 chat 走同形态请求 → hash 必须拒绝
        assertThatThrownBy(() -> h.service.probeDetail(
                identity(requestId, "完全不同的正文", RequestIdentity.Purpose.chat), subject,
                Optional.of(sessionId)))
                .isInstanceOf(TaskConflictException.class);
    }

    @Test
    void replayAfterSessionDeletionConflicts() throws Exception {
        String sessionId = newSession();
        String requestId = UUID.randomUUID().toString();
        TaskAdmitOutcome admit = h.service.admitDetail(admitInput(requestId, "删除后回放问题",
                sessionId, RequestIdentity.Purpose.chat));
        h.service.finishDetail(admit.taskId(), sessionId, "正文",
                List.of(), "answered", "supported", "", 1);
        boolean deleted = h.sessions.deleteSession(sessionId, subject);
        assertThat(deleted).isTrue();
        assertThatThrownBy(() -> h.service.probeDetail(
                identity(requestId, "删除后回放问题", RequestIdentity.Purpose.chat), subject,
                Optional.of(sessionId)))
                .isInstanceOf(TaskConflictException.class)
                .hasMessage("该请求的会话已删除，请新建请求");
    }
}
