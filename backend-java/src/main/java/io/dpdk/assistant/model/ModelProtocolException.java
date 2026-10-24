package io.dpdk.assistant.model;

/**
 * 上游 2xx 但响应体不符合协议预期（空响应、非法 JSON、缺 choices/message/content、
 * embedding 数量不匹配等）。对齐 Python 中 resp.json()/键访问失败路径：不可重试。
 */
public class ModelProtocolException extends RuntimeException {

    public ModelProtocolException(String message) {
        super(message);
    }

    public ModelProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
