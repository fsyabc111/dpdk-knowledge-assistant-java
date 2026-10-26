package io.dpdk.assistant.knowledge.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.agent.fallback.Redact;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.platform.*;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import io.dpdk.assistant.platform.time.SystemClockAdapter;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.HttpStatus;

/** 人工问题闭环；只拥有工作流表，不接管任务、消息、用量与索引存储。 */
public final class KnowledgeWorkflowService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ISSUE_SELECT =
            "SELECT i.*,r.question,r.context_text,r.reason,r.status AS answer_status,"
                    + "r.evidence_json,r.release_id,r.created_at,r.model_version,r.elapsed_ms "
                    + "FROM rag_issues i JOIN rag_requests r ON r.id=i.request_id ";
    private final TransactionRunner transactions;
    private final ClockPort clock;
    private final OriginalQuestionVerifier verifier;

    public KnowledgeWorkflowService(
            TransactionRunner transactions, ClockPort clock, OriginalQuestionVerifier verifier) {
        this.transactions = transactions;
        this.clock = clock;
        this.verifier = Objects.requireNonNull(verifier);
    }

    public record IssueUpdate(
            String status, String note, boolean claim, String mergedInto, String knowledgeId) {}

    public record KnowledgeWrite(
            String title, String body, int revision, String status, boolean sharedConfirmed) {}

    public String feedback(String messageId, Principal principal, String reason, String detail) {
        return transactions.required(tx -> {
            var request = one(tx, "SELECT id FROM rag_requests WHERE message_id=? AND user_id=?",
                    messageId, principal.subject());
            if (request == null) throw error(404, "not found");
            String id = (String) request.get("id");
            // id 与 request_id 同为任务主键；双连接同时首次反馈须同时覆盖两个唯一键。
            execute(tx, "INSERT INTO rag_issues(id,request_id,updated_at) VALUES (?,?,?) "
                    + "ON CONFLICT DO NOTHING", id, id, timestamp(null));
            // 锁行后计算版本，跨进程/同微秒反馈也必须推进 CAS 标记。
            var old = one(tx, "SELECT updated_at FROM rag_issues WHERE request_id=? FOR UPDATE", id);
            String feedback = Redact.redact(reason + ": " + detail);
            String changed = timestamp((String) old.get("updated_at"));
            execute(tx, "UPDATE rag_issues SET feedback=?,status='open',verified_at=NULL,"
                    + "verified_release_id=NULL,updated_at=? WHERE request_id=?", feedback, changed, id);
            event(tx, id, principal.subject(), "feedback", feedback, changed);
            return id;
        });
    }

    public Map<String, Object> issues(Principal principal, String status, int limit, int offset) {
        requireManager(principal);
        return transactions.required(tx -> {
            List<Object> args = new ArrayList<>();
            String scope = scope(principal, args);
            if (status != null && !status.isEmpty()) {
                scope += " AND i.status=?";
                args.add(status);
            }
            args.add(limit);
            args.add(offset);
            var rows = rows(tx, "SELECT i.id,i.status,i.owner_id,i.feedback,r.question,r.reason,"
                    + "r.created_at,r.status AS answer_status FROM rag_issues i "
                    + "JOIN rag_requests r ON r.id=i.request_id WHERE " + scope
                    + " ORDER BY r.created_at DESC,i.id LIMIT ? OFFSET ?", args.toArray());
            return Map.of("items", rows, "offset", offset, "limit", limit);
        });
    }

    public Map<String, Object> issue(String id, Principal principal) {
        requireManager(principal);
        return transactions.required(tx -> get(tx, id, principal, true));
    }

    public Map<String, Object> update(String id, Principal principal, IssueUpdate update) {
        requireManager(principal);
        Map<String, Object> old = issue(id, principal);
        return transactions.required(tx -> {
            if (present(update.mergedInto())) {
                // 合并操作共用事务级锁，防止 A→B / B→A 同时通过图约束。
                one(tx, "SELECT pg_advisory_xact_lock(19440202)");
                var target = get(tx, update.mergedInto(), principal, false);
                if (id.equals(target.get("id")) || present((String) target.get("merged_into")))
                    throw error(400, "不能循环合并或合并到已合并记录");
                if (one(tx, "SELECT id FROM rag_issues WHERE merged_into=? LIMIT 1", id) != null)
                    throw error(409, "该记录已有合并来源，不能再合并");
            }
            var current = one(tx, "SELECT release_id FROM rag_index_state WHERE name='active' FOR SHARE");
            if ("resolved".equals(update.status()) && (old.get("verified_at") == null
                    || current == null || !Objects.equals(old.get("verified_release_id"), current.get("release_id"))))
                throw error(409, "请先在当前知识版本上通过原题验证");
            if (present(update.knowledgeId())
                    && one(tx, "SELECT id FROM rag_knowledge WHERE id=?", update.knowledgeId()) == null)
                throw error(404, "知识条目不存在");
            String changed = timestamp((String) old.get("updated_at"));
            int count = execute(tx, "UPDATE rag_issues SET status=?,note=?,owner_id=?,merged_into=?,"
                    + "knowledge_id=?,updated_at=? WHERE id=? AND updated_at=?",
                    update.status(), Redact.redact(update.note()),
                    update.claim() ? principal.subject() : old.get("owner_id"), update.mergedInto(),
                    update.knowledgeId(), changed, id, old.get("updated_at"));
            if (count != 1) throw error(409, "记录已更新，请刷新");
            event(tx, id, principal.subject(), update.status(), Redact.redact(update.note()), changed);
            return get(tx, id, principal, true);
        });
    }

    public OriginalQuestionVerifier.Result verify(String id, Principal principal) {
        requireManager(principal);
        verifier.prepareAdmission(principal);
        var old = issue(id, principal);
        try {
            // 数据库事务已经结束；模型/检索/受理等待不得占用问题事务。
            var result = verifier.verify(principal, (String) old.get("question"), (String) old.get("context_text"));
            markVerified(id, principal, result.releaseId(), result.status(), (String) old.get("updated_at"));
            return result;
        } catch (ApiDetailException failure) {
            throw failure;
        } catch (RuntimeException failed) {
            throw error(503, "原题验证暂时不可用");
        }
    }

    public void markVerified(String id, Principal principal, String release, String verdict, String expected) {
        requireManager(principal);
        transactions.required(tx -> {
            get(tx, id, principal, false);
            // 冻结源分别为验证事实、问题更新和后续事件读取now；未通过仍写NULL且不读验证时钟。
            String verifiedAt = "answered".equals(verdict) ? timestamp(expected) : null;
            String changed = timestamp(verifiedAt == null ? expected : verifiedAt);
            int count = execute(tx, "UPDATE rag_issues SET verified_release_id=?,verified_at=?,updated_at=? "
                    + "WHERE id=? AND updated_at=?", release, verifiedAt,
                    changed, id, expected);
            if (count != 1) throw error(409, "问题已更新（可能有新反馈），请刷新后重新验证");
            event(tx, id, principal.subject(), "verify", verdict, changed);
            return null;
        });
    }

    public Map<String, Object> knowledge(Principal principal) {
        requireManager(principal);
        return transactions.required(tx -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", rows(tx, "SELECT * FROM rag_knowledge ORDER BY updated_at DESC LIMIT 200"));
            var state = one(tx, "SELECT release_id FROM rag_index_state WHERE name='active'");
            out.put("active_release", state == null ? null : state.get("release_id"));
            return out;
        });
    }

    public Map<String, Object> writeKnowledge(String id, Principal principal, KnowledgeWrite body) {
        requireManager(principal);
        if ("published".equals(body.status()) && !body.sharedConfirmed())
            throw error(400, "请确认内容已人工检查且对全体学生可见");
        return transactions.required(tx -> {
            String identity = id == null ? uuid() : id;
            int revision;
            String changed;
            // 人工知识正文保留冻结 Python 字节语义；教学源码允许由作者确认后共享。
            String title = body.title();
            String text = body.body();
            if (id != null) {
                var old = one(tx, "SELECT * FROM rag_knowledge WHERE id=?", id);
                if (old == null || (!principal.subject().equals(old.get("created_by")) && !leader(principal)))
                    throw error(404, "not found");
                revision = body.revision() + 1;
                changed = timestamp((String) old.get("updated_at"));
                int count = execute(tx, "UPDATE rag_knowledge SET title=?,body=?,revision=?,status=?,updated_at=? "
                        + "WHERE id=? AND revision=?", title, text, revision, body.status(), changed, id, body.revision());
                if (count != 1) throw error(409, "知识条目已更新，请刷新");
            } else {
                revision = 1;
                changed = timestamp(null);
                execute(tx, "INSERT INTO rag_knowledge(id,created_by,title,body,revision,status,updated_at) "
                        + "VALUES (?,?,?,?,?,?,?)", identity, principal.subject(), title, text, revision, body.status(), changed);
            }
            // 修订是独立持久记录，使用自己的墙钟；主记录时点仅作为单调下限。
            execute(tx, "INSERT INTO rag_knowledge_revisions(id,knowledge_id,revision,actor_id,title,body,status,created_at) "
                    + "VALUES (?,?,?,?,?,?,?,?)", uuid(), identity, revision, principal.subject(), title, text, body.status(), timestamp(changed));
            return Map.of("id", identity, "revision", revision, "status", body.status(), "index_status", "pending");
        });
    }

    public Map<String, Object> revisions(String id, Principal principal) {
        requireManager(principal);
        return transactions.required(tx -> Map.of("items", rows(tx,
                "SELECT revision,actor_id,title,body,status,created_at FROM rag_knowledge_revisions "
                        + "WHERE knowledge_id=? ORDER BY revision DESC LIMIT 100", id)));
    }

    /** 由请求留存 owner 在同一事务内先调用；请求/临时源码/作业删除仍归 task。 */
    public Map<String, Integer> maintainInTx(DbTx tx, int days) {
        if (days < 1) throw new IllegalArgumentException("留存天数必须为正数");
        String cutoff = SystemClockAdapter.pythonIso(clock.nowUtc().minus(Duration.ofDays(days)).truncatedTo(ChronoUnit.MICROS));
        for (String[] pair : List.of(new String[]{"rag_issues", "owner_id"}, new String[]{"rag_issue_events", "actor_id"},
                new String[]{"rag_knowledge", "created_by"}, new String[]{"rag_knowledge_revisions", "actor_id"}))
            execute(tx, "UPDATE " + pair[0] + " SET " + pair[1] + "=NULL WHERE " + pair[1]
                    + " IS NOT NULL AND NOT EXISTS (SELECT 1 FROM users WHERE users.id=" + pair[0] + "." + pair[1] + ")");
        int events = execute(tx, "DELETE FROM rag_issue_events WHERE issue_id IN (SELECT id FROM rag_requests WHERE created_at<?)", cutoff);
        int issues = execute(tx, "DELETE FROM rag_issues WHERE request_id IN (SELECT id FROM rag_requests WHERE created_at<?)", cutoff);
        return Map.of("events", events, "issues", issues);
    }

    private Map<String, Object> get(DbTx tx, String id, Principal principal, boolean events) {
        List<Object> args = new ArrayList<>(List.of(id));
        String clause = scope(principal, args);
        var result = one(tx, ISSUE_SELECT + "WHERE i.id=? AND (" + clause + ")", args.toArray());
        if (result == null) throw error(404, "not found");
        try { result.put("evidence", JSON.readValue((String) result.remove("evidence_json"), Object.class)); }
        catch (Exception e) { throw new IllegalStateException("问题证据 JSON 无效", e); }
        if (events) result.put("events", rows(tx, "SELECT action,detail,actor_id,created_at FROM rag_issue_events "
                + "WHERE issue_id=? ORDER BY created_at LIMIT 200", id));
        return result;
    }

    private static String scope(Principal principal, List<Object> args) {
        if (leader(principal)) return "1=1";
        args.add(principal.subject());
        return "r.user_id IN (SELECT id FROM users WHERE created_by=?)";
    }

    private static boolean leader(Principal p) { return p.roles().contains("leader"); }
    static void requireManager(Principal p) {
        if (!leader(p) && !p.roles().contains("admin")) throw error(404, "not found");
    }

    private void event(DbTx tx, String id, String actor, String action, String detail, String previous) {
        // 冻结源event独立调用now；主记录时点只作单调下限，不能复用为事件创建时间。
        execute(tx, "INSERT INTO rag_issue_events(id,issue_id,actor_id,action,detail,created_at) VALUES (?,?,?,?,?,?)",
                uuid(), id, actor, action, detail, timestamp(previous));
    }

    private String timestamp(String previous) {
        Instant now = clock.nowUtc().truncatedTo(ChronoUnit.MICROS);
        if (previous != null) {
            Instant old = OffsetDateTime.parse(previous).toInstant();
            if (!now.isAfter(old)) now = old.plusNanos(1000);
        }
        return SystemClockAdapter.pythonIso(now);
    }

    private static boolean present(String s) { return s != null && !s.isEmpty(); }
    private static String uuid() { return UUID.randomUUID().toString().replace("-", ""); }
    static ApiDetailException error(int status, Object detail) { return new ApiDetailException(HttpStatus.valueOf(status), detail); }

    private static int execute(DbTx tx, String sql, Object... args) {
        try (PreparedStatement statement = tx.connection().prepareStatement(sql)) {
            for (int i=0; i<args.length; i++) statement.setObject(i+1, args[i]);
            return statement.executeUpdate();
        } catch (SQLException e) { throw new IllegalStateException("工作流写入失败", e); }
    }

    private static Map<String, Object> one(DbTx tx, String sql, Object... args) {
        var values = rows(tx, sql, args);
        return values.isEmpty() ? null : values.getFirst();
    }

    private static List<Map<String, Object>> rows(DbTx tx, String sql, Object... args) {
        try (PreparedStatement statement = tx.connection().prepareStatement(sql)) {
            for (int i=0; i<args.length; i++) statement.setObject(i+1, args[i]);
            try (ResultSet rs = statement.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                int count = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i=1; i<=count; i++) row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    out.add(row);
                }
                return out;
            }
        } catch (SQLException e) { throw new IllegalStateException("工作流读取失败", e); }
    }
}
