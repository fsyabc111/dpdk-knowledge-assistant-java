package io.dpdk.assistant.assignment.submission;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 正式提交评测设置装配（集成者，P2；对齐 config.py grading_* 与默认值）。
 * 环境变量 GRADING_TIMEOUT_SEC / GRADING_MAX_CODE_BYTES / GRADING_DAILY_QUOTA，
 * 缺省取源默认值；非法值由 record 紧凑构造器拒绝。
 */
@Configuration
public class SubmissionWiring {

    @Bean
    SubmissionSettings submissionSettings(
            @Value("${dpdk.grading.timeout-sec:120}") int timeoutSec,
            @Value("${dpdk.grading.max-code-bytes:262144}") int maxCodeBytes,
            @Value("${dpdk.grading.daily-quota:20}") int dailyQuota) {
        return new SubmissionSettings(timeoutSec, maxCodeBytes, dailyQuota);
    }
}
