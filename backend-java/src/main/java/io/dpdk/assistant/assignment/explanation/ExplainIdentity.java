package io.dpdk.assistant.assignment.explanation;

import io.dpdk.assistant.platform.ids.PythonUuid5;

/** 由成绩主键派生的服务端讲解身份；普通聊天不能仅凭会话前缀获得讲解上下文。 */
public record ExplainIdentity(String sessionId, String requestId) {

    public static ExplainIdentity forSubmission(String submissionId) {
        return new ExplainIdentity("explain-" + submissionId,
                PythonUuid5.uuid5Url("assignment-explain:" + submissionId));
    }

    public static String submissionId(String sessionId, String requestId, String purpose) {
        if (!"explain".equals(purpose) || sessionId == null || !sessionId.startsWith("explain-")) {
            return null;
        }
        String submissionId = sessionId.substring("explain-".length());
        if (submissionId.isEmpty() || !forSubmission(submissionId).requestId().equals(requestId)) {
            return null;
        }
        return submissionId;
    }
}
