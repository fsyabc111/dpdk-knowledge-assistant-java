package io.dpdk.assistant.assignment.explanation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.agent.AgentChatModel;
import io.dpdk.assistant.assignment.catalog.*;
import io.dpdk.assistant.assignment.submission.*;
import io.dpdk.assistant.auth.*;
import io.dpdk.assistant.chat.*;
import io.dpdk.assistant.contracts.model.HarnessPort;
import io.dpdk.assistant.platform.errors.ApiExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** 公共产品基座上的讲解适配；仅模型/embedding/MCP 外部边界使用固定替身。 */
final class ExplainFlowHarness implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    final ChatTestHarness chat;
    final UserAuthRepository auth;
    final AuthPrincipalResolver resolver;
    final AssignmentCatalogService catalog;
    final SubmissionGradeWriter writer;
    final ExplainDataService data;
    final MockMvc mvc;
    final String token;

    ExplainFlowHarness(String tag, AgentChatModel model, HarnessPort harness) throws Exception {
        chat = ChatTestHarness.create("explain" + tag, model, harness);
        auth = new UserAuthRepository(chat.task.runner, chat.task.clock, chat.task.authSettings);
        resolver = new AuthPrincipalResolver(chat.task.authSettings, auth);
        catalog = new AssignmentCatalogService(new AssignmentCatalogRepository(chat.task.runner, chat.task.clock));
        writer = new SubmissionGradeWriter(chat.task.runner, chat.task.guard, chat.task.clock);
        data = new ExplainDataService(chat.task.adminUsers(), catalog,
                new SubmissionRecordReader(chat.task.runner),
                new ExplainSessionRepository(chat.task.runner, chat.task.clock));
        chat.executor.setExplainResolver(data::resolve);
        mvc = standaloneSetup(new SubmissionExplainController(chat.task.authSettings, resolver, data, chat.product),
                new TaskController(chat.task.service, resolver))
                .setControllerAdvice(new ApiExceptionHandler(), new ChatErrors()).build();
        token = auth.issueToken("student").token();
        chat.seedKnowledge("release-explain", "li_explain_dns", "DNS dns boundary handling 长度边界处理与报文检查。");
    }

    AssignmentRow assignment() {
        return catalog.createAssignment("teacher", List.of("leader"), "截止后的作业", "",
                Instant.parse("2020-01-01T00:00:00Z"), List.of(new AssignmentRow.ItemRow("dns", 20)));
    }

    String grade(AssignmentRow assignment, boolean allPass) {
        return writer.recordSubmission(new SubmissionGradeWriter.RecordInput("student", "dns", "hash-only",
                allPass ? 100 : 80, allPass ? 5 : 4, 5,
                "{\"source_origin\":\"uploaded\",\"results\":[{\"name\":\"dns-boundary\",\"result\":\""
                        + (allPass ? "PASS" : "FAIL") + "\"}]}", assignment.id()));
    }

    MvcResult explain(String assignment, String grade, String bearer) throws Exception {
        var request = post("/api/assignments/{aid}/submissions/{sid}/explain", assignment, grade);
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return mvc.perform(request).andReturn();
    }

    JsonNode body(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    String gradeSnapshot(String grade) throws Exception {
        try (var c = chat.task.ds.getConnection(); var ps = c.prepareStatement(
                "SELECT row_to_json(g)::text FROM grading_submissions g WHERE id=?")) {
            ps.setString(1, grade); try (var rs = ps.executeQuery()) {rs.next(); return rs.getString(1);}
        }
    }

    @Override public void close() throws Exception { chat.close(); }
}
