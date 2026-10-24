package io.dpdk.assistant.model;

/**
 * 上游返回非 2xx 状态（对齐 httpx raise_for_status / HTTPStatusError）。
 *
 * 携带状态码、（截断后的）响应体与 Retry-After 头的数值秒（无法解析为数字时为 null）。
 * 是否可重试由 {@link ModelFailureClassifier} 判定：429 与 5xx 可重试，其余不可。
 */
public class ModelHttpException extends RuntimeException {

    private final int status;
    private final String body;
    private final Double retryAfterSeconds;

    public ModelHttpException(int status, String body, Double retryAfterSeconds) {
        super("HTTP " + status);
        this.status = status;
        this.body = body == null ? "" : body;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public int status() {
        return status;
    }

    /** 错误响应体（可能为空串；调用方自行控制日志不回显敏感内容）。 */
    public String body() {
        return body;
    }

    /** Retry-After 头的数值秒；缺失或非数字为 null。 */
    public Double retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
