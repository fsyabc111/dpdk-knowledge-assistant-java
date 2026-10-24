package io.dpdk.assistant.contracts.task;

import java.util.Optional;

/**
 * 受理端口（owner J3-01；契约 transaction-ports.json：task.AdmissionPort）。
 *
 * probe miss/in_progress/terminal/replay；hash/用户不匹配 409。
 * admit 原子账号锁+唯一请求+临时输入+用户消息+session touch+chat quota；
 * 冲突返回 attach/replay。事务内无模型/外部调用。
 */
public interface AdmissionPort {

    Probe probe(RequestIdentity identity, String subject, Optional<String> sessionId);

    AdmissionResult admit(AdmissionInput input);
}
