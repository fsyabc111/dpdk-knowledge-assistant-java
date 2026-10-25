package io.dpdk.assistant.platform.wiring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.dpdk.assistant.assignment.submission.SubmissionSettings;
import io.dpdk.assistant.auth.AuthServerSettings;
import io.dpdk.assistant.auth.AuthSettingsProvider;
import io.dpdk.assistant.auth.TestClock;
import io.dpdk.assistant.contracts.identity.AccountWriteGuard;
import io.dpdk.assistant.contracts.platform.ClockPort;
import io.dpdk.assistant.contracts.platform.TransactionRunner;
import io.dpdk.assistant.contracts.usage.QuotaParticipant;
import io.dpdk.assistant.session.SessionService;
import io.dpdk.assistant.task.TaskSettings;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockPropertySource;

/** 运行真实 TaskWiring/@Value；占位依赖只隔离配置测试，不执行任何业务或数据库操作。 */
class TaskSettingsBridgeTest {
    private ApplicationContextRunner withEnv(Map<String, String> env) {
        Properties properties = new Properties();
        properties.putAll(env);
        return new ApplicationContextRunner().withUserConfiguration(TaskWiring.class)
                .withBean(SubmissionSettings.class, () -> SubmissionSettings.DEFAULTS)
                .withBean(ClockPort.class, TestClock::new)
                .withBean(TransactionRunner.class, () -> mock(TransactionRunner.class))
                .withBean(SessionService.class, () -> mock(SessionService.class))
                .withBean(AccountWriteGuard.class, () -> mock(AccountWriteGuard.class))
                .withBean(QuotaParticipant.class, () -> mock(QuotaParticipant.class))
                .withBean(AuthSettingsProvider.class, () -> AuthSettingsProvider.fixed(
                        AuthServerSettings.of("account", "{}", 7, 8, 4, 10000)))
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().replace(
                        "systemEnvironment", new MockPropertySource("systemEnvironment", properties)));
    }

    @Test
    void consumesLegacyEnvironmentThroughActualSpringPlaceholderResolution() {
        withEnv(Map.of("RAG_TASK_MAX_SECONDS", "12.5", "RAG_QUEUE_TIMEOUT_SEC", "2.25",
                        "RAG_MAX_ACTIVE_REQUESTS", "3", "RAG_MAX_WAITING_REQUESTS", "0",
                        "RAG_TASK_INPUT_TTL_HOURS", "2"))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    TaskSettings settings = ctx.getBean(TaskSettings.class);
                    assertThat(settings.ragTaskMaxSeconds()).isEqualTo(Duration.ofMillis(12500));
                    assertThat(settings.admissionQueueTimeout()).isEqualTo(Duration.ofMillis(2250));
                    assertThat(settings.maxActiveRequests()).isEqualTo(3);
                    assertThat(settings.maxWaitingRequests()).isZero();
                    assertThat(settings.inputTtl()).isEqualTo(Duration.ofHours(2));
                });
    }

    @Test
    void explicitDpdkPropertiesOverrideAllLegacyEnvironmentValues() {
        withEnv(Map.of("RAG_TASK_MAX_SECONDS", "12", "RAG_QUEUE_TIMEOUT_SEC", "2",
                        "RAG_MAX_ACTIVE_REQUESTS", "3", "RAG_MAX_WAITING_REQUESTS", "4",
                        "RAG_TASK_INPUT_TTL_HOURS", "2"))
                .withPropertyValues("dpdk.task.max-seconds=22", "dpdk.task.queue-timeout-sec=9",
                        "dpdk.task.max-active=7", "dpdk.task.max-waiting=8", "dpdk.task.input-ttl-hours=4")
                .run(ctx -> {
                    TaskSettings settings = ctx.getBean(TaskSettings.class);
                    assertThat(settings.ragTaskMaxSeconds()).isEqualTo(Duration.ofSeconds(22));
                    assertThat(settings.admissionQueueTimeout()).isEqualTo(Duration.ofSeconds(9));
                    assertThat(settings.maxActiveRequests()).isEqualTo(7);
                    assertThat(settings.maxWaitingRequests()).isEqualTo(8);
                    assertThat(settings.inputTtl()).isEqualTo(Duration.ofHours(4));
                });
    }

    @Test
    void missingEnvironmentUsesFrozenDefaults() {
        withEnv(Map.of()).run(ctx -> assertThat(ctx.getBean(TaskSettings.class))
                .isEqualTo(TaskSettings.defaults()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "-1", "0", "abc"})
    void invalidLegacyWallTimeFailsBeforeAnyBackgroundStarts(String value) {
        withEnv(Map.of("RAG_TASK_MAX_SECONDS", value)).run(ctx -> assertThat(ctx).hasFailed());
        withEnv(Map.of("RAG_QUEUE_TIMEOUT_SEC", value)).run(ctx -> assertThat(ctx).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RAG_MAX_ACTIVE_REQUESTS=0", "RAG_MAX_ACTIVE_REQUESTS=-1",
            "RAG_MAX_WAITING_REQUESTS=-1", "RAG_TASK_INPUT_TTL_HOURS=0", "RAG_TASK_INPUT_TTL_HOURS=-1"})
    void invalidLegacyLimitsFailStartup(String property) {
        String[] pair = property.split("=", 2);
        withEnv(Map.of(pair[0], pair[1])).run(ctx -> assertThat(ctx).hasFailed());
    }
}
