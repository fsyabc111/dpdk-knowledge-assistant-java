package io.dpdk.assistant.agent.port;

/**
 * 评测日配额超限（对位 Python rate_limiter 抛出的 HTTPException 429）。
 *
 * <p>grade_plugin 捕获后转结构化工具结果（quota_exceeded，友好提示），
 * 不是请求级 500。注意语义：非任务路径是「独立事务先递增后检查」的现状口径
 * （J2-05 登记差异）；任务路径 reserve 与账本登记同事务，超额整体回滚。
 */
public class QuotaExceededException extends RuntimeException {

    public QuotaExceededException(String message) {
        super(message);
    }
}
