package io.dpdk.assistant.chat;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.dpdk.assistant.agent.context.CodePoints;
import io.dpdk.assistant.agent.port.KbChunk;

import java.util.*;
import java.util.regex.Pattern;

/** 确定性证据门禁和显式 judge 接口；不改变引用位置、不用模型常识填补证据。 */
public final class AnswerValidator {
    public static final String UNANSWERED = "当前资料不足以支持可靠回答。请补充具体协议、文件名或问题场景。";
    public static final String UNAVAILABLE = "服务暂时不可用，本次问题已记录，请稍后重试。";
    private static final Pattern REFERENCES = Pattern.compile("\\[(\\d+)]");
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Verdict(String answer, String status, String reason) {}

    @FunctionalInterface
    public interface Judge {
        Map<String, Object> judge(
                String question,
                String answer,
                Map<String, Object> evidence,
                java.time.Duration timeout)
                throws Exception;
    }

    private AnswerValidator() {}

    public static Verdict validate(
            String question,
            String answer,
            Map<String, Object> evidence,
            Judge judge,
            java.time.Duration timeout) {
        List<?> chunks = (List<?>) evidence.getOrDefault("chunks", List.of());
        List<Integer> refs = new ArrayList<>();
        var match = REFERENCES.matcher(answer == null ? "" : answer);
        while (match.find()) {
            try {
                refs.add(Integer.parseInt(match.group(1)));
            } catch (NumberFormatException huge) {
                refs.add(Integer.MAX_VALUE);
            }
        }
        if (answer == null || io.dpdk.assistant.task.TaskInputHash.pythonStrip(answer).isEmpty())
            return no("empty_answer");
        if (refs.stream().anyMatch(i -> i < 1 || i > chunks.size())) return no("invalid_citation");
        if (judge == null) {
            Verdict post =
                    postcheck(chunks, refs, (List<?>) evidence.getOrDefault("tools", List.of()));
            return post == null ? new Verdict(answer, "answered", "supported") : post;
        }
        try {
            Map<String, Object> result = judge.judge(question, answer, evidence, timeout);
            String status = String.valueOf(result.get("status"));
            if (!Set.of(
                                    "answered",
                                    "partial",
                                    "needs_clarification",
                                    "unanswered",
                                    "not_applicable")
                            .contains(status)
                    || !(result.get("supported") instanceof Boolean))
                throw new IllegalArgumentException("invalid verdict");
            if (Set.of("answered", "partial").contains(status)) {
                Verdict post =
                        postcheck(
                                chunks, refs, (List<?>) evidence.getOrDefault("tools", List.of()));
                if (post != null) return post;
            }
            if (!Boolean.TRUE.equals(result.get("supported")) || "unanswered".equals(status))
                return no(
                        "conflicting_evidence".equals(result.get("reason"))
                                ? "conflicting_evidence"
                                : "insufficient_evidence");
            return new Verdict(
                    answer,
                    status,
                    Map.of(
                                    "answered",
                                    "supported",
                                    "partial",
                                    "insufficient_evidence",
                                    "needs_clarification",
                                    "clarification",
                                    "not_applicable",
                                    "non_knowledge")
                            .get(status));
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Verdict(UNAVAILABLE, "system_error", "validation_failed");
        }
    }

    private static Verdict no(String reason) {
        return new Verdict(UNANSWERED, "unanswered", reason);
    }

    private static Verdict postcheck(List<?> chunks, List<Integer> refs, List<?> tools) {
        if (chunks.isEmpty() && tools.isEmpty()) return no("no_evidence");
        if (!chunks.isEmpty() && refs.isEmpty()) return no("missing_citation");
        return null;
    }

    /** 保留每个 chunk 的位置，只缩正文；预算使用 Unicode 码点。 */
    public static Map<String, Object> evidence(
            List<KbChunk> chunks, List<Map<String, Object>> tools, int limit) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            var c = chunks.get(i);
            items.add(
                    new LinkedHashMap<>(
                            Map.of(
                                    "ref",
                                    i + 1,
                                    "source",
                                    c.sourcePath(),
                                    "content",
                                    CodePoints.substring(c.content(), 0, 1500))));
        }
        Map<String, Object> out = new LinkedHashMap<>(Map.of("chunks", items, "tools", tools));
        if (length(out) <= limit || items.isEmpty()) return out;
        List<String> originals = items.stream().map(c -> (String) c.get("content")).toList();
        items.forEach(c -> c.put("content", ""));
        int allowance = Math.max((limit - length(out)) / items.size(), 200);
        for (int n = 0; n < 8; n++) {
            for (int i = 0; i < items.size(); i++)
                items.get(i).put("content", CodePoints.substring(originals.get(i), 0, allowance));
            int chars = length(out);
            if (chars <= limit) return out;
            allowance = Math.max(allowance * limit / chars, 1);
        }
        items.forEach(c -> c.put("content", ""));
        return out;
    }

    private static int length(Object value) {
        try {
            String s = JSON.writeValueAsString(value);
            return s.codePointCount(0, s.length());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
