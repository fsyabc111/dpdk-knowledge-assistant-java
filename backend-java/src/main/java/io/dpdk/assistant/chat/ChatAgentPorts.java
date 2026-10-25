package io.dpdk.assistant.chat;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.dpdk.assistant.agent.port.*;
import io.dpdk.assistant.contracts.platform.*;
import io.dpdk.assistant.task.*;
import io.dpdk.assistant.usage.*;

import java.time.*;
import java.util.*;

/** Agent 消费端口到真实 Java 仓储的适配；模型、embedding、MCP 是唯一外部替身边界。 */
final class ChatAgentPorts
        implements TaskJobLedgerPort, GradingStatsPort, GradingRecordPort, GradingQuotaPort {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TaskStore tasks;
    private final io.dpdk.assistant.grading.query.AgentGradingStatsService gradingStats;
    private final ClockPort clock;
    private final UsageCounterStore quotas;

    ChatAgentPorts(
            TaskStore tasks, TransactionRunner tx, ClockPort clock, UsageCounterStore quotas) {
        this.tasks = tasks;
        this.gradingStats = new io.dpdk.assistant.grading.query.AgentGradingStatsService(tx);
        this.clock = clock;
        this.quotas = quotas;
    }

    public Map<String, Object> findJob(String taskId, String protocol, String hash) {
        return tasks.findJob(taskId, protocol, hash)
                .map(
                        r -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("job_id", r.jobId());
                            m.put("status", r.status());
                            m.put("result_json", r.resultJson());
                            m.put("submission_done", r.submissionDone());
                            return m;
                        })
                .orElse(null);
    }

    public void recordJob(String taskId, String jobId, String tool, String protocol, String hash) {
        tasks.recordJob(taskId, jobId, tool, protocol, hash);
    }

    public void reserveGradingJob(
            String taskId, String jobId, String protocol, String hash, String subject, int quota) {
        try {
            tasks.reserveGradingJob(
                    taskId,
                    jobId,
                    protocol,
                    hash,
                    subject,
                    quota,
                    LocalDate.ofInstant(clock.nowUtc(), ZoneOffset.UTC).toString());
        } catch (io.dpdk.assistant.usage.QuotaExceededException e) {
            throw new io.dpdk.assistant.agent.port.QuotaExceededException(e.getMessage());
        }
    }

    public void finishJob(String jobId, Map<String, Object> result) {
        tasks.finishJob(jobId, json(result));
    }

    public void updateTaskSource(String taskId, String text, String origin) {
        tasks.updateTaskSource(taskId, text, origin);
    }

    public void checkAndCountDaily(String subject, int quota, String kind) {
        try {
            quotas.checkAndCountDaily(subject, kind, quota);
        } catch (io.dpdk.assistant.usage.QuotaExceededException e) {
            throw new io.dpdk.assistant.agent.port.QuotaExceededException(e.getMessage());
        }
    }

    public int recentFailCount(String user, String protocol, int sample) {
        return gradingStats.recentFailCount(user, protocol, sample);
    }

    public String learnerProfile(String user) {
        return gradingStats.learnerProfile(user);
    }

    public void recordSubmission(
            String user,
            String session,
            String protocol,
            String hash,
            int score,
            int passed,
            int total,
            List<Map<String, Object>> results,
            String origin) {
        throw new IllegalStateException("产品评测必须通过 taskGrade 事务参与端口，禁止非任务成绩写入");
    }

    static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
