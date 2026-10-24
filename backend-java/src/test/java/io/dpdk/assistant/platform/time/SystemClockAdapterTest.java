package io.dpdk.assistant.platform.time;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Clock;
import org.junit.jupiter.api.Test;

/** 时钟格式与进程内微秒递增语义（契约 identity-and-data.md 时间节）。 */
class SystemClockAdapterTest {

    private static Clock fixed(Instant i) {
        return Clock.fixed(i, ZoneOffset.UTC);
    }

    @Test
    void pythonIsoWithoutFraction() {
        assertThat(SystemClockAdapter.pythonIso(Instant.parse("2026-10-25T08:00:00Z")))
                .isEqualTo("2026-10-25T08:00:00+00:00");
    }

    @Test
    void pythonIsoWithMicros() {
        assertThat(SystemClockAdapter.pythonIso(Instant.parse("2026-10-25T08:00:00.123456Z")))
                .isEqualTo("2026-10-25T08:00:00.123456+00:00");
    }

    @Test
    void pythonIsoMilliPrecisionKeepsSixDigits() {
        // Python isoformat 不截成 3 位：毫秒值也输出 6 位（补零）。
        assertThat(SystemClockAdapter.pythonIso(Instant.parse("2026-10-25T08:00:00.250Z")))
                .isEqualTo("2026-10-25T08:00:00.250000+00:00");
    }

    @Test
    void nextSessionIsoStrictlyIncreasing() {
        SystemClockAdapter adapter = new SystemClockAdapter(fixed(Instant.parse("2026-10-25T08:00:00.000001Z")));
        String first = adapter.nextSessionIso();
        String second = adapter.nextSessionIso();
        assertThat(first).isEqualTo("2026-10-25T08:00:00.000001+00:00");
        // 同一微秒再取：+1µs，不允许相等或回退。
        assertThat(second).isEqualTo("2026-10-25T08:00:00.000002+00:00");
    }

    @Test
    void zeroMicroOmitsFraction() {
        SystemClockAdapter adapter = new SystemClockAdapter(fixed(Instant.parse("2026-10-25T08:00:00Z")));
        assertThat(adapter.nextSessionIso()).isEqualTo("2026-10-25T08:00:00+00:00");
        assertThat(adapter.nextSessionIso()).isEqualTo("2026-10-25T08:00:00.000001+00:00");
    }
}
