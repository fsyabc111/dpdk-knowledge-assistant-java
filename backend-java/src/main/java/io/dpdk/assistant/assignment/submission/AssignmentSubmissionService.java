package io.dpdk.assistant.assignment.submission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.admin.AdminUserRepository;
import io.dpdk.assistant.assignment.catalog.PythonScore;
import io.dpdk.assistant.assignment.catalog.SubmissionSnapshot;
import io.dpdk.assistant.assignment.catalog.AssignmentCatalogPort;
import io.dpdk.assistant.contracts.grading.FormalSubmissionPort;
import io.dpdk.assistant.contracts.grading.SubmitReceipt;
import io.dpdk.assistant.contracts.grading.SubmittedSource;
import io.dpdk.assistant.contracts.identity.Principal;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.platform.errors.ApiDetailException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;

/**
 * 正式提交编排（冻结 api/assignments.py 的 submit_assignment 主流程）：
 *
 * <pre>受理资格（快照 404 → 截止 400 → 协议 400）→ grade_code（413/429/504/502）
 * → payload 数值化（畸形按 Python 异常语义 → 500）→ 单事务成绩落库（403 停号）
 * → best → 受理口径响应。</pre>
 *
 * 两种分值口径：响应/落库 accepted_assignment 用受理快照 points；成绩汇总的
 * 动态折算归 J2-03 report（best 查询按请求协议）。
 */
public class AssignmentSubmissionService implements FormalSubmissionPort {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** _GRADING_ERROR_STATUS（assignments.py 模块常量；其余错误码默认 502）。 */
    private static final Map<String, HttpStatus> GRADING_ERROR_STATUS = Map.of(
            "code_too_large", HttpStatus.PAYLOAD_TOO_LARGE,
            "quota_exceeded", HttpStatus.TOO_MANY_REQUESTS,
            "grading_timeout", HttpStatus.GATEWAY_TIMEOUT);

    private final AssignmentCatalogPort catalog;
    private final AdminUserRepository users;
    private final GradingInvoker grading;
    private final SubmissionGradeWriter writer;
    private final ClockPort clock;

    public AssignmentSubmissionService(AssignmentCatalogPort catalog, AdminUserRepository users,
            GradingInvoker grading, SubmissionGradeWriter writer, ClockPort clock) {
        this.catalog = catalog;
        this.users = users;
        this.grading = grading;
        this.writer = writer;
        this.clock = clock;
    }

    /** 完整提交结果（响应组装所需全集；SubmitReceipt 为其投影）。 */
    public record SubmitOutcome(int score, int passed, int total, JsonNode results, int points,
            double earned, double bestEarned, String submissionId) {
    }

    public SubmitOutcome submitFull(String assignmentId, Principal actor, SubmittedSource source) {
        // _student_created_by：学生的班级归属；静态 token/已删账号为 null（未分配）。
        String createdBy = users.getUser(actor.subject())
                .map(AdminUserRepository.UserRow::createdBy).orElse(null);
        SubmissionSnapshot snapshot = catalog
                .submissionSnapshot(assignmentId, createdBy, source.protocol())
                .orElseThrow(() -> new ApiDetailException(HttpStatus.NOT_FOUND, "not found"));
        if (catalog.isPastDeadline(snapshot, clock.nowUtc())) {
            throw new ApiDetailException(HttpStatus.BAD_REQUEST, "已过截止时间，不能提交");
        }
        Integer points = snapshot.points();
        if (points == null) {
            throw new ApiDetailException(HttpStatus.BAD_REQUEST,
                    "协议 " + source.protocol() + " 不属于该作业");
        }
        // accepted_at：datetime.now(timezone.utc).isoformat()（与 now_iso 的会话时钟分立）。
        String acceptedAt = SystemClock.pythonIso(clock.nowUtc());
        GradingInvoker.GradeOutcome grade = grading.gradeCode(actor.subject(), actor.roles(),
                source.protocol(), source.codeText());
        if (!grade.ok()) {
            HttpStatus status = GRADING_ERROR_STATUS.getOrDefault(grade.error(),
                    HttpStatus.BAD_GATEWAY);
            throw new ApiDetailException(status, grade.detail());
        }
        JsonNode payload = grade.payload();
        int score = scoreOf(payload);
        int passed = intOf(payload.get("passed"));
        int total = intOf(payload.get("cases_total"));
        JsonNode results = resultsOf(payload.get("results"));
        String storedProtocol = strOrFallback(payload.get("protocol"), source.protocol());
        // 最小化落库：代码 sha256 按编码替换后的文本计算（非上传原始字节）。
        String codeHash = PythonUtf8.sha256Hex(PythonUtf8.encodeReplace(source.codeText()));
        Map<String, Object> resultsJson = new LinkedHashMap<>();
        resultsJson.put("source_origin", sourceOriginOf(source));
        resultsJson.put("results", results);
        resultsJson.put("accepted_assignment", acceptedAssignment(snapshot, points, acceptedAt));
        String submissionId = writer.recordSubmission(new SubmissionGradeWriter.RecordInput(
                actor.subject(), storedProtocol, codeHash, score, passed, total,
                toJson(resultsJson), assignmentId));
        // 多次提交取最高分（含本次，因落库在前）；best 按请求协议（照源）。
        Optional<Integer> best = catalog.bestScore(assignmentId, actor.subject(),
                source.protocol());
        return new SubmitOutcome(score, passed, total, results, points,
                PythonScore.calcEarned(score, points),
                PythonScore.calcEarned(best.orElse(null), points),
                submissionId);
    }

    @Override
    public SubmitReceipt submit(String assignmentId, Principal actor, SubmittedSource source) {
        SubmitOutcome outcome = submitFull(assignmentId, actor, source);
        return new SubmitReceipt(outcome.submissionId(), outcome.score());
    }

    /* ------------------------------------------------------------------ */
    /* 受理快照 → 落库依据                                                  */
    /* ------------------------------------------------------------------ */

    private static Map<String, Object> acceptedAssignment(SubmissionSnapshot snapshot,
            int points, String acceptedAt) {
        Map<String, Object> accepted = new LinkedHashMap<>();
        accepted.put("points", points);
        accepted.put("scope", snapshot.scope());
        accepted.put("created_by", snapshot.createdBy());
        accepted.put("deadline", SystemClock.pythonStr(snapshot.deadline()));
        accepted.put("assignment_updated_at", SystemClock.pythonStr(snapshot.updatedAt()));
        accepted.put("accepted_at", acceptedAt);
        return accepted;
    }

    private static String sourceOriginOf(SubmittedSource source) {
        // 冻结契约 SubmittedSource 未携带 origin；upload/pasted 由请求解析侧经
        // fileName 判别（JSON 粘贴 fileName=null）。
        return source.fileName() == null ? "pasted" : "upload";
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("results_json serialization failure", e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* payload 数值化（Python or-链 / float()/int()/list() 语义）            */
    /* ------------------------------------------------------------------ */

    /** int(round(float(payload["score"] or 0)))——HALF_EVEN，畸形可抛 500。 */
    private static int scoreOf(JsonNode payload) {
        JsonNode node = payload.get("score");
        double value = truthy(node) ? pythonFloat(node) : 0.0;
        return PythonScore.roundToInt(value);
    }

    /** int(payload["x"] or 0)：int() 截断小数、字符串严格整型。 */
    private static int intOf(JsonNode node) {
        if (!truthy(node)) {
            return 0;
        }
        if (node.isInt() || node.isLong()) {
            return Math.toIntExact(node.asLong());
        }
        if (node.isDouble() || node.isFloatingPointNumber()) {
            double d = node.asDouble();
            if (Double.isNaN(d)) {
                throw new ArithmeticException("cannot convert float NaN to integer");
            }
            if (Double.isInfinite(d)) {
                throw new ArithmeticException("cannot convert float infinity to integer");
            }
            return (int) d; // Python int() 截断朝零
        }
        if (node.isBoolean()) {
            return node.asBoolean() ? 1 : 0;
        }
        if (node.isTextual()) {
            return pythonIntLiteral(node.asText());
        }
        throw new IllegalStateException("int() argument must be a string or a number");
    }

    /**
     * list(payload["results"] or [])：falsy → []；字符串拆字符；数组透传；
     * 非空对象取键序列表（Python list(dict)）；其余 TypeError → 500。
     */
    private static JsonNode resultsOf(JsonNode node) {
        List<JsonNode> items = new ArrayList<>();
        if (node == null || node.isNull() || node.isMissingNode()) {
            return MAPPER.createArrayNode();
        }
        if (node.isArray()) {
            node.forEach(items::add);
        } else if (node.isTextual()) {
            if (!node.asText().isEmpty()) {
                node.asText().codePoints()
                        .forEach(c -> items.add(MAPPER.getNodeFactory()
                                .textNode(new String(Character.toChars(c)))));
            }
        } else if (node.isObject()) {
            node.fieldNames().forEachRemaining(name ->
                    items.add(MAPPER.getNodeFactory().textNode(name)));
        } else {
            throw new IllegalStateException("results is not iterable (TypeError parity)");
        }
        var array = MAPPER.createArrayNode();
        items.forEach(array::add);
        return array;
    }

    /** str(payload["protocol"] or protocol)：falsy 回退请求协议；标量按 Python str()。 */
    private static String strOrFallback(JsonNode node, String fallback) {
        if (!truthy(node)) {
            return fallback;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        return GradingInvoker.pythonStr(node);
    }

    /** Python or-链 falsy：null/缺失/false/0/0.0/空串/空数组/空对象。 */
    private static boolean truthy(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        if (node.isNumber()) {
            return node.asDouble() != 0.0d;
        }
        if (node.isTextual()) {
            return !node.asText().isEmpty();
        }
        if (node.isArray() || node.isObject()) {
            return node.size() > 0;
        }
        return true;
    }

    /** Python float()：数字直取；bool 0/1；字符串按 Python 字面量（含 inf/nan/下划线）。 */
    private static double pythonFloat(JsonNode node) {
        if (node.isNumber()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean() ? 1.0d : 0.0d;
        }
        if (node.isTextual()) {
            return pythonFloatLiteral(node.asText());
        }
        throw new IllegalStateException("float() argument must be a string or a number");
    }

    /** Python float(str)：strip 后允许 [+-]、十进制/科学计数、下划线、inf/infinity/nan。 */
    private static double pythonFloatLiteral(String raw) {
        String text = raw.strip();
        if (text.isEmpty()) {
            throw new NumberFormatException("could not convert string to float: " + raw);
        }
        String lower = text.toLowerCase();
        String body = lower.startsWith("+") || lower.startsWith("-") ? lower.substring(1) : lower;
        if (body.equals("inf") || body.equals("infinity")) {
            return lower.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (body.equals("nan")) {
            return Double.NaN;
        }
        String compact = text.replace("_", "");
        if (compact.chars().anyMatch(c -> c == 'd' || c == 'D' || c == 'f' || c == 'F'
                || c == 'x' || c == 'X')) {
            throw new NumberFormatException("could not convert string to float: " + raw);
        }
        try {
            return Double.parseDouble(compact);
        } catch (NumberFormatException e) {
            throw new NumberFormatException("could not convert string to float: " + raw);
        }
    }

    /** Python int(str)：strip 后 [+-]?纯数字（下划线允许）；小数点/尾缀拒绝。 */
    private static int pythonIntLiteral(String raw) {
        String text = raw.strip();
        String compact = text.replace("_", "");
        String body = compact.startsWith("+") || compact.startsWith("-")
                ? compact.substring(1) : compact;
        if (body.isEmpty() || !body.chars().allMatch(Character::isDigit)) {
            throw new IllegalStateException(
                    "invalid literal for int() with base 10: '" + raw + "'");
        }
        try {
            return Integer.parseInt(compact);
        } catch (NumberFormatException e) {
            throw new ArithmeticException("Python int too large to convert to HTTP semantics");
        }
    }

    /** Python isoformat/str(datetime) 渲染（UTC 零偏移）。 */
    private static final class SystemClock {

        static String pythonIso(Instant instant) {
            return io.dpdk.assistant.platform.time.SystemClockAdapter.pythonIso(
                    instant.truncatedTo(ChronoUnit.MICROS));
        }

        static String pythonStr(Instant instant) {
            var t = java.time.LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
            StringBuilder sb = new StringBuilder(32);
            sb.append(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .format(t));
            long micros = instant.truncatedTo(ChronoUnit.MICROS).getNano() / 1_000;
            if (micros != 0) {
                sb.append('.');
                sb.append(String.format("%06d", micros));
            }
            sb.append("+00:00");
            return sb.toString();
        }
    }
}
