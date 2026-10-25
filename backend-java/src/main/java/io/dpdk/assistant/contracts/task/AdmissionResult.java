package io.dpdk.assistant.contracts.task;

import java.util.Optional;

/** 分类与同笔提交的行/消息快照；miss 时快照为空。 */
public record AdmissionResult(String outcome, Optional<TaskRow> row, Optional<TaskMessage> message) {
}
