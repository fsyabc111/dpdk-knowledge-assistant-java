package io.dpdk.assistant.session;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Python datetime 渲染兼容（J2-05：sessions/messages/grading/usage 的
 * timestamptz 列经 str(row[...]) 输出的两种形态）。
 *
 * - pythonStr：str(datetime) —— 空格分隔、微数为零不输出小数、6 位微数、+00:00；
 * - iso：datetime.isoformat() —— T 分隔（配额窗口 since、purge cutoff 用）。
 *
 * 读取侧统一 OffsetDateTime → UTC：PG timestamptz 的呈现偏移取决于会话时区，
 * 冻结源测试环境为 UTC；Java 侧显式归一避免依赖连接时区（差异登记 evidence）。
 */
public final class SessionTime {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private SessionTime() {
    }

    /** str(datetime)（Python str 保留微数则恒 6 位）。 */
    public static String pythonStr(Instant instant) {
        LocalDateTime t = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder(32);
        sb.append(DATE_TIME.format(t));
        appendMicros(sb, instant);
        sb.append("+00:00");
        return sb.toString();
    }

    /** datetime.isoformat()（T 分隔；微数为零不输出小数）。 */
    public static String iso(Instant instant) {
        LocalDateTime t = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder(32);
        sb.append(DATE_TIME.format(t).replace(' ', 'T'));
        appendMicros(sb, instant);
        sb.append("+00:00");
        return sb.toString();
    }

    private static void appendMicros(StringBuilder sb, Instant instant) {
        long micros = instant.getNano() / 1_000;
        if (micros != 0) {
            if (instant.getNano() % 1_000 != 0) {
                throw new IllegalStateException("微秒精度溢出，Python datetime 不会产生该值");
            }
            sb.append('.');
            sb.append(String.format("%06d", micros));
        }
    }

    /** timestamptz 列 → UTC Instant（JDBC 时间戳按连接时区还原后统一归一）。 */
    public static Instant utc(OffsetDateTime value) {
        return value.withOffsetSameInstant(ZoneOffset.UTC).toInstant();
    }

    public static Instant utc(Timestamp value) {
        return value.toInstant();
    }
}
