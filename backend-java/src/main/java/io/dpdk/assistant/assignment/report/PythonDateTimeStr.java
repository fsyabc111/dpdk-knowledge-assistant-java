package io.dpdk.assistant.assignment.report;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Python `str(datetime)` 渲染（成绩 last_submitted_at，golden 冻结形态：空格分隔、
 * 微秒为零省略、+00:00；与 catalog.PythonDateTime / admin.AdminTime 同语义，
 * 包私有不可跨包复用故独立成类）。
 */
final class PythonDateTimeStr {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PythonDateTimeStr() {
    }

    static String of(Instant instant) {
        LocalDateTime t = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder(32);
        sb.append(DATE_TIME.format(t));
        long micros = instant.getNano() / 1_000;
        if (micros != 0) {
            if (instant.getNano() % 1_000 != 0) {
                throw new IllegalStateException("微秒精度溢出，Python datetime 不会产生该值");
            }
            sb.append('.');
            sb.append(String.format("%06d", micros));
        }
        sb.append("+00:00");
        return sb.toString();
    }
}
