package io.dpdk.assistant.agent.tools;

import io.dpdk.assistant.agent.AgentSettings;
import io.dpdk.assistant.agent.port.AgentUsageSink;
import io.dpdk.assistant.agent.port.GradingQuotaPort;
import io.dpdk.assistant.agent.port.GradingRecordPort;
import io.dpdk.assistant.agent.port.GradingStatsPort;
import io.dpdk.assistant.agent.port.KbChunk;
import io.dpdk.assistant.agent.port.KnowledgeRetriever;
import io.dpdk.assistant.agent.port.TaskGradePort;
import io.dpdk.assistant.agent.port.TaskJobLedgerPort;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次 Agent 运行的请求级上下文（对位 AgentToolContext；工具以它闭包绑定，
 * 图本身无状态 D11，可安全按请求重建）。
 *
 * <p>执行源带外寄存（B6）字段：uploadedSource（上传原件）> pastedSource（消息
 * 抽取的围栏块）> fallbackSource（S3-c 恢复寄存源，origin=model_args 首评的
 * 权威字节）> 模型参数复述。大小校验、MCP 入参与落库 code_hash 全部以这份字节为准。
 *
 * <p>S3-c 任务账本锚点：仅 productId 上下文非空；普通聊天路径为 null，
 * grade_plugin 的账本接线整体跳过、行为与旧版一致。
 */
public final class AgentToolContext {

    /** 评测配额豁免角色（D13：教师/管理员/组长不计学生配额）。 */
    public static final List<String> GRADING_QUOTA_EXEMPT_ROLES =
            List.of("operator", "admin", "leader");

    /** 回填给模型的单个知识片段长度上限，控制 token 体积。 */
    public static final int KB_CHUNK_CONTENT_LIMIT = 1500;

    public final AgentSettings settings;
    public final String userId;
    public final List<String> userRoles;
    public final String sessionId;
    public final KnowledgeRetriever retriever;
    public final HarnessCaller harness;
    public final GradingRecordPort gradingRecord;
    public final GradingQuotaPort gradingQuota;
    public final GradingStatsPort gradingStats;
    public final TaskGradePort taskGrade;
    public final TaskJobLedgerPort taskLedger;
    /** kb_search 命中的原始 chunk，供 sources/citations 生成。 */
    public final List<KbChunk> collectedChunks = new ArrayList<>();
    /** 成功执行的 grade_plugin 结果（harness 原始 payload），供反馈层引用。 */
    public final List<Map<String, Object>> gradingResults = new ArrayList<>();
    /** 上传路径的 .c 原件源码；null 表示非上传路径。 */
    public final String uploadedSource;
    /** B6 粘贴路径的执行源：从当前用户消息抽取的最长围栏代码块；null 表示没有。 */
    public final String pastedSource;
    /** B7 同请求评测去重表：key = protocol:sha256(执行源字节)。只缓存成功结果。 */
    public final Map<String, Map<String, Object>> gradedSources = new HashMap<>();
    public final String conversationContext;
    public boolean searchAttempted;
    public final AgentUsageSink usage;
    /** S3-c 任务账本锚点；null 表示普通聊天路径。 */
    public final String taskId;
    /** S3-c 恢复寄存源（origin=model_args 首次提交的权威字节）。 */
    public final String fallbackSource;
    /** S3-d 讲解模式：关联的正式作业评测结果元数据；非 null 时提示词追加讲解块。 */
    private Map<String, Object> explainContext;

    private AgentToolContext(Builder b) {
        this.settings = b.settings;
        this.userId = b.userId;
        this.userRoles = b.userRoles;
        this.sessionId = b.sessionId;
        this.retriever = b.retriever;
        this.harness = b.harness;
        this.gradingRecord = b.gradingRecord;
        this.gradingQuota = b.gradingQuota;
        this.gradingStats = b.gradingStats;
        this.taskGrade = b.taskGrade;
        this.taskLedger = b.taskLedger;
        this.uploadedSource = b.uploadedSource;
        this.pastedSource = b.pastedSource;
        this.conversationContext = b.conversationContext;
        this.usage = b.usage;
        this.taskId = b.taskId;
        this.fallbackSource = b.fallbackSource;
        this.explainContext = b.explainContext;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private AgentSettings settings = AgentSettings.defaults();
        private String userId = "anonymous";
        private List<String> userRoles = List.of();
        private String sessionId;
        private KnowledgeRetriever retriever;
        private HarnessCaller harness;
        private GradingRecordPort gradingRecord;
        private GradingQuotaPort gradingQuota;
        private GradingStatsPort gradingStats;
        private TaskGradePort taskGrade;
        private TaskJobLedgerPort taskLedger;
        private String uploadedSource;
        private String pastedSource;
        private String conversationContext;
        private AgentUsageSink usage;
        private String taskId;
        private String fallbackSource;
        private Map<String, Object> explainContext;

        public Builder settings(AgentSettings v) {
            this.settings = v;
            return this;
        }

        public Builder userId(String v) {
            this.userId = v;
            return this;
        }

        public Builder userRoles(List<String> v) {
            this.userRoles = v == null ? List.of() : List.copyOf(v);
            return this;
        }

        public Builder sessionId(String v) {
            this.sessionId = v;
            return this;
        }

        public Builder retriever(KnowledgeRetriever v) {
            this.retriever = v;
            return this;
        }

        public Builder harness(HarnessCaller v) {
            this.harness = v;
            return this;
        }

        public Builder gradingRecord(GradingRecordPort v) {
            this.gradingRecord = v;
            return this;
        }

        public Builder gradingQuota(GradingQuotaPort v) {
            this.gradingQuota = v;
            return this;
        }

        public Builder gradingStats(GradingStatsPort v) {
            this.gradingStats = v;
            return this;
        }

        public Builder taskGrade(TaskGradePort v) {
            this.taskGrade = v;
            return this;
        }

        public Builder taskLedger(TaskJobLedgerPort v) {
            this.taskLedger = v;
            return this;
        }

        public Builder uploadedSource(String v) {
            this.uploadedSource = v;
            return this;
        }

        public Builder pastedSource(String v) {
            this.pastedSource = v;
            return this;
        }

        public Builder conversationContext(String v) {
            this.conversationContext = v;
            return this;
        }

        public Builder usage(AgentUsageSink v) {
            this.usage = v;
            return this;
        }

        public Builder taskId(String v) {
            this.taskId = v;
            return this;
        }

        public Builder fallbackSource(String v) {
            this.fallbackSource = v;
            return this;
        }

        public Builder explainContext(Map<String, Object> v) {
            this.explainContext = v;
            return this;
        }

        public AgentToolContext build() {
            return new AgentToolContext(this);
        }
    }

    public boolean quotaExempt() {
        for (String role : userRoles) {
            if (GRADING_QUOTA_EXEMPT_ROLES.contains(role)) {
                return true;
            }
        }
        return false;
    }

    /** S3-d 讲解模式的元数据装配点（对位 _build_run 的 explain 分支）。 */
    public void seedExplainContext(Map<String, Object> explain) {
        this.explainContext = explain;
        if (explain != null) {
            // 该次评测预播种进 grading_results：形状对齐 harness 原始载荷
            //（results[].result），failed_graded_protocol 据此照常触发参考答案
            // 注入，降级答案的评分事实也能带上这次成绩。有意复用，不是新评测。
            Map<String, Object> seeded = new LinkedHashMap<>();
            seeded.put("ok", true);
            seeded.put("protocol", explain.get("protocol"));
            seeded.put("score", explain.get("score"));
            seeded.put("passed", explain.get("passed"));
            seeded.put("cases_total", explain.get("total"));
            seeded.put("results", explain.get("results") == null ? List.of() : explain.get("results"));
            seeded.put("explained_submission", explain.get("submission_id"));
            gradingResults.add(seeded);
        }
    }

    public Map<String, Object> explainContext() {
        return explainContext;
    }
}
