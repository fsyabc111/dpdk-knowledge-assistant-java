package io.dpdk.assistant.acceptance.load;

import static org.assertj.core.api.Assertions.*;
import io.dpdk.assistant.contracts.model.Deadline;
import java.time.Instant;
import java.nio.file.Path;
import org.junit.jupiter.api.io.TempDir;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 测试替身仍遵守真实外部job生命周期；不启动Spring/HTTP/PG。 */
class CapacityHarnessStatusTest {
    @Test void newJobIsUnknownUntilActuallySubmitted() throws Exception {
        var harness = CapacityServer.fixedHarness(new CapacityServer.RuntimeState(null));
        String job = "pure-status-" + UUID.randomUUID();
        var deadline = new Deadline(Instant.now().plusSeconds(10), System.nanoTime() + 10_000_000_000L);
        Map<?, ?> fresh = CapacityServer.JSON.readValue(
                harness.call("get_task_status", Map.of("job_id", job), deadline).payloadJson(), Map.class);
        assertThat(fresh.get("status")).isEqualTo("unknown");
        assertThat(fresh.containsKey("result")).isFalse();
        harness.call("grade_plugin", Map.of("job_id", job), deadline);
        Map<?, ?> submitted = CapacityServer.JSON.readValue(
                harness.call("get_task_status", Map.of("job_id", job), deadline).payloadJson(), Map.class);
        assertThat(submitted.get("status")).isEqualTo("finished");
        assertThat(submitted.get("result")).isInstanceOf(Map.class);
    }

    @Test void newRuntimeReadsSubmittedJobsFromDurableExternalLedger(@TempDir Path directory) throws Exception {
        Path ledger = directory.resolve("external.jsonl");
        String job = "submitted-job";
        var deadline = new Deadline(Instant.now().plusSeconds(10), System.nanoTime() + 10_000_000_000L);
        var producer = CapacityServer.fixedHarness(new CapacityServer.RuntimeState(ledger));
        producer.call("get_task_status", Map.of("job_id", "queried-only-job"), deadline);
        producer.call("grade_plugin", Map.of("job_id", job), deadline);
        var rebuilt = CapacityServer.fixedHarness(new CapacityServer.RuntimeState(ledger));
        assertThat(CapacityServer.JSON.readValue(rebuilt.call("get_task_status", Map.of("job_id", job), deadline)
                .payloadJson(), Map.class).get("status")).isEqualTo("finished");
        assertThat(CapacityServer.JSON.readValue(rebuilt.call("get_task_status", Map.of("job_id", "queried-only-job"), deadline)
                .payloadJson(), Map.class).get("status")).isEqualTo("unknown");
    }

    @Test void recoveryModesApplyOnlyToActuallySubmittedJobs(@TempDir Path directory) throws Exception {
        var state = new CapacityServer.RuntimeState(directory.resolve("external.jsonl"));
        var harness = CapacityServer.fixedHarness(state);
        var deadline = new Deadline(Instant.now().plusSeconds(10), System.nanoTime() + 10_000_000_000L);
        state.status = "query_failed";
        assertThat(CapacityServer.JSON.readValue(harness.call("get_task_status", Map.of("job_id", "new"), deadline)
                .payloadJson(), Map.class).get("status")).isEqualTo("unknown");
        harness.call("grade_plugin", Map.of("job_id", "submitted"), deadline);
        assertThatThrownBy(() -> harness.call("get_task_status", Map.of("job_id", "submitted"), deadline))
                .isInstanceOf(IllegalStateException.class).hasMessage("合成状态查询失败");
        for (String mode : new String[]{"unknown", "running", "finished"}) {
            state.status = mode;
            Map<?, ?> reply = CapacityServer.JSON.readValue(harness.call("get_task_status", Map.of("job_id", "submitted"), deadline)
                    .payloadJson(), Map.class);
            assertThat(reply.get("status")).isEqualTo(mode);
            assertThat(reply.containsKey("result")).isEqualTo("finished".equals(mode));
        }
    }
}
