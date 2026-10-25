package io.dpdk.assistant.task;

import java.util.Optional;

/**
 * probe 三态结果（miss/in_progress/terminal/replay；命中行与回放消息随行携带）。
 * contract Probe 仅 state 字段，富结果为本包公开形态（登记见交接）。
 */
public record TaskProbeOutcome(String state, Optional<TaskRecordRow> row,
                               Optional<TaskStore.MessageRecord> message) {

    public boolean isMiss() {
        return "miss".equals(state);
    }

    public boolean isReplay() {
        return "replay".equals(state);
    }

    /** probe 命中即接续（in_progress/terminal/replay 都不再重复受理）。 */
    public boolean attached() {
        return !"miss".equals(state);
    }
}
