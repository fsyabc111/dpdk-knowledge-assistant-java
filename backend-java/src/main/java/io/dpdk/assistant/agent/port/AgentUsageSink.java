package io.dpdk.assistant.agent.port;

import io.dpdk.assistant.agent.AgentUsage;

/**
 * 请求级用量 sink（对位 core.usage.UsageRecorder 的 agent 消费面）。
 *
 * <p>recordLlm：planner/finalize 每次模型调用记一条；失败也留一条
 * （usage=null → unknown：请求已经发出去，「没记」不等于「没花」）。
 * recordOperation：harness/MCP 的非 token 用量，只记次数与耗时，显式不计费
 * （单价在外部服务里，编一个费率比不计量更糟）。传 null 表示完全透明
 * （与 Python usage=None 口径一致）。
 */
public interface AgentUsageSink {

    String KIND_MCP = "mcp";

    void recordLlm(String stage, String modelName, AgentUsage usage);

    void recordOperation(String kind, String stage, long durationMs);
}
