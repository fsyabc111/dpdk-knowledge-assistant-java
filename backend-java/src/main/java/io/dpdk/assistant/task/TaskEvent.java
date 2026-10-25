package io.dpdk.assistant.task;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务过程事件（回放日志与订阅扇出的最小单位）。
 *
 * name 与 SSE 帧名同位（start/status/tool_start/tool_end/sources/delta/done）；
 * dataJson 由 {@link TaskEventStreams} 渲染（ensure_ascii=False 语义的 UTF-8 JSON）。
 */
public record TaskEvent(String name, Map<String, Object> data) {

    public static TaskEvent of(String name, Map<String, Object> data) {
        return new TaskEvent(name, new LinkedHashMap<>(data));
    }
}
