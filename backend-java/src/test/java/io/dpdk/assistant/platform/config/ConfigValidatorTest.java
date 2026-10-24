package io.dpdk.assistant.platform.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** fail-closed 配置校验单元测试（J1-01 验收：配置错误必须失败、绝不静默回退）。 */
class ConfigValidatorTest {

    private static AppProperties valid() {
        return new AppProperties(
                new AppProperties.DataSourceSpec(
                        "jdbc:postgresql://127.0.0.1:55481/p1_java_candidate", "p1_java", "x"),
                new AppProperties.DatabaseIdentitySpec(
                        "p1_java_candidate", "p1_java", "java-migration-p1-test",
                        "/repo/.migration-artifacts/p1/pgdata"));
    }

    @Test
    void missingUrlFails() {
        var props = new AppProperties(
                new AppProperties.DataSourceSpec("", "u", "p"), valid().databaseIdentity());
        assertThatThrownBy(() -> ConfigValidator.validate(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("url 缺失");
    }

    @Test
    void missingIdentityFails() {
        var props = new AppProperties(valid().datasource(), null);
        assertThatThrownBy(() -> ConfigValidator.validate(props))
                .hasMessageContaining("database-identity 未配置");
    }

    @Test
    void nonPostgresProtocolRejected() {
        var props = new AppProperties(
                new AppProperties.DataSourceSpec("jdbc:mysql://127.0.0.1:3306/x", "u", "p"),
                valid().databaseIdentity());
        assertThatThrownBy(() -> ConfigValidator.validate(props))
                .hasMessageContaining("jdbc:postgresql://");
    }

    @Test
    void nonLoopbackHostRejected() {
        var props = new AppProperties(
                new AppProperties.DataSourceSpec("jdbc:postgresql://10.0.0.5:55481/x", "u", "p"),
                valid().databaseIdentity());
        assertThatThrownBy(() -> ConfigValidator.validate(props))
                .hasMessageContaining("回环地址");
    }

    @Test
    void defaultPort5432Rejected() {
        var props = new AppProperties(
                new AppProperties.DataSourceSpec("jdbc:postgresql://127.0.0.1:5432/postgres", "u", "p"),
                valid().databaseIdentity());
        assertThatThrownBy(() -> ConfigValidator.validate(props))
                .hasMessageContaining("5432");
    }

    @Test
    void implicitPortRejected() {
        var props = new AppProperties(
                new AppProperties.DataSourceSpec("jdbc:postgresql://127.0.0.1/x", "u", "p"),
                valid().databaseIdentity());
        assertThatThrownBy(() -> ConfigValidator.validate(props))
                .hasMessageContaining("显式指定端口");
    }

    @Test
    void validConfigPasses() {
        assertThatCode(() -> ConfigValidator.validate(valid())).doesNotThrowAnyException();
    }
}
