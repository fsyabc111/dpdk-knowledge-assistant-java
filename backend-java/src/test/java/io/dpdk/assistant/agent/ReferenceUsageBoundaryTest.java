package io.dpdk.assistant.agent;

import static org.junit.jupiter.api.Assertions.*;
import io.dpdk.assistant.agent.reference.ReferenceAnswerProvider;
import io.dpdk.assistant.agent.tools.HarnessCaller;
import io.dpdk.assistant.agent.port.AgentUsageSink;
import io.dpdk.assistant.contracts.model.HarnessReply;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Python带外reference直接用原mcp_caller；只有模型工具闭包记录operation。 */
class ReferenceUsageBoundaryTest {
    @Test void referenceIsCachedButDoesNotBecomeAModelToolUsageRow() {
        var calls = new AtomicInteger(); var operations = new AtomicInteger();
        var settings = AgentSettings.defaults();
        var harness = new HarnessCaller((tool, args, deadline) -> {
            calls.incrementAndGet();
            return new HarnessReply(HarnessReply.SubmissionPhase.SUBMITTED,
                    "{\"ok\":true,\"source\":\"synthetic-reference\"}", null);
        }, settings, new AgentUsageSink() {
            public void recordLlm(String stage, String name, io.dpdk.assistant.agent.AgentUsage usage) {}
            public void recordOperation(String kind, String stage, long duration) { operations.incrementAndGet(); }
        });
        var reference = new ReferenceAnswerProvider(harness, settings);
        assertEquals("synthetic-reference", reference.get("dns"));
        assertEquals("synthetic-reference", reference.get("dns"));
        assertEquals(1, calls.get()); assertEquals(0, operations.get());
        harness.call("list_protocols", Map.of(), 30);
        assertEquals(2, calls.get()); assertEquals(1, operations.get());
    }
}
