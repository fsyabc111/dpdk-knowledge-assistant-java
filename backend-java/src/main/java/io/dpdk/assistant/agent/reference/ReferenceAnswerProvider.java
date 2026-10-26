package io.dpdk.assistant.agent.reference;

import io.dpdk.assistant.agent.AgentSettings;
import io.dpdk.assistant.agent.context.CodePoints;
import io.dpdk.assistant.agent.tools.HarnessCaller;
import io.dpdk.assistant.retrieval.PythonJson;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 教师参考答案的带外注入（M2 §6，服务端通道，非模型工具；源 agent/reference.py）。
 *
 * <p>grade_plugin 出现非 PASS 用例时，prompt_builder 经本类向 harness 拉取该协议
 * 的参考实现，追加到系统提示词末尾，帮助模型理解错因——参考答案只进系统提示词，
 * 配合红线重述，不作为模型可见工具。降级哲学：工具不存在、网络失败、错误返回等
 * 任何异常都只记 warning 并返回 null，绝不向编排层抛穿。实例内按协议缓存
 * （prompt_builder 每轮重建都会调用，缓存避免同请求重复打 harness）。
 */
public final class ReferenceAnswerProvider {

    private static final Logger log = LoggerFactory.getLogger(ReferenceAnswerProvider.class);

    private final HarnessCaller harness;
    private final int maxChars;
    private final Map<String, String> cache = new HashMap<>();

    public ReferenceAnswerProvider(HarnessCaller harness, AgentSettings settings) {
        this.harness = harness;
        this.maxChars = settings.referenceAnswerMaxChars();
    }

    /** 返回参考答案源码，任何失败/为空返回 null（结果按协议缓存，含失败结果）。 */
    public String get(String protocol) {
        if (cache.containsKey(protocol)) {
            return cache.get(protocol);
        }
        String answer = fetch(protocol);
        cache.put(protocol, answer);
        return answer;
    }

    private String fetch(String protocol) {
        Map<String, Object> payload;
        try {
            payload = harness.referenceAnswer(protocol);
        } catch (Exception exc) {
            log.warn(PythonJson.dumps(Map.of(
                    "event", "reference_answer_failed", "protocol", protocol,
                    "error_type", exc.getClass().getSimpleName()), false));
            return null;
        }
        // 契约（teaching_harness get_reference_answer）：{"ok": true, "protocol": ..., "source": 源码}。
        if (!Boolean.TRUE.equals(payload.get("ok"))) {
            log.warn(PythonJson.dumps(Map.of(
                    "event", "reference_answer_failed", "protocol", protocol,
                    "error_type", payload.get("error") == null ? "bad_payload" : payload.get("error"),
                    "detail", CodePoints.truncate(payload.get("detail") == null
                            ? "" : String.valueOf(payload.get("detail")), 300)), false));
            return null;
        }
        Object answerObj = payload.get("source");
        if (!(answerObj instanceof String answer) || answer.isBlank()) {
            log.warn(PythonJson.dumps(Map.of(
                    "event", "reference_answer_failed", "protocol", protocol,
                    "error_type", "empty_answer", "detail", "参考答案为空"), false));
            return null;
        }
        if (CodePoints.length(answer) > maxChars) {
            answer = CodePoints.truncate(answer, maxChars) + "\n[已截断]";
        }
        return answer;
    }
}
