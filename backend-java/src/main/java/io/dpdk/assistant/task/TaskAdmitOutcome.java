package io.dpdk.assistant.task;

import java.util.Optional;

/**
 * 受理结果（created/attached/replayed 三分类；row/message 随行携带供消费方
 * 组装响应，无需二次查询）。
 *
 * - created：本次事务赢得唯一键，任务/输入/消息/配额已同笔提交；
 * - attached：唯一键败方或 probe 命中 in_progress/terminal——按既有任务接续
 *   （冲突接续不重扣配额、不重复落用户消息）；
 * - replay：已完成请求的回放（行 + 正文消息）。
 */
public record TaskAdmitOutcome(String outcome, Optional<TaskRecordRow> row,
                               Optional<TaskStore.MessageRecord> message) {

    public static final String CREATED = "created";
    public static final String ATTACHED = "attached";
    public static final String REPLAYED = "replayed";

    public boolean created() {
        return CREATED.equals(outcome);
    }

    public boolean replayed() {
        return REPLAYED.equals(outcome);
    }

    public String taskId() {
        return row.map(TaskRecordRow::id).orElse(null);
    }
}
