package io.dpdk.assistant.knowledge.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.dpdk.assistant.agent.AgentChatModel;
import io.dpdk.assistant.agent.AgentUsage;
import io.dpdk.assistant.agent.ToolCallSpec;
import io.dpdk.assistant.chat.AnswerValidator;
import io.dpdk.assistant.chat.ChatInput;
import io.dpdk.assistant.chat.ChatSettings;
import io.dpdk.assistant.chat.ChatTestHarness;
import io.dpdk.assistant.chat.GroundedQuestionVerifier;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.index.EmbeddingConfig;
import io.dpdk.assistant.contracts.model.Vector1024;
import io.dpdk.assistant.contracts.retrieval.SearchInput;
import io.dpdk.assistant.index.IndexService;
import io.dpdk.assistant.index.SourceSettings;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import io.dpdk.assistant.retrieval.HeuristicQueryRewriter;
import io.dpdk.assistant.retrieval.IndexConfigurationFingerprint;
import io.dpdk.assistant.retrieval.PgReleaseReader;
import io.dpdk.assistant.retrieval.RetrievalSettings;
import io.dpdk.assistant.retrieval.RetrievalTrace;
import io.dpdk.assistant.task.TaskResultView;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/** 问题、人工知识、全源摄取、真实检索与强制judge的组合链；仅外部模型/向量固定。 */
@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class P4WorkflowIndexIntegrationTest {
    @TempDir Path temp;
    private static final String SENTINEL = "SYNTHETIC_P4_SOURCE_SENTINEL_81";
    private static final String QUESTION = "DNS缓存策略如何配置？\n```c\n/* " + SENTINEL + " */\nint sample(void){return 0;}\n```";
    private static final Principal MANAGER = new Principal("manager", List.of("admin"), 10000, true);
    private static final Principal OTHER_MANAGER = new Principal("other-manager", List.of("admin"), 10000, true);
    private static final EmbeddingConfig CONFIG = new EmbeddingConfig("synthetic-embedding", "", 1024, "bounded-lines-v2");

    private static Vector1024 vector() {
        float[] values = new float[1024]; values[0] = 1;
        return new Vector1024(values);
    }

    private SourceSettings sources() throws Exception {
        Path dpdk = Files.createDirectories(temp.resolve("dpdk"));
        Path assistant = Files.createDirectories(temp.resolve("assistant"));
        Files.writeString(dpdk.resolve("base.md"), "# 基础项目\n数据包处理基础流程。\n");
        Files.writeString(assistant.resolve("guide.md"), "# 助教说明\n课堂问题需要引用可信资料。\n");
        Path yaml = temp.resolve("sources.yml");
        Files.writeString(yaml, "sources:\n  - name: dpdk\n    root: ${DPDK_REPO_PATH}\n    include: [base.md]\n  - name: assistant\n    root: ${ASSISTANT_PROJECT_PATH}\n    include: [guide.md]\n");
        return new SourceSettings(yaml, dpdk, assistant, temp);
    }

    private static final class Chain implements AutoCloseable {
        final AtomicInteger judgeCalls = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean firstIssue = new java.util.concurrent.atomic.AtomicBoolean(true);
        final ChatTestHarness h;
        final IndexService index;
        final PgReleaseReader reader;
        final KnowledgeWorkflowService workflow;
        String issueId;
        String knowledgeId;
        TaskResultView original;

        Chain() throws Exception {
            AgentChatModel model = (messages, tools) -> {
                if (messages.getFirst().content().equals(AnswerValidator.JUDGE_PROMPT)) {
                    judgeCalls.incrementAndGet();
                    return AgentChatModel.AgentReply.text("{\"status\":\"answered\",\"reason\":\"supported\",\"supported\":true}");
                }
                if (!tools.isEmpty() && messages.stream().noneMatch(m -> m.isTool())) {
                    return new AgentChatModel.AgentReply("", List.of(new ToolCallSpec("search-fixed", "kb_search",
                            Map.of("query", "DNS 缓存策略"))), new AgentUsage(3, 1, 4));
                }
                return AgentChatModel.AgentReply.text(firstIssue.get()
                        ? "缺少引用的临时回答" : "DNS缓存策略按教师共享资料配置 [1]");
            };
            h = ChatTestHarness.create("workflowindex", model, null);
            h.task.seedUser("manager", "[\"admin\"]", 10000);
            h.task.seedUser("other-manager", "[\"admin\"]", 10000);
            try (var c = h.task.ds.getConnection(); var ps = c.prepareStatement("UPDATE users SET created_by='manager' WHERE id='student'")) {
                ps.executeUpdate();
            }
            index = new IndexService(h.task.ds, h.task.runner, RetrievalSettings.defaults(), CONFIG,
                    new IndexConfigurationFingerprint(), h.task.clock,
                    (texts, usage) -> texts.stream().map(t -> vector()).toList(), usage -> {}, 10,
                    new PublishedKnowledgeRepository(h.task.runner));
            reader = new PgReleaseReader(h.task.ds, RetrievalSettings.defaults(), CONFIG,
                    new IndexConfigurationFingerprint(), new HeuristicQueryRewriter(),
                    input -> List.of(vector()));
            var settings = ChatSettings.defaults();
            assertThat(settings.onlineJudgeEnabled()).isFalse();
            workflow = new KnowledgeWorkflowService(h.task.runner, h.task.clock,
                    new GroundedQuestionVerifier(h.product, h.task.counters, h.task.gate, reader, model, settings));
        }

        void createIssueAndPublish(SourceSettings sources) throws Exception {
            index.ingest(sources);
            original = h.product.chat(h.student, h.input(QUESTION));
            assertThat(original.answerStatus()).isEqualTo("unanswered");
            assertThat(original.answerReason()).isEqualTo("missing_citation");
            firstIssue.set(false);
            assertThat(h.task.columnValue("rag_issues", "id", "request_id=?", original.taskId())).isEqualTo(original.taskId());
            issueId = workflow.feedback(original.messageId(), h.student, "insufficient", "需要补全DNS缓存教学资料");
            assertThat(issueId).isEqualTo(original.taskId());
            assertThatThrownBy(() -> workflow.issue(issueId, OTHER_MANAGER)).isInstanceOf(ApiDetailException.class)
                    .satisfies(e -> assertThat(((ApiDetailException)e).status().value()).isEqualTo(404));
            var claimed = workflow.update(issueId, MANAGER,
                    new KnowledgeWorkflowService.IssueUpdate("triaged", "人工认领", true, null, null));
            assertThat(claimed.get("owner_id")).isEqualTo("manager");
            var published = workflow.writeKnowledge(null, MANAGER,
                    new KnowledgeWorkflowService.KnowledgeWrite("DNS缓存策略", "DNS缓存策略：课堂规范规定缓存有效期与过期更新，按教师配置说明执行。", 0, "published", true));
            knowledgeId = (String)published.get("id");
            assertThat(published.get("index_status")).isEqualTo("pending");
            assertThat(activeSources()).doesNotContain("knowledge/published/"+knowledgeId+".md");
            assertNoSourceInPermanentRows();
        }

        void assertNoSourceInPermanentRows() throws Exception {
            assertThat(h.task.countRows("assistant_messages", "content LIKE ?", "%" + SENTINEL + "%")).isZero();
            assertThat(h.task.countRows("rag_requests", "question LIKE ? OR context_text LIKE ?", "%"+SENTINEL+"%", "%"+SENTINEL+"%")).isZero();
            assertThat(h.task.countRows("rag_issues", "feedback LIKE ?", "%"+SENTINEL+"%")).isZero();
            assertThat(h.task.countRows("rag_task_inputs", "task_id=?", original.taskId())).isZero();
        }

        List<String> activeSources() {
            var result = reader.searchPinned(reader.pin(""), new SearchInput("DNS 缓存策略", vector()), 8);
            return ((RetrievalTrace)result.trace()).nodes().stream().map(n -> n.sourcePath()).toList();
        }
        @Override public void close() throws Exception { h.close(); }
    }

    @Test
    void feedbackPublicationFullSnapshotForcedJudgeAndCurrentReleaseResolution() throws Exception {
        SourceSettings sources = sources();
        try (Chain c = new Chain()) {
            c.createIssueAndPublish(sources);
            var snapshot = c.index.scan(sources);
            assertThat(snapshot.nodes()).anyMatch(n -> n.metadata().get("source_key").equals("dpdk/base.md"));
            assertThat(snapshot.nodes()).anyMatch(n -> n.metadata().get("source_key").equals("assistant/guide.md"));
            assertThat(snapshot.nodes()).anyMatch(n -> c.knowledgeId.equals(n.metadata().get("knowledge_id")));
            String release = c.index.ingest(c.index.plan(snapshot)).releaseId();
            assertThat(c.index.health(release).healthy()).isTrue();
            assertThat(c.activeSources()).contains("knowledge/published/"+c.knowledgeId+".md");
            assertThat(c.judgeCalls.get()).isZero();
            var verified = c.workflow.verify(c.issueId, MANAGER);
            assertThat(verified.status()).isEqualTo("answered");
            assertThat(verified.releaseId()).isEqualTo(release);
            assertThat(c.judgeCalls.get()).isEqualTo(1); // onlineJudge=false仍强制验证。
            var resolved = c.workflow.update(c.issueId, MANAGER,
                    new KnowledgeWorkflowService.IssueUpdate("resolved", "当前发布验证通过", false, null, c.knowledgeId));
            assertThat(resolved.get("status")).isEqualTo("resolved");
            assertThat(resolved.get("verified_release_id")).isEqualTo(release);
            Files.writeString(sources.dpdkRoot().resolve("base.md"), "# 基础项目\n数据包处理基础流程已新增维护说明。\n");
            String next = c.index.ingest(sources).releaseId();
            assertThat(next).isNotEqualTo(release);
            assertThatThrownBy(() -> c.workflow.update(c.issueId, MANAGER,
                    new KnowledgeWorkflowService.IssueUpdate("resolved", "旧验证不得跨发布复用", false, null, c.knowledgeId)))
                    .isInstanceOf(ApiDetailException.class)
                    .satisfies(e -> assertThat(((ApiDetailException)e).status().value()).isEqualTo(409));
            var reverified = c.workflow.verify(c.issueId, MANAGER);
            assertThat(reverified.releaseId()).isEqualTo(next);
            assertThat(c.judgeCalls.get()).isEqualTo(2);
            assertThat(c.workflow.update(c.issueId, MANAGER,
                    new KnowledgeWorkflowService.IssueUpdate("resolved", "新发布重新验证通过", false, null, c.knowledgeId)).get("status"))
                    .isEqualTo("resolved");
            c.assertNoSourceInPermanentRows();
        }
    }

    @Test
    void withdrawingKnowledgeReindexesActiveWhilePinnedSessionsAndHistoricalReferencesRemainReadable() throws Exception {
        SourceSettings sources = sources();
        try (Chain c = new Chain()) {
            c.createIssueAndPublish(sources);
            String release = c.index.ingest(sources).releaseId();
            String publishedPath = "knowledge/published/"+c.knowledgeId+".md";
            var oldAnswer = c.h.product.chat(c.h.student, c.h.input("DNS 缓存策略"));
            var oldCitation = oldAnswer.citations().stream().filter(x -> publishedPath.equals(x.sourcePath())).findFirst().orElseThrow();
            assertThat(c.h.task.columnValue("assistant_sessions", "rag_release_id", "id=?", oldAnswer.sessionId())).isEqualTo(release);
            var withdrawn = c.workflow.writeKnowledge(c.knowledgeId, MANAGER,
                    new KnowledgeWorkflowService.KnowledgeWrite("DNS缓存策略", "修订后暂停共享，等待人工复核", 1, "draft", false));
            assertThat(withdrawn.get("revision")).isEqualTo(2);
            assertThat(withdrawn.get("index_status")).isEqualTo("pending");
            assertThat(c.activeSources()).contains(publishedPath); // 写工作流与生效索引分开。
            String next = c.index.ingest(sources).releaseId();
            assertThat(next).isNotEqualTo(release);
            assertThat(c.activeSources()).doesNotContain(publishedPath);
            assertThat(c.reader.historical("llama_index", oldCitation.chunkId()).orElseThrow().content()).contains("DNS缓存策略");
            var pinned = c.h.product.chat(c.h.student, new ChatInput(oldAnswer.sessionId(), UUID.randomUUID().toString(), "DNS 缓存策略", Map.of(), null));
            assertThat(pinned.citations()).anyMatch(x -> oldCitation.chunkId().equals(x.chunkId()));
            assertThat(c.h.task.columnValue("assistant_sessions", "rag_release_id", "id=?", oldAnswer.sessionId())).isEqualTo(release);
            assertThat(c.index.health(release).healthy()).isTrue();
            assertThat(c.index.health(next).healthy()).isTrue();
            c.assertNoSourceInPermanentRows();
        }
    }
}
