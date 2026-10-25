package io.dpdk.assistant.admin;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Python `str(datetime)` 输出兼容（users/审计/重置申请列表的 created_at 渲染）。
 *
 * 冻结源 user_service._user_row / _list_audit_log_sync / _list_reset_requests_sync
 * 均为 `str(row["created_at"])`：空格分隔、微秒为零不输出小数、固定 +00:00 偏移；
 * 与 isoformat（approve 的 expires_at，T 分隔）不同，golden 已分别验证两种形态。
 */
final class AdminTime {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AdminTime() {
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
