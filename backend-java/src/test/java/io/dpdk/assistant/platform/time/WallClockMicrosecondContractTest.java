package io.dpdk.assistant.platform.time;

import static org.assertj.core.api.Assertions.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WallClockMicrosecondContractTest {
    static final Instant RAW = Instant.parse("2026-10-25T13:20:00.123456789Z");
    @Test void wallClockMatchesFrozenPythonDatetimeResolutionOnNanosecondHosts() {
        var clock = new SystemClockAdapter(Clock.fixed(RAW, ZoneOffset.UTC));
        assertThat(clock.nowUtc()).isEqualTo(Instant.parse("2026-10-25T13:20:00.123456Z"));
    }
    @Test void formattersStillRejectUnnormalizedNanosecondInputs() {
        assertThatThrownBy(() -> SystemClockAdapter.pythonIso(RAW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> io.dpdk.assistant.session.SessionTime.iso(RAW)).isInstanceOf(IllegalStateException.class);
    }
}
