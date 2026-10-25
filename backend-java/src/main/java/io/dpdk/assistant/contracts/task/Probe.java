package io.dpdk.assistant.contracts.task;

import java.util.Optional;

/** 分类与同笔提交的行/消息快照；miss 时快照为空。 */
public record Probe(String state, Optional<TaskRow> row, Optional<TaskMessage> message) {
}
