package io.dpdk.assistant.task;

import java.util.List;
import java.util.Map;

/**
 * 任务结果视图（done 帧与 attach 非流式响应共用的 ChatResponse 投影，
 * 不含 answer/citations 之外的解释性字段；字段名与 ChatResponse schema 同位）。
 */
public record TaskResultView(
        String sessionId,
        String messageId,
        String answer,
        List<CitationData> citations,
        List<Map<String, Object>> nextSteps,
        String confidence,
        String answerStatus,
        String answerReason,
        String issueId,
        String requestId,
        String taskId) {
}
