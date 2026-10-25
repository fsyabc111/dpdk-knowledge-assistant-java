package io.dpdk.assistant.chat;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.dpdk.assistant.agent.port.*;
import io.dpdk.assistant.contracts.platform.*;
import io.dpdk.assistant.task.*;
import io.dpdk.assistant.usage.*;

import java.sql.*;
import java.time.*;
import java.util.*;

/** Agent 消费端口到真实 Java 仓储的适配；模型、embedding、MCP 是唯一外部替身边界。 */
final class ChatAgentPorts
        implements TaskJobLedgerPort, GradingStatsPort, GradingRecordPort, GradingQuotaPort {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TaskStore tasks;
    private final TransactionRunner tx;
    private final ClockPort clock;
    private final UsageCounterStore quotas;

    ChatAgentPorts(
            TaskStore tasks, TransactionRunner tx, ClockPort clock, UsageCounterStore quotas) {
        this.tasks = tasks;
        this.tx = tx;
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
        return tx.required(
                db -> {
                    try (var ps =
                            db.connection()
                                    .prepareStatement(
                                            "SELECT passed,total FROM grading_submissions WHERE"
                                                + " user_id=? AND protocol=? ORDER BY created_at"
                                                + " DESC,id DESC LIMIT ?")) {
                        ps.setString(1, user);
                        ps.setString(2, protocol);
                        ps.setInt(3, sample);
                        try (var rs = ps.executeQuery()) {
                            int n = 0;
                            while (rs.next()) if (rs.getInt(1) < rs.getInt(2)) n++;
                            return n;
                        }
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                });
    }

    public String learnerProfile(String user) {
        return tx.required(
                db -> {
                    try (var ps =
                            db.connection()
                                    .prepareStatement(
                                            "SELECT protocol,score,results_json FROM"
                                                + " grading_submissions WHERE user_id=? ORDER BY"
                                                + " created_at DESC,id DESC LIMIT 50")) {
                        ps.setString(1, user);
                        TreeMap<String, Profile> profiles = new TreeMap<>();
                        try (var rs = ps.executeQuery()) {
                            while (rs.next()) {
                                Profile p =
                                        profiles.computeIfAbsent(
                                                rs.getString(1), k -> new Profile());
                                p.n++;
                                p.best = Math.max(p.best, rs.getInt(2));
                                try {
                                    var node =
                                            JSON.readTree(Objects.toString(rs.getString(3), "{}"));
                                    for (var c : node.path("results"))
                                        if (c.isObject()
                                                && !"PASS".equals(c.path("result").asText())
                                                && !c.path("name").asText().isEmpty())
                                            p.fails.merge(c.path("name").asText(), 1, Integer::sum);
                                } catch (Exception ignored) {
                                }
                            }
                        }
                        List<String> parts = new ArrayList<>();
                        profiles.forEach(
                                (protocol, p) -> {
                                    String v = protocol + "：尝试" + p.n + "次、最高" + p.best + "分";
                                    var fails = new ArrayList<>(p.fails.keySet());
                                    fails.sort(
                                            (a, b) ->
                                                    Integer.compare(
                                                            p.fails.get(b), p.fails.get(a)));
                                    if (!fails.isEmpty())
                                        v +=
                                                "、常挂用例："
                                                        + String.join(
                                                                "、",
                                                                fails.subList(
                                                                        0,
                                                                        Math.min(2, fails.size())));
                                    parts.add(v);
                                });
                        return io.dpdk.assistant.agent.context.CodePoints.truncate(
                                String.join("；", parts), 300);
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                });
    }

    private static class Profile {
        int n, best;
        LinkedHashMap<String, Integer> fails = new LinkedHashMap<>();
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
        try {
            tx.required(
                    db -> {
                        try (var ps =
                                db.connection()
                                        .prepareStatement(
                                                "INSERT INTO"
                                                    + " grading_submissions(id,user_id,session_id,protocol,score,passed,total,results_json,code_hash,created_at)"
                                                    + " VALUES (?,?,?,?,?,?,?,?::jsonb,?,?)")) {
                            ps.setString(1, UUID.randomUUID().toString().replace("-", ""));
                            ps.setString(2, user);
                            ps.setString(3, session);
                            ps.setString(4, protocol);
                            ps.setInt(5, score);
                            ps.setInt(6, passed);
                            ps.setInt(7, total);
                            ps.setString(
                                    8,
                                    json(
                                            Map.of(
                                                    "results",
                                                    results,
                                                    "source_origin",
                                                    Objects.toString(origin, ""))));
                            ps.setString(9, hash);
                            ps.setObject(10, clock.nowUtc().atOffset(ZoneOffset.UTC));
                            ps.executeUpdate();
                            return null;
                        } catch (SQLException e) {
                            throw new IllegalStateException(e);
                        }
                    });
        } catch (RuntimeException ignored) {
            /* 非任务路径按源 best-effort。 */
        }
    }

    static String json(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }
}
