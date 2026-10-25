package io.dpdk.assistant.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.dpdk.assistant.agent.AgentSettings;
import io.dpdk.assistant.agent.port.AgentUsageSink;
import io.dpdk.assistant.contracts.model.Deadline;
import io.dpdk.assistant.contracts.model.HarnessPort;
import io.dpdk.assistant.contracts.model.HarnessReply;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * harness/MCP 调用的唯一出口（对位 tools.py 的 call_mcp 闭包）。
 *
 * <p>消费 J1-02 的 {@link HarnessPort}；把 HarnessReply 的规范化 JSON 解析回
 * Map（契约：成功=工具结果；失败={"ok":false,"error":...,"detail":...}）。
 * 传输层异常（建连失败等）向上抛——调用方各自决定降级形态，与 Python
 * mcp_caller 抛异常一致（run_tools 兜底为 tool_exception）。
 * finally 记一次非 token 用量：超时/失败同样占用外部算力，账上不能当没发生。
 */
public final class HarnessCaller {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HarnessPort harness;
    private final AgentUsageSink usage;

    public HarnessCaller(HarnessPort harness, AgentSettings settings, AgentUsageSink usage) {
        this.harness = harness;
        this.usage = usage;
    }

    /** 状态查询用短超时：只是接续探测，不该把请求耗在 grading_timeout 上。 */
    public static int statusTimeoutSeconds(AgentSettings settings) {
        return Math.min(settings.gradingTimeoutSec(), 30);
    }

    public Map<String, Object> call(String tool, Map<String, Object> args, int timeoutSeconds) {
        long started = System.nanoTime();
        try {
            Deadline deadline = new Deadline(Instant.now().plusSeconds(timeoutSeconds),
                    System.nanoTime() + timeoutSeconds * 1_000_000_000L);
            HarnessReply reply = harness.call(tool, args, deadline);
            Map<String, Object> payload = parse(reply.payloadJson());
            if (reply.error() != null && "harness_deadline_exceeded".equals(reply.error())) {
                if ("grade_plugin".equals(tool)) {
                    // Python asyncio.wait_for 超时口径：账本行保持 submitted，恢复扫描可接续。
                    payload.put("ok", false);
                    payload.put("error", "grading_timeout");
                    payload.put("detail", "评测超过 " + timeoutSeconds + " 秒未完成");
                }
            }
            return payload;
        } finally {
            if (usage != null) {
                usage.recordOperation(AgentUsageSink.KIND_MCP, tool,
                        (System.nanoTime() - started) / 1_000_000L);
            }
        }
    }

    /** payloadJson 恒为规范化 JSON（契约）；损坏/空形态返回 {"ok": false,...}。 */
    static Map<String, Object> parse(String payloadJson) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (payloadJson != null && !payloadJson.isBlank()) {
            try {
                Object parsed = JSON.readValue(payloadJson, new TypeReference<Object>() {
                });
                if (parsed instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        out.put(String.valueOf(e.getKey()), e.getValue());
                    }
                    return out;
                }
            } catch (Exception ignored) {
                // 落入下方统一坏载荷形态
            }
        }
        out.put("ok", false);
        out.put("error", "bad_payload");
        out.put("detail", "harness 返回形态异常");
        return out;
    }
}
