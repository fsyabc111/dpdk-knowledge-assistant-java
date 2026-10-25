package io.dpdk.assistant.agent.support;

import io.dpdk.assistant.contracts.model.Deadline;
import io.dpdk.assistant.contracts.model.HarnessPort;
import io.dpdk.assistant.contracts.model.HarnessReply;
import io.dpdk.assistant.retrieval.PythonJson;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内存假 harness（对位 golden_support.FakeMcp；无网络）。
 *
 * <p>payloads 按工具名分发返回值（同一请求里 grade_plugin 与
 * get_reference_answer/list_cases 等返回不同 payload）；值为 RuntimeException
 * 时对该工具抛错（模拟 harness 不可达）。未列出的工具退回 payload 旧行为。
 */
public final class FakeHarness implements HarnessPort {

    public Map<String, Object> payload;
    public RuntimeException error;
    public Map<String, Object> payloads = new LinkedHashMap<>();
    public final List<Map<String, Object>> calls = new ArrayList<>();

    public FakeHarness() {
        this.payload = Fixtures.payload("HARNESS_OK");
    }

    public FakeHarness(Map<String, Object> defaultPayload) {
        this.payload = defaultPayload;
    }

    public static FakeHarness withPayloads(Map<String, Object> payloads) {
        FakeHarness fake = new FakeHarness();
        fake.payloads = payloads;
        return fake;
    }

    public static FakeHarness unreachable(RuntimeException error) {
        FakeHarness fake = new FakeHarness();
        fake.error = error;
        return fake;
    }

    @Override
    public HarnessReply call(String tool, Map<String, Object> args, Deadline deadline) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("tool", tool);
        record.put("args", args);
        calls.add(record);
        Object outcome = payloads.get(tool);
        if (outcome instanceof RuntimeException runtimeError) {
            throw runtimeError;
        }
        if (outcome != null) {
            return new HarnessReply(HarnessReply.SubmissionPhase.SUBMITTED,
                    PythonJson.dumps(outcome, false), null);
        }
        if (error != null) {
            throw error;
        }
        return new HarnessReply(HarnessReply.SubmissionPhase.SUBMITTED,
                PythonJson.dumps(payload, false), null);
    }

    /** 除 get_task_status 外的调用名序列（对位 golden 的账本剥离断言）。 */
    public List<String> callNames() {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> call : calls) {
            names.add((String) call.get("tool"));
        }
        return names;
    }

    public List<Map<String, Object>> callsOf(String tool) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> call : calls) {
            if (tool.equals(call.get("tool"))) {
                out.add(call);
            }
        }
        return out;
    }
}
