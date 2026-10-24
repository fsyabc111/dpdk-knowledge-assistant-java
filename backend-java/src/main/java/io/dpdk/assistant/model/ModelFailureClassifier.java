package io.dpdk.assistant.model;

import java.util.Locale;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

/**
 * 上游失败分类与重试延迟（逐行对齐 backend/app/core/llm_client.py 的
 * is_retriable_llm_error 与 llm_retry_delay_seconds）。
 *
 * <p>判定顺序与源一致：
 * <ol>
 *   <li>传输层超时/网络错误（Spring {@link ResourceAccessException}，即 httpx
 *       TimeoutException/NetworkError 的对位）可重试；</li>
 *   <li>沿 cause 链找状态码（{@link ModelHttpException} 或 Spring 的
 *       {@link RestClientResponseException}），429 或 ≥5xx 可重试；</li>
 *   <li>逐层检查异常消息中的标记词：connection error / connection reset /
 *       timed out / temporarily unavailable。</li>
 * </ol>
 */
public final class ModelFailureClassifier {

    private static final int MAX_CAUSE_DEPTH = 8;
    private static final String[] MESSAGE_MARKERS = {
            "connection error", "connection reset", "timed out", "temporarily unavailable",
    };

    private ModelFailureClassifier() {
    }

    /** 该失败是否为可重试的瞬态传输/上游错误。 */
    public static boolean isRetriable(Throwable exc) {
        int depth = 0;
        for (Throwable t = exc; t != null && depth++ < MAX_CAUSE_DEPTH; t = t.getCause()) {
            if (t instanceof ResourceAccessException) {
                return true;
            }
            Integer status = statusCodeOf(t);
            if (status != null) {
                return status == 429 || status >= 500;
            }
        }
        // 消息标记按源语义逐层检查（Python 递归检查 cause 的 str）。
        depth = 0;
        for (Throwable t = exc; t != null && depth++ < MAX_CAUSE_DEPTH; t = t.getCause()) {
            String message = t.getMessage();
            if (message != null) {
                String lowered = message.toLowerCase(Locale.ROOT);
                for (String marker : MESSAGE_MARKERS) {
                    if (lowered.contains(marker)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static Integer statusCodeOf(Throwable t) {
        if (t instanceof ModelHttpException http) {
            return http.status();
        }
        if (t instanceof RestClientResponseException response) {
            return response.getStatusCode().value();
        }
        return null;
    }

    /**
     * 聊天指数退避：attempt 为「本次失败是第几次失败」（1 起），与源
     * {@code base * 2 ** max(attempt - 1, 0)} 一致；无抖动。
     */
    public static double retryDelaySeconds(int attempt, double baseSeconds) {
        return baseSeconds * Math.pow(2.0, Math.max(attempt - 1, 0));
    }

    /**
     * embedding 重试延迟：优先 Retry-After 数值秒；缺失、非法或 ≤0 时退化为
     * {@code base * 2**attempt + jitter}，jitter = uniform(0, min(0.5, delay*0.25))。
     * attempt 从 0 起（与源 embed 循环一致）。
     */
    public static double embeddingRetryDelaySeconds(
            int attempt, double baseSeconds, Double retryAfterSeconds, double jitterUniform01) {
        if (retryAfterSeconds != null && retryAfterSeconds > 0.0) {
            return retryAfterSeconds;
        }
        double delay = baseSeconds * Math.pow(2.0, attempt);
        double bound = Math.min(0.5, delay * 0.25);
        return delay + jitterUniform01 * bound;
    }
}
