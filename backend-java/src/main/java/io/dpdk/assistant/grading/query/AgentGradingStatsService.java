package io.dpdk.assistant.grading.query;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.dpdk.assistant.agent.port.GradingStatsPort;
import io.dpdk.assistant.contracts.platform.TransactionRunner;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/** 成绩域公开的 Agent 只读统计服务；调用者不直接拼接成绩模块 SQL。 */
public final class AgentGradingStatsService implements GradingStatsPort {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final TransactionRunner tx;

    public AgentGradingStatsService(TransactionRunner tx) {
        this.tx = tx;
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
}
