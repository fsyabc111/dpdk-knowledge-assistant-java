package io.dpdk.assistant.assignment.submission;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.contracts.model.Deadline;
import io.dpdk.assistant.contracts.model.HarnessPort;
import io.dpdk.assistant.contracts.model.HarnessReply;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.usage.QuotaExceededException;
import io.dpdk.assistant.usage.UsageCounterStore;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 评测封装（冻结 services/harness_grading.py 的 grade_code）：
 * 大小校验 → 每日配额（kind=grading，豁免角色 {operator,admin,leader} 与
 * agent/tools.py 的 GRADING_QUOTA_EXEMPT_ROLES 一致）→ 经 HarnessPort 调
 * grade_plugin。返回统一 {"ok": ...} 契约，绝不向编排方抛业务异常
 * （fallback 哲学）；配额用 J2-05 独立事务版（先递增后检查，失败计数保留）。
 *
 * <p>超时语义（J1-02 端口模型的登记差异）：Python 用 asyncio.wait_for 总超时，
 * 到期即 grading_timeout；Java 经 Deadline 表达——tools/call 字节发出前到期 →
 * 端口返回 NOT_SUBMITTED/harness_deadline_exceeded（本类折算为 grading_timeout，
 * 与 Python 同为 504）；发出后失败 → UNKNOWN/harness_unavailable（502，不盲重发，
 * 不伪称未提交）。两者的账面副作用（配额已计、成绩未落）一致。
 */
@Service
public final class GradingInvoker {

    /** GRADING_QUOTA_EXEMPT_ROLES（agent/tools.py）。 */
    public static final Set<String> EXEMPT_ROLES = Set.of("operator", "admin", "leader");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HarnessPort harness;
    private final UsageCounterStore quota;
    private final ClockPort clock;
    private final SubmissionSettings settings;

    public GradingInvoker(HarnessPort harness, UsageCounterStore quota, ClockPort clock,
            SubmissionSettings settings) {
        this.harness = harness;
        this.quota = quota;
        this.clock = clock;
        this.settings = settings;
    }

    /** grade_code 的归一化结果：ok + 结构化错误码/详情 + 成功 payload。 */
    public record GradeOutcome(boolean ok, String error, String detail, JsonNode payload) {

        static GradeOutcome failure(String error, String detail) {
            return new GradeOutcome(false, error, detail, null);
        }
    }

    /** 评测一份插件源码，返回 harness 原始 payload 或结构化错误。 */
    public GradeOutcome gradeCode(String userId, List<String> userRoles, String protocol,
            String pluginSource) {
        byte[] codeBytes = PythonUtf8.encodeReplace(pluginSource);
        if (codeBytes.length > settings.gradingMaxCodeBytes()) {
            return GradeOutcome.failure("code_too_large",
                    "源码大小超过上限（" + settings.gradingMaxCodeBytes() + " 字节）");
        }
        if (userRoles.stream().noneMatch(EXEMPT_ROLES::contains)) {
            try {
                quota.checkAndCountDaily(userId, "grading", settings.gradingDailyQuota());
            } catch (QuotaExceededException e) {
                return GradeOutcome.failure("quota_exceeded", "今日评测配额已用完（"
                        + settings.gradingDailyQuota() + " 次/天），请明天再试");
            }
        }
        Duration timeout = Duration.ofSeconds(settings.gradingTimeoutSec());
        Deadline deadline = clock.deadlineAfter(timeout);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("protocol", protocol);
        args.put("plugin_source", pluginSource);
        args.put("timeout_sec", settings.gradingTimeoutSec());
        HarnessReply reply = harness.call("grade_plugin", args, deadline);
        JsonNode payload = parsePayload(reply);
        if (reply.error() != null) {
            if ("harness_deadline_exceeded".equals(reply.error())) {
                // 端口级 Deadline 在提交前到期 → 与 Python wait_for 超时同为 grading_timeout。
                return GradeOutcome.failure("grading_timeout",
                        "评测超过 " + settings.gradingTimeoutSec() + " 秒未完成");
            }
            // harness_unavailable（含 UNKNOWN——已提交、结果未知，端口已保证不重发）。
            return GradeOutcome.failure(reply.error(), orFallback(payload.get("detail"),
                    "评测服务返回失败"));
        }
        if (!payload.isObject() || !payload.path("ok").asBoolean(false)) {
            String error = orFallback(payload.get("error"), "grading_failed");
            return GradeOutcome.failure(error, orFallback(payload.get("detail"),
                    "评测服务返回失败"));
        }
        return new GradeOutcome(true, null, null, payload);
    }

    private static JsonNode parsePayload(HarnessReply reply) {
        try {
            JsonNode node = MAPPER.readTree(reply.payloadJson());
            return node == null ? MAPPER.createObjectNode() : node;
        } catch (Exception e) {
            return MAPPER.createObjectNode();
        }
    }

    /**
     * Python {@code str(payload.get(k) or fallback)}：null/缺失/空串/False/0 均回退
     * （or-链 falsy 语义）；其余按 {@link #pythonStr}。
     */
    static String orFallback(JsonNode value, String fallback) {
        if (value == null || value.isNull() || value.isMissingNode()
                || (value.isTextual() && value.asText().isEmpty())
                || (value.isBoolean() && !value.asBoolean())
                || (value.isNumber() && value.asDouble() == 0.0d)) {
            return fallback;
        }
        return pythonStr(value);
    }

    /** Python str() 的 JSON 标量近似（bool → True/False；容器按 Jackson 渲染，登记差异）。 */
    static String pythonStr(JsonNode value) {
        if (value.isBoolean()) {
            return value.asBoolean() ? "True" : "False";
        }
        return value.asText();
    }
}
