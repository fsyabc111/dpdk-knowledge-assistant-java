package io.dpdk.assistant.knowledge.workflow;

import io.dpdk.assistant.contracts.identity.Principal;

/** 原题复验接缝：实现必须复用产品检索、生成和显式 judge，并受共享配额与受理门禁保护。 */
public interface OriginalQuestionVerifier {
    /** 源顺序：每分钟限流、每日配额先于问题读取与 admission 占槽。 */
    void prepareAdmission(Principal principal);

    Result verify(Principal principal, String question, String context);

    record Result(String status, String answer, String releaseId) {}
}
