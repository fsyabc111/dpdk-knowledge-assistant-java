package io.dpdk.assistant.assignment.submission;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 正式提交评测设置装配（集成者，P2；对齐 config.py grading_* 与默认值：
 * timeout_sec=120 / max_code_bytes=262144 / daily_quota=20，非法值由 record
 * 紧凑构造器与非数字转换失败在启动期拒绝）。
 *
 * SP-04（2026-10-25 验收修复）：GRADING_TIMEOUT_SEC / GRADING_MAX_CODE_BYTES /
 * GRADING_DAILY_QUOTA 是 P0 冻结环境键（static-inventory direct_environment_access），
 * 必须真实消费。唯一兼容优先级：dpdk.grading.* 显式配置 &gt; 旧 GRADING_* 环境变量
 * &gt; 源默认值；两类配置都未提供时取源默认。
 */
@Configuration
public class SubmissionWiring {

    @Bean
    SubmissionSettings submissionSettings(
            @Value("${dpdk.grading.timeout-sec:${GRADING_TIMEOUT_SEC:120}}") int timeoutSec,
            @Value("${dpdk.grading.max-code-bytes:${GRADING_MAX_CODE_BYTES:262144}}") int maxCodeBytes,
            @Value("${dpdk.grading.daily-quota:${GRADING_DAILY_QUOTA:20}}") int dailyQuota) {
        return new SubmissionSettings(timeoutSec, maxCodeBytes, dailyQuota);
    }
}
