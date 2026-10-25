package io.dpdk.assistant.operations.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.admin.AdminUserRepository;
import io.dpdk.assistant.contracts.model.Vector1024;
import io.dpdk.assistant.contracts.retrieval.SearchInput;
import io.dpdk.assistant.index.IndexService;
import io.dpdk.assistant.index.SourceSettings;
import io.dpdk.assistant.knowledge.workflow.KnowledgeWorkflowService;
import io.dpdk.assistant.operations.OperationsPort;
import io.dpdk.assistant.retrieval.PgReleaseReader;
import io.dpdk.assistant.session.SessionService;
import io.dpdk.assistant.task.TaskStore;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/** 运维业务适配：SQL 留在领域仓储；固定样例检索不走默认 embedding 外发。 */
public final class OperationsBusinessAdapter implements OperationsPort {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final AdminUserRepository admin;
    private final SessionService sessions;
    private final TaskStore tasks;
    private final KnowledgeWorkflowService workflow;
    private final IndexService index;
    private final PgReleaseReader retrieval;
    private final Function<Path, SourceSettings> sourceSettings;
    private final Map<String, Vector1024> fixedQueries;

    public OperationsBusinessAdapter(AdminUserRepository admin, SessionService sessions, TaskStore tasks,
            KnowledgeWorkflowService workflow, IndexService index, PgReleaseReader retrieval,
            Function<Path, SourceSettings> sourceSettings, Map<String, Vector1024> fixedRetrievalQueries) {
        this.admin = Objects.requireNonNull(admin); this.sessions = Objects.requireNonNull(sessions);
        this.tasks = Objects.requireNonNull(tasks); this.workflow = Objects.requireNonNull(workflow);
        this.index = index;
        // 接线必须传入纯本地 QueryRewritePort 的运维 reader，不能复用可能外发改写的产品 reader。
        this.retrieval = retrieval;
        this.sourceSettings = Objects.requireNonNull(sourceSettings);
        this.fixedQueries = Map.copyOf(fixedRetrievalQueries);
    }

    @Override public Map<String, Object> createUsers(CreateUsers request, boolean apply) {
        if (!apply) {
            var preview = admin.previewCreateUsers(request.ids(), request.roles(), request.dailyQuota());
            var remainingCreates = new HashSet<>(preview.wouldCreate());
            List<String> skipped = new ArrayList<>();
            for (String id : request.ids()) if (!remainingCreates.remove(id)) skipped.add(id);
            return Map.of("dry_run", true, "would_create", preview.wouldCreate(), "existing", preview.existing(),
                    "invalid", preview.invalid(), "skipped", List.copyOf(skipped));
        }
        var result = admin.createUsers(request.ids(), request.roles(), request.dailyQuota(), null);
        return Map.of("dry_run", false, "created", result.affected(), "skipped", result.skipped());
    }
    @Override public Map<String, Object> purgeSessions(int days, boolean apply) {
        positive(days); return sessions.purgeExpired(days, !apply);
    }
    @Override public Map<String, Object> purgeTaskInputs(boolean apply) { return tasks.purgeInputs(!apply); }
    @Override public Map<String, Object> retainIssues(int days, boolean apply) {
        positive(days); return tasks.maintain(days, !apply, workflow::maintainInTx);
    }
    @Override public Map<String, Object> ingest(Path manifest, boolean apply) {
        requireIndex(); requireAbsolute(manifest);
        var sources = Objects.requireNonNull(sourceSettings.apply(manifest), "显式知识源配置");
        var plan = index.plan(index.scan(sources));
        if (apply) return Map.of("dry_run", false, "result", index.ingest(plan));
        return Map.of("dry_run", true, "desired", plan.prepared().desiredNodes().size(),
                "changed", plan.prepared().changedNodes().size(), "removed", plan.removed());
    }
    @Override public Map<String, Object> rollback(String release, boolean apply) {
        requireIndex();
        if (release == null || release.isBlank()) throw new IllegalArgumentException("版本不能为空");
        var health = index.health(release);
        if (!health.healthy()) throw new IllegalArgumentException("目标版本不存在、配置不匹配或节点不完整");
        if (apply) index.rollbackChecked(release);
        return Map.of("dry_run", !apply, "release", release, "health", health);
    }
    @Override public Map<String, Object> auditGradingJobs(int limit) {
        return Map.of("read_only", true, "jobs", tasks.auditGradingJobs(limit), "re_evaluated", false);
    }
    @Override public Map<String, Object> retrieve(String query, int topK) {
        if (topK < 1 || topK > 100) throw new IllegalArgumentException("topK 越界");
        Vector1024 vector = fixedQueries.get(query);
        if (retrieval == null || vector == null)
            throw new IllegalStateException("仅支持显式配置的固定检索样例，当前样例不可用");
        var pinned = retrieval.pin(null);
        var found = retrieval.searchPinned(pinned, new SearchInput(query, vector), topK);
        return Map.of("domain", "deterministic_retrieval", "non_live", true, "release", pinned.releaseId(),
                "chunks", found.chunks());
    }
    /** 固定样例集合的检索验收，不替代原问题模型证据核验或真实 judge。 */
    @Override public Map<String, Object> judge(Path cases) {
        requireAbsolute(cases);
        final JsonNode input;
        try { input = JSON.readTree(cases.toFile()); }
        catch (IOException e) { throw new IllegalArgumentException("固定样例 JSON 不可读", e); }
        if (input == null || !input.isArray() || input.isEmpty()) throw new IllegalArgumentException("样例必须为非空 JSON 数组");
        // 先校验全部样例，避免处理一半才遇到越权 query。
        for (JsonNode item : input) {
            if (!item.path("question").isTextual() || !fixedQueries.containsKey(item.path("question").asText())
                    || !item.path("expected_chunk_ids").isArray() || item.path("expected_chunk_ids").isEmpty())
                throw new IllegalArgumentException("样例缺少显式固定 question 或非空 expected_chunk_ids");
            for (JsonNode id : item.path("expected_chunk_ids"))
                if (!id.isTextual() || id.asText().isBlank()) throw new IllegalArgumentException("期望片段 ID 无效");
        }
        if (retrieval == null) throw new IllegalStateException("固定检索适配器不可用");
        var pinned = retrieval.pin(null);
        List<Map<String, Object>> results = new ArrayList<>();
        for (JsonNode item : input) {
            String query = item.path("question").asText();
            var found = retrieval.searchPinned(pinned, new SearchInput(query, fixedQueries.get(query)), 100);
            var actual = new HashSet<>(found.chunks().stream().map(c -> c.chunkId()).toList());
            var expected = new HashSet<String>(); item.path("expected_chunk_ids").forEach(id -> expected.add(id.asText()));
            long hits = expected.stream().filter(actual::contains).count();
            results.add(Map.of("case", results.size()+1, "hits", hits, "expected", expected.size(), "recall", (double) hits/expected.size()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("domain", "deterministic_retrieval"); out.put("non_live", true);
        out.put("model_judge", "unavailable: 本入口仅验收固定检索样例，不调用模型或 harness");
        out.put("release", pinned.releaseId()); out.put("cases", results);
        return out;
    }
    private void requireIndex() { if (index == null) throw new IllegalStateException("索引能力未显式配置"); }
    private static void positive(int days) { if (days < 1) throw new IllegalArgumentException("留存天数必须为正数"); }
    private static void requireAbsolute(Path path) {
        if (path == null || !path.isAbsolute()) throw new IllegalArgumentException("必须使用显式绝对文件路径");
    }
}
