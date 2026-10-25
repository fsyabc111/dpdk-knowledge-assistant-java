package io.dpdk.assistant.agent.port;

import java.util.List;
import java.util.Map;

/**
 * 非任务路径成绩落库端口（grading_submissions；owner J2-04 侧存储，聊天路径）。
 *
 * <p>对位 GradingService.record_submission 的 agent 消费面：best-effort 持久化，
 * 数据库抖动不得让评测答复失败——实现方自行吞错（Python 捕获后记 warning）。
 * 只存最小集：学生/会话/协议/分数/用例结果 JSON/代码 sha256，不存源码全文。
 */
public interface GradingRecordPort {

    void recordSubmission(String userId, String sessionId, String protocol, String codeHash,
                          int score, int passed, int total, List<Map<String, Object>> results,
                          String sourceOrigin);
}
