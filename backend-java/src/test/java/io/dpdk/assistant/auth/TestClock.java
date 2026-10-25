package io.dpdk.assistant.auth;

import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.platform.time.SystemClockAdapter;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * J2-01 可控时钟：墙钟与单调时钟均可设定，nextSessionIso 复刻
 * SystemClockAdapter 的进程内严格递增微秒语义（对齐 Python now_iso）。
 */
public final class TestClock implements ClockPort {

    private Instant wall = Instant.parse("2026-10-25T04:00:00Z");
    private long monoNanos;
    private Instant lastStamp = Instant.EPOCH;

    @Override
    public Instant nowUtc() {
        return wall;
    }

    @Override
    public long monotonicNanos() {
        return monoNanos;
    }

    @Override
    public synchronized String nextSessionIso() {
        Instant now = wall.truncatedTo(ChronoUnit.MICROS);
        if (!now.isAfter(lastStamp)) {
            now = lastStamp.plusNanos(1_000);
        }
        lastStamp = now;
        return SystemClockAdapter.pythonIso(now);
    }

    public void setWall(Instant instant) {
        this.wall = instant;
    }

    public void advanceWall(java.time.Duration duration) {
        this.wall = wall.plus(duration);
    }

    /** 单调时钟按秒推进（限流/锁定窗口边界测试用）。 */
    public void advanceMonoSeconds(double seconds) {
        this.monoNanos += (long) (seconds * 1_000_000_000L);
    }

    public void advanceMonoNanos(long nanos) {
        this.monoNanos += nanos;
    }
}
