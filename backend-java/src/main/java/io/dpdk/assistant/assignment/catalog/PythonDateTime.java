package io.dpdk.assistant.assignment.catalog;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Python `str(datetime)` 输出兼容（assignments 各响应的 deadline/created_at/
 * updated_at 及成绩 last_submitted_at 渲染）。
 *
 * 冻结源 `_iso` 对 DB 返回的 datetime 在 oracle 打点环境下走 `str()` 分支：空格
 * 分隔、微秒为零不输出小数、固定 +00:00；golden（POST /api/assignments 等）已按
 * 该形态冻结，与 J2-02 admin.AdminTime 同一语义（本包无法复用其包私有实现）。
 */
final class PythonDateTime {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private PythonDateTime() {
    }

    static String pythonStr(Instant instant) {
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
