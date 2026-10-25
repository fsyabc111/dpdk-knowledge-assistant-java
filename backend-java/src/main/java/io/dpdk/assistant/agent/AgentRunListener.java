package io.dpdk.assistant.agent;

import io.dpdk.assistant.agent.port.KbChunk;
import java.util.List;
import java.util.Map;

/**
 * Agent 运行进度事件回调（对位 answer_stream 的 SSE 事件源，传输映射归 J3-03）。
 *
 * <p>delta：planner/finalize 产出的正文（脚本化替身按整段到达；流式适配器可按
 * chunk 细化，正文+tool_call 同消息时正文不下发会被丢——W9 口径）。
 * toolStart/toolEnd：工具卡片事件；sources：kb_search 命中后的累计引用列表
 * （顺序=模型证据编号，同文件不同片段各占一个编号）。
 */
public interface AgentRunListener {

    default void onDelta(String text) {
    }

    default void onToolStart(String tool, String label) {
    }

    default void onToolEnd(Map<String, Object> summary) {
    }

    /** chunks 为累计全量列表；引用装配（engine/rank）由 J3-03 CitationBinder 承接。 */
    default void onSources(List<KbChunk> chunks) {
    }
}
