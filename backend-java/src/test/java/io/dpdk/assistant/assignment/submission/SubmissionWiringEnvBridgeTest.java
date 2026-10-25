package io.dpdk.assistant.assignment.submission;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockPropertySource;

/**
 * SP-04 修复验收（2026-10-25 U12）：GRADING_TIMEOUT_SEC / GRADING_MAX_CODE_BYTES /
 * GRADING_DAILY_QUOTA 冻结环境键真实消费。
 *
 * 唯一兼容优先级：dpdk.grading.* 显式配置 &gt; GRADING_* 环境变量 &gt; 源默认值。
 * 环境变量经 systemEnvironment 属性源模拟（与真实进程 env 同名解析）；真实进程
 * 级实测（quota=2 → 第三次 429 等）由 R 段启动探针另行取证。
 */
class SubmissionWiringEnvBridgeTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SubmissionWiring.class);

    private ApplicationContextRunner withEnv(Map<String, String> env) {
        Properties props = new Properties();
        props.putAll(env);
        return runner.withInitializer(ctx -> ctx.getEnvironment().getPropertySources()
                .replace("systemEnvironment",
                        new MockPropertySource("systemEnvironment", props)));
    }

    @Test
    void envVariablesConsumed() {
        withEnv(Map.of("GRADING_TIMEOUT_SEC", "77",
                        "GRADING_MAX_CODE_BYTES", "111",
                        "GRADING_DAILY_QUOTA", "3"))
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SubmissionSettings.class);
                    SubmissionSettings s = ctx.getBean(SubmissionSettings.class);
                    assertThat(s.gradingTimeoutSec()).isEqualTo(77);
                    assertThat(s.gradingMaxCodeBytes()).isEqualTo(111);
                    assertThat(s.gradingDailyQuota()).isEqualTo(3);
                });
    }

    @Test
    void missingEnvFallsBackToSourceDefaults() {
        withEnv(Map.of("GRADING_TIMEOUT_SEC", "77"))
                .run(ctx -> {
                    SubmissionSettings s = ctx.getBean(SubmissionSettings.class);
                    assertThat(s.gradingTimeoutSec()).isEqualTo(77);
                    assertThat(s.gradingMaxCodeBytes())
                            .isEqualTo(SubmissionSettings.DEFAULT_MAX_CODE_BYTES);
                    assertThat(s.gradingDailyQuota())
                            .isEqualTo(SubmissionSettings.DEFAULT_DAILY_QUOTA);
                });
    }

    @Test
    void explicitPropertyOverridesLegacyEnv() {
        withEnv(Map.of("GRADING_DAILY_QUOTA", "2"))
                .withPropertyValues("dpdk.grading.daily-quota=9")
                .run(ctx -> assertThat(ctx.getBean(SubmissionSettings.class)
                        .gradingDailyQuota()).isEqualTo(9));
    }

    @Test
    void invalidEnvValueFailsStartup() {
        withEnv(Map.of("GRADING_DAILY_QUOTA", "-1"))
                .run(ctx -> assertThat(ctx).hasFailed());
        withEnv(Map.of("GRADING_DAILY_QUOTA", "abc"))
                .run(ctx -> assertThat(ctx).hasFailed());
        withEnv(Map.of("GRADING_TIMEOUT_SEC", "0"))
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
