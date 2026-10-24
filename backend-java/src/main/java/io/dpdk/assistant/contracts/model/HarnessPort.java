package io.dpdk.assistant.contracts.model;

import java.time.Instant;
import java.util.Map;

/**
 * harness/MCP 端口（owner J1-02；契约 transaction-ports.json：model.HarnessPort）。
 *
 * 单次传输建连/initialize 可重试 2 次；call_tool 开始后失败不重试（提交阶段失败
 * 返回 UNKNOWN，不盲重发）。业务恢复同 job_id 重投属 COMP-003 待决。
 */
public interface HarnessPort {

    HarnessReply call(String tool, Map<String, Object> args, Deadline deadline);
}
