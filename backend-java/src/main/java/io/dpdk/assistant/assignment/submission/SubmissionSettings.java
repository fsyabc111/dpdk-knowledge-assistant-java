package io.dpdk.assistant.assignment.submission;

/**
 * 正式提交链路的评测设置（对齐 config.py 的 grading_* 字段与默认值）：
 * grading_timeout_sec=120（≥1）、grading_max_code_bytes=262144（≥1）、
 * grading_daily_quota=20（≥0）。Spring 装配由集成者在 P2 统一接线。
 */
public record SubmissionSettings(int gradingTimeoutSec, int gradingMaxCodeBytes,
                                 int gradingDailyQuota) {

    public static final int DEFAULT_TIMEOUT_SEC = 120;
    public static final int DEFAULT_MAX_CODE_BYTES = 262144;
    public static final int DEFAULT_DAILY_QUOTA = 20;

    public static final SubmissionSettings DEFAULTS =
            new SubmissionSettings(DEFAULT_TIMEOUT_SEC, DEFAULT_MAX_CODE_BYTES, DEFAULT_DAILY_QUOTA);

    public SubmissionSettings {
        if (gradingTimeoutSec < 1) {
            throw new IllegalStateException("GRADING_TIMEOUT_SEC must be >= 1");
        }
        if (gradingMaxCodeBytes < 1) {
            throw new IllegalStateException("GRADING_MAX_CODE_BYTES must be >= 1");
        }
        if (gradingDailyQuota < 0) {
            throw new IllegalStateException("GRADING_DAILY_QUOTA must be >= 0");
        }
    }
}
