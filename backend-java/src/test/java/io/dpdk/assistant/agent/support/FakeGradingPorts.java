package io.dpdk.assistant.agent.support;

import io.dpdk.assistant.agent.port.GradingQuotaPort;
import io.dpdk.assistant.agent.port.GradingRecordPort;
import io.dpdk.assistant.agent.port.GradingStatsPort;
import io.dpdk.assistant.agent.port.QuotaExceededException;
import io.dpdk.assistant.agent.port.TaskGradePort;
import io.dpdk.assistant.contracts.grading.TaskGradeInput;
import io.dpdk.assistant.contracts.grading.TaskGradeResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 成绩/配额/统计替身束：记录调用、可注入故障。
 */
public final class FakeGradingPorts {

    /** 非任务路径成绩落库替身（best-effort；实现方自行吞错，这里记录后吞）。 */
    public static final class Record implements GradingRecordPort {
        public final List<Map<String, Object>> submissions = new ArrayList<>();
        public Supplier<RuntimeException> failure;

        @Override
        public void recordSubmission(String userId, String sessionId, String protocol,
                                     String codeHash, int score, int passed, int total,
                                     List<Map<String, Object>> results, String sourceOrigin) {
            java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("userId", userId);
            row.put("sessionId", sessionId);
            row.put("protocol", protocol);
            row.put("codeHash", codeHash);
            row.put("score", score);
            row.put("passed", passed);
            row.put("total", total);
            row.put("results", results);
            row.put("sourceOrigin", sourceOrigin);
            submissions.add(row);
            if (failure != null) {
                // 对位 Python：DB 抖动记 warning 后吞掉，不让评测答复失败。
                try {
                    throw failure.get();
                } catch (RuntimeException ignored) {
                    // best-effort
                }
            }
        }
    }

    /** 非任务路径配额替身：独立事务先递增后检查的现状口径。 */
    public static final class Quota implements GradingQuotaPort {
        public final List<String> charges = new ArrayList<>();
        public int remaining = Integer.MAX_VALUE;

        @Override
        public void checkAndCountDaily(String subject, int quota, String kind) {
            if (quota > 0 && charges.size() >= quota) {
                throw new QuotaExceededException("今日评测配额已用完");
            }
            charges.add(subject + "|" + kind + "|" + quota);
        }
    }

    /** 统计替身：升级条件与学习者画像。 */
    public static final class Stats implements GradingStatsPort {
        public int recentFails;
        public String profile = "";
        public Supplier<RuntimeException> failure;

        @Override
        public int recentFailCount(String userId, String protocol, int sample) {
            if (failure != null) {
                throw failure.get();
            }
            return recentFails;
        }

        @Override
        public String learnerProfile(String userId) {
            return profile;
        }
    }

    /** 任务成绩落库替身（J2-04 冻结契约）；persisted 幂等 + cancelled/故障注点。 */
    public static final class TaskGrade implements TaskGradePort {
        public final List<TaskGradeInput> calls = new ArrayList<>();
        public boolean cancelled;
        public Supplier<RuntimeException> failure;
        public int legacyUnlinked;

        @Override
        public TaskGradeResult recordTaskGrade(TaskGradeInput input) {
            calls.add(input);
            if (failure != null) {
                throw failure.get();
            }
            if (cancelled) {
                return TaskGradeResult.cancelled;
            }
            for (TaskGradeInput prior : calls) {
                if (prior != input && prior.jobId().equals(input.jobId())) {
                    // task_job_id 部分唯一索引防重复：已落过成绩返回 persisted。
                    return TaskGradeResult.persisted;
                }
            }
            return legacyUnlinked > 0 ? TaskGradeResult.legacy_unlinked : TaskGradeResult.persisted;
        }
    }

    /** 用量替身：记录 LLM 调用与 MCP 操作（含失败路径的 unknown 记账）。 */
    public static final class Usage implements io.dpdk.assistant.agent.port.AgentUsageSink {
        public record LlmCall(String stage, String model, boolean known, Integer totalTokens) {
        }

        public final List<LlmCall> llmCalls = new ArrayList<>();
        public final List<String> operations = new ArrayList<>();

        @Override
        public void recordLlm(String stage, String modelName, io.dpdk.assistant.agent.AgentUsage usage) {
            llmCalls.add(new LlmCall(stage, modelName, usage != null,
                    usage == null ? null : usage.totalTokens()));
        }

        @Override
        public void recordOperation(String kind, String stage, long durationMs) {
            operations.add(kind + "|" + stage);
        }
    }
}
