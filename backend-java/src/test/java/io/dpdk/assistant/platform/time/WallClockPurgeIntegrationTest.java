package io.dpdk.assistant.platform.time;

import static org.assertj.core.api.Assertions.*;
import io.dpdk.assistant.session.SessionService;
import io.dpdk.assistant.task.TaskTestHarness;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "DPDK_TEST_PG_URL", matches = ".+")
class WallClockPurgeIntegrationTest {
    @Test void purgeUsesRealMicrosecondProviderEvenWhenHostClockHasNanoseconds() throws Exception {
        var h = TaskTestHarness.create("linuxclock");
        try {
            String user = "U20260000893";
            h.seedUser(user, "[\"viewer\"]", 200);
            h.seedSession("linux-old-session", user);
            Instant raw = Instant.parse("2026-10-25T13:20:00.123456789Z");
            try (var connection = h.ds.getConnection(); var statement = connection.prepareStatement(
                    "UPDATE assistant_sessions SET updated_at=? WHERE id='linux-old-session'")) {
                statement.setObject(1, OffsetDateTime.ofInstant(raw.minus(Duration.ofDays(31)), ZoneOffset.UTC));
                statement.executeUpdate();
            }
            SessionService service = new SessionService(h.runner, new SystemClockAdapter(Clock.fixed(raw, ZoneOffset.UTC)));
            assertThat(service.purgeExpired(30, true)).containsEntry("sessions", 1).containsEntry("dry_run", true);
            assertThat(h.countRows("assistant_sessions", "id='linux-old-session'")).isEqualTo(1);
            assertThat(service.purgeExpired(30, false)).containsEntry("sessions", 1).containsEntry("dry_run", false);
            assertThat(h.countRows("assistant_sessions", "id='linux-old-session'")).isZero();
        } finally { h.taskRunner.shutdownWatchdog(); h.drop(); }
    }
}
