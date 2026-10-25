package io.dpdk.assistant.platform.time;

import io.dpdk.assistant.contracts.platform.ClockPort;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ClockPort 默认实现。
 *
 * nextSessionIso：进程内严格递增（同一微秒或回拨时 +1µs），输出对齐 Python isoformat：
 * "yyyy-MM-ddTHH:mm:ss[.ffffff]+00:00"，微秒为零不输出小数部分。
 */
@Component
public class SystemClockAdapter implements ClockPort {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final java.time.Clock wallClock;
    private Instant lastSessionStamp = Instant.EPOCH;

    @Autowired
    public SystemClockAdapter() {
        this(java.time.Clock.systemUTC());
    }

    public SystemClockAdapter(java.time.Clock wallClock) {
        this.wallClock = wallClock;
    }

    @Override
    public Instant nowUtc() {
        return wallClock.instant();
    }

    @Override
    public long monotonicNanos() {
        return System.nanoTime();
    }

    @Override
    public synchronized String nextSessionIso() {
        Instant now = wallClock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        if (!now.isAfter(lastSessionStamp)) {
            now = lastSessionStamp.plusNanos(1_000);
        }
        lastSessionStamp = now;
        return pythonIso(now);
    }

    /** Python datetime.isoformat 兼容输出（仅限 UTC 零偏移场景）。 */
    public static String pythonIso(Instant instant) {
        LocalDateTime t = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder(32);
        sb.append(DATE_TIME.format(t));
        long micros = instant.getNano() / 1_000;
        if (micros != 0) {
            sb.append('.');
            if (instant.getNano() % 1_000 != 0) {
                throw new IllegalStateException("微秒精度溢出，Python datetime 不会产生该值");
            }
            sb.append(String.format("%06d", micros));
        }
        sb.append("+00:00");
        return sb.toString();
    }
}
