package io.dpdk.assistant.knowledge.workflow;

import io.dpdk.assistant.auth.*;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.platform.*;
import io.dpdk.assistant.contracts.task.*;
import io.dpdk.assistant.platform.errors.ApiExceptionHandler;
import io.dpdk.assistant.task.*;
import java.sql.*;
import java.util.*;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/** 全部身份/任务/事务/配额走真实 Java 实现；测试库由 TestPg 先校验实例再动态建立。 */
final class WorkflowTestHarness implements AutoCloseable {
    final TaskTestHarness task;
    final KnowledgeWorkflowService workflow;
    final UserAuthRepository auth;
    final AuthPrincipalResolver resolver;
    final Map<String, String> tokens = new HashMap<>();
    final Principal teacher = principal("teacher", "admin");
    final Principal other = principal("other", "admin");
    final Principal leader = principal("leader", "leader");
    final Principal student = principal("student", "student");
    final Principal foreign = principal("foreign", "student");

    static WorkflowTestHarness create(String tag) throws Exception {
        TaskTestHarness task = TaskTestHarness.create("j402" + tag);
        try { return new WorkflowTestHarness(task); }
        catch (Exception failure) { task.drop(); throw failure; }
    }

    private WorkflowTestHarness(TaskTestHarness task) throws Exception {
        this.task = task;
        for (Principal p : List.of(teacher, other, leader, student, foreign))
            task.seedUser(p.subject(), "[\"" + p.roles().getFirst() + "\"]", 10000);
        sql("UPDATE users SET created_by='teacher' WHERE id='student'");
        sql("UPDATE users SET created_by='other' WHERE id='foreign'");
        auth = new UserAuthRepository(task.runner, task.clock, task.authSettings);
        resolver = new AuthPrincipalResolver(task.authSettings, auth);
        for (Principal p : List.of(teacher, other, leader, student, foreign))
            tokens.put(p.subject(), auth.issueToken(p.subject()).token());
        // 单卡只验工作流持久化；成功生成检索/judge 由主 Agent 真实 bridge 集成验证。
        workflow = service(task.runner);
    }

    KnowledgeWorkflowService service(TransactionRunner runner) {
        var limiter = new SlidingWindowRateLimiter(20, "chat", task.clock);
        return new KnowledgeWorkflowService(runner, task.clock, new OriginalQuestionVerifier() {
            public void prepareAdmission(Principal p) {
                limiter.check(p.subject());
                task.counters.checkAndCountDaily(p.subject(), "chat", p.dailyQuota());
            }
            public Result verify(Principal p, String q, String c) {
                throw new AssertionError("本卡不以整段验证替身宣称产品检索/模型/judge链通过");
            }
        });
    }

    MockMvc mvc() {
        return standaloneSetup(new KnowledgeWorkflowController(workflow, resolver, task.authSettings))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    record Issue(String id, String messageId, String sessionId) {}

    Issue issue(String owner, String question, boolean unresolved) throws Exception {
        String request = UUID.randomUUID().toString();
        String session = "session-" + request;
        task.seedSession(session, owner);
        var input = new AdmissionInput(new RequestIdentity(request, TaskInputHash.compute(question),
                false, RequestIdentity.Purpose.chat), owner, session, question, "DNS conversation", "synthetic-model",
                question, 10000);
        var admitted = task.service.admitDetail(input);
        var finished = task.service.finishDetail(admitted.taskId(), session, "资料不足", List.of(),
                unresolved ? "unanswered" : "answered", unresolved ? "no_evidence" : "supported", "release-1", 0);
        return new Issue(admitted.taskId(), finished.messageId(), session);
    }

    void release(String id) throws Exception {
        sql("INSERT INTO rag_index_state(name,release_id) VALUES ('active',?) "
                + "ON CONFLICT(name) DO UPDATE SET release_id=excluded.release_id", id);
    }

    void sql(String sql, Object... args) throws Exception {
        try (Connection c = task.ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i=0; i<args.length; i++) ps.setObject(i+1,args[i]);
            ps.execute();
        }
    }

    static Principal principal(String id, String role) { return new Principal(id,List.of(role),10000,false); }
    public void close() throws Exception { task.drop(); }
}
