package io.dpdk.assistant.contracts.grading;

import io.dpdk.assistant.contracts.identity.Principal;

/**
 * 正式提交端口（owner J2-04；契约 transaction-ports.json：grading.FormalSubmissionPort）。
 *
 * 作业快照+截止/协议检查 → 独立 grading quota → MCP（事务外）→ 事务内 enabled lock
 * +成绩写入 accepted_assignment → 读取 best。同步接口，没有业务幂等键，
 * 不承诺 exactly-once/自动恢复。
 */
public interface FormalSubmissionPort {

    SubmitReceipt submit(String assignmentId, Principal actor, SubmittedSource source);
}
